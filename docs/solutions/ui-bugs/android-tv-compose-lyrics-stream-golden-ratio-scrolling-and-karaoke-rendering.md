---
module: tv-compose-lyrics
date: 2026-11-18
problem_type: ui_bug
component: frontend
severity: high
symptoms:
  - "歌词高亮激活行严重偏下（落入视口 65%~70% 处），首行与末尾行无法到达 38% 黄金视线分割点"
  - "切歌时若新歌首行索引与上一曲结束索引相同，歌词列表未触发重组并残留上一曲的物理滚动偏移"
  - "具有充裕右侧留白空间的英文与中长歌词在 540dp TV 屏幕上发生生硬截断折行"
  - "WordSyncKaraokeText 逐字扫光在 60fps VSYNC 重绘阶段高频构造临时 Brush 对象引发堆内存 GC 压力"
root_cause: logic_error
resolution_type: code_fix
tags: [android-tv, compose-for-tv, lyrics-sync, lazycolumn-scrolling, golden-ratio, word-sync-karaoke, drawwithcontent, zero-allocation]
---

# Android TV Compose 歌词流 38% 黄金视线居中滚动、动态填充与零重组逐字扫光实践

## Problem

在 PureCast Android TV（`minSdk=30`, `targetSdk=34`）Apple Music 风格沉浸式歌词大屏（`LyricsScreen`）的演进与物理电视（1080p, 320dpi / density=2.0，逻辑视口 960dp x 540dp）实测中，发现了影响观感与性能的核心系统级缺陷：
1. **视线落点失准与触底卡死**：正在播放的高亮歌词行严重偏向屏幕下半部分（实拍图落于垂直 65%~70% 处），未能稳定居于垂直中间偏上（38% 黄金分割线）；首行歌词无法下沉，末尾几行歌词因视口触底被强行卡死在屏幕最底部无法上浮。
2. **切歌换曲滚动偏移残留**：当上一曲切歌瞬间恰好停留在第 0 行，而新曲目首句同样在第 0 行开唱时，列表未能感知曲目切换，导致新歌保留上一曲的滚动物理偏移。
3. **文本空间受限生硬折行**：在右侧 60% 歌词流视口中，仅有 30 余个字符的普通英文歌词（如 "And all I have to do is think of her"）因物理余量预留过激而粗暴折行，严重破坏了 10-Foot UI 诗意排版。
4. **逐字扫光 60fps 堆内存分配抖动**：逐字扫光组件（`WordSyncKaraokeText`）虽然隔离在 `drawWithContent` 纯 Draw 阶段跳过了 Compose Recomposition 与 Layout，但每帧针对所有可见视觉行重复构造 `SolidColor` 与 `Brush.horizontalGradient`，导致 GC 频次推高与微小丢帧。

## Symptoms

- **高亮行严重下沉与下句裁切**：在物理电视大屏播放（例如 The Beatles - *Something*）时，高亮放大行落入屏幕垂直 70% 区域，其上方留有极其巨大的空白空隙，而下方下一行歌词被屏幕边缘直接拦腰裁切。
- **大跨度寻道 (Seek) 与切歌瞬间跳变失真**：Seek 或切歌后，列表未能立即着陆于 38% 黄金视线，而是出现滞后爬行或顶格停留于 180dp 处。
- **英文词汇中途折断**："is thin" 与 "of her" 被拆分在两行显示，右侧屏幕明明留有将近三分之一的黑底空白却未被利用。
- **长时间播放低端盒子微卡顿**：低功耗电视盒子在播放连续多句逐字歌词时，每秒触发多次 Minor GC 垃圾回收，造成偶发 1~2 帧卡顿。

## What Didn't Work

