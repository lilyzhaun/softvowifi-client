package com.vowifi.sw.manager.ui

internal enum class SettingsRootTopBarStyle { LARGE_COLLAPSIBLE }
internal enum class SettingsSubpageTopBarStyle { SMALL_LEADING_BACK }

internal data class SettingsIconGrammar(
    val rootRowsHaveStartIcons: Boolean,
    val disabledRowsHaveStartIcons: Boolean,
    val appearanceSwitchesHaveStartIcons: Boolean,
    val groupCardSpacingDp: Int,
    val rootTopBarStyle: SettingsRootTopBarStyle,
    val subpageTopBarStyle: SettingsSubpageTopBarStyle,
)

internal fun settingsIconGrammar(catalog: SettingsCatalog): SettingsIconGrammar {
    require(catalog.groups.flatMap { it.rows }.isNotEmpty())
    return SettingsIconGrammar(
        rootRowsHaveStartIcons = true,
        disabledRowsHaveStartIcons = true,
        appearanceSwitchesHaveStartIcons = true,
        groupCardSpacingDp = 12,
        rootTopBarStyle = SettingsRootTopBarStyle.LARGE_COLLAPSIBLE,
        subpageTopBarStyle = SettingsSubpageTopBarStyle.SMALL_LEADING_BACK,
    )
}
