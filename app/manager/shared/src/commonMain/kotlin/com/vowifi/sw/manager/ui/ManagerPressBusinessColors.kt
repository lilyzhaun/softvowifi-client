package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import top.yukonga.miuix.kmp.basic.ButtonColors
import top.yukonga.miuix.kmp.basic.SwitchColors
import top.yukonga.miuix.kmp.basic.SwitchDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Route inactivity removes interaction, not the backend's resting enabled appearance. */
internal fun ButtonColors.preservingPressBusinessColors(businessEnabled: Boolean, routeActive: Boolean): ButtonColors =
    if (businessEnabled && !routeActive) copy(disabledColor = color, disabledContentColor = contentColor) else this

@Composable
internal fun managerPressSwitchColors(businessEnabled: Boolean, routeActive: Boolean): SwitchColors {
    if (!businessEnabled || routeActive) return SwitchDefaults.switchColors()
    // Original SwitchDefaults palette: miuix 0.9.3 / c36fab7, Apache-2.0, public parameters only.
    // https://github.com/compose-miuix-ui/miuix/blob/c36fab72391801d1e3ea5a00f966bf16bac28d4c/miuix-ui/src/commonMain/kotlin/top/yukonga/miuix/kmp/basic/Switch.kt
    val colors = MiuixTheme.colorScheme
    return SwitchDefaults.switchColors(
        disabledCheckedThumbColor = colors.onPrimary,
        disabledUncheckedThumbColor = if (MiuixTheme.isDynamicColor) colors.onSurface.copy(alpha = .38f) else colors.onSecondary,
        disabledCheckedTrackColor = colors.primary,
        disabledUncheckedTrackColor = colors.secondary,
    )
}
