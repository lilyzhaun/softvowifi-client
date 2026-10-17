package com.vowifi.sw.manager.ui

import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Ordinary, non-Composable core shared by explicit-source and public indication adapters.
 * It owns only drawing, never a clickable, raw pointer listener, business action or haptic.
 */
internal class ManagerPressMotionCore(style: ManagerPressStyle, private val gate: State<Boolean>) {
    private val profile = managerPressMotionProfile(style)
    private var animations by mutableStateOf(ManagerPressMotionAnimations())
    private var connected by mutableStateOf(false)
    private var binding = 0
    private var press: PressInteraction.Press? = null
    private var released = false
    private var minimumReached = false
    private var minimumJob: Job? = null
    private var sourceJob: Job? = null
    private var gateJob: Job? = null

    val motion = ManagerPressMotion(
        scale = derivedStateOf { if (connected && gate.value) animations.scale.value else 1f },
        contentAlpha = derivedStateOf { if (connected && gate.value) animations.contentAlpha.value else 1f },
        tintAlpha = derivedStateOf { if (connected && gate.value) animations.tintAlpha.value else 0f },
    )

    fun connect(scope: CoroutineScope, source: InteractionSource) {
        disconnect()
        connected = true
        val owner = binding
        // Subscribe before the first pointer, not after capturing a library source and recomposing.
        sourceJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            source.interactions.collect { interaction ->
                if (connected && owner == binding) when (interaction) {
                    is PressInteraction.Press -> onPress(scope, interaction)
                    is PressInteraction.Release -> onRelease(scope, interaction.press)
                    is PressInteraction.Cancel -> onCancel(scope, interaction.press)
                    else -> Unit // Focus and hover are not press state.
                }
            }
        }
        gateJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            snapshotFlow { gate.value to instant(scope) }.collect { (allowed, zero) ->
                if (connected && owner == binding) {
                    if (!allowed) invalidate()
                    else if (zero) {
                        minimumJob?.cancel()
                        minimumReached = true
                        if (released) recover(scope, canceled = false)
                        else animations.retarget(scope, profile, press != null, canceled = false, instant = true)
                    }
                }
            }
        }
    }

    fun disconnect() {
        connected = false
        binding++
        sourceJob?.cancel()
        gateJob?.cancel()
        sourceJob = null
        gateJob = null
        invalidate()
    }

    private fun onPress(scope: CoroutineScope, current: PressInteraction.Press) {
        if (!gate.value) return
        minimumJob?.cancel()
        press = current
        released = false
        minimumReached = instant(scope)
        animations.retarget(scope, profile, pressed = true, canceled = false, instant = instant(scope))
        if (!minimumReached) minimumJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // The first visible frame and animation frames share one clock. A wall-clock delay
            // cannot measure visible time when a host renders many frames without sleeping.
            val firstVisibleFrame = withFrameNanos { it }
            val minimumNanos = MotionTokens.PressMinimumVisibleMillis * 1_000_000L
            var frame = firstVisibleFrame
            while (frame - firstVisibleFrame < minimumNanos) {
                frame = withFrameNanos { it }
            }
            if (press === current && connected && gate.value) {
                minimumReached = true
                if (released) recover(scope, canceled = false)
            }
        }
    }

    private fun onRelease(scope: CoroutineScope, current: PressInteraction.Press) {
        if (press !== current) return
        if (!gate.value) { invalidate(); return }
        released = true
        if (minimumReached || instant(scope)) recover(scope, canceled = false)
        // Otherwise leave every channel on the SAME pressed target until the 90ms deadline.
    }

    private fun onCancel(scope: CoroutineScope, current: PressInteraction.Press) {
        if (press !== current) return
        if (!gate.value) { invalidate(); return }
        recover(scope, canceled = true)
    }

    private fun recover(scope: CoroutineScope, canceled: Boolean) {
        minimumJob?.cancel()
        minimumJob = null
        press = null
        released = false
        animations.retarget(scope, profile, pressed = false, canceled = canceled, instant = instant(scope))
    }

    private fun invalidate() {
        if (press == null && minimumJob?.isActive != true && animations.isAtRest) return
        minimumJob?.cancel()
        minimumJob = null
        press = null
        released = false
        minimumReached = false
        animations.stop()
        // Immediate rest, including re-enabled gates. Canceled old jobs retain only old channels.
        animations = ManagerPressMotionAnimations()
    }

    private fun instant(scope: CoroutineScope): Boolean =
        (scope.coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f) == 0f
}
