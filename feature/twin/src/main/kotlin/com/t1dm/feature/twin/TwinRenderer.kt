package com.t1dm.feature.twin

import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tan

/** sRGB 0..1 triples; set from the UI thread, read once per frame on the GL thread. */
internal class TwinColors(val bg: FloatArray, val primary: FloatArray, val secondary: FloatArray)

/** GL-thread only, except the @Volatile inputs the view writes. */
internal class TwinRenderer(private val body: TwinBody) : GLSurfaceView.Renderer {
    @Volatile var colors = TwinColors(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 1f, 1f), floatArrayOf(1f, 0f, 1f))
    /** Gesture goals; the camera eases toward them each frame. */
    @Volatile var yawTarget = 0f
    @Volatile var pitchTarget = 0.12f
    @Volatile var zoomTarget = 1f
    @Volatile var lastTouchNs = 0L

    /** False: morphs and the build-up jump to their end state, and the scene holds still. */
    @Volatile var animate = true
    @Volatile private var target = FloatArray(body.morphNames.size)
    @Volatile private var assembleRequested = true

    private val tris = body.indices.size / 3
    private val current = FloatArray(body.morphNames.size)
    private val blended = FloatArray(body.neutral.size)
    private val expanded = FloatArray(tris * 9)
    private val posBuf: FloatBuffer = floatBuffer(tris * 9)
    private var minY = 0f
    private var maxY = 1f
    private var dirty = true

    private var ok = false
    private var backdropProg = 0
    private var floorProg = 0
    private var bodyProg = 0
    private var particleProg = 0
    private var emptyVao = 0
    private var bodyVao = 0
    private var posVbo = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private var width = 1
    private var height = 1
    private val startNs = System.nanoTime()
    private var lastNs = startNs
    private var yawAuto = 0f
    private var yaw = 0f
    private var pitch = 0.12f
    private var zoom = 1f
    private var revealStart = 0f

    fun setTarget(weights: FloatArray, assemble: Boolean) {
        target = weights.copyOf()
        if (assemble) assembleRequested = true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        uniformCache.clear()
        ok = runCatching {
            backdropProg = program(FULLSCREEN_VS, BACKDROP_FS)
            floorProg = program(FLOOR_VS, FLOOR_FS)
            bodyProg = program(BODY_VS, BODY_FS)
            particleProg = program(PARTICLE_VS, PARTICLE_FS)
            emptyVao = genVao()
            bodyVao = genVao()
            glBindVertexArray(bodyVao)
            posVbo = genBuffer()
            glBindBuffer(GL_ARRAY_BUFFER, posVbo)
            glBufferData(GL_ARRAY_BUFFER, expanded.size * 4, null, GL_DYNAMIC_DRAW)
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0)
            val baryVbo = genBuffer()
            glBindBuffer(GL_ARRAY_BUFFER, baryVbo)
            val bary = floatBuffer(tris * 9).apply {
                repeat(tris) { put(BARY) }
                flip()
            }
            glBufferData(GL_ARRAY_BUFFER, tris * 9 * 4, bary, GL_STATIC_DRAW)
            glEnableVertexAttribArray(1)
            glVertexAttribPointer(1, 3, GL_FLOAT, false, 0, 0)
            glBindVertexArray(0)
            dirty = true
        }.onFailure { Log.e(TAG, "twin GL setup failed", it) }.isSuccess
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        glViewport(0, 0, width, height)
        Matrix.perspectiveM(proj, 0, FOV_DEG, width.toFloat() / height, 0.5f, 400f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val still = !animate
        val dt = if (still) 10f else ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = now
        val t = if (still) 0f else (now - startNs) / 1e9f
        val c = colors
        glClearColor(c.bg[0], c.bg[1], c.bg[2], 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        if (!ok) return

        stepMorph(dt)
        if (assembleRequested) {
            assembleRequested = false
            revealStart = t
        }
        val k = if (still) 1f else ((t - revealStart) / ASSEMBLE_S).coerceIn(0f, 1f)
        val reveal = minY - 1f + (maxY - minY + 2f) * (1f - (1f - k) * (1f - k) * (1f - k))

        if (!still && now - lastTouchNs > IDLE_NS) yawAuto += dt * SPIN_RAD_S
        val ease = if (still) 1f else 1f - exp(-dt * CAMERA_RATE)
        yaw += (yawAuto + yawTarget - yaw) * ease
        pitch += (pitchTarget - pitch) * ease
        zoom += (zoomTarget - zoom) * ease
        val bodyH = maxY - minY
        val z = zoom
        val toFace = ((1f - z) / (1f - MIN_ZOOM)).coerceIn(0f, 1f)
        // A fixed frame, not the body's own height, so a height change reads as one.
        val mid = minY + FRAME_H / 2f
        val cy = mid + (maxY - bodyH * EYE_FROM_TOP - mid) * toFace
        val dist = FRAME_H * 0.62f / tan(Math.toRadians(FOV_DEG / 2.0).toFloat()) * z
        val ex = sin(yaw) * cos(pitch) * dist
        val ey = cy + sin(pitch) * dist
        val ez = cos(yaw) * cos(pitch) * dist
        Matrix.setLookAtM(view, 0, ex, ey, ez, 0f, cy, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)
        glUseProgram(backdropProg)
        glUniform3fv(loc(backdropProg, "uC1"), 1, c.primary, 0)
        glUniform3fv(loc(backdropProg, "uBg"), 1, c.bg, 0)
        glBindVertexArray(emptyVao)
        glDrawArrays(GL_TRIANGLES, 0, 3)

        glEnable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        glUseProgram(floorProg)
        glUniformMatrix4fv(loc(floorProg, "uMvp"), 1, false, mvp, 0)
        glUniform1f(loc(floorProg, "uY"), minY)
        glUniform1f(loc(floorProg, "uSize"), FLOOR_SIZE)
        glUniform1f(loc(floorProg, "uT"), t)
        glUniform3fv(loc(floorProg, "uC1"), 1, c.primary, 0)
        glUniform3fv(loc(floorProg, "uC2"), 1, c.secondary, 0)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)

        glDepthMask(true)
        glDisable(GL_BLEND)
        glUseProgram(bodyProg)
        glUniformMatrix4fv(loc(bodyProg, "uMvp"), 1, false, mvp, 0)
        glUniform3f(loc(bodyProg, "uEye"), ex, ey, ez)
        glUniform1f(loc(bodyProg, "uT"), t)
        glUniform1f(loc(bodyProg, "uReveal"), reveal)
        glUniform3fv(loc(bodyProg, "uC1"), 1, c.primary, 0)
        glUniform3fv(loc(bodyProg, "uC2"), 1, c.secondary, 0)
        glBindVertexArray(bodyVao)
        glDrawArrays(GL_TRIANGLES, 0, tris * 3)

        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE)
        glDepthMask(false)
        glUseProgram(particleProg)
        glUniformMatrix4fv(loc(particleProg, "uMvp"), 1, false, mvp, 0)
        glUniform1f(loc(particleProg, "uT"), t)
        glUniform1f(loc(particleProg, "uMinY"), minY)
        glUniform1f(loc(particleProg, "uMaxY"), maxY)
        glUniform1f(loc(particleProg, "uPx"), height * 0.06f)
        glUniform3fv(loc(particleProg, "uC1"), 1, c.primary, 0)
        glBindVertexArray(emptyVao)
        glDrawArrays(GL_POINTS, 0, PARTICLES)
        glDepthMask(true)
        glBindVertexArray(0)
    }

    /** Eases [current] toward [target]; re-blends and re-uploads only while it moves. */
    private fun stepMorph(dt: Float) {
        val goal = target
        val a = 1f - exp(-dt * MORPH_RATE)
        for (i in current.indices) {
            val d = goal[i] - current[i]
            if (abs(d) > 1e-4f) {
                current[i] += d * a
                dirty = true
            } else {
                current[i] = goal[i]
            }
        }
        if (!dirty) return
        dirty = false
        body.blend(current, blended)
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        val idx = body.indices
        for (n in idx.indices) {
            val v = (idx[n].toInt() and 0xFFFF) * 3
            val y = blended[v + 1]
            expanded[n * 3] = blended[v]
            expanded[n * 3 + 1] = y
            expanded[n * 3 + 2] = blended[v + 2]
            if (y < lo) lo = y
            if (y > hi) hi = y
        }
        minY = lo
        maxY = hi
        posBuf.clear()
        posBuf.put(expanded).flip()
        glBindBuffer(GL_ARRAY_BUFFER, posVbo)
        glBufferSubData(GL_ARRAY_BUFFER, 0, expanded.size * 4, posBuf)
    }

    private val uniformCache = HashMap<Long, Int>()

    private fun loc(prog: Int, name: String): Int =
        uniformCache.getOrPut((prog.toLong() shl 32) or name.hashCode().toLong().and(0xFFFFFFFFL)) {
            glGetUniformLocation(prog, name)
        }

    private companion object {
        const val TAG = "TwinRenderer"
        const val FOV_DEG = 35f
        const val MIN_ZOOM = 0.3f

        /** Framed height in decimetres; the tallest editable body (205 cm) still fits. */
        const val FRAME_H = 19f
        const val CAMERA_RATE = 9f

        /** Eye line as a fraction of standing height, measured down from the crown. */
        const val EYE_FROM_TOP = 0.07f
        const val FLOOR_SIZE = 120f
        const val ASSEMBLE_S = 1.8f
        const val SPIN_RAD_S = 0.3f
        const val MORPH_RATE = 3f
        const val PARTICLES = 220
        const val IDLE_NS = 2_500_000_000L
        val BARY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

        fun floatBuffer(n: Int): FloatBuffer =
            ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        fun genVao(): Int = IntArray(1).also { glGenVertexArrays(1, it, 0) }[0]

        fun genBuffer(): Int = IntArray(1).also { glGenBuffers(1, it, 0) }[0]

        fun shader(type: Int, src: String): Int {
            val s = glCreateShader(type)
            glShaderSource(s, src)
            glCompileShader(s)
            val status = IntArray(1)
            glGetShaderiv(s, GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "shader: ${glGetShaderInfoLog(s)}" }
            return s
        }

        fun program(vs: String, fs: String): Int {
            val p = glCreateProgram()
            glAttachShader(p, shader(GL_VERTEX_SHADER, vs))
            glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs))
            glLinkProgram(p)
            val status = IntArray(1)
            glGetProgramiv(p, GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "link: ${glGetProgramInfoLog(p)}" }
            return p
        }
    }
}
