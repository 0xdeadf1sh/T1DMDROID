package com.t1dm.feature.game

/**
 * Why the world is frozen. Several may hold at once (the screen goes to the background WHILE an alarm
 * fires); declaration order is the order they are reported in, most important first.
 */
enum class GameHold {
    /** The screen is no longer RESUMED. */
    Background,

    /** The opening animation is still running: the panel is sliding into position and the car has not
     *  been let go yet. Held so the world cannot move under an intro that is still describing it. */
    Intro,

    /** A modal is up — the exit confirmation, or the run's own terminal card. */
    Modal,
}

/**
 * The set of reasons the loop is holding, as a bitmask over [GameHold].
 *
 * A set rather than a single flag because releases arrive out of order: an alarm that clears while the
 * screen is still backgrounded must not resume the world, and a single `paused` boolean written by two
 * independent observers loses exactly that.
 */
@JvmInline
value class GameHolds(val bits: Int) {
    fun with(hold: GameHold, on: Boolean): GameHolds =
        GameHolds(if (on) bits or (1 shl hold.ordinal) else bits and (1 shl hold.ordinal).inv())

    fun has(hold: GameHold): Boolean = bits and (1 shl hold.ordinal) != 0

    val paused: Boolean get() = bits != 0

    /** The reason to SAY, when there is one to say. */
    val primary: GameHold? get() = GameHold.entries.firstOrNull { has(it) }

    companion object {
        val NONE = GameHolds(0)
    }
}

/**
 * The live gate the frame loop reads and the composition writes.
 *
 * Plain volatile memory, not snapshot state: it is written from the main thread (lifecycle observer,
 * dialog toggles, the alarm collector) and read from the game thread every frame, and making it
 * observable would invalidate the composition on every hold change for no reader that needs it — the
 * loop polls it, and [GameScreen] renders the pause overlay off a separate, deliberately coarse
 * snapshot so a hold does not recompose anything at frame rate.
 */
class GamePauseGate {
    @Volatile
    var holds: GameHolds = GameHolds.NONE
        private set

    val paused: Boolean get() = holds.paused

    fun set(hold: GameHold, on: Boolean) {
        holds = holds.with(hold, on)
    }
}

/**
 * Frame pacing, and the one place the pause discontinuity is resolved.
 *
 * Two jobs, both of which have to happen where the wall clock is read:
 *
 *  - **the 60 fps cadence.** The display runs at 120 Hz on the target device, and `withFrameNanos`
 *    fires once per refresh. The schedule is a PHASE, not a minimum delta: [dueNs] advances by exactly
 *    [periodNs] per simulated frame and the first callback at (or near) it is the one simulated. A
 *    gate re-derived from the last simulated stamp instead leaves only the difference between the
 *    nominal periods as its whole error budget — 0.667 ms across two 120 Hz callbacks — which ordinary
 *    vsync jitter exceeds, so the loop waits for a third callback and the frame train alternates
 *    ~16.7 / ~25 ms. A phase with slack has no such cliff.
 *  - **resuming cleanly.** While held, the mark keeps advancing and nothing is simulated, so the first
 *    live frame after a five-minute background carries one frame of time rather than five minutes of
 *    it. Without this the solver would be handed a delta it would (correctly) clamp, and the car would
 *    still lurch.
 *
 * What is returned is always the REAL wall clock elapsed since the last simulated frame, never the
 * nominal period: the solver accumulates it and must be told the truth. A genuine stall — a long GC, a
 * slow first frame — is therefore handed over whole; `GameWorld.step` consumes it in fixed ticks and
 * drops the surplus past its own internal cap, which is the right place for that decision to live.
 */
class FrameClockPacer(private val periodNs: Long = FRAME_NS_60) {
    private val slackNs = periodNs / 4
    private var markNs = 0L
    private var dueNs = 0L
    private var started = false

    /** Milliseconds to simulate for the frame at [nowNs], or 0 to skip it. */
    fun tick(nowNs: Long, paused: Boolean): Float {
        // Not yet running, held, or handed a stamp that went backwards (a re-based clock): re-mark and
        // simulate nothing, so the discontinuity is dropped rather than integrated.
        if (!started || paused || nowNs <= markNs) {
            markNs = nowNs
            dueNs = nowNs + periodNs
            started = true
            return 0f
        }
        if (nowNs < dueNs - slackNs) return 0f
        val elapsed = nowNs - markNs
        markNs = nowNs
        dueNs += periodNs
        // Already behind by most of a period after that advance: the loop stalled, so re-anchor the
        // phase to now. Carrying the backlog forward would fire a run of near-empty frames to walk it
        // off, and the wait is spent time either way — it cannot be reclaimed by simulating sooner.
        if (dueNs <= nowNs + slackNs) dueNs = nowNs + periodNs
        return elapsed / 1_000_000f
    }

    companion object {
        /**
         * The nominal 60 fps period, and the slack around it is a quarter of one.
         *
         * The slack has to swallow the jitter between two callbacks a period apart while still
         * rejecting a callback a half-period early: at 120 Hz that means admitting ±4.2 ms of drift
         * against an 8.3 ms separation, which covers vsync jitter with room to spare and cannot
         * mistake the intervening callback for the due one. There is otherwise no rate governor: per
         * the explicit decision, thermal headroom does NOT throttle this loop.
         */
        const val FRAME_NS_60 = 16_666_667L
    }
}
