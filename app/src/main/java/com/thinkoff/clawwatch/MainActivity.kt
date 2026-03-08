package com.thinkoff.clawwatch

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.AlarmClock
import android.util.Log
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
        private const val PERMISSION_HEART_RATE = "android.permission.health.READ_HEART_RATE"
        private const val PREF_RAG_MODE = "rag_mode"
        private const val PREF_AVATAR_TYPE = "avatar_type"
        private const val PREF_LIVE_TEXT_ENABLED = "live_text_enabled"
        private const val ACCENT_COLOR = 0xFFD4A5E9.toInt()
        private const val LOW_BATTERY_COLOR = 0xFF9CA3AF.toInt()
        private val AVATARS = listOf("ant", "lobster", "orange_lobster", "robot", "boy", "girl")
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var clawRunner: ClawRunner
    private lateinit var voiceEngine: VoiceEngine
    private lateinit var dayPhaseManager: DayPhaseManager
    private lateinit var vitalsReader: VitalsReader
    private val prefs by lazy { SecurePrefs.watch(this) }

    private enum class State { SETUP, IDLE, LISTENING, THINKING, SEARCHING, SPEAKING, ERROR }
    private enum class AvatarType { ANT, LOBSTER, ORANGE_LOBSTER, ROBOT, BOY, GIRL }
    private enum class AvatarState { IDLE, LISTENING, THINKING, SEARCHING, SPEAKING, ERROR }
    private enum class LocalCommandType { VITALS_SNAPSHOT, HEART_RATE, FAMILY_STATUS }
    private data class TimerCommand(
        val totalSeconds: Int,
        val spokenDuration: String
    )
    private data class PendingVitalsCommand(
        val type: LocalCommandType,
        val token: Int
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
    private var listeningPulseAnimator: ValueAnimator? = null
    private var speakingPreviewJob: Job? = null
    private var lastPartialStatusAt = 0L
    private var avatarSwipeStartX = 0f
    private var avatarSwipeStartY = 0f
    private var avatarSwipeActive = false
    private var avatarTouchStartAt = 0L
    private var pendingVitalsCommand: PendingVitalsCommand? = null

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startListening() else setStatus("Mic permission needed") }

    private val requestVitalsPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val pending = pendingVitalsCommand ?: return@registerForActivityResult
        pendingVitalsCommand = null
        runVitalsCommand(pending.type, pending.token)
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

        clawRunner = ClawRunner(this)
        voiceEngine = VoiceEngine(this)
        dayPhaseManager = DayPhaseManager(this)
        vitalsReader = VitalsReader(this)
        
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

        lifecycleScope.launch { initialise() }
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
                
                // Only animate when the user is likely looking at the watch:
                // active conversation states, not passive idle loops.
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
            },
            onError = { err ->
                Log.w(TAG, "Vosk not ready: $err")
                setState(State.IDLE)
                setStatus("Tap to talk")
            }
        )
    }

    private fun onSaveKey() { /* key set via ADB, not on-watch */ }

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
                    pulseListeningAvatar()
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
        if (!normalized.contains("family")) return false
        return normalized.contains("going on") ||
            normalized.contains("what's") ||
            normalized.contains("what is") ||
            normalized.contains("how's") ||
            normalized.contains("how is") ||
            normalized.contains("status") ||
            normalized.contains("update")
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
            if (!hasPermission(PERMISSION_HEART_RATE)) {
                permissions += PERMISSION_HEART_RATE
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
        val canReadHeartRate = hasPermission(PERMISSION_HEART_RATE)
        val canReadSteps = hasPermission(Manifest.permission.ACTIVITY_RECOGNITION)
        if (command == LocalCommandType.HEART_RATE && !canReadHeartRate) {
            speakLocalResponse("I need heart-rate permission before I can read your pulse.", token)
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
                LocalCommandType.HEART_RATE -> buildHeartRateSummary(snapshot)
                LocalCommandType.FAMILY_STATUS -> "I couldn't check the family yet."
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

    private fun buildHeartRateSummary(snapshot: VitalsReader.Snapshot): String {
        val bpm = snapshot.heartRateBpm
            ?: return "I couldn't get a clean pulse reading just now. Keep the watch snug and hold still for a moment, then ask again."
        return "Your pulse is $bpm beats per minute. ${describeRecoveryState(snapshot, bpm)}"
    }

    private fun buildVitalsSummary(
        snapshot: VitalsReader.Snapshot,
        canReadHeartRate: Boolean,
        canReadSteps: Boolean
    ): String {
        val details = mutableListOf<String>()
        if (snapshot.heartRateBpm != null) {
            details += "Pulse ${snapshot.heartRateBpm} beats per minute."
        } else if (!canReadHeartRate) {
            details += "Pulse is unavailable until you allow heart-rate access."
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
            bpm != null && bpm >= 125 -> "Looks like you're exercising pretty hard right now."
            bpm != null && bpm >= 95 -> "You seem active and warmed up."
            snapshot.motionLevel == VitalsReader.MotionLevel.ACTIVE -> "Looks like you're getting good exercise right now."
            snapshot.motionLevel == VitalsReader.MotionLevel.LIGHT -> "All good, you seem lightly active."
            else -> "All good, you seem to be resting."
        }
    }

    private fun speakLocalResponse(message: String, token: Int) {
        setState(State.SPEAKING)
        startSpeakingPreview(message)
        voiceEngine.speak(message) {
            runOnUiThread {
                if (token == interactionToken) {
                    stopSpeakingPreview()
                    setState(State.IDLE)
                    setStatus("Tap to talk")
                }
            }
        }
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
                    binding.responseText.text = response
                    setState(State.SPEAKING)
                    startSpeakingPreview(response)
                    voiceEngine.speak(response) {
                        runOnUiThread {
                            if (token == interactionToken && state == State.SPEAKING) {
                                startListening(autoWindow = true)
                            }
                        }
                    }
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

    private fun showOptionsMenu() {
        val options = arrayOf("Demo live text: Off", "Demo live text: On")
        val selected = if (isLiveTextEnabled()) 1 else 0
        AlertDialog.Builder(this)
            .setTitle("Options")
            .setSingleChoiceItems(options, selected) { dialog, which ->
                prefs.edit().putBoolean(PREF_LIVE_TEXT_ENABLED, which == 1).apply()
                applyLiveTextVisibility()
                if (which == 1) {
                    setStatus(when (state) {
                        State.IDLE -> "Tap to talk"
                        State.LISTENING -> "Listening…"
                        State.THINKING -> "Thinking…"
                        State.SEARCHING -> "Searching…"
                        State.SPEAKING -> "Speaking…"
                        State.ERROR -> "Error"
                        State.SETUP -> "API key missing"
                    })
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyLiveTextVisibility() {
        val show = isLiveTextEnabled() && state != State.SETUP
        binding.statusText.visibility = if (show) View.VISIBLE else View.GONE
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
    }

    private fun applyDayPhaseAppearance(snapshot: DayPhaseManager.Snapshot) {
        runOnUiThread {
            binding.avatarMoodGlow.backgroundTintList = ColorStateList.valueOf(snapshot.color)
            binding.avatarMoodGlow.alpha = snapshot.alpha
            binding.fab.backgroundTintList = ColorStateList.valueOf(snapshot.color)
        }
    }

    private fun pulseListeningAvatar() {
        if (state != State.LISTENING || isLowBattery()) return
        listeningPulseAnimator?.cancel()
        listeningPulseAnimator = ValueAnimator.ofFloat(1f, 1.045f, 1f).apply {
            duration = 220L
            addUpdateListener { animator ->
                val scale = animator.animatedValue as Float
                binding.avatarContainer.scaleX = scale
                binding.avatarContainer.scaleY = scale
            }
            start()
        }
    }

    private fun setState(s: State) {
        state = s
        runOnUiThread {
            if (s != State.LISTENING) {
                listeningPulseAnimator?.cancel()
                listeningPulseAnimator = null
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

    private fun setStatus(msg: String) = runOnUiThread {
        if (!isLiveTextEnabled() || state == State.SETUP) {
            binding.statusText.visibility = View.GONE
            return@runOnUiThread
        }
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = formatStatus(msg)
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
