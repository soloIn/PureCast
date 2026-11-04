---
title: PureCast 阶段二：Apple TV 极简 UI 美学与 10-Foot 遥控器大屏交互 - Plan
type: feat
date: 2026-11-04
origin: 项目背景与核心目标.md
artifact_contract: ce-unified-plan/v1
product_contract_source: legacy-requirements
execution: code
---

# PureCast 阶段二：Apple TV 极简 UI 美学与 10-Foot 遥控器大屏交互实现计划

---

## Goal Capsule

- **Objective**: 依照《项目背景与核心目标.md》第 5 节规范，将 PureCast 当前简陋的大屏骨架全面重构升级为 Apple TV 极简工业美学大屏界面。集成 Coil 异步加载高清网络专辑封面并用 `Palette` 提取主色调驱动动态流光流体背景；实现纤细圆角进度条与时间轴；补齐「上一曲 / 下一曲」按钮并在实时流时置灰与友好提示；实现歌曲标题超长平滑跑马灯；拦截遥控器 `KEYCODE_MENU` 键并完善全功能侧边栏抽屉（改名、独占开关、权限自检、硬件监控、让渡策略切换）；实现 3 分钟无操作自动隐藏控制条与像素微移防烧屏引擎。
- **Means**: 引入 `io.coil-kt:coil-compose` 加载封面；使用 `androidx.palette:palette-ktx` 异步提取色板；在 `MainTvScreen` 中构筑进度条、跑马灯与焦点流；拦截 `MainActivity.onKeyDown` 处理 `KEYCODE_MENU` 与媒体键；扩充 `SettingsDrawer` 状态机；构建基于 Compose `LaunchedEffect` 的无操作超时计时器。
- **Authority Hierarchy**:
  1. 产品行为以 `Product Contract` 中明确的 R-IDs 为唯一权威；
  2. 底层驱动机制与技术实现以 `Planning Contract` 中明确的 KTD-IDs 为唯一权威；
  3. 实现单元（U-IDs）严格遵循上述规范，不任意扩展业务边界。
- **Stop Conditions**: EF-15 电视大屏上完整呈现 42%/58% Apple TV 黄金比例布局，封面网络加载迅速并实时变换全屏流光背景；遥控器 D-Pad 丝滑穿梭于进度条与控制按钮间，微光放大（Scale `1.12x`）清晰稳定；按下 MENU 键呼出全功能侧边栏；3 分钟无操作自动淡出控制栏防烧屏。
- **Execution Profile**: 适用于 `ce-work` 执行，结合真机无线 ADB 与 `screencap` 视觉自检。

---

## Product Contract

### Summary
本计划将 PureCast 的大屏交互从临时调试状态全面升级为符合 Apple TV (tvOS) 极简标准的 10-Foot UI。彻底解决传统播放器控件繁琐、Neutron 界面不响应遥控器、以及缺乏视觉美感的痛点，为用户提供视听双重极致享受。

### Problem Frame
当前 UI 仅具备极简的双栏骨架：封面为死板字符、流光背景颜色固定、缺少进度条组件、缺少上一曲/下一曲按键、标题超长被截断、MENU 键无效、侧边栏设置项残缺，且缺少长时间播放的防烧屏自动隐藏控制栏机制。

