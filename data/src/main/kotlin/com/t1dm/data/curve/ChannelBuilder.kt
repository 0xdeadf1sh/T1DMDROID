package com.t1dm.data.curve

import com.t1dm.core.model.BasalSchedule
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind

/**
 * Source of the logged carb/insulin/basal events the [ChannelBuilder] reconstructs channels
 * from (§3.3). Kept as an interface so `:calc`/tests can substitute a fake and
 * so the builder never reaches into Room directly. The default implementation
 * ([com.t1dm.data.curve.RoomDoseStore]) reads `logged_dose` / `logged_meal` / `basal_schedule`
 * and resolves each row into a [CurveEvent] via the [CurveEngine].
 *
 * "Overlapping [fromMs, toMs)" — an event is returned when its ACTION overlaps the window, so a
 * dose taken before `fromMs` whose PK tail reaches into the window is included (existing-dose
 * tails carried forward, SPEC §3.3). Callers query a padded window (`fromMs - maxDia`) and let
 * bucketize/onBoard clip.
 */
interface DoseStore {
    /** Resolved carb appearance ([CurveKind.CARB]) events whose Ra window overlaps `[fromMs,toMs)`. */
    suspend fun carbEvents(fromMs: Long, toMs: Long): List<CurveEvent>

    /** Resolved discrete BOLUS ([CurveKind.INSULIN], gamma PK) events whose action overlaps
     *  `[fromMs,toMs)`. Basal is NOT included here (issue #6): the caller pairs this with exactly
     *  one basal representation — the auto-extended [activeBasalSchedule] if configured, else the
     *  discrete [basalInjectionEvents] — so a schedule-user who also logs basal never double-counts. */
    suspend fun insulinEvents(fromMs: Long, toMs: Long): List<CurveEvent>

    /** The active daily basal schedule (MDI), or null if none is configured. */
    suspend fun activeBasalSchedule(): BasalSchedule?

    /** Only the discrete long-acting BASAL injections (not boluses) whose action overlaps
     *  `[fromMs,toMs)` — the basal subset of [insulinEvents], used purely for the dashboard's
     *  separate basal overlay series (issue 18). Default empty for fakes that don't distinguish. */
    suspend fun basalInjectionEvents(fromMs: Long, toMs: Long): List<CurveEvent> = emptyList()

    /**
     * [insulinEvents] and [basalInjectionEvents] over the SAME window, from one read.
     *
     * The two are a partition of one table by `kind`, and every caller that has no basal schedule
     * wants both halves of the identical window — so asking separately issued two identical queries
     * differing only in a post-hoc predicate, and (for the Room store) reconstructed every row's PK
     * curve twice. The default splits the two calls exactly as before, so a fake need not implement
     * it; the Room store overrides it with a single windowed read.
     *
     * `first` is the BOLUS half, `second` the BASAL half — the same lists, in the same order, that
     * the two separate calls return.
     */
    suspend fun insulinAndBasalInjectionEvents(
        fromMs: Long,
        toMs: Long,
    ): Pair<List<CurveEvent>, List<CurveEvent>> =
        insulinEvents(fromMs, toMs) to basalInjectionEvents(fromMs, toMs)
}

/** The two normalized-ready raw channels over a grid: carbs (Ra) and insulin (combined). */
data class ContextChannels(val carb: DoubleArray, val insulin: DoubleArray) {
    override fun equals(other: Any?): Boolean =
        other is ContextChannels && carb.contentEquals(other.carb) && insulin.contentEquals(other.insulin)

    override fun hashCode(): Int = 31 * carb.contentHashCode() + insulin.contentHashCode()
}

/**
 * The future carb/insulin channels for announced-future conditioning (SPEC §3.3), plus the IOB
 * at the roll start. [carb]/[insulin] are per-5-min amounts over the prediction/roll horizon,
 * feeding `build_context`'s `announced_carb` / `announced_insulin`. They fold together
 * (a) existing-dose PK/Ra TAILS carried past the roll start, (b) user-announced future meals/
 * boluses, (c) an optional CANDIDATE dose the calculator is scoring, and (d) the auto-extended
 * basal background.
 */
