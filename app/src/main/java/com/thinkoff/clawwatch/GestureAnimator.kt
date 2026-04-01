package com.thinkoff.clawwatch

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnticipateOvershootInterpolator
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import kotlinx.coroutines.*
import kotlin.random.Random

/**
 * Drives claw/avatar animations based on the actual text being spoken,
 * rather than looping a single gesture. Parses text into segments and
 * triggers contextual animations timed to TTS output.
 *
 * Each mood has a qualitatively different motion style, not just
 * parameter tweaks. PLAYFUL bounces, EXCITED jabs, CALM sways gently,
 * CHEERFUL bobs up, SERIOUS nods deliberately.
 */
class GestureAnimator(private val scope: CoroutineScope) {

    private data class MotionProfile(
        val rotationRange: Float,
        val horizontalRange: Float,
        val verticalRange: Float,
        val scaleRange: Float,
        val speedFactor: Float,
        val bounciness: Float,
        val idleJitter: Float
    )

    enum class Mood {
        NEUTRAL,
        CHEERFUL,
        EXCITED,
        PLAYFUL,
        CALM,
        SERIOUS
    }

    data class GestureSegment(
        val text: String,
        val gesture: GestureType,
        val durationMs: Long
    )

    enum class GestureType {
        QUESTION,
        EXCLAMATION,
        EMPHASIS,
        RAPID,
        PAUSE,
        LISTING,
        NEUTRAL
    }

    private var animJob: Job? = null
    private val rng = Random(System.nanoTime())
    private var segmentCounter = 0

    private val charsPerSecond = 13.0

