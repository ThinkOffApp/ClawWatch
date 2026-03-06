package com.thinkoff.clawwatch

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

/**
 * Receives config from the companion phone app via Wearable Data Layer.
 * Wakes automatically when data arrives, even if the watch app is closed.
 *
 * Data paths:
 *   /clawwatch/config   — model, system_prompt, max_tokens, rag_mode
 *   /clawwatch/apikey   — Anthropic API key
 *   /clawwatch/bravekey — Brave Search API key
 */
class ConfigSyncService : WearableListenerService() {

    companion object {
        private const val TAG = "ConfigSyncService"
        const val PATH_CONFIG   = "/clawwatch/config"
        const val PATH_APIKEY   = "/clawwatch/apikey"
        const val PATH_BRAVEKEY = "/clawwatch/bravekey"
    }

    override fun onDataChanged(events: DataEventBuffer) {
        val runner = ClawRunner(applicationContext)

        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            val map  = DataMapItem.fromDataItem(event.dataItem).dataMap
            when (path) {
                "/clawwatch/sync_all" -> {
                    val payload = map.getString("payload") ?: continue
                    try {
                        val json = JSONObject(payload)
                        json.optString("api_key").takeIf { it.isNotBlank() }
                            ?.let { runner.saveApiKey(it) }
                        json.optString("brave_key").takeIf { it.isNotBlank() }
                            ?.let { runner.saveBraveKey(it) }
                        json.optString("model").takeIf { it.isNotBlank() }
                            ?.let { runner.saveModel(it) }
                        json.optString("system_prompt").takeIf { it.isNotBlank() }
                            ?.let { runner.saveSystemPrompt(it) }
                        if (json.has("max_tokens"))
                            runner.saveMaxTokens(json.getInt("max_tokens"))
                        json.optString("rag_mode").takeIf { it.isNotBlank() }
                            ?.let { runner.saveRagMode(it) }
                        Log.i(TAG, "All config synced from phone (single burst)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Config sync burst error: ${e.message}")
                    }
                }
            }
        }
    }
}
