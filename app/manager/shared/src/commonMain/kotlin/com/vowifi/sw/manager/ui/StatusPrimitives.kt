package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.RegistrationEvent
import com.vowifi.sw.manager.model.RegistrationState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class StatePanelTone {
    INTERACTIVE,
    NEUTRAL,
    WARNING,
    ERROR,
}

data class StatePanelSpec(
    val icon: ImageVector,
    val tone: StatePanelTone,
    val title: String,
    val description: String,
    val loading: Boolean = false,
    val retryDescription: String? = null,
)

internal data class StatePanelLayoutContract(
    val usesCard: Boolean = true,
    val usesBasicComponent: Boolean = true,
    val usesOfficialProgressIndicator: Boolean = true,
    val centeredPanel: Boolean = false,
    val retryMinimumTouchDp: Int = 48,
    val fullWidth: Boolean = true,
)

internal val statePanelLayoutContract = StatePanelLayoutContract()

@Composable
fun StatePanel(
    spec: StatePanelSpec,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null, retryEnabled: Boolean = true,
) {
    val colors = LocalSoftVoWiFiColors.current
    val accent = when (spec.tone) {
        StatePanelTone.INTERACTIVE -> MiuixTheme.colorScheme.primary
        StatePanelTone.NEUTRAL -> colors.neutralStatus
        StatePanelTone.WARNING -> colors.warning
        StatePanelTone.ERROR -> colors.error
    }
    val surface = when (spec.tone) {
        StatePanelTone.INTERACTIVE, StatePanelTone.NEUTRAL -> colors.surfaceRaised
        StatePanelTone.WARNING -> colors.warningSurface
        StatePanelTone.ERROR -> colors.errorSurface
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                if (spec.loading) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
        insideMargin = PaddingValues(vertical = 4.dp),
        colors = CardDefaults.defaultColors(surface, colors.textPrimary),
    ) {
        BasicComponent(
            title = spec.title,
            summary = spec.description,
            startAction = {
                if (spec.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    Icon(
                        imageVector = spec.icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(24.dp),
                    )
                }
            },
            bottomAction = {
                if (onRetry != null && spec.retryDescription != null) {
                    val routeActive = LocalAppRouteActive.current
                    val businessEnabled = !spec.loading && retryEnabled
                    val actionable = businessEnabled && routeActive
                    val feedback = managerActionFeedback(ManagerAction.Refresh, enabled = actionable)
                    val handler = rememberManagerActionHandler(feedback, onRetry)
                    val interaction = remember { MutableInteractionSource() }
                    val indication = rememberManagerPressIndication(ManagerPressStyle.Solid, actionable)
                    Button(
                        onClick = handler,
                        enabled = actionable,
                        interactionSource = interaction,
                        indication = null,
                        colors = ButtonDefaults.buttonColors(contentColor = MiuixTheme.colorScheme.primary)
                            .preservingPressBusinessColors(businessEnabled, routeActive),
                        modifier = Modifier.heightIn(min = statePanelLayoutContract.retryMinimumTouchDp.dp)
                            .managerKeyboardFocusRing(interaction, actionable)
                            .managerPressInputGate(actionable).indication(interaction, indication),
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(spec.retryDescription, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            },
        )
    }
}

@Composable
fun SubscriptionStatusCard(event: RegistrationEvent, modifier: Modifier = Modifier) {
    val colors = LocalSoftVoWiFiColors.current
    val stateColor = stateColor(event.normalizedState, colors)
    val stacked = LocalDensity.current.fontScale >= 1.5f
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 4.dp,
        insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(colors.surfaceRaised, colors.textPrimary),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(stateColor))
            Column(
                modifier = Modifier.padding(16.dp).weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(systemSubscriptionLabel(event.subId), color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        StatusBadge(event.normalizedState)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(systemSubscriptionLabel(event.subId), color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        StatusBadge(event.normalizedState)
                    }
                }
                if (event.normalizedState == RegistrationState.UNREGISTERED) {
                    FactRow("原因码", event.reasonCode?.toString() ?: "未上报", mono = true)
                    FactRow("附加码", event.extraCode?.toString() ?: "未上报", mono = true)
                    FactRow("说明", event.extraMessage?.takeIf { it.isNotBlank() } ?: "未上报")
                } else {
                    FactRow("承载", transportLabel(event.transportType))
                    FactRow("属性标志", event.attributeFlags?.toString() ?: "未上报", mono = true)
                    FactRow("Feature tags", event.featureTags?.takeIf { it.isNotEmpty() }?.joinToString() ?: "未上报")
                }
                FactRow("最后事件", formatTimestamp(event.timestampMs), mono = true)
            }
        }
    }
}

@Composable
fun FactRow(label: String, value: String, mono: Boolean = false, modifier: Modifier = Modifier) {
    val colors = LocalSoftVoWiFiColors.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            color = colors.textSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            softWrap = true,
        )
        Text(
            text = value,
            color = colors.textPrimary,
            fontSize = 16.sp,
            lineHeight = 22.sp,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
            softWrap = true,
        )
    }
}

internal fun registrationStatusBadgeSpec(state: RegistrationState): SemanticStatusBadgeSpec =
    semanticStatusBadge(
        label = stateLabel(state),
        tone = when (state) {
            RegistrationState.REGISTERED -> SemanticTone.SUCCESS
            RegistrationState.REGISTERING -> SemanticTone.WARNING
            RegistrationState.UNREGISTERED -> SemanticTone.ERROR
            RegistrationState.UNKNOWN -> SemanticTone.NEUTRAL
        },
    )

@Composable
fun StatusBadge(state: RegistrationState, modifier: Modifier = Modifier) {
    SemanticStatusBadge(registrationStatusBadgeSpec(state), modifier)
}

private fun stateLabel(state: RegistrationState): String = when (state) {
    RegistrationState.REGISTERED -> "已注册"
    RegistrationState.REGISTERING -> "注册中"
    RegistrationState.UNREGISTERED -> "未注册"
    RegistrationState.UNKNOWN -> "未知"
}

private fun stateColor(state: RegistrationState, colors: SoftVoWiFiColors): Color = when (state) {
    RegistrationState.REGISTERED -> colors.success
    RegistrationState.REGISTERING -> colors.warning
    RegistrationState.UNREGISTERED -> colors.error
    RegistrationState.UNKNOWN -> colors.neutralStatus
}

private fun transportLabel(value: Int?): String = when (value) {
    1 -> "WWAN (1)"
    2 -> "WLAN (2)"
    null -> "未上报"
    else -> "未知 ($value)"
}

private fun formatTimestamp(value: Long): String = value.toString()

internal fun systemSubscriptionLabel(subId: Int): String = "系统订阅 $subId"
