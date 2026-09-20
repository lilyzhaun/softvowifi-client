package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsIconGrammarTest {
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
