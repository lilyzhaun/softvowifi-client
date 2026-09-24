package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.ui.navigation.AppRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SettingsMatureArchitectureTest {
    @Test
    fun appearanceIsFirstAndEveryCatalogRowHasNavigateOrHonestDisabledTarget() {
        val catalog = settingsCatalog(settingsSnapshot())
        val rows = catalog.groups.flatMap { it.rows }

        assertEquals(SettingsGroupId.APPEARANCE, catalog.groups.first().id)
        assertEquals(SettingsRowId.Root.APPEARANCE, rows.first().id)
        assertEquals(SettingsRouteId.APPEARANCE, (rows.first().target as SettingsRowTarget.Navigate).route)
        assertEquals(SettingsRouteId.entries, rows.mapNotNull { (it.target as? SettingsRowTarget.Navigate)?.route })
        assertTrue(rows.all { it.enabled || it.target == null })
        assertTrue(rows.filterNot { it.enabled }.all { it.summary.isNotBlank() })
    }

    @Test
    fun routeMapHasRealDestinationForEverySettingsSubpage() {
        val map = settingsRouteMap()

        assertEquals(
            listOf(
                AppRoute.SettingsAppearance,
                AppRoute.SettingsConfiguration,
                AppRoute.SettingsDiagnostics,
                AppRoute.SettingsLogs,
                AppRoute.SettingsCallNotifications,
                AppRoute.SettingsAbout,
            ),
            map.keys.toList(),
        )
        assertTrue(map.values.all { it != SettingsDestinationKind.UNIT })
        assertNotNull(map[AppRoute.SettingsAppearance])
    }

    @Test
    fun settingsRootIsHubOnlyAndAppearanceOwnsTwoSwitches() {
        val hub = settingsHubContract()
        val appearance = settingsAppearanceContract()

        assertTrue(hub.usesCatalog)
        assertTrue(hub.groupedCards)
        assertFalse(hub.embedsConfigurationControls)
        assertFalse(hub.embedsAppearanceSwitches)
        assertEquals(
            listOf(
                AppearancePreferenceKey.FLOATING_NAV,
                AppearancePreferenceKey.BLUR_ENABLED,
                AppearancePreferenceKey.LIQUID_GLASS_ENABLED,
                AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED,
                AppearancePreferenceKey.HAPTICS_ENABLED,
            ),
            appearance.switches,
        )
    }
}
