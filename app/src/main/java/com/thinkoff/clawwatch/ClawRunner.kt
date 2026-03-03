package com.thinkoff.clawwatch

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * ClawRunner — manages NullClaw binary + Anthropic API calls with optional RAG.
 *
 * RAG modes:
 *  - KOTLIN: pre-search with DuckDuckGo (no key) or Brave Search (optional key),
 *            inject top results into system context before Anthropic call.
 *  - OPUS_TOOL: use Anthropic tool_use to let Claude call a web_search tool,
 *               execute the search on the Kotlin side, return results, get final answer.
 *
 * Config is read from SharedPreferences (set via admin panel / set_key.sh).
 */
class ClawRunner(private val context: Context) {

    companion object {
        private const val TAG = "ClawRunner"
        private const val CONFIG_NAME = "nullclaw.json"
        private const val PREFS_NAME = "clawwatch_prefs"
        private const val PREF_API_KEY = "anthropic_api_key"
        private const val PREF_MODEL = "model"
        private const val PREF_SYSTEM_PROMPT = "system_prompt"
        private const val PREF_MAX_TOKENS = "max_tokens"
        private const val PREF_RAG_MODE = "rag_mode"         // "off" | "kotlin" | "opus_tool"
        private const val PREF_BRAVE_KEY = "brave_api_key"

        // Keywords that suggest the query needs current/live information
        private val LIVE_INFO_KEYWORDS = setOf(
            "today", "tonight", "tomorrow", "yesterday", "now", "current", "currently",
            "latest", "recent", "news", "weather", "temperature", "price", "stock",
            "score", "result", "standings", "match", "game", "live", "happening",
            "what time", "how long", "when does", "is it open", "open now",
            "who won", "did they", "is there"
        )

        private const val DEFAULT_MODEL = "claude-opus-4-6"
        private const val DEFAULT_MAX_TOKENS = 150
        private const val DEFAULT_SYSTEM_PROMPT =
            "You are a voice assistant on a Samsung smartwatch. " +
            "Rules: respond in 1-3 short sentences maximum. No markdown, no lists, no bullet points. " +
            "Plain spoken language only. Be direct and precise. Never say 'Certainly!' or 'Great question!'"
    }

    private val filesDir get() = context.filesDir
    private val homeDir get() = context.filesDir.parentFile!!
    private val nativeLibDir get() = context.applicationInfo.nativeLibraryDir
    private val binaryFile get() = File(nativeLibDir, "libnullclaw.so")
    private val configFile get() = File(filesDir, CONFIG_NAME)
    private val nullclawConfigFile get() = File(homeDir, ".nullclaw/config.json")
    private val caBundleFile get() = File(filesDir, "ca-certificates.crt")

    private val prefs get() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Config accessors ─────────────────────────────────────────────────────

    fun saveApiKey(key: String) = prefs.edit().putString(PREF_API_KEY, key).apply()
    fun saveBraveKey(key: String) = prefs.edit().putString(PREF_BRAVE_KEY, key).apply()
    fun saveModel(model: String) = prefs.edit().putString(PREF_MODEL, model).apply()
    fun saveSystemPrompt(prompt: String) = prefs.edit().putString(PREF_SYSTEM_PROMPT, prompt).apply()
    fun saveMaxTokens(n: Int) = prefs.edit().putInt(PREF_MAX_TOKENS, n).apply()
    fun saveRagMode(mode: String) = prefs.edit().putString(PREF_RAG_MODE, mode).apply()