### Key Decisions
- **KD1. Coil-Compose 封面异步加载与 Palette 色调联动**: 引入轻量 Coil 库从 `metadata.coverUri` 加载封面图片，在协程中通过 `Palette.from(bitmap).generate()` 提取 Vibrant/Muted 主次色调，无缝驱动流光背景；未播放或无封面时降级为深邃暗夜灰 (session-settled: user-directed — chosen over 静态深色背景: 打造 Apple TV 沉浸式流光氛围). *Governs R6.*
- **KD2. 纤细进度条与时间轴焦点交互**: 构筑圆角进度条，左侧显示已播放时长，右侧显示总时长；针对 `airupnp` 等实时无边界流显示 `LIVE / 实时直通`，D-Pad 支持上下键获取焦点、左右键快进快退 10 秒 (session-settled: user-directed — chosen over 仅做展示不响应按键: 满足电视遥控器交互标准). *Governs R5, R6.*
- **KD3. 实时无边界流控制项自适应置灰与提示**: 播放 `airupnp` 等外部推送流时，「上一曲」和「下一曲」按钮自动置灰禁用；当用户在遥控器上按切歌键时，弹出优雅 Toast 提示“当前为外部实时投送流”，保护外部推流状态 (session-settled: user-directed — chosen over 盲目切歌导致流中断). *Governs R5, R6.*
- **KD4. 遥控器 MENU 键全局呼出全功能设置侧边栏**: 拦截系统 `KEYCODE_MENU` 键直接唤出毛玻璃侧边栏抽屉，提供改名、独占开关、权限自检列表、硬件时钟/缓冲监视器以及让渡模式切换 (session-settled: user-directed — chosen over 隐藏在二级菜单: 便于客厅大屏快捷调参). *Governs R6.*
- **KD5. 3 分钟无操作自动隐藏控制栏防烧屏机制**: 播放过程中无遥控器按键满 3 分钟，底部控制栏平滑淡出，仅保留封面、歌名与徽章并配合微像素平移（Pixel Shift）；按任意方向键瞬间恢复 (session-settled: user-directed — chosen over 常驻界面: 保护电视 OLED/OLED 面板寿命). *Governs R6.*

### Requirements
- **R6-P1. Coil 封面加载与 Palette 动态流光背景**: 异步加载网络封面，Palette 提取色系，结合 API 31+ `Modifier.blur` / API 30 纯 Kotlin StackBlur 渲染双层流体背景。
- **R6-P2. 纤细进度条与 D-Pad 寻道**: 显示播放时间轴与当前进度，实时流自适应显示 LIVE 标识，支持左右键步进寻道。
- **R6-P3. 切歌控制与实时流置灰提示**: 水平排布「上一曲」、「播放/暂停」、「下一曲」、「设置」按钮，具备 `Scale 1.12x` 放大与微光指示；实时流置灰切歌并拦截按键。
- **R6-P4. 标题跑马灯平滑滚动**: 歌曲名超长时启动 `Modifier.basicMarquee()` 跑马灯滚动。
- **R6-P5. 全功能侧边栏设置与 MENU 快捷键**: 拦截 `KEYCODE_MENU`；抽屉支持修改 UPnP 设备名、USB DAC 独占开关、权限状态检查、实时硬件时钟与缓冲监视面板、让渡模式切换。
- **R6-P6. 3 分钟防烧屏自动隐藏控制栏**: 3 分钟无按键淡出控制栏，按任意方向键或确定键即刻唤醒。

### Scope Boundaries
- **In Scope**:
  - `app/build.gradle.kts` 添加 Coil 依赖。
  - `app/src/main/java/com/solo/purecast/ui/*` 全量组件与页面改造。
  - `app/src/main/java/com/solo/purecast/ui/MainActivity.kt` 按键拦截。
- **Deferred to Follow-Up Work**:
  - 开机权限自检引导向导界面（阶段三实现）。
  - MediaSession 绑定与 UPnP 协议深化（阶段三实现）。

### Success Criteria
- **SC1. 流光大屏视觉自检**: 推流带封面的歌曲时，大屏圆角封面清晰展示，背景流畅切换为与封面同色系的动态流光；标题超长时平滑滚动。
- **SC2. 遥控器 D-Pad 焦点丝滑**: 上下键在进度条与控制按钮间流畅移动，按钮高亮白底微光放大。
- **SC3. 侧边栏与按键闭环**: 按下电视遥控器 MENU 键立即呼出侧边栏，支持查看实时缓冲与切换让渡策略。
- **SC4. 防烧屏验证**: 模拟无操作 3 分钟，底部控制栏淡出，按方向键瞬间复原。

---

## Planning Contract