1. **写死静态 `contentPadding(top = 180.dp, bottom = 220.dp)` 试图隔离上下羽化区**：
   - *尝试*：给 `LazyColumn` 传入固定的静态 `PaddingValues(top = 180.dp, bottom = 220.dp)`。
   - *失败原因*：在 540dp 视口高度的电视上，上下 padding 合计占用了 `180 + 220 = 400.dp`，只留下 **140dp** 给歌词内容显示！首行歌词因为 `topPadding` 只有 180dp（33%），加上自身行高半高（~40dp）后中心点直接跌入 41% 处，永远到不了 38%；而最后一行歌词因为 `bottomPadding` 仅 220dp，列表向上滑动行程被物理边界提前截断，死死卡死在 70% 处。
2. **在 `scrollToItem` 中使用 `(topPaddingPx - targetCenterY).coerceAtLeast(0)` 计算偏移量**：
   - *尝试*：试图通过传入非负的 `scrollOffset` 将 item 顶到目标位置。
   - *失败原因*：Compose 中 `scrollToItem(index, scrollOffset)` 的数学含义是将该项顶部放置在 `beforeContentPadding - scrollOffset`。若想将 item 从顶部 padding 处向下推到目标 `targetCenterY`，数学上需要负的 `scrollOffset`！原代码使用 `.coerceAtLeast(0)` 强行截断，导致计算结果**恒为 0**，大跨度跳转时强行将高亮项推到顶部 padding（180dp 处），完全脱离 38% 视线点。
3. **`LaunchedEffect(activeLineIndex)` 仅单键监听行索引**：
   - *尝试*：仅以 `activeLineIndex` 作为滚动协程触发器。
   - *失败原因*：切歌换曲时，若新曲目激活的首句与上一首切歌时的索引恰好相同（同为 0L 或 1L），Compose 不会重新执行 `LaunchedEffect`，新歌列表完全失去对齐重置机会。
4. **对单行文本设置 `.fillMaxWidth(0.925f)` 预留 7.5% 缩放余量**：
   - *尝试*：为了防止行高亮放大 1.08x 时文字右侧贴边，将容器宽度压缩至 0.925f。
   - *失败原因*：Compose 在 Layout 阶段对文本进行 Measure 时采用的是 1.0x 宽度，0.925f 使得可用测绘宽度直接损失了 40dp，加上 34sp 粗体字号，英文单词在 Layout 阶段即被强制判定折行，导致换行高度成倍暴增。

## Solution

### 1. 动态自适应 Content Padding 闭环数学推导

在 `LyricsStreamView` 外部引入 `BoxWithConstraints` 动态获取视口物理高度 `viewportHeightPx`（在 540dp TV 上为 1080px = 540dp）：
$$\text{targetCenterY} = \text{viewportHeightPx} \times 0.38f$$
$$\text{topPaddingPx} = (\text{targetCenterY} - \text{halfItemHeightPx}).\text{coerceAtLeast}(0f)$$
$$\text{bottomPaddingPx} = (\text{viewportHeightPx} - \text{targetCenterY} - \text{halfItemHeightPx}).\text{coerceAtLeast}(0f)$$

```kotlin
// 💡 动态计算首尾内容填充，确保首行与末行均能精准对齐至 38% 黄金视线分割点
val halfItemHeightPx = with(density) { 36.dp.toPx() }
val topPaddingPx = (targetCenterY - halfItemHeightPx).coerceAtLeast(0f)
val bottomPaddingPx = (viewportHeightPx - targetCenterY - halfItemHeightPx).coerceAtLeast(0f)
val topPaddingDp = with(density) { topPaddingPx.toDp() }
val bottomPaddingDp = with(density) { bottomPaddingPx.toDp() }

LazyColumn(
    state = listState,
    contentPadding = PaddingValues(start = 0.dp, top = topPaddingDp, end = 0.dp, bottom = bottomPaddingDp),
    verticalArrangement = Arrangement.spacedBy(28.dp),
    modifier = Modifier.fillMaxSize()
) { ... }
```
- **首行（index 0）**：在列表处于最顶端（`scrollOffset = 0`）时，首行顶部刚好被推到 `topPaddingPx`（即 `targetCenterY - halfItemHeightPx`），中心点自然落在 `targetCenterY`（38% 视线点）！
- **末行（index last）**：底部的 `bottomPaddingPx` 提供了充足的向上滑动行程，末行歌词可自由上浮至 38%，彻底消除底部触底卡死。

