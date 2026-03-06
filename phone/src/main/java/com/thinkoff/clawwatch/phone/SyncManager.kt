package com.thinkoff.clawwatch.phone

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Syncs ClawWatch configuration from phone to watch via Wearable Data Layer.
 *
 * Data paths:
 *   /clawwatch/config  — model, system prompt, max tokens, rag mode
 *   /clawwatch/apikey  — Anthropic API key (plain, transmitted over encrypted BT/WiFi channel)
 *   /clawwatch/bravekey — Brave Search API key (optional)
 */
class SyncManager(private val context: Context) {

    companion object {
        private const val TAG = "SyncManager"
        const val PATH_CONFIG  = "/clawwatch/config"
        const val PATH_APIKEY  = "/clawwatch/apikey"
        const val PATH_BRAVEKEY = "/clawwatch/bravekey"
    }

    private val dataClient = Wearable.getDataClient(context)

    /** Push all config (except keys) to watch. */
    suspend fun pushConfig(
        model: String,
        systemPrompt: String,
        maxTokens: Int,
        ragMode: String
    ) = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("model", model)
                put("system_prompt", systemPrompt)
                put("max_tokens", maxTokens)
                put("rag_mode", ragMode)
            }.toString()

            val request = PutDataMapRequest.create(PATH_CONFIG).apply {
                dataMap.putString("payload", json)
                dataMap.putLong("updated_at", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()

            dataClient.putDataItem(request).await()
            Log.i(TAG, "Config pushed to watch")
        } catch (e: Exception) {
            Log.e(TAG, "pushConfig failed: ${e.message}")
            throw e
        }
    }

    /** Push Anthropic API key to watch. */
    suspend fun pushApiKey(key: String) = withContext(Dispatchers.IO) {
        try {
            val request = PutDataMapRequest.create(PATH_APIKEY).apply {
                dataMap.putString("key", key)
                dataMap.putLong("updated_at", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()

            dataClient.putDataItem(request).await()
            Log.i(TAG, "API key pushed to watch")
        } catch (e: Exception) {
            Log.e(TAG, "pushApiKey failed: ${e.message}")
            throw e
        }
    }

    /** Push Brave Search API key to watch. */
    suspend fun pushBraveKey(key: String) = withContext(Dispatchers.IO) {
        try {
            val request = PutDataMapRequest.create(PATH_BRAVEKEY).apply {
                dataMap.putString("key", key)
                dataMap.putLong("updated_at", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()

            dataClient.putDataItem(request).await()
            Log.i(TAG, "Brave key pushed to watch")
        } catch (e: Exception) {
            Log.e(TAG, "pushBraveKey failed: ${e.message}")
            throw e
        }
    }

    /** Push everything in one shot to save watch battery (single sync). */
    suspend fun pushAll(
        anthropicKey: String,
        braveKey: String?,
        model: String,
        systemPrompt: String,
        maxTokens: Int,
        ragMode: String
    ) = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("api_key", anthropicKey)
                if (!braveKey.isNullOrBlank()) put("brave_key", braveKey)
                put("model", model)
                put("system_prompt", systemPrompt)
                put("max_tokens", maxTokens)
                put("rag_mode", ragMode)
            }.toString()

            val request = PutDataMapRequest.create("/clawwatch/sync_all").apply {
                dataMap.putString("payload", json)
                dataMap.putLong("updated_at", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()

            dataClient.putDataItem(request).await()
            Log.i(TAG, "All config pushed to watch in single burst")
        } catch (e: Exception) {
            Log.e(TAG, "pushAll failed: ${e.message}")
            throw e
        }
    }
}
