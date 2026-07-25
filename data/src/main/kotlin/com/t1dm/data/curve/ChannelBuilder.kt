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

/**
 * Builds the model's two event-reconstructed input channels from the dose store (SPEC §3.3).
 * ONE transform, three consumers:
 *  - [contextChannels] — the historical carb/insulin context that feeds `build_context`
 *    (feat 1 / feat 2) alongside the CGM-derived BG channel;
 *  - [futureOverrides] — the announced-future conditioning the calculators inject into the
 *    prediction zone (existing tails + announced + candidate + auto-extended basal);
 *  - [onBoard] — IOB/COB as remaining tail area, surfaced with provenance by the caller.
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
        val boluses = store.insulinEvents(fromPadded, gridEndMs)
        val basal = basalEvents(fromPadded, gridEndMs)

        val carbCh = engine.bucketize(carbs, gridStartMs, nSteps, CurveKind.CARB)
        val insulinCh = engine.bucketize(boluses + basal, gridStartMs, nSteps, CurveKind.INSULIN)
        return ContextChannels(carbCh, insulinCh)
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
        val storeInsulin = store.insulinEvents(fromPadded, horizonEndMs)
        val basal = basalEvents(fromPadded, horizonEndMs)

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
     * The BASAL-only insulin channel over `[gridStartMs, gridStartMs + nSteps·STEP_MS)` (issue 18):
     * the single basal representation (auto-extended daily schedule if configured, else the discrete
     * logged long-acting injections — issue #6 XOR), bucketized on the same grid as [contextChannels].
     * Purely for the dashboard's separate basal overlay series — the model still consumes the
     * COMBINED insulin channel from [contextChannels].
     */
    suspend fun basalChannel(gridStartMs: Long, nSteps: Int): DoubleArray {
        val gridEndMs = gridStartMs + nSteps * CurveEngine.STEP_MS
        val fromPadded = gridStartMs - PAD_MS
        val basal = basalEvents(fromPadded, gridEndMs)
        return engine.bucketize(basal, gridStartMs, nSteps, CurveKind.INSULIN)
    }

    /** IOB/COB at [atMs] from the logged store doses only (the value the decision card shows). */
    suspend fun onBoard(atMs: Long, kind: CurveKind): Double {
        val from = atMs - PAD_MS
        val events = when (kind) {
            CurveKind.CARB -> store.carbEvents(from, atMs + CurveEngine.STEP_MS)
            CurveKind.INSULIN ->
                store.insulinEvents(from, atMs + CurveEngine.STEP_MS) +
                    basalEvents(from, atMs + CurveEngine.STEP_MS)
        }
        return engine.onBoard(events, atMs, kind)
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
    suspend fun insulinZeroMs(atMs: Long): Long? {
        val from = atMs - PAD_MS
        val events = store.insulinEvents(from, atMs + CurveEngine.STEP_MS) +
            basalEvents(from, atMs + CurveEngine.STEP_MS)
        return events.asSequence()
            .filter { it.kind == CurveKind.INSULIN }
            .mapNotNull { ev -> ev.values.indexOfLast { it > 0.0 }.takeIf { it >= 0 }?.let { j -> ev.startMs + j * ev.stepMs } }
            .maxOrNull()
    }

    /**
     * The single basal representation over `[fromMs, toMs)` (issue #6): the auto-extended active
     * daily schedule if one is configured, ELSE the discrete logged BASAL injections — schedule
     * XOR discrete, never both, so a schedule-user who also logs a basal injection never
     * double-counts. Schedule-primary is the fail-closed direction: presence of a schedule (not the
     * emptiness of its output over this window) selects it, so a spurious extra basal can't inflate
     * the combined channel and deflate the forecast into admitting a larger dose.
     */
    private suspend fun basalEvents(fromMs: Long, toMs: Long): List<CurveEvent> =
        store.activeBasalSchedule()?.let { engine.extendBasal(it, fromMs, toMs) }
            ?: store.basalInjectionEvents(fromMs, toMs)

    companion object {
        /** Look-back padding (minutes) covering the longest plausible action tail (degludec ~42 h). */
        const val PAD_MIN: Long = 48 * 60
        val PAD_MS: Long = PAD_MIN * 60_000
    }
}
