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
import android.view.Surface
import android.view.TextureView
import android.view.View
import com.solo.purecast.util.Logger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * 💡 专门承载 GLES2 流体渲染的 TextureView (KawarpTextureView)
 * 具备独立 EGL 线程与自动生命周期管理，不抢占 D-Pad 遥控器焦点，在 EGL 异常时优雅通知降级
 */
internal class KawarpTextureView(
    context: Context,
    private val onFailure: () -> Unit
) : TextureView(context), TextureView.SurfaceTextureListener {

    @Volatile private var artwork: Bitmap? = null
    @Volatile private var renderState = FluidRenderState()
    @Volatile private var active = false
    @Volatile private var closed = false
    @Volatile private var surfaceWidth = 1
    @Volatile private var surfaceHeight = 1
    private var worker: Worker? = null
    private val clock = FluidClock()

    init {
        surfaceTextureListener = this
        isOpaque = true
        isFocusable = false
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        alpha = 0f // GL 帧准备好之前保持透明
    }

    fun update(bitmap: Bitmap?, state: FluidRenderState, foreground: Boolean) {
        if (closed) return
        val changed = artwork !== bitmap || renderState != state || active != foreground
        artwork = bitmap
        renderState = state
        active = foreground
        if (changed) worker?.wake()
    }

    fun close() {
        if (closed) return
        closed = true
        active = false
        artwork = null
        worker?.stop(false)
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        if (closed) return
        if (!isHardwareAccelerated) {
            Logger.ui.w("Kawarp", "缺少硬件加速环境，回退静态背景")
            onFailure()
            return
        }
        surfaceWidth = width
        surfaceHeight = height
        texture.setDefaultBufferSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
        alpha = 0f
        worker = Worker(texture).also { it.start() }
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        if (!closed) texture.setDefaultBufferSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
        worker?.wake()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        val old = worker ?: return true
        worker = null
        old.stop(true)
        return false
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        if (!closed) alpha = if (worker?.visibleFrame == true) 1f else 0f
    }

    private inner class Worker(private val texture: SurfaceTexture) {
        private val thread = HandlerThread("Kawarp-GLES2-Worker")
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
        private var window: Surface? = null
        private var renderer: KawarpRenderer? = null
        private var uploaded: Bitmap? = null
        private var contextRestarts = 0

        private val frame = Runnable { render() }

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
            if (!stopping.get()) handler.post { schedule() }
        }

        private fun schedule() {
            handler.removeCallbacks(frame)
            if (!stopping.get() && !closed && active && renderer != null) {
                renderer?.resetFrameClock()
                handler.post(frame)
            }
        }

        private fun render() {
            if (stopping.get() || closed || !active) return
            val started = SystemClock.uptimeMillis()
            try {
                val r = renderer ?: return
                val now = System.nanoTime()
                val bitmap = artwork
                if (bitmap !== uploaded) {
                    r.setArtwork(bitmap, now)
                    uploaded = bitmap
                }
                visibleFrame = r.render(surfaceWidth, surfaceHeight, renderState, now)
                if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                    val eglError = EGL14.eglGetError()
                    if (eglError == 0x300E && contextRestarts++ == 0) {
                        Logger.ui.w("Kawarp", "EGL 上下文丢失，重建中...")
                        destroyEgl()
                        createEgl()
                    } else {
                        error("EGL 交换缓冲失败: 0x${eglError.toString(16)}")
                    }
                }
                val fps = if (renderState.playing) renderState.quality.fps else min(15, renderState.quality.fps)
                val delay = max(1L, 1000L / fps.coerceAtLeast(1) - (SystemClock.uptimeMillis() - started))
                if (!stopping.get() && !closed && active) {
                    handler.postDelayed(frame, delay)
                }
            } catch (e: Exception) {
                fail(e)
            }
        }

        private fun createEgl() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "无法获取 EGL display" }
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
            eglContext = EGL14.eglCreateContext(
                display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
            )
            check(eglContext != EGL14.EGL_NO_CONTEXT) { "EGLContext 创建失败" }
            window = Surface(texture)
            eglSurface = EGL14.eglCreateWindowSurface(
                display, configs[0], window,
                intArrayOf(EGL14.EGL_NONE), 0
            )
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "EGLWindowSurface 创建失败" }
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) { "EGL makeCurrent 失败" }
            EGL14.eglSwapInterval(display, 1)
            renderer = KawarpRenderer(clock).also { it.initialize() }
            uploaded = null
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
            display = EGL14.EGL_NO_DISPLAY
        }

        private fun fail(error: Exception) {
            visibleFrame = false
            Logger.ui.w("Kawarp", "GLES2 渲染异常，回退静态背景: ${error.message}", error)
            this@KawarpTextureView.post {
                if (!closed && worker === this) {
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
                    destroyEgl()
                    cleanedUp = true
                    if (releaseTexture) releaseNativeTexture()
                    thread.quitSafely()
                }
            } else if (release && cleanedUp) {
                releaseNativeTexture()
            }
        }

        private fun releaseNativeTexture() {
            if (textureReleased.compareAndSet(false, true)) texture.release()
        }
    }
}
