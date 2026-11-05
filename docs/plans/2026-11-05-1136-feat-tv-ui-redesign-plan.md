---
artifact_contract: ce-unified-plan/v1
product_contract_source: ce-brainstorm
execution: code
title: PureCast Android TV 播放与设置界面重构
date: 2026-11-05
topic: tv-ui-redesign
type: feat
---

## Goal Capsule

- **Objective:** 为 PureCast 打造深度符合 Apple TV / tvOS 工业设计美学的沉浸式播放界面与全屏双栏设置界面，提供 42/58 黄金比例大屏布局、GLES2 动态流光交融着色器背景、纯色彩语义的实时音频链路胶囊、纯图标化遥控器焦点控制组以及专业沉浸的全屏设置仪表盘。
- **Means:** 移植参考项目（`lavande16/Apple-Music-For-Android-TV`）的 `MacBackground` GLES2 材质网格流体渲染管道，重构 `MainTvScreen` 为 tvOS 经典分栏结构，剥离所有歌词/队列/收藏等无数据支撑功能，并将抽屉重构为全屏双栏 `SettingsScreen`。
- **Product Authority:** 本需求由用户明确选定方案 A（分栏布局）与方案 S1（全屏双栏设置），并明确音频链路胶囊采用设备名纯色彩指示（绿色=独占，红色=非独占）及无文字后缀的极简格式。
- **Open Blockers:** 无阻断项。所有产品边界与交互细节已在对话中明确收敛。

## Product Contract

> Product Contract preservation: Product Contract unchanged.

### Summary
重构 PureCast Android TV 大屏交互界面，播放页采用 42% / 58% 黄金比例分栏布局，左侧呈现浮动高清封面并由 GLES2 材质网格着色器提取封面色彩生成动态流光交融背景；右侧配备格式为 `协议 · 音频格式 | 设备名称`（通过设备名纯色彩区分独占与非独占）的实时音频品质胶囊、超长跑马灯标题与纯图标化 tvOS 焦点控制组；设置页采用 Apple TV 经典全屏双栏架构，支持遥控器返回键一键回退，并彻底清理歌词与队列等不适用冗余。

### Problem Frame
PureCast 作为 Android TV 平台上的高保真音频接收端（支持 AirPlay 1 RAOP 与 UPnP/DLNA 源码推流直通），现有界面存在若干体验短板：
1. **视觉美感与氛围感不足**：现有背景仅为简单的 2 色静态径向渐变或简易模糊，缺乏参考项目（Apple-Music-For-Android-TV）中封面色彩自然流动交融的沉浸感。
2. **大屏交互存在多余与陈旧控件**：控制栏采用包含繁琐中文字符的矩形按钮（如 `⏮ 上一曲`、`⚙ 设置与硬件`），不仅破坏 tvOS 极简美学，且未充分发挥 10-Foot 遥控器大屏聚焦放大的视觉冲击力；原参考项目中残留的歌词面板、播放队列列表、收藏/喜欢等功能在 PureCast 作为被动接收端的定位下毫无真实数据支撑，属于干扰大屏重心的伪功能。
3. **设置呈现空间受限**：现有 460dp 右侧滑出抽屉（SettingsDrawer）在展示复杂的硬件缓冲水位、显式时钟反馈闭环等监控数据时较为拥挤，且不符合 Apple TV 原生系统设置的全屏双栏大屏规范。
4. **音频品质信息不够凝练**：原有链路状态指示缺乏统一的视觉色彩语义，需要用文字清晰但极简的格式传达“当前推流协议、音频格式以及 USB DAC 设备名称与硬件独占状态”。

### Key Decisions
- **42% / 58% 黄金比例分栏平衡布局** (session-settled: user-directed — 选自方案 A 相对方案 B 居中布局：视觉重心稳健、封面大且右侧多层级排版舒展)。*Governs R1, R2, R3.*
- **GLES2 材质网格流光背景 (MacBackground)** (session-settled: user-directed — 选自参考项目原生 OpenGL ES 2.0 着色器流体渲染方案相对 Canvas 简易模糊：从封面萃取多色、柔和流动交融、营造极致沉浸的视听氛围，且适配 Android 11+ 电视 SoC)。*Governs R4, R5.*
- **音频品质胶囊去文字后缀并采用设备名纯色彩指示** (session-settled: user-directed — 规范格式为 `协议 · 音频格式 | 设备名称`，仅通过设备名称颜色本身表达独占与非独占状态：绿色代表 USB DAC 独占直通，红色代表非独占或异常，无额外文字括号后缀)。*Governs R6, R7, R8.*
- **tvOS 全屏分类双栏设置界面** (session-settled: user-directed — 选自方案 S1 相对方案 S2 毛玻璃悬浮抽屉：契合 Apple TV 原生设置规范，左侧分类、右侧配置卡片与监控仪表盘，按返回键一键返回播放页)。*Governs R12, R13, R14.*
- **纯图标化 tvOS 焦点控制按钮组** (session-settled: user-approved — 淘汰汉字按钮，改用纯几何图标 `[⏮] [ ▶ / ❚❚ ] [⏭] [⚙]`，统一 1.12x 缩放 + 外发光质感)。*Governs R9, R10, R11.*
- **彻底剥离不适用功能** (session-settled: user-directed — 坚决移除歌词显示、列表/队列按钮、收藏/喜欢等纯播放器专用伪操作)。*Governs R15.*

