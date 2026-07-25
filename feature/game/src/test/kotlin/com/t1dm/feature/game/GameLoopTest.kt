package com.t1dm.feature.game

import androidx.compose.runtime.BroadcastFrameClock
import com.t1dm.core.common.GameWorld
import com.t1dm.core.model.CarState
import com.t1dm.core.model.CgmReading
import com.t1dm.core.model.CgmSourceId
import com.t1dm.core.model.ReadingFlag
import com.t1dm.core.model.ReadingProvenance
import com.t1dm.core.model.RunState
import com.t1dm.core.model.UnitSpace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.abs

private const val MS = 1_000_000L
private const val FRAME = 20 * MS

/** Frames the shipped opening is allowed before the test calls it hung. A 10× zoom at `ZOOM_HZ` needs
 *  ~2.7 s to land inside its settle tolerance and the drop another 0.42 s, so ~160 frames; this is a
 *  generous multiple of that, and its only job is to fail rather than hang if the opening never ends. */
private const val OPEN_FRAME_CAP = 600

/** A stand-in for the Rust world. Records which THREAD every call arrived on, which is the property
 *  the whole design exists to guarantee. */
private class FakeWorld : GameWorld {
    override val trackLength = 400f

    val callThreads: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile var steps = 0
    @Volatile var totalDtMs = 0f
    @Volatile var resets = 0
    @Volatile var closed = false
    /** What the world reports; the real one latches this in Rust and short-circuits `step` on it. */
    @Volatile var run = RunState.Running
    private var x = 0f

    override fun step(dtMs: Float, throttle: Float, brake: Float): CarState {
        callThreads += Thread.currentThread().name
        steps++
        totalDtMs += dtMs
        x += dtMs * 0.01f * throttle
        return state()
    }

    override fun state(): CarState {
        callThreads += Thread.currentThread().name
        return CarState(
            x = x, y = 5f, angle = 0f, vx = 1f, vy = 0f, angularVelocity = 0f,
            rearX = x - 1f, rearY = 4f, rearAngle = 0f, rearOmega = 0f, rearContact = true,
            frontX = x + 1f, frontY = 4f, frontAngle = 0f, frontOmega = 0f, frontContact = true,
            rpm = 0f, throttleApplied = 0f, impactImpulse = 0f, roughness = 0f, airborne = false,
            distanceM = x, run = run, elapsedS = 0f,
        )
    }

    override fun reset(): CarState = resetAt(0f)

    /** Records where the loop asked the car to be dropped, so a test can assert it is the tap and not
     *  the track's origin. */
    var lastResetX = Float.NaN
        private set

    override fun resetAt(x: Float): CarState {
        resets++
        lastResetX = x
        this.x = x
        run = RunState.Running
        return state()
    }

    override fun close() {
        closed = true
    }
}

/** Records what the loop asked of the senses, and on which thread. The vibrator and the `AudioTrack`
 *  are both behind this seam, so the wiring can be asserted without either. */
private class RecordingFeel : FeelSink {
    @Volatile var frames = 0
    @Volatile var holds = 0
    val callThreads: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())

    override fun frame(s: CarState) {
        callThreads += Thread.currentThread().name
        frames++
    }

    override fun hold() {
        callThreads += Thread.currentThread().name
        holds++
    }
}

private fun readings(n: Int): List<CgmReading> {
    val t0 = 1_700_000_000_000L / 300_000L * 300_000L
    return List(n) { i ->
        CgmReading(
            sourceId = CgmSourceId("test"),
            tsMs = t0 + i * 300_000L,
            bgMgdl = 100 + (i % 40),
            trendTenthsPerMin = 0,
            minFromStart = 120 + i * 5,
            quality = 1,
            provenance = ReadingProvenance.MEASURED,
            flag = ReadingFlag.NORMAL,
            tzOffsetMin = 0,
            rxWallMs = t0 + i * 300_000L,
            rssi = -60,
        )
    }
}

/**
 * The loop, driven end to end against a fake world and a fake frame clock.
 *
 * `BroadcastFrameClock` is not `AndroidUiFrameClock`, so this does not prove the Choreographer
 * plumbing — what it DOES prove is the part that is this module's to get right: that a
 * `MonotonicFrameClock` in the coroutine context still drives a loop whose dispatcher is somewhere
 * else entirely, and that every solver call therefore lands on that other thread and nowhere near the
 * one issuing frames.
 */
