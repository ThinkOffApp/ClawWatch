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
import kotlinx.coroutines.launch

/**
 * Main Wear OS activity.
 *
 * UX flow:
 *   IDLE  → tap FAB → LISTENING → (speech detected) → THINKING → SPEAKING → IDLE
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ClawWatch"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var clawRunner: ClawRunner
    private lateinit var voiceEngine: VoiceEngine

    private enum class State { IDLE, LISTENING, THINKING, SPEAKING }
    private var state = State.IDLE

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening()
        else setStatus("Mic permission denied")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        clawRunner = ClawRunner(this)
        voiceEngine = VoiceEngine(this)

        binding.fab.setOnClickListener { onFabTapped() }

        lifecycleScope.launch {
            setStatus("Setting up…")
            clawRunner.ensureInstalled()
            voiceEngine.initTts()
            voiceEngine.initVosk(
                onReady = { setStatus("Tap to talk") },
                onError = { err -> setStatus("STT: $err") }
            )
        }
    }

    private fun onFabTapped() {
        when (state) {
            State.IDLE -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) startListening()
                else requestMic.launch(Manifest.permission.RECORD_AUDIO)
            }
            State.LISTENING -> {
                // Tap again to stop listening early and process what we have
                voiceEngine.stopListening()
                setState(State.IDLE)
                setStatus("Tap to talk")
            }
            State.THINKING, State.SPEAKING -> {
                // Tap to interrupt
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
                    Log.i(TAG, "STT result: $text")
                    voiceEngine.stopListening()
                    askClaw(text)
                }
            },
            onPartial = { partial ->
                runOnUiThread { setStatus("\"$partial\"") }
            }
        )
    }

    private fun askClaw(prompt: String) {
        setState(State.THINKING)
        setStatus("Thinking…")
        binding.queryText.text = prompt

        lifecycleScope.launch {
            val result = clawRunner.query(prompt)
            result.fold(
                onSuccess = { response ->
                    Log.i(TAG, "Claw response: $response")
                    setState(State.SPEAKING)
                    setStatus("Speaking…")
                    binding.responseText.text = response
                    voiceEngine.speak(response)
                    // After TTS finishes, go idle (simple delay approach)
                    kotlinx.coroutines.delay(response.length * 60L + 1000L)
                    setState(State.IDLE)
                    setStatus("Tap to talk")
                },
                onFailure = { err ->
                    setState(State.IDLE)
                    setStatus("Error: ${err.message}")
                    voiceEngine.speak("Sorry, something went wrong.")
                }
            )
        }
    }

    private fun setState(s: State) {
        state = s
        runOnUiThread {
            binding.fab.contentDescription = when (s) {
                State.IDLE -> "Tap to talk"
                State.LISTENING -> "Tap to stop"
                State.THINKING -> "Thinking"
                State.SPEAKING -> "Tap to stop"
            }
            binding.thinkingIndicator.visibility =
                if (s == State.THINKING) View.VISIBLE else View.GONE
        }
    }

    private fun setStatus(msg: String) {
        runOnUiThread { binding.statusText.text = msg }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceEngine.release()
    }
}
