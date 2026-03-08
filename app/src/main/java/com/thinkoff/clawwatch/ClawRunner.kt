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
import java.util.zip.GZIPInputStream

/**
 * ClawRunner — manages NullClaw binary + Anthropic API calls with optional RAG.
 *
 * RAG modes:
 *  - KOTLIN: pre-search with DuckDuckGo (no key) or Brave Search (optional key),
 *            inject top results into system context before Anthropic call.
 *  - OPUS_TOOL: use Anthropic tool_use to let Claude call a web_search tool,
 *               execute the search on the Kotlin side, return results, get final answer.
 *
 * Config is read from encrypted SharedPreferences (migrated from legacy prefs if present).
 */
class ClawRunner(private val context: Context) {

    companion object {
        private const val TAG = "ClawRunner"
        private const val CONFIG_NAME = "nullclaw.json"
        private const val CONFIG_FALLBACK_NAME = "nullclaw.json.example"
        private const val PREF_API_KEY = "anthropic_api_key"
        private const val PREF_MODEL = "model"
        private const val PREF_SYSTEM_PROMPT = "system_prompt"
        private const val PREF_MAX_TOKENS = "max_tokens"
        private const val PREF_RAG_MODE = "rag_mode"         // "off" | "kotlin" | "always" | "opus_tool"
        private const val PREF_BRAVE_KEY = "brave_api_key"
        private const val PREF_TAVILY_KEY = "tavily_api_key"
        private const val PREF_ANTFARM_KEY = "antfarm_api_key"
        private const val PREF_ANTFARM_ROOMS = "antfarm_rooms"
        private const val DEFAULT_FAMILY_ROOMS = "thinkoff-development"

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
            "You are ClawWatch, a smart and relaxed voice presence on a watch. " +
            "Respond in 1-3 short sentences maximum. No markdown, no lists, no bullet points. " +
            "Use plain spoken language. Be natural, helpful, and a little playful when it fits, without sounding formal, salesy, or robotic."

        private const val MAX_CONTEXT_MESSAGES = 10
        private const val MAX_CONTEXT_CHARS_PER_MESSAGE = 600
    }

    private data class ChatTurn(val role: String, val content: String)
    private data class FamilyMessage(
        val room: String,
        val from: String,
        val body: String,
        val createdAt: String
    )

    private val filesDir get() = context.filesDir
    private val homeDir get() = context.filesDir.parentFile!!
    private val nativeLibDir get() = context.applicationInfo.nativeLibraryDir
    private val binaryFile get() = File(nativeLibDir, "libnullclaw.so")
    private val configFile get() = File(filesDir, CONFIG_NAME)
    private val nullclawConfigFile get() = File(homeDir, ".nullclaw/config.json")
    private val caBundleFile get() = File(filesDir, "ca-certificates.crt")

