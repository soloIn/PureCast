package com.solo.purecast.util

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PermissionHelperTest {

    private class CascadingFakeActivity(
        private val failActions: Set<String> = emptySet(),
        private val keepWindowFocusOnActions: Set<String> = emptySet()
    ) : Activity() {
        val launchedIntents = mutableListOf<Intent>()
        private var focused = true

        init {
            attachBaseContext(RuntimeEnvironment.getApplication())
        }

        override fun startActivity(intent: Intent) {
            launchedIntents += intent
            val action = intent.action ?: ""
            if (action in failActions) {
                throw ActivityNotFoundException("blocked: $action")
            }
            focused = action in keepWindowFocusOnActions
        }

        override fun hasWindowFocus(): Boolean = focused
    }

    @Test
    fun `openOverlaySettings unwraps ContextWrapper and cascades past exceptions and empty-shell focus`() {
        val customActivity = CascadingFakeActivity(
            failActions = setOf(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            keepWindowFocusOnActions = setOf(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
        )
        // 使用包装了 Activity 的 ContextWrapper，验证 findActivity() 正确解包并生效 350ms 空壳降级
        val wrappedCustom = object : ContextWrapper(customActivity) {}

        PermissionHelper.openOverlaySettings(wrappedCustom)
        // 1 & 2: ACTION_MANAGE_OVERLAY_PERMISSION (有/无 URI) 抛异常立即级联到 ACTION_MANAGE_APPLICATIONS_SETTINGS
        assertEquals(3, customActivity.launchedIntents.size)
        assertEquals(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS, customActivity.launchedIntents[2].action)

        // 350ms 后因 hasWindowFocus() 仍为 true（空壳页），自动降级到 ACTION_APPLICATION_DETAILS_SETTINGS 并失焦停止
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(4, customActivity.launchedIntents.size)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, customActivity.launchedIntents[3].action)
    }

    @Test
    fun `openBatteryOptimizationSettings uses TV leanback path and shows fallback toast when all fail`() {
        val appContext = RuntimeEnvironment.getApplication()
        shadowOf(appContext.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)

        val failingActivity = CascadingFakeActivity(
            failActions = setOf(
                Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Settings.ACTION_SETTINGS
            )
        )
        val wrapped = object : ContextWrapper(failingActivity) {}

        PermissionHelper.openBatteryOptimizationSettings(wrapped)
        // TV Leanback 跳过 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS，直接从 MANAGE_APPLICATIONS_SETTINGS 开始尝试
        assertEquals(
            listOf(
                Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS,
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Settings.ACTION_SETTINGS
            ),
            failingActivity.launchedIntents.map { it.action }
        )
        assertTrue(ShadowToast.getTextOfLatestToast().contains("电池白名单"))
    }

    @Test
    fun `rapid repeated calls invalidate previous cascade token and non-Activity adds NEW_TASK`() {
        val firstActivity = CascadingFakeActivity(
            keepWindowFocusOnActions = setOf(Intent.ACTION_AUTO_REVOKE_PERMISSIONS)
        )
        val wrappedFirst = object : ContextWrapper(firstActivity) {}

        PermissionHelper.openAutoRevokeSettings(wrappedFirst)
        assertEquals(1, firstActivity.launchedIntents.size)

        // 350ms 内再次点击新跳转，前一次 350ms 延迟回调应被 token 作废，不再降级
        val secondActivity = CascadingFakeActivity()
        val wrappedSecond = object : ContextWrapper(secondActivity) {}
        PermissionHelper.openAppDetailsSettings(wrappedSecond)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals("前一次级联已被新 token 作废，不应继续降级", 1, firstActivity.launchedIntents.size)
        assertEquals(1, secondActivity.launchedIntents.size)

        // 非 Activity 上下文自动追加 FLAG_ACTIVITY_NEW_TASK
        val appContext = RuntimeEnvironment.getApplication()
        PermissionHelper.openAppDetailsSettings(appContext)
        val started = shadowOf(appContext).nextStartedActivity
        assertTrue((started.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }
}
