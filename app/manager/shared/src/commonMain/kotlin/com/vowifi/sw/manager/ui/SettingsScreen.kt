package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.Phone
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings

internal data class AppearancePreferenceSpec(
    val key: AppearancePreferenceKey,
    val title: String,
    val summary: String,
    val checked: Boolean,
    val availability: ManagerPreferenceAvailability = ManagerPreferenceAvailability.Enabled,
)

internal data class AppearanceSectionSpec(
    val title: String,
    val specs: List<AppearancePreferenceSpec>,
)

internal fun appearanceSectionSpecs(
    settings: AppearanceSettings,
    blurSupported: Boolean,
): List<AppearanceSectionSpec> {
    val navSpecs = mutableListOf<AppearancePreferenceSpec>()
    navSpecs += AppearancePreferenceSpec(
        AppearancePreferenceKey.FLOATING_NAV,
        "悬浮底栏",
        if (settings.floatingNavigation) "使用紧凑悬浮导航" else "使用标准底部导航",
        settings.floatingNavigation,
    )
    navSpecs += AppearancePreferenceSpec(
        AppearancePreferenceKey.BLUR_ENABLED,
        "模糊效果",
        appearanceBlurSummary(settings, blurSupported).text,
        settings.blurRequested,
    )
    if (settings.floatingNavigation) {
        val summary = "已改用原生静态导航，不再提供液态折射"
        navSpecs += AppearancePreferenceSpec(
            AppearancePreferenceKey.LIQUID_GLASS_ENABLED,
            "液态玻璃",
            summary,
            false,
            availability = ManagerPreferenceAvailability.Disabled(summary),
        )
    }

    val interactionSpecs = listOf(
        AppearancePreferenceSpec(
            AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED,
            "预测性返回",
            predictiveBackSummary(settings.predictiveBackRequested),
            settings.predictiveBackRequested,
        ),
        AppearancePreferenceSpec(
            AppearancePreferenceKey.HAPTICS_ENABLED,
            "触感反馈",
            hapticsSummary(settings.hapticsEnabled),
            settings.hapticsEnabled,
        ),
    )

    return listOf(
        AppearanceSectionSpec("导航效果", navSpecs),
        AppearanceSectionSpec("交互", interactionSpecs),
    )
}

internal fun appearancePreferenceSpecs(
    settings: AppearanceSettings,
    blurSupported: Boolean,
): List<AppearancePreferenceSpec> = appearanceSectionSpecs(settings, blurSupported).flatMap { it.specs }

internal fun settingsRowToNavigatePreference(row: SettingsRowPresentation): ManagerPreferenceRow.Navigate {
    val availability = if (row.enabled && row.target is SettingsRowTarget.Navigate) {
        ManagerPreferenceAvailability.Enabled
    } else {
        ManagerPreferenceAvailability.Disabled(row.summary)
    }
    return managerNavigatePreference(
        title = row.title,
        summary = row.summary,
        startIcon = row.rootIcon(),
        availability = availability,
    )
}

internal fun appearanceSpecToTogglePreference(
    spec: AppearancePreferenceSpec,
): ManagerPreferenceRow.Toggle {
    val icon = when (spec.key) {
        AppearancePreferenceKey.FLOATING_NAV -> MiuixIcons.Phone
        AppearancePreferenceKey.BLUR_ENABLED -> MiuixIcons.Refresh
        AppearancePreferenceKey.LIQUID_GLASS_ENABLED -> MiuixIcons.Refresh
        AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED -> MiuixIcons.Phone
        AppearancePreferenceKey.HAPTICS_ENABLED -> MiuixIcons.Settings
    }
    return managerTogglePreference(
        key = spec.key,
        title = spec.title,
        summary = spec.summary,
        startIcon = icon,
        checked = spec.checked,
        availability = spec.availability,
    )
}

@Composable
internal fun SettingsScreen(
    catalog: SettingsCatalog,
    onNavigate: (com.vowifi.sw.manager.ui.navigation.AppRoute.Subpage) -> Unit,
) {
    settingsHubContract()
    val layout = settingsHubLayoutContract()
    AppPageScaffold(
        title = appRootTitle(com.vowifi.sw.manager.ui.navigation.AppRoute.Settings),
        contentHorizontalInset = layout.horizontalInset,
        contentTopInset = 0.dp,
    ) {
        settingsCardGroups(catalog).forEachIndexed { index, card ->
            item(key = "settings-card-${card.rows.first().id}") {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(
                        top = if (index == 0) layout.topInset else layout.cardGap,
                    ),
                    insideMargin = PaddingValues(vertical = ManagerPreferenceLayoutContract.cardInsideVerticalPadding),
                ) {
                    Column {
                        card.rows.forEach { row ->
                            val preferenceRow = settingsRowToNavigatePreference(row)
                            val route = (row.target as? SettingsRowTarget.Navigate)?.route?.appRoute()
                            val onClick = if (preferenceRow.actionable && route != null) {
                                { onNavigate(route) }
                            } else {
                                null
                            }
                            ManagerNavigatePreferenceItem(
                                row = preferenceRow,
                                onClick = onClick,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun SettingsRowPresentation.rootIcon(): ImageVector = when (id) {
    SettingsRowId.Root.APPEARANCE -> MiuixIcons.Settings
    SettingsRowId.Root.CONFIGURATION -> MiuixIcons.Settings
    SettingsRowId.Root.DIAGNOSTICS -> MiuixIcons.Info
    SettingsRowId.Root.OPERATIONAL_LOGS -> MiuixIcons.File
    SettingsRowId.Root.CALL_NOTIFICATION -> MiuixIcons.Refresh
    SettingsRowId.Root.ABOUT -> MiuixIcons.Info
    else -> MiuixIcons.Settings
}

internal data class SettingsAboutCopy(
    val title: String,
    val versionLabel: String,
    val description: String,
)

internal fun aboutPresentation(appVersion: String): SettingsAboutCopy {
    val safeVersion = appVersion.takeIf { it.matches(Regex("[A-Za-z0-9._+-]{1,32}")) } ?: "未知"
    return SettingsAboutCopy("关于", "版本 $safeVersion", "SoftVoWiFi 管理应用")
}
