package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings

@Composable
internal fun HomeStatusChip(text: String, tone: SemanticTone, modifier: Modifier = Modifier) {
    SemanticStatusBadge(homeStatusBadge(text, tone), modifier)
}

internal fun homeManagerAction(id: HomeActionId): ManagerAction = when (id) {
    HomeActionId.START -> ManagerAction.StartEngine
    HomeActionId.STOP -> ManagerAction.StopEngine
    HomeActionId.PROVISION -> ManagerAction.ProvisionConfiguration
}

internal fun HomeActionPresentation.homeIcon(): ImageVector = when {
    busy -> MiuixIcons.Refresh
    id == HomeActionId.STOP -> MiuixIcons.Pause
    id == HomeActionId.PROVISION -> MiuixIcons.Settings
    else -> MiuixIcons.Play
}

internal fun SemanticTone.homeIcon(): ImageVector = when (this) {
    SemanticTone.INTERACTIVE -> MiuixIcons.Refresh
    SemanticTone.ERROR -> MiuixIcons.Close
    SemanticTone.NEUTRAL, SemanticTone.SUCCESS, SemanticTone.WARNING -> MiuixIcons.Info
}

internal fun SemanticTone.homeColor(colors: SoftVoWiFiColors): Color = when (this) {
    SemanticTone.INTERACTIVE -> colors.interactive
    SemanticTone.NEUTRAL -> colors.neutralStatus
    SemanticTone.SUCCESS -> colors.success
    SemanticTone.WARNING -> colors.warning
    SemanticTone.ERROR -> colors.error
}

internal fun SemanticTone.homeSurface(colors: SoftVoWiFiColors): Color = when (this) {
    SemanticTone.INTERACTIVE, SemanticTone.NEUTRAL -> colors.surfaceRaised
    SemanticTone.SUCCESS -> colors.successSurface
    SemanticTone.WARNING -> colors.warningSurface
    SemanticTone.ERROR -> colors.errorSurface
}