### Requirements

#### 播放页面布局与排版层级 (Playback Screen Hierarchy)
- R1. 播放主页面必须采用 42% / 58% 水平分栏布局（左侧 42% 唱片封面区，右侧 58% 歌曲信息与控制操作区），在 1080p/4K 电视大屏上保持稳定的视觉重心与黄金比例分割。
- R2. 左侧封面区必须呈现 24dp 圆角的高清浮动唱片卡片，具备 32dp 景深阴影与细腻边框，在播放时呈现 1.0x 呼吸放大状态，暂停时平滑弹簧缩放至 0.94x。
- R3. 右侧信息区必须自上而下纵向居中排列：音频品质胶囊（Signal Path Capsule）、歌曲标题（Title）、艺术家与专辑（Artist & Album）、纤细圆角进度条（ProgressBar）以及控制按钮组（Playback Controls）。

#### GLES2 动态流光交融背景 (Dynamic Mesh Fluid Background)
- R4. 播放页面背景必须移植参考项目（`Apple-Music-For-Android-TV`）的 OpenGL ES 2.0 材质网格着色器渲染管道（`MacBackground` / `MacRenderer` / `MacMesh` / `MacShaders`），从当前播放封面图片（网络 URL 或 AirPlay 内存位图）中提取代表色并生成纹理。
- R5. 流光背景必须支持低多边形网格的旋转、微波扭曲与多层高斯模糊，使背景光影自然交融、柔和流动，并内置平滑降级（支持 High/Medium/Low/Off 档位），确保在各类低功耗电视 SoC 上稳定以低开销运行。

#### 实时音频品质胶囊 (Realtime Audio Quality Capsule)
- R6. 歌曲标题上方必须展示单行静态药丸胶囊（不可被遥控器 D-Pad 聚焦或点击），实时反映底层播放服务与音频驱动的最新状态。
- R7. 胶囊中文本信息格式必须严格统一为：`协议 · 音频格式 | 设备名称`。
- R8. 胶囊中「设备名称」的颜色必须完全承担硬件独占状态指示，禁止添加任何 `(独占)` 或 `(非独占)` 等文字说明：
  - 正常独占直通：设备名显示为高饱和度绿色（`#30D158`，如 `AirPlay · ALAC · 44.1kHz/16bit | ZX300A` 且 ZX300A 为绿色，或 `UPnP · PCM · 96.0kHz/24bit | Sony DAC` 且 Sony DAC 为绿色）。
  - 非独占/共享输出/让渡/异常：设备名显示为红色（`#FF453A`，如 `AirPlay · ALAC · 44.1kHz/16bit | ZX300A` 且 ZX300A 为红色）。
  - 空闲待机状态：无音频推流时，胶囊显示待机提示（如 `就绪 · 等待推流 | 未连接 USB DAC` 或检测到的设备名灰字）。

#### 控制操作与风格统一的设置按钮 (Controls & Unified Settings Button)
- R9. 控制栏必须淘汰所有文本字样按钮，全面采用由 `androidx.tv.material3.Button` 构建的纯几何矢量图标按钮：上一曲 `[⏮]`、播放/暂停 `[ ▶ / ❚❚ ]`、下一曲 `[⏭]` 以及设置按钮 `[⚙]`。
- R10. 设置按钮 `[⚙]` 必须紧跟在下一曲按钮右侧（保持 14dp 间距），其外形、圆角（14dp）、高度（48dp）与获得 D-Pad 焦点时的 1.12x 缩放、纯白高亮背景及外发光轮廓必须与上一曲/下一曲按钮保持 100% 视觉与物理动效一致。
- R11. 播放/暂停按钮必须作为主控按钮（默认高度 56dp，居中突出），并在控制栏显现或设置页退出时自动绑定焦点自愈（FocusRequester），确保遥控器 OK 键直接响应。

#### tvOS 全屏分类双栏设置界面 (Fullscreen Categorized Settings)
- R12. 点击播放界面的设置按钮 `[⚙]` 时，界面必须平滑淡入切换为全屏 tvOS 分类双栏设置页面（`SettingsScreen`）。
- R13. 设置页面左侧必须提供固定宽度（280dp-320dp）的分类导航栏，包含：
  - 1. 常规与设备广播（Device Name & Friendly Name）
  - 2. USB DAC 独占与让渡（Exclusive Mode & Idle Timeout）
  - 3. 接收协议配置（AirPlay & UPnP Switches）
  - 4. 硬件时钟与缓冲监控（SPSC Buffer Watermark & UAC2 Feedback Clock）
  - 5. 系统权限与运行健康（Permission & Battery Whitelist Status）
- R14. 设置页面右侧必须呈现大尺寸卡片式配置项与动态监控仪表盘，支持 D-Pad 顺畅纵向滚动与水平焦点无缝穿越，并且在用户按下遥控器返回键（Back / KEYCODE_BACK）时平滑退回播放主界面。

