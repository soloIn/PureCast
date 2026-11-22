package com.solo.purecast.ui.background

import android.content.Context
import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import com.solo.purecast.util.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 💡 PureCast Apple Music 原生 GLES2 硬件叠加渲染视图 (MacTextureView -> SurfaceView)
 * 升级为 SurfaceView + SurfaceHolder.setFixedSize 走电视芯片 HWC / VPU 硬件显示管线直接缩放叠加：
 * 1. 彻底消除 TextureView 强制分配的 1920x1080 GlLayer (8.1MB) 以及 Skia 每帧重采样全屏背景的 GPU 占用；
 * 2. 位于 Compose 透明窗口底层，大屏前景 UI、歌词与实心不透明设置页自然覆盖其上，无任何 Z-Order 冲突；
 * 3. 采用独立 HandlerThread + Choreographer VSYNC 帧驱动，暂停态 GPU 占用归零。
 */
internal class MacTextureView(
    context: Context,
    private val clock: MacClock,
    private val onOpaqueReady: () -> Unit = {},
    private val onOpaqueLost: () -> Unit = {},
    private val onFailure: () -> Unit
) : SurfaceView(context), SurfaceHolder.Callback {

    @Volatile private var artwork: Bitmap? = null
    @Volatile private var renderState = FluidRenderState()
    @Volatile private var active = false
    @Volatile private var closed = false
    @Volatile private var surfaceGeneration = 0
    @Volatile private var surfaceWidth = 1
    @Volatile private var surfaceHeight = 1
    private var fixedBufferWidth = -1
    private var fixedBufferHeight = -1
    private var worker: Worker? = null

    init {
        holder.addCallback(this)
        isFocusable = false
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    fun update(bitmap: Bitmap?, state: FluidRenderState, foreground: Boolean) {
        if (closed) return
        val changed = artwork !== bitmap || renderState != state || active != foreground
        artwork = bitmap
        renderState = state
        active = foreground
        if (width > 0 && height > 0) {
            applyFixedBufferSize(width, height, state.quality)
        }
        // 💡 设置页叠层关闭切回播放页时 SurfaceView 底层 Surface 从未丢失，只需 worker?.wake() 恢复 VSYNC 续绘
        if (changed) worker?.wake()
    }

    private fun applyFixedBufferSize(viewWidth: Int, viewHeight: Int, quality: FluidQuality) {
        val desired = backgroundOutputSize(viewWidth, viewHeight, quality)
        if (desired.width != fixedBufferWidth || desired.height != fixedBufferHeight) {
            fixedBufferWidth = desired.width
            fixedBufferHeight = desired.height
            holder.setFixedSize(desired.width, desired.height)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!closed && w > 0 && h > 0) {
            applyFixedBufferSize(w, h, renderState.quality)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        active = false
        artwork = null
        val old = worker
        worker = null
        // 💡 与 surfaceDestroyed 保持一致的同步释放，确保 AndroidView.onRelease 先于 surfaceDestroyed 时
        // GL 线程在底层 Surface 销毁前完成 eglDestroySurface / destroyEgl
        old?.stopSync()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (closed) return
        if (width > 0 && height > 0) {
            applyFixedBufferSize(width, height, renderState.quality)
            surfaceWidth = fixedBufferWidth.coerceAtLeast(1)
            surfaceHeight = fixedBufferHeight.coerceAtLeast(1)
        } else {
            val frame = holder.surfaceFrame
            if (frame.width() > 0 && frame.height() > 0) {
                applyFixedBufferSize(frame.width(), frame.height(), renderState.quality)
                surfaceWidth = fixedBufferWidth.coerceAtLeast(1)
                surfaceHeight = fixedBufferHeight.coerceAtLeast(1)
            }
        }
        val old = worker
        worker = null
        old?.stopSync()
        worker = Worker(holder.surface).also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (closed) return
        val newW = width.coerceAtLeast(1)
        val newH = height.coerceAtLeast(1)
        val sizeChanged = newW != surfaceWidth || newH != surfaceHeight
        surfaceWidth = newW
        surfaceHeight = newH
        if (sizeChanged) {
            surfaceGeneration++
        }
        worker?.wake()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        onOpaqueLost()
        val old = worker
        worker = null
        // 💡 SurfaceView 契约要求在 surfaceDestroyed 返回前完成 EGLSurface 解绑与释放，杜绝销毁后渲染崩溃
        old?.stopSync()
    }

    private inner class Worker(private val surface: Surface) {
        private val thread = HandlerThread("Mac-GLES2-Worker")
        private lateinit var handler: Handler
        private val stopping = AtomicBoolean(false)
        @Volatile var visibleFrame = false
            private set
        private var firstFrameNotified = false
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var eglConfig: EGLConfig? = null
        private var outputGeneration = -1
        private var outputWidth = -1
        private var outputHeight = -1
        private var renderer: MacRenderer? = null
        @Volatile private var rendererAnimating = false
        private var uploaded: Bitmap? = null
        private var contextRestarts = 0

        private val mainHandler = Handler(android.os.Looper.getMainLooper())
        private val frameQueued = AtomicBoolean(false)
        private val vsyncRunning = AtomicBoolean(false)
        private val vsyncFrame = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (stopping.get() || closed || !active || !vsyncRunning.get()) {
                    vsyncRunning.set(false)
                    return
                }
                val state = renderState

                // 💡 帧率节流：按 60Hz VSYNC 精确分频（例如 30fps -> 每 2 帧渲染 1 次，20fps -> 每 3 帧渲染 1 次）
                val stride = if (state.quality.fps in 1..59) {
                    (60 / state.quality.fps).coerceAtLeast(1).toLong()
                } else {
                    1L
                }
                vsyncFrameCount++
                val shouldDrawThisTick = stride <= 1L || (vsyncFrameCount % stride == 0L)
                if (shouldDrawThisTick && ::handler.isInitialized && frameQueued.compareAndSet(false, true)) {
                    if (!handler.post(frame)) {
                        frameQueued.set(false)
                    }
                }

                // 💡 当 state.playing == false（暂停或停止）且无材质交叉淡入渐变或已被取消时，停止主线程 choreographer 回调，GPU 占用直接降至 0%
                if (vsyncRunning.get() && shouldContinueVsync(state)) {
                    Choreographer.getInstance().postFrameCallback(this)
                } else {
                    vsyncRunning.set(false)
                }
            }
        }
        private var vsyncFrameCount = 0L

        private fun shouldContinueVsync(state: FluidRenderState): Boolean =
            !stopping.get() && !closed && active && state.quality.animated && (state.playing || rendererAnimating)

        private val frame = Runnable {
            frameQueued.set(false)
            render()
            // 💡 首帧或 wake() 唤醒渲染后，若主线程 VSYNC 尚未启动且仍需持续动画，启动主线程 Choreographer 续订
            val state = renderState
            if (shouldContinueVsync(state) && !vsyncRunning.get()) {
                scheduleMainVsync()
            }
        }

        private fun scheduleMainVsync() {
            if (!vsyncRunning.compareAndSet(false, true)) return
            mainHandler.post {
                if (!stopping.get() && !closed && active && vsyncRunning.get()) {
                    val c = Choreographer.getInstance()
                    c.removeFrameCallback(vsyncFrame)
                    c.postFrameCallback(vsyncFrame)
                } else {
                    vsyncRunning.set(false)
                }
            }
        }

        private fun cancelMainVsync() {
            vsyncRunning.set(false)
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                Choreographer.getInstance().removeFrameCallback(vsyncFrame)
            } else {
                mainHandler.post {
                    Choreographer.getInstance().removeFrameCallback(vsyncFrame)
                }
            }
        }

        fun start() {
            thread.start()
            handler = Handler(thread.looper)
            handler.post {
                try {
                    createEgl()
                    schedule()
                } catch (e: Exception) {
                    fail(e)
                }
            }
        }

        fun wake() {
            if (!stopping.get() && ::handler.isInitialized) {
                handler.post { schedule() }
            }
        }

        private fun schedule() {
            handler.removeCallbacks(frame)
            frameQueued.set(false)
            cancelMainVsync()
            if (!stopping.get() && !closed && active && renderer != null) {
                renderer?.resetFrameClock()
                if (frameQueued.compareAndSet(false, true)) {
                    if (!handler.post(frame)) {
                        frameQueued.set(false)
                    }
                }
            }
        }

        private fun render() {
            if (stopping.get() || closed || !active || !surface.isValid) return
            val w = surfaceWidth
            val h = surfaceHeight
            if (w <= 1 || h <= 1) return
            try {
                val r = renderer ?: return
                val now = System.nanoTime()
                val bitmap = artwork
                if (bitmap !== uploaded) {
                    r.setArtwork(bitmap, now)
                    uploaded = bitmap
                }
                val state = renderState
                ensureOutputSurface(w, h)
                visibleFrame = r.render(w, h, state, now, w, h)
                rendererAnimating = r.isAnimating
                // 💡 仅在成功完成有效帧渲染时才交换缓冲区，避免未就绪时向大屏输出黑底或残留帧
                if (visibleFrame) {
                    if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                        val eglError = EGL14.eglGetError()
                        if (eglError == 0x300E && contextRestarts++ == 0) {
                            Logger.ui.w("MacTextureView", "EGL 上下文丢失，重建中...")
                            destroyEgl()
                            createEgl()
                            handler.post(frame)
                        } else {
                            error("EGL 交换缓冲失败: 0x${eglError.toString(16)}")
                        }
                    } else if (!firstFrameNotified) {
                        firstFrameNotified = true
                        this@MacTextureView.post {
                            if (!closed && worker === this) {
                                onOpaqueReady()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                fail(e)
            }
        }

        private fun createEgl() {
            check(surface.isValid) { "Surface 无效" }
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
            ensureOutputSurface(surfaceWidth.coerceAtLeast(1), surfaceHeight.coerceAtLeast(1))
            renderer = MacRenderer(context, clock)
            renderer!!.initialize()
            uploaded = null
        }

        private fun ensureOutputSurface(w: Int, h: Int) {
            val generation = surfaceGeneration
            if (outputWidth == w && outputHeight == h && outputGeneration == generation && eglSurface != EGL14.EGL_NO_SURFACE) return
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT))
                check(EGL14.eglDestroySurface(display, eglSurface))
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            eglSurface = EGL14.eglCreateWindowSurface(
                display, checkNotNull(eglConfig), surface,
                intArrayOf(EGL14.EGL_NONE), 0
            )
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "EGLWindowSurface 创建失败" }
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) { "EGL make-current 失败" }
            EGL14.eglSwapInterval(display, 1)
            outputWidth = w
            outputHeight = h
            outputGeneration = generation
        }

        private fun destroyEgl() {
            rendererAnimating = false
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
            eglSurface = EGL14.EGL_NO_SURFACE
            eglContext = EGL14.EGL_NO_CONTEXT
            eglConfig = null
            outputWidth = -1
            outputHeight = -1
            outputGeneration = -1
            display = EGL14.EGL_NO_DISPLAY
        }

        private fun fail(error: Exception) {
            visibleFrame = false
            Logger.ui.w("MacTextureView", "EGL 渲染异常，降级静态帧: ${error.message}")
            this@MacTextureView.post {
                if (!closed && worker === this) {
                    onFailure()
                }
            }
            stopAsync()
        }

        fun stopAsync() {
            if (stopping.compareAndSet(false, true) && ::handler.isInitialized) {
                cancelMainVsync()
                handler.post {
                    handler.removeCallbacks(frame)
                    frameQueued.set(false)
                    destroyEgl()
                    thread.quitSafely()
                }
            }
        }

        fun stopSync() {
            if (stopping.compareAndSet(false, true) && ::handler.isInitialized) {
                cancelMainVsync()
                val latch = CountDownLatch(1)
                val posted = handler.post {
                    try {
                        handler.removeCallbacks(frame)
                        frameQueued.set(false)
                        destroyEgl()
                    } finally {
                        latch.countDown()
                        thread.quitSafely()
                    }
                }
                if (posted) {
                    try {
                        latch.await(500L, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        }
    }
}
