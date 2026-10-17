package com.vowifi.sw.manager.ui

import androidx.compose.runtime.*
import com.vowifi.sw.manager.model.CallHistoryDeleteResult
import com.vowifi.sw.manager.model.CallHistoryRecordId
import com.vowifi.sw.manager.model.CallHistoryState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class CallsHistoryDeleteUi(
    val message: String?,
    val ids: Set<CallHistoryRecordId>,
    val clearMessage: () -> Unit,
    val submit: (CallHistoryState.Ready, Set<CallHistoryRecordId>) -> Unit,
)

/** One page-local waiter and persistent explanation, not an optimistic deletion producer. */
@Composable
internal fun rememberCallsHistoryDeleteUi(
    selection: Any?, active: Boolean,
    await: (suspend (CallHistoryState.Ready, Set<CallHistoryRecordId>) -> CallHistoryDeleteResult)?,
    legacy: (CallHistoryState.Ready, Set<CallHistoryRecordId>) -> Unit,
): CallsHistoryDeleteUi {
    var notice by remember(selection, active) { mutableStateOf<Pair<Set<CallHistoryRecordId>, String>?>(null) }
    val scope = rememberCoroutineScope()
    val waiter = remember { arrayOfNulls<Job>(1) }
    val currentSelection by rememberUpdatedState(selection)
    val currentActive by rememberUpdatedState(active)
    val currentAwait by rememberUpdatedState(await)
    val currentLegacy by rememberUpdatedState(legacy)
    DisposableEffect(selection, active) {
        onDispose { waiter[0]?.cancel(); waiter[0] = null }
    }
    return CallsHistoryDeleteUi(notice?.second, notice?.first.orEmpty(), { notice = null }) { expected, ids ->
        if (currentActive && waiter[0]?.isActive == true) {
            notice = ids to "正在删除通话记录，请稍候。"
        } else if (currentActive) {
            notice = null
            val accepted = currentAwait
            if (accepted == null) currentLegacy(expected, ids) else {
                waiter[0] = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val result = accepted(expected, ids)
                    if (currentActive && currentSelection == selection) {
                        val message = when (result) {
                            CallHistoryDeleteResult.COMMITTED -> null
                            CallHistoryDeleteResult.FAILED -> "删除失败，请重试。"
                            CallHistoryDeleteResult.UNCONFIRMED -> "删除结果未确认，请刷新核对。"
                            CallHistoryDeleteResult.REJECTED -> "记录或通话状态已变化，请重试。"
                            CallHistoryDeleteResult.UNAVAILABLE -> "当前无法删除通话记录，请稍后重试。"
                        }
                        notice = message?.let { ids to it }
                    }
                }
            }
        }
    }
}