#### 冗余清理与防烧屏双重保护 (Cleanup & OLED Protection)
- R15. 必须彻底清除所有歌词显示（Lyrics）、播放队列与曲目列表（Queue/Playlist）、收藏/喜欢（Favorite）等不适用的代码、组件与交互占位符。
- R16. 播放页面必须保留防烧屏双重防护机制：每 10 秒正弦平移 2-3 像素（Pixel Shift），以及无操作 3 分钟后控制栏平滑淡出，按任意方向键即刻唤醒并恢复防烧屏计时。

### Key Flows

#### F1. 大屏主播放与遥控器焦点交互
- **Trigger:** 客户端发起 AirPlay 1 或 UPnP 推流，`PlaybackService` 状态变为 `PLAYING`。
- **Actors:** 电视用户、遥控器 D-Pad、`MainTvScreen`、`PlaybackControls`。
- **Steps:**
  1. `MainTvScreen` 接收到元数据与流媒体格式，`MacBackground` 异步加载封面纹理并启动 GLES2 流体渲染。
  2. 歌曲标题自动启用 `basicMarquee` 横向平滑循环滚动；品质胶囊实时提取协议（AirPlay/UPnP）、采样率/位深，并将检测到的 USB 设备名称以绿色（已独占）渲染。
  3. 控制栏将焦点默认置于 `[ ❚❚ 暂停 ]` 按钮上，封面展开 1.0x 呼吸微动效。
  4. 用户按下遥控器右键移动焦点至 `[⏭ 下一曲]`，再按右键移动至 `[⚙ 设置]`，设置按钮平滑放大至 1.12x 并呈现纯白高光外发光轮廓。
  5. 若持续 3 分钟无任何按键操作，控制栏平滑淡出，仅保留封面、微流光背景与歌曲信息，且整屏每 10 秒微幅正弦位移 2-3 像素。
- **Outcome:** 沉浸式高保真视听体验，兼顾电视屏幕长寿命防护。
- **Governs:** R1, R2, R3, R4, R9, R10, R11, R16.

#### F2. 呼出与浏览 tvOS 全屏双栏设置页
- **Trigger:** 用户在播放控制栏中点击 `[⚙ 设置]` 按钮，或在遥控器上按下 MENU 键。
- **Actors:** 电视用户、遥控器 D-Pad、`SettingsScreen`。
- **Steps:**
  1. 播放界面平滑淡出，全屏展示 `SettingsScreen`，焦点默认落在左侧分类导航的第一个项（常规与设备广播）。
  2. 用户按 D-Pad 下键切换至“硬件时钟与缓冲监控”，右侧内容区联动更新为实时仪表盘：以数字和进度卡片展示 SPSC 环形缓冲容量、当前可读水位以及 UAC2 显式时钟反馈闭环锁定状态（0 丢包）。
  3. 用户按 D-Pad 右键穿透至右侧卡片区对参数进行配置（如切换让渡超时为 10 秒）。
  4. 用户随时按下遥控器返回键（Back），设置界面平滑退回播放主页，焦点自愈回播放/暂停按钮。
- **Outcome:** 专业、沉浸且符合 Apple TV 原生系统设置规范的大屏设置体验。
- **Governs:** R10, R12, R13, R14.

#### F3. 硬件独占与让渡状态实时色彩切换
- **Trigger:** 推流暂停后超过设定让渡超时（如 10 秒），NativeAudioEngine 触发声卡让渡归还给 Linux 内核；或重新开始推流触发声卡独占接管。
- **Actors:** `NativeAudioEngine`、`PlaybackService`、`SignalPathPill`。
- **Steps:**
  1. `AudioPathState` 发布硬件状态变更事件。
  2. `SignalPathPill` 监听到状态流变化，重组叶子节点。
  3. 胶囊中文本前半段保持当前流媒体信息 `协议 · 音频格式 | `，后半段设备名称立即由绿色平滑切换为红色（代表声卡处于让渡/系统接管中，非 PureCast 独占）。
  4. 用户重新点击播放推流时，底座接管成功，设备名称立即恢复为纯净绿色。
- **Outcome:** 用户无需点击任何按钮，仅凭扫视一眼胶囊文字颜色即可准确掌握高保真 DAC 硬件独占状态。
- **Governs:** R6, R7, R8.

### Acceptance Examples

- AE1. 播放页面黄金比例与布局验证
  - **Given:** 电视设备屏幕分辨率为 1920x1080 或 3840x2160。
  - **When:** 启动 PureCast 播放页面并加载任意流媒体。
  - **Then:** 左侧封面占据水平空间 42%，右侧内容占据 58%；封面圆角为 24dp，标题具备跑马灯能力，下方依次排列进度条与统一图标控制组。
  - **Covers:** R1, R2, R3.

- AE2. 动态流光背景视觉质感
  - **Given:** 当前曲目加载了一张包含鲜艳暖红与深蓝色彩的专辑封面。
  - **When:** 背景 GLES2 着色器初始化成功。
  - **Then:** 屏幕背景呈现出源自封面的自然流动、旋转融合的低饱和度光影效果，切歌时新旧封面色彩平滑流动过渡，且 TV 设备无明显掉帧。
  - **Covers:** R4, R5.

