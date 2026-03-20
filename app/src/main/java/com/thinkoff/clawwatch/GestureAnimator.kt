package com.thinkoff.clawwatch

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlinx.coroutines.*

/**
 * Drives claw/avatar animations based on the actual text being spoken,
 * rather than looping a single gesture. Parses text into segments and
 * triggers contextual animations timed to TTS output.
 */
class GestureAnimator(private val scope: CoroutineScope) {

    private data class MotionProfile(
        val rotationMultiplier: Float,
        val horizontalMultiplier: Float,
        val verticalMultiplier: Float,
        val scaleBoost: Float,
        val durationMultiplier: Float,
        val swingBase: Float,
        val overshootTension: Float
    )

    enum class Mood {
        NEUTRAL,
        CHEERFUL,
        EXCITED,
        PLAYFUL,
        CALM,
        SERIOUS
    }

    /** A segment of text mapped to a gesture style and estimated duration. */
    data class GestureSegment(
        val text: String,
        val gesture: GestureType,
        val durationMs: Long
    )

    enum class GestureType {
        QUESTION,       // claws tilt inward, head tilts
        EXCLAMATION,    // wide sweep outward
        EMPHASIS,       // big slow gesture
        RAPID,          // quick small taps
        PAUSE,          // claws rest briefly
        LISTING,        // alternating left-right counting
        NEUTRAL         // moderate conversational gesture
    }

    private var animJob: Job? = null

    /** Average TTS rate: ~13 chars per second at 1.1x speed. */
    private val charsPerSecond = 13.0