### Key Technical Decisions
- **KTD1. Coil 与 Palette 集成架构**:
  - 在 `app/build.gradle.kts` 中添加 `io.coil-kt:coil-compose:2.6.0`；
  - 创建 `rememberPaletteFromUrl(url: String?)` 自定义 Compose Effect，在后台 Dispatchers.IO 异步获取 `Bitmap` 并解析 `Palette`，提取 `vibrantSwatch` 与 `dominantSwatch`，输出 `State<Pair<Color, Color>>`。
- **KTD2. 焦点树层级与 D-Pad 导航**:
  - 进度条作为独立的 `FocusRequester` 节点置于控制栏上方；
  - 配置 `FocusRequester` 确保按 UP 键从控制栏切入进度条，按 DOWN 键从进度条落入播放控制按钮。
- **KTD3. 无边界流时间轴自适应**:
  - 当 `metadata.isLiveStream == true` 时，进度条固定为满格或流动脉冲状态，右侧显示 `"LIVE / 实时直通"`，禁用左右快进快退。
- **KTD4. 无操作防烧屏状态机**:
  - 在 `MainTvScreen` 中维护 `lastInteractionTimestamp = remember { mutableLongStateOf(System.currentTimeMillis()) }`；
  - 在外层 Box 上添加 `onKeyEvent` 拦截任意按键事件重置该时间戳；
  - 协程循环每 5 秒检查，若 `now - lastInteractionTimestamp > 180_000` 则设置 `isControlsVisible = false`，带 `AnimatedVisibility(fadeIn, fadeOut)` 动效。

---

## Implementation Units

### U1. Coil 异步封面加载与 Palette 动态流光背景联动 (Coil Cover & Palette Fluid Background)
- **Goal**: 引入 Coil-Compose 异步加载真实网络封面，提取封面主色调与次色调并驱动全屏流光背景，无封面时优雅回退为深邃灰。
- **Requirements**: R6-P1
- **Dependencies**: None
- **Files**:
  - `app/build.gradle.kts`
  - `gradle/libs.versions.toml`
  - `app/src/main/java/com/solo/purecast/ui/components/FluidBackground.kt`
  - `app/src/main/java/com/solo/purecast/ui/screen/MainTvScreen.kt`
- **Approach**:
  1. 在 `libs.versions.toml` 与 `app/build.gradle.kts` 中增加 `coil-compose` 依赖。
  2. 在 `MainTvScreen` 左侧 42% 封面卡片中，用 `AsyncImage` 或 Coil 异步加载 `metadata.coverUri`，圆角 24dp，带大尺寸阴影与播放缩放弹簧动画。
  3. 构建 `rememberPaletteColors(coverUri)`：通过 `ImageRequest` 获取 Bitmap 并调用 `Palette.from(bitmap).generate()` 提取调色板。
  4. 将提取的主色调和次色调传入 `FluidBackground`，结合 API 31+ 硬件模糊 / API 30 内存 StackBlur 呈现慢速流体动画。
- **Test scenarios**:
  - 推送带封面的 DLNA 音频，验证大屏成功显示真实高清封面，全屏背景跳变为对应主题流光色。
  - 推送无封面流，验证界面显示默认优雅唱片占位图与暗夜灰流光背景。
- **Verification**: 真机截图自检，封面清晰无拉伸，流光背景色彩与封面完全契合。

---

### U2. 纤细圆角进度条与时间轴 D-Pad 焦点交互 (Slim Progress Bar & D-Pad Seeking)
- **Goal**: 实现 Apple TV 风格的纤细圆角进度条组件，精准展示已播放时间与总时长，实时流展示 `LIVE` 状态，D-Pad 支持获得焦点与左右快退/快进。
- **Requirements**: R6-P2
- **Dependencies**: None
- **Files**:
  - `app/src/main/java/com/solo/purecast/ui/components/ProgressBarComponent.kt`
  - `app/src/main/java/com/solo/purecast/ui/screen/MainTvScreen.kt`
- **Approach**:
  1. 创建 `ProgressBarComponent.kt`：高度 4dp（获得焦点时放大至 6dp 并点亮白色外发光），圆角胶囊状。
  2. 左侧显示 `mm:ss`，右侧显示总时长或 `-mm:ss`；若 `isLiveStream` 为真，则右侧显示红色圆点 + `"LIVE / 实时直通"`。
  3. 配置 `focusable()` 与 `onKeyEvent`：当获得焦点且非实时流时，按左右键触发快进/快退 10 秒回调。
