package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.OperationalLogEntry
import com.vowifi.sw.manager.model.OperationalLogUiState
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle

enum class SettingsGroupId { APPEARANCE, SIM_AND_NETWORK, TROUBLESHOOTING, INCOMING_CALL, ABOUT }

enum class SettingsRouteId(val wireValue: String) {
    APPEARANCE("settings/appearance"),
    CONFIGURATION("settings/configuration"),
    DIAGNOSTICS("settings/diagnostics"),
    OPERATIONAL_LOGS("settings/operational-logs"),
    CALL_NOTIFICATION("settings/call-notification"),
    ABOUT("settings/about");

    companion object {
        fun fromWire(value: String): SettingsRouteId? = entries.firstOrNull { it.wireValue == value }
    }
}

sealed interface SettingsRowId {
    enum class Root : SettingsRowId {
        APPEARANCE,
        CONFIGURATION,
        DIAGNOSTICS,
        OPERATIONAL_LOGS,
        CALL_NOTIFICATION,
        ABOUT,
    }

    sealed interface Configuration : SettingsRowId {
        data class Configured(val instanceId: ConfigurationInstanceId) : Configuration
        data class ActiveSim(val handle: SimHandle) : Configuration
        data class UnavailableSim(val handle: SimHandle) : Configuration
    }

    data object OperationalLogExport : SettingsRowId
    data object NotificationPermission : SettingsRowId
    data object FullScreenIntent : SettingsRowId
    data object IncomingCallChannel : SettingsRowId
    data object ObserverChannel : SettingsRowId
}

enum class SettingsActionId {
    APPLY_CONFIGURATION,
    PROVISION_CONFIGURATION,
    EXPORT_OPERATIONAL_LOGS,
    MANAGE_NOTIFICATION_PERMISSION,
    MANAGE_FULL_SCREEN_INTENT,
    MANAGE_INCOMING_CALL_CHANNEL,
    MANAGE_OBSERVER_CHANNEL,
}

sealed interface SettingsAction {
    val id: SettingsActionId

    data class ApplyConfiguration(val instanceId: ConfigurationInstanceId) : SettingsAction {
        override val id = SettingsActionId.APPLY_CONFIGURATION
    }

    data class ProvisionConfiguration(val handle: SimHandle) : SettingsAction {
        override val id = SettingsActionId.PROVISION_CONFIGURATION
    }

    data class ExportOperationalLogs(val entries: List<OperationalLogEntry>) : SettingsAction {
        override val id = SettingsActionId.EXPORT_OPERATIONAL_LOGS

        init {
            require(entries.isNotEmpty())
        }
    }

    data object ManageNotificationPermission : SettingsAction {
        override val id = SettingsActionId.MANAGE_NOTIFICATION_PERMISSION
    }

    data object ManageFullScreenIntent : SettingsAction {
        override val id = SettingsActionId.MANAGE_FULL_SCREEN_INTENT
    }

    data object ManageIncomingCallChannel : SettingsAction {
        override val id = SettingsActionId.MANAGE_INCOMING_CALL_CHANNEL
    }
    data object ManageObserverChannel : SettingsAction {
        override val id = SettingsActionId.MANAGE_OBSERVER_CHANNEL
    }
}

enum class NotificationPermissionStatus { GRANTED, DENIED, UNAVAILABLE }
enum class FullScreenStatus { ALLOWED, DENIED, UNAVAILABLE, NOT_REQUIRED }
enum class IncomingCallChannelStatus { UNAVAILABLE, MISSING, BLOCKED, REDUCED, HIGH }
enum class NotificationSnapshotFreshness { CURRENT, STALE, MALFORMED }
enum class ObserverChannelStatus { UNAVAILABLE, MISSING, BLOCKED, ENABLED }

data class CallNotificationSettingsSnapshot(
    val permission: NotificationPermissionStatus,
    val fullScreen: FullScreenStatus,
    val freshness: NotificationSnapshotFreshness = NotificationSnapshotFreshness.CURRENT,
    val channel: IncomingCallChannelStatus = IncomingCallChannelStatus.UNAVAILABLE,
    val observerChannel: ObserverChannelStatus = ObserverChannelStatus.UNAVAILABLE,
)

fun interface CallNotificationSettingsSnapshotAdapter {
    fun currentSnapshot(): CallNotificationSettingsSnapshot
}

sealed interface SettingsRowTarget {
    data class Navigate(val route: SettingsRouteId) : SettingsRowTarget
    data class Action(val action: SettingsAction) : SettingsRowTarget
}

data class SettingsRowPresentation(
    val id: SettingsRowId,
    val title: String,
    val summary: String,
    val enabled: Boolean,
    val target: SettingsRowTarget?,
) {
    init {
        require(enabled == (target != null))
    }
}

data class SettingsGroupPresentation(
    val id: SettingsGroupId,
    val title: String,
    val rows: List<SettingsRowPresentation>,
)

sealed interface SettingsRefreshPolicy {
    data object None : SettingsRefreshPolicy

    data class OnEnter(val targets: Set<RefreshTarget>) : SettingsRefreshPolicy {
        init {
            require(targets.isNotEmpty())
        }
    }
}

data class SettingsPagePresentation(
    val route: SettingsRouteId,
    val title: String,
    val refreshPolicy: SettingsRefreshPolicy,
    val rows: List<SettingsRowPresentation> = emptyList(),
) {
    val refreshTargets: Set<RefreshTarget>
        get() = (refreshPolicy as? SettingsRefreshPolicy.OnEnter)?.targets.orEmpty()
}

data class SettingsAboutPresentation(
    val route: SettingsRouteId,
    val title: String,
    val versionLabel: String,
    val refreshPolicy: SettingsRefreshPolicy = SettingsRefreshPolicy.None,
) {
    val refreshTargets: Set<RefreshTarget>
        get() = (refreshPolicy as? SettingsRefreshPolicy.OnEnter)?.targets.orEmpty()
}

data class SettingsCatalog(
    val groups: List<SettingsGroupPresentation>,
    val appearance: SettingsPagePresentation,
    val configuration: SettingsPagePresentation,
    val diagnostics: SettingsPagePresentation,
    val operationalLogs: SettingsPagePresentation,
    val callNotification: SettingsPagePresentation,
    val about: SettingsAboutPresentation,
)

data class SettingsCatalogSnapshot(
    val configuration: ConfigurationUiState,
    val sims: SimControlUiState,
    val diagnostics: EnvironmentDiagnosticsUiState,
    val operationalLogs: OperationalLogUiState,
    val callNotification: CallNotificationSettingsSnapshot,
    val appVersion: String,
    val exportBusy: Boolean,
)
