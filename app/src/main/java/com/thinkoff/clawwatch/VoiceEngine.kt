package com.thinkoff.clawwatch

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Handles offline STT (Vosk) and TTS (Android built-in).
 *
 * Fixed (claudemm review):
 * - #1/#2: onFinalResult now properly parses JSON and handles null
 * - #5: Vosk model copied from assets to filesDir before loading
 * - #6: TTS uses UtteranceProgressListener.onDone() not heuristic delay
 * - #9: Vosk JSON parsed with JSONObject not regex
 */
class VoiceEngine(private val context: Context) {

    companion object {
        private const val TAG = "VoiceEngine"
        private const val VOSK_MODEL_DIR = "vosk-model-small-en-us"
        private const val SAMPLE_RATE = 16000f
        private const val TTS_UTTERANCE_ID = "claw_response"
    }

    private var tts: TextToSpeech? = null
    private var speechService: SpeechService? = null
    private var voskModel: Model? = null
    private var recognizer: Recognizer? = null

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

    /** Speak text and call onDone when finished. Fix #6: use UtteranceProgressListener. */
    fun speak(text: String, onDone: () -> Unit = {}) {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { if (utteranceId == TTS_UTTERANCE_ID) onDone() }
            override fun onError(utteranceId: String?) { onDone() }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, TTS_UTTERANCE_ID)
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    // ── STT (Vosk offline) ───────────────────────────────

    /**
     * Copy Vosk model from assets to filesDir if needed, then load it.
     * Fix #5: model was looked up in filesDir but only existed in assets.
     */
    fun initVosk(onReady: () -> Unit, onError: (String) -> Unit) {
        val modelDir = File(context.filesDir, VOSK_MODEL_DIR)
        if (!modelDir.exists()) {
            Log.i(TAG, "Copying Vosk model from assets to filesDir…")
            try {
                copyAssetDir(VOSK_MODEL_DIR, modelDir)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy Vosk model", e)
                onError("Vosk copy failed: ${e.message}")
                return
            }
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

    /** Recursively copy an asset directory to a destination File. */
    private fun copyAssetDir(assetPath: String, dest: File) {
        val assets = context.assets.list(assetPath) ?: emptyArray()
        if (assets.isEmpty()) {
            // It's a file
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            }
        } else {
            dest.mkdirs()
            for (child in assets) {
                copyAssetDir("$assetPath/$child", File(dest, child))
            }
        }
    }

    fun startListening(onResult: (String) -> Unit, onPartial: (String) -> Unit) {
        val model = voskModel ?: return
        stopListening()
        recognizer = Recognizer(model, SAMPLE_RATE)
        speechService = SpeechService(recognizer, SAMPLE_RATE)
        speechService?.startListening(object : RecognitionListener {
            override fun onResult(hypothesis: String?) {
                val text = parseVoskText(hypothesis, "text") ?: return
                if (text.isNotBlank()) onResult(text)
            }
            override fun onPartialResult(hypothesis: String?) {
                val text = parseVoskText(hypothesis, "partial") ?: return
                if (text.isNotBlank()) onPartial(text)
            }
            // Fix #1/#2: parse JSON properly, handle null
            override fun onFinalResult(hypothesis: String?) {
                val text = parseVoskText(hypothesis, "text") ?: return
                if (text.isNotBlank()) onResult(text)
            }
            override fun onError(e: Exception?) { Log.e(TAG, "STT error", e) }
            override fun onTimeout() { Log.w(TAG, "STT timeout") }
        })
    }

    fun stopListening() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        try {
            recognizer?.close()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    // Fix #9: use JSONObject instead of regex
    private fun parseVoskText(json: String?, key: String): String? {
        if (json.isNullOrBlank()) return null
        return try {
            JSONObject(json).optString(key, "").takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    // ── Cleanup ──────────────────────────────────────────

    fun release() {
        stopListening()
        try {
            voskModel?.close()
        } catch (_: Exception) {
        }
        voskModel = null
        tts?.shutdown()
        tts = null
    }
}
