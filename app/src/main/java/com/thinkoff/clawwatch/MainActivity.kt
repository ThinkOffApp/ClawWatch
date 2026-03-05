package com.thinkoff.clawwatch

import android.Manifest
import android.animation.ValueAnimator
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.ViewConfiguration
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.GestureDetector
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
        private const val PREF_RAG_MODE = "rag_mode"
        private const val PREF_AVATAR_TYPE = "avatar_type"
        private const val PREF_LIVE_TEXT_ENABLED = "live_text_enabled"
        private const val ACCENT_COLOR = 0xFFD4A5E9.toInt()
        private const val LOW_BATTERY_COLOR = 0xFF9CA3AF.toInt()
        private val AVATARS = listOf("ant", "lobster", "robot", "boy", "girl")
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var clawRunner: ClawRunner
    private lateinit var voiceEngine: VoiceEngine
    private val prefs by lazy { SecurePrefs.watch(this) }

    private enum class State { SETUP, IDLE, LISTENING, THINKING, SEARCHING, SPEAKING, ERROR }
    private enum class AvatarType { ANT, LOBSTER, ROBOT, BOY, GIRL }
    private enum class AvatarState { IDLE, LISTENING, THINKING, SEARCHING, SPEAKING, ERROR }
    private lateinit var gestureDetector: GestureDetector
    private var currentAvatarIndex = 0

    private var state = State.SETUP
    private var queryJob: Job? = null
    private var autoListenTimeoutJob: Job? = null
    private var interactionToken = 0
    private var lastFabTapAt = 0L
    private var isAutoListenWindow = false
    private var countdownAnimator: ValueAnimator? = null
    private var avatarAnimator: ValueAnimator? = null
    private var speakingPreviewJob: Job? = null
    private var lastPartialStatusAt = 0L
    private var avatarSwipeStartX = 0f
    private var avatarSwipeStartY = 0f
    private var avatarSwipeActive = false
    private var avatarTouchStartAt = 0L

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startListening() else setStatus("Mic permission needed") }

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

        lifecycleScope.launch { initialise() }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun updateAvatarDrawable(s: State) {
        if (s == State.SETUP) return

        val charName = AVATARS[currentAvatarIndex]
        val stateName = when(s) {
            State.IDLE -> "idle"
            State.LISTENING -> "listening"
            State.THINKING -> "thinking"
            State.SPEAKING -> "speaking"
            else -> "idle"
        }

        val resName = "avd_avatar_${charName}_${stateName}"
        val resId = resources.getIdentifier(resName, "drawable", packageName)

        if (resId != 0) {
            val drawable = ContextCompat.getDrawable(this, resId) as? android.graphics.drawable.AnimatedVectorDrawable
            binding.avatarView.setImageDrawable(drawable)
            drawable?.start()
        }
    }

    private suspend fun initialise() {
        setStatus("Starting…")
        clawRunner.ensureInstalled()
        voiceEngine.initTts()
        updateAvatar(State.IDLE)

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
        updateAvatar(state)
    }

    private fun avatarStateFor(s: State): AvatarState = when (s) {
        State.SETUP, State.IDLE -> AvatarState.IDLE
        State.LISTENING -> AvatarState.LISTENING
        State.THINKING -> AvatarState.THINKING
        State.SEARCHING -> AvatarState.SEARCHING
        State.SPEAKING -> AvatarState.SPEAKING
        State.ERROR -> AvatarState.ERROR
    }

    private fun isLowBattery(): Boolean {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level <= 0 || scale <= 0) return false
        return (level * 100 / scale) < 20
    }

    private fun updateAvatar(s: State) {
        val aState = avatarStateFor(s)
        
        if (isLowBattery()) {
            stopAvatarAnimation()
            return
        }
        startAvatarAnimation(aState)
    }

    private fun startAvatarAnimation(state: AvatarState) {
        stopAvatarAnimation()
        when (state) {
            AvatarState.LISTENING -> {
                avatarAnimator = ValueAnimator.ofFloat(1.0f, 1.08f, 1.0f).apply {
                    duration = 850L
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener {
                        // binding.avatarFace.scaleX = v
                        // binding.avatarFace.scaleY = v
                    }
                    start()
                }
            }
            AvatarState.THINKING, AvatarState.SEARCHING -> {
                avatarAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1200L
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener {
                        // binding.avatarFace.rotation = -6f + 12f * p
                    }
                    start()
                }
            }
            AvatarState.SPEAKING -> {
                avatarAnimator = ValueAnimator.ofFloat(1.0f, 1.05f, 1.0f).apply {
                    duration = 480L
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener {
                        // binding.avatarFace.scaleX = v
                        // binding.avatarFace.scaleY = v
                    }
                    start()
                }
            }
            AvatarState.ERROR -> {
                avatarAnimator = ValueAnimator.ofFloat(1.0f, 0.75f, 1.0f).apply {
                    duration = 700L
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { binding.avatarFace.alpha = it.animatedValue as Float }
                    start()
                }
            }
            AvatarState.IDLE -> stopAvatarAnimation()
        }
    }

    private fun stopAvatarAnimation() {
        avatarAnimator?.cancel()
        avatarAnimator = null
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

    private fun setState(s: State) {
        state = s
        runOnUiThread {
            applyConversationScreenPolicy(s)
            binding.splashPanel.visibility = View.GONE
            binding.setupPanel.visibility  = if (s == State.SETUP)    View.VISIBLE else View.GONE
            binding.mainPanel.visibility   = if (s != State.SETUP)    View.VISIBLE else View.GONE
            applyLiveTextVisibility()
            updateAvatar(s)
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
        stopAvatarAnimation()
        stopSpeakingPreview()
        queryJob?.cancel()
        voiceEngine.release()
    }
}
