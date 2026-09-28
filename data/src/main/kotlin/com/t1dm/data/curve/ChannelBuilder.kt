package com.t1dm.data.curve

import com.t1dm.core.model.BasalSchedule
import com.t1dm.core.model.CurveEvent
import com.t1dm.core.model.CurveKind

/** Events keyed on start time; callers pad fromMs by [ChannelBuilder.PAD_MS] to reach each tail. */
interface DoseStore {
    suspend fun carbEvents(fromMs: Long, toMs: Long): List<CurveEvent>

    /** Boluses only; caller pairs with exactly ONE basal rep, schedule or injections, not both. */
    suspend fun insulinEvents(fromMs: Long, toMs: Long): List<CurveEvent>

    suspend fun activeBasalSchedule(): BasalSchedule?

    suspend fun basalInjectionEvents(fromMs: Long, toMs: Long): List<CurveEvent> = emptyList()

    /** `first` is the BOLUS half, `second` the BASAL half — the lists the separate calls return. */
    suspend fun insulinAndBasalInjectionEvents(
        fromMs: Long,
        toMs: Long,
    ): Pair<List<CurveEvent>, List<CurveEvent>> =
        insulinEvents(fromMs, toMs) to basalInjectionEvents(fromMs, toMs)
}

data class ContextChannels(val carb: DoubleArray, val insulin: DoubleArray) {
    override fun equals(other: Any?): Boolean =
        other is ContextChannels && carb.contentEquals(other.carb) &&
            insulin.contentEquals(other.insulin)

    override fun hashCode(): Int = 31 * carb.contentHashCode() + insulin.contentHashCode()
}

/** Grams of carbohydrate equivalent per bucket. Unwired ⇒ zeros, not a fabricated curve. */
fun interface ExerciseChannelSource {
    suspend fun exercise(gridStartMs: Long, nSteps: Int): DoubleArray
}

/** carb/insulin per-5-min over the roll horizon: dose tails, announced doses, candidate, basal. */
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

data class InsulinOnBoard(val iobU: Double, val zeroMs: Long?)

/** basal is a COMPONENT of insulin, not independent; summing double-counts. exercise is READ. */
data class OverlayChannels(
    val carb: DoubleArray,
    val insulin: DoubleArray,
    val basal: DoubleArray,
    val exercise: DoubleArray,
) {
    override fun equals(other: Any?): Boolean =
        other is OverlayChannels && carb.contentEquals(other.carb) &&
            insulin.contentEquals(other.insulin) && basal.contentEquals(other.basal) &&
            exercise.contentEquals(other.exercise)

    override fun hashCode(): Int {
        var h = carb.contentHashCode()
        h = 31 * h + insulin.contentHashCode()
        h = 31 * h + basal.contentHashCode()
        h = 31 * h + exercise.contentHashCode()
        return h
    }
}

