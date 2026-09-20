package com.vowifi.sw.manager.ui

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal object ManagerPreferenceLayoutContract {
    val minTouchTarget: Dp = 48.dp
    val cardGap: Dp = 12.dp
    val cardInsideVerticalPadding: Dp = 4.dp
    val startIconEndPadding: Dp = 6.dp
    const val pressScale: Float = 0.96f
    const val groupedPreferencesUseCardClip: Boolean = true
    const val groupedPreferencesApplyExtraPressScale: Boolean = true
    const val usesDefaultPlatformGrayIndication: Boolean = false
    const val usesGrayFillClippedToRow: Boolean = false
    const val suppressesPlatformDefaultIndication: Boolean = true
    const val actionOwnerCountPerItem: Int = 1
    const val wrapperCardOwnsAction: Boolean = false
    const val toggleBehaviorIsExactlyOnce: Boolean = true
}

internal enum class ManagerPreferenceComponent {
    ArrowPreference,
    SwitchPreference,
    BasicComponent,
}

internal sealed interface ManagerPreferenceAvailability {
    data object Enabled : ManagerPreferenceAvailability

    data class Disabled(val reason: String) : ManagerPreferenceAvailability {
        init {
            require(reason.isNotBlank())
        }
    }

    data object Busy : ManagerPreferenceAvailability

    data class Error(val summary: String) : ManagerPreferenceAvailability {
        init {
            require(summary.isNotBlank())
        }
    }
}

internal sealed interface ManagerPreferenceRow {
    val title: String
    val summary: String
    val startIcon: ImageVector
    val availability: ManagerPreferenceAvailability

    val renderedComponent: ManagerPreferenceComponent
        get() = when (this) {
            is Navigate -> if (actionable) ManagerPreferenceComponent.ArrowPreference else ManagerPreferenceComponent.BasicComponent
            is Toggle -> ManagerPreferenceComponent.SwitchPreference
            is Information -> ManagerPreferenceComponent.BasicComponent
        }

    val actionable: Boolean
        get() = this !is Information && availability == ManagerPreferenceAvailability.Enabled

    val showsProgress: Boolean
        get() = availability == ManagerPreferenceAvailability.Busy

    val isVisuallyEnabled: Boolean
        get() = availability !is ManagerPreferenceAvailability.Disabled

    val isErrorState: Boolean
        get() = availability is ManagerPreferenceAvailability.Error

    val visibleSummary: String
        get() = when (val state = availability) {
            is ManagerPreferenceAvailability.Disabled -> state.reason
            is ManagerPreferenceAvailability.Error -> state.summary
            ManagerPreferenceAvailability.Busy,
            ManagerPreferenceAvailability.Enabled,
            -> summary
        }

    data class Navigate(
        override val title: String,
        override val summary: String,
        override val startIcon: ImageVector,
        override val availability: ManagerPreferenceAvailability,
    ) : ManagerPreferenceRow

    data class Information(
        override val title: String,
        override val summary: String,
        override val startIcon: ImageVector,
        override val availability: ManagerPreferenceAvailability = ManagerPreferenceAvailability.Enabled,
    ) : ManagerPreferenceRow

    class Toggle private constructor(
        val key: AppearancePreferenceKey,
        val checked: Boolean,
        override val title: String,
        override val summary: String,
        override val startIcon: ImageVector,
        override val availability: ManagerPreferenceAvailability,
    ) : ManagerPreferenceRow {
        companion object {
            fun create(
                key: AppearancePreferenceKey,
                title: String,
                summary: String,
                startIcon: ImageVector,
                checked: Boolean,
                availability: ManagerPreferenceAvailability,
            ): Toggle = when (key) {
                AppearancePreferenceKey.FLOATING_NAV,
                AppearancePreferenceKey.BLUR_ENABLED,
                AppearancePreferenceKey.LIQUID_GLASS_ENABLED,
                AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED,
                AppearancePreferenceKey.HAPTICS_ENABLED,
                -> Toggle(key, checked, title, summary, startIcon, availability)
            }
        }
    }
}

internal val managerTogglePreferenceKeys: Set<AppearancePreferenceKey> = setOf(
    AppearancePreferenceKey.FLOATING_NAV,
    AppearancePreferenceKey.BLUR_ENABLED,
    AppearancePreferenceKey.LIQUID_GLASS_ENABLED,
    AppearancePreferenceKey.PREDICTIVE_BACK_ENABLED,
    AppearancePreferenceKey.HAPTICS_ENABLED,
)

internal fun managerNavigatePreference(
    title: String,
    summary: String,
    startIcon: ImageVector,
    availability: ManagerPreferenceAvailability = ManagerPreferenceAvailability.Enabled,
): ManagerPreferenceRow.Navigate {
    requirePreferenceCopy(title, summary)
    return ManagerPreferenceRow.Navigate(title, summary, startIcon, availability)
}

internal fun managerInformationPreference(
    title: String,
    summary: String,
    startIcon: ImageVector,
): ManagerPreferenceRow.Information {
    requirePreferenceCopy(title, summary)
    return ManagerPreferenceRow.Information(title, summary, startIcon)
}

internal fun managerTogglePreference(
    key: AppearancePreferenceKey,
    title: String,
    summary: String,
    startIcon: ImageVector,
    checked: Boolean,
    availability: ManagerPreferenceAvailability = ManagerPreferenceAvailability.Enabled,
): ManagerPreferenceRow.Toggle {
    requirePreferenceCopy(title, summary)
    return ManagerPreferenceRow.Toggle.create(key, title, summary, startIcon, checked, availability)
}

private fun requirePreferenceCopy(title: String, summary: String) {
    require(title.isNotBlank())
    require(summary.isNotBlank())
}