class GameLoopTest {

    private class Harness(val zoom: GameZoom) {
        val world = FakeWorld()
        val bus = GameFrameBus()
        val camera = GameCamera()
        val controls = GameControls()
        val viewport = GameViewport().apply { widthPx = 1080f; heightPx = 2000f }
        val gate = GamePauseGate()
        val commands = GameCommands()
        val hud = HudState()
        val live = LiveReadingRef()
        val clock = BroadcastFrameClock()
        val feel = RecordingFeel()
        val executor = Executors.newSingleThreadExecutor { r -> Thread(r, GAME_THREAD) }

        fun CoroutineScope.startLoop(track: com.t1dm.ui.game.GameTrack, dropAtX: Float): Job =
            launch(executor.asCoroutineDispatcher()) {
                try {
                    runGameLoop(
                        dropAtX,
                        {},
                        0f,
                        world, track, bus, camera, zoom, controls, viewport,
                        gate, commands, hud, live, ZoneId.of("UTC"), feel,
                    )
                } finally {
                    world.close()
                }
            }

        suspend fun frame(nanos: Long) {
            // A frame sent before the loop is awaiting is simply lost, which would make the test flaky
            // rather than wrong; wait for the awaiter instead of sleeping a guessed interval.
            settle()
            clock.sendFrame(nanos)
        }

        /** Block until the loop is back awaiting the clock — i.e. every frame already sent has been
         *  fully processed on the game thread. Without this the last frame of a burst races the
         *  assertions that follow it. */
        suspend fun settle() {
            while (!clock.hasAwaiters) delay(1)
        }
    }

    /** [zoom] defaults to one with NO drop. The opening is a deliberate hold on the solver, and at its
     *  shipped 0.42 s it would swallow every step-counting burst in this file — `GameZoom` takes the
     *  timing for exactly this reason. `the opening holds the solver…` exercises the real one. */
    private fun harness(
        dropAtX: Float = 0f,
        zoom: GameZoom = GameZoom(revealS = 0f),
        block: suspend Harness.(Job) -> Unit,
    ) {
        val h = Harness(zoom)
        val track = runBlocking {
            loadGameScene(readings(300), emptyList(), UnitSpace.MgDl, null, 20, 250).track
        }
        assertTrue("the synthetic day must be drivable", track.isPlayable)
        try {
            runBlocking(h.clock) {
                val job = with(h) { startLoop(track, dropAtX) }
                h.block(job)
                job.cancel()
                job.join()
            }
        } finally {
            h.executor.shutdownNow()
        }
    }

    @Test
    fun `every solver call lands on the dedicated game thread`() = harness { job ->
        controls.throttleTarget = 1f
        var t = 1_000L * MS
        repeat(8) {
            t += FRAME
            frame(t)
        }
        settle()
        job.cancel()
        job.join()
        assertTrue("the world was touched at all", world.callThreads.isNotEmpty())
        // The coroutines debug agent decorates thread names with " @coroutine#n", so match the thread,
        // not the decoration.
        val names = world.callThreads.map { it.substringBefore(" @") }.toSet()
        assertEquals(setOf(GAME_THREAD), names)
        // …and never from the thread that issued the frames.
        assertFalse(names.contains(Thread.currentThread().name.substringBefore(" @")))
    }

    @Test
    fun `the first frame places the car without simulating it`() = harness { job ->
        frame(1_000L * MS)
        settle()
        job.cancel()
        job.join()
        assertEquals(0, world.steps)
        // The opening frame is still published, so the screen has something to draw before the first tick.
        assertTrue(bus.tick >= 1L)
    }

