---
module: tv-compose-ui
date: 2026-11-05
problem_type: ui_bug
component: frontend
severity: high
symptoms:
  - "Compose for TV 原生 Button 内置不对称 padding 导致矢量 SF 图标发生几何偏心且尺寸失衡"
  - "大屏 D-Pad 焦点引擎缺乏层级 Back 状态机，右侧内容区按返回键直接穿透退出整个设置页面"
  - "待机无推流时进度条根据 duration <= 0 错误判定为实时流并渲染 100% 满格高亮进度线"
  - "OpenGL ES 2.0 动态网格流体背景在低功耗电视芯片上每帧分配对象引发 VSYNC 掉帧卡顿"
  - "待机浅色主题下静态暗化遮罩（22% 黑）未被解绑，造成待机背景发污发灰且前景低对比度"
root_cause: logic_error
resolution_type: code_fix
tags: [android-tv, compose-for-tv, tvos-design, d-pad-focus, backhandler, gles2, vsync, zero-allocation, theme-adaptation]
---

# Android TV (Compose for TV) Apple TV 工业设计移植与焦点引擎容错实践

## Problem

在 PureCast Android TV 大屏端重构过程中，界面由传统的粗糙移动端移植版全面转向 Apple TV (tvOS) 极简工业设计与沉浸式动态流体视觉。在真机联调（九洲 DTP9733 / EF-15 电视盒子，Android 11 / API 30）与压测中，遇到了以下关键系统级交互与渲染缺陷：
1. 原生 `androidx.tv.material3.Button` 内置移动端残留的隐式 Padding，导致 SF 风格矢量控制图标严重偏心，且固定宽高导致适配失真；
2. 双栏设置页面在 D-Pad 遥控器导航时，右侧具体配置项按返回键会直接穿透并退出整个设置页面返回播放主页，破坏了 TV 的层级导航心智；
3. 待机无推流时，进度条组件将 `durationMs <= 0` 错误判定为“实时无界流（Live Stream）”，在屏幕中央渲染贯穿式的 100% 满格亮线；
4. GLES2 动态彩色流体背景在低功耗 32 位电视芯片上每帧动态分配 `BackgroundOutputSize` 触发 GC 抖动与掉帧；且待机浅色模式下残留 22% 黑色遮罩造成背景发污发灰。

## Symptoms

1. **按钮图标几何偏心与轮廓异常**：播放控制栏（Play/Pause/Skip）图标无法居中，偏向左上方，在获焦放大时偏心瑕疵进一步被放大。
2. **设置页 D-Pad 返回穿透退出**：焦点深入右侧（如 DAC 独占超时、深浅模式切换）时，按下遥控器 Back 键直接跳出设置界面回到播放大屏，用户无法退回到左侧类别列表。
3. **待机时间戳 00:00 伴随 100% 满格进度线**：未推流待机状态下，时间显示 00:00 / 00:00，但进度条却是一条贯穿屏幕的 100% 纯白或亮色条。
4. **低功耗盒子动画掉帧与浅色脏底**：电视背景动画偶发卡顿，而在切换至浅色模式（Light Mode）后，页面蒙上一层灰暗污浊的半透明黑层，与浅色主题格格不入。

## What Didn't Work

1. **在 `Button` 上追加 `Modifier.padding(0.dp)` 试图消除偏心**：
   - *尝试*：给 `androidx.tv.material3.Button` 传入 `contentPadding = PaddingValues(0.dp)`。
   - *失败原因*：TV Material 3 的 `Button` 内部仍硬编码了针对文本基线的最低触摸尺寸和内部对齐插值（Minimum Interactive Touch Target），即使 Padding 清零，内部渲染槽依然不会呈现绝对的几何笛卡尔中心。
2. **在页面外层追加单个全局 `BackHandler` 拦截返回键**：
   - *尝试*：在外层统一维护一个状态计数或简单布尔值。
   - *失败原因*：Compose 焦点系统与事件分发是解耦的。若右侧某项获焦后直接按返回，外层无法感知当前物理焦点是否位于右侧子树，或者将焦点粗暴 `requestDefaultFocus()` 会导致遥控器焦点跳跃至未知元素。
3. **在待机时仅简单将进度条 `alpha` 设为 0**：
   - *尝试*：`if (state.totalDurationMs <= 0) Modifier.alpha(0f)`。
   - *失败原因*：破坏了布局层级的一致性，在实时网络流（如 UPnP Live FLAC）推流时导致进度条意外消失；且待机恢复播放的第一帧会出现视觉闪烁。

## Solution

针对上述问题，我们在 UI 架构层实施了以下核心改造：

### 1. 采用 `Surface` + `Box(Alignment.Center)` 实现绝对居中与呼吸获焦

抛弃移动端带副作用的 `Button` 组件，改用底层 `Surface` 配合 `Box(fillMaxSize, Alignment.Center)`，并结合 `Indication` 与 `scale` 动画：

```kotlin
// app/src/main/java/com/solo/purecast/ui/components/PlaybackControls.kt:139
Surface(
    onClick = onClick,
    modifier = Modifier
        .size(buttonSize)
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .onFocusChanged { isFocused = it.isFocused },
    shape = ClickableSurfaceDefaults.shape(CircleShape),
    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
    colors = ClickableSurfaceDefaults.colors(
        containerColor = Color.Transparent,
        focusedContainerColor = focusedBgColor,
        pressedContainerColor = focusedBgColor
    ),
    border = ClickableSurfaceDefaults.border(
        border = Border.None,
        focusedBorder = Border.None
    )
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = contentTint
        )
    }
}
```

