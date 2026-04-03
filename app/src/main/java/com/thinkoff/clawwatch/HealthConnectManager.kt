package com.thinkoff.clawwatch

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

class HealthConnectManager(private val context: Context) {
    
    fun isAvailable(): Boolean {
        val status = HealthConnectClient.getSdkStatus(context)
        return status == HealthConnectClient.SDK_AVAILABLE
    }

    val healthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    val permissions = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class)
    )

    suspend fun hasAllPermissions(): Boolean {
        if (!isAvailable()) return false
        return try {
            healthConnectClient.permissionController.getGrantedPermissions().containsAll(permissions)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun readRecentHealthData(): String = withContext(Dispatchers.IO) {
        if (!isAvailable()) {
            return@withContext "Health Connect SDK not available."
        }
        if (!hasAllPermissions()) {
            return@withContext "Health Connect missing permissions."
        }

        val endTime = Instant.now()
        val startTime = endTime.minus(24, ChronoUnit.HOURS)
        val timeRangeFilter = TimeRangeFilter.between(startTime, endTime)

        try {
            val stepsRequest = ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = timeRangeFilter
            )
            val stepsResponse = healthConnectClient.readRecords(stepsRequest)
            val totalSteps = stepsResponse.records.sumOf { it.count }

            val hrRequest = ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = timeRangeFilter
            )
            val hrResponse = healthConnectClient.readRecords(hrRequest)
            val lastHr = hrResponse.records.lastOrNull()?.samples?.lastOrNull()?.beatsPerMinute

            val sleepRequest = ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = timeRangeFilter
            )
            val sleepResponse = healthConnectClient.readRecords(sleepRequest)
            val lastSleep = sleepResponse.records.lastOrNull()
            val sleepDurationHr = lastSleep?.let {
                java.time.Duration.between(it.startTime, it.endTime).toHours()
            }

            buildString {
                append("Steps last 24h: $totalSteps. ")
                if (lastHr != null) append("Last HR: $lastHr bpm. ")
                if (sleepDurationHr != null) append("Last sleep: $sleepDurationHr hrs.")
            }
        } catch (e: Exception) {
            "Health Connect data error: ${e.message}"
        }
    }
}
