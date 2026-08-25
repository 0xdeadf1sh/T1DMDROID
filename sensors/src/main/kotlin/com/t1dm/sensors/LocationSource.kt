package com.t1dm.sensors

import android.annotation.SuppressLint
import android.location.LocationListener
import android.location.LocationManager
import android.os.HandlerThread
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** `GPS_PROVIDER` alone: the fused provider needs Play Services, which this app does not carry, and
 *  the network provider is tower trilateration. `ACCESS_FINE_LOCATION` specifically — a coarse grant
 *  still yields fixes, fuzzed to a ~2 km circle the bucketer refuses at its 50 m ceiling. */
class LocationSource(
    private val locationManager: LocationManager,
    private val precise: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun isAvailable(): Boolean =
        runCatching { LocationManager.GPS_PROVIDER in locationManager.allProviders }.getOrDefault(false)

    /** Asked live, so a grant changed under a running bout is read as it stands. */
    fun isPrecise(): Boolean = runCatching { precise() }.getOrDefault(false)

    /** Off is a live, reversible state, not a missing capability — hence separate from
     *  [isAvailable]. */
    fun isEnabled(): Boolean =
        runCatching { locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)

    /** Callbacks arrive on a dedicated [HandlerThread], never the main one: every fix walks the
     *  bucketer and may hit Room behind it. An absent permission throws from `requestLocationUpdates`,
     *  which closes the flow empty. */
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
                        // Unknown accuracy fails closed: past the bucketer's ceiling.
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
        /** Fine enough that a run's corners survive, short of a continuous fix. */
        const val MIN_TIME_MS = 4_000L

        /** Under 5 m of movement is a stationary receiver's own scatter, not a step taken. */
        const val MIN_DISTANCE_M = 5f

        /** Battery saver: the track coarsens, it never stops. */
        const val LOW_POWER_MIN_TIME_MS = 15_000L
    }
}
