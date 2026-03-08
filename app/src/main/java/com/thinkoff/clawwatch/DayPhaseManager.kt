package com.thinkoff.clawwatch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

class DayPhaseManager(context: Context) {

    data class Snapshot(val phase: Phase, val color: Int, val alpha: Float)
    enum class Phase { NIGHT, MORNING, DAY, EVENING }

    companion object {
        private const val TAG = "DayPhaseManager"
        private const val PREFS = "clawwatch_day_phase"
        private const val KEY_FETCHED_AT = "fetched_at"
        private const val KEY_SUNRISE = "sunrise"
        private const val KEY_SUNSET = "sunset"
        private const val KEY_CIVIL_BEGIN = "civil_begin"
        private const val KEY_CIVIL_END = "civil_end"
        private const val REFRESH_INTERVAL_MS = 12 * 60 * 60 * 1000L

        private const val NIGHT_COLOR = 0xFF6E7CF7.toInt()
        private const val MORNING_COLOR = 0xFFFFC266.toInt()
        private const val DAY_COLOR = 0xFF7CD9FF.toInt()
        private const val EVENING_COLOR = 0xFFFF8FB1.toInt()
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun snapshotNow(now: Instant = Instant.now()): Snapshot {
        val civilBegin = parse(KEY_CIVIL_BEGIN)
        val sunrise = parse(KEY_SUNRISE)
        val sunset = parse(KEY_SUNSET)
        val civilEnd = parse(KEY_CIVIL_END)

        val phase = when {
            civilBegin != null && now.isBefore(civilBegin) -> Phase.NIGHT
            civilEnd != null && now.isAfter(civilEnd) -> Phase.NIGHT
            sunrise != null && now.isBefore(sunrise.plusSeconds(45 * 60)) -> Phase.MORNING
            sunset != null && now.isAfter(sunset.minusSeconds(60 * 60)) -> Phase.EVENING
            sunrise != null && sunset != null && now.isAfter(sunrise) && now.isBefore(sunset) -> Phase.DAY
            else -> fallbackPhase(now)
        }

        return when (phase) {
            Phase.NIGHT -> Snapshot(phase, NIGHT_COLOR, 0.15f)
            Phase.MORNING -> Snapshot(phase, MORNING_COLOR, 0.14f)
            Phase.DAY -> Snapshot(phase, DAY_COLOR, 0.10f)
            Phase.EVENING -> Snapshot(phase, EVENING_COLOR, 0.16f)
        }
    }

    suspend fun refreshIfStale(now: Instant = Instant.now()): Snapshot = withContext(Dispatchers.IO) {
        val fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
        if (fetchedAt <= 0L || now.toEpochMilli() - fetchedAt > REFRESH_INTERVAL_MS || parse(KEY_SUNRISE) == null) {
            try {
                fetchAndCache(now)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh sunrise/sunset cache", e)
            }
        }
        snapshotNow(now)
    }

    private fun fetchAndCache(now: Instant) {
        val geo = URL("https://ipwho.is/").openConnection() as HttpURLConnection
        geo.connectTimeout = 7000
        geo.readTimeout = 7000
        val geoJson = geo.inputStream.bufferedReader().use { it.readText() }
        val geoObj = JSONObject(geoJson)
        if (!geoObj.optBoolean("success", false)) return
        val lat = geoObj.optDouble("latitude", Double.NaN)
        val lon = geoObj.optDouble("longitude", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) return

        val sunUrl = "https://api.sunrise-sunset.org/json?lat=$lat&lng=$lon&formatted=0"
        val sun = URL(sunUrl).openConnection() as HttpURLConnection
        sun.connectTimeout = 7000
        sun.readTimeout = 7000
        val sunJson = sun.inputStream.bufferedReader().use { it.readText() }
        val sunObj = JSONObject(sunJson).getJSONObject("results")

        prefs.edit()
            .putLong(KEY_FETCHED_AT, now.toEpochMilli())
            .putString(KEY_SUNRISE, sunObj.optString("sunrise"))
            .putString(KEY_SUNSET, sunObj.optString("sunset"))
            .putString(KEY_CIVIL_BEGIN, sunObj.optString("civil_twilight_begin"))
            .putString(KEY_CIVIL_END, sunObj.optString("civil_twilight_end"))
            .apply()
    }

    private fun parse(key: String): Instant? =
        prefs.getString(key, null)?.takeIf { it.isNotBlank() }?.let {
            try { Instant.parse(it) } catch (_: Exception) { null }
        }

    private fun fallbackPhase(now: Instant): Phase {
        val hour = java.time.ZonedDateTime.ofInstant(now, java.time.ZoneId.systemDefault()).hour
        return when (hour) {
            in 6..9 -> Phase.MORNING
            in 10..16 -> Phase.DAY
            in 17..20 -> Phase.EVENING
            else -> Phase.NIGHT
        }
    }
}
