package com.solo.purecast.ui.background

/**
 * 💡 流光动态背景质量档位 (FluidQuality)
 * 控制 GLES2 着色器的分辨率上限与帧率节拍，适配不同性能规格的 Android 电视芯片
 */
enum class FluidQuality(val maxEdge: Int, val fps: Int) {
    OFF(0, 0),
    STATIC(0, 0),
    POWER_SAVE(480, 20),
    STANDARD(640, 20),
    HIGH_QUALITY(1280, 60);

    val animated: Boolean get() = fps > 0

    companion object {
        fun fromName(name: String?): FluidQuality =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: STANDARD
    }
}

internal data class FluidRenderState(
    val quality: FluidQuality = FluidQuality.STANDARD,
    val playing: Boolean = false,
)
