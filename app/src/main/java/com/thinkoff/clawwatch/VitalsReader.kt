package com.thinkoff.clawwatch

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.temporal.ChronoUnit
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.math.sqrt

class VitalsReader(private val context: Context) {

    data class Snapshot(
        val heartRateBpm: Int?,
        val ambientLux: Float?,
        val pressureHpa: Float?,
        val stepCount: Int?,
        val motionLevel: MotionLevel,
        val batteryPercent: Int
    )

    enum class MotionLevel { STILL, LIGHT, ACTIVE }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun readSnapshot(
        batteryPercent: Int,
        canReadHeartRate: Boolean,
        canReadSteps: Boolean
    ): Snapshot = withContext(Dispatchers.Default) {
        val heartRate = if (canReadHeartRate) {
            readSingleValue(Sensor.TYPE_HEART_RATE, timeoutMs = 10000L) { it > 20f }
        } else {
            null
        }?.roundToIntSafe()
        val ambientLux = readSingleValue(Sensor.TYPE_LIGHT, timeoutMs = 1200L)
        val pressure = readSingleValue(Sensor.TYPE_PRESSURE, timeoutMs = 1200L)
        val steps = if (canReadSteps) {
            readSingleValue(Sensor.TYPE_STEP_COUNTER, timeoutMs = 1200L)
        } else {
            null
        }
            ?.takeIf { it >= 0f }?.roundToIntSafe()
        val motionLevel = readMotionLevel()

        Snapshot(
            heartRateBpm = heartRate,
            ambientLux = ambientLux,
            pressureHpa = pressure,
            stepCount = steps,
            motionLevel = motionLevel,
            batteryPercent = batteryPercent
        )
    }

    private suspend fun readSingleValue(
        sensorType: Int,
        timeoutMs: Long,
        validator: ((Float) -> Boolean)? = null
    ): Float? =
        suspendCancellableCoroutine { cont ->
            val sensor = sensorManager.getDefaultSensor(sensorType)
            if (sensor == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val value = event.values.firstOrNull()
                    if (value != null && (validator == null || validator(value))) {
                        sensorManager.unregisterListener(this)
                        if (cont.isActive) cont.resume(value)
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

            try {
                // Register with mainHandler so callbacks are delivered on main Looper
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, mainHandler)
            } catch (_: SecurityException) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { sensorManager.unregisterListener(listener) }

            GlobalScope.launch(Dispatchers.Default) {
                delay(timeoutMs)
                sensorManager.unregisterListener(listener)
                if (cont.isActive) cont.resume(null)
            }
        }

    private suspend fun readMotionLevel(): MotionLevel =
        suspendCancellableCoroutine { cont ->
            val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (sensor == null) {
                cont.resume(MotionLevel.STILL)
                return@suspendCancellableCoroutine
            }

            var maxMagnitude = 0f
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val x = event.values.getOrElse(0) { 0f }
                    val y = event.values.getOrElse(1) { 0f }
                    val z = event.values.getOrElse(2) { 0f }
                    val magnitude = sqrt(x * x + y * y + z * z)
                    if (magnitude > maxMagnitude) maxMagnitude = magnitude
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

            try {
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, mainHandler)
            } catch (_: SecurityException) {
                cont.resume(MotionLevel.STILL)
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation { sensorManager.unregisterListener(listener) }

            GlobalScope.launch(Dispatchers.Default) {
                delay(900L)
                sensorManager.unregisterListener(listener)
                val motion = when {
                    maxMagnitude < 0.6f -> MotionLevel.STILL
                    maxMagnitude < 2.2f -> MotionLevel.LIGHT
                    else -> MotionLevel.ACTIVE
                }
                if (cont.isActive) cont.resume(motion)
            }
        }

    private fun Float.roundToIntSafe(): Int = roundToInt()
}
