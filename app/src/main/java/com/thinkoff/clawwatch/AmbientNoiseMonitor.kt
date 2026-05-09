package com.thinkoff.clawwatch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Lightweight ambient noise monitor used to decide whether ClawWatch
 * should reply via TTS or fall back to text on the CodeWatch chat
 * interface.
 *
 * Design notes:
 *  - Samples RMS on a short 200 ms PCM window using a dedicated short
 *    `AudioRecord` session — completely separate from VoiceEngine's
 *    main capture so we don't conflict with active STT.
 *  - Returns a single-shot boolean ("is the room loud right now?")
 *    rather than streaming, because that's how the reply branch
 *    consumes it: at the moment we're about to speak, ask once.
 *  - Threshold defaults assume Wear OS hardware where the mic gain
 *    pushes a quiet room to RMS ~60-200 and a noisy bar/party past
 *    1500. The default of 1200 was picked from quick spot tests on
 *    a Pixel Watch 2; expose it as a tunable so we can adjust.
 *  - Falls back to "not noisy" (i.e. keep using TTS) on any error,
 *    permission denial, or hardware unavailability — failing closed
 *    here means worst case the user just doesn't hear well, but the
 *    answer still plays. Failing open (always text) would silently
 *    break the voice UX for users with no party in the room.
 *
 *  Integration sketch (not yet wired to ClawRunner — done in a
 *  follow-up edit so this file lands self-contained for review):
 *
 *      val ambient = AmbientNoiseMonitor(context)
 *      val noisy = ambient.sampleIsNoisy()
 *      if (noisy) chatSink.send(replyText) else tts.speak(replyText)
 */
class AmbientNoiseMonitor(
    private val context: Context,
    /** RMS threshold above which the room is considered noisy. */
    private val noisyRmsThreshold: Int = DEFAULT_NOISY_RMS,
    /** How long to listen before computing RMS, in milliseconds. */
    private val sampleDurationMs: Int = DEFAULT_SAMPLE_MS,
    private val sampleRateHz: Int = 16_000,
) {
    companion object {
        private const val TAG = "AmbientNoise"
        const val DEFAULT_NOISY_RMS = 1200
        const val DEFAULT_SAMPLE_MS = 200
    }

    /** True if the caller has the runtime mic permission. */
    fun hasMicPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Block briefly (≈ sampleDurationMs) and return true if the
     * measured RMS is above the noisy threshold. Returns false on
     * any failure path — see class kdoc for the rationale.
     */
    suspend fun sampleIsNoisy(): Boolean = withContext(Dispatchers.IO) {
        val rms = sampleRms() ?: return@withContext false
        Log.d(TAG, "ambient RMS=$rms threshold=$noisyRmsThreshold")
        rms > noisyRmsThreshold
    }

    /**
     * Return the raw RMS, or null on permission/hardware failure.
     * Useful for logging and for tuning the threshold.
     */
    suspend fun sampleRms(): Int? = withContext(Dispatchers.IO) {
        if (!hasMicPermission()) {
            Log.w(TAG, "RECORD_AUDIO not granted — skipping noise sample")
            return@withContext null
        }
        val bufSize = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (bufSize <= 0) {
            Log.w(TAG, "AudioRecord min buf size invalid: $bufSize")
            return@withContext null
        }
        val sampleCount = (sampleRateHz * sampleDurationMs) / 1000
        val readBuffer = ShortArray(sampleCount)
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRateHz,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "AudioRecord ctor SecurityException: ${e.message}")
            return@withContext null
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord ctor failed: ${e.message}")
            return@withContext null
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord not initialized")
            rec.release()
            return@withContext null
        }
        return@withContext try {
            rec.startRecording()
            var read = 0
            while (read < sampleCount) {
                val n = rec.read(readBuffer, read, sampleCount - read)
                if (n <= 0) break
                read += n
            }
            if (read <= 0) null else computeRms(readBuffer, read)
        } catch (e: Exception) {
            Log.w(TAG, "RMS sample failed: ${e.message}")
            null
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    private fun computeRms(buf: ShortArray, length: Int): Int {
        if (length <= 0) return 0
        var sumSquares = 0.0
        for (i in 0 until length) {
            val v = buf[i].toDouble()
            sumSquares += v * v
        }
        return sqrt(sumSquares / length).toInt()
    }
}
