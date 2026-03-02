package com.thinkoff.clawwatch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.thinkoff.clawwatch.databinding.ActivityMainBinding
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
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var clawRunner: ClawRunner
    private lateinit var voiceEngine: VoiceEngine

    private enum class State { SETUP, IDLE, LISTENING, THINKING, SPEAKING }
    private var state = State.SETUP

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening() else setStatus("Mic permission needed")
    }

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

        if (!clawRunner.hasApiKey()) {
            setState(State.IDLE)
            setStatus("No key — run set_key.sh")
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
        when (state) {
            State.SETUP -> { /* handled by save button */ }
            State.IDLE -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) startListening()
                else requestMic.launch(Manifest.permission.RECORD_AUDIO)
            }
            State.LISTENING -> {
                voiceEngine.stopListening()
                setState(State.IDLE)
                setStatus("Tap to talk")
            }
            State.THINKING, State.SPEAKING -> {
                voiceEngine.stopSpeaking()
                setState(State.IDLE)
                setStatus("Tap to talk")
            }
        }
    }

    private fun startListening() {
        setState(State.LISTENING)
        setStatus("Listening…")
        voiceEngine.startListening(
            onResult = { text ->
                runOnUiThread {
                    voiceEngine.stopListening()
                    binding.queryText.text = "\u201c$text\u201d"
                    askClaw(text)
                }
            },
            onPartial = { partial ->
                runOnUiThread { setStatus(partial) }
            }
        )
    }

    private fun askClaw(prompt: String) {
        setState(State.THINKING)
        setStatus("Opus 4.6 thinking…")

        lifecycleScope.launch {
            val result = clawRunner.query(prompt)
            result.fold(
                onSuccess = { response ->
                    binding.responseText.text = response
                    setState(State.SPEAKING)
                    setStatus("Speaking…")
                    voiceEngine.speak(response)
                    // Estimate TTS duration, then return to idle
                    delay(response.length * 55L + 1000L)
                    if (state == State.SPEAKING) {
                        setState(State.IDLE)
                        setStatus("Tap to talk")
                    }
                },
                onFailure = { err ->
                    val msg = err.message ?: "Error"
                    setStatus(msg)
                    voiceEngine.speak("Sorry, $msg")
                    setState(State.IDLE)
                }
            )
        }
    }

    private fun setState(s: State) {
        state = s
        runOnUiThread {
            binding.setupPanel.visibility  = if (s == State.SETUP)    View.VISIBLE else View.GONE
            binding.mainPanel.visibility   = if (s != State.SETUP)    View.VISIBLE else View.GONE
            binding.thinkingIndicator.visibility =
                if (s == State.THINKING) View.VISIBLE else View.GONE
            binding.fab.contentDescription = when (s) {
                State.IDLE      -> "Tap to talk"
                State.LISTENING -> "Tap to stop"
                State.THINKING  -> "Thinking"
                State.SPEAKING  -> "Tap to stop"
                State.SETUP     -> ""
            }
        }
    }

    private fun setStatus(msg: String) = runOnUiThread { binding.statusText.text = msg }

    override fun onDestroy() {
        super.onDestroy()
        voiceEngine.release()
    }
}
