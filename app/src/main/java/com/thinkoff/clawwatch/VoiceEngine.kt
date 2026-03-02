package com.thinkoff.clawwatch

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Handles offline STT (Vosk) and TTS (Android built-in).
 */
class VoiceEngine(private val context: Context) {

    companion object {
        private const val TAG = "VoiceEngine"
        private const val VOSK_MODEL_DIR = "vosk-model-small-en-us"
        private const val SAMPLE_RATE = 16000f
    }

    private var tts: TextToSpeech? = null
    private var speechService: SpeechService? = null
    private var voskModel: Model? = null

    // ── TTS ──────────────────────────────────────────────

    suspend fun initTts() = suspendCancellableCoroutine { cont ->
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(1.1f)
                Log.i(TAG, "TTS ready")
                cont.resume(true)
            } else {
                Log.e(TAG, "TTS init failed: $status")
                cont.resume(false)
            }
        }
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "claw_response")
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    // ── STT (Vosk offline) ───────────────────────────────

    fun initVosk(onReady: () -> Unit, onError: (String) -> Unit) {
        val modelDir = File(context.filesDir, VOSK_MODEL_DIR)
        if (!modelDir.exists()) {
            onError("Vosk model not found — download required")
            return
        }
        try {
            voskModel = Model(modelDir.absolutePath)
            Log.i(TAG, "Vosk model loaded")
            onReady()
        } catch (e: Exception) {
            Log.e(TAG, "Vosk init failed", e)
            onError(e.message ?: "Vosk load error")
        }
    }

    fun startListening(onResult: (String) -> Unit, onPartial: (String) -> Unit) {
        val model = voskModel ?: return
        val recognizer = Recognizer(model, SAMPLE_RATE)
        speechService = SpeechService(recognizer, SAMPLE_RATE)
        speechService?.startListening(object : RecognitionListener {
            override fun onResult(hypothesis: String?) {
                val text = parseVoskResult(hypothesis) ?: return
                if (text.isNotBlank()) onResult(text)
            }
            override fun onPartialResult(hypothesis: String?) {
                val text = parseVoskPartial(hypothesis) ?: return
                if (text.isNotBlank()) onPartial(text)
            }
            override fun onFinalResult(hypothesis: String?) = onResult(hypothesis)
            override fun onError(e: Exception?) = Log.e(TAG, "STT error", e)
            override fun onTimeout() = Log.w(TAG, "STT timeout")
        })
    }

    fun stopListening() {
        speechService?.stop()
        speechService = null
    }

    private fun parseVoskResult(json: String?): String? =
        json?.let { Regex("\"text\"\\s*:\\s*\"([^\"]*)\"").find(it)?.groupValues?.get(1) }

    private fun parseVoskPartial(json: String?): String? =
        json?.let { Regex("\"partial\"\\s*:\\s*\"([^\"]*)\"").find(it)?.groupValues?.get(1) }

    // ── Cleanup ──────────────────────────────────────────

    fun release() {
        speechService?.shutdown()
        tts?.shutdown()
    }
}
