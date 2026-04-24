package com.thinkoff.clawwatch

import android.content.Context
import org.json.JSONObject

class PhoneVitalsStore(context: Context) {
    data class Snapshot(
        val source: String,
        val restingHeartRateBpm: Int?,
        val hrvRmssdMs: Double?,
        val sleepSummary: String?,
        val readinessSummary: String?,
        val updatedAtEpochMs: Long
    ) {
        fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
            updatedAtEpochMs > 0 && nowMs - updatedAtEpochMs < 12L * 60L * 60L * 1000L
    }

    private val prefs = SecurePrefs.watch(context)

    fun saveFromJson(payload: String) {
        val json = JSONObject(payload)
        prefs.edit()
            .putString(KEY_SOURCE, json.optString("source", "phone"))
            .putInt(KEY_RESTING_HR, json.optIntOrNull("resting_heart_rate_bpm") ?: -1)
            .putString(KEY_HRV, json.optDoubleOrNull("hrv_rmssd_ms")?.toString())
            .putString(KEY_SLEEP, json.optString("sleep_summary").ifBlank { null })
            .putString(KEY_READINESS, json.optString("readiness_summary").ifBlank { null })
            .putLong(KEY_UPDATED_AT, json.optLong("updated_at_epoch_ms", System.currentTimeMillis()))
            .apply()
    }

    fun latest(): Snapshot? {
        val updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
        if (updatedAt <= 0L) return null
        val restingHr = prefs.getInt(KEY_RESTING_HR, -1).takeIf { it > 0 }
        return Snapshot(
            source = prefs.getString(KEY_SOURCE, "phone") ?: "phone",
            restingHeartRateBpm = restingHr,
            hrvRmssdMs = prefs.getString(KEY_HRV, null)?.toDoubleOrNull(),
            sleepSummary = prefs.getString(KEY_SLEEP, null),
            readinessSummary = prefs.getString(KEY_READINESS, null),
            updatedAtEpochMs = updatedAt
        )
    }

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (has(name) && !isNull(name)) optDouble(name) else null

    companion object {
        private const val KEY_SOURCE = "phone_vitals_source"
        private const val KEY_RESTING_HR = "phone_resting_heart_rate_bpm"
        private const val KEY_HRV = "phone_hrv_rmssd_ms"
        private const val KEY_SLEEP = "phone_sleep_summary"
        private const val KEY_READINESS = "phone_readiness_summary"
        private const val KEY_UPDATED_AT = "phone_vitals_updated_at_epoch_ms"
    }
}
