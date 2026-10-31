package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.EngineLifecycle
import top.yukonga.miuix.kmp.basic.Switch

/** The official miuix switch owns input and motion; checked is never an optimistic engine state. */
@Composable
internal fun HomeEngineSwitch(action: HomeActionPresentation, callProtected: Boolean, confirming: Boolean, onAction: () -> Unit) {
    val routeActive = LocalAppRouteActive.current
    val actionable = action.enabled && !action.busy && !confirming && routeActive
    val actionableNow by rememberUpdatedState(actionable)
    val currentAction by rememberUpdatedState(onAction)
    val checked = action.lifecycle == EngineLifecycle.RUNNING || action.lifecycle == EngineLifecycle.STOPPING
    val checkedNow by rememberUpdatedState(checked)
    val label = if (action.busy) "正在处理" else action.label
    val feedback = rememberManagerActionHandler(
        if (callProtected) managerActionFeedback(ManagerAction.Navigate) else toggleActionFeedback(!checked),
    ) { currentAction() }
    val focus = rememberManagerKeyboardFocusIndication(actionable, CircleShape)
    WithoutComponentHaptics {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.LocalIndication provides focus) {
            Switch(checked, onCheckedChange = { requested ->
                if (actionableNow && requested != checkedNow) feedback()
            }, enabled = actionable, colors = managerPressSwitchColors(action.enabled && !action.busy, routeActive),
                modifier = Modifier.defaultMinSize(minWidth = 64.dp, minHeight = 48.dp)
                    .semantics {
                        contentDescription = label
                        if (action.busy) {
                            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                            stateDescription = "正在处理"
                        }
                    }.managerPressInputGate(actionable))
        }
    }
}
