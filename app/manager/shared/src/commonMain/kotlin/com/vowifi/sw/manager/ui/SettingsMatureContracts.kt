package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.ui.navigation.AppRoute

internal enum class SettingsDestinationKind {
    APPEARANCE,
    CONFIGURATION,
    DIAGNOSTICS,
    LOGS,
    CALL_NOTIFICATIONS,
    ABOUT,
    UNIT,
}

internal fun settingsRouteMap(): LinkedHashMap<AppRoute.Subpage, SettingsDestinationKind> = linkedMapOf(
    AppRoute.SettingsAppearance to SettingsDestinationKind.APPEARANCE,
    AppRoute.SettingsConfiguration to SettingsDestinationKind.CONFIGURATION,
    AppRoute.SettingsDiagnostics to SettingsDestinationKind.DIAGNOSTICS,
    AppRoute.SettingsLogs to SettingsDestinationKind.LOGS,
    AppRoute.SettingsCallNotifications to SettingsDestinationKind.CALL_NOTIFICATIONS,
    AppRoute.SettingsAbout to SettingsDestinationKind.ABOUT,
)

internal data class SettingsHubContract(
    val usesCatalog: Boolean,
    val groupedCards: Boolean,
    val embedsConfigurationControls: Boolean,
    val embedsAppearanceSwitches: Boolean,
)

internal fun settingsHubContract(): SettingsHubContract = SettingsHubContract(
    usesCatalog = true,
    groupedCards = true,
    embedsConfigurationControls = false,
    embedsAppearanceSwitches = false,
)

internal data class SettingsAppearanceContract(
    val switches: List<AppearancePreferenceKey>,
)

internal fun settingsAppearanceContract(): SettingsAppearanceContract = SettingsAppearanceContract(
    switches = listOf(
        AppearancePreferenceKey.FLOATING_NAV,
        AppearancePreferenceKey.BLUR_ENABLED,
        AppearancePreferenceKey.LIQUID_GLASS_ENABLED,
        AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED,
        AppearancePreferenceKey.HAPTICS_ENABLED,
    ),
)

internal fun SettingsRouteId.appRoute(): AppRoute.Subpage = when (this) {
    SettingsRouteId.APPEARANCE -> AppRoute.SettingsAppearance
    SettingsRouteId.CONFIGURATION -> AppRoute.SettingsConfiguration
    SettingsRouteId.DIAGNOSTICS -> AppRoute.SettingsDiagnostics
    SettingsRouteId.OPERATIONAL_LOGS -> AppRoute.SettingsLogs
    SettingsRouteId.CALL_NOTIFICATION -> AppRoute.SettingsCallNotifications
    SettingsRouteId.ABOUT -> AppRoute.SettingsAbout
}
