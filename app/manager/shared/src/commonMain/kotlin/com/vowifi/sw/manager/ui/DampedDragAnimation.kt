// SPDX-License-Identifier: GPL-3.0-only
// Closely adapted from KernelSU DampedDragAnimation.kt @ befbcd388bb6c10befd3645311ad08e556d362ac.
package com.vowifi.sw.manager.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

internal class DampedDragAnimation(
    private val scope: CoroutineScope,
    initialValue: Float,
    private val valueRange: ClosedRange<Float>,
    visibilityThreshold: Float,
    initialScale: Float,
    private val pressedScale: Float,
    private val canDrag: (Offset) -> Boolean = { true },
    private val onDragStarted: DampedDragAnimation.(Offset) -> Unit,
    private val onDragStopped: DampedDragAnimation.() -> Unit,
    private val onDragCancelled: DampedDragAnimation.() -> Unit,
    private val onDrag: DampedDragAnimation.(IntSize, Offset, Offset) -> Unit,
) {
    private val valueSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocitySpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressSpec = spring(1f, 1000f, 0.001f)
    private val scaleXSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYSpec = spring(0.7f, 250f, 0.001f)
    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)
    private val mutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()
    private var transferredValue by mutableStateOf<Float?>(null)
    private var transferJob: Job? = null

    val value: Float get() = transferredValue ?: valueAnimation.value
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectBarDragGestures(
            onDragStart = { down -> onDragStarted(down.position); press() },
            onDragEnd = { onDragStopped(); release() },
            onDragCancel = { onDragCancelled(); release() },
        ) { change, amount ->
            if (canDrag(change.position) && canDrag(change.previousPosition)) onDrag(size, change.position, amount)
        }
    }

    fun press() {
        velocityTracker.resetTracking()
        scope.launch {
            launch { pressAnimation.animateTo(1f, pressSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYSpec) }
        }
    }

    fun release() {
        scope.launch {
            withFrameNanos { }
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }.first { abs(it - valueAnimation.targetValue) < threshold }
            }
            launch { pressAnimation.animateTo(0f, pressSpec) }
            launch { scaleXAnimation.animateTo(1f, scaleXSpec) }
            launch { scaleYAnimation.animateTo(1f, scaleYSpec) }
        }
    }

    fun updateValue(value: Float) {
        scope.launch {
            transferJob?.join()
            valueAnimation.animateTo(value.coerceIn(valueRange), valueSpec) { updateVelocity() }
        }
    }

    /** Snap may suspend while cancelling an old spring; expose the transferred origin immediately. */
    fun snapToValue(value: Float) {
        val origin = value.coerceIn(valueRange)
        transferredValue = origin
        transferJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            valueAnimation.snapTo(origin)
            transferredValue = null
        }
    }

    fun animateToValue(value: Float) {
        scope.launch {
            transferJob?.join()
            mutex.mutate {
                press()
                launch { valueAnimation.animateTo(value.coerceIn(valueRange), valueSpec) }
                if (velocity != 0f) launch { velocityAnimation.animateTo(0f, velocitySpec) }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(System.currentTimeMillis(), Offset(value, 0f))
        val distance = valueRange.endInclusive - valueRange.start
        val target = if (distance == 0f) 0f else velocityTracker.calculateVelocity().x / distance
        scope.launch { velocityAnimation.animateTo(target, velocitySpec) }
    }
}
