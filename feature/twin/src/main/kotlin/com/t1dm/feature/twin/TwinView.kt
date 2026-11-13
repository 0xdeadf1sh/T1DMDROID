package com.t1dm.feature.twin

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.t1dm.core.design.LocalAnimationsEnabled
import com.t1dm.core.design.LocalT1dmSemantics
import com.t1dm.core.model.TwinScan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ln

/** The 3D twin; a new [scan] replays the build-up and eases the body to its new shape. */
@Composable
fun TwinView(
    scan: TwinScan?,
    modifier: Modifier = Modifier,
    onOrbit: (rad: Float) -> Unit = {},
    onZoom: (absLogScale: Float) -> Unit = {},
    callouts: Boolean = true,
    face: ByteArray? = null,
) {
    val context = LocalContext.current
    val body = remember { loadBody(context) }
    val palette = LocalT1dmSemantics.current
    val cs = MaterialTheme.colorScheme
    val animate = LocalAnimationsEnabled.current
    val view = remember { TwinTextureView(context, body) }
    view.renderer.colors = TwinColors(palette.background.rgb(), cs.primary.rgb(), cs.secondary.rgb())
    view.animate = animate
    view.renderer.animate = animate
    view.onOrbit = onOrbit
    view.onZoom = onZoom

    LaunchedEffect(scan?.id) {
        view.renderer.setTarget(TwinMorphs.weights(scan, body), assemble = true)
        view.kick()
    }
    LaunchedEffect(face) {
        val bitmap = face?.let {
            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }
        view.renderer.setFace(bitmap)
        view.kick()
    }
    LaunchedEffect(scan?.outputs) {
        view.renderer.setTarget(TwinMorphs.weights(scan, body), assemble = false)
        view.kick()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> view.resume()
                Lifecycle.Event.ON_PAUSE -> view.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.resume()
        onDispose {
            lifecycle.removeObserver(observer)
            view.pause()
        }
    }
    Box(modifier) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        AnimatedVisibility(callouts, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            TwinCallouts(scan, Modifier.fillMaxSize())
        }
    }
}

private fun Color.rgb() = floatArrayOf(red, green, blue)

@Volatile private var cachedBody: TwinBody? = null

internal fun loadBody(context: Context): TwinBody =
    cachedBody ?: TwinBody.parse(context.assets.open("twin_body.bin").use { it.readBytes() }).also { cachedBody = it }

/** TextureView, not SurfaceView: it composites in-window, so Compose draws over and under it. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
internal class TwinTextureView(context: Context, body: TwinBody) : TextureView(context),
    TextureView.SurfaceTextureListener {
    val renderer = TwinRenderer(body)
    var animate = true
    var onOrbit: (Float) -> Unit = {}
    var onZoom: (Float) -> Unit = {}
    private var gl: GlThread? = null
    private var running = false
    private var lastFrameNs = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var activeId = MotionEvent.INVALID_POINTER_ID

    /** ≤60 fps from the frame clock, never the panel's full 120 Hz. */
    private val tick = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (frameTimeNanos - lastFrameNs >= FRAME_NS) {
                lastFrameNs = frameTimeNanos
                gl?.requestRender()
            }
            if (animate) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            renderer.zoomTarget = (renderer.zoomTarget / d.scaleFactor).coerceIn(0.3f, 2.2f)
            onZoom(abs(ln(d.scaleFactor)))
            return true
        }
    })

    init {
        surfaceTextureListener = this
    }

    fun resume() {
        if (running) return
        running = true
        kick()
    }

    fun pause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(tick)
    }

    fun kick() {
        if (!running) return
        Choreographer.getInstance().removeFrameCallback(tick)
        Choreographer.getInstance().postFrameCallback(tick)
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        gl = GlThread(st, w, h, renderer).also { it.start() }
        kick()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        gl?.resize(w, h)
        kick()
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        gl?.quitAndJoin()
        gl = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scale.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                track(e, 0)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            // The finger that stays down takes over; its stale delta was what snapped the view.
            MotionEvent.ACTION_POINTER_UP -> track(e, if (e.actionIndex == 0) 1 else 0)
            MotionEvent.ACTION_MOVE -> if (!scale.isInProgress && e.pointerCount == 1) {
                val i = e.findPointerIndex(activeId)
                if (i >= 0) {
                    val dYaw = (e.getX(i) - lastX) / width * 4f
                    renderer.yawTarget -= dYaw
                    onOrbit(abs(dYaw))
                    renderer.pitchTarget = (renderer.pitchTarget + (e.getY(i) - lastY) / height * 2f)
                        .coerceIn(-0.2f, 0.9f)
                    lastX = e.getX(i)
                    lastY = e.getY(i)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        renderer.lastTouchNs = System.nanoTime()
        kick()
        return true
    }

    private fun track(e: MotionEvent, index: Int) {
        activeId = e.getPointerId(index)
        lastX = e.getX(index)
        lastY = e.getY(index)
    }

    private companion object {
        const val FRAME_NS = 15_000_000L
    }
}

/** Owns the EGL context; draws one frame per [requestRender], idles otherwise. */
private class GlThread(
    private val st: SurfaceTexture,
    private var w: Int,
    private var h: Int,
    private val renderer: TwinRenderer,
) : Thread("twin-gl") {
    private val lock = Object()
    private var pending = true
    private var resized = false
    private var quit = false

    fun requestRender() = synchronized(lock) {
        pending = true
        lock.notifyAll()
    }

    fun resize(width: Int, height: Int) = synchronized(lock) {
        w = width
        h = height
        resized = true
        pending = true
        lock.notifyAll()
    }

    fun quitAndJoin() {
        synchronized(lock) {
            quit = true
            lock.notifyAll()
        }
        join(1_000)
    }

    override fun run() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        try {
            val v = IntArray(2)
            check(EGL14.eglInitialize(display, v, 0, v, 1)) { "eglInitialize" }
            val config = chooseConfig(display)
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
            ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            check(ctx != EGL14.EGL_NO_CONTEXT) { "eglCreateContext" }
            surface = EGL14.eglCreateWindowSurface(display, config, st, intArrayOf(EGL14.EGL_NONE), 0)
            check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface" }
            check(EGL14.eglMakeCurrent(display, surface, surface, ctx)) { "eglMakeCurrent" }
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, w, h)
            while (true) {
                var resize = false
                var width = 0
                var height = 0
                synchronized(lock) {
                    while (!pending && !quit) lock.wait()
                    if (quit) return
                    pending = false
                    if (resized) {
                        resized = false
                        resize = true
                        width = w
                        height = h
                    }
                }
                if (resize) renderer.onSurfaceChanged(null, width, height)
                renderer.onDrawFrame(null)
                EGL14.eglSwapBuffers(display, surface)
            }
        } catch (e: Exception) {
            Log.e("TwinGl", "twin GL thread stopped", e)
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, ctx)
        }
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig {
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 24,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val n = IntArray(1)
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, n, 0) && n[0] > 0) { "eglChooseConfig" }
        return configs[0]!!
    }

    private companion object {
        const val EGL_OPENGL_ES3_BIT = 0x40
    }
}
