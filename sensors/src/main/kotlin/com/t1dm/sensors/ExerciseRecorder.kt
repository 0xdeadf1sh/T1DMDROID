package com.t1dm.sensors

import com.t1dm.core.common.T1dmDispatchers
import com.t1dm.core.model.ActiveExercise
import com.t1dm.core.model.ExerciseSession
import com.t1dm.core.model.TrackPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/** What a finished bout measured, for the row that closes it. */
data class ExerciseSummary(val activeSec: Int, val distanceM: Double?, val kcal: Int?)

/**
 * Drives one bout: merges [LocationSource.fixes] with a wall-clock ticker, walks them through an
 * [ExerciseBucketer], lays the bout's glucose-disposal curve into `sample.exercise` and each accepted
 * fix into the bout's track, and publishes the live [ActiveExercise] the panel and the service
 * notification read.
 *
 * One instance per bout. [run] collects until its scope is cancelled; [finish] then flushes the final
 * partial bucket and the last unwritten fixes and returns what the bout measured. The two are
 * separate because the flush must survive the cancellation that ended the recording — the caller runs
 * [finish] on a scope that outlives the service.
 *
 * Under battery saver the fix interval COARSENS (see [LocationSource.LOW_POWER_MIN_TIME_MS]) and the
 * reason is published on [ActiveExercise.degraded]. It never stops ITSELF on a degraded or silent
 * signal: a recording that quietly ended mid-bout would hand back a truncated track that looks
 * complete, which is the one failure this feature cannot allow. The only bound on a bout's length is
 * the service's own limit, applied from outside.
 */