    /** The opening is the one hold the loop imposes on ITSELF, and it has three obligations: freeze the
     *  solver, keep publishing anyway, and not show the car until the zoom has arrived. Run with the
     *  shipped timings, unlike every other test here. */
    @Test
    fun `the opening holds the solver and spawns the car only once the zoom has settled`() =
        harness(zoom = GameZoom()) { job ->
            // A REAL zoom to perform. Without these the target span falls back to the current one, the
            // span arrives on the first animating frame, and the test would be exercising the drop only.
            viewport.visibleWidthM = 900f
            viewport.carScalePx = 12f
            viewport.plotInsetPx = 0f
            controls.throttleTarget = 1f
            var t = 1_000L * MS
            frame(t)
            settle()
            assertFalse("the car must not be drawn on the placement frame", bus.published.carShown)

            // Mid-zoom: publishing, because the draw is invalidated by a commit and nothing else, so a
            // held loop that stopped publishing would freeze the panel and then cut to the settled view.
            val tickAfterPlacement = bus.tick
            repeat(6) {
                t += FRAME
                frame(t)
            }
            settle()
            assertEquals("the solver must stay frozen through the opening", 0, world.steps)
            assertTrue("the opening must still publish", bus.tick > tickAfterPlacement)
            assertFalse("and must not have spawned the car mid-zoom", bus.published.carShown)

            // Drive it out. Bounded, so a regression that never opens fails here rather than hanging.
            var frames = 0
            while (frames < OPEN_FRAME_CAP && bus.published.carLiftM > 1e-4f) {
                t += FRAME
                frame(t)
                frames++
            }
            settle()
            job.cancel()
            job.join()
            assertTrue("the opening never completed in $frames frames", frames < OPEN_FRAME_CAP)
            assertTrue("the car must be shown once open", bus.published.carShown)
            assertTrue("the solver must run once the opening is done", world.steps > 0)
        }

    @Test
    fun `frames advance the world and publish exactly one buffer each`() = harness { job ->
        controls.throttleTarget = 1f
        var t = 1_000L * MS
        frame(t)
        settle()
        val tickAfterPlacement = bus.tick
        repeat(10) {
            t += FRAME
            frame(t)
        }
        settle()
        job.cancel()
        job.join()
        assertEquals(10, world.steps)
        assertEquals(10L, bus.tick - tickAfterPlacement)
        assertEquals(200f, world.totalDtMs, 0.5f)
        assertTrue("the car moved", bus.published.x > 0f)
        assertEquals(bus.published.x, world.state().x, 1e-4f)
    }

    @Test
    fun `a hold freezes the world and releasing it resumes without a lurch`() = harness { job ->
        controls.throttleTarget = 1f
        var t = 1_000L * MS
        frame(t)
        t += FRAME
        frame(t)
        settle()
        val stepsBefore = world.steps
        val xBefore = bus.published.x

        gate.set(GameHold.Background, true)
        // A long absence, delivered as frames — the case where the window stays attached.
        repeat(20) {
            t += 500L * MS
            frame(t)
        }
        settle()
        assertEquals("nothing simulated while held", stepsBefore, world.steps)
        assertEquals(xBefore, bus.published.x, 0f)

        gate.set(GameHold.Background, false)
        val dtBefore = world.totalDtMs
        t += FRAME
        frame(t)
        // Wait for the step to land on the other thread.
        while (world.steps == stepsBefore) delay(1)
        job.cancel()
        job.join()
        val resumedDt = world.totalDtMs - dtBefore
        assertTrue("resumed with $resumedDt ms, not the whole absence", abs(resumedDt - 20f) < 5f)
    }

    @Test
    fun `an alarm hold is as hard a stop as the lifecycle one`() = harness { job ->
        controls.throttleTarget = 1f
        var t = 1_000L * MS
        frame(t)
        t += FRAME
        frame(t)
        settle()
        val before = world.steps
        gate.set(GameHold.Modal, true)
        repeat(10) {
            t += FRAME
            frame(t)
        }
        settle()
        job.cancel()
        job.join()
        assertEquals(before, world.steps)
        assertEquals(GameHold.Modal, gate.holds.primary)
    }

    @Test
    fun `a restart re-runs the world and re-snaps the camera`() = harness { job ->
        controls.throttleTarget = 1f
        var t = 1_000L * MS
        frame(t)
        repeat(10) {
            t += FRAME
            frame(t)
        }
        settle()
        val travelled = bus.published.x
        assertTrue(travelled > 0f)

        // The loop places the car at the tap on EVERY placement, the opening one included, so the
        // count is asserted as an INCREMENT rather than an absolute — the opening reset is not an
        // accident to be pinned at zero.
        val before = world.resets
        commands.reset = true
        t += FRAME
        frame(t)
        while (world.resets == before) delay(1)
        job.cancel()
        job.join()
        assertEquals(before + 1, world.resets)
        assertTrue("back at the start line", bus.published.x < travelled)
    }

