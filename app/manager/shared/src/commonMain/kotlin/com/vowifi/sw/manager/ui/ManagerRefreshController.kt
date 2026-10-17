package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.vowifi.sw.manager.data.RefreshResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One page-owned waiter. The source, not this controller, owns the actual producer. */
internal class ManagerRefreshController(
    private val scope: CoroutineScope,
    private val allowed: () -> Boolean,
    private val refresh: suspend () -> RefreshResult,
) {
    var running by mutableStateOf(false)
        private set
    var sequence by mutableLongStateOf(0L)
        private set
    var result: RefreshResult? by mutableStateOf(null)
        private set
    var failureScope: () -> Any? = { Unit }
    private var lastFailure by mutableStateOf<Pair<Any?, RefreshResult>?>(null)
    val presentedFailure: RefreshResult? get() = lastFailure?.takeIf { it.first == failureScope() }?.second
    private var waiter: Job? = null
    val available: Boolean get() = allowed()
    val enabled: Boolean get() = available && !running

    fun request(): Boolean {
        if (!enabled) return false
        running = true
        result = null
        sequence++
        val owner = failureScope()
        waiter = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val terminal = refresh()
                result = terminal
                if (terminal == RefreshResult.Completed) {
                    if (lastFailure?.first == owner) lastFailure = null
                } else if (managerRefreshFailure(terminal) != null) lastFailure = owner to terminal
            } catch (error: CancellationException) {
                result = RefreshResult.Cancelled
                throw error
            } finally {
                running = false
            }
        }
        return true
    }

    fun cancelWaiter() { waiter?.cancel() }
    fun clearPresentedFailure() { lastFailure = null }
}

@Composable
internal fun rememberManagerRefreshController(
    onRefresh: (suspend () -> RefreshResult)?,
    enabled: Boolean = true,
    presentationScope: Any? = Unit,
): ManagerRefreshController {
    val scope = rememberCoroutineScope()
    val routeActive = LocalAppRouteActive.current
    val latest = rememberUpdatedState(onRefresh)
    val latestScope = rememberUpdatedState(presentationScope)
    val allowed = rememberUpdatedState(enabled && routeActive && onRefresh != null)
    val controller = remember(scope) {
        ManagerRefreshController(scope, { allowed.value }, { requireNotNull(latest.value).invoke() })
            .also { it.failureScope = { latestScope.value } }
    }
    DisposableEffect(controller) { onDispose { controller.cancelWaiter() } }
    DisposableEffect(controller, presentationScope) { onDispose { controller.clearPresentedFailure() } }
    LaunchedEffect(controller, routeActive) { if (!routeActive) controller.cancelWaiter() }
    return controller
}

internal fun RefreshResult?.refreshWasRejected(): Boolean =
    this == RefreshResult.Busy || this == RefreshResult.Unsupported

internal fun managerRefreshFailure(result: RefreshResult?): String? = when (result) {
    is RefreshResult.Failed -> result.message
    RefreshResult.Busy -> "当前操作尚未结束，请稍后重试。"
    RefreshResult.Unsupported -> "当前刷新不可用。"
    // Leaving a page or replacing a read is not a failed request to retry on reentry.
    RefreshResult.Cancelled, RefreshResult.Completed, null -> null
}