    fun parseGestures(text: String): List<GestureSegment> {
        val segments = mutableListOf<GestureSegment>()
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

    fun animateSpeech(
        avatarView: View,
        text: String,
        energyScale: Float = 1.0f,
        mood: Mood = Mood.NEUTRAL
    ) {
        stop()
        val segments = parseGestures(text)
        if (segments.isEmpty()) return
        segmentCounter = 0

        animJob = scope.launch {
            for (segment in segments) {
                if (!isActive) break
                applyGesture(avatarView, segment, energyScale, mood)
                segmentCounter++
            }
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
        val profile = motionProfile(mood)
        val energy = energyScale.coerceIn(0.75f, 1.45f)
        val jitter = { (rng.nextFloat() - 0.5f) * profile.idleJitter }
        val side = if (segmentCounter % 2 == 0) 1f else -1f
        val randomSide = if (rng.nextBoolean()) 1f else -1f

        when (mood) {
            Mood.PLAYFUL -> applyPlayful(view, segment, profile, energy, jitter, side, randomSide)
            Mood.EXCITED -> applyExcited(view, segment, profile, energy, jitter, side, randomSide)
            Mood.CALM -> applyCalm(view, segment, profile, energy, jitter, side)
            Mood.CHEERFUL -> applyCheerful(view, segment, profile, energy, jitter, side, randomSide)
            Mood.SERIOUS -> applySerious(view, segment, profile, energy, jitter, side)
            Mood.NEUTRAL -> applyNeutral(view, segment, profile, energy, jitter, side, randomSide)
        }
    }

    private suspend fun applyPlayful(
        view: View, seg: GestureSegment, p: MotionProfile,
        energy: Float, jitter: () -> Float, side: Float, rSide: Float
    ) {
        val dur = seg.durationMs
        when (seg.gesture) {
            GestureType.QUESTION -> {
                // Curious head-cock with bounce
                animate(view, rotation = -14f * energy + jitter(), scaleX = 0.96f, scaleY = 1.04f,
                    tx = -6f * energy, ty = -8f * energy, ms = 200, bounce = true)
                delay(dur / 2)
                animate(view, rotation = 6f * energy + jitter(), scaleX = 1.02f, scaleY = 0.98f,
                    tx = 4f * energy, ty = -3f * energy, ms = 200, bounce = true)
                delay(dur / 2)
            }
            GestureType.EXCLAMATION -> {
                // Big jump up then wiggle
                animate(view, rotation = rSide * 5f, scaleX = 1.15f, scaleY = 1.15f,
                    tx = 0f, ty = -18f * energy, ms = 150, overshoot = 2.5f)
                delay(dur / 3)
                animate(view, rotation = -rSide * 8f + jitter(), scaleX = 1.06f, scaleY = 1.06f,
                    tx = rSide * 10f, ty = -6f, ms = 180, bounce = true)
                delay(dur / 3)
                animate(view, rotation = rSide * 4f + jitter(), scaleX = 1.02f, scaleY = 1.02f,
                    tx = -rSide * 5f, ty = -2f, ms = 180)
                delay(dur / 3)
            }
            GestureType.LISTING -> {
                // Bouncy left-right counting
                val items = (seg.text.split(",").size).coerceIn(2, 5)
                val stepDur = (dur / items).coerceAtLeast(120)
                for (i in 0 until items) {
                    val s = if (i % 2 == 0) 1f else -1f
                    animate(view, rotation = s * 10f * energy + jitter(),
                        scaleX = 1.04f, scaleY = 1.04f,
                        tx = s * 12f * energy, ty = -4f + jitter(), ms = (stepDur * 0.7).toLong(),
                        bounce = true)
                    delay(stepDur)
                }
            }
            GestureType.PAUSE -> {
                // Little wiggle settle
                animate(view, rotation = jitter() * 3f, scaleX = 1.0f, scaleY = 1.0f,
                    tx = jitter() * 2f, ty = jitter() * 2f, ms = 150)
                delay(seg.durationMs)
            }
            else -> {
                // Playful sway with random direction changes
                val steps = (dur / 250).toInt().coerceIn(2, 6)
                val stepDur = dur / steps
                for (i in 0 until steps) {
                    val s = if (rng.nextBoolean()) 1f else -1f
                    animate(view,
                        rotation = s * p.rotationRange * energy * rng.nextFloat() + jitter(),
                        scaleX = 1f + rng.nextFloat() * p.scaleRange,
                        scaleY = 1f + rng.nextFloat() * p.scaleRange,
                        tx = s * p.horizontalRange * energy * rng.nextFloat() + jitter(),
                        ty = -p.verticalRange * energy * rng.nextFloat() + jitter(),
                        ms = (stepDur * 0.7).toLong(),
                        bounce = rng.nextFloat() > 0.5f)
                    delay(stepDur)
                }
            }
        }
    }

    private suspend fun applyExcited(
        view: View, seg: GestureSegment, p: MotionProfile,
        energy: Float, jitter: () -> Float, side: Float, rSide: Float
    ) {
        val dur = seg.durationMs
        when (seg.gesture) {
            GestureType.QUESTION -> {
                // Eager forward lean
                animate(view, rotation = -6f * energy, scaleX = 1.08f, scaleY = 1.12f,
                    tx = 0f, ty = -14f * energy, ms = 180, overshoot = 2.2f)
                delay(dur / 2)
                animate(view, rotation = 4f + jitter(), scaleX = 1.04f, scaleY = 1.04f,
                    tx = 5f * rSide, ty = -6f, ms = 200)
                delay(dur / 2)
            }
            GestureType.EXCLAMATION -> {
                // Explosive: scale up big, then rapid shake
                animate(view, rotation = 0f, scaleX = 1.2f, scaleY = 1.2f,
                    tx = 0f, ty = -20f * energy, ms = 120, overshoot = 3f)
                delay(dur / 4)
                for (i in 0 until 3) {
                    val s = if (i % 2 == 0) 1f else -1f
                    animate(view, rotation = s * 12f * energy, scaleX = 1.1f, scaleY = 1.1f,
                        tx = s * 8f * energy, ty = -12f, ms = 80)
                    delay(100)
                }
                delay(dur / 4)
            }
            GestureType.EMPHASIS -> {
                // Jabbing forward motion
                animate(view, rotation = side * 3f, scaleX = 1.1f, scaleY = 1.14f,
                    tx = side * 6f * energy, ty = -15f * energy, ms = 200, overshoot = 2f)
                delay(dur / 2)
                animate(view, rotation = -side * 2f + jitter(), scaleX = 1.04f, scaleY = 1.04f,
                    tx = -side * 3f, ty = -5f, ms = 250)
                delay(dur / 2)
            }
            GestureType.PAUSE -> {
                // Quick vibrate then still
                animate(view, rotation = jitter() * 4f, scaleX = 1.02f, scaleY = 1.02f,
                    tx = jitter() * 3f, ty = -2f, ms = 80)
                delay(seg.durationMs)
            }
            else -> {
                // Fast alternating jabs
                val steps = (dur / 200).toInt().coerceIn(2, 8)
                val stepDur = dur / steps
                for (i in 0 until steps) {
                    val s = if (i % 2 == 0) 1f else -1f
                    animate(view,
                        rotation = s * p.rotationRange * energy * (0.6f + rng.nextFloat() * 0.4f) + jitter(),
                        scaleX = 1f + p.scaleRange * (0.5f + rng.nextFloat() * 0.5f),
                        scaleY = 1f + p.scaleRange * (0.5f + rng.nextFloat() * 0.5f),
                        tx = s * p.horizontalRange * energy * (0.5f + rng.nextFloat() * 0.5f) + jitter(),
                        ty = -p.verticalRange * energy * (0.3f + rng.nextFloat() * 0.7f) + jitter(),
                        ms = (stepDur * 0.6).toLong(),
                        overshoot = 1.8f)
                    delay(stepDur)
                }
            }
        }
    }

    private suspend fun applyCalm(
        view: View, seg: GestureSegment, p: MotionProfile,
        energy: Float, jitter: () -> Float, side: Float
    ) {
        val dur = seg.durationMs
        when (seg.gesture) {
            GestureType.QUESTION -> {
                // Gentle tilt, slow and smooth
                animate(view, rotation = -4f * energy, scaleX = 0.99f, scaleY = 1.01f,
                    tx = -2f, ty = -2f * energy, ms = 500)
                delay(dur)
            }
            GestureType.EXCLAMATION -> {
                // Subtle rise, no overshoot
                animate(view, rotation = side * 2f, scaleX = 1.03f, scaleY = 1.05f,
                    tx = side * 2f, ty = -5f * energy, ms = 400)
                delay(dur)
            }
            GestureType.PAUSE -> {
                // Almost still, breathing motion
                animate(view, rotation = 0f, scaleX = 1.005f, scaleY = 1.01f,
                    tx = 0f, ty = -1f, ms = 300)
                delay(seg.durationMs)
            }
            else -> {
                // Slow gentle sway like breathing
                val half = dur / 2
                animate(view,
                    rotation = side * p.rotationRange * energy * 0.4f,
                    scaleX = 1f + p.scaleRange * 0.3f,
                    scaleY = 1f + p.scaleRange * 0.5f,
                    tx = side * p.horizontalRange * energy * 0.3f,
                    ty = -p.verticalRange * energy * 0.3f,
                    ms = (half * 0.8).toLong())
                delay(half)
                animate(view,
                    rotation = -side * p.rotationRange * energy * 0.3f + jitter(),
                    scaleX = 1f + p.scaleRange * 0.2f,
                    scaleY = 1f + p.scaleRange * 0.3f,
                    tx = -side * p.horizontalRange * energy * 0.2f,
                    ty = -p.verticalRange * energy * 0.2f,
                    ms = (half * 0.8).toLong())
                delay(half)
            }
        }
    }

    private suspend fun applyCheerful(
        view: View, seg: GestureSegment, p: MotionProfile,
        energy: Float, jitter: () -> Float, side: Float, rSide: Float
    ) {
        val dur = seg.durationMs
        when (seg.gesture) {
            GestureType.QUESTION -> {
                // Perky tilt with upward bob
                animate(view, rotation = -10f * energy, scaleX = 1.02f, scaleY = 1.06f,
                    tx = -4f * energy, ty = -10f * energy, ms = 250, overshoot = 1.5f)
                delay(dur / 2)
                animate(view, rotation = 3f + jitter(), scaleX = 1.03f, scaleY = 1.03f,
                    tx = 2f, ty = -4f, ms = 250)
                delay(dur / 2)
            }
            GestureType.EXCLAMATION -> {
                // Happy bounce up
                animate(view, rotation = rSide * 6f, scaleX = 1.1f, scaleY = 1.12f,
                    tx = rSide * 4f, ty = -16f * energy, ms = 180, overshoot = 2f)
                delay(dur / 3)
                animate(view, rotation = -rSide * 4f + jitter(), scaleX = 1.05f, scaleY = 1.05f,
                    tx = -rSide * 3f, ty = -5f, ms = 220, bounce = true)
                delay(dur / 3)
                animate(view, rotation = rSide * 2f, scaleX = 1.02f, scaleY = 1.02f,
                    tx = rSide * 1f, ty = -2f, ms = 200)
                delay(dur / 3)
            }
            GestureType.LISTING -> {
                // Bobbing count
                val items = (seg.text.split(",").size).coerceIn(2, 5)
                val stepDur = (dur / items).coerceAtLeast(120)
                for (i in 0 until items) {
                    animate(view, rotation = (if (i % 2 == 0) 5f else -5f) * energy + jitter(),
                        scaleX = 1.04f, scaleY = 1.06f,
                        tx = (if (i % 2 == 0) 6f else -6f) * energy,
                        ty = -8f * energy, ms = (stepDur * 0.5).toLong(), overshoot = 1.4f)
                    delay(stepDur)
                }
            }
            GestureType.PAUSE -> {
                animate(view, rotation = jitter() * 2f, scaleX = 1.01f, scaleY = 1.01f,
                    tx = jitter(), ty = -1f, ms = 200)
                delay(seg.durationMs)
            }
            else -> {
                // Upbeat bobbing with side sway
                val steps = (dur / 300).toInt().coerceIn(2, 5)
                val stepDur = dur / steps
                for (i in 0 until steps) {
                    val s = if (i % 2 == 0) 1f else -1f
                    val bobUp = if (i % 2 == 0) -8f else -3f
                    animate(view,
                        rotation = s * p.rotationRange * energy * (0.5f + rng.nextFloat() * 0.5f) + jitter(),
                        scaleX = 1f + p.scaleRange * (0.4f + rng.nextFloat() * 0.6f),
                        scaleY = 1f + p.scaleRange * (0.5f + rng.nextFloat() * 0.5f),
                        tx = s * p.horizontalRange * energy * 0.6f + jitter(),
                        ty = bobUp * energy + jitter(),
                        ms = (stepDur * 0.65).toLong(),
                        overshoot = 1.3f)
                    delay(stepDur)
                }
            }
        }
    }

    private suspend fun applySerious(
        view: View, seg: GestureSegment, p: MotionProfile,
        energy: Float, jitter: () -> Float, side: Float
    ) {
        val dur = seg.durationMs
        when (seg.gesture) {
            GestureType.QUESTION -> {
                // Deliberate slight tilt, minimal movement
                animate(view, rotation = -5f * energy, scaleX = 1.0f, scaleY = 1.02f,
                    tx = -2f, ty = -3f * energy, ms = 400)
                delay(dur)
            }
            GestureType.EXCLAMATION -> {
                // Firm forward nod
                animate(view, rotation = 0f, scaleX = 1.04f, scaleY = 1.06f,
                    tx = 0f, ty = -10f * energy, ms = 250)
                delay(dur / 2)
                animate(view, rotation = 0f, scaleX = 1.01f, scaleY = 1.01f,
                    tx = 0f, ty = -3f, ms = 350)
                delay(dur / 2)
            }
            GestureType.EMPHASIS -> {
                // Slow deliberate nod
                animate(view, rotation = side * 2f, scaleX = 1.02f, scaleY = 1.05f,
                    tx = 0f, ty = -8f * energy, ms = 350)
                delay(dur / 2)
                animate(view, rotation = 0f, scaleX = 1.0f, scaleY = 1.01f,
                    tx = 0f, ty = -2f, ms = 400)
                delay(dur / 2)
            }
            GestureType.PAUSE -> {
                // Still
                animate(view, rotation = 0f, scaleX = 1.0f, scaleY = 1.0f,
                    tx = 0f, ty = 0f, ms = 200)
                delay(seg.durationMs)
            }
            else -> {
                // Minimal controlled sway
                animate(view,
                    rotation = side * p.rotationRange * energy * 0.5f,
                    scaleX = 1f + p.scaleRange * 0.2f,
                    scaleY = 1f + p.scaleRange * 0.3f,
                    tx = side * p.horizontalRange * energy * 0.3f,
                    ty = -p.verticalRange * energy * 0.4f,
                    ms = (dur * 0.6).toLong())
                delay(dur)
            }
        }
    }

    private suspend fun applyNeutral(
        view: View, seg: GestureSegment, p: MotionProfile,
        energy: Float, jitter: () -> Float, side: Float, rSide: Float
    ) {
        val dur = seg.durationMs
        when (seg.gesture) {
            GestureType.QUESTION -> {
                animate(view, rotation = -8f * energy + jitter(), scaleX = 0.97f, scaleY = 1.01f,
                    tx = -5f * energy + jitter(), ty = -5f * energy, ms = 280)
                delay(dur)
            }
            GestureType.EXCLAMATION -> {
                animate(view, rotation = rSide * 3f, scaleX = 1.08f, scaleY = 1.08f,
                    tx = rSide * 3f, ty = -11f * energy, ms = 200, overshoot = 1.8f)
                delay(dur / 2)
                animate(view, rotation = jitter() * 2f, scaleX = 1.02f, scaleY = 1.02f,
                    tx = jitter() * 2f, ty = -3f, ms = 250)
                delay(dur / 2)
            }
            GestureType.EMPHASIS -> {
                animate(view, rotation = side * 4f * energy + jitter(),
                    scaleX = 1.05f, scaleY = 1.05f,
                    tx = side * 5f * energy + jitter(), ty = -7f * energy, ms = 400)
                delay(dur)
            }
            GestureType.RAPID -> {
                animate(view, rotation = side * p.rotationRange * energy * 0.6f + jitter(),
                    scaleX = 1.01f, scaleY = 1.01f,
                    tx = side * p.horizontalRange * energy * 0.5f + jitter(),
                    ty = -3f * energy, ms = 120, overshoot = 1.4f)
                delay(dur)
            }
            GestureType.LISTING -> {
                val items = (seg.text.split(",").size).coerceIn(2, 5)
                val stepDur = (dur / items).coerceAtLeast(120)
                for (i in 0 until items) {
                    val s = if (i % 2 == 0) 1f else -1f
                    animate(view, rotation = s * 6f * energy + jitter(),
                        scaleX = 1.03f, scaleY = 1.01f,
                        tx = s * 8f * energy + jitter(), ty = -4f + jitter(),
                        ms = (stepDur * 0.6).toLong())
                    delay(stepDur)
                }
            }
            GestureType.PAUSE -> {
                animate(view, rotation = 0f, scaleX = 1.0f, scaleY = 1.0f,
                    tx = 0f, ty = 0f, ms = 150)
                delay(seg.durationMs)
            }
            GestureType.NEUTRAL -> {
                val steps = (dur / 350).toInt().coerceIn(1, 4)
                val stepDur = dur / steps
                for (i in 0 until steps) {
                    val s = if ((segmentCounter + i) % 2 == 0) 1f else -1f
                    animate(view,
                        rotation = s * p.rotationRange * energy * (0.3f + rng.nextFloat() * 0.5f) + jitter(),
                        scaleX = 1f + rng.nextFloat() * p.scaleRange * 0.5f,
                        scaleY = 1f + rng.nextFloat() * p.scaleRange * 0.5f,
                        tx = s * p.horizontalRange * energy * (0.3f + rng.nextFloat() * 0.4f) + jitter(),
                        ty = -p.verticalRange * energy * (0.2f + rng.nextFloat() * 0.4f) + jitter(),
                        ms = (stepDur * 0.65).toLong())
                    delay(stepDur)
                }
            }
        }
    }

    private fun motionProfile(mood: Mood): MotionProfile = when (mood) {
        Mood.CHEERFUL -> MotionProfile(
            rotationRange = 14f, horizontalRange = 12f, verticalRange = 16f,
            scaleRange = 0.08f, speedFactor = 1.18f, bounciness = 1.8f, idleJitter = 4f
        )
        Mood.EXCITED -> MotionProfile(
            rotationRange = 20f, horizontalRange = 18f, verticalRange = 24f,
            scaleRange = 0.11f, speedFactor = 1.55f, bounciness = 2.4f, idleJitter = 7f
        )
        Mood.PLAYFUL -> MotionProfile(
            rotationRange = 18f, horizontalRange = 22f, verticalRange = 16f,
            scaleRange = 0.10f, speedFactor = 1.35f, bounciness = 2.5f, idleJitter = 8f
        )
        Mood.CALM -> MotionProfile(
            rotationRange = 4f, horizontalRange = 3f, verticalRange = 4f,
            scaleRange = 0.02f, speedFactor = 0.6f, bounciness = 0f, idleJitter = 1f
        )
        Mood.SERIOUS -> MotionProfile(
            rotationRange = 6f, horizontalRange = 2f, verticalRange = 10f,
            scaleRange = 0.035f, speedFactor = 0.78f, bounciness = 0f, idleJitter = 1.2f
        )
        Mood.NEUTRAL -> MotionProfile(
            rotationRange = 9f, horizontalRange = 7f, verticalRange = 8f,
            scaleRange = 0.05f, speedFactor = 1f, bounciness = 1.1f, idleJitter = 3f
        )
    }

    private suspend fun animate(
        view: View,
        rotation: Float, scaleX: Float, scaleY: Float,
        tx: Float, ty: Float, ms: Long,
        overshoot: Float = 0f,
        bounce: Boolean = false
    ) {
        withContext(Dispatchers.Main) {
            val interpolator = when {
                bounce -> BounceInterpolator()
                overshoot > 0f -> OvershootInterpolator(overshoot)
                else -> AccelerateDecelerateInterpolator()
            }

            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(view, "rotation", rotation),
                    ObjectAnimator.ofFloat(view, "scaleX", scaleX),
                    ObjectAnimator.ofFloat(view, "scaleY", scaleY),
                    ObjectAnimator.ofFloat(view, "translationX", tx),
                    ObjectAnimator.ofFloat(view, "translationY", ty)
                )
                duration = ms.coerceAtLeast(50)
                this.interpolator = interpolator
                start()
            }
        }
    }

    private fun resetToRest(view: View) {
        scope.launch(Dispatchers.Main) {
            AnimatorSet().apply {
                playTogether(
                    ObjectAnimator.ofFloat(view, "rotation", 0f),
                    ObjectAnimator.ofFloat(view, "scaleX", 1f),
                    ObjectAnimator.ofFloat(view, "scaleY", 1f),
                    ObjectAnimator.ofFloat(view, "translationX", 0f),
                    ObjectAnimator.ofFloat(view, "translationY", 0f)
                )
                duration = 400
                interpolator = DecelerateInterpolator()
                start()
            }
        }
    }

    fun stop() {
        animJob?.cancel()
        animJob = null
    }
}