    /**
     * Parse text into gesture segments with timing estimates.
     */
    fun parseGestures(text: String): List<GestureSegment> {
        val segments = mutableListOf<GestureSegment>()
        // Split on sentence boundaries, keeping delimiters
        val sentences = text.split(Regex("(?<=[.!?;:])|(?<=,\\s)"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (sentence in sentences) {
            val durationMs = ((sentence.length / charsPerSecond) * 1000).toLong().coerceAtLeast(200)

            val gesture = when {
                sentence.endsWith("?") -> GestureType.QUESTION
                sentence.endsWith("!") -> GestureType.EXCLAMATION
                sentence.contains(",") && sentence.split(",").size >= 3 -> GestureType.LISTING
                sentence.any { it.isUpperCase() } &&
                    sentence.uppercase() == sentence &&
                    sentence.length > 2 -> GestureType.EMPHASIS
                sentence.split(" ").size <= 3 -> GestureType.RAPID
                sentence.endsWith(",") || sentence.endsWith(";") || sentence.endsWith(":") -> {
                    // add a brief pause after this segment
                    segments += GestureSegment(sentence, GestureType.NEUTRAL, durationMs)
                    segments += GestureSegment("", GestureType.PAUSE, 150)
                    continue
                }
                else -> GestureType.NEUTRAL
            }
            segments += GestureSegment(sentence, gesture, durationMs)
        }

        return segments
    }

    /**
     * Animate the avatar view (left claw, right claw, head) according to
     * parsed gesture segments. Call this when SPEAKING state begins.
     *
     * @param avatarView The ImageView holding the AnimatedVectorDrawable
     * @param text The full text being spoken
     */
    fun animateSpeech(
        avatarView: View,
        text: String,
        energyScale: Float = 1.0f,
        mood: Mood = Mood.NEUTRAL
    ) {
        stop()
        val segments = parseGestures(text)
        if (segments.isEmpty()) return

        animJob = scope.launch {
            for (segment in segments) {
                if (!isActive) break
                applyGesture(avatarView, segment, energyScale, mood)
                delay(segment.durationMs)
            }
            // Return to rest
            if (isActive) {
                resetToRest(avatarView)
            }
        }
    }

    private suspend fun applyGesture(
        view: View,
        segment: GestureSegment,
        energyScale: Float,
        mood: Mood
    ) {
        withContext(Dispatchers.Main) {
            val profile = motionProfile(mood)
            val energy = energyScale.coerceIn(0.75f, 1.45f)
            val signedVariation = segmentSignedVariation(segment, mood)
            val driftVariation = segmentDriftVariation(segment, mood)
            val rotationBias = signedVariation * 1.4f
            val horizontalBias = signedVariation * 2.1f
            val verticalBias = driftVariation * 1.2f
            val durationScale = profile.durationMultiplier
            val baseScale = 1.0f + profile.scaleBoost

            when (segment.gesture) {
                GestureType.QUESTION -> {
                    animateProperties(view,
                        rotation = ((-8f * energy * profile.rotationMultiplier) + rotationBias),
                        scaleX = (baseScale - 0.03f).coerceAtLeast(0.94f),
                        scaleY = (baseScale - 0.02f).coerceAtLeast(0.95f),
                        translationX = ((-5f * energy * profile.horizontalMultiplier) + horizontalBias),
                        translationY = ((-5f * energy * profile.verticalMultiplier) + verticalBias),
                        durationMs = (310L * durationScale).toLong()
                    )
                }
                GestureType.EXCLAMATION -> {
                    animateProperties(view,
                        rotation = rotationBias * 0.5f,
                        scaleX = 1.08f + (0.07f * (energy - 1f)) + profile.scaleBoost,
                        scaleY = 1.08f + (0.07f * (energy - 1f)) + profile.scaleBoost,
                        translationX = horizontalBias * 0.55f,
                        translationY = (-11f * energy * profile.verticalMultiplier) + verticalBias,
                        durationMs = (210L * durationScale).toLong(),
                        overshoot = true,
                        overshootTension = profile.overshootTension
                    )
                }
                GestureType.EMPHASIS -> {
                    animateProperties(view,
                        rotation = ((2f + signedVariation) * energy * profile.rotationMultiplier),
                        scaleX = 1.05f + (0.05f * (energy - 1f)) + profile.scaleBoost,
                        scaleY = 1.05f + (0.05f * (energy - 1f)) + profile.scaleBoost,
                        translationX = ((4f * energy * profile.horizontalMultiplier) + horizontalBias),
                        translationY = ((-7f * energy * profile.verticalMultiplier) + verticalBias),
                        durationMs = (480L * durationScale).toLong()
                    )
                }
                GestureType.RAPID -> {
                    val swing = if ((segment.text.hashCode() and 1) == 0) profile.swingBase else -profile.swingBase
                    animateProperties(view,
                        rotation = (swing * energy * profile.rotationMultiplier) + rotationBias,
                        scaleX = 1.01f + profile.scaleBoost,
                        scaleY = 1.01f + profile.scaleBoost,
                        translationX = ((swing * 1.35f * energy * profile.horizontalMultiplier) + horizontalBias),
                        translationY = ((-3f * energy * profile.verticalMultiplier) + verticalBias),
                        durationMs = (120L * durationScale).toLong(),
                        overshoot = mood != Mood.CALM,
                        overshootTension = profile.overshootTension
                    )
                }
                GestureType.LISTING -> {
                    animateProperties(view,
                        rotation = ((profile.swingBase + 2f) * energy * profile.rotationMultiplier) + rotationBias,
                        scaleX = 1.04f + profile.scaleBoost,
                        scaleY = baseScale,
                        translationX = ((8f * energy * profile.horizontalMultiplier) + horizontalBias),
                        translationY = ((-5f * energy * profile.verticalMultiplier) + verticalBias),
                        durationMs = (240L * durationScale).toLong()
                    )
                    delay(segment.durationMs / 3)
                    animateProperties(view,
                        rotation = ((-(profile.swingBase + 2f)) * energy * profile.rotationMultiplier) - rotationBias,
                        scaleX = baseScale,
                        scaleY = 1.04f + profile.scaleBoost,
                        translationX = ((-8f * energy * profile.horizontalMultiplier) - horizontalBias),
                        translationY = ((-5f * energy * profile.verticalMultiplier) + verticalBias),
                        durationMs = (240L * durationScale).toLong()
                    )
                }
                GestureType.PAUSE -> {
                    animateProperties(view,
                        rotation = 0f,
                        scaleX = 1.0f,
                        scaleY = 1.0f,
                        translationX = 0f,
                        translationY = 0f,
                        durationMs = 150
                    )
                }
                GestureType.NEUTRAL -> {
                    val tilt = if ((segment.text.hashCode() % 2) == 0) profile.swingBase else -profile.swingBase
                    animateProperties(view,
                        rotation = (tilt * energy * profile.rotationMultiplier) + rotationBias,
                        scaleX = 1.02f + profile.scaleBoost,
                        scaleY = 1.02f + profile.scaleBoost,
                        translationX = ((tilt * 1.15f * energy * profile.horizontalMultiplier) + horizontalBias),
                        translationY = ((-4f * energy * profile.verticalMultiplier) + verticalBias),
                        durationMs = (280L * durationScale).toLong()
                    )
                }
            }
        }
    }

    private fun motionProfile(mood: Mood): MotionProfile = when (mood) {
        Mood.CHEERFUL -> MotionProfile(
            rotationMultiplier = 1.08f,
            horizontalMultiplier = 0.95f,
            verticalMultiplier = 1.28f,
            scaleBoost = 0.022f,
            durationMultiplier = 0.92f,
            swingBase = 4.8f,
            overshootTension = 1.6f
        )
        Mood.EXCITED -> MotionProfile(
            rotationMultiplier = 1.32f,
            horizontalMultiplier = 1.18f,
            verticalMultiplier = 1.42f,
            scaleBoost = 0.034f,
            durationMultiplier = 0.82f,
            swingBase = 6.4f,
            overshootTension = 1.9f
        )
        Mood.PLAYFUL -> MotionProfile(
            rotationMultiplier = 1.22f,
            horizontalMultiplier = 1.36f,
            verticalMultiplier = 1.18f,
            scaleBoost = 0.026f,
            durationMultiplier = 0.88f,
            swingBase = 7.2f,
            overshootTension = 1.7f
        )
        Mood.CALM -> MotionProfile(
            rotationMultiplier = 0.58f,
            horizontalMultiplier = 0.38f,
            verticalMultiplier = 0.72f,
            scaleBoost = -0.01f,
            durationMultiplier = 1.2f,
            swingBase = 2.4f,
            overshootTension = 1.0f
        )
        Mood.SERIOUS -> MotionProfile(
            rotationMultiplier = 0.74f,
            horizontalMultiplier = 0.46f,
            verticalMultiplier = 0.9f,
            scaleBoost = 0.0f,
            durationMultiplier = 1.08f,
            swingBase = 3.1f,
            overshootTension = 1.1f
        )
        Mood.NEUTRAL -> MotionProfile(
            rotationMultiplier = 1.0f,
            horizontalMultiplier = 1.0f,
            verticalMultiplier = 1.0f,
            scaleBoost = 0.0f,
            durationMultiplier = 1.0f,
            swingBase = 5.0f,
            overshootTension = 1.5f
        )
    }

    private fun segmentSignedVariation(segment: GestureSegment, mood: Mood): Float {
        val seed = segment.text.hashCode() xor (mood.ordinal * 97) xor (segment.durationMs.toInt() * 13)
        return (((seed and 0x7fffffff) % 7) - 3).toFloat() * 0.55f
    }

    private fun segmentDriftVariation(segment: GestureSegment, mood: Mood): Float {
        val seed = (segment.text.reversed().hashCode() xor (mood.ordinal * 53) xor segment.gesture.ordinal * 19)
        return (((seed and 0x7fffffff) % 5) - 2).toFloat() * 0.45f
    }

    private fun animateProperties(
        view: View,
        rotation: Float,
        scaleX: Float,
        scaleY: Float,
        translationX: Float,
        translationY: Float,
        durationMs: Long,
        overshoot: Boolean = false,
        overshootTension: Float = 1.5f
    ) {
        val interpolator = if (overshoot) OvershootInterpolator(overshootTension)
            else AccelerateDecelerateInterpolator()

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, "rotation", rotation),
                ObjectAnimator.ofFloat(view, "scaleX", scaleX),
                ObjectAnimator.ofFloat(view, "scaleY", scaleY),
                ObjectAnimator.ofFloat(view, "translationX", translationX),
                ObjectAnimator.ofFloat(view, "translationY", translationY)
            )
            duration = durationMs
            this.interpolator = interpolator
            start()
        }
    }

    private fun resetToRest(view: View) {
        animateProperties(view,
            rotation = 0f,
            scaleX = 1.0f,
            scaleY = 1.0f,
            translationX = 0f,
            translationY = 0f,
            durationMs = 400
        )
    }

    fun stop() {
        animJob?.cancel()
        animJob = null
    }
}
