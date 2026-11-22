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

/** One placed sensor for the GL thread; [glow] below 0 draws it stale: grey, no halo. */
internal class SensorDraw(val tri: Int, val u: Float, val v: Float, val glow: Float, val focus: Boolean)

/** Closest pinch zoom, past the face framing at 0.3, so there is room left for the brain. */
internal const val ZOOM_IN_LIMIT = 0.15f

/** GL-thread only, except the @Volatile inputs the view writes. */
internal class TwinRenderer(
    private val body: TwinBody,
    private val organs: Map<String, TwinOrgan>,
    private val systems: List<TwinSystem>,
) : GLSurfaceView.Renderer {
    @Volatile var colors = TwinColors(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 1f, 1f), floatArrayOf(1f, 0f, 1f))
    /** Gesture goals; the camera eases toward them each frame. */
    @Volatile var yawTarget = 0f
    @Volatile var pitchTarget = 0.12f
    @Volatile var zoomTarget = 1f
    @Volatile var lastTouchNs = 0L

    /** Horizontal body offset goal in NDC; negative moves it left. */
    @Volatile var shiftTarget = 0f

    /** False: morphs and the build-up jump to their end state, and the scene holds still. */
    @Volatile var animate = true
    @Volatile var sensors: List<SensorDraw> = emptyList()

    /** Called on the GL thread with the skin under a [requestPick]; misses are dropped. */
    @Volatile var onPick: ((SkinHit) -> Unit)? = null
    @Volatile private var pickNdc: FloatArray? = null
    @Volatile private var target = FloatArray(body.morphNames.size)
    @Volatile private var assembleRequested = true

    private val tris = body.indices.size / 3
    private val current = FloatArray(body.morphNames.size)
    private val blended = FloatArray(body.neutral.size)
    private val posed = FloatArray(body.neutral.size)
    private val pivots = FloatArray(body.boneCount * 3)
    private val skin = TwinSkin(body)
    private val idle = TwinIdle(body)
    private val chestBone = body.boneIndex("spine03")
    private val headBone = body.boneIndex("head")
    private val restCentre = FloatArray(3)
    private val organRot = FloatArray(9)
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
    private var systemProg = 0
    private val systemVao = IntArray(systems.size)
    private val systemVbo = IntArray(systems.size)
    private val systemPos = systems.map { FloatArray(it.vertexCount * 3) }
    private val systemBuf = systems.map { floatBuffer(it.vertexCount * 3) }
    private var systemsDirty = true
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
    private var shift = 0f
    private val shifted = FloatArray(16)
    private val shiftedProj = FloatArray(16)
    private var revealStart = 0f
    private var haloProg = 0
    private var puckVao = 0
    private val puck = puckMesh()
    private val invMvp = FloatArray(16)
    private val clip = FloatArray(4)
    private val near = FloatArray(4)
    private val far = FloatArray(4)
    private val anchor = FloatArray(6)
    private val sensorCentre = FloatArray(3)
    private val sensorRot = FloatArray(9)
    private var eased = FloatArray(0)

    fun setTarget(weights: FloatArray, assemble: Boolean) {
        target = weights.copyOf()
        if (assemble) assembleRequested = true
    }

    /** NDC, y up; answered through [onPick] on the next frame. */
    fun requestPick(x: Float, y: Float) {
        pickNdc = floatArrayOf(x, y)
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
            organProg = program(ORGAN_VS, ORGAN_FS)
            sparkProg = program(SPARK_VS, SPARK_FS)
            systemProg = program(SYSTEM_VS, SYSTEM_FS)
            systems.forEachIndexed { i, s -> systemVao[i] = systemVao(s, i) }
            systemsDirty = true
            heartVao = heart?.let(::organVao) ?: 0
            brainVao = brain?.let(::organVao) ?: 0
            sparkVao = brain?.let(::sparkVao) ?: 0
            haloProg = program(HALO_VS, HALO_FS)
            puckVao = puckVao()
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

        val idleNow = !still && now - lastTouchNs > IDLE_NS
        if (idleNow) yawAuto += dt * SPIN_RAD_S
        val ease = if (still) 1f else 1f - exp(-dt * CAMERA_RATE)
        yaw += (yawAuto + yawTarget - yaw) * ease
        pitch += (pitchTarget - pitch) * ease
        zoom += (zoomTarget - zoom) * ease
        shift += (shiftTarget - shift) * ease
        val bodyH = maxY - minY
        val z = zoom
        val toFace = ((1f - z) / (1f - MIN_ZOOM)).coerceIn(0f, 1f)
        idle.step(t, dt, still, idleNow, toFace)
        poseBody()
        // A fixed frame, not the body's own height, so a height change reads as one.
        val mid = minY + FRAME_H / 2f
        val cy = mid + (maxY - bodyH * EYE_FROM_TOP - mid) * toFace
        val dist = FRAME_H * 0.62f / tan(Math.toRadians(FOV_DEG / 2.0).toFloat()) * z
        val ex = sin(yaw) * cos(pitch) * dist
        val ey = cy + sin(pitch) * dist
        val ez = cos(yaw) * cos(pitch) * dist
        Matrix.setLookAtM(view, 0, ex, ey, ez, 0f, cy, 0f, 0f, 1f, 0f)
        Matrix.setIdentityM(shifted, 0)
        shifted[12] = shift
        Matrix.multiplyMM(shiftedProj, 0, shifted, 0, proj, 0)
        Matrix.multiplyMM(mvp, 0, shiftedProj, 0, view, 0)
        pickNdc?.let {
            pickNdc = null
            pick(it[0], it[1])
        }
        val headY = maxY - bodyH * HEAD_FRAC

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
        val inside = 1f - smoothstep(BRAIN_FULL_ZOOM, BRAIN_START_ZOOM, z)
        glUniform1f(loc(bodyProg, "uHeadY"), headY)
        glUniform1f(loc(bodyProg, "uHeadFade"), inside)
        glBindVertexArray(bodyVao)
        glDrawArrays(GL_TRIANGLES, 0, tris * 3)

        glDisable(GL_DEPTH_TEST)
        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ONE)
        glDepthMask(false)
        if (reveal > maxY) {
            drawOrgans(t, c, inside, ex, ey, ez)
            drawSystems(t, c, inside, ex, ey, ez)
            drawSensors(t, dt, c, ex, ey, ez)
        }
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
            organFrame(organProg, h, chestBone)
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
        organFrame(organProg, b, headBone)
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
        glUniformMatrix3fv(loc(sparkProg, "uRot"), 1, false, organRot, 0)
        glUniform1f(loc(sparkProg, "uScale"), 1f)
        glUniform1f(loc(sparkProg, "uT"), t)
        glUniform3fv(loc(sparkProg, "uColor"), 1, c.secondary, 0)
        glUniform1f(loc(sparkProg, "uGain"), inside)
        glBindVertexArray(sparkVao)
        glDrawArrays(GL_LINES, 0, sparkVerts)
    }

    /** Vessels always; the cord and nerves only as [inside] brings the brain in. */
    private fun drawSystems(t: Float, c: TwinColors, inside: Float, ex: Float, ey: Float, ez: Float) {
        if (systems.isEmpty()) return
        if (systemsDirty) {
            systemsDirty = false
            systems.forEachIndexed { i, s ->
                if (s.name != "vessels" && inside <= 0f) return@forEachIndexed
                s.positions(posed, systemPos[i])
                systemBuf[i].clear()
                systemBuf[i].put(systemPos[i]).flip()
                glBindBuffer(GL_ARRAY_BUFFER, systemVbo[i])
                glBufferSubData(GL_ARRAY_BUFFER, 0, systemPos[i].size * 4, systemBuf[i])
            }
        }
        val phase = (t * BPM / 60f) % 1f
        glUseProgram(systemProg)
        glUniformMatrix4fv(loc(systemProg, "uMvp"), 1, false, mvp, 0)
        glUniform3f(loc(systemProg, "uEye"), ex, ey, ez)
        glUniform1f(loc(systemProg, "uT"), t)
        glUniform1f(loc(systemProg, "uBeat"), ((phase - SYSTOLE + 1f) % 1f) * 60f / BPM)
        glUniform1f(loc(systemProg, "uVessels"), VESSEL_GAIN)
        glUniform1f(loc(systemProg, "uNerves"), inside)
        glUniform3fv(loc(systemProg, "uC1"), 1, c.primary, 0)
        glUniform3fv(loc(systemProg, "uC2"), 1, c.secondary, 0)
        systems.forEachIndexed { i, s ->
            if (s.name != "vessels" && inside <= 0f) return@forEachIndexed
            glUniform1f(loc(systemProg, "uLines"), if (s.lines) 1f else 0f)
            glBindVertexArray(systemVao[i])
            glDrawElements(if (s.lines) GL_LINES else GL_TRIANGLES, s.indices.size, GL_UNSIGNED_SHORT, 0)
        }
    }

    /** A puck on the skin per placed sensor, its halo and rings scaled by the eased link glow. */
    private fun drawSensors(t: Float, dt: Float, c: TwinColors, ex: Float, ey: Float, ez: Float) {
        val list = sensors
        if (list.isEmpty()) return
        if (eased.size != list.size) eased = FloatArray(list.size) { list[it].glow.coerceAtLeast(0f) }
        val a = if (animate) 1f - exp(-dt * GLOW_RATE) else 1f
        list.forEachIndexed { i, s ->
            TwinPick.anchor(posed, body.indices, s.tri, s.u, s.v, anchor)
            basis(anchor, sensorRot)
            val stale = s.glow < 0f
            eased[i] += ((if (stale) 0f else s.glow) - eased[i]) * a
            val g = eased[i]
            val pulse = if (s.focus && animate) 0.8f + 0.2f * sin(t * PULSE_RAD_S) else 1f
            for (k in 0 until 3) sensorCentre[k] = anchor[k] + anchor[3 + k] * PUCK_HALF_H
            glUseProgram(organProg)
            glUniformMatrix4fv(loc(organProg, "uMvp"), 1, false, mvp, 0)
            glUniform3f(loc(organProg, "uEye"), ex, ey, ez)
            glUniform3fv(loc(organProg, "uCentre"), 1, sensorCentre, 0)
            glUniformMatrix3fv(loc(organProg, "uRot"), 1, false, sensorRot, 0)
            glUniform1f(loc(organProg, "uScale"), 1f)
            glUniform1f(loc(organProg, "uAtria"), 0f)
            glUniform1f(loc(organProg, "uVentricles"), 0f)
            glUniform3fv(loc(organProg, "uColor"), 1, if (stale) STALE_RGB else c.primary, 0)
            glUniform1f(loc(organProg, "uGain"), if (stale) 0.6f else (0.6f + 1.4f * g) * pulse)
            glBindVertexArray(puckVao)
            glDrawArrays(GL_TRIANGLES, 0, puck.size / 3)
            if (stale) return@forEachIndexed
            for (k in 0 until 3) sensorCentre[k] = anchor[k] + anchor[3 + k] * PUCK_HALF_H * 2f
            glUseProgram(haloProg)
            glUniformMatrix4fv(loc(haloProg, "uMvp"), 1, false, mvp, 0)
            glUniform3fv(loc(haloProg, "uCentre"), 1, sensorCentre, 0)
            glUniform3f(loc(haloProg, "uRight"), view[0], view[4], view[8])
            glUniform3f(loc(haloProg, "uUp"), view[1], view[5], view[9])
            glUniform1f(loc(haloProg, "uSize"), HALO_DM * (0.6f + 0.8f * g))
            glUniform1f(loc(haloProg, "uT"), t)
            glUniform1f(loc(haloProg, "uGlow"), g * pulse)
            glUniform3fv(loc(haloProg, "uColor"), 1, c.secondary, 0)
            glBindVertexArray(emptyVao)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    /** Unprojects the NDC point through the frame's [mvp] and casts it at the posed skin. */
    private fun pick(x: Float, y: Float) {
        val cb = onPick ?: return
        if (!Matrix.invertM(invMvp, 0, mvp, 0)) return
        unproject(x, y, -1f, near)
        unproject(x, y, 1f, far)
        val dir = floatArrayOf(far[0] - near[0], far[1] - near[1], far[2] - near[2])
        TwinPick.ray(posed, body.indices, near, dir)?.let(cb)
    }

    private fun unproject(x: Float, y: Float, z: Float, out: FloatArray) {
        clip[0] = x
        clip[1] = y
        clip[2] = z
        clip[3] = 1f
        Matrix.multiplyMV(out, 0, invMvp, 0, clip, 0)
        for (k in 0 until 3) out[k] /= out[3]
    }

    /** Expanded puck triangles; motion class 2 keeps the organ shader from squeezing it. */
    private fun puckVao(): Int {
        val vao = genVao()
        glBindVertexArray(vao)
        val n = puck.size / 3
        val bary = floatBuffer(n * 3)
        val motion = floatBuffer(n * 4)
        for (i in 0 until n) {
            bary.put(BARY, (i % 3) * 3, 3)
            motion.put(puck, i * 3, 3)
            motion.put(2f)
        }
        attribute(0, 3, floatBuffer(puck.size).apply { put(puck).flip() })
        attribute(1, 3, bary.apply { flip() })
        attribute(2, 4, motion.apply { flip() })
        glBindVertexArray(0)
        return vao
    }

    /** Positions stream per morph change; class, distance and indices are static. */
    private fun systemVao(s: TwinSystem, i: Int): Int {
        val vao = genVao()
        glBindVertexArray(vao)
        systemVbo[i] = genBuffer()
        glBindBuffer(GL_ARRAY_BUFFER, systemVbo[i])
        glBufferData(GL_ARRAY_BUFFER, s.vertexCount * 12, null, GL_DYNAMIC_DRAW)
        glEnableVertexAttribArray(0)
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 0, 0)
        attribute(1, 2, floatBuffer(s.attrs.size).apply { put(s.attrs).flip() })
        val ebo = genBuffer()
        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo)
        val idx = ByteBuffer.allocateDirect(s.indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        idx.put(s.indices).flip()
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, s.indices.size * 2, idx, GL_STATIC_DRAW)
        glBindVertexArray(0)
        return vao
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

    /** Skins the morphed body into the idle pose and streams it; the vessels follow it. */
    private fun poseBody() {
        skin.pose(pivots, idle.rot, idle.shift)
        skin.apply(blended, posed)
        val idx = body.indices
        for (n in idx.indices) {
            val v = (idx[n].toInt() and 0xFFFF) * 3
            expanded[n * 3] = posed[v]
            expanded[n * 3 + 1] = posed[v + 1]
            expanded[n * 3 + 2] = posed[v + 2]
        }
        posBuf.clear()
        posBuf.put(expanded).flip()
        glBindBuffer(GL_ARRAY_BUFFER, posVbo)
        glBufferSubData(GL_ARRAY_BUFFER, 0, expanded.size * 4, posBuf)
        systemsDirty = true
    }

    /** An organ rides [bone]: its anchored centre and its turn go to the organ shader. */
    private fun organFrame(prog: Int, organ: TwinOrgan, bone: Int) {
        organ.centre(blended, restCentre)
        skin.transform(bone, restCentre, 0, centre, 0)
        skin.rotationColumnMajor(bone, organRot)
        glUniform3fv(loc(prog, "uCentre"), 1, centre, 0)
        glUniformMatrix3fv(loc(prog, "uRot"), 1, false, organRot, 0)
    }

    private fun smoothstep(a: Float, b: Float, x: Float): Float {
        val k = ((x - a) / (b - a)).coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }

    /** Eases [current] toward [target]; re-blends only while it moves. */
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
        body.pivots(current, pivots)
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in 1 until blended.size step 3) {
            val y = blended[i]
            if (y < lo) lo = y
            if (y > hi) hi = y
        }
        minY = lo
        maxY = hi
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
        const val SPIN_RAD_S = 0.075f
        const val MORPH_RATE = 3f
        const val PARTICLES = 220
        const val BPM = 60f

        /** Share of the beat at which ventricular systole starts and the arterial pulse leaves. */
        const val SYSTOLE = 0.15f
        const val VESSEL_GAIN = 0.55f

        /** Head bottom as a share of stature below the crown; the head fades above it. */
        const val HEAD_FRAC = 0.14f

        /** Zoom past the face framing over which the head thins and the brain appears. */
        const val BRAIN_START_ZOOM = 0.27f
        const val BRAIN_FULL_ZOOM = 0.18f
        const val IDLE_NS = 2_500_000_000L
        val BARY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

        /** A 35 mm disc, 5 mm thick: the size of a worn CGM, in decimetres. */
        const val PUCK_R = 0.175f
        const val PUCK_HALF_H = 0.025f
        const val PUCK_SEGMENTS = 20
        const val HALO_DM = 0.9f
        const val GLOW_RATE = 2f
        const val PULSE_RAD_S = 3f
        val STALE_RGB = floatArrayOf(0.45f, 0.45f, 0.45f)

        /** Expanded triangles of a disc about z: both caps and the rim. */
        fun puckMesh(): FloatArray {
            val out = ArrayList<Float>()
            fun v(a: Int, z: Float) {
                val r = 2.0 * Math.PI * a / PUCK_SEGMENTS
                out += PUCK_R * cos(r).toFloat()
                out += PUCK_R * sin(r).toFloat()
                out += z
            }
            fun centre(z: Float) {
                out += 0f
                out += 0f
                out += z
            }
            val h = PUCK_HALF_H
            for (a in 0 until PUCK_SEGMENTS) {
                centre(h); v(a, h); v(a + 1, h)
                centre(-h); v(a + 1, -h); v(a, -h)
                v(a, -h); v(a + 1, -h); v(a + 1, h)
                v(a, -h); v(a + 1, h); v(a, h)
            }
            return out.toFloatArray()
        }

        /** Column-major mat3 whose z column is the normal at [a] 3..5. */
        fun basis(a: FloatArray, out: FloatArray) {
            val nx = a[3]
            val ny = a[4]
            val nz = a[5]
            val helperY = abs(ny) < 0.9f
            var tx = if (helperY) nz else 0f
            var ty = if (helperY) 0f else -nz
            var tz = if (helperY) -nx else ny
            val len = kotlin.math.sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-12f)
            tx /= len
            ty /= len
            tz /= len
            out[0] = tx
            out[1] = ty
            out[2] = tz
            out[3] = ny * tz - nz * ty
            out[4] = nz * tx - nx * tz
            out[5] = nx * ty - ny * tx
            out[6] = nx
            out[7] = ny
            out[8] = nz
        }

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