- **Test scenarios**:
  - 遥控器按 UP 键，焦点从播放按钮上移至进度条，进度条加粗并浮起白光。
  - 播放实时流时，右侧显示 `LIVE`，按左右键无快进快退动作。
- **Verification**: 真机操作遥控器，进度条焦点移动自然，时间指示准确。

---

### U3. 遥控器控制按钮组补齐与实时流置灰提示 (Complete Remote Controls & Live Stream Guard)
- **Goal**: 补齐「上一曲」、「播放/暂停」、「下一曲」、「设置」按钮，实时流时切歌按钮优雅置灰并在遥控器点击时 Toast 提示。
- **Requirements**: R6-P3
- **Dependencies**: None
- **Files**:
  - `app/src/main/java/com/solo/purecast/ui/components/PlaybackControls.kt`
  - `app/src/main/java/com/solo/purecast/ui/screen/MainTvScreen.kt`
- **Approach**:
  1. 重构 `PlaybackControls.kt`：水平排列「上一曲 (⏮)」、「播放/暂停 (▶ / ❚❚)」、「下一曲 (⏭)」与「设置 (⚙)」。
  2. 每个按钮统一采用 `androidx.tv.material3.Button`，设置 `scale = ButtonDefaults.scale(1.0f, focusedScale = 1.12f)` 与高亮描边。
  3. 当 `isLiveStream == true` 时，将「上一曲」与「下一曲」的 `enabled` 设置为 `false`，透明度降低为 0.38f。
  4. 为切歌按键设置点击事件拦截：若为实时流且用户按下切歌键，弹出 Toast 提示：“当前为外部实时投送流，不支持切换上一曲/下一曲”。
- **Test scenarios**:
  - 推送 `airupnp` 实时流，切歌按钮自动变灰，遥控器点击弹出友好提示。
  - 普通流时切歌按钮高亮可用。
- **Verification**: 遥控器左右平移焦点丝滑，按钮缩放微光指示清晰。

---

### U4. 歌曲标题平滑跑马灯（Marquee）与大屏排版 (Title Marquee & Typography)
- **Goal**: 实现超长歌曲标题的平滑跑马灯横向滚动，优化大屏高对比度层级排版。
- **Requirements**: R6-P4
- **Dependencies**: None
- **Files**:
  - `app/src/main/java/com/solo/purecast/ui/screen/MainTvScreen.kt`
- **Approach**:
  1. 在歌曲标题 `Text` 上添加 `Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000)`。
  2. 当标题未超出宽度时静止居左，超出宽度时在静止 2 秒后平滑滚动循环。
  3. 调整字体样式：歌曲名 38sp Bold 白色，歌手 22sp Medium 次级灰（`#B0B0B8`），专辑名 16sp 次级灰，符合 WCAG AA 大屏对比度标准。
- **Test scenarios**:
  - 推送包含超长歌曲标题（如 50 个字符）的音频，验证标题在 2 秒后开始平滑匀速横向滚动。
- **Verification**: 跑马灯滚动顺畅无掉帧，文字排版大气优美。

---

### U5. 遥控器 MENU 键拦截与全功能侧边栏抽屉升级 (MENU Key & Full Settings Drawer)
- **Goal**: 在 `MainActivity` 拦截遥控器 `KEYCODE_MENU` 键呼出侧边栏；在 `SettingsDrawer` 中完善 UPnP 设备改名、USB 独占开关、权限自检面板、硬件时钟/缓冲监视面板以及让渡模式切换。
- **Requirements**: R6-P5
- **Dependencies**: None
- **Files**:
  - `app/src/main/java/com/solo/purecast/ui/MainActivity.kt`
  - `app/src/main/java/com/solo/purecast/ui/screen/SettingsDrawer.kt`
  - `app/src/main/java/com/solo/purecast/service/PlaybackService.kt`
