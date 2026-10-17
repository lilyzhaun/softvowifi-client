package com.vowifi.sw.manager.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.preference.ArrowPreference

internal object NoVisualIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = object : Modifier.Node() {}
    override fun hashCode(): Int = 0
    override fun equals(other: Any?): Boolean = other === this
}

internal data class ManagerPreferencePresentation(
    val row: ManagerPreferenceRow,
    val component: ManagerPreferenceComponent,
    val actionable: Boolean,
    val feedback: ManagerActionFeedback,
    val usesPlatformDefaultGrayIndication: Boolean = false,
    val usesGrayFillClippedToRow: Boolean = false,
    val suppressesPlatformDefaultIndication: Boolean = actionable,
    val actionOwnerCount: Int = 1,
    val wrapperCardOwnsAction: Boolean = false,
    val toggleBehaviorIsExactlyOnce: Boolean = true,
)

internal fun managerPreferencePresentation(row: ManagerPreferenceRow): ManagerPreferencePresentation {
    val feedback = when (row) {
        is ManagerPreferenceRow.Navigate -> managerActionFeedback(ManagerAction.Navigate, row.actionable)
        is ManagerPreferenceRow.Toggle -> toggleActionFeedback(row.checked, row.actionable)
        is ManagerPreferenceRow.Information -> ManagerActionFeedback.None
    }
    return ManagerPreferencePresentation(
        row = row,
        component = row.renderedComponent,
        actionable = row.actionable,
        feedback = feedback,
    )
}

@Composable
internal fun ManagerPreferenceStartIcon(
    icon: ImageVector,
    availability: ManagerPreferenceAvailability,
    modifier: Modifier = Modifier,
) {
    if (availability == ManagerPreferenceAvailability.Busy) {
        CircularProgressIndicator(
            modifier = modifier
                .padding(end = ManagerPreferenceLayoutContract.startIconEndPadding)
                .size(24.dp),
        )
    } else {
        val colors = LocalSoftVoWiFiColors.current
        val tint = when (availability) {
            is ManagerPreferenceAvailability.Enabled -> colors.textPrimary
            is ManagerPreferenceAvailability.Error -> colors.error
            is ManagerPreferenceAvailability.Disabled,
            ManagerPreferenceAvailability.Busy,
            -> colors.textDisabled
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = modifier.padding(end = ManagerPreferenceLayoutContract.startIconEndPadding),
        )
    }
}

@Composable
internal fun ManagerNavigatePreferenceItem(
    row: ManagerPreferenceRow.Navigate,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val presentation = managerPreferencePresentation(row)
    val actionable = row.actionable && LocalAppRouteActive.current
    val colors = LocalSoftVoWiFiColors.current
    val itemModifier = modifier
        .defaultMinSize(minHeight = ManagerPreferenceLayoutContract.minTouchTarget)
        .semantics {
            if (row.showsProgress) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        }

    val startSlot: @Composable () -> Unit = {
        ManagerPreferenceStartIcon(row.startIcon, row.availability)
    }

    val summaryColor = if (row.isErrorState) {
        BasicComponentDefaults.summaryColor(
            color = colors.error,
            disabledColor = colors.error,
        )
    } else {
        BasicComponentDefaults.summaryColor()
    }

    if (row.actionable && onClick != null) {
        val feedback = if (actionable) presentation.feedback else ManagerActionFeedback.None
        val actionHandler = rememberManagerActionHandler(feedback, onClick)
        val indication = rememberManagerPressIndication(ManagerPressStyle.CardRow, actionable)
        CompositionLocalProvider(LocalIndication provides indication) {
            ArrowPreference(
                title = row.title,
                summary = row.visibleSummary,
                summaryColor = summaryColor,
                startAction = startSlot,
                onClick = actionHandler.takeIf { actionable },
                enabled = row.actionable,
                modifier = itemModifier.managerPressInputGate(actionable),
            )
        }
    } else {
        BasicComponent(
            title = row.title,
            summary = row.visibleSummary,
            summaryColor = summaryColor,
            enabled = row.isVisuallyEnabled,
            startAction = startSlot,
            modifier = itemModifier,
        )
    }
}

