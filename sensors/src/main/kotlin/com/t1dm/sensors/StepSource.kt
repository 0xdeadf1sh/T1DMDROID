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

/** Stamped with phone wall time, like CGM; needs `ACTIVITY_RECOGNITION` perm from `:app`. */
class StepSource(
    private val sensorManager: SensorManager,
    private val clock: () -> Long = System::currentTimeMillis,
    private val bucketMs: Long = FIVE_MIN_MS,
) {
    fun isAvailable(): Boolean = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) != null

    /** Fresh [StepBucketer] per subscription; callbacks off-main on [HandlerThread], not UI. */
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
