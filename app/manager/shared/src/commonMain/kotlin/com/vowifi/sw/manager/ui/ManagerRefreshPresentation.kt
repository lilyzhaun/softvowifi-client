package com.vowifi.sw.manager.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.MotionDurationScale
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import com.vowifi.sw.manager.data.RefreshResult
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Minimum display only; the controller's running/result still end with the real read. */
@Composable
internal fun rememberManagerRefreshIndicator(controller: ManagerRefreshController): State<Boolean> {
    val settledSequence = remember(controller) { mutableLongStateOf(0L) }
    // The first composition of a new accepted request must already pass true to the
    // library. This also covers a synchronous Completed with no observable busy frame.
    val visible = remember(controller) {
        derivedStateOf {
            controller.sequence > settledSequence.longValue &&
                !controller.result.refreshWasRejected() && controller.result != RefreshResult.Cancelled
        }
    }
    LaunchedEffect(controller, controller.sequence) {
        val sequence = controller.sequence
        if (sequence == 0L || controller.result.refreshWasRejected() || controller.result == RefreshResult.Cancelled) {
            settledSequence.longValue = sequence
            return@LaunchedEffect
        }
        coroutineScope {
            val minimum = async { delay(MotionTokens.RefreshMinimumVisibleMillis.toLong()) }
            snapshotFlow { controller.running }.first { !it }
            if (controller.result.refreshWasRejected() || controller.result == RefreshResult.Cancelled) minimum.cancel()
            else minimum.await()
            // Settle only this request: an older tail can never close a newer sequence.
            settledSequence.longValue = sequence
        }
    }
    return visible
}

/** Consumers pass this stable State to the Icon's draw layer, never read it at the root. */
@Composable
internal fun rememberManagerRefreshRotation(controller: ManagerRefreshController): State<Float> {
    val angle = remember(controller) { Animatable(0f) }
    LaunchedEffect(controller, controller.sequence) {
        if (controller.sequence == 0L || (controller.result.refreshWasRejected() && angle.value == 0f)) return@LaunchedEffect
        do {
            // Zero-scale tween completes synchronously: don't spin a synchronous infinite loop.
            if ((currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f) <= 0f) break
            val target = (floor(angle.value / 360f) + 1f) * 360f
            val remainingMillis = (MotionTokens.RefreshRotationMillis * (target - angle.value) / 360f)
                .roundToInt().coerceAtLeast(1)
            angle.animateTo(target, tween(remainingMillis, easing = MotionTokens.EaseLinear))
        } while (controller.running)
        angle.snapTo(0f)
    }
    return angle.asState()
}

@Composable
internal fun RegisterManagerRefreshAction(controller: ManagerRefreshController, label: String, rotation: State<Float>) {
    val slot = LocalSubpageTopBarAction.current
    val enabled = controller.enabled
    val running = controller.running
    val click = remember(controller) { { controller.request(); Unit } }
    DisposableEffect(slot, controller, label, enabled, running, rotation) {
        val action = SubpageTopBarAction(MiuixIcons.Refresh, label, ManagerAction.Refresh, enabled,
            iconRotation = rotation, busy = running, onClick = click)
        slot?.value = action
        onDispose { slot?.let { if (it.value === action) it.value = null } }
    }
}

internal fun LazyListScope.managerRefreshResultItem(controller: ManagerRefreshController, key: String) {
    // Keep the status key in the same list position; a failed read must not insert a new
    // leading item and anchor the viewport below its own retry message.
    item(key = key) {
        val failure = controller.presentedFailure
        val message = managerRefreshFailure(failure)
        RefreshFailurePresentation(
            spec = message?.let { StatePanelSpec(MiuixIcons.Refresh,
                if (failure.refreshWasRejected()) StatePanelTone.WARNING else StatePanelTone.ERROR,
                "刷新未完成", "$it\n旧快照不代表本次读取成功。", retryDescription = "重试刷新") },
            modifier = Modifier.padding(bottom = settingsHubLayoutContract().cardGap),
            retryEnabled = controller.enabled,
            onRetry = { controller.request() },
        )
    }
}

internal fun managerRefreshTexts(result: RefreshResult?): List<String> = listOf(
    "下拉刷新", "松开刷新", "正在刷新", when (result) {
        RefreshResult.Completed -> "刷新完成"
        is RefreshResult.Failed -> "刷新未完成"
        RefreshResult.Busy -> "当前忙碌"
        RefreshResult.Unsupported -> "刷新不可用"
        RefreshResult.Cancelled -> "刷新已取消"
        null -> ""
    },
)
