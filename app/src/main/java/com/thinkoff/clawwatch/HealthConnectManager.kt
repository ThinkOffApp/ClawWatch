package com.thinkoff.clawwatch

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class HealthConnectManager(context: Context) {

    companion object {
        private const val TAG = "HealthConnect"
    }

    // Use applicationContext to avoid lifecycle issues on Wear OS
    private val appContext = context.applicationContext

    fun isAvailable(): Boolean {
        // On API 34+, Health Connect is a platform module — getSdkStatus can be unreliable.
        // Just check if the system has it.
        if (Build.VERSION.SDK_INT >= 34) return true
        return try {
            val status = HealthConnectClient.getSdkStatus(appContext)
            status == HealthConnectClient.SDK_AVAILABLE
        } catch (e: Exception) {
            Log.w(TAG, "SDK status check failed: ${e.message}")
            false
        }
    }

    val healthConnectClient: HealthConnectClient? by lazy {
        // Try platform provider first (API 34+), then Google's package
        val providers = listOf(
            "com.android.healthconnect.controller",
            "com.google.android.apps.healthdata"
        )
        for (provider in providers) {
            try {
                val client = HealthConnectClient.getOrCreate(appContext, provider)
                Log.i(TAG, "HealthConnectClient created with provider: $provider")
                return@lazy client
            } catch (e: Exception) {
                Log.w(TAG, "HC provider $provider failed: ${e.message}")
            }
        }
        // Last resort: default
        try {
            HealthConnectClient.getOrCreate(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "HealthConnectClient creation failed entirely: ${e.message}")
            null
        }
    }

    val permissions = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    )

    private val writePermission: String =
        HealthPermission.getWritePermission(HeartRateRecord::class)

    suspend fun canWriteHeartRate(): Boolean {
        return getGrantedPermissions().contains(writePermission)
    }

    /**
     * Write a single live pulse reading to Health Connect so other apps
     * (phone-side companion, Oura dashboards, etc.) can see it.
     * Silent no-op if the write permission is not granted.
     */
    suspend fun writeLiveHeartRate(bpm: Int, time: Instant = Instant.now()) = withContext(Dispatchers.IO) {
        if (bpm <= 20 || bpm >= 250) return@withContext
        if (!isAvailable()) return@withContext
        val client = healthConnectClient ?: return@withContext
        if (!canWriteHeartRate()) return@withContext
        try {
            val record = HeartRateRecord(
                startTime = time,
                startZoneOffset = null,
                endTime = time.plusMillis(1),
                endZoneOffset = null,
                samples = listOf(
                    HeartRateRecord.Sample(time = time, beatsPerMinute = bpm.toLong())
                )
            )
            client.insertRecords(listOf(record))
            Log.i(TAG, "Wrote live HR $bpm bpm to Health Connect")
        } catch (e: Exception) {
            Log.w(TAG, "HR write failed: ${e.message}")
        }
    }

    // HRV permission may not exist on all firmware — request separately, don't require
    private val optionalPermissions: Set<String> by lazy {
        try {
            setOf(HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class))
        } catch (e: Exception) {
            emptySet()
        }
    }

    val allRequestablePermissions: Set<String>
        get() = permissions + optionalPermissions + setOf(writePermission)

    private suspend fun getGrantedPermissions(): Set<String> {
        if (!isAvailable()) return emptySet()
        return try {
            healthConnectClient?.permissionController?.getGrantedPermissions() ?: emptySet()
        } catch (e: Exception) {
            Log.w(TAG, "Permission check failed: ${e.message}")
            emptySet()
        }
    }

    suspend fun hasAllPermissions(): Boolean {
        return getGrantedPermissions().containsAll(permissions)
    }

    suspend fun hasAnyPermission(): Boolean {
        return getGrantedPermissions().any { it in permissions }
    }

    /**
     * Main health summary — injected into LLM system prompt on every query.
     * Reads whatever data we have permission for.
     */
    suspend fun readRecentHealthData(): String = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext "Health Connect not available on this watch."
        if (!hasAnyPermission()) return@withContext "Health Connect permissions not granted."

        val now = Instant.now()
        val last24h = now.minus(24, ChronoUnit.HOURS)
        val timeRange = TimeRangeFilter.between(last24h, now)

        val parts = mutableListOf<String>()

        try {
            // Steps
            val steps = readTotalSteps(timeRange)
            if (steps != null) parts += "${formatNumber(steps)} steps today"

            // Heart rate
            val hr = readLatestHeartRate(timeRange)
            if (hr != null) parts += "last heart rate ${hr} bpm"

            // HRV
            val hrv = readLatestHrv(timeRange)
            if (hrv != null) parts += "HRV ${hrv} ms"

            // Sleep (look back further for overnight)
            val sleepBrief = readLastSleepBrief()
            if (sleepBrief != null) parts.add(sleepBrief)

            // Exercise
            val exerciseSummary = readRecentExercise(timeRange)
            if (exerciseSummary != null) parts += exerciseSummary

        } catch (e: Exception) {
            Log.w(TAG, "Failed reading health data: ${e.message}")
            return@withContext "Health data temporarily unavailable."
        }

        if (parts.isEmpty()) return@withContext "No health data recorded recently."
        parts.joinToString(". ") + "."
    }

    /**
     * Dedicated sleep summary for voice command — richer detail.
     */
    suspend fun readSleepSummary(): String = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext "Health Connect not available."
        if (!hasAnyPermission()) return@withContext "Health Connect permissions not granted."

        try {
            val now = Instant.now()
            val lookback = now.minus(36, ChronoUnit.HOURS)
            val timeRange = TimeRangeFilter.between(lookback, now)

            val sleepRequest = ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = timeRange
            )
            val sleepResponse = healthConnectClient!!.readRecords(sleepRequest)
            val lastSleep = sleepResponse.records.lastOrNull()
                ?: return@withContext "No sleep data recorded in the last 36 hours."

            val duration = Duration.between(lastSleep.startTime, lastSleep.endTime)
            val hours = duration.toHours()
            val minutes = duration.toMinutes() % 60

            val bedTime = lastSleep.startTime.atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("h:mm a"))
            val wakeTime = lastSleep.endTime.atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("h:mm a"))

            val durationText = if (minutes > 0) "$hours hours and $minutes minutes" else "$hours hours"

            val stages = lastSleep.stages
            val stageBreakdown = if (stages.isNotEmpty()) {
                val deepMin = stages.filter { it.stage == SleepSessionRecord.STAGE_TYPE_DEEP }
                    .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
                val remMin = stages.filter { it.stage == SleepSessionRecord.STAGE_TYPE_REM }
                    .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
                val lightMin = stages.filter { it.stage == SleepSessionRecord.STAGE_TYPE_LIGHT }
                    .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
                val awakeMin = stages.filter { it.stage == SleepSessionRecord.STAGE_TYPE_AWAKE }
                    .sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }

                buildString {
                    if (deepMin > 0) append(" Deep sleep: $deepMin minutes.")
                    if (remMin > 0) append(" REM: $remMin minutes.")
                    if (lightMin > 0) append(" Light sleep: $lightMin minutes.")
                    if (awakeMin > 0) append(" Awake: $awakeMin minutes.")
                }
            } else ""

            "You slept $durationText, from $bedTime to $wakeTime.$stageBreakdown"
        } catch (e: Exception) {
            Log.w(TAG, "Sleep summary failed: ${e.message}")
            "I couldn't read your sleep data right now."
        }
    }

    /**
     * Dedicated health snapshot for voice command — detailed spoken summary.
     */
    suspend fun readHealthSnapshot(): String = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext "Health Connect is not available on this watch."
        if (!hasAnyPermission()) return@withContext "I need Health Connect permissions first."

        val now = Instant.now()
        val last24h = now.minus(24, ChronoUnit.HOURS)
        val timeRange = TimeRangeFilter.between(last24h, now)
        val parts = mutableListOf<String>()

        try {
            // Steps
            val steps = readTotalSteps(timeRange)
            if (steps != null) {
                parts += "You have ${formatNumber(steps)} steps today"
            }

            // Heart rate
            val hr = readLatestHeartRate(timeRange)
            if (hr != null) {
                val hrDesc = when {
                    hr < 60 -> "$hr bpm, which is on the low side"
                    hr in 60..100 -> "$hr bpm, in normal range"
                    else -> "$hr bpm, which is elevated"
                }
                parts += "Your last heart rate was $hrDesc"
            }

            // HRV
            val hrv = readLatestHrv(timeRange)
            if (hrv != null) {
                val hrvDesc = when {
                    hrv < 20 -> "$hrv milliseconds, which is low — take it easy"
                    hrv in 20..50 -> "$hrv milliseconds, moderate recovery"
                    else -> "$hrv milliseconds, good recovery"
                }
                parts += "Heart rate variability is $hrvDesc"
            }

            // Sleep
            val sleepBrief = readLastSleepBrief()
            if (sleepBrief != null) parts += sleepBrief

            // Exercise
            val exerciseSummary = readRecentExercise(timeRange)
            if (exerciseSummary != null) parts += exerciseSummary

        } catch (e: Exception) {
            Log.w(TAG, "Health snapshot failed: ${e.message}")
            return@withContext "I had trouble reading your health data right now."
        }

        if (parts.isEmpty()) return@withContext "No health data found. Make sure your watch sensors are active."
        parts.joinToString(". ") + "."
    }

    // ── Internal readers ─────────────────────────────────────────────────────

    private suspend fun readTotalSteps(timeRange: TimeRangeFilter): Long? {
        return try {
            val request = ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = timeRange
            )
            val response = healthConnectClient!!.readRecords(request)
            val total = response.records.sumOf { it.count }
            if (total > 0) total else null
        } catch (e: Exception) {
            Log.w(TAG, "Steps read failed: ${e.message}")
            null
        }
    }

    private suspend fun readLatestHeartRate(timeRange: TimeRangeFilter): Long? {
        return try {
            val request = ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = timeRange
            )
            val response = healthConnectClient!!.readRecords(request)
            response.records.lastOrNull()?.samples?.lastOrNull()?.beatsPerMinute
        } catch (e: Exception) {
            Log.w(TAG, "HR read failed: ${e.message}")
            null
        }
    }

    private suspend fun readLatestHrv(timeRange: TimeRangeFilter): Int? {
        return try {
            val request = ReadRecordsRequest(
                recordType = HeartRateVariabilityRmssdRecord::class,
                timeRangeFilter = timeRange
            )
            val response = healthConnectClient!!.readRecords(request)
            response.records.lastOrNull()?.heartRateVariabilityMillis?.toInt()
        } catch (e: Exception) {
            Log.w(TAG, "HRV read failed: ${e.message}")
            null
        }
    }

    private suspend fun readLastSleepBrief(): String? {
        return try {
            val now = Instant.now()
            val lookback = now.minus(36, ChronoUnit.HOURS)
            val timeRange = TimeRangeFilter.between(lookback, now)
            val request = ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = timeRange
            )
            val response = healthConnectClient!!.readRecords(request)
            val lastSleep = response.records.lastOrNull() ?: return null
            val duration = Duration.between(lastSleep.startTime, lastSleep.endTime)
            val hours = duration.toHours()
            val minutes = duration.toMinutes() % 60
            if (minutes > 0) "Last sleep was $hours hours $minutes minutes"
            else "Last sleep was $hours hours"
        } catch (e: Exception) {
            Log.w(TAG, "Sleep brief failed: ${e.message}")
            null
        }
    }

    private suspend fun readRecentExercise(timeRange: TimeRangeFilter): String? {
        return try {
            val request = ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = timeRange
            )
            val response = healthConnectClient!!.readRecords(request)
            val sessions = response.records
            if (sessions.isEmpty()) return null

            val count = sessions.size
            val totalMin = sessions.sumOf {
                Duration.between(it.startTime, it.endTime).toMinutes()
            }
            if (count == 1) {
                val name = exerciseTypeName(sessions[0].exerciseType)
                "$name session, $totalMin minutes"
            } else {
                "$count exercise sessions, $totalMin minutes total"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exercise read failed: ${e.message}")
            null
        }
    }

    private fun exerciseTypeName(type: Int): String = when (type) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Running"
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Walking"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Cycling"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "Swimming"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Hiking"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "Yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "Strength training"
        else -> "Exercise"
    }

    private fun formatNumber(n: Long): String {
        return if (n >= 1000) {
            val thousands = n / 1000
            val remainder = (n % 1000) / 100
            if (remainder > 0) "$thousands,${remainder}00" else "${thousands},000"
        } else {
            n.toString()
        }
    }
}
