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
) {
    val ready = state as? CallUiState.Ready
    val back = LocalPhoneBack.current
    PhoneDialerScreen(draft, onDraftChange, lineLabel,
        ready?.sync == CallSyncState.Ready && ready.phase == CallPhase.Idle && ready.origin == CallOrigin.ENGINE,
        ready?.busy == true, ready?.operationMessage ?: callStatePanelSpec(state)?.description,
        { onDial(draft) }, back, back, onPermissions)
}
