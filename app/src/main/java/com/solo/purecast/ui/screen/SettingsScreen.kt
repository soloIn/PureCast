package com.solo.purecast.ui.screen

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.solo.purecast.data.SettingsRepository
import com.solo.purecast.model.AudioPathState
import com.solo.purecast.model.StreamProtocol
import com.solo.purecast.core.nativebridge.NativeAudioEngine
import com.solo.purecast.service.PlaybackService
import com.solo.purecast.ui.theme.AppPalette
import com.solo.purecast.ui.theme.ThemeMode
import com.solo.purecast.util.Logger
import kotlinx.coroutines.delay
import java.util.Locale

private const val TAG = "SettingsScreen"

/**
 * 💡 5 大分类导航枚举
 */
enum class SettingsCategory(val title: String, val subtitle: String) {
    APPEARANCE_AND_GENERAL("外观与常规", "界面主题模式与局域网广播名称"),
    USB_DAC("USB DAC 独占", "底层 usbfs 独占接管与空闲让渡策略"),
    PROTOCOLS("接收协议引擎", "独立开关 AirPlay 1 RAOP 与 UPnP 渲染引擎"),
    HARDWARE_MONITOR("硬件与时钟监控", "SPSC 无锁环形缓冲与 UAC2 显式时钟反馈闭环"),
    HEALTH_CHECK("系统权限健康", "USB 权限、后台电池白名单与保活自检")
}

