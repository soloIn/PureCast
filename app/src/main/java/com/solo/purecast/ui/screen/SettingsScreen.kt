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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import com.solo.purecast.ui.background.FluidQuality
import com.solo.purecast.ui.components.formatSampleRate
import com.solo.purecast.ui.theme.AppPalette
import com.solo.purecast.ui.theme.ThemeMode
import com.solo.purecast.util.Logger
import com.solo.purecast.util.PermissionHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
@OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
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

    // 💡 为每个分类在右侧的首个可获焦项绑定确定性的 FocusRequester (解决跨栏导航错位)
    val categoryFirstFocusRequesters = remember {
        mapOf(
            SettingsCategory.APPEARANCE_AND_GENERAL to FocusRequester(),
            SettingsCategory.USB_DAC to FocusRequester(),
            SettingsCategory.PROTOCOLS to FocusRequester(),
            SettingsCategory.HEALTH_CHECK to FocusRequester()
        )
    }

    val coroutineScope = rememberCoroutineScope()

    // 1. 设备广播名与外观状态 (统一使用 collectAsState 读取内存 Flow，消除设置页首帧磁盘同步读取)
    var currentThemeMode by remember { mutableStateOf(AppPalette.mode) }
    val friendlyName by settingsRepo.friendlyNameFlow.collectAsState()
    var isEditingCustomName by remember { mutableStateOf(false) }
    var customNameInput by remember { mutableStateOf(friendlyName) }
    val currentFluidQuality by settingsRepo.fluidQualityFlow.collectAsState()
    val customNameFocusRequester = remember { FocusRequester() }
    val customEditButtonFocusRequester = remember { FocusRequester() }
    var isTextFieldFocused by remember { mutableStateOf(false) }
    val isAirPlayEnabled by settingsRepo.airPlayEnabledFlow.collectAsState()
    val isUpnpEnabled by settingsRepo.upnpEnabledFlow.collectAsState()
    val idleTimeoutSeconds by settingsRepo.idleTimeoutFlow.collectAsState()

    // 💡 退出自定义名称编辑；若 restoreFocusToButton == true 则请求焦点至“✏️ 自定义”按钮，若已导航至左侧则严禁抢夺焦点 (Finding 2)
    fun exitCustomNameEditing(restoreFocusToButton: Boolean = true) {
        isEditingCustomName = false
        if (restoreFocusToButton) {
            coroutineScope.launch {
                delay(50)
                try {
                    customEditButtonFocusRequester.requestFocus()
                } catch (e: Exception) {
                    Logger.ui.w(TAG, "退出自定义名称编辑后请求焦点异常", e)
                }
            }
        }
    }

    // 💡 自定义输入框展开时，主动将遥控器焦点请求至 BasicTextField，避免落入焦点陷阱
    LaunchedEffect(isEditingCustomName) {
        if (isEditingCustomName) {
            delay(150)
            try {
                customNameFocusRequester.requestFocus()
            } catch (e: Exception) {
                Logger.ui.w(TAG, "展开输入框请求焦点异常", e)
            }
        }
    }

    // 💡 严谨的 Apple TV 双栏 Back 键层级路由
    BackHandler(enabled = isFocusInRightContent || isEditingCustomName) {
        if (isEditingCustomName) {
            exitCustomNameEditing()
        } else if (isFocusInRightContent) {
            // 右侧按 Back：将焦点请求回左侧当前分类项 (附带首项回退兜底，杜绝焦点丢失)
            try {
                navFocusRequesters[selectedCategory.ordinal].requestFocus()
            } catch (e: Exception) {
                Logger.ui.w(TAG, "返回键请求导航分类焦点异常，尝试首项兜底", e)
                try {
                    navFocusRequesters.firstOrNull()?.requestFocus()
                } catch (ex: Exception) {
                    Logger.ui.w(TAG, "导航分类首项兜底焦点请求异常", ex)
                }
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

    fun checkUsbPermission(ctx: Context): Boolean {
        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        return usbManager.deviceList.values.any { device ->
            val isAudio = device.deviceClass == UsbConstants.USB_CLASS_AUDIO ||
                (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
            isAudio && usbManager.hasPermission(device)
        }
    }

    var isUsbGranted by remember { mutableStateOf(checkUsbPermission(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                bufferCapacity = NativeAudioEngine.getBufferCapacity().coerceAtLeast(1)
                bufferAvailableRead = NativeAudioEngine.getAvailableRead()
                libusbVer = NativeAudioEngine.getLibusbVersion()
                isUsbGranted = checkUsbPermission(context)
            } catch (e: Exception) {
                Logger.ui.w(TAG, "获取底层驱动信息异常", e)
            }
            delay(1000)
        }
    }

    // 3. 权限自检计算
    var isRecordAudioGranted by remember {
        mutableStateOf(PermissionHelper.isRecordAudioGranted(context))
    }
    var isIgnoringBattery by remember {
        mutableStateOf(PermissionHelper.isBatteryOptimizationIgnored(context))
    }
    var isOverlayGranted by remember {
        mutableStateOf(PermissionHelper.isOverlayGranted(context))
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isUsbGranted = checkUsbPermission(context)
                isRecordAudioGranted = PermissionHelper.isRecordAudioGranted(context)
                isIgnoringBattery = PermissionHelper.isBatteryOptimizationIgnored(context)
                isOverlayGranted = PermissionHelper.isOverlayGranted(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // 页面进入时聚焦左侧第 1 项
    LaunchedEffect(Unit) {
        delay(120)
        try {
            navFocusRequesters[0].requestFocus()
        } catch (e: Exception) {
            Logger.ui.w(TAG, "页面进入聚焦首项异常", e)
        }
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
                            .focusProperties {
                                val target = categoryFirstFocusRequesters[cat]
                                if (target != null) {
                                    right = target
                                } else {
                                    // 💡 针对 HARDWARE_MONITOR 等纯监控展示分类（无右侧可获焦项），禁止向右导航失焦
                                    right = FocusRequester.Cancel
                                }
                            }
                            .onFocusChanged {
                                isCatFocused = it.isFocused
                                if (it.isFocused) {
                                    selectedCategory = cat
                                    isFocusInRightContent = false
                                    if (isEditingCustomName) {
                                        exitCustomNameEditing(restoreFocusToButton = false)
                                    }
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
            LaunchedEffect(selectedCategory) {
                scrollState.scrollTo(0)
                if (isEditingCustomName) {
                    exitCustomNameEditing(restoreFocusToButton = false)
                }
            }
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
                                        focusRequester = categoryFirstFocusRequesters[SettingsCategory.APPEARANCE_AND_GENERAL],
                                        leftNavFocusRequester = navFocusRequesters[selectedCategory.ordinal],
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
                                    val presets = listOf("PureCast TV", "客厅 Hi-Fi", "影音室 TV")
                                    presets.forEachIndexed { pIdx, preset ->
                                        val isCur = friendlyName == preset && !isEditingCustomName
                                        SegmentOptionButton(
                                            label = preset,
                                            isSelected = isCur,
                                            leftNavFocusRequester = if (pIdx == 0) navFocusRequesters[selectedCategory.ordinal] else null,
                                            onClick = {
                                                isEditingCustomName = false
                                                settingsRepo.setFriendlyName(preset)
                                                PlaybackService.instance?.updateFriendlyName(preset)
                                                Toast.makeText(context, "已更新设备名为: $preset", Toast.LENGTH_SHORT).show()
                                            }
                                        )
                                    }
                                    val isCustomSelected = isEditingCustomName || friendlyName !in presets
                                    val customLabel = if (!isEditingCustomName && friendlyName !in presets) "✏️ $friendlyName" else "✏️ 自定义"
                                    SegmentOptionButton(
                                        label = customLabel,
                                        isSelected = isCustomSelected,
                                        focusRequester = customEditButtonFocusRequester,
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
                                                .border(
                                                    width = if (isTextFieldFocused) 2.dp else 1.dp,
                                                    color = if (isTextFieldFocused) AppPalette.accent else Color.Transparent,
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                        ) {
                                            BasicTextField(
                                                value = customNameInput,
                                                onValueChange = { customNameInput = it },
                                                singleLine = true,
                                                textStyle = TextStyle(color = AppPalette.primaryText, fontSize = 14.sp),
                                                cursorBrush = SolidColor(AppPalette.primaryText),
                                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                                keyboardActions = KeyboardActions(
                                                    onDone = {
                                                        val t = customNameInput.trim()
                                                        if (t.isNotEmpty()) {
                                                            settingsRepo.setFriendlyName(t)
                                                            PlaybackService.instance?.updateFriendlyName(t)
                                                            exitCustomNameEditing()
                                                            Toast.makeText(context, "已保存广播名为: $t", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }
                                                ),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .focusRequester(customNameFocusRequester)
                                                    .focusProperties { left = navFocusRequesters[selectedCategory.ordinal] }
                                                    .onFocusChanged { isTextFieldFocused = it.isFocused }
                                            )
                                        }
                                        SegmentOptionButton(
                                            label = "保存",
                                            isSelected = true,
                                            onClick = {
                                                val t = customNameInput.trim()
                                                if (t.isNotEmpty()) {
                                                    settingsRepo.setFriendlyName(t)
                                                    PlaybackService.instance?.updateFriendlyName(t)
                                                    exitCustomNameEditing()
                                                    Toast.makeText(context, "已保存广播名为: $t", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                        )
                                        SegmentOptionButton(
                                            label = "取消",
                                            isSelected = false,
                                            onClick = { exitCustomNameEditing() }
                                        )
                                    }
                                }
                            }
                        }

                        // 3. 动态流光背景画质
                        SettingsSection(title = "动态流光背景画质") {
                            SettingTieredRow(
                                title = "GLES2 着色器渲染档位",
                                subtitle = "根据电视芯片性能调节流光分辨率与帧率节拍 (高配推荐极佳/标准，低配推荐省电或静态)"
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf(
                                        FluidQuality.HIGH_QUALITY to "极佳 (60fps)",
                                        FluidQuality.STANDARD to "标准 (20fps)",
                                        FluidQuality.POWER_SAVE to "省电 (20fps · 低清)",
                                        FluidQuality.STATIC to "静态磨砂",
                                        FluidQuality.OFF to "关闭"
                                    ).forEachIndexed { qIdx, (quality, label) ->
                                        SegmentOptionButton(
                                            label = label,
                                            isSelected = currentFluidQuality == quality,
                                            leftNavFocusRequester = if (qIdx == 0) navFocusRequesters[selectedCategory.ordinal] else null,
                                            onClick = {
                                                settingsRepo.setFluidQuality(quality)
                                                Toast.makeText(context, "已设置流光画质: $label", Toast.LENGTH_SHORT).show()
                                            }
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
                                subtitle = if (audioState.isExclusive) "已解绑 Linux snd-usb-audio 内核驱动，直通 UAC 音频硬件 (绕过 48kHz SRC)" else "声卡处于待机让渡或系统共享状态"
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
                                        focusRequester = categoryFirstFocusRequesters[SettingsCategory.USB_DAC],
                                        leftNavFocusRequester = navFocusRequesters[selectedCategory.ordinal],
                                        onClick = {
                                            if (audioState.isExclusive) {
                                                PlaybackService.instance?.releaseUsbExclusiveManually()
                                                Toast.makeText(context, "已释放独占，声卡已归还系统", Toast.LENGTH_SHORT).show()
                                            } else {
                                                if (context is Activity) {
                                                    PlaybackService.instance?.claimUsbExclusiveManually(context) { _, msg ->
                                                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                                    }
                                                }
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
                                    listOf(Pair(5, "5秒"), Pair(10, "10秒 (推荐)"), Pair(30, "30秒"), Pair(0, "常驻独占")).forEachIndexed { optIdx, (sec, label) ->
                                        val isSelected = idleTimeoutSeconds == sec
                                        SegmentOptionButton(
                                            label = label,
                                            isSelected = isSelected,
                                            leftNavFocusRequester = if (optIdx == 0) navFocusRequesters[selectedCategory.ordinal] else null,
                                            onClick = {
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
                                    focusRequester = categoryFirstFocusRequesters[SettingsCategory.PROTOCOLS],
                                    leftNavFocusRequester = navFocusRequesters[selectedCategory.ordinal],
                                    onToggle = {
                                        val target = !isAirPlayEnabled
                                        settingsRepo.setAirPlayEnabled(target)
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
                                    leftNavFocusRequester = navFocusRequesters[selectedCategory.ordinal],
                                    onToggle = {
                                        val target = !isUpnpEnabled
                                        settingsRepo.setUpnpEnabled(target)
                                        PlaybackService.instance?.setUpnpEnabled(target)
                                    }
                                )
                            }
                        }
                    }

                    SettingsCategory.HARDWARE_MONITOR -> {
                        SettingsSection(title = "SPSC 无锁环形缓冲与时钟仪表盘") {
                            val formatSpec = if (audioState.protocol != StreamProtocol.NONE && audioState.sampleRate > 0) {
                                "${formatSampleRate(audioState.sampleRate)} / ${audioState.bitDepth}-bit (${audioState.channels} 声道)"
                            } else {
                                "待机无推流"
                            }

                            val bufferUsagePercent = if (bufferCapacity > 0) {
                                (bufferAvailableRead * 100L / bufferCapacity).toInt()
                            } else 0

                            val bufferAvailableKb = bufferAvailableRead / 1024
                            val bufferCapacityKb = bufferCapacity / 1024
                            val effectiveSampleRate = if (audioState.sampleRate > 0) audioState.sampleRate else 44100
                            val effectiveChannels = if (audioState.channels > 0) audioState.channels else 2
                            val effectiveBits = if (audioState.bitDepth > 0) audioState.bitDepth else 16
                            val bytesPerSec = effectiveSampleRate * effectiveChannels * (effectiveBits / 8)
                            val bufferMs = if (bytesPerSec > 0) (bufferAvailableRead * 1000L / bytesPerSec) else 0L

                            MetricDataRow(label = "当前实时采样率与位深", value = formatSpec)
                            SettingDivider()
                            MetricDataRow(
                                label = "SPSC 环形缓冲区",
                                value = "${bufferAvailableKb} KB / ${bufferCapacityKb} KB (${bufferMs}ms / $bufferUsagePercent% 水位)"
                            )
                            SettingDivider()
                            MetricDataRow(label = "UAC 物理时钟锁定", value = if (audioState.isExclusive) "UAC1/UAC2 异步/显式反馈闭环物理时钟 (SOF 同步)" else "让渡共享时钟")
                            SettingDivider()
                            MetricDataRow(label = "底层驱动与内核通信", value = "libusb-$libusbVer (usbfs 根权限包装)")
                        }
                    }

                    SettingsCategory.HEALTH_CHECK -> {
                        SettingsSection(title = "Android 权限与系统保活自检") {
                            PermissionCheckRow(
                                label = "USB 硬件直接访问权限",
                                description = "直通 UAC 音频时钟与 Linux usbfs 底层声卡驱动",
                                isGranted = isUsbGranted,
                                focusRequester = categoryFirstFocusRequesters[SettingsCategory.HEALTH_CHECK],
                                leftNavFocusRequester = navFocusRequesters[selectedCategory.ordinal],
                                onClick = {
                                    val act = context as? Activity
                                    if (act != null) {
                                        PlaybackService.instance?.requestUsbPermission(act)
                                    } else {
                                        PermissionHelper.openAppDetailsSettings(context)
                                    }
                                }
                            )
                            SettingDivider()
                            PermissionCheckRow(
                                label = "后台无限制与电池优化白名单",
                                description = "防止大屏熄屏或空闲时系统清理后台推流服务",
                                isGranted = isIgnoringBattery,
                                leftNavFocusRequester = navFocusRequesters[selectedCategory.ordinal],
                                onClick = {
                                    PermissionHelper.openBatteryOptimizationSettings(context)
                                }
                            )
                            SettingDivider()
                            PermissionCheckRow(
                                label = "核心录音权限 (RECORD_AUDIO)",
                                description = "仅用于系统免弹窗接管，工程绝不调用录音硬件",
                                isGranted = isRecordAudioGranted,
                                leftNavFocusRequester = navFocusRequesters[selectedCategory.ordinal],
                                onClick = {
                                    val act = context as? Activity
                                    if (act != null && !isRecordAudioGranted) {
                                        androidx.core.app.ActivityCompat.requestPermissions(
                                            act,
                                            arrayOf(Manifest.permission.RECORD_AUDIO),
                                            1001
                                        )
                                    } else {
                                        PermissionHelper.openAppDetailsSettings(context)
                                    }
                                }
                            )
                            SettingDivider()
                            PermissionCheckRow(
                                label = "悬浮窗与前台拉起权限 (OVERLAY)",
                                description = "支持局域网接收推流时自动拉起大屏播放界面",
                                isGranted = isOverlayGranted,
                                leftNavFocusRequester = navFocusRequesters[selectedCategory.ordinal],
                                onClick = {
                                    PermissionHelper.openOverlaySettings(context)
                                }
                            )
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
 * - 文本自适应包裹宽度 (wrapContentWidth)，杜绝“✏️ 自定义”折行；
 * - 支持 focusRequester 首项锚定与 leftNavFocusRequester 确定性返回。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SegmentOptionButton(
    label: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    leftNavFocusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    val isDark = AppPalette.isDark
    val normalBg = if (isSelected) AppPalette.accent else AppPalette.surfaceSecondary
    val normalContent = if (isSelected) Color.White else AppPalette.primaryText

    // 严谨适配深/浅模式的获焦底色与前景色
    val focusedBg = if (isDark) Color.White else Color(0xFF1C1C1E)
    val focusedContent = if (isDark) Color(0xFF1C1C1E) else Color.White

    val shape = RoundedCornerShape(10.dp)

    Surface(
        onClick = onClick,
        modifier = modifier
            .wrapContentWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(if (leftNavFocusRequester != null) Modifier.focusProperties { left = leftNavFocusRequester } else Modifier),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(scale = 1.0f, focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = normalBg,
            focusedContainerColor = focusedBg,
            contentColor = normalContent,
            focusedContentColor = focusedContent
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border.None
        ),
        glow = ClickableSurfaceDefaults.glow(
            glow = Glow.None,
            focusedGlow = Glow(elevationColor = focusedBg.copy(alpha = 0.35f), elevation = 6.dp)
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            if (isSelected) {
                Text(
                    text = "●",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}

/**
 * 💡 严格适配深/浅模式的开关切换按钮 (ToggleSwitchButton)
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ToggleSwitchButton(
    isEnabled: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    leftNavFocusRequester: FocusRequester? = null,
    onToggle: () -> Unit
) {
    val isDark = AppPalette.isDark
    val normalBg = if (isEnabled) AppPalette.success else AppPalette.surfaceSecondary
    val normalContent = if (isEnabled) Color.White else AppPalette.secondaryText

    val focusedBg = if (isDark) Color.White else Color(0xFF1C1C1E)
    val focusedContent = if (isDark) Color(0xFF1C1C1E) else Color.White

    val shape = RoundedCornerShape(10.dp)

    Surface(
        onClick = onToggle,
        modifier = modifier
            .wrapContentWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(if (leftNavFocusRequester != null) Modifier.focusProperties { left = leftNavFocusRequester } else Modifier),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(scale = 1.0f, focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = normalBg,
            focusedContainerColor = focusedBg,
            contentColor = normalContent,
            focusedContentColor = focusedContent
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border.None
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
 * 💡 辅助权限检查行 (Surface 可获焦组件，支持遥控器一键跳转修复)
 * 严格遵从 Apple TV HIG 列表项焦点规范：
 * 1. 彻底移除生硬突兀的 2dp 黑色外框与放缩撕裂 (scale=1.0f, border=None)；
 * 2. 规范深/浅模式高反差焦点设计：
 *    - 深色模式获焦：纯白底 (Color.White) + 纯黑字 (0xFF1C1C1E) + 深绿/深橙状态指示；
 *    - 浅色模式获焦：纯黑底 (0xFF1C1C1E) + 纯白字 (Color.White) + 鲜绿/鲜橙状态指示；
 *    - 未获焦时：透明底色，优雅嵌入卡片；
 * 3. 悬浮圆角药丸质感 (RoundedCornerShape(10.dp) + 内嵌边距)，与外层圆角卡片完美贴合；
 * 4. 显式绑定向左导航至左侧当前分类项，杜绝焦点跑飞与误切分类。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PermissionCheckRow(
    label: String,
    description: String,
    isGranted: Boolean,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    leftNavFocusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    val isDark = AppPalette.isDark
    var isFocused by remember { mutableStateOf(false) }

    val normalBg = Color.Transparent
    val focusedBg = if (isDark) Color.White else Color(0xFF1C1C1E)

    // 动态文字高反差
    val titleColor = if (isFocused) {
        if (isDark) Color(0xFF1C1C1E) else Color.White
    } else {
        AppPalette.primaryText
    }

    val descColor = if (isFocused) {
        if (isDark) Color(0xFF6E6E73) else Color(0xFFE5E5EA)
    } else {
        AppPalette.secondaryText
    }

    // 状态指示色 (已就绪 / 去授权)
    val statusColor = if (isFocused) {
        if (isDark) {
            if (isGranted) Color(0xFF1B5E20) else Color(0xFFE65100)
        } else {
            if (isGranted) Color(0xFF30D158) else Color(0xFFFF9F0A)
        }
    } else {
        if (isGranted) AppPalette.success else AppPalette.warning
    }

    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(if (leftNavFocusRequester != null) Modifier.focusProperties { left = leftNavFocusRequester } else Modifier)
            .onFocusChanged { isFocused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(scale = 1.0f, focusedScale = 1.0f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = normalBg,
            focusedContainerColor = focusedBg,
            contentColor = if (isDark) Color(0xFF1C1C1E) else Color.White,
            focusedContentColor = if (isDark) Color(0xFF1C1C1E) else Color.White
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border.None
        ),
        glow = ClickableSurfaceDefaults.glow(
            glow = Glow.None,
            focusedGlow = Glow(elevationColor = focusedBg.copy(alpha = 0.35f), elevation = 6.dp)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = descColor
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Text(
                    text = if (isGranted) "已就绪" else "去授权 ›",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = statusColor
                )
            }
        }
    }
}
