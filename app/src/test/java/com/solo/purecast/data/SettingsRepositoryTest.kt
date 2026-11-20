package com.solo.purecast.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.solo.purecast.ui.background.FluidQuality
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 💡 SettingsRepository 配置持久化与 StateFlow 响应式状态流完整单元测试
 * 覆盖：
 * 1. 纯净首发默认值及 StateFlow 契约 (_idleTimeoutFlow, _onboardingCompletedFlow, friendlyNameFlow, fluidQualityFlow 等)；
 * 2. 预填充 SharedPreferences 恢复与 StateFlow 初始值；
 * 3. 各 Setter 更新、持久化写入与 StateFlow 同步；
 * 4. 友好名称 trim 规范化与空串回退默认值；
 * 5. StateFlow 真实协程流式订阅与响应式触发。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {

    private lateinit var app: Application
    private lateinit var repo: SettingsRepository

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // 清理 SharedPreferences，保证测试用例独立性
        app.getSharedPreferences("purecast_settings", Context.MODE_PRIVATE).edit().clear().commit()
        repo = SettingsRepository(app)
    }

    @Test
    fun testDefaultStateFlowInitialValues() {
        // 验证无任何预存配置时的默认初始值契约
        assertEquals(SettingsRepository.DEFAULT_FRIENDLY_NAME, repo.getFriendlyName())
        assertEquals(SettingsRepository.DEFAULT_FRIENDLY_NAME, repo.friendlyNameFlow.value)

        assertEquals(SettingsRepository.DEFAULT_IDLE_TIMEOUT, repo.getIdleTimeoutSeconds())
        assertEquals(SettingsRepository.DEFAULT_IDLE_TIMEOUT, repo.idleTimeoutFlow.value)

        assertTrue(repo.isAirPlayEnabled())
        assertTrue(repo.airPlayEnabledFlow.value)

        assertTrue(repo.isUpnpEnabled())
        assertTrue(repo.upnpEnabledFlow.value)

        assertFalse(repo.isOnboardingCompleted())
        assertFalse(repo.onboardingCompletedFlow.value)

        assertEquals(FluidQuality.STANDARD, repo.getFluidQuality())
        assertEquals(FluidQuality.STANDARD, repo.fluidQualityFlow.value)
    }

    @Test
    fun testPreExistingSharedPreferencesInitialization() {
        // 预写入持久化数据
        app.getSharedPreferences("purecast_settings", Context.MODE_PRIVATE).edit()
            .putString("upnp_friendly_name", "Pre-set TV")
            .putInt("usb_idle_timeout_seconds", 45)
            .putBoolean("airplay_enabled", false)
            .putBoolean("upnp_enabled", false)
            .putBoolean("onboarding_completed", true)
            .putString("fluid_quality", FluidQuality.HIGH_QUALITY.name)
            .commit()

        val restoredRepo = SettingsRepository(app)

        assertEquals("Pre-set TV", restoredRepo.getFriendlyName())
        assertEquals("Pre-set TV", restoredRepo.friendlyNameFlow.value)

        assertEquals(45, restoredRepo.getIdleTimeoutSeconds())
        assertEquals(45, restoredRepo.idleTimeoutFlow.value)

        assertFalse(restoredRepo.isAirPlayEnabled())
        assertFalse(restoredRepo.airPlayEnabledFlow.value)

        assertFalse(restoredRepo.isUpnpEnabled())
        assertFalse(restoredRepo.upnpEnabledFlow.value)

        assertTrue(restoredRepo.isOnboardingCompleted())
        assertTrue(restoredRepo.onboardingCompletedFlow.value)

        assertEquals(FluidQuality.HIGH_QUALITY, restoredRepo.getFluidQuality())
        assertEquals(FluidQuality.HIGH_QUALITY, restoredRepo.fluidQualityFlow.value)
    }

    @Test
    fun testIdleTimeoutFlowAndSetter() {
        assertEquals(SettingsRepository.DEFAULT_IDLE_TIMEOUT, repo.getIdleTimeoutSeconds())
        assertEquals(SettingsRepository.DEFAULT_IDLE_TIMEOUT, repo.idleTimeoutFlow.value)

        repo.setIdleTimeoutSeconds(30)
        assertEquals(30, repo.getIdleTimeoutSeconds())
        assertEquals(30, repo.idleTimeoutFlow.value)

        // 验证磁盘 SharedPreferences 同步写入
        val diskVal = app.getSharedPreferences("purecast_settings", Context.MODE_PRIVATE)
            .getInt("usb_idle_timeout_seconds", -1)
        assertEquals(30, diskVal)
    }

    @Test
    fun testOnboardingCompletedFlowAndSetter() {
        assertFalse(repo.isOnboardingCompleted())
        assertFalse(repo.onboardingCompletedFlow.value)

        repo.setOnboardingCompleted(true)
        assertTrue(repo.isOnboardingCompleted())
        assertTrue(repo.onboardingCompletedFlow.value)

        val diskVal = app.getSharedPreferences("purecast_settings", Context.MODE_PRIVATE)
            .getBoolean("onboarding_completed", false)
        assertTrue(diskVal)
    }

    @Test
    fun testFriendlyNameFlowAndSetterWithTrimming() {
        assertEquals(SettingsRepository.DEFAULT_FRIENDLY_NAME, repo.getFriendlyName())
        assertEquals(SettingsRepository.DEFAULT_FRIENDLY_NAME, repo.friendlyNameFlow.value)

        // 正常重命名
        repo.setFriendlyName("PureCast Living Room")
        assertEquals("PureCast Living Room", repo.getFriendlyName())
        assertEquals("PureCast Living Room", repo.friendlyNameFlow.value)

        // 包含首尾空白字符自动 Trim
        repo.setFriendlyName("  PureCast Study  ")
        assertEquals("PureCast Study", repo.getFriendlyName())
        assertEquals("PureCast Study", repo.friendlyNameFlow.value)

        // 空串或纯空白回退至默认名称
        repo.setFriendlyName("   ")
        assertEquals(SettingsRepository.DEFAULT_FRIENDLY_NAME, repo.getFriendlyName())
        assertEquals(SettingsRepository.DEFAULT_FRIENDLY_NAME, repo.friendlyNameFlow.value)

        // 验证磁盘 SharedPreferences 同步写入
        val diskVal = app.getSharedPreferences("purecast_settings", Context.MODE_PRIVATE)
            .getString("upnp_friendly_name", null)
        assertEquals(SettingsRepository.DEFAULT_FRIENDLY_NAME, diskVal)
    }

    @Test
    fun testAirPlayAndUpnpFlowsAndSetters() {
        assertTrue(repo.isAirPlayEnabled())
        assertTrue(repo.airPlayEnabledFlow.value)

        repo.setAirPlayEnabled(false)
        assertFalse(repo.isAirPlayEnabled())
        assertFalse(repo.airPlayEnabledFlow.value)

        val diskAirPlay = app.getSharedPreferences("purecast_settings", Context.MODE_PRIVATE)
            .getBoolean("airplay_enabled", true)
        assertFalse(diskAirPlay)

        assertTrue(repo.isUpnpEnabled())
        assertTrue(repo.upnpEnabledFlow.value)

        repo.setUpnpEnabled(false)
        assertFalse(repo.isUpnpEnabled())
        assertFalse(repo.upnpEnabledFlow.value)

        val diskUpnp = app.getSharedPreferences("purecast_settings", Context.MODE_PRIVATE)
            .getBoolean("upnp_enabled", true)
        assertFalse(diskUpnp)
    }

    @Test
    fun testFluidQualityFlowAndSetter() {
        assertEquals(FluidQuality.STANDARD, repo.getFluidQuality())
        assertEquals(FluidQuality.STANDARD, repo.fluidQualityFlow.value)

        repo.setFluidQuality(FluidQuality.HIGH_QUALITY)
        assertEquals(FluidQuality.HIGH_QUALITY, repo.getFluidQuality())
        assertEquals(FluidQuality.HIGH_QUALITY, repo.fluidQualityFlow.value)

        repo.setFluidQuality(FluidQuality.OFF)
        assertEquals(FluidQuality.OFF, repo.getFluidQuality())
        assertEquals(FluidQuality.OFF, repo.fluidQualityFlow.value)

        val diskQuality = app.getSharedPreferences("purecast_settings", Context.MODE_PRIVATE)
            .getString("fluid_quality", null)
        assertEquals(FluidQuality.OFF.name, diskQuality)
    }

    @Test
    fun testReactiveFlowEmissions() = runTest {
        val collectedFriendlyNames = mutableListOf<String>()
        val collectedTimeouts = mutableListOf<Int>()
        val collectedOnboarding = mutableListOf<Boolean>()
        val collectedFluidQuality = mutableListOf<FluidQuality>()

        val job1 = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.friendlyNameFlow.toList(collectedFriendlyNames)
        }
        val job2 = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.idleTimeoutFlow.toList(collectedTimeouts)
        }
        val job3 = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.onboardingCompletedFlow.toList(collectedOnboarding)
        }
        val job4 = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.fluidQualityFlow.toList(collectedFluidQuality)
        }

        // 验证初始发射
        assertEquals(listOf(SettingsRepository.DEFAULT_FRIENDLY_NAME), collectedFriendlyNames)
        assertEquals(listOf(SettingsRepository.DEFAULT_IDLE_TIMEOUT), collectedTimeouts)
        assertEquals(listOf(false), collectedOnboarding)
        assertEquals(listOf(FluidQuality.STANDARD), collectedFluidQuality)

        // 触发变更
        repo.setFriendlyName("New Reactive Name")
        repo.setIdleTimeoutSeconds(20)
        repo.setOnboardingCompleted(true)
        repo.setFluidQuality(FluidQuality.HIGH_QUALITY)

        // 验证响应式数据流实时收集到更新
        assertEquals(listOf(SettingsRepository.DEFAULT_FRIENDLY_NAME, "New Reactive Name"), collectedFriendlyNames)
        assertEquals(listOf(SettingsRepository.DEFAULT_IDLE_TIMEOUT, 20), collectedTimeouts)
        assertEquals(listOf(false, true), collectedOnboarding)
        assertEquals(listOf(FluidQuality.STANDARD, FluidQuality.HIGH_QUALITY), collectedFluidQuality)

        job1.cancel()
        job2.cancel()
        job3.cancel()
        job4.cancel()
    }

    @Test
    fun testPresetFriendlyNames() {
        assertTrue(SettingsRepository.PRESET_FRIENDLY_NAMES.isNotEmpty())
        assertTrue(SettingsRepository.PRESET_FRIENDLY_NAMES.contains(SettingsRepository.DEFAULT_FRIENDLY_NAME))
    }

    @Test
    fun testGetInstanceReturnsSharedSingleton() {
        SettingsRepository.resetInstanceForTest()
        val first = SettingsRepository.getInstance(app)
        val second = SettingsRepository.getInstance(app)
        assertTrue(first === second)

        first.setFluidQuality(FluidQuality.HIGH_QUALITY)
        assertEquals(FluidQuality.HIGH_QUALITY, second.fluidQualityFlow.value)

        SettingsRepository.resetInstanceForTest()
    }
}
