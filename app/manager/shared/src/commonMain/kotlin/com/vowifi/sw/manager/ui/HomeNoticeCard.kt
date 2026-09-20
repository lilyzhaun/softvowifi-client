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

internal fun homeNoticeRenderingContract(): HomeNoticeRenderingContract = HomeNoticeRenderingContract()

@Composable
internal fun HomeNoticeCard(
    notice: HomeNoticePresentation,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSoftVoWiFiColors.current
    val feedback = managerActionFeedback(ManagerAction.Navigate, enabled = true)
    val actionHandler = rememberManagerActionHandler(feedback, onClick)

    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(
            color = notice.tone.homeSurface(colors),
            contentColor = colors.textPrimary,
        ),
    ) {
        CompositionLocalProvider(LocalIndication provides NoVisualIndication) {
            ArrowPreference(
                title = notice.title,
                summary = notice.summary,
                startAction = {
                    Icon(
                        imageVector = notice.tone.homeIcon(),
                        contentDescription = null,
                        tint = notice.tone.homeColor(colors),
                        modifier = Modifier
                            .padding(end = ManagerPreferenceLayoutContract.startIconEndPadding)
                            .size(24.dp),
                    )
                },
                onClick = actionHandler,
                modifier = Modifier
                    .fillMaxWidth()
                    .managerPressScale(feedback),
            )
        }
    }
}
