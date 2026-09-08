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

data class ExerciseSummary(val activeSec: Int, val distanceM: Double?, val kcal: Int?)

/** One instance/bout; [run] cancels its scope, [finish] must outlive it; never self-stops. */
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

    /** Seeded from the bucketer so the boundary keyed off is the one the bucketer uses. */
    private var openBucketStart: Long = bucketer.peek().bucketStartMs

    /** The segments [ExerciseEnergy] scores over; held here because no bucket is ever stored. */
    private val segments = LinkedHashMap<Long, ExerciseBucket>()

    private var bodyMass: Double? = null
    private var lowPowerNow = false

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

    /** Safe on a double stop: same duration ⇒ same curve, unchanged, no double count. */
    suspend fun finish(endMs: Long): ExerciseSummary = withContext(dispatchers.default) {
        bodyMass = bodyMassKg()
        advance(bucketer.onTick(endMs))
        // Boundary crossed or not: the stored curve must sum to the bout's final duration.
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
            // A refused fix leaves `lastFix` where it was.
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

    /** Open partial via [ExerciseBucketer.peek] (onFix returns only closed); grid-driven write. */
    private suspend fun advance(buckets: List<ExerciseBucket>) {
        for (b in buckets) segments[b.bucketStartMs] = b
        val open = bucketer.peek()
        segments[open.bucketStartMs] = open
        if (open.bucketStartMs != openBucketStart) {
            openBucketStart = open.bucketStartMs
            persist()
        }
    }

    /** The writer knows what it put where, so this hands over a duration and nothing else. */
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

    private fun kcalNow(): Int? = ExerciseEnergy.kcal(session.kind, segments.values, bodyMass)

    /** Ordered by what the user can act on, most specific first. */
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
        /** Finer than the write grid, because the live panel reads the same seconds. */
        const val TICK_MS = 30_000L

        /** Fixes per track write; at a 4s cadence that's a batch every 40s, not a write per fix. */
        const val TRACK_BATCH = 10

        /** The project's discriminator for a suspended scan: alive, newest stamp frozen. */
        const val STALE_FIX_MS = 180_000L

        /** Below this a pace is the receiver's own scatter divided by a small number. */
        const val MIN_PACE_DISTANCE_M = 50.0

        const val LOCATION_OFF = "Location off — no track"

        /** Separate from "no fix", which would point the user at the sky, not at the toggle. */
        const val NO_PRECISE = "Approximate location — no track"
    }
}