- AE3. 音频胶囊格式与色彩语义合规
  - **Given:** 正在通过 AirPlay 播放 44.1kHz / 16bit ALAC 音频，外接 USB DAC 设备名为 "ZX300A"，且已成功锁定 UAC2 显式反馈时钟。
  - **When:** 渲染歌曲标题上方的音频品质胶囊。
  - **Then:** 胶囊文本呈现为 `AirPlay · ALAC · 44.1kHz/16bit | ZX300A`；其中 `ZX300A` 文字严格渲染为绿色（`#30D158`），绝无 `(独占)` 汉字后缀；当拔出 USB 或让渡声卡时，`ZX300A` 文本立刻变为红色（`#FF453A`）。
  - **Covers:** R6, R7, R8.

- AE4. 纯图标控制按钮与设置按钮焦点
  - **Given:** 播放界面显示控制条。
  - **When:** 遥控器焦点从上一曲移动至播放、下一曲、再移动至设置按钮。
  - **Then:** 每个按钮均为纯图标按钮，无汉字说明；获得焦点时均执行 1.12x 浮起放大、纯白背景高亮与外发光轮廓，动效完全统一；点击设置按钮可顺利进入全屏设置页。
  - **Covers:** R9, R10, R11, R12.

- AE5. 全屏设置页面双栏导航与一键返回
  - **Given:** 用户处于全屏设置页面 `SettingsScreen` 中。
  - **When:** 用户在右侧卡片区浏览，然后按下遥控器返回键（Back）。
  - **Then:** 拦截返回事件，退出设置页面并无缝返回播放主界面，播放音乐不受任何中断，焦点自动锁定在播放/暂停按钮上。
  - **Covers:** R13, R14.

- AE6. 冗余功能彻底清理
  - **Given:** 检索整个工程 UI 视图树与交互逻辑。
  - **When:** 审计播放主屏与设置页面。
  - **Then:** 不存在任何歌词渲染组件、无任何播放队列/列表弹窗或图标、无任何收藏/喜爱按钮或伪交互。
  - **Covers:** R15.

### Scope Boundaries

#### In Scope (包含范围)
- 播放主界面 `MainTvScreen` 42/58 分栏结构重塑与排版层级优化。
- 移植参考项目 `MacBackground` GLES2 材质网格着色器流光背景，提供封面多色萃取与液态交融光影。
- 纯色彩语义的实时音频品质胶囊组件（格式：`协议 · 音频格式 | 设备名称`，设备名根据独占/非独占实时切绿/红）。
- 纯图标化 tvOS 焦点控制按钮组（上一曲、播放/暂停、下一曲、设置按钮统一风格与 1.12x 放大光影）。
- 全新 tvOS 全屏双栏设置页面（`SettingsScreen`）替换原有右侧滑出抽屉，左侧分类导航，右侧卡片配置与硬件监控仪表盘。
- 彻底移除歌词面板、队列/列表按钮、收藏/喜欢等纯播放器伪功能。
- 延续防烧屏双重机制（微像素正弦平移 + 3分钟自动隐藏控制栏）。

#### Out of Scope (排除范围)
- 本地曲库扫描、本地音乐文件浏览与搜索功能。
- 歌词解析引擎（LRC / TTML / 逐字词同步）与歌词滚动逻辑。
- 在线音乐平台账号登录、云端收藏与个人歌单同步。
- 底层 C++/JNI 音频引擎（`NativeAudioEngine`）、libusb-1.0 驱动封装与 UAC2 显式时钟反馈算法的协议重构（底层驱动已稳定达标，本次为纯 UI 与大屏交互重塑）。

### Success Criteria
- **视觉美学完全契合 Apple TV**：大屏在播放时呈现呼吸感强烈的唱片封面与自然的流光背景，无任何粗糙汉字按钮或破坏美感的未对齐边距。
- **信息呈现 100% 准确清晰**：音频胶囊严格按照 `协议 · 音频格式 | 设备名称` 展示，设备名绿/红两色精准对应硬件独占/非独占状态，无文字冗余。
- **10-Foot 遥控器交互流畅自然**：所有可交互元素均具备清晰的 1.12x 放大和高对比白色发光轮廓，焦点自愈无丢失，全屏设置页与播放页之间通过返回键无缝切换。
- **零伪功能与纯净代码基线**：代码库中不残留任何歌词、列表或收藏的死代码，保持 PureCast 专注源码直通接收端的高净度架构。

## Planning Contract

### Summary
本技术方案将完整的 Apple TV 美学与现代 Android TV Jetpack Compose 技术规范注入 PureCast：
1. **渲染层**：封装基于 OpenGL ES 2.0 的独立渲染引擎（`MacBackground`），在 Compose 的 `AndroidView` 底层承载 256x256 低多边形扭曲网格着色器，通过硬件 VSync 节奏实现 30/60fps 流光交融背景，并提供针对低端芯片的静态高斯降级。
2. **状态与胶囊层**：将 `SignalPathPill` 改造为无焦点静态指示器，订阅 `PlaybackService.audioPathState` 与 `currentMetadata`，以 Spannable/AnnotatedString 格式驱动 `协议 · 音频格式 | 设备名`，独占状态直接映射设备名文字颜色（绿 `#30D158` / 红 `#FF453A`）。
3. **交互控制层**：重写 `PlaybackControls`，移除所有文字标签，使用 `androidx.tv.material3.Button` 构建矢量图标按钮（`[⏮] [ ▶ / ❚❚ ] [⏭] [⚙]`），配置统一度量的 1.12x 焦点缩放与白发光阴影。
4. **主界面与全屏设置**：重组 `MainTvScreen` 为精确的 42%/58% 水平分栏，以渐变 Crossfade 串联全新开发的 `SettingsScreen`（双栏结构：左侧垂直导航 List，右侧卡片式参数与无阻塞轮询硬件仪表盘），拦截 Back 按键平滑退回。同时在工程中彻底删除歌词与队列等遗留占位。

