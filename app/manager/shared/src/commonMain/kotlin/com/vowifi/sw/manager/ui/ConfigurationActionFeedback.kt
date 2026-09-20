package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationUiState

internal fun configurationSaveAction(): ManagerAction = ManagerAction.SaveConfiguration

internal fun configurationSaveIsBusy(
    state: ConfigurationUiState,
    id: ConfigurationInstanceId,
): Boolean = state is ConfigurationUiState.Ready && state.applyingInstanceId == id

internal fun configurationSaveFeedback(
    state: ConfigurationUiState,
    id: ConfigurationInstanceId,
    enabled: Boolean = true,
): ManagerActionFeedback {
    val canApply = canApplyConfiguration(state, id)
    val isBusy = configurationSaveIsBusy(state, id)
    return managerActionFeedback(
        action = configurationSaveAction(),
        enabled = enabled && canApply && !isBusy,
    )
}

internal fun configurationProvisionAction(): ManagerAction = ManagerAction.ProvisionConfiguration

internal fun configurationProvisionFeedback(
    state: ConfigurationUiState,
    row: ConfigurationRowPresentation,
): ManagerActionFeedback = managerActionFeedback(
    action = configurationProvisionAction(),
    enabled = canProvisionConfiguration(state, row),
)
