package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*

class SettingsIconGrammarTest {
    @Test fun rootAndAppearanceCardsNeverRepeatAnIconWithinTheSameCard() {
        val catalog = settingsCatalog(settingsSnapshot())
        settingsCardGroups(catalog).forEach { card ->
            val icons = card.rows.map { settingsRowToNavigatePreference(it).startIcon }
            assertEquals(icons.size, icons.distinct().size, "同一卡片的不同目的地须使用不同语义图标")
        }
        appearanceSectionSpecs(AppearanceSettings.Default, true).forEach { section ->
            val icons = section.specs.map { appearanceSpecToTogglePreference(it).startIcon }
            assertEquals(icons.size, icons.distinct().size)
        }
    }

    @Test fun rootIconsAndAppearanceSummaryDescribeAllFiveExistingPreferences() {
        val rows = settingsCatalog(settingsSnapshot()).groups.flatMap { it.rows }
        val icons = rows.associate { it.id to settingsRowToNavigatePreference(it).startIcon }
        assertEquals(MiuixIcons.Theme, icons[SettingsRowId.Root.APPEARANCE])
        assertEquals(MiuixIcons.Carrier, icons[SettingsRowId.Root.CONFIGURATION])
        assertEquals(MiuixIcons.SearchDevice, icons[SettingsRowId.Root.DIAGNOSTICS])
        assertEquals(MiuixIcons.Notes, icons[SettingsRowId.Root.OPERATIONAL_LOGS])
        assertEquals(MiuixIcons.Answer, icons[SettingsRowId.Root.CALL_NOTIFICATION])
        assertEquals(MiuixIcons.Info, icons[SettingsRowId.Root.ABOUT])
        val summary = rows.single { it.id == SettingsRowId.Root.APPEARANCE }.summary
        for (word in listOf("悬浮底栏", "模糊", "液态玻璃", "返回", "触感")) assertTrue(word in summary)
        val appearance = appearancePreferenceSpecs(AppearanceSettings.Default, true)
            .associate { it.key to appearanceSpecToTogglePreference(it).startIcon }
        assertEquals(MiuixIcons.Layers, appearance[AppearancePreferenceKey.FLOATING_NAV])
        assertEquals(MiuixIcons.Background, appearance[AppearancePreferenceKey.BLUR_ENABLED])
        assertEquals(MiuixIcons.Show, appearance[AppearancePreferenceKey.LIQUID_GLASS_ENABLED])
        assertEquals(MiuixIcons.Back, appearance[AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED])
        assertEquals(MiuixIcons.Tune, appearance[AppearancePreferenceKey.HAPTICS_ENABLED])
    }

    @Test
    fun settingsRowsAndAppearanceSwitchesOwnSemanticStartIcons() {
        val catalog = settingsCatalog(settingsSnapshot())
        val presentation = settingsIconGrammar(catalog)

        assertTrue(presentation.rootRowsHaveStartIcons)
        assertTrue(presentation.disabledRowsHaveStartIcons)
        assertTrue(presentation.appearanceSwitchesHaveStartIcons)
        assertEquals(12, presentation.groupCardSpacingDp)
        assertEquals(SettingsRootTopBarStyle.LARGE_COLLAPSIBLE, presentation.rootTopBarStyle)
        assertEquals(SettingsSubpageTopBarStyle.SMALL_LEADING_BACK, presentation.subpageTopBarStyle)
    }
}
