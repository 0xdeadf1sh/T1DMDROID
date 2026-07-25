package com.t1dm.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The hardware edge of the step pipeline (§3.5 / Phase 1). Registers a
 * `TYPE_STEP_COUNTER` [SensorEventListener], stamps each cumulative reading with phone wall time
 * (passive phone-time stamping, mirroring the CGM path), and folds it through a per-subscription
 * [StepBucketer] into a [Flow] of grid-aligned [StepBucket]s.
 *
 * Requires the `ACTIVITY_RECOGNITION` runtime permission, declared by `:app`.
 */
class StepSource(
    private val sensorManager: SensorManager,
    private val clock: () -> Long = System::currentTimeMillis,
    private val bucketMs: Long = FIVE_MIN_MS,
) {
    /** True when the device exposes a step counter (the K90 does; guards graceful no-op elsewhere). */
    fun isAvailable(): Boolean = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    /**
     * Cold flow of per-5-min step buckets. Each subscription owns a fresh [StepBucketer], so its
     * first sample primes the baseline. Sensor callbacks are delivered on a dedicated background
     * [HandlerThread] — never the main thread — since bucketing plus the downstream Room write must
     * stay off the UI (§2.3). Closes empty on a device with no step counter.
     */
    fun buckets(): Flow<StepBucket> = callbackFlow {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor == null) {
            close()
            return@callbackFlow
        }
        val bucketer = StepBucketer(bucketMs)
        val thread = HandlerThread("t1dm-steps").apply { start() }
        val handler = Handler(thread.looper)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val cumulative = event.values[0].toLong()
                for (bucket in bucketer.onSample(clock(), cumulative)) trySend(bucket)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
        awaitClose {
            sensorManager.unregisterListener(listener)
            thread.quitSafely()
        }
    }

    private companion object {
        const val FIVE_MIN_MS = 300_000L
    }
}