    @Test
    fun `a terminal run holds the loop as hard as any other hold`() = harness { job ->
        controls.throttleTarget = 1f
        var t = 1_000L * MS
        frame(t)
        t += FRAME
        frame(t)
        settle()

        world.run = RunState.Crashed
        t += FRAME
        frame(t)
        settle()
        // The crash IS simulated — that step is what reports it — and it must reach the HUD at once,
        // or the card the run ends on never appears.
        val stepsAtCrash = world.steps
        val tickAtCrash = bus.tick
        assertEquals(RunState.Crashed, hud.value.run)

        // Nothing behind the card: no solver call, no publish, no described frame.
        val describedAtCrash = feel.frames
        repeat(20) {
            t += FRAME
            frame(t)
        }
        settle()
        assertEquals("a frozen scene is not stepped", stepsAtCrash, world.steps)
        assertEquals("…nor re-published", tickAtCrash, bus.tick)
        assertEquals(describedAtCrash, feel.frames)
        assertTrue("and every held frame says so", feel.holds >= 20)

        // Restart is the way out, exactly as it is for the composition's own holds.
        commands.reset = true
        t += FRAME
        frame(t)
        while (world.resets == 0) delay(1)
        t += FRAME
        frame(t)
        while (world.steps == stepsAtCrash) delay(1)
        job.cancel()
        job.join()
        assertEquals(RunState.Running, world.run)
    }

    @Test
    fun `the world is closed when the loop is cancelled`() = harness { job ->
        frame(1_000L * MS)
        settle()
        job.cancel()
        job.join()
        assertTrue(world.closed)
    }

    @Test
    fun `an un-laid-out viewport banks no time`() = harness { job ->
        viewport.widthPx = 0f
        viewport.heightPx = 0f
        controls.throttleTarget = 1f
        var t = 1_000L * MS
        repeat(5) {
            t += 1_000L * MS
            frame(t)
        }
        settle()
        assertEquals(0, world.steps)

        viewport.widthPx = 1080f
        viewport.heightPx = 2000f
        t += FRAME
        frame(t)
        t += FRAME
        frame(t)
        while (world.steps == 0) delay(1)
        job.cancel()
        job.join()
        // The five seconds spent waiting for layout must not arrive as the opening timestep.
        assertTrue("first dt was ${world.totalDtMs} ms", world.totalDtMs < 60f)
    }

    // ── the sensory layer's wiring ─────────────────────────────────────────────────────────────

    @Test
    fun `the senses ride the simulated frames, on the game thread`() = harness { job ->
        controls.throttleTarget = 1f
        var t = 1_000L * MS
        frame(t)
        repeat(10) {
            t += FRAME
            frame(t)
        }
        settle()
        job.cancel()
        job.join()
        assertEquals("one frame described per step simulated", world.steps, feel.frames)
        // Exactly one hold: the opening placement. A running world holds nothing.
        assertEquals(1, feel.holds)
        val names = feel.callThreads.map { it.substringBefore(" @") }.toSet()
        assertEquals(setOf(GAME_THREAD), names)
    }

    @Test
    fun `a hold silences the senses for as long as it lasts`() = harness { job ->
        controls.throttleTarget = 1f
        var t = 1_000L * MS
        frame(t)
        t += FRAME
        frame(t)
        settle()
        val describedBefore = feel.frames

        gate.set(GameHold.Modal, true)
        repeat(10) {
            t += FRAME
            frame(t)
        }
        settle()
        job.cancel()
        job.join()
        assertEquals("nothing may be described while held", describedBefore, feel.frames)
        assertTrue("and every held frame must say so", feel.holds >= 10)
    }


    @Test
    fun `the opening placement drops the car at the tap, not at the track origin`() = harness(dropAtX = 137f) { job ->
        var t = 1_000L * MS
        frame(t)
        settle()
        job.cancel()
        job.join()
        // Regression: the loop used to place only when `commands.reset` was set, which is false on the
        // FIRST placement — so it fell through to `state()` and the car stayed where the constructor's
        // own reset had left it, at the track origin. On a track cut a full span before the viewport
        // that is far off the left of the panel, with the camera then chasing it.
        assertEquals("placed at the tap", 137f, world.lastResetX, 0.001f)
        assertEquals("and the published frame agrees", 137f, bus.published.x, 0.001f)
    }

    @Test
    fun `placement is a hold, not a frame`() = harness { job ->
        frame(1_000L * MS)
        settle()
        job.cancel()
        job.join()
        assertEquals("a car being put on the start line has nothing to sound like", 0, feel.frames)
        assertTrue(feel.holds >= 1)
    }

    private companion object {
        const val GAME_THREAD = "t1dm-game-under-test"
    }
}
