package com.thinkoff.clawwatch

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.AlarmClock
import android.util.Log
import android.view.Gravity
import android.view.ViewConfiguration
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.GestureDetector
import android.widget.TextView
import android.graphics.drawable.AnimatedVectorDrawable
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.health.connect.client.PermissionController
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.thinkoff.clawwatch.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Main Wear OS activity.
 *
 * States:
 *   SETUP     → no API key yet, show key entry
 *   IDLE      → tap mic to start
 *   LISTENING → Vosk capturing speech, partial shown
 *   THINKING  → NullClaw + Opus 4.6 running
 *   SPEAKING  → Android TTS playing response
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ClawWatch"
        private const val FAB_DEBOUNCE_MS = 300L
        private const val AUTO_LISTEN_WINDOW_MS = 3500L
        private const val AVATAR_SWIPE_THRESHOLD_DP = 24f
        private const val PREF_RAG_MODE = "rag_mode"
        private const val PREF_AVATAR_TYPE = "avatar_type"
        private const val PREF_LIVE_TEXT_ENABLED = "live_text_enabled"
        private const val PREF_STATUS_BUBBLES_ENABLED = "status_bubbles_enabled"
        private const val ACCENT_COLOR = 0xFFD4A5E9.toInt()
        private const val LOW_BATTERY_COLOR = 0xFF9CA3AF.toInt()
        private val AVATARS = listOf("ant", "lobster", "orange_lobster", "robot", "boy", "girl")
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var clawRunner: ClawRunner
    private lateinit var voiceEngine: VoiceEngine
    private lateinit var dayPhaseManager: DayPhaseManager
    private lateinit var vitalsReader: VitalsReader
    private lateinit var watchPushRegistrar: WatchPushRegistrar
    private var intentAdapter: WatchIntentAdapter? = null
    private lateinit var healthConnectManager: HealthConnectManager
    private val prefs by lazy { SecurePrefs.watch(this) }

    private enum class State { SETUP, IDLE, LISTENING, THINKING, SEARCHING, SPEAKING, ERROR }
    private enum class AvatarType { ANT, LOBSTER, ORANGE_LOBSTER, ROBOT, BOY, GIRL }
    private enum class AvatarState { IDLE, LISTENING, THINKING, SEARCHING, SPEAKING, ERROR }
    private enum class LocalCommandType { VITALS_SNAPSHOT, HEART_RATE, FAMILY_STATUS, HEALTH_SNAPSHOT, SLEEP_SUMMARY }
    private data class TimerCommand(
        val totalSeconds: Int,
        val spokenDuration: String
    )
    private data class RoomMessageCommand(
        val room: String?,
        val body: String
    )
    private data class PendingVitalsCommand(
        val type: LocalCommandType,
        val token: Int
    )
    private data class SpeechRenderPlan(
        val spokenText: String,
        val previewText: String,
        val speechRate: Float = 1.1f,
        val pitch: Float = 1.0f,
        val gestureEnergy: Float = 1.0f,
        val mood: GestureAnimator.Mood = GestureAnimator.Mood.NEUTRAL
    )
    private lateinit var gestureDetector: GestureDetector
    private var currentAvatarIndex = 0
    private var useEmojiFallback = false
    private var fallbackTextView: TextView? = null

    private var state = State.SETUP
    private var queryJob: Job? = null
    private var autoListenTimeoutJob: Job? = null
    private var interactionToken = 0
    private var lastFabTapAt = 0L
    private var isAutoListenWindow = false
    private var countdownAnimator: ValueAnimator? = null
    private var avatarAnimator: ValueAnimator? = null
    private var speakingPreviewJob: Job? = null
    private val gestureAnimator by lazy { GestureAnimator(lifecycleScope) }
    private var lastPartialStatusAt = 0L
    private var avatarSwipeStartX = 0f
    private var avatarSwipeStartY = 0f
    private var avatarSwipeActive = false
    private var avatarTouchStartAt = 0L
    private var speechAudioStarted = false
    private var pendingVitalsCommand: PendingVitalsCommand? = null

    private fun ensureStatusOverlayDefaults() {
        if (!isLiveTextEnabled() && !prefs.getBoolean(PREF_STATUS_BUBBLES_ENABLED, true)) {
            prefs.edit().putBoolean(PREF_STATUS_BUBBLES_ENABLED, true).apply()
        }
    }

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startListening() else setStatus("Mic permission needed") }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Log.w(TAG, "Notification permission denied; urgent push alerts may be hidden.")
        }
    }

    private val requestVitalsPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val pending = pendingVitalsCommand ?: return@registerForActivityResult
        pendingVitalsCommand = null
        runVitalsCommand(pending.type, pending.token)
    }

    private val requestHealthConnectPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(healthConnectManager.allRequestablePermissions)) {
            Log.i(TAG, "Health Connect permissions granted")
        } else {
            Log.w(TAG, "Health Connect permissions partially denied: $granted")
        }
    }

    private val avatarExpressions: Map<AvatarType, Map<AvatarState, String>> = mapOf(
        AvatarType.ANT to mapOf(
            AvatarState.IDLE to "🐜",
            AvatarState.LISTENING to "🐜",
            AvatarState.THINKING to "🐜",
            AvatarState.SEARCHING to "🐜",
            AvatarState.SPEAKING to "🐜",
            AvatarState.ERROR to "🐜"
        ),
        AvatarType.LOBSTER to mapOf(
            AvatarState.IDLE to "🦞",
            AvatarState.LISTENING to "🦞",
            AvatarState.THINKING to "🦞",
            AvatarState.SEARCHING to "🦞",
            AvatarState.SPEAKING to "🦞",
            AvatarState.ERROR to "🦞"
        ),
        AvatarType.ORANGE_LOBSTER to mapOf(
            AvatarState.IDLE to "🦞",
            AvatarState.LISTENING to "🦞",
            AvatarState.THINKING to "🦞",
            AvatarState.SEARCHING to "🦞",
            AvatarState.SPEAKING to "🦞",
            AvatarState.ERROR to "🦞"
        ),
        AvatarType.ROBOT to mapOf(
            AvatarState.IDLE to "🤖",
            AvatarState.LISTENING to "🤖",
            AvatarState.THINKING to "🤖",
            AvatarState.SEARCHING to "🤖",
            AvatarState.SPEAKING to "🤖",
            AvatarState.ERROR to "🤖"
        ),
        AvatarType.BOY to mapOf(
            AvatarState.IDLE to "👦",
            AvatarState.LISTENING to "👦",
            AvatarState.THINKING to "👦",
            AvatarState.SEARCHING to "👦",
            AvatarState.SPEAKING to "👦",
            AvatarState.ERROR to "👦"
        ),
        AvatarType.GIRL to mapOf(
            AvatarState.IDLE to "👧",
            AvatarState.LISTENING to "👧",
            AvatarState.THINKING to "👧",
            AvatarState.SEARCHING to "👧",
            AvatarState.SPEAKING to "👧",
            AvatarState.ERROR to "👧"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ensureStatusOverlayDefaults()

        clawRunner = ClawRunner(this)
        voiceEngine = VoiceEngine(this)
        dayPhaseManager = DayPhaseManager(this)
        vitalsReader = VitalsReader(this)
        watchPushRegistrar = WatchPushRegistrar(this)
        healthConnectManager = HealthConnectManager(this)
        
        val prefs = getSharedPreferences("claw_prefs", 0)
        currentAvatarIndex = prefs.getInt("avatar_idx", 0)

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null && e1.x - e2.x > 50 && Math.abs(velocityX) > 200) {
                    // Swiped Left
                    currentAvatarIndex = (currentAvatarIndex + 1) % AVATARS.size
                    prefs.edit().putInt("avatar_idx", currentAvatarIndex).apply()
                    updateAvatarDrawable(state)
                    return true
                }
                return false
            }
        })

        binding.fab.setOnClickListener { onFabTapped() }
        binding.saveKeyBtn.setOnClickListener { onSaveKey() }
        setupAvatarSwipeSwitch()
        applyDayPhaseAppearance(dayPhaseManager.snapshotNow())
        ensureNotificationPermission()
        handleAlertOpenIntent(intent)

        intentAdapter = WatchIntentAdapter(this, prefs, lifecycleScope)
        intentAdapter?.start(
            initialState = "idle",
            screenActive = true,
            batteryPct = getBatteryPercentage(),
            lowBattery = isLowBattery()
        )

        lifecycleScope.launch {
            watchPushRegistrar
                .syncRegistration(trigger = "app_start")
                .onFailure { Log.w(TAG, "Push registration sync failed: ${it.message}") }
        }

        lifecycleScope.launch { initialise() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAlertOpenIntent(intent)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun ensureFallbackCreated() {
        if (fallbackTextView == null && useEmojiFallback) {
            val tv = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.CENTER
                }
                textSize = 72f
                visibility = View.VISIBLE
            }
            binding.avatarContainer.addView(tv)
            fallbackTextView = tv
        }
    }

    private fun updateAvatarDrawable(s: State) {
        if (s == State.SETUP) return

        val typeStr = prefs.getString(PREF_AVATAR_TYPE, "lobster")
        val avatarType = try {
            AvatarType.valueOf(typeStr?.uppercase() ?: "LOBSTER")
        } catch (e: Exception) { AvatarType.LOBSTER }

        val avatarState = when(s) {
            State.IDLE -> AvatarState.IDLE
            State.LISTENING -> AvatarState.LISTENING
            State.THINKING -> AvatarState.THINKING
            State.SEARCHING -> AvatarState.SEARCHING
            State.SPEAKING -> AvatarState.SPEAKING
            State.ERROR -> AvatarState.ERROR
            else -> AvatarState.IDLE
        }

        if (useEmojiFallback) {
            ensureFallbackCreated()
            binding.avatarView.visibility = View.GONE
            fallbackTextView?.visibility = View.VISIBLE
            val emoji = avatarExpressions[avatarType]?.get(avatarState) ?: "❓"
            fallbackTextView?.text = emoji
            
            // Text emoji simple scaling animation
            avatarAnimator?.cancel()
            if (s in listOf(State.LISTENING, State.THINKING, State.SEARCHING)) {
                fallbackTextView?.let { tv ->
                    avatarAnimator = ValueAnimator.ofFloat(1f, 1.05f).apply {
                        duration = 800
                        repeatCount = ValueAnimator.INFINITE
                        repeatMode = ValueAnimator.REVERSE
                        addUpdateListener { animator ->
                            val scale = animator.animatedValue as Float
                            tv.scaleX = scale
                            tv.scaleY = scale
                        }
                        start()
                    }
                }
            } else {
                fallbackTextView?.scaleX = 1f
                fallbackTextView?.scaleY = 1f
            }
        } else {
            fallbackTextView?.visibility = View.GONE
            binding.avatarView.visibility = View.VISIBLE
            
            val charName = avatarType.name.lowercase()
            val stateName = when(s) {
                State.IDLE -> "idle"
                State.LISTENING -> "listening"
                State.THINKING -> "thinking"
                State.SEARCHING -> "searching"
                State.SPEAKING -> "speaking"
                State.ERROR -> "error"
                else -> "idle"
            }

            val resName = "avd_avatar_${charName}_${stateName}"
            var resId = resources.getIdentifier(resName, "drawable", packageName)
            
            // Fallback to base static vector if AVD lookup fails
            if (resId == 0) {
                resId = resources.getIdentifier("ic_avatar_${charName}_base", "drawable", packageName)
            }

            if (resId != 0) {
                // Stop the previous drawable if it was animating
                (binding.avatarView.drawable as? AnimatedVectorDrawable)?.stop()

                val drawable = ContextCompat.getDrawable(this, resId)
                binding.avatarView.setImageDrawable(drawable)
                
                // Run the state AVD whenever the user is actively engaged.
                // SPEAKING also gets a looping AVD so the internal claw parts
                // keep moving while GestureAnimator adds stronger whole-body motion.
                if (getBatteryPercentage() > 20 && shouldAnimateAvatarForState(s)) {
                    (drawable as? AnimatedVectorDrawable)?.start()
                }
            }
            
            // AVD handles its own internal visual feedback.
        }
    }

    private fun shouldAnimateAvatarForState(state: State): Boolean = when (state) {
        State.LISTENING, State.THINKING, State.SEARCHING, State.SPEAKING -> true
        else -> false
    }

    private suspend fun initialise() {
        setStatus("Starting…")
        clawRunner.ensureInstalled()
        voiceEngine.initTts()
        applyDayPhaseAppearance(dayPhaseManager.refreshIfStale())
        updateAvatarDrawable(State.IDLE)

        if (!clawRunner.hasApiKey()) {
            setState(State.SETUP)
            setStatus("API key missing")
            return
        }

        voiceEngine.initVosk(
            onReady = {
                setState(State.IDLE)
                setStatus("Tap to talk")
                requestHealthConnectIfNeeded()
            },
            onError = { err ->
                Log.w(TAG, "Vosk not ready: $err")
                setState(State.IDLE)
                setStatus("Tap to talk")
                requestHealthConnectIfNeeded()
            }
        )
    }

    private fun onSaveKey() { /* key set via ADB, not on-watch */ }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun handleAlertOpenIntent(intent: Intent?) {
        if (intent?.action != AlertContract.ACTION_ALERT_OPEN) return

        val title = intent.getStringExtra(AlertContract.EXTRA_ALERT_TITLE).orEmpty()
        val body = intent.getStringExtra(AlertContract.EXTRA_ALERT_BODY).orEmpty()
        val room = intent.getStringExtra(AlertContract.EXTRA_ALERT_ROOM).orEmpty()
        val prompt = intent.getStringExtra(AlertContract.EXTRA_ALERT_PROMPT).orEmpty()

        val prefix = if (room.isNotBlank()) "[URGENT][$room]" else "[URGENT]"
        val detail = listOf(title, body).firstOrNull { it.isNotBlank() } ?: "Urgent alert opened."
        binding.queryText.text = "$prefix $detail"
        if (prompt.isNotBlank()) {
            binding.responseText.text = prompt
        }

        if (state == State.SETUP) {
            setStatus("Urgent alert received")
            return
        }

        interactionToken++
        cancelAutoListenWindow()
        stopSpeakingPreview()
        queryJob?.cancel()
        queryJob = null
        voiceEngine.stopListening()
        voiceEngine.stopSpeaking()
        setState(State.IDLE)
        setStatus("Urgent alert opened")
    }

    private fun onFabTapped() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFabTapAt < FAB_DEBOUNCE_MS) return
        lastFabTapAt = now

        when (state) {
            State.SETUP -> { /* handled by save button */ }
            State.IDLE, State.ERROR -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) startListening()
                else requestMic.launch(Manifest.permission.RECORD_AUDIO)
            }
            State.LISTENING -> {
                interactionToken++
                cancelAutoListenWindow()
                voiceEngine.stopListening()
                setState(State.IDLE)
                setStatus("Tap to talk")
            }
            State.THINKING, State.SEARCHING, State.SPEAKING -> {
                interactionToken++
                cancelAutoListenWindow()
                stopSpeakingPreview()
                queryJob?.cancel()
                queryJob = null
                voiceEngine.stopListening()
                voiceEngine.stopSpeaking()
                setState(State.IDLE)
                setStatus("Tap to talk")
            }
        }
    }

    private fun startListening(autoWindow: Boolean = false) {
        interactionToken++
        val token = interactionToken
        var consumed = false
        queryJob?.cancel()
        queryJob = null
        cancelAutoListenWindow()
        stopSpeakingPreview()
        voiceEngine.stopSpeaking()
        lastPartialStatusAt = 0L
        isAutoListenWindow = autoWindow
        setState(State.LISTENING)
        setStatus(if (autoWindow) "Listening (follow-up)…" else "Listening…")
        if (autoWindow) startAutoListenWindow(token)

        voiceEngine.startListening(
            onResult = { text ->
                runOnUiThread {
                    if (token != interactionToken || consumed) return@runOnUiThread
                    consumed = true
                    cancelAutoListenWindow()
                    voiceEngine.stopListening()
                    if (handleLocalCommand(text, token)) return@runOnUiThread
                    binding.queryText.text = "\u201c$text\u201d"
                    askClaw(text, token)
                }
            },
            onPartial = { partial ->
                runOnUiThread {
                    if (token != interactionToken || consumed) return@runOnUiThread
                    if (isAutoListenWindow && partial.isNotBlank()) cancelAutoListenWindow()
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPartialStatusAt < 250L) return@runOnUiThread
                    lastPartialStatusAt = now
                    setStatus(partial)
                }
            }
        )
    }

    private fun handleLocalCommand(prompt: String, token: Int): Boolean {
        parseVitalsCommand(prompt)?.let { command ->
            launchVitalsCommand(command, token)
            return true
        }
        parseHealthCommand(prompt)?.let { command ->
            launchHealthConnectCommand(command, token)
            return true
        }
        parseRoomMessageCommand(prompt)?.let { command ->
            launchRoomMessageCommand(command, token)
            return true
        }
        if (isFamilyStatusCommand(prompt)) {
            launchFamilyStatusCommand(token)
            return true
        }
        val timer = parseTimerCommand(prompt) ?: return false
        return launchSystemTimer(timer, token)
    }

    private fun parseVitalsCommand(prompt: String): LocalCommandType? {
        val normalized = prompt.lowercase()
        if (
            normalized.contains("pulse") ||
            normalized.contains("heart rate") ||
            normalized.contains("heartbeat")
        ) {
            return LocalCommandType.HEART_RATE
        }
        if (
            normalized.contains("check my vitals") ||
            normalized.contains("check vitals") ||
            normalized.contains("my vitals") ||
            normalized.contains("how am i doing")
        ) {
            return LocalCommandType.VITALS_SNAPSHOT
        }
        return null
    }

    private fun isFamilyStatusCommand(prompt: String): Boolean {
        val normalized = prompt.lowercase()
        val hasSubject = normalized.contains("family") ||
            normalized.contains("room") ||
            normalized.contains("team")
        if (!hasSubject) return false
        return normalized.contains("going on") ||
            normalized.contains("what's") ||
            normalized.contains("what is") ||
            normalized.contains("how's") ||
            normalized.contains("how is") ||
            normalized.contains("status") ||
            normalized.contains("update") ||
            normalized.contains("check") ||
            normalized.contains("happening")
    }

    private fun parseHealthCommand(prompt: String): LocalCommandType? {
        val normalized = prompt.lowercase()
        // Sleep-specific commands
        if (
            normalized.contains("how did i sleep") ||
            normalized.contains("sleep summary") ||
            normalized.contains("sleep report") ||
            normalized.contains("last night sleep") ||
            normalized.contains("my sleep")
        ) {
            return LocalCommandType.SLEEP_SUMMARY
        }
        // General health commands
        if (
            normalized.contains("health stats") ||
            normalized.contains("health data") ||
            normalized.contains("health summary") ||
            normalized.contains("health report") ||
            normalized.contains("check my health") ||
            normalized.contains("health snapshot") ||
            normalized.contains("my health") ||
            normalized.contains("how's my body") ||
            normalized.contains("hrv") ||
            normalized.contains("heart rate variability")
        ) {
            return LocalCommandType.HEALTH_SNAPSHOT
        }
        return null
    }

    private fun launchHealthConnectCommand(command: LocalCommandType, token: Int) {
        queryJob?.cancel()
        setState(State.THINKING)
        setStatus(if (command == LocalCommandType.SLEEP_SUMMARY) "Checking sleep…" else "Checking health…")
        queryJob = lifecycleScope.launch {
            if (!healthConnectManager.hasAnyPermission()) {
                val response = "Opening Health Connect permissions now."
                binding.responseText.text = response
                speakLocalResponse(response, token)
                openHealthConnectPermissions()
                return@launch
            }
            val response = when (command) {
                LocalCommandType.SLEEP_SUMMARY -> healthConnectManager.readSleepSummary()
                LocalCommandType.HEALTH_SNAPSHOT -> healthConnectManager.readHealthSnapshot()
                else -> healthConnectManager.readRecentHealthData()
            }
            if (token != interactionToken) return@launch
            binding.responseText.text = response
            speakLocalResponse(response, token)
        }
    }

    private fun openHealthConnectPermissions() {
        // Try the standard permission contract first
        try {
            requestHealthConnectPermissions.launch(healthConnectManager.allRequestablePermissions)
            return
        } catch (e: Exception) {
            Log.w(TAG, "Permission contract failed: ${e.message}")
        }

        // Fallback: open HC app permission management for our package directly
        val intents = listOf(
            Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS").apply {
                putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
            },
            Intent("android.health.connect.action.HEALTH_HOME_SETTINGS"),
            Intent().setClassName(
                "com.android.healthconnect.controller",
                "com.android.healthconnect.controller.HealthConnectActivity"
            )
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                Log.i(TAG, "Opened HC settings via ${intent.action ?: intent.component}")
                return
            } catch (e: Exception) {
                Log.w(TAG, "HC intent failed: ${intent.action ?: intent.component}: ${e.message}")
            }
        }
        Log.e(TAG, "Could not open Health Connect settings at all")
    }

    private fun requestHealthConnectIfNeeded() {
        if (!healthConnectManager.isAvailable()) {
            Log.i(TAG, "Health Connect SDK not available on this watch")
            return
        }
        lifecycleScope.launch {
            if (!healthConnectManager.hasAnyPermission()) {
                Log.i(TAG, "Requesting Health Connect permissions")
                openHealthConnectPermissions()
            } else {
                Log.i(TAG, "Health Connect permissions already granted")
            }
        }
    }

    private fun parseRoomMessageCommand(prompt: String): RoomMessageCommand? {
        val normalized = prompt.trim().replace(Regex("\\s+"), " ")

        val explicitMessagePatterns = listOf(
            Regex("(?i)^send (?:a )?message to (?:(?:the )?(?:room|team|family)|room )(?:(?<room>[a-z0-9._-]+) )?(?:saying |that )?(?<body>.+)$"),
            Regex("(?i)^post to (?:(?:the )?(?:room|team|family)|room )(?:(?<room>[a-z0-9._-]+) )?(?<body>.+)$"),
            Regex("(?i)^write to (?:(?:the )?(?:room|team|family)|room )(?:(?<room>[a-z0-9._-]+) )?(?<body>.+)$"),
            Regex("(?i)^tell (?:(?:the )?(?:room|team|family)) (?:(?<room>[a-z0-9._-]+) )?(?:that )?(?<body>.+)$"),
            Regex("(?i)^send to (?<room>[a-z0-9._-]+) (?<body>.+)$"),
            Regex("(?i)^post to (?<room>[a-z0-9._-]+) (?<body>.+)$"),
            Regex("(?i)^write to (?<room>[a-z0-9._-]+) (?<body>.+)$")
        )

        for (pattern in explicitMessagePatterns) {
            val match = pattern.matchEntire(normalized) ?: continue
            val body = match.groups["body"]?.value?.trim().orEmpty()
            if (body.isBlank()) continue
            val room = match.groups["room"]?.value?.trim()?.ifBlank { null }
            return RoomMessageCommand(room = room, body = sanitizeRoomMessageBody(body))
        }

        return null
    }

    private fun sanitizeRoomMessageBody(body: String): String {
        return body
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .trim()
    }

    private fun parseTimerCommand(prompt: String): TimerCommand? {
        val normalized = prompt.lowercase().replace('-', ' ')
        if (!normalized.contains("timer")) return null

        val match = Regex(
            "\\b((?:\\d+)|(?:a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty)(?:\\s+(?:one|two|three|four|five|six|seven|eight|nine))?)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)\\b"
        ).find(normalized) ?: return null

        val amount = parseSpokenNumber(match.groupValues[1]) ?: return null
        if (amount <= 0) return null

        val unit = match.groupValues[2]
        val multiplier = when {
            unit.startsWith("hour") || unit.startsWith("hr") -> 3600
            unit.startsWith("minute") || unit.startsWith("min") -> 60
            else -> 1
        }
        val totalSeconds = amount * multiplier
        if (totalSeconds <= 0) return null

        val normalizedUnit = when (multiplier) {
            3600 -> if (amount == 1) "hour" else "hours"
            60 -> if (amount == 1) "minute" else "minutes"
            else -> if (amount == 1) "second" else "seconds"
        }
        return TimerCommand(
            totalSeconds = totalSeconds,
            spokenDuration = "$amount $normalizedUnit"
        )
    }

    private fun parseSpokenNumber(raw: String): Int? {
        val text = raw.trim().lowercase()
        text.toIntOrNull()?.let { return it }
        if (text == "a" || text == "an") return 1

        val numberWords = mapOf(
            "one" to 1,
            "two" to 2,
            "three" to 3,
            "four" to 4,
            "five" to 5,
            "six" to 6,
            "seven" to 7,
            "eight" to 8,
            "nine" to 9,
            "ten" to 10,
            "eleven" to 11,
            "twelve" to 12,
            "thirteen" to 13,
            "fourteen" to 14,
            "fifteen" to 15,
            "sixteen" to 16,
            "seventeen" to 17,
            "eighteen" to 18,
            "nineteen" to 19,
            "twenty" to 20,
            "thirty" to 30,
            "forty" to 40,
            "fifty" to 50,
            "sixty" to 60
        )

        val parts = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.isEmpty() || parts.size > 2) return null
        if (parts.size == 1) return numberWords[parts[0]]

        val tens = numberWords[parts[0]] ?: return null
        val ones = numberWords[parts[1]] ?: return null
        if (tens !in setOf(20, 30, 40, 50, 60) || ones !in 1..9) return null
        return tens + ones
    }

    private fun launchSystemTimer(timer: TimerCommand, token: Int): Boolean {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, timer.totalSeconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_MESSAGE, "Set by ClawWatch")
        }
        if (intent.resolveActivity(packageManager) == null) {
            return false
        }

        return try {
            startActivity(intent)
            val confirmation = "Okay. I set a ${timer.spokenDuration} timer on the watch."
            speakLocalResponse(confirmation, token)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch system timer", e)
            false
        }
    }

    private fun launchVitalsCommand(command: LocalCommandType, token: Int) {
        val missing = requiredVitalsPermissions(command)
        if (missing.isNotEmpty()) {
            pendingVitalsCommand = PendingVitalsCommand(command, token)
            requestVitalsPermissions.launch(missing.toTypedArray())
            return
        }
        runVitalsCommand(command, token)
    }

    private fun requiredVitalsPermissions(command: LocalCommandType): List<String> {
        val permissions = mutableListOf<String>()
        if (command == LocalCommandType.HEART_RATE || command == LocalCommandType.VITALS_SNAPSHOT) {
            if (!hasPermission("android.permission.health.READ_HEART_RATE")) {
                permissions += "android.permission.health.READ_HEART_RATE"
            }
        }
        if (command == LocalCommandType.VITALS_SNAPSHOT && !hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)) {
            permissions += Manifest.permission.ACTIVITY_RECOGNITION
        }
        return permissions
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun runVitalsCommand(command: LocalCommandType, token: Int) {
        val canReadHeartRate = hasPermission("android.permission.health.READ_HEART_RATE")
        val canReadSteps = hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
        if (command == LocalCommandType.HEART_RATE && !canReadHeartRate) {
            speakLocalResponse("I need Health Connect permission to read your pulse history.", token)
            return
        }

        queryJob?.cancel()
        setState(State.THINKING)
        setStatus("Checking vitals…")
        queryJob = lifecycleScope.launch {
            val snapshot = vitalsReader.readSnapshot(
                batteryPercent = getBatteryPercentage(),
                canReadHeartRate = canReadHeartRate,
                canReadSteps = canReadSteps
            )
            if (token != interactionToken) return@launch

            val response = when (command) {
                LocalCommandType.VITALS_SNAPSHOT -> buildVitalsSummary(snapshot, canReadHeartRate, canReadSteps)
<<<<<<< HEAD
                LocalCommandType.HEART_RATE -> {
                    if (snapshot.heartRateBpm != null) {
                        "Your last recorded pulse is ${snapshot.heartRateBpm} beats per minute."
                    } else {
                        "I couldn't find a recent pulse reading in Health Connect."
                    }
                }
                LocalCommandType.FAMILY_STATUS -> "I couldn't check the family yet."
=======
                LocalCommandType.HEART_RATE -> buildHeartRateSummary(snapshot)
                else -> "I couldn't check the family yet."
>>>>>>> 6a89461 (feat(health): complete Health Connect integration with permission flow)
            }
            binding.responseText.text = response
            speakLocalResponse(response, token)
        }
    }

    private fun launchFamilyStatusCommand(token: Int) {
        queryJob?.cancel()
        setState(State.THINKING)
        setStatus("Checking the family…")
        queryJob = lifecycleScope.launch {
            val result = clawRunner.summarizeFamilyStatus()
            if (token != interactionToken) return@launch
            val response = result.getOrElse {
                "I couldn't check the family right now."
            }
            binding.responseText.text = response
            speakLocalResponse(response, token)
        }
    }

    private fun launchRoomMessageCommand(command: RoomMessageCommand, token: Int) {
        queryJob?.cancel()
        setState(State.THINKING)
        setStatus("Posting to the room…")
        queryJob = lifecycleScope.launch {
            val result = clawRunner.postRoomMessage(
                message = command.body,
                requestedRoom = command.room
            )
            if (token != interactionToken) return@launch
            val response = result.fold(
                onSuccess = { room ->
                    "Okay. I posted that in $room."
                },
                onFailure = {
                    it.message ?: "I couldn't post that right now."
                }
            )
            binding.responseText.text = response
            speakLocalResponse(response, token)
        }
    }

    private fun buildVitalsSummary(
        snapshot: VitalsReader.Snapshot,
        canReadHeartRate: Boolean,
        canReadSteps: Boolean
    ): String {
        val details = mutableListOf<String>()
        if (snapshot.heartRateBpm != null) {
            details += "Recent pulse was ${snapshot.heartRateBpm}."
        } else if (!canReadHeartRate) {
            details += "Pulse history is unavailable without permission."
        }
        snapshot.ambientLux?.let { lux ->
            details += when {
                lux < 10f -> "Light around you is dim."
                lux < 400f -> "Light around you is moderate."
                else -> "Light around you is bright."
            }
        }
        snapshot.pressureHpa?.let { pressure ->
            details += "Air pressure is ${pressure.toInt()} hectopascals."
        }
        if (snapshot.stepCount != null) {
            details += "The watch step counter reads ${snapshot.stepCount}."
        } else if (!canReadSteps) {
            details += "Step count is unavailable until you allow activity access."
        }
        details += describeRecoveryState(snapshot, snapshot.heartRateBpm)
        return details.joinToString(" ").take(320)
    }

    private fun describeRecoveryState(snapshot: VitalsReader.Snapshot, bpm: Int?): String {
        return when {
            bpm != null && bpm >= 125 -> "Looks like you've been exercising hard recently."
            bpm != null && bpm >= 95 -> "You've been active and warmed up."

            snapshot.motionLevel == VitalsReader.MotionLevel.ACTIVE -> "Looks like you're getting good exercise right now."
            snapshot.motionLevel == VitalsReader.MotionLevel.LIGHT -> "All good, you seem lightly active."
            else -> "All good, you seem to be resting."
        }
    }

    private fun speakLocalResponse(message: String, token: Int) {
        val plan = buildSpeechRenderPlan(message)
        setState(State.SPEAKING)
        startSpeakingPreview(plan.previewText)
        gestureAnimator.animateSpeech(
            binding.avatarView,
            plan.previewText,
            plan.gestureEnergy,
            plan.mood
        )
        voiceEngine.speak(
            plan.spokenText,
            speechRate = plan.speechRate,
            pitch = plan.pitch,
            onStart = {
                runOnUiThread {
                    speechAudioStarted = true
                    updateStatusBubbleStyle()
                }
            },
            onDone = {
            runOnUiThread {
                if (token == interactionToken && state == State.SPEAKING) {
                    speechAudioStarted = false
                    gestureAnimator.stop()
                    stopSpeakingPreview()
                    startListening(autoWindow = true)
                }
            }
        })
    }

    private fun askClaw(prompt: String, token: Int) {
        val searchLikely = shouldShowSearching(prompt)
        setState(if (searchLikely) State.SEARCHING else State.THINKING)
        setStatus(if (searchLikely) "Searching…" else "Thinking…")

        queryJob = lifecycleScope.launch {
            val result = clawRunner.query(prompt)
            if (token != interactionToken) return@launch
            result.fold(
                onSuccess = { response ->
                    if (token != interactionToken) return@fold
                    val plan = buildSpeechRenderPlan(response)
                    binding.responseText.text = response
                    setState(State.SPEAKING)
                    startSpeakingPreview(plan.previewText)
                    gestureAnimator.animateSpeech(
                        binding.avatarView,
                        plan.previewText,
                        plan.gestureEnergy,
                        plan.mood
                    )
                    voiceEngine.speak(
                        plan.spokenText,
                        speechRate = plan.speechRate,
                        pitch = plan.pitch,
                        onStart = {
                            runOnUiThread {
                                speechAudioStarted = true
                                updateStatusBubbleStyle()
                            }
                        },
                        onDone = {
                        runOnUiThread {
                            if (token == interactionToken && state == State.SPEAKING) {
                                speechAudioStarted = false
                                gestureAnimator.stop()
                                stopSpeakingPreview()
                                startListening(autoWindow = true)
                            }
                        }
                    })
                },
                onFailure = { err ->
                    if (token != interactionToken) return@fold
                    stopSpeakingPreview()
                    val msg = err.message ?: "Error"
                    setStatus(msg)
                    setState(State.ERROR)
                    voiceEngine.speak("Sorry, $msg")
                }
            )
        }
    }

    private fun shouldShowSearching(prompt: String): Boolean {
        val ragMode = prefs.getString(PREF_RAG_MODE, "kotlin") ?: "kotlin"
        if (ragMode == "off") return false
        val p = prompt.lowercase()
        val liveHints = listOf(
            "today", "latest", "weather", "news", "price", "score", "who won",
            "stock", "open now", "current", "live", "result"
        )
        return liveHints.any { p.contains(it) }
    }

    private fun currentAvatarType(): AvatarType {
        return when ((prefs.getString(PREF_AVATAR_TYPE, "lobster") ?: "lobster").lowercase()) {
            "lobster" -> AvatarType.LOBSTER
            "orange_lobster" -> AvatarType.ORANGE_LOBSTER
            "robot" -> AvatarType.ROBOT
            "boy" -> AvatarType.BOY
            "girl" -> AvatarType.GIRL
            "ant" -> AvatarType.ANT
            else -> AvatarType.LOBSTER
        }
    }

    private fun setupAvatarSwipeSwitch() {
        val threshold = AVATAR_SWIPE_THRESHOLD_DP * resources.displayMetrics.density
        binding.avatarContainer.isClickable = true
        binding.avatarContainer.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    avatarSwipeStartX = event.x
                    avatarSwipeStartY = event.y
                    avatarTouchStartAt = SystemClock.elapsedRealtime()
                    avatarSwipeActive = true
                    view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!avatarSwipeActive) return@setOnTouchListener false
                    val dx = event.x - avatarSwipeStartX
                    val dy = event.y - avatarSwipeStartY
                    if (abs(dx) > threshold / 2 && abs(dx) > abs(dy)) {
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!avatarSwipeActive) return@setOnTouchListener false
                    avatarSwipeActive = false
                    val dx = event.x - avatarSwipeStartX
                    val dy = event.y - avatarSwipeStartY
                    val heldLong = (SystemClock.elapsedRealtime() - avatarTouchStartAt) >=
                        ViewConfiguration.getLongPressTimeout()
                    val mostlyStill = abs(dx) < threshold / 2 && abs(dy) < threshold / 2
                    if (heldLong && mostlyStill) {
                        showOptionsMenu()
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        return@setOnTouchListener true
                    }
                    if (abs(dx) > threshold && abs(dx) > abs(dy) * 1.2f) {
                        if (dx > 0) finish() else nextAvatarType()
                    }
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    avatarSwipeActive = false
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }
                else -> true
            }
        }
        updateAvatarDrawable(state)
    }

    private fun isLiveTextEnabled(): Boolean =
        prefs.getBoolean(PREF_LIVE_TEXT_ENABLED, false)

    private fun areStatusBubblesEnabled(): Boolean =
        prefs.getBoolean(PREF_STATUS_BUBBLES_ENABLED, true)

    private fun shouldShowStatusOverlay(): Boolean {
        if (state == State.SETUP) return false
        if (isLiveTextEnabled()) return false
        return when (state) {
            State.LISTENING, State.THINKING, State.SEARCHING, State.SPEAKING, State.ERROR -> true
            else -> false
        }
    }

    private fun showOptionsMenu() {
        val options = arrayOf(
            "Live text",
            "Thought / speech bubbles"
        )
        val checked = booleanArrayOf(
            isLiveTextEnabled(),
            areStatusBubblesEnabled()
        )
        AlertDialog.Builder(this)
            .setTitle("Options")
            .setMultiChoiceItems(options, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Apply") { _, _ ->
                prefs.edit()
                    .putBoolean(PREF_LIVE_TEXT_ENABLED, checked[0])
                    .putBoolean(PREF_STATUS_BUBBLES_ENABLED, checked[1])
                    .apply()
                applyLiveTextVisibility()
                refreshStatusPresentation()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyLiveTextVisibility() {
        val show = shouldShowStatusOverlay()
        binding.statusText.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            refreshStatusPresentation()
        }
    }

    private fun avatarTypeKey(type: AvatarType): String = when (type) {
        AvatarType.ANT -> "ant"
        AvatarType.LOBSTER -> "lobster"
        AvatarType.ORANGE_LOBSTER -> "orange_lobster"
        AvatarType.ROBOT -> "robot"
        AvatarType.BOY -> "boy"
        AvatarType.GIRL -> "girl"
    }

    private fun nextAvatarType() = rotateAvatarBy(+1)

    private fun rotateAvatarBy(step: Int) {
        val all = AvatarType.entries
        val current = currentAvatarType()
        val currentIndex = all.indexOf(current).takeIf { it >= 0 } ?: 0
        val nextIndex = (currentIndex + step + all.size) % all.size
        val next = all[nextIndex]
        prefs.edit().putString(PREF_AVATAR_TYPE, avatarTypeKey(next)).apply()
        updateAvatarDrawable(state)
    }

    private fun avatarStateFor(s: State): AvatarState = when (s) {
        State.SETUP, State.IDLE -> AvatarState.IDLE
        State.LISTENING -> AvatarState.LISTENING
        State.THINKING -> AvatarState.THINKING
        State.SEARCHING -> AvatarState.SEARCHING
        State.SPEAKING -> AvatarState.SPEAKING
        State.ERROR -> AvatarState.ERROR
    }

    private fun getBatteryPercentage(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 100
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level <= 0 || scale <= 0) return 100
        return (level * 100 / scale)
    }

    private fun isLowBattery(): Boolean {
        return getBatteryPercentage() < 20
    }

    private fun startAutoListenWindow(token: Int) {
        binding.autoListenCountdown.visibility = View.VISIBLE
        binding.autoListenCountdown.progress = 100
        countdownAnimator?.cancel()
        countdownAnimator = ValueAnimator.ofInt(100, 0).apply {
            duration = AUTO_LISTEN_WINDOW_MS
            addUpdateListener {
                binding.autoListenCountdown.setProgressCompat(it.animatedValue as Int, false)
            }
            start()
        }
        autoListenTimeoutJob = lifecycleScope.launch {
            delay(AUTO_LISTEN_WINDOW_MS)
            if (token == interactionToken && state == State.LISTENING && isAutoListenWindow) {
                interactionToken++
                voiceEngine.stopListening()
                setState(State.IDLE)
                setStatus("Tap to talk")
            }
        }
    }

    private fun cancelAutoListenWindow() {
        isAutoListenWindow = false
        autoListenTimeoutJob?.cancel()
        autoListenTimeoutJob = null
        countdownAnimator?.cancel()
        countdownAnimator = null
        binding.autoListenCountdown.visibility = View.GONE
    }

    private fun startSpeakingPreview(fullText: String) {
        stopSpeakingPreview()
        val clean = formatStatus(fullText)
        if (clean.isBlank()) {
            setStatus("Speaking…")
            return
        }
        if (isLowBattery()) {
            setStatus(clean)
            return
        }
        speakingPreviewJob = lifecycleScope.launch {
            var count = 0
            while (count < clean.length && state == State.SPEAKING) {
                count = minOf(clean.length, count + 8)
                setStatus(clean.substring(0, count))
                delay(250L)
            }
            if (state == State.SPEAKING) setStatus(clean)
        }
    }

    private fun stopSpeakingPreview() {
        speakingPreviewJob?.cancel()
        speakingPreviewJob = null
        gestureAnimator.stop()
    }

    private fun applyDayPhaseAppearance(snapshot: DayPhaseManager.Snapshot) {
        runOnUiThread {
            binding.avatarMoodGlow.backgroundTintList = ColorStateList.valueOf(snapshot.color)
            binding.avatarMoodGlow.alpha = snapshot.alpha
            binding.fab.backgroundTintList = ColorStateList.valueOf(snapshot.color)
        }
    }

    private fun setState(s: State) {
        state = s
        intentAdapter?.onStateChanged(
            state = s.name.lowercase(),
            screenActive = true,
            batteryPct = getBatteryPercentage(),
            lowBattery = isLowBattery()
        )
        runOnUiThread {
            if (s != State.SPEAKING) {
                speechAudioStarted = false
            }
            if (s != State.LISTENING) {
                binding.avatarContainer.scaleX = 1f
                binding.avatarContainer.scaleY = 1f
            }
            applyConversationScreenPolicy(s)
            binding.splashPanel.visibility = View.GONE
            binding.setupPanel.visibility  = if (s == State.SETUP)    View.VISIBLE else View.GONE
            binding.mainPanel.visibility   = if (s != State.SETUP)    View.VISIBLE else View.GONE
            applyLiveTextVisibility()
            updateAvatarDrawable(s)
            binding.fab.contentDescription = when (s) {
                State.IDLE      -> "Tap to talk"
                State.LISTENING -> "Tap to stop"
                State.THINKING  -> "Thinking"
                State.SEARCHING -> "Searching"
                State.SPEAKING  -> "Tap to stop"
                State.ERROR     -> "Tap to retry"
                State.SETUP     -> ""
            }
            binding.fab.isEnabled = s != State.SETUP
            binding.fab.text = when (s) {
                State.IDLE -> "◉"
                State.LISTENING -> "■"
                State.THINKING, State.SEARCHING, State.SPEAKING -> "■"
                State.ERROR -> "↻"
                State.SETUP -> ""
            }
        }
    }


    private fun applyConversationScreenPolicy(state: State) {
        val keepOn = when (state) {
            State.LISTENING, State.THINKING, State.SEARCHING, State.SPEAKING -> true
            else -> false
        }
        binding.root.keepScreenOn = keepOn
        if (keepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun formatStatus(msg: String): String =
        msg.trim().replace(Regex("\\s+"), " ").take(160)

    private fun buildSpeechRenderPlan(rawText: String): SpeechRenderPlan {
        val stageDirections = Regex("\\*([^*]{1,40})\\*")
            .findAll(rawText)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim()?.lowercase() }
            .toList()

        val spoken = rawText
            .replace(Regex("\\*[^*]{1,40}\\*"), " ")
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([,.!?;:])"), "$1")
            .trim()
            .ifBlank { "..." }

        var speechRate = 1.1f
        var pitch = 1.0f
        var gestureEnergy = 1.0f
        var mood = GestureAnimator.Mood.NEUTRAL
        val lowerSpoken = spoken.lowercase()
        val exclamationCount = spoken.count { it == '!' }
        val questionCount = spoken.count { it == '?' }

        for (direction in stageDirections) {
            when {
                listOf("cheerfully", "warmly", "brightly", "happily").any { direction.contains(it) } -> {
                    speechRate += 0.03f
                    pitch += 0.05f
                    gestureEnergy += 0.12f
                    if (mood == GestureAnimator.Mood.NEUTRAL) {
                        mood = GestureAnimator.Mood.CHEERFUL
                    }
                }
                listOf("excited", "enthusiastic", "energetic").any { direction.contains(it) } -> {
                    speechRate += 0.06f
                    pitch += 0.03f
                    gestureEnergy += 0.18f
                    mood = GestureAnimator.Mood.EXCITED
                }
                listOf("chuckles", "laughs", "smiles", "grins").any { direction.contains(it) } -> {
                    speechRate += 0.02f
                    pitch += 0.02f
                    gestureEnergy += 0.14f
                    if (mood != GestureAnimator.Mood.EXCITED) {
                        mood = GestureAnimator.Mood.PLAYFUL
                    }
                }
                listOf("calmly", "softly", "gently", "quietly").any { direction.contains(it) } -> {
                    speechRate -= 0.08f
                    pitch -= 0.02f
                    gestureEnergy -= 0.12f
                    if (mood == GestureAnimator.Mood.NEUTRAL) {
                        mood = GestureAnimator.Mood.CALM
                    }
                }
                listOf("serious", "firmly", "flatly").any { direction.contains(it) } -> {
                    speechRate -= 0.03f
                    pitch -= 0.06f
                    gestureEnergy += 0.04f
                    mood = GestureAnimator.Mood.SERIOUS
                }
            }
        }

        if (mood == GestureAnimator.Mood.NEUTRAL) {
            mood = when {
                listOf("great", "glad", "nice", "wonderful", "amazing", "awesome", "love", "perfect", "fantastic").any { lowerSpoken.contains(it) } ->
                    GestureAnimator.Mood.CHEERFUL
                listOf("haha", "funny", "maybe", "perhaps", "curious", "hmm", "interesting").any { lowerSpoken.contains(it) } || questionCount > 0 ->
                    GestureAnimator.Mood.PLAYFUL
                listOf("sorry", "cannot", "can't", "failed", "error", "need", "must", "important", "careful", "warning").any { lowerSpoken.contains(it) } ->
                    GestureAnimator.Mood.SERIOUS
                listOf("calm", "rest", "steady", "gentle", "soft", "quiet", "okay").any { lowerSpoken.contains(it) } ->
                    GestureAnimator.Mood.CALM
                exclamationCount >= 2 ->
                    GestureAnimator.Mood.EXCITED
                exclamationCount == 1 ->
                    GestureAnimator.Mood.CHEERFUL
                else -> GestureAnimator.Mood.NEUTRAL
            }
        }

        when (mood) {
            GestureAnimator.Mood.EXCITED -> {
                speechRate += 0.04f
                pitch += 0.02f
                gestureEnergy += 0.18f
            }
            GestureAnimator.Mood.CHEERFUL -> {
                speechRate += 0.02f
                pitch += 0.03f
                gestureEnergy += 0.12f
            }
            GestureAnimator.Mood.PLAYFUL -> {
                speechRate += 0.01f
                pitch += 0.02f
                gestureEnergy += 0.15f
            }
            GestureAnimator.Mood.CALM -> {
                speechRate -= 0.06f
                pitch -= 0.01f
                gestureEnergy -= 0.08f
            }
            GestureAnimator.Mood.SERIOUS -> {
                speechRate -= 0.02f
                pitch -= 0.04f
                gestureEnergy += 0.06f
            }
            GestureAnimator.Mood.NEUTRAL -> {
                if (exclamationCount > 0) {
                    gestureEnergy += 0.08f
                }
            }
        }

        return SpeechRenderPlan(
            spokenText = spoken,
            previewText = spoken,
            speechRate = speechRate.coerceIn(0.9f, 1.2f),
            pitch = pitch.coerceIn(0.9f, 1.15f),
            gestureEnergy = gestureEnergy.coerceIn(0.8f, 1.6f),
            mood = mood
        )
    }

    private fun refreshStatusPresentation() {
        val text = binding.statusText.text?.toString().orEmpty()
        if (!shouldShowStatusOverlay()) {
            binding.statusText.visibility = View.GONE
            return
        }
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.bringToFront()
        binding.statusText.text = text
        updateStatusBubbleStyle()
    }

    private fun updateStatusBubbleStyle() {
        val useBubbles = areStatusBubblesEnabled()
        val useGraphicNovelBubble = useBubbles && when (state) {
            State.LISTENING, State.THINKING, State.SEARCHING, State.SPEAKING -> true
            else -> false
        }
        val useThoughtBubble = useGraphicNovelBubble && (
            state == State.LISTENING ||
                state == State.THINKING ||
                state == State.SEARCHING ||
                (state == State.SPEAKING && !speechAudioStarted)
            )
        val backgroundRes = if (!useGraphicNovelBubble) {
            R.drawable.live_text_bg
        } else {
            when (state) {
                State.SPEAKING.takeIf { !speechAudioStarted } -> R.drawable.thought_bubble_bg
                State.LISTENING, State.THINKING, State.SEARCHING -> R.drawable.thought_bubble_bg
                State.SPEAKING -> R.drawable.speech_bubble_bg
                else -> R.drawable.live_text_bg
            }
        }
        binding.statusText.setBackgroundResource(backgroundRes)
        binding.statusText.bringToFront()
        binding.statusText.maxLines = when {
            !useGraphicNovelBubble -> 1
            state == State.SPEAKING && speechAudioStarted -> 3
            else -> 4
        }
        binding.statusText.ellipsize = if (useGraphicNovelBubble) null else android.text.TextUtils.TruncateAt.END
        binding.statusText.setTextColor(if (useGraphicNovelBubble) 0xFF121212.toInt() else ACCENT_COLOR)
        binding.statusText.setTypeface(null, if (useGraphicNovelBubble) Typeface.BOLD else Typeface.NORMAL)
        binding.statusText.setLineSpacing(
            if (useGraphicNovelBubble) resources.displayMetrics.density * 1.5f else 0f,
            1.0f
        )
        binding.statusText.gravity = when {
            !useGraphicNovelBubble -> Gravity.CENTER
            state == State.SPEAKING && speechAudioStarted -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            else -> Gravity.CENTER
        }
        val width = if (useGraphicNovelBubble) {
            (resources.displayMetrics.density *
                if (state == State.SPEAKING && speechAudioStarted) 172 else 168).toInt()
        } else {
            (resources.displayMetrics.density * 140).toInt()
        }
        binding.statusText.layoutParams = binding.statusText.layoutParams.apply {
            this.width = width
        }
        val horizontalPadding = if (useGraphicNovelBubble) {
            (resources.displayMetrics.density * 12).toInt()
        } else {
            (resources.displayMetrics.density * 8).toInt()
        }
        val topPadding = when {
            !useGraphicNovelBubble -> binding.statusText.paddingTop
            state == State.SPEAKING && speechAudioStarted -> (resources.displayMetrics.density * 14).toInt()
            useThoughtBubble -> (resources.displayMetrics.density * 8).toInt()
            else -> (resources.displayMetrics.density * 7).toInt()
        }
        val bottomPadding = when {
            !useGraphicNovelBubble -> binding.statusText.paddingBottom
            state == State.SPEAKING && speechAudioStarted -> (resources.displayMetrics.density * 18).toInt()
            useThoughtBubble -> (resources.displayMetrics.density * 20).toInt()
            else -> (resources.displayMetrics.density * 8).toInt()
        }
        binding.statusText.minHeight = when {
            useThoughtBubble -> (resources.displayMetrics.density * 64).toInt()
            state == State.SPEAKING && speechAudioStarted -> (resources.displayMetrics.density * 58).toInt()
            useGraphicNovelBubble -> (resources.displayMetrics.density * 44).toInt()
            else -> (resources.displayMetrics.density * 24).toInt()
        }
        binding.statusText.setPadding(
            horizontalPadding,
            topPadding,
            horizontalPadding,
            bottomPadding
        )
    }

    private fun setStatus(msg: String) = runOnUiThread {
        if (!shouldShowStatusOverlay()) {
            binding.statusText.visibility = View.GONE
            return@runOnUiThread
        }
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = formatStatus(msg)
        updateStatusBubbleStyle()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelAutoListenWindow()
        avatarAnimator?.cancel()
        stopSpeakingPreview()
        queryJob?.cancel()
        voiceEngine.release()
    }
}
