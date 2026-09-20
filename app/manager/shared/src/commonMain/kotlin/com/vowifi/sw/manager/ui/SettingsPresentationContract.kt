package com.vowifi.sw.manager.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val SettingsRhythm = 12.dp

internal data class SettingsHubLayoutContract(
    val groupHeaderCount: Int,
    val horizontalInset: Dp,
    val topInset: Dp,
    val cardGap: Dp,
)

internal fun settingsHubLayoutContract() = SettingsHubLayoutContract(
    groupHeaderCount = 0,
    horizontalInset = SettingsRhythm,
    topInset = SettingsRhythm,
    cardGap = SettingsRhythm,
)

internal data class SettingsCardPresentation(
    val rows: List<SettingsRowPresentation>,
)

internal fun settingsCardGroups(catalog: SettingsCatalog): List<SettingsCardPresentation> {
    val groups = catalog.groups.associateBy(SettingsGroupPresentation::id)
    return SETTINGS_CARD_GROUPS.map { groupIds ->
        SettingsCardPresentation(
            rows = groupIds.flatMap { groupId -> requireNotNull(groups[groupId]).rows },
        )
    }
}

private val SETTINGS_CARD_GROUPS = listOf(
    listOf(SettingsGroupId.APPEARANCE, SettingsGroupId.SIM_AND_NETWORK),
    listOf(SettingsGroupId.TROUBLESHOOTING),
    listOf(SettingsGroupId.INCOMING_CALL),
    listOf(SettingsGroupId.ABOUT),
)

internal data class AppearanceCardPresentation(
    val sections: List<AppearanceSectionSpec>,
    val groupHeaderCount: Int,
    val horizontalInset: Dp,
    val topInset: Dp,
)

internal fun appearanceCardPresentation(
    settings: AppearanceSettings,
    blurSupported: Boolean,
): AppearanceCardPresentation = AppearanceCardPresentation(
    sections = appearanceSectionSpecs(settings, blurSupported),
    groupHeaderCount = 2,
    horizontalInset = SettingsRhythm,
    topInset = SettingsRhythm,
)
