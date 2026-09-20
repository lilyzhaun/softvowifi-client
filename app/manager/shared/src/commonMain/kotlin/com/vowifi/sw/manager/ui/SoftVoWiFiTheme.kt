package com.vowifi.sw.manager.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

data class SoftVoWiFiColors(
    val canvas: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val separator: Color,
    val interactive: Color,
    val success: Color,
    val successSurface: Color,
    val warning: Color,
    val warningSurface: Color,
    val error: Color,
    val errorSurface: Color,
    val neutralStatus: Color,
)

private val LightColors = SoftVoWiFiColors(
    canvas = Color(0xFFF4F6F8), surface = Color.White, surfaceRaised = Color(0xFFE9EDF2),
    textPrimary = Color(0xFF161616), textSecondary = Color(0xFF525252), textDisabled = Color(0xFF8D8D8D),
    separator = Color(0xFFDDE1E6), interactive = Color(0xFF376F6B), success = Color(0xFF198038),
    successSurface = Color(0xFFDEFBE6), warning = Color(0xFF8E6A00), warningSurface = Color(0xFFFCF4D6),
    error = Color(0xFFDA1E28), errorSurface = Color(0xFFFFF1F1), neutralStatus = Color(0xFF6F6F6F),
)

private val DarkColors = SoftVoWiFiColors(
    canvas = Color(0xFF121417), surface = Color(0xFF1C1F24), surfaceRaised = Color(0xFF252A31),
    textPrimary = Color(0xFFF4F4F4), textSecondary = Color(0xFFC6C6C6), textDisabled = Color(0xFF6F6F6F),
    separator = Color(0xFF393939), interactive = Color(0xFF91B8B3), success = Color(0xFF42BE65),
    successSurface = Color(0xFF0E3820), warning = Color(0xFFF1C21B), warningSurface = Color(0xFF3B2F05),
    error = Color(0xFFFA4D56), errorSurface = Color(0xFF520408), neutralStatus = Color(0xFFA8A8A8),
)

val LocalSoftVoWiFiColors = staticCompositionLocalOf { LightColors }

internal fun communicationThemeColors(dark: Boolean): SoftVoWiFiColors = if (dark) DarkColors else LightColors

@Composable
fun SoftVoWiFiTheme(forceDark: Boolean? = null, content: @Composable () -> Unit) {
    val dark = forceDark ?: isSystemInDarkTheme()
    val controller = remember(dark) {
        ThemeController(
            if (dark) ColorSchemeMode.Dark else ColorSchemeMode.Light,
        )
    }
    CompositionLocalProvider(LocalSoftVoWiFiColors provides communicationThemeColors(dark)) {
        MiuixTheme(controller = controller, content = content)
    }
}