    fun hasApiKey(): Boolean = prefs.getString(PREF_API_KEY, null)?.isNotBlank() == true
    private fun getApiKey(): String? = prefs.getString(PREF_API_KEY, null)
    private fun getBraveKey(): String? = prefs.getString(PREF_BRAVE_KEY, null)
    private fun getModel(): String = prefs.getString(PREF_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    private fun getSystemPrompt(): String = prefs.getString(PREF_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
    private fun getMaxTokens(): Int = prefs.getInt(PREF_MAX_TOKENS, DEFAULT_MAX_TOKENS)
    private fun getRagMode(): String = prefs.getString(PREF_RAG_MODE, "kotlin") ?: "kotlin"

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    suspend fun ensureInstalled() = withContext(Dispatchers.IO) {
        Log.i(TAG, "NullClaw binary: ${binaryFile.absolutePath} (exists: ${binaryFile.exists()})")
        if (!configFile.exists()) {
            context.assets.open(CONFIG_NAME).use { it.copyTo(configFile.outputStream()) }
        }
        writeNullclawHomeConfig()
        buildCaBundle()
        Log.i(TAG, "ClawRunner ready, home=${homeDir.absolutePath}, rag=${getRagMode()}")
    }

    private fun buildCaBundle() {
        if (caBundleFile.exists()) return
        val certDirs = listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts")
        val bundle = StringBuilder()
        for (dir in certDirs) {
            val d = File(dir)
            if (!d.exists()) continue
            d.listFiles()?.forEach { cert ->
                try { bundle.append(cert.readText()).append("\n") } catch (_: Exception) {}
            }
            if (bundle.isNotEmpty()) break
        }
        if (bundle.isNotEmpty()) {
            caBundleFile.writeText(bundle.toString())
            Log.i(TAG, "CA bundle written (${bundle.length} bytes)")
        }
    }

    private fun writeNullclawHomeConfig() {
        val apiKey = getApiKey() ?: return
        nullclawConfigFile.parentFile?.mkdirs()
        nullclawConfigFile.writeText("""
{
  "agents": {
    "defaults": {
      "model": {
        "primary": "anthropic/${getModel()}"
      }
    }
  },
  "providers": {
    "anthropic": {
      "api_key": "$apiKey"
    }
  }
}
""".trimIndent())
    }

    // ── RAG: web search ───────────────────────────────────────────────────────

    /** Returns true if the query likely needs current/live information. */
    private fun needsWebSearch(prompt: String): Boolean {
        val lower = prompt.lowercase()
        return LIVE_INFO_KEYWORDS.any { lower.contains(it) }
    }

    private val WEATHER_KEYWORDS = setOf("weather", "temperature", "forecast", "rain", "sunny", "cloudy", "wind", "humidity", "hot", "cold", "degrees")

    /**
     * Route to the right search source based on query type.
     * Weather → wttr.in (free, real-time, no key needed)
     * Other   → Brave (if key set) or DuckDuckGo
     */
    private fun webSearch(query: String): List<Pair<String, String>> {
        val lower = query.lowercase()
        if (WEATHER_KEYWORDS.any { lower.contains(it) }) {
            val weatherResult = wttrSearch(query)
            if (weatherResult.isNotEmpty()) return weatherResult
        }
        val braveKey = getBraveKey()
        return if (!braveKey.isNullOrBlank()) braveSearch(query, braveKey)
        else duckDuckGoSearch(query)
    }

    // WMO weather code descriptions
    private fun wmoDescription(code: Int) = when(code) {
        0 -> "Clear sky"; 1,2,3 -> "Partly cloudy"
        45,48 -> "Foggy"; 51,53,55 -> "Drizzle"; 61,63,65 -> "Rain"
        71,73,75 -> "Snow"; 80,81,82 -> "Rain showers"; 95 -> "Thunderstorm"
        else -> "Overcast"
    }

    /** Get current weather from Open-Meteo (free, no API key, real-time). */
    private fun wttrSearch(query: String): List<Pair<String, String>> {
        return try {
            // Extract location from query
            val location = query.lowercase()
                .replace(Regex("\\b(weather|forecast|temperature|what's?|what is|the|is|in|today|tonight|now|currently|right now|please|tell me)\\b"), " ")
                .trim().replace(Regex("\\s+"), " ").ifBlank { "Berlin" }

            // Step 1: geocode the location
            val geoEncoded = URLEncoder.encode(location, "UTF-8")
            val geoUrl = URL("https://geocoding-api.open-meteo.com/v1/search?name=$geoEncoded&count=1&format=json")
            val geoConn = geoUrl.openConnection() as HttpURLConnection
            geoConn.connectTimeout = 8_000; geoConn.readTimeout = 8_000
            if (geoConn.responseCode != 200) return emptyList()
            val geoJson = JSONObject(geoConn.inputStream.bufferedReader().readText())
            val results = geoJson.optJSONArray("results") ?: return emptyList()
            if (results.length() == 0) return emptyList()
            val place = results.getJSONObject(0)
            val lat = place.getDouble("latitude")
            val lon = place.getDouble("longitude")
            val cityName = place.getString("name")
            val country = place.optString("country", "")

            // Step 2: get current weather
            val wxUrl = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,apparent_temperature" +
                "&temperature_unit=celsius&wind_speed_unit=kmh&format=json")
            val wxConn = wxUrl.openConnection() as HttpURLConnection
            wxConn.connectTimeout = 8_000; wxConn.readTimeout = 8_000
            if (wxConn.responseCode != 200) return emptyList()
            val wxJson = JSONObject(wxConn.inputStream.bufferedReader().readText())
            val current = wxJson.getJSONObject("current")

            val tempC = current.getDouble("temperature_2m")
            val feelsC = current.getDouble("apparent_temperature")
            val humidity = current.getInt("relative_humidity_2m")
            val wind = current.getDouble("wind_speed_10m")
            val code = current.getInt("weather_code")
            val desc = wmoDescription(code)

            val summary = "$desc, ${tempC}°C (feels like ${feelsC}°C). Humidity ${humidity}%, wind ${wind} km/h."
            Log.i(TAG, "Open-Meteo: $cityName $summary")
            listOf(Pair("Current weather in $cityName, $country", summary))
        } catch (e: Exception) {
            Log.w(TAG, "Open-Meteo failed: ${e.message}")
            emptyList()
        }
    }

    private fun braveSearch(query: String, apiKey: String): List<Pair<String, String>> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://api.search.brave.com/res/v1/web/search?q=$encoded&count=3")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.setRequestProperty("X-Subscription-Token", apiKey)
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000

            if (conn.responseCode != 200) return emptyList()

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val results = json.optJSONObject("web")?.optJSONArray("results") ?: return emptyList()
            (0 until minOf(results.length(), 3)).map { i ->
                val r = results.getJSONObject(i)
                Pair(
                    r.optString("title", ""),
                    r.optString("description", "")
                )
            }.filter { it.second.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "Brave search failed: ${e.message}")
            emptyList()
        }
    }

    private fun duckDuckGoSearch(query: String): List<Pair<String, String>> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "ClawWatch/1.0")
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000

            if (conn.responseCode != 200) return emptyList()

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val results = mutableListOf<Pair<String, String>>()

            // Abstract (direct answer)
            val abstract = json.optString("Abstract", "")
            val abstractTitle = json.optString("Heading", "")
            if (abstract.isNotBlank()) results.add(Pair(abstractTitle, abstract))

            // Related topics
            val topics = json.optJSONArray("RelatedTopics") ?: JSONArray()
            for (i in 0 until minOf(topics.length(), 3 - results.size)) {
                val t = topics.optJSONObject(i) ?: continue
                val text = t.optString("Text", "")
                if (text.isNotBlank()) results.add(Pair("", text))
            }

            results.filter { it.second.isNotBlank() }.take(3)
        } catch (e: Exception) {
            Log.w(TAG, "DDG search failed: ${e.message}")
            emptyList()
        }
    }

    /** Build an augmented system prompt with web search results injected. */
    private fun buildRagSystemPrompt(basePrompt: String, results: List<Pair<String, String>>): String {
        if (results.isEmpty()) return basePrompt
        val sb = StringBuilder(basePrompt)
        sb.append("\n\nCurrent web information (use this to answer questions about recent/live data):\n")
        results.forEachIndexed { i, (title, snippet) ->
            sb.append("${i + 1}. ")
            if (title.isNotBlank()) sb.append("$title: ")
            sb.append(snippet)
            sb.append("\n")
        }
        sb.append("\nIf the web results are relevant, use them. Still keep response to 1-3 sentences.")
        return sb.toString()
    }

    // ── Main query entry point ────────────────────────────────────────────────

    suspend fun query(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey()
            ?: return@withContext Result.failure(RuntimeException("No API key — run ./set_key.sh"))

        Log.i(TAG, "Query: '${prompt.take(60)}' rag=${getRagMode()}")

        return@withContext when (getRagMode()) {
            "opus_tool" -> queryWithOpusTool(prompt, apiKey)
            "kotlin"    -> queryWithKotlinRag(prompt, apiKey)
            else        -> queryDirect(prompt, apiKey)
        }
    }

    // ── Mode 1: Direct (no RAG) ───────────────────────────────────────────────

    private suspend fun queryDirect(prompt: String, apiKey: String): Result<String> =
        withContext(Dispatchers.IO) {
            callAnthropicMessages(
                apiKey = apiKey,
                model = getModel(),
                maxTokens = getMaxTokens(),
                systemPrompt = getSystemPrompt(),
                userMessage = prompt
            )
        }

    // ── Mode 2: Kotlin RAG — pre-search + inject ──────────────────────────────

    private suspend fun queryWithKotlinRag(prompt: String, apiKey: String): Result<String> =
        withContext(Dispatchers.IO) {
            var systemPrompt = getSystemPrompt()

            if (needsWebSearch(prompt)) {
                Log.i(TAG, "Kotlin RAG: searching for '$prompt'")
                val results = webSearch(prompt)
                if (results.isNotEmpty()) {
                    systemPrompt = buildRagSystemPrompt(systemPrompt, results)
                    Log.i(TAG, "Kotlin RAG: injected ${results.size} results")
                }
            }

            callAnthropicMessages(
                apiKey = apiKey,
                model = getModel(),
                maxTokens = getMaxTokens(),
                systemPrompt = systemPrompt,
                userMessage = prompt
            )
        }

    // ── Mode 3: Opus Tool Use — Claude calls web_search, we execute ───────────

    private suspend fun queryWithOpusTool(prompt: String, apiKey: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                // Define web_search tool for Claude
                val tools = JSONArray().apply {
                    put(JSONObject().apply {
                        put("name", "web_search")
                        put("description",
                            "Search the web for current information. Use this when the user asks about " +
                            "recent events, live data, weather, news, prices, scores, or anything " +
                            "that requires up-to-date information.")
                        put("input_schema", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("query", JSONObject().apply {
                                    put("type", "string")
                                    put("description", "The search query")
                                })
                            })
                            put("required", JSONArray().apply { put("query") })
                        })
                    })
                }

                // First call — Claude decides whether to search
                val firstBody = JSONObject().apply {
                    put("model", getModel())
                    put("max_tokens", getMaxTokens() + 200) // extra tokens for tool call
                    put("system", getSystemPrompt())
                    put("tools", tools)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                    })
                }.toString()

                val firstResponse = callAnthropicRaw(apiKey, firstBody)
                    ?: return@withContext Result.failure(RuntimeException("API error"))

                val stopReason = firstResponse.optString("stop_reason")

                // If Claude didn't call the tool, extract text directly
                if (stopReason != "tool_use") {
                    val text = firstResponse.getJSONArray("content")
                        .getJSONObject(0).getString("text").trim()
                    return@withContext Result.success(text)
                }

                // Claude called web_search — find the tool call
                val content = firstResponse.getJSONArray("content")
                var searchQuery = prompt // fallback
                var toolUseId = ""
                for (i in 0 until content.length()) {
                    val block = content.getJSONObject(i)
                    if (block.optString("type") == "tool_use" &&
                        block.optString("name") == "web_search") {
                        searchQuery = block.optJSONObject("input")
                            ?.optString("query", prompt) ?: prompt
                        toolUseId = block.optString("id")
                        break
                    }
                }

                Log.i(TAG, "Opus tool: searching '$searchQuery'")
                val results = webSearch(searchQuery)
                val searchResultText = if (results.isNotEmpty()) {
                    results.joinToString("\n") { (title, snippet) ->
                        if (title.isNotBlank()) "$title: $snippet" else snippet
                    }
                } else {
                    "No results found for: $searchQuery"
                }

                // Second call — send search results back to Claude
                val secondBody = JSONObject().apply {
                    put("model", getModel())
                    put("max_tokens", getMaxTokens())
                    put("system", getSystemPrompt())
                    put("tools", tools)
                    put("messages", JSONArray().apply {
                        // Original user message
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", prompt)
                        })
                        // Claude's response with tool call
                        put(JSONObject().apply {
                            put("role", "assistant")
                            put("content", content)
                        })
                        // Tool result
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "tool_result")
                                    put("tool_use_id", toolUseId)
                                    put("content", searchResultText)
                                })
                            })
                        })
                    })
                }.toString()

                val secondResponse = callAnthropicRaw(apiKey, secondBody)
                    ?: return@withContext Result.failure(RuntimeException("API error on second call"))

                val finalText = secondResponse.getJSONArray("content")
                    .getJSONObject(0).getString("text").trim()

                Log.i(TAG, "Opus tool result: '${finalText.take(80)}'")
                Result.success(finalText)

            } catch (e: Exception) {
                Log.e(TAG, "Opus tool query error", e)
                // Fall back to direct query
                Log.w(TAG, "Falling back to direct query")
                queryDirect(prompt, apiKey)
            }
        }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    /** Call /v1/messages and return the response JSONObject, or null on error. */
    private fun callAnthropicRaw(apiKey: String, body: String): JSONObject? {
        return try {
            val url = URL("https://api.anthropic.com/v1/messages")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-api-key", apiKey)
            conn.setRequestProperty("anthropic-version", "2023-06-01")
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            val code = conn.responseCode
            val responseText = if (code == 200)
                conn.inputStream.bufferedReader().readText()
            else
                conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"

            Log.i(TAG, "Anthropic raw response code=$code")
            if (code != 200) {
                Log.e(TAG, "Anthropic error: $responseText")
                null
            } else {
                JSONObject(responseText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP error", e)
            null
        }
    }

    /** Convenience: call /v1/messages for a simple text response. */
    private fun callAnthropicMessages(
        apiKey: String,
        model: String,
        maxTokens: Int,
        systemPrompt: String,
        userMessage: String
    ): Result<String> {
        val body = JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userMessage)
                })
            })
        }.toString()

        val response = callAnthropicRaw(apiKey, body)
            ?: return Result.failure(RuntimeException("API call failed"))

        return try {
            val text = response.getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
                .trim()
            Log.i(TAG, "Response: '${text.take(80)}'")
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(RuntimeException("Failed to parse response: ${e.message}"))
        }
    }

    fun isInstalled() = binaryFile.exists()
}