@Composable
internal fun ManagerTogglePreferenceItem(
    row: ManagerPreferenceRow.Toggle,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val routeActive = LocalAppRouteActive.current
    val actionable = row.actionable && routeActive
    val interaction = remember { MutableInteractionSource() }
    val colors = LocalSoftVoWiFiColors.current
    val itemModifier = modifier
        .defaultMinSize(minHeight = ManagerPreferenceLayoutContract.minTouchTarget)
        .semantics {
            if (row.showsProgress) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        }

    val startSlot: @Composable () -> Unit = {
        ManagerPreferenceStartIcon(row.startIcon, row.availability)
    }

    val summaryColor = if (row.isErrorState) {
        BasicComponentDefaults.summaryColor(
            color = colors.error,
            disabledColor = colors.error,
        )
    } else {
        BasicComponentDefaults.summaryColor()
    }

    // Keep the application handler outside the library-only haptic suppression scope.
    val toggleHandler = rememberManagerToggleHandler(actionable, onCheckedChange)
    val indication = rememberManagerPressIndication(ManagerPressStyle.CardRow, actionable)
    val switchColors = managerPressSwitchColors(row.actionable, routeActive)
    // miuix 0.9.3 Switch.kt uses CircleShape for its 49x28dp capsule; keep that public shape unchanged.
    val switchFocus = rememberManagerKeyboardFocusIndication(actionable, shape = CircleShape)

    CompositionLocalProvider(LocalIndication provides indication) {
        BasicComponent(
            title = row.title,
            summary = row.visibleSummary,
            summaryColor = summaryColor,
            enabled = row.actionable,
            startAction = startSlot,
            modifier = itemModifier.managerPressInputGate(actionable),
            interactionSource = interaction,
            role = Role.Switch,
            onClick = if (actionable) ({ toggleHandler(!row.checked) }) else null,
            endActions = {
                // Slot geometry reference: miuix 0.9.3 SwitchPreference, c36fab7, Apache-2.0.
                // https://github.com/compose-miuix-ui/miuix/blob/c36fab72391801d1e3ea5a00f966bf16bac28d4c/miuix-preference/src/commonMain/kotlin/top/yukonga/miuix/kmp/preference/SwitchPreference.kt
                Row(Modifier.padding(end = 8.dp).align(Alignment.CenterVertically).weight(1f, fill = false)) {}
                WithoutComponentHaptics {
                    CompositionLocalProvider(LocalIndication provides switchFocus) {
                        Switch(checked = row.checked, onCheckedChange = toggleHandler, enabled = actionable,
                            colors = switchColors, modifier = Modifier.managerPressInputGate(actionable))
                    }
                }
            },
        )
    }
}

@Composable
internal fun ManagerInformationPreferenceItem(
    row: ManagerPreferenceRow.Information,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSoftVoWiFiColors.current
    val startSlot: @Composable () -> Unit = {
        ManagerPreferenceStartIcon(row.startIcon, row.availability)
    }

    val summaryColor = if (row.isErrorState) {
        BasicComponentDefaults.summaryColor(
            color = colors.error,
            disabledColor = colors.error,
        )
    } else {
        BasicComponentDefaults.summaryColor()
    }

    val itemModifier = modifier
        .defaultMinSize(minHeight = ManagerPreferenceLayoutContract.minTouchTarget)
        .semantics {
            if (row.showsProgress) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        }

    BasicComponent(
        title = row.title,
        summary = row.visibleSummary,
        summaryColor = summaryColor,
        enabled = row.isVisuallyEnabled,
        startAction = startSlot,
        modifier = itemModifier,
    )
}
