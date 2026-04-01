package com.thinkoff.clawwatch

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
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
        private const val PREF_TTS_LOCALE = "tts_locale"
        private const val PREF_TTS_VOICE_NAME = "tts_voice_name"
        private const val PREF_TTS_ENGINE_PACKAGE = "tts_engine_package"
    }

    private var tts: TextToSpeech? = null
    private var speechService: SpeechService? = null
    private var voskModel: Model? = null
    private var recognizer: Recognizer? = null
    private val prefs by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { SecurePrefs.watch(context) }
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    /** Set media volume to max so TTS is audible on weaker speakers. */
    private fun boostVolume() {
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
    }

    // ── TTS ──────────────────────────────────────────────

    suspend fun initTts() = suspendCancellableCoroutine { cont ->
        val preferredEngine = prefs.getString(PREF_TTS_ENGINE_PACKAGE, null)?.takeIf { it.isNotBlank() }
        val initListener: (Int) -> Unit = { status ->
            if (status == TextToSpeech.SUCCESS) {
                applyPreferredVoice()
                tts?.setSpeechRate(1.1f)
                Log.i(TAG, "TTS ready")
                cont.resume(true)
            } else {
                Log.e(TAG, "TTS init failed: $status")
                cont.resume(false)
            }
        }

        tts = if (preferredEngine != null) {
            TextToSpeech(context, initListener, preferredEngine)
        } else {
            TextToSpeech(context, initListener)
        }
    }

    private fun applyPreferredVoice() {
        val preferredLocaleTag = prefs.getString(PREF_TTS_LOCALE, null)?.takeIf { it.isNotBlank() } ?: "en-US"
        val preferredVoiceName = prefs.getString(PREF_TTS_VOICE_NAME, null)?.takeIf { it.isNotBlank() }
        val desiredLocale = Locale.forLanguageTag(preferredLocaleTag)
        val engine = tts ?: return

        val availableVoices = try {
            engine.voices.orEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "Could not enumerate TTS voices", e)
            emptySet()
        }

        val selectedVoice = when {
            preferredVoiceName != null ->
                availableVoices.firstOrNull { it.name == preferredVoiceName }
            else ->
                availableVoices.firstOrNull {
                    it.locale == desiredLocale && !it.isNetworkConnectionRequired
                } ?: availableVoices.firstOrNull { it.locale == desiredLocale }
        }

        if (selectedVoice != null) {
            engine.voice = selectedVoice
            Log.i(TAG, "TTS voice selected: ${selectedVoice.name} (${selectedVoice.locale.toLanguageTag()})")
        } else {
            engine.language = desiredLocale
            Log.i(TAG, "TTS locale selected: ${desiredLocale.toLanguageTag()}")
        }
    }

    /** Speak text and call onDone when finished. Fix #6: use UtteranceProgressListener. */
    fun speak(
        text: String,
        speechRate: Float = 1.1f,
        pitch: Float = 1.0f,
        onStart: () -> Unit = {},
        onDone: () -> Unit = {}
    ) {
        boostVolume()
        tts?.setSpeechRate(speechRate.coerceIn(0.85f, 1.25f))
        tts?.setPitch(pitch.coerceIn(0.85f, 1.20f))
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                if (utteranceId == TTS_UTTERANCE_ID) onStart()
            }
            override fun onDone(utteranceId: String?) { if (utteranceId == TTS_UTTERANCE_ID) onDone() }
            override fun onError(utteranceId: String?) { onDone() }
        })
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, TTS_UTTERANCE_ID)
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
        try {
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Vosk SpeechService", e)
            onPartial("Mic err: ${e.message ?: "Unknown"}")
        }
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