- **Approach**:
  1. `MainActivity.kt`：在 `onKeyDown` 中拦截 `KeyEvent.KEYCODE_MENU`，通知 UI 显示侧边栏抽屉。
  2. `SettingsDrawer.kt` 扩充 5 大功能板块：
     - **设备广播名设置**：显示当前名称，支持遥控器修改并持久化保存。
     - **USB DAC 独占开关**：支持切换是否强制独占模式。
     - **系统权限与后台豁免自检**：直观展示录音权限、悬浮窗、电池白名单、防自动回收状态（绿色打勾或黄色待配置）。
     - **硬件链路与缓冲监视器**：显示物理采样率、位深、UAC 显式反馈时钟瞬时值、RingBuffer 缓冲水位与丢包计数（0 overrun/underrun）。
     - **让渡策略模式切换**：支持「空闲 10 秒自动让渡（默认）」与「仅退出 App 时彻底释放」切换。
- **Test scenarios**:
  - 按电视遥控器 MENU 键，侧边栏瞬间滑出；按返回键抽屉平滑缩回。
  - 侧边栏实时刷新当前 DAC 物理采样率与反馈时钟值。
- **Verification**: MENU 键响应灵敏，侧边栏各大板块数据准确，设置项持久化有效。

---

### U6. 3 分钟无操作自动隐藏控制栏防烧屏机制 (Auto-Hide Controls & Screen Burn Protection)
- **Goal**: 实现播放状态下 3 分钟无遥控器按键操作自动隐藏底部控制栏，仅保留封面、歌名与徽章，并配合微像素平移（Pixel Shift）防止电视烧屏；按任意键即刻复原。
- **Requirements**: R6-P6
- **Dependencies**: U2, U3
- **Files**:
  - `app/src/main/java/com/solo/purecast/ui/screen/MainTvScreen.kt`
  - `app/src/main/java/com/solo/purecast/ui/MainActivity.kt`
- **Approach**:
  1. 在 `MainTvScreen` 中维护 `showControls` 状态，外层包裹 `AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut())`。
  2. 监听按键事件或在 Activity 级分发按键通知重置倒计时。
  3. 当处于 `PLAYING` 且超时 180 秒无按键时，`showControls` 自动置为 `false`。
  4. 当按任意遥控器按键时，立即将 `showControls` 置为 `true` 并重新计时。
  5. 配合现有的 10 秒正弦微像素位移，实现全方位防烧屏。
- **Test scenarios**:
  - 模拟 3 分钟无操作，控制按钮与进度条平滑淡出。
  - 按下遥控器任意方向键，控制栏瞬间淡入恢复。
- **Verification**: 防烧屏状态机运行稳定，电视大屏长时间播放安全无忧。

---

## Verification Contract

### 质量门禁与验证命令
1. **构建与依赖校验**:
   ```bash
   ./gradlew assembleDebug
   ```
2. **真机无线安装部署**:
   ```bash
   ./scripts/adb_remote_install.sh 127.0.0.1 5555
   ```
3. **遥控器按键自动化模拟**:
   ```bash
   adb shell input keyevent 82 # MENU 键呼出侧边栏
   adb shell input keyevent 4  # BACK 键关闭抽屉
   adb shell input keyevent 19 # UP 键聚焦进度条
   adb shell input keyevent 20 # DOWN 键聚焦播放栏
   ```
4. **大屏视觉截图断言**:
   ```bash
   adb exec-out screencap -p > /tmp/tv_ui_verification.png
   ```

---

## Definition of Done

- [ ] 高清网络封面成功异步加载，Palette 提取主色调驱动全屏流光背景。
- [ ] 纤细进度条与时间轴呈现良好，支持 D-Pad 上下左右焦点交互。
- [ ] 「上一曲」与「下一曲」按钮齐备，实时无边界流时置灰并友好拦截提示。
- [ ] 歌曲名称超长平滑横向跑马灯滚动。
- [ ] 遥控器 MENU 键呼出全功能侧边栏抽屉，权限自检与硬件监控运行正常。
- [ ] 3 分钟无操作自动淡出控制栏，按任意键即时恢复。