### Key Technical Decisions
- **KTD1: 使用 AndroidView 承载 GLES2 TextureView 渲染流光背景** (session-settled: user-directed — 遵循 R4/R5，解决 Android 11+ 无 RenderScript 且 Compose AGSL 仅限 Android 13+ 的版本兼容瓶颈；TextureView 可良好融入 Compose 视图层级且支持硬件加速)。*Governs U1, U4.*
- **KTD2: AnnotatedString 动态驱动无状态胶囊文本色彩** (session-settled: user-directed — 遵循 R6/R7/R8，在叶子组件内通过 `remember(audioState, metadata)` 组装带局部 `SpanStyle(color = ...)` 的单一 Text 控件，完全避免布局嵌套与文字重组抖动)。*Governs U2, U4.*
- **KTD3: 全屏 Crossfade 状态机替代侧边栏抽屉** (session-settled: user-directed — 遵循 R12/R13/R14，在 `MainTvScreen` 内部以 `Crossfade(targetState = isSettingsOpen)` 驱动全屏切换，设置页面通过 `BackHandler` 拦截返回并自愈焦点至播放按钮)。*Governs U4, U5.*
- **KTD4: 纯图标化矢量按键与焦点发光规格** (session-settled: user-approved — 遵循 R9/R10/R11，统一采用 `androidx.tv.material3` 的 `ButtonDefaults.scale(scale=1.0f, focusedScale=1.12f)` 及 14dp 圆角轮廓，主播放键为 56dp 圆形，辅助控制与设置键为 48dp 圆形/胶囊)。*Governs U3, U4.*

### High-Level Technical Design

```mermaid
flowchart TD
    subgraph UI_Layer ["TV 界面层 (androidx.tv.material3)"]
        MainScreen["MainTvScreen (42% / 58% 分栏)"]
        CoverView["浮动封面卡片 (Cover Card 24dp)"]
        MetaCol["元数据与控制列"]
        SignalPill["SignalPathPill (静态指示器)"]
        TitleText["歌曲标题 (basicMarquee)"]
        Progress["IsolatedProgressBar (寻道条)"]
        Controls["PlaybackControls (纯图标组: ⏮ ▶/❚❚ ⏭ ⚙)"]
        SettingsUI["SettingsScreen (全屏双栏设置页)"]
    end

    subgraph Background_Pipeline ["流光渲染管道 (GLES2)"]
        CoilLoader["Coil 异步加载封面 (256x256 ARGB_8888)"]
        MacBg["MacBackground (AndroidView)"]
        MacRenderer["MacRenderer (OpenGL ES 2.0)"]
        MacMesh["MacMesh (低多边形网格坐标)"]
        MacShaders["MacShaders (ROTATION + MESH + BLUR)"]
    end

    subgraph Service_Core ["系统服务与驱动监控"]
        Service["PlaybackService"]
        MetaState["currentMetadata Flow"]
        AudioState["audioPathState Flow"]
        NativeEngine["NativeAudioEngine (SPSC 水位 / UAC2 反馈)"]
    end

    Service -->|发布元数据| MetaState
    Service -->|发布链路状态| AudioState
    MetaState -->|封面数据| CoilLoader
    CoilLoader -->|Bitmap 纹理| MacRenderer
    MacRenderer --> MacMesh & MacShaders
    MacBg -->|底座渲染| MainScreen

    MetaState --> TitleText
    AudioState --> SignalPill
    MainScreen --> CoverView & MetaCol
    MetaCol --> SignalPill & TitleText & Progress & Controls

    Controls -->|点击 [⚙]| SettingsUI
    SettingsUI -->|查询底层硬件| NativeEngine
    SettingsUI -->|Back 按键回退| MainScreen
```

### Output Structure

