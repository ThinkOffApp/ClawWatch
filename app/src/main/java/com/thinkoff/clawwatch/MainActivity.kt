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
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.thinkoff.clawwatch.databinding.ActivityMainBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        private const val PREF_RAG_MODE = "rag_mode"
        private const val PREF_AVATAR_TYPE = "avatar_type"
        private const val ACCENT_COLOR = 0xFFD4A5E9.toInt()
        private const val LOW_BATTERY_COLOR = 0xFF9CA3AF.toInt()
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var clawRunner: ClawRunner
    private lateinit var voiceEngine: VoiceEngine
    private val prefs by lazy { SecurePrefs.watch(this) }

    private enum class State { SETUP, IDLE, LISTENING, THINKING, SEARCHING, SPEAKING, ERROR }
    private enum class AvatarType { ANT, LOBSTER, ROBOT, BOY, GIRL }
    private enum class AvatarState { IDLE, LISTENING, THINKING, SEARCHING, SPEAKING, ERROR }

    private var state = State.SETUP
    private var queryJob: Job? = null
    private var autoListenTimeoutJob: Job? = null
    private var interactionToken = 0
    private var lastFabTapAt = 0L
    private var isAutoListenWindow = false
    private var countdownAnimator: ValueAnimator? = null
    private var avatarAnimator: ValueAnimator? = null

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startListening() else setStatus("Mic permission needed") }

    private val avatarExpressions: Map<AvatarType, Map<AvatarState, String>> = mapOf(
        AvatarType.ANT to mapOf(
            AvatarState.IDLE to "🐜",
            AvatarState.LISTENING to "🐜👂",
            AvatarState.THINKING to "🐜💭",
            AvatarState.SEARCHING to "🐜🔎",
            AvatarState.SPEAKING to "🐜💬",
            AvatarState.ERROR to "🐜⚠️"
        ),
        AvatarType.LOBSTER to mapOf(
            AvatarState.IDLE to "🦞",
            AvatarState.LISTENING to "🦞👂",
            AvatarState.THINKING to "🦞💭",
            AvatarState.SEARCHING to "🦞🔎",
            AvatarState.SPEAKING to "🦞💬",
            AvatarState.ERROR to "🦞⚠️"
        ),
        AvatarType.ROBOT to mapOf(
            AvatarState.IDLE to "🤖",
            AvatarState.LISTENING to "🤖👂",
            AvatarState.THINKING to "🤖💭",
            AvatarState.SEARCHING to "🤖🔎",
            AvatarState.SPEAKING to "🤖💬",
            AvatarState.ERROR to "🤖⚠️"
        ),
        AvatarType.BOY to mapOf(
            AvatarState.IDLE to "👦",
            AvatarState.LISTENING to "👦👂",
            AvatarState.THINKING to "👦💭",
            AvatarState.SEARCHING to "👦🔎",
            AvatarState.SPEAKING to "👦💬",
            AvatarState.ERROR to "👦⚠️"
        ),
        AvatarType.GIRL to mapOf(
            AvatarState.IDLE to "👧",
            AvatarState.LISTENING to "👧👂",
            AvatarState.THINKING to "👧💭",
            AvatarState.SEARCHING to "👧🔎",
            AvatarState.SPEAKING to "👧💬",
            AvatarState.ERROR to "👧⚠️"
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        clawRunner = ClawRunner(this)
        voiceEngine = VoiceEngine(this)

        binding.fab.setOnClickListener { onFabTapped() }
        binding.saveKeyBtn.setOnClickListener { onSaveKey() }

        lifecycleScope.launch { initialise() }
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
        voiceEngine.stopSpeaking()
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
                    setStatus("Speaking…")
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
        return when ((prefs.getString(PREF_AVATAR_TYPE, "ant") ?: "ant").lowercase()) {
            "lobster" -> AvatarType.LOBSTER
            "robot" -> AvatarType.ROBOT
            "boy" -> AvatarType.BOY
            "girl" -> AvatarType.GIRL
            else -> AvatarType.ANT
        }
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
        val type = currentAvatarType()
        val aState = avatarStateFor(s)
        val face = avatarExpressions[type]?.get(aState) ?: "🐜"
        binding.avatarFace.text = face

        val lowBattery = isLowBattery()
        binding.avatarFace.setTextColor(if (lowBattery) LOW_BATTERY_COLOR else ACCENT_COLOR)
        binding.avatarFace.alpha = if (lowBattery) 0.6f else 1.0f

        if (lowBattery) {
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
                        val v = it.animatedValue as Float
                        binding.avatarFace.scaleX = v
                        binding.avatarFace.scaleY = v
                    }
                    start()
                }
            }
            AvatarState.THINKING, AvatarState.SEARCHING -> {
                avatarAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1200L
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener {
                        val p = it.animatedFraction
                        binding.avatarFace.rotation = -6f + 12f * p
                    }
                    start()
                }
            }
            AvatarState.SPEAKING -> {
                avatarAnimator = ValueAnimator.ofFloat(1.0f, 1.05f, 1.0f).apply {
                    duration = 480L
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener {
                        val v = it.animatedValue as Float
                        binding.avatarFace.scaleX = v
                        binding.avatarFace.scaleY = v
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
        binding.avatarFace.scaleX = 1.0f
        binding.avatarFace.scaleY = 1.0f
        binding.avatarFace.rotation = 0f
        if (!isLowBattery()) binding.avatarFace.alpha = 1.0f
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

    private fun setState(s: State) {
        state = s
        runOnUiThread {
            binding.splashPanel.visibility = View.GONE
            binding.setupPanel.visibility  = if (s == State.SETUP)    View.VISIBLE else View.GONE
            binding.mainPanel.visibility   = if (s != State.SETUP)    View.VISIBLE else View.GONE
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
            binding.fab.setImageDrawable(
                AppCompatResources.getDrawable(
                    this,
                    when (s) {
                        State.IDLE -> android.R.drawable.ic_btn_speak_now
                        State.LISTENING -> android.R.drawable.presence_audio_online
                        State.THINKING, State.SEARCHING, State.SPEAKING -> android.R.drawable.ic_media_pause
                        State.ERROR -> android.R.drawable.ic_popup_sync
                        State.SETUP -> android.R.drawable.ic_btn_speak_now
                    }
                )
            )
        }
    }

    private fun setStatus(msg: String) = runOnUiThread { binding.statusText.text = msg }

    override fun onDestroy() {
        super.onDestroy()
        cancelAutoListenWindow()
        stopAvatarAnimation()
        queryJob?.cancel()
        voiceEngine.release()
    }
}