### 2. 瞬时粗定位与亚像素精细微调对齐引擎

```kotlin
// 💡 修复 Finding 1: 绑定 (activeLineIndex, timeline) 双 Key 并记录 lastScrolledTimeline，
// 彻底根除切歌换曲时因首行索引同值 (如均为 0) 导致新曲目列表停留于旧曲目滚动偏移的缺陷
var lastScrolledTimeline by remember { mutableStateOf<LyricTimeline?>(null) }
var lastScrolledLineIndex by remember { mutableIntStateOf(-1) }

LaunchedEffect(activeLineIndex, timeline) {
    if (activeLineIndex in timeline.lines.indices) {
        val isNewTimeline = (timeline != lastScrolledTimeline)
        lastScrolledTimeline = timeline
        val delta = if (isNewTimeline) Int.MAX_VALUE else abs(activeLineIndex - lastScrolledLineIndex)
        lastScrolledLineIndex = activeLineIndex

        if (delta > 2 || isNewTimeline) {
            // 💡 跨行寻道 (Seek) 或切歌换曲大跨度跳变：立即瞬时定位对齐，杜绝缓慢滑行与残留偏移
            // 由于 topPadding 已精确设为 targetCenterY - halfItemHeight，scrollToItem(activeLineIndex, 0)
            // 瞬间将目标行定位于 38% 黄金视线附近
            listState.scrollToItem(index = activeLineIndex, scrollOffset = 0)

            // 下一帧获取经过 Measure/Layout 的实际真实高度，做亚像素级精细修正
            val postItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeLineIndex }
            if (postItem != null) {
                val currentCenter = postItem.offset + postItem.size / 2f
                val fineDelta = currentCenter - targetCenterY
                if (abs(fineDelta) > 1f) {
                    listState.scrollBy(fineDelta)
                }
            }
        } else {
            // 💡 正常逐句推进：高阻尼平滑动画过渡，动态将高亮行物理中心平移对齐至垂直中间偏上 (38%)
            val visibleItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeLineIndex }
            if (visibleItem != null) {
                val currentCenter = visibleItem.offset + visibleItem.size / 2f
                val scrollDelta = currentCenter - targetCenterY
                listState.animateScrollBy(
                    value = scrollDelta,
                    animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing)
                )
            } else {
                listState.scrollToItem(index = activeLineIndex, scrollOffset = 0)
                val postItem = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == activeLineIndex }
                if (postItem != null) {
                    val currentCenter = postItem.offset + postItem.size / 2f
                    val fineDelta = currentCenter - targetCenterY
                    if (abs(fineDelta) > 1f) {
                        listState.scrollBy(fineDelta)
                    }
                }
            }
        }
    }
}
```

### 3. 0 堆内存分配的纯 Draw 阶段逐字扫光

在 `WordSyncKaraokeText` 中，按视觉行分段裁剪，并对未扫到和已扫完的行彻底剥离 `Brush` 分配：

