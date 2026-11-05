package com.solo.purecast.ui.background

/**
 * 💡 流光着色器动态微调参数 (FluidTuning)
 * 精确对齐 Apple Music 流体光影的运动速度、色彩饱和度与高斯虚化遍数
 */
internal object FluidTuning {
    const val BLUR_PASSES = 6
    const val WARP_INTENSITY = 1f
    const val ANIMATION_SPEED = 1f
    const val SATURATION = 1.9f
    const val TINT_INTENSITY = 0.08f
    const val SCALE = 2.2f
    const val DITHERING = 0.008f
    const val TRANSITION_SECONDS = 1.0
    const val PALETTE_CYCLE_SECONDS = 20.0
}
