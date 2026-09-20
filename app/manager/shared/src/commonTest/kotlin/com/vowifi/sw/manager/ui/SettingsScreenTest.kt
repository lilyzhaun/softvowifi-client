package com.vowifi.sw.manager.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsScreenTest {
    @Test
    fun rootAndAppearanceUseKsuCardRhythmWithoutGroupHeaders() {
        val cards = settingsCardGroups(settingsCatalog(settingsSnapshot()))
        val hubLayout = settingsHubLayoutContract()
        val appearance = appearanceCardPresentation(AppearanceSettings.Default, blurSupported = false)

        assertEquals(
            listOf(
                listOf(SettingsRowId.Root.APPEARANCE, SettingsRowId.Root.CONFIGURATION),
                listOf(SettingsRowId.Root.DIAGNOSTICS, SettingsRowId.Root.OPERATIONAL_LOGS),
                listOf(SettingsRowId.Root.CALL_NOTIFICATION),
                listOf(SettingsRowId.Root.ABOUT),
            ),
            cards.map { card -> card.rows.map { it.id } },
        )
        assertEquals(4, cards.size)
        assertEquals(0, hubLayout.groupHeaderCount)
        assertEquals(12.dp, hubLayout.horizontalInset)
        assertEquals(12.dp, hubLayout.topInset)
        assertEquals(12.dp, hubLayout.cardGap)
        assertEquals(2, appearance.sections.size)
        assertEquals(2, appearance.groupHeaderCount)
        assertEquals(12.dp, appearance.horizontalInset)
        assertEquals(12.dp, appearance.topInset)
    }

    @Test
    fun enabledNavigationUsesArrowAndDisabledRowUsesBasicPresentation() {
        val enabled = SettingsRowPresentation(
            SettingsRowId.Root.APPEARANCE,
            "外观设置",
            "悬浮底栏与模糊效果",
            true,
            SettingsRowTarget.Navigate(SettingsRouteId.APPEARANCE),
        )
        val disabled = SettingsRowPresentation(
            SettingsRowId.Root.CALL_NOTIFICATION,
            "来电通知能力",
            "当前桌面环境不提供通知权限状态",
            false,
            null,
        )

        val enabledPref = settingsRowToNavigatePreference(enabled)
        val disabledPref = settingsRowToNavigatePreference(disabled)

        assertEquals(ManagerPreferenceComponent.ArrowPreference, enabledPref.renderedComponent)
        assertTrue(enabledPref.actionable)
        assertTrue(enabledPref.isVisuallyEnabled)
        assertEquals("悬浮底栏与模糊效果", enabledPref.visibleSummary)

        assertEquals(ManagerPreferenceComponent.BasicComponent, disabledPref.renderedComponent)
        assertFalse(disabledPref.actionable)
        assertFalse(disabledPref.isVisuallyEnabled)
        assertEquals("当前桌面环境不提供通知权限状态", disabledPref.visibleSummary)
        assertEquals("当前桌面环境不提供通知权限状态", (disabledPref.availability as ManagerPreferenceAvailability.Disabled).reason)
        assertFalse(disabled.enabled)
        assertEquals(null, disabled.target)
    }

    @Test
    fun appearanceSectionsContainNavigationAndInteractionGroups() {
        val defaultSections = appearanceSectionSpecs(AppearanceSettings.Default, blurSupported = true)
        val nonFloatingSections = appearanceSectionSpecs(
            AppearanceSettings.Default.copy(floatingNavigation = false),
            blurSupported = true,
        )
        val unsupportedBlurSections = appearanceSectionSpecs(
            AppearanceSettings.Default.copy(liquidGlassRequested = true),
            blurSupported = false,
        )

        assertEquals(2, defaultSections.size)
        assertEquals("导航效果", defaultSections[0].title)
        assertEquals("交互", defaultSections[1].title)

        val navKeys = defaultSections[0].specs.map { it.key }
        assertEquals(
            listOf(
                AppearancePreferenceKey.FLOATING_NAV,
                AppearancePreferenceKey.BLUR_ENABLED,
                AppearancePreferenceKey.LIQUID_GLASS_ENABLED,
            ),
            navKeys,
        )

        val nonFloatingNavKeys = nonFloatingSections[0].specs.map { it.key }
        assertEquals(
            listOf(
                AppearancePreferenceKey.FLOATING_NAV,
                AppearancePreferenceKey.BLUR_ENABLED,
            ),
            nonFloatingNavKeys,
        )

        val unsupportedLiquidSpec = unsupportedBlurSections[0].specs.first { it.key == AppearancePreferenceKey.LIQUID_GLASS_ENABLED }
        assertEquals("已改用原生静态导航，不再提供液态折射", unsupportedLiquidSpec.summary)
        assertFalse(unsupportedLiquidSpec.checked)
        val toggleRow = appearanceSpecToTogglePreference(unsupportedLiquidSpec)
        assertFalse(toggleRow.actionable)
        assertFalse(toggleRow.isVisuallyEnabled)

        val interactionKeys = defaultSections[1].specs.map { it.key }
        assertEquals(
            listOf(
                AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED,
                AppearancePreferenceKey.HAPTICS_ENABLED,
            ),
            interactionKeys,
        )
    }

    @Test
    fun appearanceContainsFourControlsAndALegacyExplanation() {
        val defaultSections = appearanceSectionSpecs(AppearanceSettings.Default, blurSupported = true)
        val allSpecs = defaultSections.flatMap { it.specs }

        assertEquals(5, allSpecs.size)
        assertEquals(
            listOf(
                AppearancePreferenceKey.FLOATING_NAV,
                AppearancePreferenceKey.BLUR_ENABLED,
                AppearancePreferenceKey.LIQUID_GLASS_ENABLED,
                AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED,
                AppearancePreferenceKey.HAPTICS_ENABLED,
            ),
            allSpecs.map { it.key },
        )

        val toggleRows = allSpecs.filter { it.key != AppearancePreferenceKey.LIQUID_GLASS_ENABLED }
            .map { appearanceSpecToTogglePreference(it) }
        assertEquals(
            List(4) { ManagerPreferenceComponent.SwitchPreference },
            toggleRows.map { it.renderedComponent },
        )
        assertTrue(toggleRows.all { it.actionable })
        assertEquals(
            setOf(
                AppearancePreferenceKey.FLOATING_NAV,
                AppearancePreferenceKey.BLUR_ENABLED,
                AppearancePreferenceKey.LIQUID_GLASS_ENABLED,
                AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED,
                AppearancePreferenceKey.HAPTICS_ENABLED,
            ),
            managerTogglePreferenceKeys,
        )
    }

    @Test
    fun aboutUsesInjectedVersionAndOnlyBoundedProductCopy() {
        val about = aboutPresentation("fixture-desktop")
        val rendered = listOf(about.title, about.versionLabel, about.description).joinToString("\n")

        assertEquals("关于", about.title)
        assertEquals("版本 fixture-desktop", about.versionLabel)
        assertFalse(rendered.contains("ICCID", ignoreCase = true))
        assertFalse(rendered.contains("IMSI", ignoreCase = true))
        assertFalse(rendered.contains("MSISDN", ignoreCase = true))
        assertFalse(rendered.contains("/data/"))
        assertFalse(rendered.contains("key", ignoreCase = true))
        assertFalse(rendered.contains("raw log", ignoreCase = true))
    }

    @Test
    fun unsafeOrUnboundedVersionDoesNotReachAboutCopy() {
        val unsafe = "/data/private/raw.log"

        val about = aboutPresentation(unsafe)

        assertEquals("版本 未知", about.versionLabel)
        assertFalse(about.toString().contains(unsafe))
    }
}
