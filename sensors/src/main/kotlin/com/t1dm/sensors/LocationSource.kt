package com.t1dm.sensors

import android.annotation.SuppressLint
import android.location.LocationListener
import android.location.LocationManager
import android.os.HandlerThread
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The hardware edge of the exercise track, in the shape of [StepSource]: registers a
 * [LocationListener] on `GPS_PROVIDER`, stamps each reading with phone wall time (as the CGM and step
 * paths do) and delivers it as a [Flow] of [ExerciseFix]. Whether a fix is believed is
 * [ExerciseBucketer]'s decision, not this class's.
 *
 * `GPS_PROVIDER` alone, deliberately: the fused provider lives in Play Services, which this app does
 * not carry, and the network provider returns cell/WiFi trilateration that a route drawn on a map
 * would render as a staircase between towers.
 *
 * **`ACCESS_FINE_LOCATION` specifically, not either location permission.** A coarse-only client still
 * receives `GPS_PROVIDER` updates on 31+, but the platform fuzzes them to a block and reports an
 * accuracy circle of roughly 2 km — every one of which [ExerciseBucketer] refuses at its 50 m ceiling.
 * The receiver would then run for the whole bout and land nothing at all, which is the always-on-radio
 * shape with no possible benefit. So [precise] is asked BEFORE the receiver is registered, the bout
 * records its seconds without a track, and the panel is told which toggle caused it.
 *
 * The permission is declared by this module's manifest and requested by the exercise panel. Without
 * it the flow closes empty rather than throwing into the collector — the recorder reports the
 * silence, and the caller sees a bout that records seconds and no metres instead of a crash inside a
 * foreground service.
 */
class LocationSource(
    private val locationManager: LocationManager,
    private val precise: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** True when the device exposes a GPS provider at all. */
    fun isAvailable(): Boolean =
        runCatching { LocationManager.GPS_PROVIDER in locationManager.allProviders }.getOrDefault(false)

    /** True when `ACCESS_FINE_LOCATION` is held. Asked live rather than captured, so a grant revoked
     *  or upgraded under a running bout is read as it stands. */
    fun isPrecise(): Boolean = runCatching { precise() }.getOrDefault(false)

    /** True when the user has location switched on for that provider — off is a live, reversible
     *  state, not a missing capability, so it is asked separately from [isAvailable]. */
    fun isEnabled(): Boolean =
        runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

    /**
     * Cold flow of fixes at no more than one per [minTimeMs] and no closer together than
     * [minDistanceM]. Callbacks are delivered on a dedicated [HandlerThread] — never the main thread —
     * since every fix walks the bucketer and may hit Room behind it.
     *
     * The permission check is the `requestLocationUpdates` call itself: it throws `SecurityException`
     * when the permission is absent, which closes the flow empty.
     */
    @SuppressLint("MissingPermission")
    fun fixes(minTimeMs: Long = MIN_TIME_MS, minDistanceM: Float = MIN_DISTANCE_M): Flow<ExerciseFix> =
        callbackFlow {
            if (!isAvailable() || !isPrecise()) {
                close()
                return@callbackFlow
            }
            val thread = HandlerThread("t1dm-loc").apply { start() }
            val listener = LocationListener { location ->
                trySend(
                    ExerciseFix(
                        tsMs = clock(),
                        lat = location.latitude,
                        lon = location.longitude,
                        // An unknown accuracy fails closed: the bucketer refuses anything past its
                        // ceiling, so a receiver that will not say lands nothing on the track.
                        accuracyM = if (location.hasAccuracy()) location.accuracy else Float.MAX_VALUE,
                        speedMps = if (location.hasSpeed()) location.speed else null,
                    ),
                )
            }
            val registered = runCatching {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    minTimeMs,
                    minDistanceM,
                    listener,
                    thread.looper,
                )
            }.isSuccess
            if (!registered) {
                thread.quitSafely()
                close()
                return@callbackFlow
            }
            awaitClose {
                runCatching { locationManager.removeUpdates(listener) }
                thread.quitSafely()
            }
        }

    companion object {
        /** One fix every 4 s: fine enough that a run's corners survive, coarse enough that the
         *  receiver is not asked for a continuous fix. */
        const val MIN_TIME_MS = 4_000L

        /** Under 5 m of movement is a stationary receiver's own scatter, not a step taken. */
        const val MIN_DISTANCE_M = 5f

        /** The interval under battery saver — the track coarsens, it never stops (see
         *  [ExerciseRecorder]). */
        const val LOW_POWER_MIN_TIME_MS = 15_000L
    }
}