    private val prefs by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SecurePrefs.watch(context) }
    private val conversationLock = Any()
    private val conversation = ArrayDeque<ChatTurn>()
    @Volatile
    private var conversationConfigFingerprint: String? = null

    // ── Config accessors ─────────────────────────────────────────────────────

    fun saveApiKey(key: String) = prefs.edit().putString(PREF_API_KEY, key).apply()
    fun saveBraveKey(key: String) = prefs.edit().putString(PREF_BRAVE_KEY, key).apply()
    fun saveTavilyKey(key: String) = prefs.edit().putString(PREF_TAVILY_KEY, key).apply()
    fun saveAntFarmKey(key: String) = prefs.edit().putString(PREF_ANTFARM_KEY, key).apply()
    fun saveAntFarmRooms(rooms: String) = prefs.edit().putString(PREF_ANTFARM_ROOMS, rooms).apply()
    fun saveModel(model: String) = prefs.edit().putString(PREF_MODEL, model).apply()
    fun saveSystemPrompt(prompt: String) = prefs.edit().putString(PREF_SYSTEM_PROMPT, prompt).apply()
    fun saveMaxTokens(n: Int) = prefs.edit().putInt(PREF_MAX_TOKENS, n).apply()
    fun saveRagMode(mode: String) = prefs.edit().putString(PREF_RAG_MODE, mode).apply()

    fun hasApiKey(): Boolean = prefs.getString(PREF_API_KEY, null)?.isNotBlank() == true
    private fun getApiKey(): String? = prefs.getString(PREF_API_KEY, null)
    private fun getBraveKey(): String? = prefs.getString(PREF_BRAVE_KEY, null)
    private fun getTavilyKey(): String? = prefs.getString(PREF_TAVILY_KEY, null)
    private fun getAntFarmKey(): String? = prefs.getString(PREF_ANTFARM_KEY, null)
    private fun getAntFarmRooms(): List<String> =
        (prefs.getString(PREF_ANTFARM_ROOMS, DEFAULT_FAMILY_ROOMS) ?: DEFAULT_FAMILY_ROOMS)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    private fun getModel(): String = prefs.getString(PREF_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    private fun getSystemPrompt(): String = prefs.getString(PREF_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
    private fun getMaxTokens(): Int = prefs.getInt(PREF_MAX_TOKENS, DEFAULT_MAX_TOKENS)
    private fun getRagMode(): String = prefs.getString(PREF_RAG_MODE, "kotlin") ?: "kotlin"

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    suspend fun ensureInstalled() = withContext(Dispatchers.IO) {
        Log.i(TAG, "NullClaw binary: ${binaryFile.absolutePath} (exists: ${binaryFile.exists()})")
        if (!configFile.exists()) {
            val assetName = try {
                context.assets.open(CONFIG_NAME).close()
                CONFIG_NAME
            } catch (_: Exception) {
                CONFIG_FALLBACK_NAME
            }
            context.assets.open(assetName).use { it.copyTo(configFile.outputStream()) }
        }
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

    // ── RAG: web search ───────────────────────────────────────────────────────

    /** Returns true if the query likely needs current/live information. */
    private fun needsWebSearch(prompt: String): Boolean {
        val lower = prompt.lowercase()
        return LIVE_INFO_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * Universal web search — priority: Tavily → Brave → nothing.
     * Tavily: free 1000/mo, built for AI RAG, get key at tavily.com
     * Brave:  free 2000/mo, get key at brave.com/search/api
     * Set keys in admin panel at http://localhost:4747
     */
    private fun webSearch(query: String): List<Pair<String, String>> {
        val tavilyKey = getTavilyKey()
        if (!tavilyKey.isNullOrBlank()) return tavilySearch(query, tavilyKey)
        val braveKey = getBraveKey()
        if (!braveKey.isNullOrBlank()) return braveSearch(query, braveKey)

        Log.i(TAG, "No search API key set — falling back to free DDG/Open-Meteo")
        val lower = query.lowercase()
        val isWeather = listOf("weather", "temperature", "forecast", "how hot", "how cold", "rain", "snow").any { lower.contains(it) }
        
        return if (isWeather) {
            val wx = wttrSearch(query)
            if (wx.isNotEmpty()) wx else duckDuckGoHtmlSearch(query)
        } else {
            duckDuckGoHtmlSearch(query)
        }
    }

    /** Tavily AI search — designed for RAG, returns clean snippets for any query. */
    private fun tavilySearch(query: String, apiKey: String): List<Pair<String, String>> {
        return try {
            val body = JSONObject().apply {
                put("api_key", apiKey)
                put("query", query)
                put("search_depth", "basic")
                put("max_results", 3)
                put("include_answer", true)
            }.toString()

            val url = URL("https://api.tavily.com/search")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(body) }

            if (conn.responseCode != 200) {
                Log.w(TAG, "Tavily error ${conn.responseCode}")
                return emptyList()
            }

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val results = mutableListOf<Pair<String, String>>()

            // Direct answer if available
            val answer = json.optString("answer", "")
            if (answer.isNotBlank()) results.add(Pair("Direct answer", answer))

            // Web results
            val items = json.optJSONArray("results") ?: JSONArray()
            for (i in 0 until minOf(items.length(), 3 - results.size)) {
                val r = items.getJSONObject(i)
                val title = r.optString("title", "")
                val content = r.optString("content", "").take(200)
                if (content.isNotBlank()) results.add(Pair(title, content))
            }
            Log.i(TAG, "Tavily: ${results.size} results for '$query'")
            results
        } catch (e: Exception) {
            Log.w(TAG, "Tavily failed: ${e.message}")
            emptyList()
        }
    }

    private fun wmoDescription(code: Int) = when(code) {
        0 -> "Clear sky"; 1,2,3 -> "Partly cloudy"
        45,48 -> "Foggy"; 51,53,55 -> "Drizzle"; 61,63,65 -> "Rain"
        71,73,75 -> "Snow"; 80,81,82 -> "Rain showers"; 95 -> "Thunderstorm"
        else -> "Overcast"
    }

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

            val json = JSONObject(readResponseBody(conn))
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

    private fun readResponseBody(conn: HttpURLConnection): String {
        val input = conn.inputStream
        val isGzip = (conn.contentEncoding ?: "").contains("gzip", ignoreCase = true)
        val stream = if (isGzip) GZIPInputStream(input) else input
        return stream.bufferedReader().use { it.readText() }
    }

    /**
     * DuckDuckGo HTML search — returns real web results for any query.
     * No API key needed. Scrapes the HTML search results page.
     */
    private fun duckDuckGoHtmlSearch(query: String): List<Pair<String, String>> {
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://html.duckduckgo.com/html/?q=$encoded")
            val conn = url.openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/91.0 Mobile Safari/537.36")
            conn.setRequestProperty("Accept", "text/html")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            if (conn.responseCode != 200) return emptyList()

            val html = conn.inputStream.bufferedReader().readText()
            val results = mutableListOf<Pair<String, String>>()

            // Extract result snippets from DDG HTML
            val snippetRegex = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            val titleRegex   = Regex("""class="result__a"[^>]*>(.*?)</a>""",    RegexOption.DOT_MATCHES_ALL)
            val snippets = snippetRegex.findAll(html).map {
                it.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
            }.filter { it.isNotBlank() }.take(3).toList()
            val titles = titleRegex.findAll(html).map {
                it.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
            }.filter { it.isNotBlank() }.take(3).toList()

            snippets.forEachIndexed { i, snippet ->
                val title = titles.getOrElse(i) { "" }
                results.add(Pair(title, snippet))
            }
            Log.i(TAG, "DDG HTML: ${results.size} results for '$query'")
            results
        } catch (e: Exception) {
            Log.w(TAG, "DDG HTML search failed: ${e.message}")
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
            ?: return@withContext Result.failure(RuntimeException("API key missing"))

        val ragMode = getRagMode()
        clearConversationIfConfigChanged(ragMode)
        Log.i(TAG, "Query: '${prompt.take(60)}' rag=$ragMode")

        return@withContext when (ragMode) {
            "opus_tool" -> queryWithOpusTool(prompt, apiKey)
            "always"    -> queryWithKotlinRag(prompt, apiKey, forceSearch = true)
            "kotlin"    -> queryWithKotlinRag(prompt, apiKey, forceSearch = false)
            else        -> queryDirect(prompt, apiKey)
        }
    }

    suspend fun summarizeFamilyStatus(): Result<String> = withContext(Dispatchers.IO) {
        val antFarmKey = getAntFarmKey()
            ?: return@withContext Result.success(
                "I don't have family room access configured yet."
            )
        val rooms = getAntFarmRooms()
        val recentMessages = fetchRecentFamilyMessages(rooms, antFarmKey)
        if (recentMessages.isEmpty()) {
            return@withContext Result.success(
                "I couldn't find any recent family updates in ${rooms.joinToString(", ")}."
            )
        }

        val apiKey = getApiKey()
            ?: return@withContext Result.success(buildFallbackFamilySummary(recentMessages))

        val transcript = recentMessages.joinToString("\n") { message ->
            "[${message.room}] ${message.createdAt} ${message.from}: ${message.body}"
        }
        val prompt = buildString {
            append("Summarize what is going on with the family based on these recent room updates.\n")
            append("Keep it to 1 or 2 spoken sentences. Mention the most important current activity, blocker, or mood.\n")
            append("If the updates are mixed, say that briefly.\n\n")
            append(transcript)
        }

        val result = callAnthropicMessages(
            apiKey = apiKey,
            model = getModel(),
            maxTokens = 120,
            systemPrompt = "You summarize recent family/team room activity for a smartwatch. Be concrete, calm, and truthful. No lists.",
            userMessage = prompt
        )
        result.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.success(buildFallbackFamilySummary(recentMessages)) }
        )
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

    private fun fetchRecentFamilyMessages(
        rooms: List<String>,
        apiKey: String
    ): List<FamilyMessage> {
        return rooms.flatMap { room ->
            fetchRoomMessages(room, apiKey)
        }
            .sortedByDescending { it.createdAt }
            .take(10)
    }

    private fun fetchRoomMessages(room: String, apiKey: String): List<FamilyMessage> {
        return try {
            val encodedRoom = URLEncoder.encode(room, "UTF-8")
            val url = URL("https://antfarm.world/api/v1/rooms/$encodedRoom/messages?limit=6")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            if (conn.responseCode != 200) {
                Log.w(TAG, "Ant Farm room fetch failed for $room: ${conn.responseCode}")
                return emptyList()
            }

            val json = JSONObject(readResponseBody(conn))
            val messages = json.optJSONArray("messages") ?: JSONArray()
            buildList {
                for (i in 0 until messages.length()) {
                    val item = messages.optJSONObject(i) ?: continue
                    val body = item.optString("body", "")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    if (body.isBlank()) continue
                    val from = item.optString("from").ifBlank {
                        item.optJSONObject("from_agent")?.optString("handle", "")?.takeIf { it.isNotBlank() }
                            ?: "someone"
                    }
                    add(
                        FamilyMessage(
                            room = room,
                            from = from,
                            body = body.take(220),
                            createdAt = item.optString("created_at", "")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ant Farm fetch failed for $room: ${e.message}")
            emptyList()
        }
    }

    private fun buildFallbackFamilySummary(messages: List<FamilyMessage>): String {
        val latest = messages.firstOrNull()
            ?: return "I couldn't find any recent family updates."
        return "Latest family activity is in ${latest.room}. ${latest.from} said: ${latest.body}"
            .take(220)
    }

    // ── Mode 2: Kotlin RAG — pre-search + inject ──────────────────────────────

    private suspend fun queryWithKotlinRag(
        prompt: String,
        apiKey: String,
        forceSearch: Boolean
    ): Result<String> =
        withContext(Dispatchers.IO) {
            var systemPrompt = getSystemPrompt()
            if (forceSearch || needsWebSearch(prompt)) {
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
                    put("messages", buildMessagesWithContext(prompt))
                }.toString()

                val firstResponse = callAnthropicRaw(apiKey, firstBody)
                    ?: return@withContext Result.failure(RuntimeException("API error"))

                val stopReason = firstResponse.optString("stop_reason")

                // If Claude didn't call the tool, extract text directly
                if (stopReason != "tool_use") {
                    val text = firstResponse.getJSONArray("content")
                        .getJSONObject(0).getString("text").trim()
                    appendConversation("user", prompt)
                    appendConversation("assistant", text)
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
                    val historyWithTool = buildMessagesWithContext(prompt).apply {
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
                    }
                    put("messages", historyWithTool)
                }.toString()

                val secondResponse = callAnthropicRaw(apiKey, secondBody)
                    ?: return@withContext Result.failure(RuntimeException("API error on second call"))

                val finalText = secondResponse.getJSONArray("content")
                    .getJSONObject(0).getString("text").trim()

                appendConversation("user", prompt)
                appendConversation("assistant", finalText)

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
            put("messages", buildMessagesWithContext(userMessage))
        }.toString()

        val response = callAnthropicRaw(apiKey, body)
            ?: return Result.failure(RuntimeException("API call failed"))

        return try {
            val text = response.getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
                .trim()
            appendConversation("user", userMessage)
            appendConversation("assistant", text)
            Log.i(TAG, "Response: '${text.take(80)}'")
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(RuntimeException("Failed to parse response: ${e.message}"))
        }
    }

    private fun buildMessagesWithContext(userMessage: String): JSONArray {
        val snapshot = synchronized(conversationLock) { conversation.toList() }
        return JSONArray().apply {
            snapshot.forEach { turn ->
                put(JSONObject().apply {
                    put("role", turn.role)
                    put("content", turn.content)
                })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }
    }

    private fun clearConversationIfConfigChanged(ragMode: String) {
        val fingerprint = buildString {
            append(getModel())
            append('\u001F')
            append(getSystemPrompt())
            append('\u001F')
            append(ragMode)
        }
        synchronized(conversationLock) {
            val previous = conversationConfigFingerprint
            if (previous == null) {
                conversationConfigFingerprint = fingerprint
                return
            }
            if (previous != fingerprint) {
                conversation.clear()
                conversationConfigFingerprint = fingerprint
                Log.i(TAG, "Conversation context cleared due to config change")
            }
        }
    }

    private fun appendConversation(role: String, content: String) {
        val normalized = content.trim().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return
        synchronized(conversationLock) {
            conversation.addLast(ChatTurn(role, normalized.take(MAX_CONTEXT_CHARS_PER_MESSAGE)))
            while (conversation.size > MAX_CONTEXT_MESSAGES) {
                conversation.removeFirst()
            }
        }
    }

    fun isInstalled() = binaryFile.exists()
}
