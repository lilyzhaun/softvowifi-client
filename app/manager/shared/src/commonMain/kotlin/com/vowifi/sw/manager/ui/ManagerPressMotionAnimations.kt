package com.vowifi.sw.manager.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Retarget each channel from its current value AND velocity; no restart from a preset origin. */
internal class ManagerPressMotionAnimations {
    val scale = Animatable(1f)
    val contentAlpha = Animatable(1f)
    val tintAlpha = Animatable(0f)
    private val jobs = mutableListOf<Job>()

    val isAtRest: Boolean
        get() = jobs.none { it.isActive } && scale.value == 1f && contentAlpha.value == 1f && tintAlpha.value == 0f

    fun retarget(
        scope: CoroutineScope,
        profile: ManagerPressMotionProfile,
        pressed: Boolean,
        canceled: Boolean,
        instant: Boolean,
    ) {
        // Canceling an Animatable's old job clears its velocity; capture all velocities first.
        val scaleVelocity = scale.velocity
        val alphaVelocity = contentAlpha.velocity
        val tintVelocity = tintAlpha.velocity
        stop()
        val scaleSpec = when {
            pressed -> profile.pressScaleSpec
            canceled -> profile.cancelScaleSpec
            else -> profile.releaseScaleSpec
        }
        move(scope, scale, if (pressed) profile.pressedScale else 1f, scaleSpec, scaleVelocity, instant)
        move(scope, contentAlpha, if (pressed) profile.pressedAlpha else 1f, profile.alphaSpec, alphaVelocity, instant)
        move(scope, tintAlpha, if (pressed) profile.pressedTint else 0f,
            if (pressed) profile.tintInSpec else profile.tintOutSpec, tintVelocity, instant)
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private fun move(
        scope: CoroutineScope,
        value: Animatable<Float, AnimationVector1D>,
        target: Float,
        spec: AnimationSpec<Float>,
        velocity: Float,
        instant: Boolean,
    ) {
        if (value.value == target && velocity == 0f) return
        jobs += scope.launch(start = CoroutineStart.UNDISPATCHED) {
            if (instant) value.snapTo(target)
            else value.animateTo(target, animationSpec = spec, initialVelocity = velocity)
        }
    }
}