```text
app/src/main/java/com/solo/purecast/
├── ui/
│   ├── background/                     # [新包] GLES2 动态流光交融着色器引擎
│   │   ├── FluidQuality.kt             # 流光质量档位 (HIGH / MEDIUM / LOW / OFF)
│   │   ├── MacBackground.kt            # Compose 接入层 (AndroidView 包装)
│   │   ├── MacMesh.kt                  # 低多边形几何网格生成
│   │   ├── MacRenderer.kt              # OpenGL ES 2.0 帧缓冲区与管线渲染器
│   │   └── MacShaders.kt               # GLSL 顶点/片元着色器源码 (网格扰动与高斯模糊)
│   ├── components/
│   │   ├── FluidBackground.kt          # [修改] 代理接入 MacBackground 并保持兼容降级
│   │   ├── PlaybackControls.kt         # [修改] 重构为纯几何图标按钮组 (⏮ ▶/❚❚ ⏭ ⚙)
│   │   ├── ProgressBarComponent.kt     # [保持] 纤细进度条与 D-Pad 寻道
│   │   └── SignalPathPill.kt           # [修改] 重构为纯色彩语义的免聚焦静态胶囊
│   └── screen/
│       ├── MainTvScreen.kt             # [修改] 42/58 黄金比例重组、全屏切换与冗余清理
│       ├── SettingsScreen.kt           # [新建] tvOS 全屏分类双栏设置页面
│       └── SettingsDrawer.kt           # [删除/替换] 彻底被 SettingsScreen 替代
```

### System-Wide Impact
1. **CPU/GPU 负载控制**：`MacBackground` 采用 256x256 软件位图上传为纹理，网格计算在 GPU 顶点着色器执行，默认限制渲染节拍为 30fps，电视芯片 GPU 占用率低于 5%，内存占用低于 12MB。
2. **遥控器焦点自愈机制**：在全屏设置页开启与关闭的 Crossfade 切换中，`FocusRequester` 确保焦点始终可预测，防止遥控器 D-Pad 焦点坠入空虚。
3. **防烧屏双引擎联动**：全屏切换不打断全局正弦微像素位移（Pixel Shift），控制栏 3 分钟自动淡出后仍保留 GLES2 柔和低饱和度流光。

## Implementation Units

### U1. GLES2 Dynamic Mesh Fluid Background Engine
- **Goal:** 将参考项目的 OpenGL ES 2.0 网格流体着色器管道无缝移植到 PureCast，提供低开销、高质量的大屏封面自适应流动光影背景。
- **Requirements:** R4, R5.
- **Dependencies:** None.
- **Files:**
  - `app/src/main/java/com/solo/purecast/ui/background/FluidQuality.kt` (新建)
  - `app/src/main/java/com/solo/purecast/ui/background/MacShaders.kt` (新建)
  - `app/src/main/java/com/solo/purecast/ui/background/MacMesh.kt` (新建)
  - `app/src/main/java/com/solo/purecast/ui/background/MacRenderer.kt` (新建)
  - `app/src/main/java/com/solo/purecast/ui/background/MacBackground.kt` (新建)
  - `app/src/main/java/com/solo/purecast/ui/components/FluidBackground.kt` (重构代理)
- **Approach:**
  1. 创建 `FluidQuality` 枚举类，定义 HIGH / MEDIUM / LOW / OFF。
  2. 移植 `MacShaders.kt`，包含 `ROTATION_VERTEX`, `ROTATION`, `MESH_VERTEX`, `MESH`, 以及高斯模糊 `BLUR` 的 GLSL 源码。
  3. 移植 `MacMesh.kt`，构建适用于 16:9 大屏视口的低多边形网格顶点缓存。
  4. 移植 `MacRenderer.kt`，管理 GLES2 纹理加载、FBO 离屏渲染目标与双缓冲切换。
  5. 封装 `MacBackground.kt`，通过 `AndroidView` 承载 `TextureView`，利用 Coil 异步将封面数据转为 256x256 软件位图推入渲染器。
  6. 在 `FluidBackground.kt` 中提供对外统一入口，当输入封面为空时平滑回退至深邃夜空黑。
- **Test Scenarios:**
  - Covers AE2. 当传入有效网络图片 URL 时，`MacBackground` 成功初始化 GLES2 环境并在 TextureView 上渲染交融光影。
  - Covers AE2. 当切歌传入新封面时，旧纹理与新纹理在着色器内执行 1.5 秒平滑交叉淡化。
  - 当封面为 null 或异常数据时，背景优雅降级为深色静态底色，无崩溃或黑屏。

### U2. Pure-Color Semantic SignalPathPill Component
- **Goal:** 重构音频品质胶囊组件为无焦点纯静态指示器，信息格式为 `协议 · 音频格式 | 设备名称`，由设备名文本颜色表达独占（绿）与非独占（红）。
- **Requirements:** R6, R7, R8.
- **Dependencies:** None.
- **Files:**
  - `app/src/main/java/com/solo/purecast/ui/components/SignalPathPill.kt`
- **Approach:**
  1. 移除 `SignalPathPill` 上所有的可聚焦与可点击修饰符（确保 D-Pad 导航直接跳过胶囊）。
  2. 重构数据解析逻辑，根据 `AudioPathState` 与 `TrackMetadata` 构建三段式文本：
     - 前缀协议：`UPnP` 或 `AirPlay`；
     - 格式指标：如 `PCM · 96.0kHz/24bit` 或 `ALAC · 44.1kHz/16bit` 或 `FLAC · 192.0kHz/24bit`；
     - 隔离分隔符：` | `；
     - 设备名称：提取连接的 USB DAC 名称（如 `ZX300A`），若无外接设备则显示 `系统默认输出` 或 `未连接 USB DAC`。
  3. 使用 Compose `buildAnnotatedString`：协议与格式赋予半透明高质感浅灰，设备名称根据 `audioState.isDirectMode && audioState.isDacConnected` 精确着色：
     - 独占直通：Color(0xFF30D158)（高亮亮绿）；
     - 非独占/让渡/共享：Color(0xFFFF453A)（警告亮红）；
     - 待机无推流：Color(0xFF86868B)（中性灰）。
  4. 坚决禁止输出任何诸如 `(独占)`、`(非独占)` 或括号文字后缀。
