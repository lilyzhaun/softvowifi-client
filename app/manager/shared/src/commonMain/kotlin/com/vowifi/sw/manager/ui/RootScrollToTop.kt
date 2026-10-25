package com.vowifi.sw.manager.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import com.vowifi.sw.manager.ui.navigation.AppRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.abs

/** Low-frequency commands only: AppShell never observes a list's offset or animation frames. */
internal class RootScrollToTopRegistry {
    private val targets = mutableMapOf<AppRoute.Root, RootScrollToTopTarget>()

    internal fun register(root: AppRoute.Root, target: RootScrollToTopTarget): () -> Unit {
        targets.put(root, target)?.cancel()
        return {
            if (targets[root] === target) targets.remove(root)
            target.cancel()
        }
    }

    fun request(root: AppRoute.Root) { targets[root]?.request() }
    fun cancel(root: AppRoute.Root) { targets[root]?.cancel() }
}

private data class RootScrollToTopContext(val registry: RootScrollToTopRegistry, val root: AppRoute.Root)
private val LocalRootScrollToTop = staticCompositionLocalOf<RootScrollToTopContext?> { null }

/** Scope is supplied only inside a pager root, never around SubpageHost. */
@Composable
internal fun RootScrollToTopProvider(registry: RootScrollToTopRegistry, root: AppRoute.Root, content: @Composable () -> Unit) {
    val context = remember(registry, root) { RootScrollToTopContext(registry, root) }
    CompositionLocalProvider(LocalRootScrollToTop provides context) {
        Box(Modifier.fillMaxSize().pointerInput(registry, root) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                // Stop at touch-down, without consuming input or waiting for drag recognition.
                registry.cancel(root)
            }
        }) { content() }
    }
}

@Composable
internal fun RegisterRootScrollToTop(
    state: LazyListState,
    needsExpansion: () -> Boolean = { false },
    onExpand: suspend () -> Unit = {},
) {
    val context = LocalRootScrollToTop.current ?: return
    val active = LocalAppRouteActive.current
    val latestExpand by rememberUpdatedState(onExpand)
    val latestNeedsExpansion by rememberUpdatedState(needsExpansion)
    val scope = rememberCoroutineScope()
    val target = remember(scope, state) {
        RootScrollToTopTarget(scope, state, needsExpansion = { latestNeedsExpansion() }) { latestExpand() }
    }
    DisposableEffect(context, target, active) {
        val unregister = if (active) context.registry.register(context.root, target) else null
        onDispose { unregister?.invoke() }
    }
}

internal class RootScrollToTopTarget(
    private val scope: CoroutineScope,
    private val state: LazyListState,
    private val needsExpansion: () -> Boolean = { false },
    private val onExpand: suspend () -> Unit,
) {
    private var job: Job? = null

    fun cancel() { job?.cancel(); job = null }

    fun request() {
        cancel()
        val needsScroll = state.firstVisibleItemIndex != 0 || state.firstVisibleItemScrollOffset != 0
        // Read title state only for this command, never as a root composition dependency.
        if (!needsScroll && !needsExpansion()) return
        job = scope.launch {
            if (!needsScroll) {
                onExpand()
                return@launch
            }
            // Both operations belong to the same cancellation boundary. An interrupted scroll
            // must not leave a detached title expansion running or force it in a finally block.
            coroutineScope {
                launch { onExpand() }
                state.animateScrollToItemFromNearby(0)
            }
        }
    }
}

/** R8 in either direction: teleport only beyond three viewports, then animate the last viewport. */
internal suspend fun LazyListState.animateScrollToItemFromNearby(index: Int) {
    val layout = layoutInfo
    val viewport = layout.viewportEndOffset - layout.viewportStartOffset
    val visible = layout.visibleItemsInfo
    val average = if (visible.isEmpty()) 0.0 else visible.sumOf { it.size }.toDouble() / visible.size
    val delta = index - firstVisibleItemIndex
    if (viewport > 0 && average > 0 && abs(delta * average - firstVisibleItemScrollOffset) > viewport * 3.0) {
        val screen = ceil(viewport / average).toInt()
        val nearby = if (delta > 0) (index - screen).coerceAtLeast(firstVisibleItemIndex)
            else (index + screen).coerceAtMost(firstVisibleItemIndex)
        scrollToItem(nearby)
    }
    animateScrollToItem(index)
}
