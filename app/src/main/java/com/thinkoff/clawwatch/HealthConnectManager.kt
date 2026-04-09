package com.thinkoff.clawwatch

import android.content.Context
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

class HealthConnectManager(private val context: Context) {

    companion object {
        private const val TAG = "HealthConnect"
    }

    fun isAvailable(): Boolean {
        return try {
            val status = HealthConnectClient.getSdkStatus(context)
            status == HealthConnectClient.SDK_AVAILABLE
        } catch (e: Exception) {
            Log.w(TAG, "SDK status check failed: ${e.message}")
            false
        }
    }

    val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    val permissions = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)
    )

    suspend fun hasAllPermissions(): Boolean {
        if (!isAvailable()) return false
        return try {
            healthConnectClient.permissionController.getGrantedPermissions().containsAll(permissions)
        } catch (e: Exception) {
            Log.w(TAG, "Permission check failed: ${e.message}")
            false
        }
    }

    /**
     * Main health summary — injected into LLM system prompt on every query.
     * Returns a concise, spoken-friendly string.
     */
    suspend fun readRecentHealthData(): String = withContext(Dispatchers.IO) {
        if (!isAvailable()) return@withContext "Health Connect not available on this watch."
        if (!hasAllPermissions()) return@withContext "Health Connect permissions not granted."

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
        if (!hasAllPermissions()) return@withContext "Health Connect permissions not granted."

        try {
            val now = Instant.now()
            val lookback = now.minus(36, ChronoUnit.HOURS)
            val timeRange = TimeRangeFilter.between(lookback, now)

            val sleepRequest = ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = timeRange
            )
            val sleepResponse = healthConnectClient.readRecords(sleepRequest)
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
        if (!hasAllPermissions()) return@withContext "I need Health Connect permissions first."

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
            val response = healthConnectClient.readRecords(request)
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
            val response = healthConnectClient.readRecords(request)
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
            val response = healthConnectClient.readRecords(request)
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
            val response = healthConnectClient.readRecords(request)
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
            val response = healthConnectClient.readRecords(request)
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