data class FutureChannels(
    val carb: DoubleArray,
    val insulin: DoubleArray,
    val iobAtStart: Double,
    val cobAtStart: Double,
) {
    override fun equals(other: Any?): Boolean =
        other is FutureChannels && carb.contentEquals(other.carb) && insulin.contentEquals(other.insulin) &&
            iobAtStart == other.iobAtStart && cobAtStart == other.cobAtStart

    override fun hashCode(): Int {
        var h = carb.contentHashCode()
        h = 31 * h + insulin.contentHashCode()
        h = 31 * h + iobAtStart.hashCode()
        h = 31 * h + cobAtStart.hashCode()
        return h
    }
}

/** IOB at an instant paired with the instant it reaches zero — see [ChannelBuilder.insulinOnBoard]. */
data class InsulinOnBoard(val iobU: Double, val zeroMs: Long?)

/**
 * The dashboard overlay's three series over one grid: [carb] and the COMBINED [insulin] exactly as
 * [ContextChannels] carries them, plus the BASAL-only sub-channel (issue 18) — all bucketized from
 * ONE gather. The overlay draws all three together and used to ask for them through two calls — a
 * context build and a separate basal build — each of which resolved the same padded window and
 * rebuilt the same basal representation, though the basal series is a COMPONENT of the combined
 * insulin one rather than an independent quantity.
 */
data class OverlayChannels(val carb: DoubleArray, val insulin: DoubleArray, val basal: DoubleArray) {
    override fun equals(other: Any?): Boolean =
        other is OverlayChannels && carb.contentEquals(other.carb) &&
            insulin.contentEquals(other.insulin) && basal.contentEquals(other.basal)

    override fun hashCode(): Int =
        31 * (31 * carb.contentHashCode() + insulin.contentHashCode()) + basal.contentHashCode()
}

/**
 * Builds the model's two event-reconstructed input channels from the dose store (SPEC §3.3).
 * ONE transform, four consumers:
 *  - [contextChannels] — the historical carb/insulin context that feeds `build_context`
 *    (feat 1 / feat 2) alongside the CGM-derived BG channel;
 *  - [overlayChannels] — the same two plus the basal sub-series, for the dashboard's overlay;
 *  - [futureOverrides] — the announced-future conditioning the calculators inject into the
 *    prediction zone (existing tails + announced + candidate + auto-extended basal);
 *  - [onBoard] — IOB/COB as remaining tail area, surfaced with provenance by the caller.
 *
 * Every one of them resolves its insulin side through the single private gather that states the
 * issue-#6 schedule-XOR-discrete rule once.
 *
 * Carb/insulin channels are EVENT-RECONSTRUCTED here, so the CGM reboot-gap interpolation only
 * ever touches the BG channel (SPEC §3.3).
 */