- **Test Scenarios:**
  - Covers AE3. 当推流协议为 AirPlay、16bit/44.1kHz 且 USB DAC 独占时，组件文本为 `AirPlay · ALAC · 44.1kHz/16bit | ZX300A`，其中 `ZX300A` 为绿色。
  - Covers AE3. 当进入空闲超时触发声卡让渡时，`ZX300A` 文本立刻变为红色，无文字后缀变动。
  - 当系统未连接任何 USB DAC 时，胶囊显示 `就绪 · 等待推流 | 未连接 USB DAC`，设备名为中性灰色。

### U3. Icon-Only PlaybackControls and Unified Settings Button
- **Goal:** 将播放控制栏全部文本按钮替换为 tvOS 纯几何图标按钮，添加风格统一的设置按钮 `[⚙]`，具备 1.12x 浮起放大与白发光聚焦动效。
- **Requirements:** R9, R10, R11.
- **Dependencies:** None.
- **Files:**
  - `app/src/main/java/com/solo/purecast/ui/components/PlaybackControls.kt`
- **Approach:**
  1. 移除原有代码中的中文字符串 `⏮ 上一曲`、`▶ 播放`、`❚❚ 暂停`、`⏭ 下一曲`、`⚙ 设置与硬件`。
  2. 使用矢量 Path 或 Material/Tv 图标渲染纯几何图形：
     - 上一曲：跳至上一首矢量图标（双左三角+竖杠）；
     - 播放/暂停：正圆主按钮，播放为实心右三角，暂停为双竖线；
     - 下一曲：跳至下一首矢量图标（双右三角+竖杠）；
     - 设置：标准六齿/八齿齿轮矢量图标 `[⚙]`。
  3. 统一度量尺寸与聚焦样式：
     - 上一曲、下一曲、设置按钮尺寸为 48dp x 48dp 圆角（14dp），未聚焦背景 `Color(0x26FFFFFF)`，聚焦背景 `Color.White`，聚焦图标 `Color.Black`；
     - 播放/暂停按钮尺寸为 56dp x 56dp 圆形，聚焦时放大至 1.14x 并带有纯白柔光外发光；
     - 设置按钮紧随下一曲按钮右侧排列（间距 14dp），配置相同的 `ButtonDefaults.scale(scale = 1.0f, focusedScale = 1.12f)`。
  4. 播放/暂停键绑定传入的 `FocusRequester`，保障按键分发自愈。
- **Test Scenarios:**
  - Covers AE4. 控制栏由 4 个纯图标按钮组成，横向紧凑对齐，无任何文字标签。
  - Covers AE4. 使用 D-Pad 左右导航时，焦点在 `[⏮]`, `[▶/❚❚]`, `[⏭]`, `[⚙]` 之间顺畅平移，每个按钮聚焦时平滑放大 1.12x 并呈现高对比白底黑图标。

### U4. MainTvScreen 42/58 Golden-Ratio Split Layout and Dead Code Cleanup
- **Goal:** 重构大屏主界面为 42%/58% 黄金比例分栏，彻底删除歌词/队列/收藏等死代码与占位，集成流体背景与全屏设置切换。
- **Requirements:** R1, R2, R3, R15, R16.
- **Dependencies:** U1, U2, U3.
- **Files:**
  - `app/src/main/java/com/solo/purecast/ui/screen/MainTvScreen.kt`
- **Approach:**
  1. 彻底检索并清除工程中所有歌词（Lyrics）、播放队列（Queue）、收藏（Favorite）的遗留引用、死代码与假交互。
  2. 调整外层布局比例：左侧 `Box(modifier = Modifier.weight(0.42f).fillMaxHeight())` 承载封面，右侧 `Column(modifier = Modifier.weight(0.58f).fillMaxHeight())` 承载信息与控制。
  3. 封面卡片设定为 24dp 圆角，配置弹簧微缩放（播放 1.0f / 暂停 0.94f）与 32dp 景深阴影。
  4. 背景层接入 U1 的 `FluidBackground`（由 `MacBackground` 驱动）。
  5. 右侧纵向布局自上而下严格排列：`SignalPathPill` -> `Spacer(20.dp)` -> `Text(metadata.title, basicMarquee)` -> `Spacer(8.dp)` -> `Text(metadata.artist)` -> `Text(metadata.album)` -> `Spacer(24.dp)` -> `IsolatedProgressBar` -> `PlaybackControls`。
  6. 维持微像素正弦位移（`pixelShiftX`, `pixelShiftY` 每 10 秒 2-3px）与 3 分钟控制栏无操作自动淡出逻辑。
- **Test Scenarios:**
  - Covers AE1. 1080p/4K 屏幕上，左侧封面区域稳定占据 42% 宽度，右侧元数据与控制占据 58% 宽度。
  - Covers AE6. 全局检索代码库，确认无任何歌词面板、播放队列或收藏相关的未用代码或界面占位。
  - 3 分钟无遥控器按键时，控制栏淡出；按方向键唤醒后焦点恢复在主播放按钮。

