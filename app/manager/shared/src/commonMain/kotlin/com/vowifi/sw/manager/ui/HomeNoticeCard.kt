package com.vowifi.sw.manager.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.preference.ArrowPreference

internal data class HomeNoticeRenderingContract(
    val actionable: Boolean = true,
    val usesCard: Boolean = true,
    val usesArrowPreference: Boolean = true,
) {
    val usesStatePanel: Boolean = false
}

internal fun homeNoticeRenderingContract(navigation: Boolean = true): HomeNoticeRenderingContract =
    HomeNoticeRenderingContract(usesArrowPreference = navigation)

@Composable
internal fun HomeNoticeCard(
    notice: HomeNoticePresentation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    refreshEnabled: Boolean = true,
    refreshRunning: Boolean = false,
) {
    val colors = LocalSoftVoWiFiColors.current
    val actionable = LocalAppRouteActive.current
    val leading: @Composable () -> Unit = {
        Icon(imageVector = notice.tone.homeIcon(), contentDescription = null,
            tint = notice.tone.homeColor(colors), modifier = Modifier
                .padding(end = ManagerPreferenceLayoutContract.startIconEndPadding).size(24.dp))
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(
            color = notice.tone.homeSurface(colors),
            contentColor = colors.textPrimary,
        ),
    ) {
        when (val action = notice.action) {
            is HomeNoticeAction.Navigate -> {
                val feedback = managerActionFeedback(ManagerAction.Navigate, enabled = actionable)
                val actionHandler = rememberManagerActionHandler(feedback, onClick)
                val indication = rememberManagerPressIndication(ManagerPressStyle.CardRow, actionable)
                CompositionLocalProvider(LocalIndication provides indication) {
                    ArrowPreference(title = notice.title, summary = notice.summary, startAction = leading,
                        onClick = actionHandler.takeIf { actionable }, enabled = true,
                        modifier = Modifier.fillMaxWidth().managerPressInputGate(actionable))
                }
            }
            is HomeNoticeAction.Refresh -> BasicComponent(title = notice.title, summary = notice.summary,
                startAction = leading, modifier = Modifier.fillMaxWidth(), bottomAction = {
                    ManagerTextAction(action.label, ManagerAction.Refresh, onClick,
                        enabled = actionable && refreshEnabled, busy = refreshRunning, busyLabel = "正在刷新")
                },
            )
        }
    }
}