/**
 * 💡 PureCast 全屏双栏 Apple TV 系统级设置界面 (SettingsScreen)
 * 严格按照 Apple TV HIG 与 Android TV 双栏导航规范重构：
 * 1. 严格层级 Back 导航路由：
 *    - 焦点在右侧配置项时按 Back 键：交还焦点给左侧当前选中分类项，不退出页面；
 *    - 正在编辑输入框时按 Back 键：退出编辑模式，不退出页面；
 *    - 只有焦点在左侧分类导航时按 Back 键：平滑退出设置返回播放页；
 * 2. 彻底移除左下角多余突兀的长条按钮；
 * 3. 规范焦点与深/浅模式高亮：
 *    - 深色模式获焦：纯白底 (Color.White) + 纯黑字；
 *    - 浅色模式获焦：纯黑底 (Color(0xFF1C1C1E)) + 纯白字；
 *    - 彻底消灭浅色模式下变黄绿色的恶性视觉割裂；
 * 4. 自适应内边距与换行防护：分段按钮文字横向完全包裹，杜绝“✏️ 自定义”折行。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(
    audioState: AudioPathState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }

    var selectedCategory by remember { mutableStateOf(SettingsCategory.APPEARANCE_AND_GENERAL) }
    var isFocusInRightContent by remember { mutableStateOf(false) }

    // 左侧导航栏各项的 FocusRequester
    val navFocusRequesters = remember {
        Array(SettingsCategory.entries.size) { FocusRequester() }
    }

    // 1. 设备广播名与外观状态
    var currentThemeMode by remember { mutableStateOf(AppPalette.mode) }
    var friendlyName by remember { mutableStateOf(settingsRepo.getFriendlyName()) }
    var isEditingCustomName by remember { mutableStateOf(false) }
    var customNameInput by remember { mutableStateOf(friendlyName) }
    var isAirPlayEnabled by remember { mutableStateOf(settingsRepo.isAirPlayEnabled()) }
    var isUpnpEnabled by remember { mutableStateOf(settingsRepo.isUpnpEnabled()) }
    var idleTimeoutSeconds by remember { mutableIntStateOf(settingsRepo.getIdleTimeoutSeconds()) }

    // 💡 严谨的 Apple TV 双栏 Back 键层级路由
    BackHandler(enabled = isFocusInRightContent || isEditingCustomName) {
        if (isEditingCustomName) {
            isEditingCustomName = false
        } else if (isFocusInRightContent) {
            // 右侧按 Back：将焦点请求回左侧当前分类项 (附带首项回退兜底，杜绝焦点丢失)
            try {
                navFocusRequesters[selectedCategory.ordinal].requestFocus()
            } catch (_: Exception) {
                try {
                    navFocusRequesters.firstOrNull()?.requestFocus()
                } catch (_: Exception) {}
            }
            isFocusInRightContent = false
        }
    }

    // 当焦点已经在左侧时，按 Back 退出设置页
    BackHandler(enabled = !isFocusInRightContent && !isEditingCustomName) {
        onDismiss()
    }

    // 2. 硬件缓冲区与时钟动态监控状态 (每秒轮询一次)
    var bufferCapacity by remember { mutableIntStateOf(65536) }
    var bufferAvailableRead by remember { mutableIntStateOf(0) }
    var libusbVer by remember { mutableStateOf("1.0.27") }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                bufferCapacity = NativeAudioEngine.getBufferCapacity().coerceAtLeast(1)
                bufferAvailableRead = NativeAudioEngine.getAvailableRead()
                libusbVer = NativeAudioEngine.getLibusbVersion()
            } catch (e: Exception) {
                Logger.ui.w(TAG, "获取底层驱动信息异常", e)
            }
            delay(1000)
        }
    }

    // 3. 权限自检计算
    val isUsbGranted = remember(audioState) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        usbManager.deviceList.values.any { device ->
            val isAudio = device.deviceClass == UsbConstants.USB_CLASS_AUDIO ||
                    (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
            isAudio && usbManager.hasPermission(device)
        }
    }
    val isRecordAudioGranted = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }
    val isIgnoringBattery = remember {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }
    val isOverlayGranted = remember {
        android.provider.Settings.canDrawOverlays(context)
    }

    // 页面进入时聚焦左侧第 1 项
    LaunchedEffect(Unit) {
        delay(120)
        try {
            navFocusRequesters[0].requestFocus()
        } catch (_: Exception) {}
    }

    // 100% 实心不透明背景
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppPalette.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 72.dp, end = 72.dp, top = 76.dp, bottom = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp)
        ) {
            // ==========================================
            // 左侧：纯粹的固定 300dp 分类导航栏 (彻底去除底部突兀长条)
            // ==========================================
            Column(
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "⚙ 设置与硬件",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppPalette.primaryText,
                    modifier = Modifier.padding(bottom = 16.dp, start = 8.dp)
                )

                SettingsCategory.entries.forEachIndexed { index, cat ->
                    val isSelected = selectedCategory == cat
                    var isCatFocused by remember { mutableStateOf(false) }

                    Surface(
                        onClick = { selectedCategory = cat },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(navFocusRequesters[index])
                            .onFocusChanged {
                                isCatFocused = it.isFocused
                                if (it.isFocused) {
                                    selectedCategory = cat
                                    isFocusInRightContent = false
                                }
                            },
                        scale = ClickableSurfaceDefaults.scale(scale = 1.0f, focusedScale = 1.05f),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (isSelected) AppPalette.surfaceSecondary else Color.Transparent,
                            focusedContainerColor = AppPalette.accent,
                            contentColor = if (isSelected) AppPalette.primaryText else AppPalette.secondaryText,
                            focusedContentColor = Color.White
                        )
                    ) {
                        Text(
                            text = cat.title,
                            fontSize = 15.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
            }

            // ==========================================
            // 右侧：遵循 HIG 分组列表的配置区域
            // ==========================================
            val scrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(scrollState)
                    .onFocusChanged {
                        if (it.hasFocus) {
                            isFocusInRightContent = true
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // 分类标题与副标
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = selectedCategory.title,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = AppPalette.primaryText
                    )
                    Text(
                        text = selectedCategory.subtitle,
                        fontSize = 13.sp,
                        color = AppPalette.secondaryText
                    )
                }

                when (selectedCategory) {
                    SettingsCategory.APPEARANCE_AND_GENERAL -> {
                        // 1. 界面外观深浅模式
                        SettingsSection(title = "界面主题模式") {
                            SettingTieredRow(
                                title = "外观色彩模式",
                                subtitle = "支持跟随系统动态自适应或强制深浅主题"
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    SegmentOptionButton(
                                        label = "⚡️ 自动 (跟随系统)",
                                        isSelected = currentThemeMode == ThemeMode.AUTO,
                                        onClick = {
                                            currentThemeMode = ThemeMode.AUTO
                                            AppPalette.setThemeMode(context, ThemeMode.AUTO)
                                        }
                                    )
                                    SegmentOptionButton(
                                        label = "🌙 深色模式",
                                        isSelected = currentThemeMode == ThemeMode.DARK,
                                        onClick = {
                                            currentThemeMode = ThemeMode.DARK
                                            AppPalette.setThemeMode(context, ThemeMode.DARK)
                                        }
                                    )
                                    SegmentOptionButton(
                                        label = "☀️ 浅色模式",
                                        isSelected = currentThemeMode == ThemeMode.LIGHT,
                                        onClick = {
                                            currentThemeMode = ThemeMode.LIGHT
                                            AppPalette.setThemeMode(context, ThemeMode.LIGHT)
                                        }
                                    )
                                }
                            }
                        }

                        // 2. 局域网广播名称
                        SettingsSection(title = "局域网广播设备标识") {
                            SettingTieredRow(
                                title = "设备广播发现名称",
                                subtitle = "当前网络标识: \"$friendlyName\" (用于 AirPlay 1 与 UPnP 广播)"
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("PureCast TV", "客厅 Hi-Fi", "影音室 TV").forEach { preset ->
                                        val isCur = friendlyName == preset && !isEditingCustomName
                                        SegmentOptionButton(
                                            label = preset,
                                            isSelected = isCur,
                                            onClick = {
                                                isEditingCustomName = false
                                                friendlyName = preset
                                                settingsRepo.setFriendlyName(preset)
                                                PlaybackService.instance?.updateFriendlyName(preset)
                                                Toast.makeText(context, "已更新设备名为: $preset", Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    }
                                    SegmentOptionButton(
                                        label = "✏️ 自定义",
                                        isSelected = isEditingCustomName,
                                        onClick = {
                                            customNameInput = friendlyName
                                            isEditingCustomName = !isEditingCustomName
                                        }
                                    )
                                }
                            }

                            if (isEditingCustomName) {
                                SettingDivider()
                                SettingTieredRow(
                                    title = "编辑自定义设备名",
                                    subtitle = "输入新的设备发现名称并确认保存"
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(260.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(AppPalette.surfaceSecondary)
                                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                        ) {
                                            BasicTextField(
                                                value = customNameInput,
                                                onValueChange = { customNameInput = it },
                                                singleLine = true,
                                                textStyle = TextStyle(color = AppPalette.primaryText, fontSize = 14.sp),
                                                cursorBrush = SolidColor(AppPalette.primaryText),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                        SegmentOptionButton(
                                            label = "保存",
                                            isSelected = true,
                                            onClick = {
                                                val t = customNameInput.trim()
                                                if (t.isNotEmpty()) {
                                                    friendlyName = t
                                                    settingsRepo.setFriendlyName(t)
                                                    PlaybackService.instance?.updateFriendlyName(t)
                                                    isEditingCustomName = false
                                                    Toast.makeText(context, "已保存广播名为: $t", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        )
                                        SegmentOptionButton(
                                            label = "取消",
                                            isSelected = false,
                                            onClick = { isEditingCustomName = false }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SettingsCategory.USB_DAC -> {
                        SettingsSection(title = "USB DAC 硬件独占接管") {
                            SettingInlineRow(
                                title = "声卡端点与驱动状态",
                                subtitle = if (audioState.isExclusive) "已解绑 Linux snd-usb-audio 内核驱动，直通 UAC2 硬件 (绕过 48kHz SRC)" else "声卡处于待机让渡或系统共享状态"
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = audioState.dacName,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (audioState.isExclusive) AppPalette.success else AppPalette.warning
                                    )
                                    SegmentOptionButton(
                                        label = if (audioState.isExclusive) "🔄 让渡声卡" else "⚡ 独占接管",
                                        isSelected = !audioState.isExclusive,
                                        onClick = {
                                            if (audioState.isExclusive) {
                                                PlaybackService.instance?.pausePlayback()
                                                Toast.makeText(context, "已触发让渡，声卡归还系统", Toast.LENGTH_SHORT).show()
                                            } else {
                                                if (context is Activity) {
                                                    PlaybackService.instance?.requestUsbPermission(context)
                                                }
                                                PlaybackService.instance?.resumePlayback()
                                                Toast.makeText(context, "正在尝试接管 USB DAC...", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                }
                            }

                            SettingDivider()

                            SettingTieredRow(
                                title = "空闲自动让渡时机",
                                subtitle = "停止播放后，等待指定秒数自动解除声卡独占并归还系统"
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(Pair(5, "5秒"), Pair(10, "10秒 (推荐)"), Pair(30, "30秒"), Pair(0, "常驻独占")).forEach { (sec, label) ->
                                        val isSelected = idleTimeoutSeconds == sec
                                        SegmentOptionButton(
                                            label = label,
                                            isSelected = isSelected,
                                            onClick = {
                                                idleTimeoutSeconds = sec
                                                settingsRepo.setIdleTimeoutSeconds(sec)
                                                PlaybackService.instance?.setIdleTimeoutSeconds(sec)
                                                Toast.makeText(context, "已设置让渡超时为: $label", Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    SettingsCategory.PROTOCOLS -> {
                        SettingsSection(title = "本地网络音频流服务") {
                            SettingInlineRow(
                                title = "AirPlay 1 (AirTunes / RAOP)",
                                subtitle = "支持 iOS / macOS / iTunes 纯音频低延迟推流，ALAC 44.1kHz 源码直通"
                            ) {
                                ToggleSwitchButton(
                                    isEnabled = isAirPlayEnabled,
                                    onToggle = {
                                        val target = !isAirPlayEnabled
                                        isAirPlayEnabled = target
                                        PlaybackService.instance?.setAirPlayEnabled(target)
                                    }
                                )
                            }

                            SettingDivider()

                            SettingInlineRow(
                                title = "UPnP / DLNA (MediaRenderer 2.0)",
                                subtitle = "支持 QQ音乐、网易云等 App 投播，支持 192kHz / 24bit Hi-Res 源码直通"
                            ) {
                                ToggleSwitchButton(
                                    isEnabled = isUpnpEnabled,
                                    onToggle = {
                                        val target = !isUpnpEnabled
                                        isUpnpEnabled = target
                                        PlaybackService.instance?.setUpnpEnabled(target)
                                    }
                                )
                            }
                        }
                    }

                    SettingsCategory.HARDWARE_MONITOR -> {
                        SettingsSection(title = "SPSC 无锁环形缓冲与时钟仪表盘") {
                            val sampleRateKhz = String.format(Locale.US, "%.1f", audioState.sampleRate / 1000.0)
                            val formatSpec = if (audioState.protocol != StreamProtocol.NONE && audioState.sampleRate > 0) {
                                "$sampleRateKhz kHz / ${audioState.bitDepth}-bit (${audioState.channels} 声道)"
                            } else {
                                "待机无推流"
                            }

                            val bufferUsagePercent = if (bufferCapacity > 0) {
                                (bufferAvailableRead * 100L / bufferCapacity).toInt()
                            } else 0

                            MetricDataRow(label = "当前实时采样率与位深", value = formatSpec)
                            SettingDivider()
                            MetricDataRow(label = "SPSC 环形缓冲区", value = "$bufferAvailableRead 采样 / $bufferCapacity 采样 ($bufferUsagePercent% 水位)")
                            SettingDivider()
                            MetricDataRow(label = "UAC 物理时钟锁定", value = if (audioState.isExclusive) "UAC2 Explicit Feedback 闭环物理时钟 (SOF 纳秒级同步)" else "让渡共享时钟")
                            SettingDivider()
                            MetricDataRow(label = "底层驱动与内核通信", value = "libusb-$libusbVer (usbfs 根权限包装)")
                        }
                    }

                    SettingsCategory.HEALTH_CHECK -> {
                        SettingsSection(title = "Android 权限与系统保活自检") {
                            PermissionCheckRow(label = "USB 硬件直接访问权限", isGranted = isUsbGranted)
                            SettingDivider()
                            PermissionCheckRow(label = "后台无限制与电池优化白名单", isGranted = isIgnoringBattery)
                            SettingDivider()
                            PermissionCheckRow(label = "核心录音权限 (RECORD_AUDIO)", isGranted = isRecordAudioGranted)
                            SettingDivider()
                            PermissionCheckRow(label = "悬浮窗与前台拉起权限 (OVERLAY)", isGranted = isOverlayGranted)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 💡 标准 HIG 分组容器 (SettingsSection)
 */