### 2. 双层响应式 `BackHandler` 与 D-Pad 层级焦点引擎

在设置双栏结构中引入精准的焦点感知与双层 `BackHandler` 路由：
- 右侧内容区通过 `Modifier.onFocusChanged { if (it.hasFocus) isFocusInRightContent = true }` 追踪焦点落点；
- 第一层 `BackHandler(enabled = isFocusInRightContent || isEditingCustomName)`：捕获 Back 键，通过 `navFocusRequesters[selectedCategory.ordinal].requestFocus()` 优雅交还焦点给左侧导航列表；
- 第二层 `BackHandler(enabled = !isFocusInRightContent && !isEditingCustomName)`：当焦点确在左侧侧边栏时，Back 键才触发退出设置界面。

```kotlin
// app/src/main/java/com/solo/purecast/ui/screen/SettingsScreen.kt:132
BackHandler(enabled = isFocusInRightContent || isEditingCustomName) {
    if (isEditingCustomName) {
        isEditingCustomName = false
    } else if (isFocusInRightContent) {
        try {
            val requester = navFocusRequesters.getOrNull(selectedCategory.ordinal)
                ?: navFocusRequesters.firstOrNull()
            requester?.requestFocus()
        } catch (_: Exception) {}
        isFocusInRightContent = false
    }
}

// app/src/main/java/com/solo/purecast/ui/screen/SettingsScreen.kt:149
BackHandler(enabled = !isFocusInRightContent && !isEditingCustomName) {
    onDismiss()
}
```

### 3. 解耦待机无音频与实时流的进度判定纯函数

明确状态机定义：当且仅当 `totalDurationMs <= 0L && currentPositionMs <= 0L` 时表示真正无音频的待机态，返回 `0.0f` 并静默；仅当 `totalDurationMs <= 0L && currentPositionMs > 0L` 时才视为未知长度的活跃实时流（返回 `1.0f`）：

```kotlin
// app/src/main/java/com/solo/purecast/ui/components/ProgressBarComponent.kt:249
internal fun calculateProgressFraction(
    currentPositionMs: Long,
    totalDurationMs: Long
): Float {
    return when {
        totalDurationMs <= 0L && currentPositionMs <= 0L -> 0.0f
        totalDurationMs <= 0L -> 1.0f
        else -> (currentPositionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0.0f, 1.0f)
    }
}
```

### 4. VSYNC 帧循环内存零分配与待机模式遮罩解绑

- 在 60 FPS 的渲染循环中，消除每帧构造 `BackgroundOutputSize` 的开销，使用视图宽高与画质缓存：
  ```kotlin
  // app/src/main/java/com/solo/purecast/ui/background/MacTextureView.kt:133
  private var cachedViewWidth = -1
  private var cachedViewHeight = -1
  private var cachedQuality: FluidQuality? = null
  private var cachedOutputSize: BackgroundOutputSize? = null
  ```
- 待机与播放双模式渲染分流：推流活跃时开启 GLES2 动态流光并叠加 22% 黑色保护层；待机时彻底移除该黑色遮罩，使浅色模式呈现纯净通透的米白底色，文字自适应 `AppPalette.textPrimary`。

## Why This Works

1. **绝对对齐契约**：`Surface` 剥离了所有高级交互组件自带的边距猜测，将形状、缩放、背景着色交由 GPU 绘制树处理，内部的 `Box` 提供纯粹的二维中心约束，确保矢量 SF 符号在任何缩放比例下严格居中。
2. **状态优先的导航模型**：双层 `BackHandler` 不依赖脆弱的时序或延迟调用，而是直接绑定 Compose 运行时计算的 `isFocusInRightContent` 派生状态。无论用户使用遥控器右键深入多级选项还是点击右侧按钮，Back 键的行为都严格符合 TV HIG（从细节退回分类，从分类退回上一页）。
3. **数学无歧义的进度模型**：通过提取并覆盖 6 种典型边界用例的单元测试，彻底消除了“零总长”状态下的二义性。
4. **GC 零暂停保障**：避免在 `onDrawFrame` / VSYNC 脉冲中进行堆内存分配，杜绝了低端 Android 电视盒子由于 GC STW (Stop The World) 带来的周期性微卡顿。

## Prevention

1. **TV 控件选型铁律**：严禁在 Android TV 大屏端使用移动端通用的 `Button`、`IconButton` 封装；交互控件一律使用 `Surface(shape, scale, colors)` + `Box(contentAlignment = Center)`。
2. **双栏界面规范**：凡是主次分栏页面，必须配备层级焦点感知器与双层 `BackHandler`，并在进入/退出时保留 FocusRequester 兜底。
3. **边界逻辑纯函数化与单测覆盖**：UI 计算逻辑（如进度、格式化、缩放）必须抽离为包内 `internal` 纯函数，并建立全覆盖的单元测试（如 `ProgressBarComponentTest.kt`）。
4. **帧循环内存审查**：在 Custom View 或 OpenGL 渲染循环中，禁止出现任何 `new` / 实例化对象的代码，必须使用成员变量复用（Object Pool / Cache）。
