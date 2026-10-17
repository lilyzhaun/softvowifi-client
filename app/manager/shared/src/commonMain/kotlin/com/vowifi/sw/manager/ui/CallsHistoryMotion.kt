package com.vowifi.sw.manager.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.Box
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val CallsHistoryPlacement = spring<IntOffset>(
    dampingRatio = MotionTokens.SpringSpatial.dampingRatio, stiffness = MotionTokens.SpringSpatial.stiffness)

private class CallsHistoryExit<Row>(val index: Int, val row: Row, val alpha: Animatable<Float, AnimationVector1D>)

private class CallsHistoryRowMemory<Row, Key>(var previous: List<Row>, var change: HistoryListChange<CallHistoryRecordId>?) {
    var pending: Set<CallHistoryRecordId> = emptySet()
    var retained by mutableStateOf<Map<Key, CallsHistoryExit<Row>>>(emptyMap())
    var placementKey: Any? = null
    var placement = false
}

internal class CallsHistoryRows<Row, Key>(
    val rows: List<Row>,
    val entering: Set<Key>,
    val removing: Map<Key, State<Float>>,
    val placement: Boolean,
    val placementKey: Any?,
    val selection: Any?,
)

/** Only retain visual slots from a confirmed USER_DELETE; this does not own business state. */
@Composable
internal fun <Row, Key> rememberCallsHistoryRows(
    rows: List<Row>, change: HistoryListChange<CallHistoryRecordId>?, selection: Any?, active: Boolean,
    key: (Row) -> Key, ids: (Row) -> Set<CallHistoryRecordId>,
    pending: Set<CallHistoryRecordId> = emptySet(),
): CallsHistoryRows<Row, Key> {
    val memory = remember(selection, active) { CallsHistoryRowMemory<Row, Key>(rows, change) }
    val old = memory.change
    val fresh = active && old != null && change != null && old.scope === change.scope && change.version > old.version
    val currentKeys = rows.map(key).toSet()
    val beforeKeys = memory.previous.map(key).toSet()
    val entering = if (fresh && change?.reason == HistoryChangeReason.LIVE_INSERT)
        rows.filter { key(it) !in beforeKeys && ids(it).any { id -> id in change.ids } }.map(key).toSet() else emptySet()
    val retained = memory.retained.toMutableMap()
    if (fresh && change?.reason == HistoryChangeReason.REPLACE) retained.clear()
    if (fresh && change?.reason == HistoryChangeReason.USER_DELETE) {
        memory.previous.forEachIndexed { index, row ->
            if (key(row) !in currentKeys && ids(row).any { it in change.ids }) {
                val drawnAlpha = if (ids(row).any { it in memory.pending }) MotionTokens.AlphaPending else 1f
                retained[key(row)] = CallsHistoryExit(index, row, Animatable(drawnAlpha))
            }
        }
    }
    retained.keys.removeAll(currentKeys)
    val displayed = rows.toMutableList()
    retained.values.sortedBy { it.index }.forEach { displayed.add(it.index.coerceAtMost(displayed.size), it.row) }
    if (fresh && change?.reason != HistoryChangeReason.CONTENT) {
        memory.placementKey = change
        memory.placement = change?.reason in setOf(HistoryChangeReason.LIVE_INSERT, HistoryChangeReason.LIVE_MOVE)
    }
    // Consume the cause before any zero-scale completion, so it cannot restore an old slot.
    memory.retained = retained
    memory.previous = rows
    memory.pending = pending
    memory.change = change
    // These local draw effects survive lazy disposal: scrolling away cannot strand an exit.
    retained.forEach { (rowKey, exit) ->
        androidx.compose.runtime.key(rowKey) {
            LaunchedEffect(exit) {
                exit.alpha.animateTo(0f, tween(MotionTokens.DurFast, easing = MotionTokens.EaseExit))
                if (memory.retained[rowKey] === exit) {
                    memory.placementKey = Any()
                    memory.placement = true
                    memory.retained = memory.retained - rowKey
                }
            }
        }
    }
    return CallsHistoryRows(displayed, entering, retained.mapValues { it.value.alpha.asState() },
        memory.placement, memory.placementKey, selection)
}

@Composable
internal fun CallsHistoryRowMotion(
    modifier: Modifier, rowKey: Any, entering: Boolean, pending: Boolean, exitAlpha: State<Float>?,
    content: @Composable () -> Unit,
) {
    val active = LocalAppRouteActive.current
    val alpha = remember(rowKey) { Animatable(if (entering) 0f else 1f) }
    LaunchedEffect(alpha) {
        if (entering) alpha.animateTo(1f, tween(MotionTokens.DurBase, easing = MotionTokens.EaseEnter))
    }
    Box(modifier.graphicsLayer { this.alpha = exitAlpha?.value ?: if (pending) MotionTokens.AlphaPending else alpha.value }
        .then(if (!active || exitAlpha != null) Modifier.clearAndSetSemantics { }
            else if (pending) Modifier.semantics(mergeDescendants = true) {
                disabled(); stateDescription = "正在删除"
            } else Modifier)) {
        content()
    }
}

private class CallsHistoryPlacementBaseline {
    var offset: IntOffset? = null
    var cause: Any? = null
    var job: Job? = null
    var active = true
}

/** Only a real spatial cause or the completed exit starts placement; L7/scroll stays natural. */
@Composable
internal fun Modifier.callsHistoryPlacement(rows: CallsHistoryRows<*, *>?): Modifier {
    val active = LocalAppRouteActive.current
    val baseline = remember(rows?.selection) { CallsHistoryPlacementBaseline() }
    val offset = remember(rows?.selection) { Animatable(IntOffset.Zero, IntOffset.VectorConverter) }
    val scope = rememberCoroutineScope()
    DisposableEffect(baseline) { onDispose { baseline.job?.cancel() } }
    SideEffect {
        if (baseline.active && !active) {
            baseline.offset = null
            baseline.cause = null
            baseline.job?.cancel()
            baseline.job = scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(IntOffset.Zero) }
        }
        baseline.active = active
    }
    return onPlaced { coordinates ->
        val point = coordinates.positionInParent()
        val target = IntOffset(point.x.roundToInt(), point.y.roundToInt())
        if (!active) { baseline.offset = target; return@onPlaced }
        val previous = baseline.offset
        if (baseline.cause !== rows?.placementKey) {
            baseline.cause = rows?.placementKey
            baseline.job?.cancel()
            val from = if (rows?.placement == true && previous != null) offset.value + previous - target else IntOffset.Zero
            baseline.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                offset.snapTo(from)
                if (from != IntOffset.Zero) offset.animateTo(IntOffset.Zero, CallsHistoryPlacement)
            }
        }
        baseline.offset = target
    }.graphicsLayer { translationX = offset.value.x.toFloat(); translationY = offset.value.y.toFloat() }
}

/** Replacement owns one local draw layer; CONTENT does not restart its fade. */
@Composable
internal fun Modifier.callsHistoryReplacementFade(change: HistoryListChange<*>?, selection: Any?): Modifier {
    val baseline = remember(selection, change?.scope) { longArrayOf(-1L) }
    val replacement = if (change?.reason == HistoryChangeReason.REPLACE) change.version else baseline[0]
    SideEffect { baseline[0] = replacement }
    val alpha = remember(selection, change?.scope, replacement) { Animatable(0f) }
    LaunchedEffect(alpha) { alpha.animateTo(1f, tween(MotionTokens.DurFast, easing = MotionTokens.EaseEnter)) }
    return graphicsLayer { this.alpha = alpha.value }
}