/** Carb/insulin channels are EVENT-RECONSTRUCTED; CGM gap interpolation touches only BG. */
class ChannelBuilder(
    private val engine: CurveEngine,
    private val store: DoseStore,
    private val exerciseSource: ExerciseChannelSource? = null,
) {
    private suspend fun exerciseChannel(gridStartMs: Long, nSteps: Int): DoubleArray =
        exerciseSource?.exercise(gridStartMs, nSteps) ?: DoubleArray(nSteps)

    /** [insulin] is bolus PK plus the basal background. */
    suspend fun contextChannels(gridStartMs: Long, nSteps: Int): ContextChannels {
        val gridEndMs = gridStartMs + nSteps * CurveEngine.STEP_MS
        val fromPadded = gridStartMs - PAD_MS
        val carbs = store.carbEvents(fromPadded, gridEndMs)
        val insulin = insulinEventsIn(fromPadded, gridEndMs)

        val carbCh = engine.bucketize(carbs, gridStartMs, nSteps, CurveKind.CARB)
        val insulinCh = engine.bucketize(insulin.combined, gridStartMs, nSteps, CurveKind.INSULIN)
        return ContextChannels(carbCh, insulinCh)
    }

    /** contextChannels plus the basal sub-series, one gather; byte-for-byte the same arrays. */
    suspend fun overlayChannels(gridStartMs: Long, nSteps: Int): OverlayChannels {
        val gridEndMs = gridStartMs + nSteps * CurveEngine.STEP_MS
        val fromPadded = gridStartMs - PAD_MS
        val carbs = store.carbEvents(fromPadded, gridEndMs)
        val insulin = insulinEventsIn(fromPadded, gridEndMs)

        return OverlayChannels(
            carb = engine.bucketize(carbs, gridStartMs, nSteps, CurveKind.CARB),
            insulin = engine.bucketize(insulin.combined, gridStartMs, nSteps, CurveKind.INSULIN),
            basal = engine.bucketize(insulin.basal, gridStartMs, nSteps, CurveKind.INSULIN),
            exercise = exerciseChannel(gridStartMs, nSteps),
        )
    }

    /** [announced] and [candidate] are pre-resolved [CurveEvent]s with absolute `startMs`. */
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

        // Logged doses only, never announced or candidate.
        val iob = engine.onBoard(storeInsulin + basal, rollStartMs, CurveKind.INSULIN)
        val cob = engine.onBoard(storeCarbs, rollStartMs, CurveKind.CARB)
        return FutureChannels(carbCh, insulinCh, iob, cob)
    }

    /** Logged store doses only; exercise is READ from wide sample, nothing here to integrate. */
    suspend fun onBoard(atMs: Long, kind: CurveKind): Double {
        val events = when (kind) {
            CurveKind.CARB -> store.carbEvents(atMs - PAD_MS, atMs + CurveEngine.STEP_MS)
            CurveKind.INSULIN -> insulinEventsAt(atMs)
            CurveKind.EXERCISE -> return 0.0
        }
        return engine.onBoard(events, atMs, kind)
    }

    /** [onBoard]`(atMs, INSULIN)` and [insulinZeroMs]`(atMs)` from one gather; identical values. */
    suspend fun insulinOnBoard(atMs: Long): InsulinOnBoard {
        val events = insulinEventsAt(atMs)
        return InsulinOnBoard(
            iobU = engine.onBoard(events, atMs, CurveKind.INSULIN),
            zeroMs = insulinZeroOf(events),
        )
    }

    private suspend fun insulinEventsAt(atMs: Long): List<CurveEvent> =
        insulinEventsIn(atMs - PAD_MS, atMs + CurveEngine.STEP_MS).combined

    private class InsulinEvents(val bolus: List<CurveEvent>, val basal: List<CurveEvent>) {
        /** Boluses first, then basal. */
        val combined: List<CurveEvent> get() = bolus + basal
    }

    /** schedule-XOR-discrete rule's only home: extended schedule if configured, else discrete. */
    private suspend fun insulinEventsIn(fromMs: Long, toMs: Long): InsulinEvents {
        val schedule = store.activeBasalSchedule()
        if (schedule != null) {
            return InsulinEvents(store.insulinEvents(fromMs, toMs), engine.extendBasal(schedule, fromMs, toMs))
        }
        val (bolus, basal) = store.insulinAndBasalInjectionEvents(fromMs, toMs)
        return InsulinEvents(bolus, basal)
    }

    /** Carb and bolus starts in `[fromMs, toMs)`, ascending; basal is background, not an event. */
    suspend fun eventOnsets(fromMs: Long, toMs: Long): LongArray {
        val carbs = store.carbEvents(fromMs, toMs)
        val bolus = store.insulinEvents(fromMs, toMs)
        val out = LongArray(carbs.size + bolus.size)
        var n = 0
        for (e in carbs) if (e.total > 0.0 && e.startMs in fromMs until toMs) out[n++] = e.startMs
        for (e in bolus) if (e.total > 0.0 && e.startMs in fromMs until toMs) out[n++] = e.startMs
        return out.copyOf(n).apply { sort() }
    }

    /** The IOB ceiling's input: boluses only, basal excluded. */
    suspend fun bolusOnBoard(atMs: Long): Double =
        engine.onBoard(insulinEventsIn(atMs - PAD_MS, atMs + CurveEngine.STEP_MS).bolus, atMs, CurveKind.INSULIN)

    /** Null when no insulin action; past instants aren't clipped, a decayed dose predates atMs. */
    suspend fun insulinZeroMs(atMs: Long): Long? = insulinZeroOf(insulinEventsAt(atMs))

    private fun insulinZeroOf(events: List<CurveEvent>): Long? = events.asSequence()
        .filter { it.kind == CurveKind.INSULIN }
        .mapNotNull { ev -> ev.values.indexOfLast { it > 0.0 }.takeIf { it >= 0 }?.let { j -> ev.startMs + j * ev.stepMs } }
        .maxOrNull()

    companion object {
        /** Minutes. Covers the longest action window, degludec 133 h; caps custom insulin types. */
        const val PAD_MIN: Long = 134 * 60
        val PAD_MS: Long = PAD_MIN * 60_000
    }
}
