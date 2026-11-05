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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.tv.material3.ExperimentalTvMaterial3Api
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
 * 严格遵循 10-Foot 电视大屏与 Apple TV HIG 工业设计规范：
 * 1. 100% 实心不透明背景 (AppPalette.background)，彻底解决半透明穿透播放界面的问题；
 * 2. 完整支持自动模式 (跟随系统 uiMode)、深色模式与浅色模式切换与持久化；
 * 3. 彻底重构设置行排版：采用垂直分层 (SettingTieredRow) 与紧凑行 (SettingInlineRow)，
 *    彻底根治因多按钮横塞导致的恶性 6 行逐字换行与空白大卡片 Bug；
 * 4. 严守 TV Safe Area 安全区域：顶部 padding 提升至 76dp，消除任何电视 Overscan 削头截断。
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

    // 遥控器返回键平滑退回播放主页
    BackHandler {
        onDismiss()
    }

    var selectedCategory by remember { mutableStateOf(SettingsCategory.APPEARANCE_AND_GENERAL) }

    // 1. 设备广播名与外观状态
    var currentThemeMode by remember { mutableStateOf(AppPalette.mode) }
    var friendlyName by remember { mutableStateOf(settingsRepo.getFriendlyName()) }
    var isEditingCustomName by remember { mutableStateOf(false) }
    var customNameInput by remember { mutableStateOf(friendlyName) }
    var isAirPlayEnabled by remember { mutableStateOf(settingsRepo.isAirPlayEnabled()) }
    var isUpnpEnabled by remember { mutableStateOf(settingsRepo.isUpnpEnabled()) }
    var idleTimeoutSeconds by remember { mutableIntStateOf(settingsRepo.getIdleTimeoutSeconds()) }

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

    val defaultFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(120)
        try {
            defaultFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    // 100% 实心不透明背景，杜绝半透明穿透播放界面的问题
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
            // 左侧：固定 300dp 纵向分类导航栏
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
                    Button(
                        onClick = { selectedCategory = cat },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (index == 0) Modifier.focusRequester(defaultFocusRequester) else Modifier)
                            .onFocusChanged { if (it.isFocused) selectedCategory = cat },
                        scale = ButtonDefaults.scale(scale = 1.0f, focusedScale = 1.05f),
                        shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                        colors = ButtonDefaults.colors(
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
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // 返回大屏播放主页按钮
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    scale = ButtonDefaults.scale(scale = 1.0f, focusedScale = 1.05f),
                    shape = ButtonDefaults.shape(RoundedCornerShape(12.dp)),
                    colors = ButtonDefaults.colors(
                        containerColor = AppPalette.surfaceSecondary,
                        focusedContainerColor = AppPalette.accent,
                        contentColor = AppPalette.primaryText,
                        focusedContentColor = Color.White
                    )
                ) {
                    Text(
                        text = "← 返回播放页面",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
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
                    .verticalScroll(scrollState),
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
                        // 1. 界面外观深浅模式 (提供 自动/深色/浅色 3 档)
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

                        // 2. 局域网广播名称 (垂直分层布局，彻底杜绝横向挤压与逐字折行)
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
                                        Button(
                                            onClick = {
                                                val t = customNameInput.trim()
                                                if (t.isNotEmpty()) {
                                                    friendlyName = t
                                                    settingsRepo.setFriendlyName(t)
                                                    PlaybackService.instance?.updateFriendlyName(t)
                                                    isEditingCustomName = false
                                                    Toast.makeText(context, "已保存广播名为: $t", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            shape = ButtonDefaults.shape(RoundedCornerShape(8.dp)),
                                            colors = ButtonDefaults.colors(
                                                containerColor = AppPalette.accent,
                                                focusedContainerColor = Color.White,
                                                contentColor = Color.White,
                                                focusedContentColor = Color.Black
                                            )
                                        ) {
                                            Text(text = "保存", fontSize = 13.sp)
                                        }
                                        Button(
                                            onClick = { isEditingCustomName = false },
                                            shape = ButtonDefaults.shape(RoundedCornerShape(8.dp)),
                                            colors = ButtonDefaults.colors(
                                                containerColor = AppPalette.surfaceSecondary,
                                                focusedContainerColor = Color.White,
                                                contentColor = AppPalette.primaryText,
                                                focusedContentColor = Color.Black
                                            )
                                        ) {
                                            Text(text = "取消", fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    SettingsCategory.USB_DAC -> {
                        SettingsSection(title = "USB DAC 硬件独占接管") {
                            // 紧凑行：声卡端点与独占状态
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
                                    Button(
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
                                        },
                                        scale = ButtonDefaults.scale(scale = 1.0f, focusedScale = 1.08f),
                                        shape = ButtonDefaults.shape(RoundedCornerShape(10.dp)),
                                        colors = ButtonDefaults.colors(
                                            containerColor = if (audioState.isExclusive) AppPalette.surfaceSecondary else AppPalette.accent,
                                            focusedContainerColor = Color.White,
                                            contentColor = if (audioState.isExclusive) AppPalette.primaryText else Color.White,
                                            focusedContentColor = Color.Black
                                        )
                                    ) {
                                        Text(
                                            text = if (audioState.isExclusive) "🔄 让渡声卡" else "⚡ 独占接管",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }

                            SettingDivider()

                            // 分层行：空闲自动让渡时机 (解决 4 个按钮挤爆文字导致 6 行换行的 Bug)
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
 * 连续圆角卡片包装内部所有配置行，消除碎片化卡片与遮挡
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
 * 上层：100% 宽度展示标题与副标说明，保证长说明文字自然横向排版，永不折叠挤压；
 * 下层：放置分段选择器或按钮组，彻底解决横向空间拥挤与空白卡片撑爆 Bug。
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
 * 适用于右侧仅有单个紧凑操作控件（如开关或按钮）的条目
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
 * 💡 分段式选项按钮 (SegmentOptionButton)
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SegmentOptionButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        scale = ButtonDefaults.scale(scale = 1.0f, focusedScale = 1.08f),
        shape = ButtonDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ButtonDefaults.colors(
            containerColor = if (isSelected) AppPalette.accent else AppPalette.surfaceSecondary,
            focusedContainerColor = Color.White,
            contentColor = if (isSelected) Color.White else AppPalette.primaryText,
            focusedContentColor = Color.Black
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(border = androidx.compose.foundation.BorderStroke(2.dp, Color.White))
        )
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * 💡 开关切换按钮 (ToggleSwitchButton)
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ToggleSwitchButton(
    isEnabled: Boolean,
    onToggle: () -> Unit
) {
    Button(
        onClick = onToggle,
        scale = ButtonDefaults.scale(scale = 1.0f, focusedScale = 1.08f),
        shape = ButtonDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ButtonDefaults.colors(
            containerColor = if (isEnabled) AppPalette.success else AppPalette.surfaceSecondary,
            focusedContainerColor = Color.White,
            contentColor = if (isEnabled) Color.White else AppPalette.secondaryText,
            focusedContentColor = Color.Black
        ),
        border = ButtonDefaults.border(
            focusedBorder = Border(border = androidx.compose.foundation.BorderStroke(2.dp, Color.White))
        )
    ) {
        Text(
            text = if (isEnabled) "已启用" else "已禁用",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
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