class ExerciseRecorder(
    private val session: ExerciseSession,
    private val source: LocationSource,
    private val samples: ExerciseSampleWriter,
    private val track: ExerciseTrackWriter,
    private val dispatchers: T1dmDispatchers,
    private val active: MutableStateFlow<ActiveExercise?>,
    private val bodyMassKg: suspend () -> Double?,
    private val lowPower: Flow<Boolean> = flowOf(false),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val bucketer = ExerciseBucketer(session.startMs)
    private val pending = ArrayList<TrackPoint>(TRACK_BATCH)

    /**
     * The grid bucket the bout is currently inside. Moving it is what triggers a magnitude write —
     * see [advance]. Seeded from the bucketer rather than computed here, so the boundary this keys
     * off is the one the bucketer actually uses.
     */
    private var openBucketStart: Long = bucketer.peek().bucketStartMs

    /**
     * Every grid bucket this bout has touched, with the metres and the seconds it holds — the segments
     * [ExerciseEnergy] scores the bout over, one entry per five minutes.
     *
     * Kept here rather than derived at the end because the buckets are not stored: `sample.exercise`
     * carries the disposal curve, which is a function of the bout's duration and says nothing about
     * where its metres were covered, and `exercise_session` carries only the bout's totals. The open
     * bucket is overwritten on every event, so this is always the bout as it stands.
     */
    private val segments = LinkedHashMap<Long, ExerciseBucket>()

    private var bodyMass: Double? = null
    private var lowPowerNow = false

    /** Collect fixes and ticks until the calling scope is cancelled. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun run() = withContext(dispatchers.default) {
        bodyMass = bodyMassKg()
        publish(clock())
        val fixes = lowPower
            .distinctUntilChanged()
            .onEach { lowPowerNow = it }
            .flatMapLatest { low ->
                source.fixes(if (low) LocationSource.LOW_POWER_MIN_TIME_MS else LocationSource.MIN_TIME_MS)
            }
        merge(fixes.map { Event.Fix(it) }, ticker().map { Event.Tick(it) }).collect { onEvent(it) }
    }

    /**
     * Close the bout at [endMs]: lay the curve for the bout's final duration, write the fixes still
     * batched, and report the totals. Safe on a double stop — the second call resolves the same
     * duration to the same curve and the writer finds nothing changed, so a bout's magnitude cannot
     * be counted twice.
     */
    suspend fun finish(endMs: Long): ExerciseSummary = withContext(dispatchers.default) {
        bodyMass = bodyMassKg()
        advance(bucketer.onTick(endMs))
        // Always, whether or not the advance crossed a boundary: this is the bout's final duration
        // and the stored curve has to be the one that sums to its magnitude.
        persist()
        flushTrack()
        ExerciseSummary(
            activeSec = bucketer.activeSec,
            distanceM = bucketer.distanceM,
            kcal = kcalNow(),
        )
    }

    private suspend fun onEvent(event: Event) = when (event) {
        is Event.Fix -> {
            advance(bucketer.onFix(event.fix))
            // The bucketer is the only judge of a fix; a refused one leaves `lastFix` where it was.
            if (bucketer.lastFix === event.fix) {
                pending.add(event.fix.toTrackPoint())
                if (pending.size >= TRACK_BATCH) flushTrack()
            }
            publish(event.fix.tsMs)
        }
        is Event.Tick -> {
            bodyMass = bodyMassKg()
            advance(bucketer.onTick(event.wallMs))
            flushTrack()
            publish(event.wallMs)
        }
    }

    /**
     * Fold what the bucketer just reported into [segments], and re-lay the bout's disposal curve
     * once per five-minute boundary.
     *
     * The open partial is taken from [ExerciseBucketer.peek] rather than from [buckets], because
     * `onFix` returns only the buckets it CLOSED — the metres it just added land in a bucket that is
     * still open, and the live energy figure has to see them.
     *
     * **The magnitude write is driven by the GRID, not by the ticker.** The curve is a function of
     * the bout's whole duration, so a longer bout is a different curve in every bucket it covers;
     * rewriting it on every 30-second tick would re-file a couple of hundred grid rows ten times per
     * five minutes for a number that only becomes meaningful at the grid's own resolution. Crossing
     * a boundary is the moment the stored series can actually change, so that is when it is written.
     * A bout shorter than one bucket writes exactly once, at [finish].
     */
    private suspend fun advance(buckets: List<ExerciseBucket>) {
        for (b in buckets) segments[b.bucketStartMs] = b
        val open = bucketer.peek()
        segments[open.bucketStartMs] = open
        if (open.bucketStartMs != openBucketStart) {
            openBucketStart = open.bucketStartMs
            persist()
        }
    }

    /**
     * Lay the bout's curve as it now stands, replacing what this bout last wrote.
     *
     * The writer is the one that knows what it put where, so this hands over a duration and nothing
     * else. Recording the same duration twice writes nothing, which is what makes a second [finish]
     * harmless.
     */
    private suspend fun persist() {
        samples.record(session.startMs, bucketer.activeSec / 60.0)
    }

    private suspend fun flushTrack() {
        if (pending.isEmpty()) return
        track.append(session.id, pending.toList())
        pending.clear()
    }

    private fun publish(nowMs: Long) {
        val distanceM = bucketer.distanceM
        val activeSec = bucketer.activeSec
        active.value = ActiveExercise(
            session = session,
            elapsedMs = (nowMs - session.startMs).coerceAtLeast(0L),
            distanceM = distanceM ?: 0.0,
            paceSecPerKm = pace(distanceM, activeSec),
            kcal = kcalNow(),
            lastFixAgeMs = bucketer.lastFix?.let { (nowMs - it.tsMs).coerceAtLeast(0L) },
            degraded = degradedReason(nowMs),
        )
    }

    /** The bout's energy as it stands, scored over its own five-minute segments — never over its
     *  average speed, which a stretch that was not the labelled exercise would carry away. */
    private fun kcalNow(): Int? = ExerciseEnergy.kcal(session.kind, segments.values, bodyMass)

    /**
     * Why the track is worse than the user asked for, or null when it is not. A location service that
     * has been switched off or suspended still holds a bout open, and a track that merely stops
     * growing is indistinguishable from a walk that went nowhere unless the panel is told.
     *
     * Ordered by what the user can act on, most specific first: a coarse-only grant, which no amount
     * of sky will fix and which the receiver is never even started for; then location switched off;
     * then a receiver that has gone quiet; then the coarser cadence battery saver imposes — which is a
     * degradation, not a fault.
     */
    private fun degradedReason(nowMs: Long): String? {
        if (!source.isPrecise()) return NO_PRECISE
        if (!source.isEnabled()) return LOCATION_OFF
        val sinceMs = nowMs - (bucketer.lastFix?.tsMs ?: session.startMs)
        if (sinceMs >= STALE_FIX_MS) return "No fix for ${sinceMs / 60_000} min"
        if (lowPowerNow) return "Low power — ${LocationSource.LOW_POWER_MIN_TIME_MS / 1000} s fixes"
        return null
    }

    private fun pace(distanceM: Double?, activeSec: Int): Double? {
        if (distanceM == null || distanceM < MIN_PACE_DISTANCE_M || activeSec <= 0) return null
        return activeSec / (distanceM / 1000.0)
    }

    private fun ticker(): Flow<Long> = flow {
        while (true) {
            delay(TICK_MS)
            emit(clock())
        }
    }

    private sealed interface Event {
        class Fix(val fix: ExerciseFix) : Event
        class Tick(val wallMs: Long) : Event
    }

    companion object {
        /** How often the bout's seconds are advanced with no fix to prompt it. Finer than the grid
         *  the magnitude is written on, because the live panel reads the same seconds. */
        const val TICK_MS = 30_000L

        /** Fixes per track write. At a 4 s cadence that is a batch every 40 s, not a write per fix. */
        const val TRACK_BATCH = 10

        /** The same discriminator the project uses for a suspended BLE scan: the process is alive
         *  while the newest stamp is frozen. */
        const val STALE_FIX_MS = 180_000L

        /** Below this a pace is the receiver's own scatter divided by a small number. */
        const val MIN_PACE_DISTANCE_M = 50.0

        const val LOCATION_OFF = "Location off — no track"

        /** A coarse-only grant. Named separately because "no fix" would send the user to look at the
         *  sky rather than at the Precise/Approximate toggle that actually caused it. */
        const val NO_PRECISE = "Approximate location — no track"
    }
}
