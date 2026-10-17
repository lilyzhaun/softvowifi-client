package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.lazy.LazyListState
import com.vowifi.sw.manager.model.HistoryChangeReason
import com.vowifi.sw.manager.model.HistoryListChange
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlin.math.roundToInt

/** P5 keeps its source-owned visual cycle without inserting another content layout coordinator. */
@Composable
internal fun Modifier.messageBubbleContentPress(feedback: ManagerActionFeedback, source: InteractionSource): Modifier {
    val motion = rememberManagerPressMotion(source, feedback.accepted, ManagerPressStyle.Content)
    val paint = remember { Paint() }
    return drawWithContent {
        val factor = motion.scale.value
        val opacity = motion.contentAlpha.value.coerceIn(0f, 1f)
        if (factor == 1f && opacity == 1f) this@drawWithContent.drawContent()
        else scale(factor) {
            if (opacity == 1f) this@drawWithContent.drawContent()
            else {
                paint.alpha = opacity
                val canvas = drawContext.canvas
                canvas.saveLayer(Rect(Offset.Zero, size), paint)
                try { this@drawWithContent.drawContent() } finally { canvas.restore() }
            }
        }
    }
}

internal val MessagePlacementSpring = spring<IntOffset>(
    dampingRatio = MotionTokens.SpringSpatial.dampingRatio, stiffness = MotionTokens.SpringSpatial.stiffness)

private class MessageThreadPlacementBaseline {
    var position: IntOffset? = null
    var parentSize: IntSize? = null
    var version: Long? = null
    var job: Job? = null
}

/** Compensate the original whole-item node, not a separate child of an untransformed row. */
@Composable
internal fun Modifier.messageThreadItemPlacement(
    change: HistoryListChange<*>?, selection: Any?, enabled: Boolean, listState: LazyListState,
): Modifier {
    val baseline = remember(selection, change?.scope) { MessageThreadPlacementBaseline() }
    val motion = remember(selection, change?.scope) { Animatable(IntOffset.Zero, IntOffset.VectorConverter) }
    var delta by remember(selection, change?.scope) { mutableStateOf(IntOffset.Zero) }
    val scope = rememberCoroutineScope()
    DisposableEffect(baseline) { onDispose { baseline.job?.cancel() } }
    return onPlaced { coordinates ->
        // Observe before the same item's layer; surface, text and their bounds share that owner.
        val point = coordinates.positionInParent()
        val target = IntOffset(point.x.roundToInt(), point.y.roundToInt())
        val previous = baseline.position
        val parentSize = coordinates.parentLayoutCoordinates?.size
        val resized = baseline.parentSize != null && baseline.parentSize != parentSize
        val spatial = messageContentAnimationContract(change?.reason).animatePlacement
        if (!enabled || resized || listState.isScrollInProgress || (!spatial && change?.reason != HistoryChangeReason.CONTENT)) {
            baseline.version = change?.version
            if (delta != IntOffset.Zero || baseline.job?.isActive == true) {
                baseline.job?.cancel()
                delta = IntOffset.Zero
                baseline.job = scope.launch(start = CoroutineStart.UNDISPATCHED) { motion.snapTo(IntOffset.Zero) }
            }
        } else if (baseline.version != change?.version && spatial) {
            if (previous == null) baseline.version = change?.version
            else if (target != previous) {
                // An append can first lay out offscreen, then the same cause requests bottom.
                // Consume it only when this already displayed item actually changes position.
                baseline.version = change?.version
                val from = delta + previous - target
                val velocity = motion.velocity
                baseline.job?.cancel()
                delta = from
                baseline.job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    motion.snapTo(from)
                    motion.animateTo(IntOffset.Zero, MessagePlacementSpring, initialVelocity = velocity) { delta = value }
                }
            }
        } else if (change?.reason == HistoryChangeReason.CONTENT) {
            // A status/read update must not restart or cancel an existing placement spring.
            baseline.version = change.version
        }
        baseline.position = target
        baseline.parentSize = parentSize
    }.graphicsLayer {
        translationX = delta.x.toFloat()
        translationY = delta.y.toFloat()
        clip = false
    }
}

private class MessagePlacementBaseline {
    var offset: IntOffset? = null
    var cause: Pair<Long?, Boolean>? = null
}

