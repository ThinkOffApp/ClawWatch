package com.thinkoff.clawwatch

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class WatchPushRegistrar(private val context: Context) {

    companion object {
        private const val TAG = "WatchPushRegistrar"
        private const val PREF_GROUPMIND_KEY = "groupmind_api_key"
        private const val ENDPOINT_AGENTS_ME = "https://groupmind.one/api/v1/agents/me"
        private const val ENDPOINT_WATCH_DEVICES = "https://groupmind.one/api/v1/watch/devices"
        private const val MIN_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L // 6h
    }

    suspend fun syncRegistration(
        trigger: String,
        force: Boolean = false,
        tokenHint: String? = null
    ): Result<Unit> {
        return runCatching {
            val prefs = SecurePrefs.watch(context)
            val groupMindApiKey = prefs.getString(PREF_GROUPMIND_KEY, null)?.trim().orEmpty()
            if (groupMindApiKey.isBlank()) {
                Log.i(TAG, "Skipping push registration: groupmind_api_key missing")
                return@runCatching
            }

            val fcmToken = tokenHint?.trim().takeUnless { it.isNullOrBlank() } ?: try {
                FirebaseMessaging.getInstance().token.await().trim()
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Firebase Messaging not configured for com.thinkoff.clawwatch. Add app/google-services.json.",
                    e
                )
            }
            if (fcmToken.isBlank()) {
                throw IllegalStateException("FCM token is blank")
            }
            prefs.edit().putString(AlertContract.PREF_LAST_FCM_TOKEN, fcmToken).apply()

            val lastToken = prefs.getString(AlertContract.PREF_LAST_REGISTERED_FCM_TOKEN, null)
            val lastSyncAt = prefs.getLong(AlertContract.PREF_LAST_FCM_SYNC_AT, 0L)
            val now = System.currentTimeMillis()
            val recentlySynced = (now - lastSyncAt) < MIN_SYNC_INTERVAL_MS
            val tokenUnchanged = !lastToken.isNullOrBlank() && lastToken == fcmToken
            if (!force && tokenUnchanged && recentlySynced) {
                Log.i(TAG, "Skipping push registration: recent sync, unchanged token ($trigger)")
                return@runCatching
            }

            val agentHandle = fetchAgentHandle(groupMindApiKey)
            registerDevice(groupMindApiKey, agentHandle, fcmToken)

            prefs.edit()
                .putString(AlertContract.PREF_LAST_REGISTERED_FCM_TOKEN, fcmToken)
                .putLong(AlertContract.PREF_LAST_FCM_SYNC_AT, now)
                .apply()
            Log.i(TAG, "Watch push registration synced. trigger=$trigger handle=@$agentHandle")
        }
    }

    private fun fetchAgentHandle(apiKey: String): String {
        val conn = (URL(ENDPOINT_AGENTS_ME).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        val code = conn.responseCode
        val raw = readResponse(conn)
        if (code != 200) {
            throw IllegalStateException("agents/me failed ($code): ${raw.take(200)}")
        }
        val handle = JSONObject(raw).optString("handle", "")
            .trim()
            .lowercase()
            .removePrefix("@")
        if (handle.isBlank()) {
            throw IllegalStateException("agents/me returned empty handle")
        }
        return handle
    }

    private fun registerDevice(apiKey: String, handle: String, fcmToken: String) {
        val payload = JSONObject().apply {
            put("platform", "wearos")
            put("delivery_mode", "fcm")
            put("fcm_token", fcmToken)
            put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            put("user_handle", handle)
        }.toString()

        val conn = (URL(ENDPOINT_WATCH_DEVICES).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 10_000
            readTimeout = 10_000
            doOutput = true
        }
        OutputStreamWriter(conn.outputStream).use { it.write(payload) }
        val code = conn.responseCode
        val raw = readResponse(conn)
        if (code != 200 && code != 201) {
            throw IllegalStateException("watch/devices failed ($code): ${raw.take(220)}")
        }
    }

    private fun readResponse(conn: HttpURLConnection): String {
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        } finally {
            conn.disconnect()
        }
    }
}
