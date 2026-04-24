package com.thinkoff.clawwatch

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.temporal.ChronoUnit
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
        val batteryPercent: Int,
        val phoneVitals: PhoneVitalsStore.Snapshot?
    )

    enum class MotionLevel { STILL, LIGHT, ACTIVE }

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    suspend fun readSnapshot(
        batteryPercent: Int,
        canReadHeartRate: Boolean,
        canReadSteps: Boolean
    ): Snapshot = withContext(Dispatchers.Default) {
        var heartRate: Int? = null
        if (canReadHeartRate) {
            try {
                val sdkStatus = HealthConnectClient.getSdkStatus(context)
                if (sdkStatus == HealthConnectClient.SDK_AVAILABLE) {
                    val client = HealthConnectClient.getOrCreate(context)
                    val response = client.readRecords(
                        ReadRecordsRequest(
                            HeartRateRecord::class,
                            timeRangeFilter = TimeRangeFilter.between(
                                Instant.now().minus(2, ChronoUnit.HOURS),
                                Instant.now()
                            )
                        )
                    )
                    heartRate = response.records.lastOrNull()?.samples?.lastOrNull()?.beatsPerMinute?.toInt()
                } else {
                    android.util.Log.w("VitalsReader", "Health Connect SDK not available (status=$sdkStatus). Install or enable Health Connect on this watch.")
                }
            } catch (e: Exception) {
                android.util.Log.w("VitalsReader", "Health Connect read failed: ${e.message}")
            }
        }
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
            batteryPercent = batteryPercent,
            phoneVitals = PhoneVitalsStore(context).latest()
        )
    }

    suspend fun measurePulseAndWriteToHealthConnect(): Int? = withContext(Dispatchers.Default) {
        val bpm = readHeartRateSensor(timeoutMs = 10_000L) ?: return@withContext null
        try {
            val sdkStatus = HealthConnectClient.getSdkStatus(context)
            if (sdkStatus == HealthConnectClient.SDK_AVAILABLE) {
                val now = Instant.now()
                val record = HeartRateRecord(
                    startTime = now.minusSeconds(1),
                    startZoneOffset = null,
                    endTime = now,
                    endZoneOffset = null,
                    samples = listOf(HeartRateRecord.Sample(now, bpm.toLong())),
                    metadata = Metadata(recordingMethod = Metadata.RECORDING_METHOD_ACTIVELY_RECORDED)
                )
                HealthConnectClient.getOrCreate(context).insertRecords(listOf(record))
            } else {
                android.util.Log.w("VitalsReader", "Health Connect unavailable, pulse measured but not written (status=$sdkStatus).")
            }
        } catch (e: Exception) {
            android.util.Log.w("VitalsReader", "Health Connect pulse write failed: ${e.message}")
        }
        bpm
    }

    private suspend fun readSingleValue(sensorType: Int, timeoutMs: Long): Float? =
        suspendCancellableCoroutine { cont ->
            val sensor = sensorManager.getDefaultSensor(sensorType)
            if (sensor == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    sensorManager.unregisterListener(this)
                    cont.resume(event.values.firstOrNull())
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

            try {
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
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

    private suspend fun readHeartRateSensor(timeoutMs: Long): Int? =
        suspendCancellableCoroutine { cont ->
            val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            if (sensor == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    val bpm = event.values.firstOrNull()
                        ?.takeIf { it in 30f..230f }
                        ?.roundToIntSafe()
                    if (bpm != null) {
                        sensorManager.unregisterListener(this)
                        cont.resume(bpm)
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

            try {
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
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
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
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