@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = AppPalette.secondaryText,
            modifier = Modifier.padding(start = 12.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(AppPalette.surface)
                .border(1.dp, AppPalette.border.copy(alpha = 0.4f), RoundedCornerShape(14.dp)),
            content = content
        )
    }
}

/**
 * 💡 标准 HIG 分隔细线
 */
@Composable
private fun SettingDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(0.5.dp)
            .background(AppPalette.divider)
    )
}

/**
 * 💡 垂直分层配置行 (SettingTieredRow)
 */
@Composable
private fun SettingTieredRow(
    title: String,
    subtitle: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppPalette.primaryText
            )
            Text(
                text = subtitle,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = AppPalette.secondaryText
            )
        }
        content()
    }
}

/**
 * 💡 水平紧凑配置行 (SettingInlineRow)
 */
@Composable
private fun SettingInlineRow(
    title: String,
    subtitle: String,
    action: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 20.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppPalette.primaryText
            )
            Text(
                text = subtitle,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = AppPalette.secondaryText
            )
        }
        action()
    }
}

/**
 * 💡 严格适配深/浅模式的 Apple TV 分段按钮 (SegmentOptionButton)
 * 采用 Surface + 规范焦点色彩：
 * - 深色模式获焦：纯白底 (Color.White) + 纯黑字；
 * - 浅色模式获焦：纯黑底 (Color(0xFF1C1C1E)) + 纯白字；
 * - 杜绝任何荧光黄绿混乱色块；
 * - 文本自适应包裹宽度 (wrapContentWidth)，杜绝“✏️ 自定义”折行。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SegmentOptionButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val isDark = AppPalette.isDark
    val normalBg = if (isSelected) AppPalette.accent else AppPalette.surfaceSecondary
    val normalContent = if (isSelected) Color.White else AppPalette.primaryText

    // 严谨适配深/浅模式的获焦底色与前景色
    val focusedBg = if (isDark) Color.White else Color(0xFF1C1C1E)
    val focusedContent = if (isDark) Color(0xFF1C1C1E) else Color.White

    val shape = RoundedCornerShape(10.dp)
    val noBorder = Border(BorderStroke(0.dp, Color.Transparent))
    val focusedBorder = Border(BorderStroke(2.dp, if (isDark) Color.White else Color(0xFF1C1C1E)))

    Surface(
        onClick = onClick,
        modifier = Modifier.wrapContentWidth(),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(scale = 1.0f, focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = normalBg,
            focusedContainerColor = focusedBg,
            contentColor = normalContent,
            focusedContentColor = focusedContent
        ),
        border = ClickableSurfaceDefaults.border(
            border = noBorder,
            focusedBorder = focusedBorder
        ),
        glow = ClickableSurfaceDefaults.glow(
            glow = Glow.None,
            focusedGlow = Glow(elevationColor = focusedBg.copy(alpha = 0.35f), elevation = 6.dp)
        )
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

/**
 * 💡 严格适配深/浅模式的开关切换按钮 (ToggleSwitchButton)
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ToggleSwitchButton(
    isEnabled: Boolean,
    onToggle: () -> Unit
) {
    val isDark = AppPalette.isDark
    val normalBg = if (isEnabled) AppPalette.success else AppPalette.surfaceSecondary
    val normalContent = if (isEnabled) Color.White else AppPalette.secondaryText

    val focusedBg = if (isDark) Color.White else Color(0xFF1C1C1E)
    val focusedContent = if (isDark) Color(0xFF1C1C1E) else Color.White

    val shape = RoundedCornerShape(10.dp)
    val noBorder = Border(BorderStroke(0.dp, Color.Transparent))
    val focusedBorder = Border(BorderStroke(2.dp, if (isDark) Color.White else Color(0xFF1C1C1E)))

    Surface(
        onClick = onToggle,
        modifier = Modifier.wrapContentWidth(),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(scale = 1.0f, focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = normalBg,
            focusedContainerColor = focusedBg,
            contentColor = normalContent,
            focusedContentColor = focusedContent
        ),
        border = ClickableSurfaceDefaults.border(
            border = noBorder,
            focusedBorder = focusedBorder
        ),
        glow = ClickableSurfaceDefaults.glow(
            glow = Glow.None,
            focusedGlow = Glow(elevationColor = focusedBg.copy(alpha = 0.35f), elevation = 6.dp)
        )
    ) {
        Text(
            text = if (isEnabled) "已启用" else "已禁用",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

/**
 * 💡 辅助指标展示行
 */
@Composable
private fun MetricDataRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 14.sp, color = AppPalette.secondaryText)
        Text(text = value, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = AppPalette.primaryText)
    }
}

/**
 * 💡 辅助权限检查行
 */
@Composable
private fun PermissionCheckRow(
    label: String,
    isGranted: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 14.sp, color = AppPalette.primaryText)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (isGranted) AppPalette.success else AppPalette.warning)
            )
            Text(
                text = if (isGranted) "已就绪" else "待授权",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (isGranted) AppPalette.success else AppPalette.warning
            )
        }
    }
}
