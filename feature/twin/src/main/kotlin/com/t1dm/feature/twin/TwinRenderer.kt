package com.t1dm.feature.twin

import android.graphics.Bitmap
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
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

/** Closest pinch zoom, past the face framing at 0.3, so there is room left for the brain. */
internal const val ZOOM_IN_LIMIT = 0.15f

/** GL-thread only, except the @Volatile inputs the view writes. */
internal class TwinRenderer(
    private val body: TwinBody,
    private val organs: Map<String, TwinOrgan>,
) : GLSurfaceView.Renderer {
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
    private var organProg = 0
    private var sparkProg = 0
    private val heart = organs["heart"]
    private val brain = organs["brain"]
    private var heartVao = 0
    private var brainVao = 0
    private var sparkVao = 0
    private var sparkVerts = 0
    private val centre = FloatArray(3)

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

    @Volatile private var faceBitmap: Bitmap? = null
    @Volatile private var faceDirty = false
    private var faceTex = 0

    /** Kept, not recycled: a new GL context re-uploads it. Null removes the face. */
    fun setFace(bitmap: Bitmap?) {
        faceBitmap = bitmap
        faceDirty = true
    }

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
            val uvVbo = genBuffer()
            glBindBuffer(GL_ARRAY_BUFFER, uvVbo)
            glBufferData(GL_ARRAY_BUFFER, tris * 6 * 4, faceUvs(), GL_STATIC_DRAW)
            glEnableVertexAttribArray(2)
            glVertexAttribPointer(2, 2, GL_FLOAT, false, 0, 0)
            glBindVertexArray(0)
            organProg = program(ORGAN_VS, ORGAN_FS)
            sparkProg = program(SPARK_VS, SPARK_FS)
            heartVao = heart?.let(::organVao) ?: 0
            brainVao = brain?.let(::organVao) ?: 0
            sparkVao = brain?.let(::sparkVao) ?: 0
            faceTex = 0
            faceDirty = true
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
        if (faceDirty) uploadFace()
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, faceTex)
        glUseProgram(bodyProg)
        glUniform1i(loc(bodyProg, "uFace"), 0)
        glUniform1f(loc(bodyProg, "uFaceOn"), if (faceTex != 0) 1f else 0f)
        glUniformMatrix4fv(loc(bodyProg, "uMvp"), 1, false, mvp, 0)
        glUniform3f(loc(bodyProg, "uEye"), ex, ey, ez)
        glUniform1f(loc(bodyProg, "uT"), t)
        glUniform1f(loc(bodyProg, "uReveal"), reveal)
        glUniform3fv(loc(bodyProg, "uC1"), 1, c.primary, 0)
        glUniform3fv(loc(bodyProg, "uC2"), 1, c.secondary, 0)
        val inside = 1f - smoothstep(BRAIN_FULL_ZOOM, BRAIN_START_ZOOM, z)
        glUniform1f(loc(bodyProg, "uHeadY"), maxY - bodyH * HEAD_FRAC)
        glUniform1f(loc(bodyProg, "uHeadFade"), inside)
        glBindVertexArray(bodyVao)
        glDrawArrays(GL_TRIANGLES, 0, tris * 3)

        glDisable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ONE)
        glDepthMask(false)
        if (reveal > maxY) drawOrgans(t, c, inside, ex, ey, ez)
        glEnable(GL_DEPTH_TEST)

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

    /** Heart beating at [BPM] under the chest; the brain and its sparks as [inside] rises. */
    private fun drawOrgans(t: Float, c: TwinColors, inside: Float, ex: Float, ey: Float, ez: Float) {
        glUseProgram(organProg)
        glUniformMatrix4fv(loc(organProg, "uMvp"), 1, false, mvp, 0)
        glUniform3f(loc(organProg, "uEye"), ex, ey, ez)
        heart?.let { h ->
            // One cycle per beat: atrial kick, then ventricular systole (≈0.15–0.5 of the cycle).
            val phase = (t * BPM / 60f) % 1f
            val atria = exp(-((phase - 0.06f) / 0.045f).let { it * it })
            val ventricles = smoothstep(0.14f, 0.24f, phase) * (1f - smoothstep(0.36f, 0.5f, phase))
            h.centre(blended, centre)
            glUniform3fv(loc(organProg, "uCentre"), 1, centre, 0)
            glUniform1f(loc(organProg, "uScale"), 1f)
            glUniform1f(loc(organProg, "uAtria"), atria)
            glUniform1f(loc(organProg, "uVentricles"), ventricles)
            glUniform3fv(loc(organProg, "uColor"), 1, c.secondary, 0)
            glUniform1f(loc(organProg, "uGain"), 0.6f + 0.5f * ventricles + 0.3f * atria)
            glBindVertexArray(heartVao)
            glDrawArrays(GL_TRIANGLES, 0, h.tris.size)
        }
        val b = brain ?: return
        if (inside <= 0f) return
        b.centre(blended, centre)
        glUniform3fv(loc(organProg, "uCentre"), 1, centre, 0)
        glUniform1f(loc(organProg, "uScale"), 1f)
        glUniform1f(loc(organProg, "uAtria"), 0f)
        glUniform1f(loc(organProg, "uVentricles"), 0f)
        glUniform3fv(loc(organProg, "uColor"), 1, c.primary, 0)
        glUniform1f(loc(organProg, "uGain"), 0.35f * inside)
        glBindVertexArray(brainVao)
        glDrawArrays(GL_TRIANGLES, 0, b.tris.size)
        glUseProgram(sparkProg)
        glUniformMatrix4fv(loc(sparkProg, "uMvp"), 1, false, mvp, 0)
        glUniform3fv(loc(sparkProg, "uCentre"), 1, centre, 0)
        glUniform1f(loc(sparkProg, "uScale"), 1f)
        glUniform1f(loc(sparkProg, "uT"), t)
        glUniform3fv(loc(sparkProg, "uColor"), 1, c.secondary, 0)
        glUniform1f(loc(sparkProg, "uGain"), inside)
        glBindVertexArray(sparkVao)
        glDrawArrays(GL_LINES, 0, sparkVerts)
    }

    /** Expanded triangles with barycentrics, for the wire edges. */
    private fun organVao(o: TwinOrgan): Int {
        val vao = genVao()
        glBindVertexArray(vao)
        val n = o.tris.size
        val pos = floatBuffer(n * 3)
        val bary = floatBuffer(n * 3)
        val motion = floatBuffer(n * 4)
        for (i in 0 until n) {
            val v = o.tris[i].toInt() and 0xFFFF
            pos.put(o.pos, v * 3, 3)
            bary.put(BARY, (i % 3) * 3, 3)
            motion.put(o.motion, v * 4, 4)
        }
        attribute(0, 3, pos.apply { flip() })
        attribute(1, 3, bary.apply { flip() })
        attribute(2, 4, motion.apply { flip() })
        glBindVertexArray(0)
        return vao
    }

    /** One line per mesh edge, each end carrying its distances from the spark seeds. */
    private fun sparkVao(o: TwinOrgan): Int {
        val edges = o.edges()
        val dist = o.seedDistances(edges)
        val pos = floatBuffer(edges.size * 3)
        val d = floatBuffer(edges.size * TwinOrgan.SEEDS)
        for (v in edges) {
            pos.put(o.pos, v * 3, 3)
            d.put(dist, v * TwinOrgan.SEEDS, TwinOrgan.SEEDS)
        }
        val vao = genVao()
        glBindVertexArray(vao)
        attribute(0, 3, pos.apply { flip() })
        attribute(1, TwinOrgan.SEEDS, d.apply { flip() })
        glBindVertexArray(0)
        sparkVerts = edges.size
        return vao
    }

    private fun attribute(index: Int, size: Int, data: FloatBuffer) {
        glBindBuffer(GL_ARRAY_BUFFER, genBuffer())
        glBufferData(GL_ARRAY_BUFFER, data.remaining() * 4, data, GL_STATIC_DRAW)
        glEnableVertexAttribArray(index)
        glVertexAttribPointer(index, size, GL_FLOAT, false, 0, 0)
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val k = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }

    /** Per expanded vertex; a triangle touching any off-face vertex is off the face whole. */
    private fun faceUvs(): FloatBuffer {
        val uv = body.faceUv
        val idx = body.indices
        val out = floatBuffer(tris * 6)
        for (t in 0 until tris) {
            val v = IntArray(3) { idx[t * 3 + it].toInt() and 0xFFFF }
            val off = v.any { uv[it * 2] == TwinBody.NO_UV }
            for (k in v) {
                out.put(if (off) TwinBody.NO_UV else uv[k * 2])
                out.put(if (off) TwinBody.NO_UV else uv[k * 2 + 1])
            }
        }
        return out.apply { flip() }
    }

    private fun uploadFace() {
        faceDirty = false
        val b = faceBitmap
        if (b == null) {
            if (faceTex != 0) glDeleteTextures(1, intArrayOf(faceTex), 0)
            faceTex = 0
            return
        }
        if (faceTex == 0) faceTex = IntArray(1).also { glGenTextures(1, it, 0) }[0]
        glBindTexture(GL_TEXTURE_2D, faceTex)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, b, 0)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glGenerateMipmap(GL_TEXTURE_2D)
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
        const val BPM = 60f

        /** Head bottom as a share of stature below the crown; the head fades above it. */
        const val HEAD_FRAC = 0.14f

        /** Zoom past the face framing over which the head thins and the brain appears. */
        const val BRAIN_START_ZOOM = 0.27f
        const val BRAIN_FULL_ZOOM = 0.18f
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
