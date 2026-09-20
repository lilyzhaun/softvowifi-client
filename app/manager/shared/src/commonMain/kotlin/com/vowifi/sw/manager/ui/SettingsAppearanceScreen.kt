package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.SmallTitle

data class AppearanceSettingsActions(
    val onFloatingNavigationChange: (Boolean) -> Unit,
    val onBlurRequestedChange: (Boolean) -> Unit,
    val onLiquidGlassRequestedChange: (Boolean) -> Unit,
    val onPredictiveBackRequestedChange: (Boolean) -> Unit,
    val onHapticsEnabledChange: (Boolean) -> Unit,
)

@Composable
internal fun SettingsAppearanceScreen(
    settings: AppearanceSettings,
    blurSupported: Boolean,
    actions: AppearanceSettingsActions,
) {
    settingsAppearanceContract()
    val presentation = appearanceCardPresentation(settings, blurSupported)
    val sections = presentation.sections
    AppPageScaffold(
        title = "外观",
        showTopBar = false,
        contentHorizontalInset = presentation.horizontalInset,
        contentTopInset = 0.dp,
    ) {
        sections.forEachIndexed { index, section ->
            item(key = "appearance-title-${section.title}") {
                SmallTitle(section.title)
            }
            item(key = "appearance-card-${section.title}") {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(
                        bottom = if (index < sections.lastIndex) 12.dp else 0.dp,
                    ),
                    insideMargin = PaddingValues(vertical = ManagerPreferenceLayoutContract.cardInsideVerticalPadding),
                ) {
                    Column {
                        section.specs.forEach { spec ->
                            if (spec.key == AppearancePreferenceKey.LIQUID_GLASS_ENABLED) {
                                BasicComponent(title = spec.title, summary = spec.summary)
                                return@forEach
                            }
                            val toggleRow = appearanceSpecToTogglePreference(spec)
                            val onCheckedChange: (Boolean) -> Unit = when (spec.key) {
                                AppearancePreferenceKey.FLOATING_NAV -> actions.onFloatingNavigationChange
                                AppearancePreferenceKey.BLUR_ENABLED -> actions.onBlurRequestedChange
                                AppearancePreferenceKey.LIQUID_GLASS_ENABLED -> actions.onLiquidGlassRequestedChange
                                AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED -> actions.onPredictiveBackRequestedChange
                                AppearancePreferenceKey.HAPTICS_ENABLED -> actions.onHapticsEnabledChange
                            }
                            ManagerTogglePreferenceItem(
                                row = toggleRow,
                                onCheckedChange = onCheckedChange,
                            )
                        }
                    }
                }
            }
        }
    }
}