```kotlin
// 💡 按视觉行分段精确裁切与绘制，彻底根除多行换行歌词跨行串色 Bug
for (lineIdx in 0 until lineCount) {
    val lineTop = lr.getLineTop(lineIdx)
    val lineBottom = lr.getLineBottom(lineIdx)
    val lineLeft = lr.getLineLeft(lineIdx)
    val lineRight = lr.getLineRight(lineIdx)

    clipRect(left = 0f, top = lineTop, right = size.width, bottom = lineBottom) {
        if (lineIdx < activeVisualLine) {
            // 该视觉行已全部唱完：直接整行主色绘制 (0 堆内存对象分配)
            drawRect(color = primaryColor, blendMode = BlendMode.SrcIn)
        } else if (lineIdx > activeVisualLine) {
            // 该视觉行尚未唱到：直接整行暗色绘制 (0 堆内存对象分配)
            drawRect(color = unlitColor, blendMode = BlendMode.SrcIn)
        } else {
            // 正在该视觉行演唱：仅在该行宽度内应用局部水平渐变扫光
            val sweep = currentSweepX.coerceIn(lineLeft, lineRight)
            if (sweep <= lineLeft) {
                drawRect(color = unlitColor, blendMode = BlendMode.SrcIn)
            } else if (sweep >= lineRight) {
                drawRect(color = primaryColor, blendMode = BlendMode.SrcIn)
            } else {
                // 仅处于当前视觉行动态过渡扫光时才构建水平渐变画刷
                val lineWidth = (lineRight - lineLeft).coerceAtLeast(1f)
                val stopLit = ((sweep - lineLeft) / lineWidth).coerceIn(0f, 1f)
                val stopUnlit = (((sweep + feather) - lineLeft) / lineWidth).coerceIn(0f, 1f)
                val brush = Brush.horizontalGradient(
                    0f to primaryColor,
                    stopLit to primaryColor,
                    stopUnlit to unlitColor,
                    1f to unlitColor,
                    startX = lineLeft,
                    endX = lineRight
                )
                drawRect(brush = brush, blendMode = BlendMode.SrcIn)
            }
        }
    }
}
```

### 4. 大屏空间与字号排版优化

- `SmoothLyricLineRow` 容器物理余量放宽至 `.fillMaxWidth(0.96f)`；
- 主歌词字号从 `34.sp` 微调至 `31.sp`（`lineHeight = 40.sp`）；
- 单行有效容字量提升约 30%，消除无意义换行，同时在 1.08x 放大时确保右边缘留有安全间距不被裁切。

## Why This Works

1. **`LazyColumn` 滑动物理极限公式**：
   $$\text{maxScrollOffset} = \text{totalContentHeight} + \text{topPadding} + \text{bottomPadding} - \text{viewportHeight}$$
   当且仅当 `bottomPadding >= viewportHeight - targetCenterY - halfItemHeight` 时，列表滚动至尾部项时，尾部项的中心才有足够的行程继续向上平移直至抵达 `targetCenterY`。动态自适应公式从根本上保证了全列表在任意视口分辨率下的无限对齐能力。
2. **`scrollToItem` 与 `scrollOffset = 0` 的精准几何意义**：
   当 `topPadding` 设为 `targetCenterY - halfItemHeight` 时，调用 `scrollToItem(index, 0)` 的直接几何效果就是将 item 顶部定位在 `topPadding`。此时：
   $$\text{itemCenter} = \text{topPadding} + \text{itemHeight}/2 \approx \text{targetCenterY}$$
   使得大跨度 Seek 或切歌时无需经历任何负偏移或耗时动画，首帧直接着陆在 38% 黄金视线邻域。
3. **Compose Draw 阶段重绘与硬件加速合成**：
   在 `drawWithContent` 内部消费纳秒时间戳（`frameTrigger`），重绘仅调度在 Android 的 RenderThread / GPU 管线，UI 树的 Composition 与 Layout 阶段完全跳过；配合 `drawRect(color = ...)` 消除非激活行的每帧垃圾分配，达成极度丝滑的 60fps 逐字扫光体验。

## Prevention

1. **禁止在 TV Compose 滚动容器中使用静态硬编码 Content Padding 实现居中**：任何需要黄金视线居中或偏上对齐的列表，必须通过 `BoxWithConstraints` 基于容器物理高度动态推导 `topPadding` 与 `bottomPadding`。
2. **禁止依赖单 Key 监听可能随业务重置的行索引**：在跨列表、跨曲目或跨章节场景下，`LaunchedEffect` 必须同时绑定 `(itemIndex, containerEntityKey)` 双 Key，并在实体变更时重置位移缓存，杜绝索引同值失效。
3. **Draw 阶段高频代码必须坚持零对象分配**：在 60fps 逐帧重绘的 Canvas / DrawScope 作用域中，禁止在常规分支（未激活/已完成）中重复构造 `Brush` 或 `Shader`，统一使用原始 `Color` 或预编译缓存。
