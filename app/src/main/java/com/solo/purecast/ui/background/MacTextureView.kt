package com.solo.purecast.ui.background

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import android.view.View
import com.solo.purecast.util.Logger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 💡 PureCast Apple Music 原生 GLES2 纹理承载视图 (MacTextureView)
 * 采用独立 HandlerThread 配合 Choreographer VSYNC 帧驱动，超低 CPU/GPU 开销，保障大屏 60 FPS 绝对丝滑。
 */
internal class MacTextureView(
    context: Context,
    private val clock: MacClock,
    private val onFailure: () -> Unit
) : TextureView(context), TextureView.SurfaceTextureListener {

    @Volatile private var artwork: Bitmap? = null
    @Volatile private var renderState = FluidRenderState()
    @Volatile private var active = false
    @Volatile private var closed = false
    @Volatile private var surfaceGeneration = 0
    @Volatile private var surfaceWidth = 1
    @Volatile private var surfaceHeight = 1
    private var fadeInStarted = false
    private var worker: Worker? = null

    init {
        surfaceTextureListener = this
        isOpaque = true
        isFocusable = false
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        alpha = 0f
    }

    fun update(bitmap: Bitmap?, state: FluidRenderState, foreground: Boolean) {
        if (closed) return
        val changed = artwork !== bitmap || renderState != state || active != foreground
        if (foreground && !active) requestOutputRecovery()
        artwork = bitmap
        renderState = state
        active = foreground
        if (changed) worker?.wake()
    }

    private fun requestOutputRecovery() {
        // 💡 仅递增 generation 以在待机恢复/HDMI切源时促使 EGLSurface 重建，绝不将 alpha 置 0 导致黑闪
        surfaceGeneration++
        worker?.wake()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE && !closed) requestOutputRecovery()
    }

    fun close() {
        if (closed) return
        closed = true
        active = false
        artwork = null
        animate().cancel()
        fadeInStarted = false
        worker?.stop(false)
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (closed) return
        if (!isHardwareAccelerated) {
            Logger.ui.w("MacTextureView", "缺少硬件加速，回退静态背景")
            onFailure()
            return
        }
        surfaceWidth = width
        surfaceHeight = height
        worker = Worker(texture).also { it.start() }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        surfaceGeneration++
        worker?.wake()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        animate().cancel()
        fadeInStarted = false
        val old = worker ?: return true
        worker = null
        old.stop(true)
        return false
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        // 💡 优化 5：MacTextureView 动画单次触发守卫
        // 首帧渲染成功后通过 350ms 属性动画平滑渐显点亮，杜绝瞬时突变闪烁与每帧重复启动属性动画
        if (!closed && !fadeInStarted && worker?.visibleFrame == true) {
            fadeInStarted = true
            alpha = 0.01f
            animate().alpha(1f).setDuration(350L).start()
        }
    }

    private inner class Worker(private val texture: SurfaceTexture) {
        private val thread = HandlerThread("Mac-GLES2-Worker")
        private lateinit var handler: Handler
        private val stopping = AtomicBoolean(false)
        private val textureReleased = AtomicBoolean(false)
        @Volatile private var releaseTexture = false
        @Volatile private var cleanedUp = false
        @Volatile var visibleFrame = false
            private set
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var eglConfig: EGLConfig? = null
        private var outputSize: BackgroundOutputSize? = null
        private var cachedViewWidth = -1
        private var cachedViewHeight = -1
        private var cachedQuality: FluidQuality? = null
        private var cachedOutputSize: BackgroundOutputSize? = null
        private var outputGeneration = -1
        private var reportedQuality: FluidQuality? = null
        private var window: Surface? = null
        private var renderer: MacRenderer? = null
        private var uploaded: Bitmap? = null
        private var contextRestarts = 0

        private var choreographer: Choreographer? = null
        private val vsyncFrame = Choreographer.FrameCallback { onVsync() }
        private var vsyncFrameCount = 0L

        private fun shouldContinueVsync(state: FluidRenderState): Boolean =
            !stopping.get() && !closed && active && state.quality.animated && (state.playing || renderer?.isAnimating == true)

        private fun onVsync() {
            if (!stopping.get() && !closed && active) {
                val state = renderState

                // 💡 帧率节流：当 quality.fps 在 1..30 之间（如 POWER_SAVE 档位的 30fps）时，跳过奇数 VSYNC 信号（60Hz -> 30Hz）
                vsyncFrameCount++
                if (state.quality.fps in 1..30 && (vsyncFrameCount % 2L != 0L)) {
                    if (shouldContinueVsync(state)) {
                        choreographer?.postFrameCallback(vsyncFrame)
                    }
                    return
                }

                render()

                // 💡 当 state.playing == false（暂停或停止）且无材质交叉淡入渐变时，停止 choreographer 回调，GPU 占用直接降至 0%
                // 若正在进行暂停期间的切歌封面淡入渐变 (renderer.isTransitioning == true)，维持帧率直至过渡完毕
                if (shouldContinueVsync(state)) {
                    choreographer?.postFrameCallback(vsyncFrame)
                }
            }
        }

        private val frame = Runnable {
            render()
            val state = renderState
            if (shouldContinueVsync(state)) {
                choreographer?.postFrameCallback(vsyncFrame)
            }
        }

        fun start() {
            thread.start()
            handler = Handler(thread.looper)
            handler.post {
                try {
                    choreographer = Choreographer.getInstance()
                    createEgl()
                    schedule()
                } catch (e: Exception) {
                    fail(e)
                }
            }
        }

        fun wake() {
            if (!stopping.get()) handler.post { schedule() }
        }

        private fun schedule() {
            handler.removeCallbacks(frame)
            choreographer?.removeFrameCallback(vsyncFrame)
            if (!stopping.get() && !closed && active && renderer != null) {
                renderer?.resetFrameClock()
                handler.post(frame)
            }
        }

        private fun render() {
            if (stopping.get() || closed || !active) return
            try {
                val r = renderer ?: return
                val now = System.nanoTime()
                val bitmap = artwork
                if (bitmap !== uploaded) {
                    r.setArtwork(bitmap, now)
                    uploaded = bitmap
                }
                val state = renderState
                val viewWidth = surfaceWidth
                val viewHeight = surfaceHeight
                val requested = if (viewWidth == cachedViewWidth && viewHeight == cachedViewHeight && state.quality == cachedQuality && cachedOutputSize != null) {
                    cachedOutputSize!!
                } else {
                    backgroundOutputSize(viewWidth, viewHeight, state.quality).also {
                        cachedViewWidth = viewWidth
                        cachedViewHeight = viewHeight
                        cachedQuality = state.quality
                        cachedOutputSize = it
                    }
                }
                ensureOutputSurface(requested)
                visibleFrame = r.render(viewWidth, viewHeight, state, now, requested.width, requested.height)
                if (reportedQuality != state.quality) {
                    reportedQuality = state.quality
                }
                // 💡 仅在成功完成有效帧渲染时才交换缓冲区，避免未就绪时向大屏输出黑底或残留帧
                if (visibleFrame) {
                    if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                        val eglError = EGL14.eglGetError()
                        if (eglError == 0x300E && contextRestarts++ == 0) {
                            Logger.ui.w("MacTextureView", "EGL 上下文丢失，重建中...")
                            destroyEgl()
                            createEgl()
                            // 💡 修复：重建 EGL 上下文后立即 post 渲染一帧，消除在暂停态下因缺乏 VSYNC 驱动而黑屏
                            handler.post(frame)
                        } else {
                            error("EGL 交换缓冲失败: 0x${eglError.toString(16)}")
                        }
                    }
                }
            } catch (e: Exception) {
                fail(e)
            }
        }

        private fun createEgl() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "无 EGL display" }
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL initialize 失败" }
            val attributes = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 0, EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT, EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "无匹配 RGBA8 ES2 配置" }
            eglConfig = checkNotNull(configs[0])
            eglContext = EGL14.eglCreateContext(
                display, eglConfig, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            check(eglContext != EGL14.EGL_NO_CONTEXT) { "EGLContext 创建失败" }
            // 💡 修复 Finding 2: setDefaultBufferSize 必须严格在 Surface(texture) 创建之前调用，
            // 确保底层 BufferQueue 原生图层在首次与 Surface 绑定时即持有准确分辨率，杜绝首帧黑闪与拉伸错位
            val initialSize = backgroundOutputSize(surfaceWidth, surfaceHeight, renderState.quality)
            texture.setDefaultBufferSize(initialSize.width, initialSize.height)
            window = Surface(texture)
            ensureOutputSurface(initialSize)
            renderer = MacRenderer(context, clock)
            renderer!!.initialize()
            uploaded = null
        }

        private fun ensureOutputSurface(size: BackgroundOutputSize) {
            val generation = surfaceGeneration
            if (outputSize == size && outputGeneration == generation && eglSurface != EGL14.EGL_NO_SURFACE) return
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT))
                check(EGL14.eglDestroySurface(display, eglSurface))
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            texture.setDefaultBufferSize(size.width, size.height)
            eglSurface = EGL14.eglCreateWindowSurface(
                display, checkNotNull(eglConfig), window,
                intArrayOf(EGL14.EGL_NONE), 0
            )
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "EGLWindowSurface 创建失败" }
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) { "EGL make-current 失败" }
            EGL14.eglSwapInterval(display, 1)
            outputSize = size
            outputGeneration = generation
            reportedQuality = null
        }

        private fun destroyEgl() {
            runCatching { renderer?.release() }
            renderer = null
            uploaded = null
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(display)
            }
            window?.release()
            window = null
            eglSurface = EGL14.EGL_NO_SURFACE
            eglContext = EGL14.EGL_NO_CONTEXT
            eglConfig = null
            outputSize = null
            cachedViewWidth = -1
            cachedViewHeight = -1
            cachedQuality = null
            cachedOutputSize = null
            outputGeneration = -1
            reportedQuality = null
            display = EGL14.EGL_NO_DISPLAY
        }

        private fun fail(error: Exception) {
            visibleFrame = false
            Logger.ui.w("MacTextureView", "EGL 渲染异常，降级静态帧: ${error.message}")
            this@MacTextureView.post {
                if (!closed && worker === this) {
                    animate().cancel()
                    fadeInStarted = false
                    alpha = 0f
                    onFailure()
                }
            }
            stop(false)
        }

        fun stop(release: Boolean) {
            if (release) releaseTexture = true
            if (stopping.compareAndSet(false, true)) {
                handler.post {
                    handler.removeCallbacks(frame)
                    choreographer?.removeFrameCallback(vsyncFrame)
                    destroyEgl()
                    cleanedUp = true
                    if (releaseTexture) releaseNativeTexture()
                    thread.quitSafely()
                }
            } else if (release && cleanedUp) releaseNativeTexture()
        }

        private fun releaseNativeTexture() {
            if (textureReleased.compareAndSet(false, true)) texture.release()
        }
    }
}
