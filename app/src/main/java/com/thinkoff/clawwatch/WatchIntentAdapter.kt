package com.thinkoff.clawwatch

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Watch-side adapter for user-intent-kit style device updates.
 * Publishes watch state to /intent/{userId}/{deviceId} on state changes and heartbeat.
 */
class WatchIntentAdapter(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val scope: LifecycleCoroutineScope
) {
    companion object {
        private const val TAG = "WatchIntentAdapter"
        private const val DEFAULT_BASE_URL = "https://groupmind.one/api/v1"
        private const val PREF_GROUPMIND_API_KEY = "groupmind_api_key"
        private const val PREF_INTENT_BASE_URL = "intent_base_url"
        private const val PREF_INTENT_USER_ID = "intent_user_id"
        private const val PREF_INTENT_DEVICE_ID = "intent_device_id"
        private const val BATTERY_DELTA_THRESHOLD = 5
    }

    @Volatile private var cachedUserId: String? = null
    @Volatile private var cachedDeviceId: String? = null
    @Volatile private var currentState: String = "idle"
    @Volatile private var currentScreenActive: Boolean = false
    @Volatile private var currentBatteryPct: Int = 100
    @Volatile private var currentLowBattery: Boolean = false
    @Volatile private var lastPublishedSnapshot: Snapshot? = null

    private data class Snapshot(
        val state: String,
        val screenActive: Boolean,
        val batteryPct: Int,
        val lowBattery: Boolean
    )

    fun start(
        initialState: String,
        screenActive: Boolean,
        batteryPct: Int,
        lowBattery: Boolean
    ) {
        currentState = initialState
        currentScreenActive = screenActive
        currentBatteryPct = batteryPct
        currentLowBattery = lowBattery
        if (!isConfigured()) return

        scope.launch(Dispatchers.IO) {
            resolveIdentityIfNeeded()
            publishState(force = true)
        }
    }

    fun stop() = Unit

    fun onStateChanged(
        state: String,
        screenActive: Boolean,
        batteryPct: Int,
        lowBattery: Boolean
    ) {
        currentState = state
        currentScreenActive = screenActive
        currentBatteryPct = batteryPct
        currentLowBattery = lowBattery
        if (!isConfigured()) return

        scope.launch(Dispatchers.IO) {
            resolveIdentityIfNeeded()
            publishState(force = false)
        }
    }

    private fun isConfigured(): Boolean = apiKey().isNotBlank()
    private fun prefString(key: String): String? {
        val secure = prefs.getString(key, null)?.trim()
        if (!secure.isNullOrBlank()) return secure
        val legacy = context.getSharedPreferences("clawwatch_prefs", Context.MODE_PRIVATE)
            .getString(key, null)
            ?.trim()
        return if (legacy.isNullOrBlank()) null else legacy
    }

    private fun baseUrl(): String =
        (prefString(PREF_INTENT_BASE_URL) ?: DEFAULT_BASE_URL)
            .trim()
            .trimEnd('/')

    private fun apiKey(): String = prefString(PREF_GROUPMIND_API_KEY).orEmpty()

    private fun resolveIdentityIfNeeded() {
        if (cachedDeviceId.isNullOrBlank()) {
            val fromPrefs = prefString(PREF_INTENT_DEVICE_ID)
            cachedDeviceId = if (!fromPrefs.isNullOrBlank()) fromPrefs else buildDefaultDeviceId()
            if (fromPrefs.isNullOrBlank() && !cachedDeviceId.isNullOrBlank()) {
                prefs.edit().putString(PREF_INTENT_DEVICE_ID, cachedDeviceId).apply()
            }
        }

        if (cachedUserId.isNullOrBlank()) {
            val fromPrefs = prefString(PREF_INTENT_USER_ID)
            cachedUserId = fromPrefs
            if (cachedUserId.isNullOrBlank()) {
                val fetched = fetchUserIdFromApi()
                if (!fetched.isNullOrBlank()) {
                    cachedUserId = fetched
                    prefs.edit().putString(PREF_INTENT_USER_ID, fetched).apply()
                }
            }
        }
    }

    private fun buildDefaultDeviceId(): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) {
            null
        }
        val suffix = androidId?.takeLast(8)?.lowercase(Locale.US) ?: "watch"
        return "clawwatch-$suffix"
    }

    private fun fetchUserIdFromApi(): String? {
        val key = apiKey()
        if (key.isBlank()) return null

        return try {
            val json = request(
                method = "GET",
                path = "/users/me",
                body = null
            ) ?: return null

            extractUserId(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve /users/me for intent adapter: ${e.message}")
            null
        }
    }

    private fun extractUserId(json: JSONObject): String? {
        val direct = listOf("user_id", "id", "username", "handle", "slug")
        for (key in direct) {
            val value = json.optString(key, "").trim()
            if (value.isNotBlank()) return value.trimStart('@')
        }

        val nestedUser = json.optJSONObject("user")
        if (nestedUser != null) {
            for (key in direct) {
                val value = nestedUser.optString(key, "").trim()
                if (value.isNotBlank()) return value.trimStart('@')
            }
        }
        return null
    }

    private fun shouldPublish(next: Snapshot): Boolean {
        val prev = lastPublishedSnapshot ?: return true
        if (prev.state != next.state) return true
        if (prev.screenActive != next.screenActive) return true
        if (prev.lowBattery != next.lowBattery) return true
        if (kotlin.math.abs(prev.batteryPct - next.batteryPct) >= BATTERY_DELTA_THRESHOLD) return true
        return false
    }

    private fun publishState(force: Boolean) {
        val userId = cachedUserId ?: return
        val deviceId = cachedDeviceId ?: return
        val snapshot = Snapshot(
            state = currentState,
            screenActive = currentScreenActive,
            batteryPct = currentBatteryPct,
            lowBattery = currentLowBattery
        )
        if (!force && !shouldPublish(snapshot)) return

        val fields = JSONObject().apply {
            put("context", snapshot.state)
            put("screen_active", snapshot.screenActive)
            put("wrist_raise", false)
            put("device_type", "watch")
            put("active_app", "clawwatch")
            put("battery_pct", snapshot.batteryPct)
            put("low_battery", snapshot.lowBattery)
        }

        try {
            request(
                method = "PATCH",
                path = "/intent/$userId/$deviceId",
                body = fields
            )
            lastPublishedSnapshot = snapshot
        } catch (e: Exception) {
            Log.w(TAG, "Intent publish failed: ${e.message}")
        }
    }

    private fun request(method: String, path: String, body: JSONObject?): JSONObject? {
        val url = URL("${baseUrl()}$path")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("X-API-Key", apiKey())
            setRequestProperty("Content-Type", "application/json")
            doInput = true
            if (body != null && method != "GET" && method != "DELETE") {
                doOutput = true
            }
        }

        if (body != null && method != "GET" && method != "DELETE") {
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
        }

        val code = conn.responseCode
        val raw = try {
            if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
        } catch (_: Exception) {
            ""
        } finally {
            conn.disconnect()
        }

        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code $method $path ${raw.take(200)}")
        }

        if (raw.isBlank()) return null
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }
}