/** §5.7：只移动真实cause改变后的绘制；滚动、提示高度及分页始终使用自然布局。 */
@Composable
internal fun Modifier.messageHistoryItem(change: HistoryListChange<*>?, selection: Any?, enter: Boolean, place: Boolean): Modifier {
    val scope = rememberCoroutineScope()
    val baseline = remember(selection, change?.scope) { MessagePlacementBaseline() }
    val offset = remember(selection, change?.scope) { Animatable(IntOffset.Zero, IntOffset.VectorConverter) }
    var delta by remember(selection, change?.scope) { mutableStateOf(IntOffset.Zero) }
    val alpha = remember(selection, change?.scope) { Animatable(if (enter) 0f else 1f) }
    LaunchedEffect(alpha, enter) {
        if (enter) alpha.animateTo(1f, tween(messageContentAnimationContract(HistoryChangeReason.LIVE_INSERT).durationMillis,
            easing = MotionTokens.EaseEnter)) else alpha.snapTo(1f)
    }
    // foundation-desktop 1.11.1 LazyListMeasuredItem.place无条件使用已有动画节点的lookaheadOffset。
    // 机制核对：org.jetbrains.compose.foundation/foundation-desktop/1.11.1源码包，Apache-2.0；不访问其internal。
    // https://repo.maven.apache.org/maven2/org/jetbrains/compose/foundation/foundation-desktop/1.11.1/foundation-desktop-1.11.1-sources.jar
    // 这里仅在现有item观察实际位置并改变graphicsLayer，不再挂会覆盖提示自然中间高度的lazy动画节点。
    return onPlaced { coordinates ->
        val point = coordinates.positionInParent()
        val target = IntOffset(point.x.roundToInt(), point.y.roundToInt())
        val previous = baseline.offset
        val cause = change?.version to place
        if (baseline.cause != cause) {
            baseline.cause = cause
            // A persisted read/status update cannot terminate the spatial move already in flight.
            if (change?.reason != HistoryChangeReason.CONTENT || target != previous) {
                val from = if (place && previous != null) delta + previous - target else IntOffset.Zero
                delta = from
                scope.launch {
                    val velocity = offset.velocity
                    offset.snapTo(from)
                    if (from != IntOffset.Zero) offset.animateTo(IntOffset.Zero, MessagePlacementSpring,
                        initialVelocity = velocity) { delta = value }
                }
            }
        }
        baseline.offset = target
    }.graphicsLayer { this.alpha = alpha.value; translationX = delta.x.toFloat(); translationY = delta.y.toFloat() }
}

private class MessageInsertBaseline<Id : Any> {
    var change: HistoryListChange<Id>? = null
    var enabled = false
    var inserted: Set<Id> = emptySet()
}

/** E2：REPLACE只让同一列表淡入；CONTENT不重启，PAGE_MERGE不把新页当实时入场。 */
@Composable
internal fun Modifier.messageReplacementFade(change: HistoryListChange<*>?, selection: Any?): Modifier {
    val replacement = remember(selection, change?.scope) { longArrayOf(-1L) }
    val version = if (change?.reason == HistoryChangeReason.REPLACE) change.version else replacement[0]
    SideEffect { replacement[0] = version }
    val alpha = remember(selection, change?.scope, version) { Animatable(0f) }
    LaunchedEffect(alpha) { alpha.animateTo(1f, tween(MotionTokens.DurFast, easing = MotionTokens.EaseEnter)) }
    return graphicsLayer { this.alpha = alpha.value }
}

/**
 * MT alpha must survive the LazyList animator reset performed by automatic bottom following.
 * 2026-10-17: a followed MT that becomes the newest row also rises its own height plus one item gap
 * with the same placement spring as the rows it pushes up, so it emerges from behind the floating composer.
 */
@Composable
internal fun Modifier.messageIncomingEntrance(id: String, enter: Boolean, rise: Boolean = false): Modifier {
    val alpha = remember(id) { Animatable(if (enter) 0f else 1f) }
    val offset = remember(id) { Animatable(IntOffset.Zero, IntOffset.VectorConverter) }
    val pending = remember(id) { booleanArrayOf(enter && rise) }
    val gap = with(LocalDensity.current) { messageSubpageSpacing().threadItemGapDp.dp.roundToPx() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id, enter) {
        if (enter) alpha.animateTo(1f, tween(MotionTokens.DurBase, easing = MotionTokens.EaseEnter))
        else alpha.snapTo(1f)
    }
    return layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        if (pending[0]) {
            pending[0] = false
            if (scope.coroutineContext[MotionDurationScale]?.scaleFactor != 0f) {
                val from = IntOffset(0, placeable.height + gap)
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    offset.snapTo(from)
                    offset.animateTo(IntOffset.Zero, MessagePlacementSpring)
                }
            }
        }
        layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                this.alpha = alpha.value
                translationY = offset.value.y.toFloat()
            }
        }
    }
}

/** 新MO只有初次呈现的稳定id有资格；状态报告复用该绘制层，不重新运行入场。 */
@Composable
internal fun Modifier.messageOutgoingEntrance(id: String, enter: Boolean): Modifier {
    val progress = remember(id) { Animatable(if (enter) 0f else 1f) }
    val flight = LocalMessageSendFlight.current
    LaunchedEffect(id, enter) {
        if (enter) progress.animateTo(1f, MotionTokens.SpringSpatialFast) else progress.snapTo(1f)
    }
    return graphicsLayer {
        val value = progress.value
        // A composer send is drawn by its flight until it lands; the persisted bubble then takes over.
        alpha = if (flight?.hides(id) == true) 0f else value
        scaleX = MotionTokens.ScaleEnterFrom + (1f - MotionTokens.ScaleEnterFrom) * value
        scaleY = scaleX
        translationY = MotionTokens.OffsetSmall.toPx() * (1f - value)
    }
}

/** E2/R6：沿真实持久化入场id跨越快速CONTENT；首见、重入及历史页只建基线。 */
@Composable
internal fun <Id : Any> rememberMessageInsertedIds(
    change: HistoryListChange<Id>?, enabled: Boolean, retainWhileItemsCompose: Boolean = false,
): Set<Id> {
    val baseline = remember { MessageInsertBaseline<Id>() }
    val previous = baseline.change
    val inserted = if (enabled && baseline.enabled && previous != null && change != null &&
        previous.scope === change.scope) when {
        change.version == previous.version -> if (retainWhileItemsCompose) baseline.inserted else emptySet()
        change.version > previous.version -> change.liveInsertedIds - previous.liveInsertedIds
        else -> emptySet()
    } else emptySet()
    SideEffect {
        baseline.change = change
        baseline.enabled = enabled
        baseline.inserted = inserted
    }
    return inserted
}