class ChannelBuilder(
    private val engine: CurveEngine,
    private val store: DoseStore,
) {
    /**
     * The historical context channels over `[gridStartMs, gridStartMs + nSteps·STEP_MS)`:
     * carb appearance (feat 1) and combined insulin action = bolus PK + basal background
     * (feat 2). Reads events from a window padded back by [PAD_MIN] so a dose taken just before
     * the grid still contributes its overlapping tail.
     */
    suspend fun contextChannels(gridStartMs: Long, nSteps: Int): ContextChannels {
        val gridEndMs = gridStartMs + nSteps * CurveEngine.STEP_MS
        val fromPadded = gridStartMs - PAD_MS
        val carbs = store.carbEvents(fromPadded, gridEndMs)
        val insulin = insulinEventsIn(fromPadded, gridEndMs)

        val carbCh = engine.bucketize(carbs, gridStartMs, nSteps, CurveKind.CARB)
        val insulinCh = engine.bucketize(insulin.combined, gridStartMs, nSteps, CurveKind.INSULIN)
        return ContextChannels(carbCh, insulinCh)
    }

    /**
     * [contextChannels] AND the basal sub-series over one grid, from ONE gather — what the dashboard
     * overlay actually draws (issue 18: carbs, combined insulin, and the basal background as its own
     * series). Asking through two separate entry points resolved the padded window twice and,
     * with it, rebuilt the single basal representation twice: two `activeBasalSchedule()` lookups and
     * either two `extendBasal` expansions or two Bateman reconstructions of every logged injection,
     * over a window the overlay caps at ~14 days of 5-min buckets. Byte-for-byte the same three
     * arrays: the same events, bucketized on the same grid, in the same order.
     */
    suspend fun overlayChannels(gridStartMs: Long, nSteps: Int): OverlayChannels {
        val gridEndMs = gridStartMs + nSteps * CurveEngine.STEP_MS
        val fromPadded = gridStartMs - PAD_MS
        val carbs = store.carbEvents(fromPadded, gridEndMs)
        val insulin = insulinEventsIn(fromPadded, gridEndMs)

        return OverlayChannels(
            carb = engine.bucketize(carbs, gridStartMs, nSteps, CurveKind.CARB),
            insulin = engine.bucketize(insulin.combined, gridStartMs, nSteps, CurveKind.INSULIN),
            basal = engine.bucketize(insulin.basal, gridStartMs, nSteps, CurveKind.INSULIN),
        )
    }

    /**
     * The announced-future channels over the roll horizon
     * `[rollStartMs, rollStartMs + nSteps·STEP_MS)`. [announced] is the user's announced future
     * meals/boluses; [candidate] is an optional dose the calculator is scoring (both are
     * pre-resolved [CurveEvent]s with absolute `startMs`). Existing-dose tails that spill past
     * [rollStartMs] are pulled from the store; the basal background (schedule XOR discrete
     * injections) spans the horizon. Also returns IOB/COB at [rollStartMs] (remaining tail area
     * from the STORE doses only — announced/candidate excluded so the card's "IOB from logged
     * doses only" holds).
     */
    suspend fun futureOverrides(
        rollStartMs: Long,
        nSteps: Int,
        announced: List<CurveEvent>,
        candidate: List<CurveEvent>?,
    ): FutureChannels {
        val horizonEndMs = rollStartMs + nSteps * CurveEngine.STEP_MS
        val fromPadded = rollStartMs - PAD_MS

        val storeCarbs = store.carbEvents(fromPadded, horizonEndMs)
        val gathered = insulinEventsIn(fromPadded, horizonEndMs)
        val storeInsulin = gathered.bolus
        val basal = gathered.basal

        val annCarbs = announced.filter { it.kind == CurveKind.CARB }
        val annInsulin = announced.filter { it.kind == CurveKind.INSULIN }
        val candInsulin = candidate?.filter { it.kind == CurveKind.INSULIN }.orEmpty()
        val candCarbs = candidate?.filter { it.kind == CurveKind.CARB }.orEmpty()

        val carbCh = engine.bucketize(
            storeCarbs + annCarbs + candCarbs,
            rollStartMs,
            nSteps,
            CurveKind.CARB,
        )
        val insulinCh = engine.bucketize(
            storeInsulin + basal + annInsulin + candInsulin,
            rollStartMs,
            nSteps,
            CurveKind.INSULIN,
        )

        // IOB/COB provenance: logged (store) doses only — NOT announced/candidate (SPEC §3.6-F).
        val iob = engine.onBoard(storeInsulin + basal, rollStartMs, CurveKind.INSULIN)
        val cob = engine.onBoard(storeCarbs, rollStartMs, CurveKind.CARB)
        return FutureChannels(carbCh, insulinCh, iob, cob)
    }

    /**
     * Every carb and insulin curve whose action overlaps `[fromMs, toMs)` — carbs, the logged
     * boluses, and the SINGLE basal representation, resolved through [insulinEventsIn] so the
     * schedule-XOR-discrete rule stays stated in exactly one place.
     *
     * The look-back padding is applied HERE rather than by the caller: [PAD_MS] is this file's own
     * knowledge of the longest plausible action tail, and a caller guessing its own pad would be a
     * second copy of it, free to be shorter than a degludec tail without anything noticing.
     *
     * The classical baseline's fit builds its strictly-causal IOB/COB columns from these. It wants
     * the raw events rather than the bucketized channels because on-board is the remaining FORWARD
     * area of each event, and computing that causally needs to know which event contributed what and
     * when it started — information a summed channel has already discarded.
     */
    suspend fun eventsIn(fromMs: Long, toMs: Long): List<CurveEvent> =
        store.carbEvents(fromMs - PAD_MS, toMs) + insulinEventsIn(fromMs - PAD_MS, toMs).combined

    /** IOB/COB at [atMs] from the logged store doses only (the value the decision card shows). */
    suspend fun onBoard(atMs: Long, kind: CurveKind): Double {
        val events = when (kind) {
            CurveKind.CARB -> store.carbEvents(atMs - PAD_MS, atMs + CurveEngine.STEP_MS)
            CurveKind.INSULIN -> insulinEventsAt(atMs)
        }
        return engine.onBoard(events, atMs, kind)
    }

    /**
     * IOB at [atMs] AND the instant it decays to zero, from ONE gather of the insulin events.
     *
     * [onBoard]`(atMs, INSULIN)` and [insulinZeroMs]`(atMs)` resolve the identical padded window — the
     * same `logged_dose` read, the same basal-schedule lookup, the same per-row PK reconstruction — and
     * every caller that wants one wants the other, so asking for them separately did all of it twice.
     * The two halves are computed here exactly as they are there: the IOB first (it is the one that can
     * fail, through the core), then the zero instant, which is total arithmetic over the events already
     * in hand.
     */
    suspend fun insulinOnBoard(atMs: Long): InsulinOnBoard {
        val events = insulinEventsAt(atMs)
        return InsulinOnBoard(
            iobU = engine.onBoard(events, atMs, CurveKind.INSULIN),
            zeroMs = insulinZeroOf(events),
        )
    }

    /** The logged boluses + the single basal representation whose action overlaps `[atMs - PAD, atMs]`. */
    private suspend fun insulinEventsAt(atMs: Long): List<CurveEvent> =
        insulinEventsIn(atMs - PAD_MS, atMs + CurveEngine.STEP_MS).combined

    /** The logged boluses and the single basal representation over one window, kept apart. */
    private class InsulinEvents(val bolus: List<CurveEvent>, val basal: List<CurveEvent>) {
        /** Boluses first, then basal — the concatenation order every caller already used. */
        val combined: List<CurveEvent> get() = bolus + basal
    }

    /**
     * The insulin side of `[fromMs, toMs)` in ONE pass — the logged boluses, and the SINGLE basal
     * representation. This is the only place the schedule-XOR-discrete rule is stated.
     *
     * Issue #6: the basal background is the auto-extended active daily schedule if one is configured,
     * ELSE the discrete logged BASAL injections — never both, so a schedule-user who also logs a basal
     * injection never double-counts. Schedule-primary is the fail-closed direction: PRESENCE of a
     * schedule (not the emptiness of its output over this window) selects it, so a spurious extra
     * basal can't inflate the combined channel and deflate the forecast into admitting a larger dose.
     *
     * Where there is no schedule — the fallback every user without a configured template takes — the
     * two halves come from one `logged_dose` window read rather than two identical reads filtered
     * differently after the fact (see [DoseStore.insulinAndBasalInjectionEvents]). Reading both halves
     * at one instant also makes the pair a single consistent snapshot, where two separate reads could
     * straddle a concurrent write.
     */
    private suspend fun insulinEventsIn(fromMs: Long, toMs: Long): InsulinEvents {
        val schedule = store.activeBasalSchedule()
        if (schedule != null) {
            return InsulinEvents(store.insulinEvents(fromMs, toMs), engine.extendBasal(schedule, fromMs, toMs))
        }
        val (bolus, basal) = store.insulinAndBasalInjectionEvents(fromMs, toMs)
        return InsulinEvents(bolus, basal)
    }

    /**
     * The wall-clock instant the combined insulin action last carries a positive value — the moment
     * IOB decays to zero, which the circadian panel projects DKA/coma/death forward from. Gathers
     * the SAME logged boluses + auto-extended basal tails as [onBoard]'s INSULIN branch (so the
     * "logged doses only" provenance holds), then, per event, finds the last non-zero step of its
     * PK curve and maps it back to absolute time; the latest such instant across all events wins —
     * a still-acting basal background pushes the zero past a shorter, already-decayed bolus. Null
     * when no insulin event carries any action (nothing on board). Does NOT clip past instants: an
     * event whose tail already lies before [atMs] still contributes, so a fully-decayed dose yields
     * a zero instant earlier than [atMs] rather than being dropped.
     */
    suspend fun insulinZeroMs(atMs: Long): Long? = insulinZeroOf(insulinEventsAt(atMs))

    private fun insulinZeroOf(events: List<CurveEvent>): Long? = events.asSequence()
        .filter { it.kind == CurveKind.INSULIN }
        .mapNotNull { ev -> ev.values.indexOfLast { it > 0.0 }.takeIf { it >= 0 }?.let { j -> ev.startMs + j * ev.stepMs } }
        .maxOrNull()

    companion object {
        /** Look-back padding (minutes) covering the longest plausible action tail (degludec ~42 h). */
        const val PAD_MIN: Long = 48 * 60
        val PAD_MS: Long = PAD_MIN * 60_000
    }
}
