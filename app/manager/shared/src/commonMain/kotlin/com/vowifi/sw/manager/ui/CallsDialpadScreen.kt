package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.*

@Composable internal fun CallsDialpadScreen(
    state: CallUiState, draft: String, onDraftChange: (String) -> Unit,
    onDial: (String) -> Unit,
    lineLabel: String = "当前绑定线路",
    onPermissions: (() -> Unit)? = null,
    outgoingEnabled: Boolean? = null,
    outgoingNotice: String? = null,
    lineContent: (@Composable () -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
) {
    val ready = state as? CallUiState.Ready
    val back = LocalPhoneBack.current
    val message = listOfNotNull(outgoingNotice, ready?.operationMessage,
        callStatePanelSpec(state)?.description.takeIf { ready?.operationMessage == null && (outgoingNotice == null || ready == null) })
        .distinct().joinToString("\n").ifEmpty { null }
    val synchronizedIdle = ready?.sync == CallSyncState.Ready && ready.phase == CallPhase.Idle
    val refresh = onRefresh?.takeIf { canRefreshCallState(state) }
    PhoneDialerScreen(draft, onDraftChange, lineLabel,
        synchronizedIdle && (outgoingEnabled ?: (ready.origin == CallOrigin.ENGINE)),
        ready?.busy == true, message,
        { onDial(draft) }, back, back, onPermissions, lineContent, refresh)
}