### U5. Fullscreen Categorized Dual-Column SettingsScreen
- **Goal:** 新建 tvOS 原生全屏分类双栏设置界面 `SettingsScreen`，替代原抽屉组件，支持分类切换、丰富卡片配置与返回键一键回退。
- **Requirements:** R12, R13, R14.
- **Dependencies:** U4.
- **Files:**
  - `app/src/main/java/com/solo/purecast/ui/screen/SettingsScreen.kt` (新建)
  - `app/src/main/java/com/solo/purecast/ui/screen/SettingsDrawer.kt` (废弃删除)
  - `app/src/main/java/com/solo/purecast/ui/screen/MainTvScreen.kt` (串联全屏状态)
- **Approach:**
  1. 创建 `SettingsScreen.kt`，外层铺设深邃暗色毛玻璃遮罩（`background(Color(0xE60A0C13))`）。
  2. 左侧固定宽度 280dp 呈现纵向分类菜单，提供 5 大分类：
     - ① 常规与设备广播
     - ② USB DAC 独占与让渡
     - ③ 接收协议配置
     - ④ 硬件时钟与缓冲监控
     - ⑤ 系统权限与运行健康
     左侧菜单项采用 `androidx.tv.material3.Button`，未聚焦透明，聚焦时呈现白底黑字胶囊，并高亮指示当前选中分类。
  3. 右侧 `Box(modifier = Modifier.weight(1f))` 呈现对应分类的设置详情卡片组，支持 D-Pad 右键穿透焦点进入卡片操作（开关、让渡超时单选块、设备名输入框等）。
  4. 分类 ④“硬件时钟与缓冲监控”展示实时仪表盘：动态刷新 SPSC 环形缓冲水位（进度卡片与字节数）、UAC2 显式时钟反馈闭环锁定状态与 libusb 版本。
  5. 内部集成 `BackHandler { onDismiss() }`，用户按下遥控器返回键时触发淡出退出，在 `MainTvScreen` 中以 `Crossfade` 驱动平滑过渡。
  6. 删除原 `SettingsDrawer.kt`。
- **Test Scenarios:**
  - Covers AE5. 点击播放控制栏的 `[⚙]` 按钮，界面平滑淡入全屏双栏设置页，焦点默认落在左侧第一项。
  - Covers AE5. 在右侧卡片浏览或修改参数时，按返回键一键返回播放主界面，播放音乐完全不中断，焦点回到播放按钮。
  - 切换到硬件监控分类时，SPSC 缓冲容量与 UAC2 时钟反馈状态每秒稳定刷新。

## Verification Contract

### Automated Build & Lint Check
- 执行 Gradle 构建与编译检查：
  `./gradlew assembleDebug`
- 验证无废弃 API 警告、无 Compose TV 组件混淆报错、无未定义资源引用。

### Manual TV Interaction & Hardware Test Gates
1. **电视真机联调部署**：
   - 安装 APK：`adb -s 127.0.0.1:5555 install -r app/build/outputs/apk/debug/app-debug.apk`
   - 重启 App：`adb -s 127.0.0.1:5555 shell am force-stop com.solo.purecast && adb -s 127.0.0.1:5555 shell am start -n com.solo.purecast/.ui.MainActivity`
2. **大屏截屏视觉复核**：
   - `adb -s 127.0.0.1:5555 shell screencap -p /sdcard/tv_playback.png && adb -s 127.0.0.1:5555 pull /sdcard/tv_playback.png /tmp/tv_playback.png`
   - 核验 42%/58% 分栏比例、高精圆角封面、GLES2 流光背景与跑马灯标题。
3. **音频品质胶囊颜色语义验证**：
   - 推流并接管 USB DAC 时核验截屏：设备名文字呈现鲜绿色，无 `(独占)` 括号字样。
   - 停止推流 10 秒触发让渡后截屏：设备名文字平滑变为鲜红色。
4. **全屏设置页交互复核**：
   - 遥控器导航至设置按钮并确认：`adb -s 127.0.0.1:5555 shell input keyevent 23`，核验全屏双栏页面成功拉起。
   - 按下返回键：`adb -s 127.0.0.1:5555 shell input keyevent 4`，核验平滑退回播放主界面。

## Definition of Done
1. **代码与构建**：所有新增与重构代码编译 0 报错，无任何废弃 API，严格遵循 `AGENTS.md` 规范。
2. **视觉还原度**：完全达成 Apple TV / tvOS 现代质感，42/58 黄金比例分栏与 GLES2 流体网格流光交融运行平稳。
3. **胶囊与控制**：音频品质胶囊严格对齐 `协议 · 音频格式 | 设备名称` 且由文字绿/红纯色彩指示独占；控制按钮完全纯图标化并带有统一的 1.12x 焦点高光。
4. **设置大屏体验**：全屏分类双栏设置页（`SettingsScreen`）完备可用，实时仪表盘正常刷新，返回键一键自愈回退。
5. **代码洁净度**：歌词与队列等一切不支持的占位或死代码清理完毕。
