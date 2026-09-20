package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh

internal enum class ManagerStatusKind {
    Loading,
    Empty,
    Unavailable,
    PermissionDenied,
    Failure,
    Busy,
    Warning,
}

internal data class ManagerStatus(
    val kind: ManagerStatusKind,
    val title: String,
    val summary: String,
    val retryDescription: String?,
) {
    init {
        require(title.isNotBlank())
        require(summary.isNotBlank())
        require(retryDescription == null || retryDescription.isNotBlank())
    }

    fun toStatePanelSpec(): StatePanelSpec = when (kind) {
        ManagerStatusKind.Loading -> StatePanelSpec(MiuixIcons.Refresh, StatePanelTone.INTERACTIVE, title, summary, loading = true, retryDescription = retryDescription)
        ManagerStatusKind.Empty -> StatePanelSpec(MiuixIcons.Info, StatePanelTone.NEUTRAL, title, summary, retryDescription = retryDescription)
        ManagerStatusKind.Unavailable -> StatePanelSpec(MiuixIcons.Info, StatePanelTone.NEUTRAL, title, summary, retryDescription = retryDescription)
        ManagerStatusKind.PermissionDenied -> StatePanelSpec(MiuixIcons.Close, StatePanelTone.ERROR, title, summary, retryDescription = retryDescription)
        ManagerStatusKind.Failure -> StatePanelSpec(MiuixIcons.Close, StatePanelTone.ERROR, title, summary, retryDescription = retryDescription)
        ManagerStatusKind.Busy -> StatePanelSpec(MiuixIcons.Refresh, StatePanelTone.INTERACTIVE, title, summary, loading = true, retryDescription = retryDescription)
        ManagerStatusKind.Warning -> StatePanelSpec(MiuixIcons.Info, StatePanelTone.WARNING, title, summary, retryDescription = retryDescription)
    }
}

internal fun managerStatus(
    kind: ManagerStatusKind,
    title: String,
    summary: String,
    retryDescription: String? = null,
): ManagerStatus = ManagerStatus(kind, title, summary, retryDescription)

internal fun homeNoticeStatus(spec: NoticeCardSpec): ManagerStatus = ManagerStatus(
    kind = when (spec.tone) {
        SemanticTone.ERROR -> ManagerStatusKind.Failure
        SemanticTone.WARNING -> ManagerStatusKind.Warning
        SemanticTone.INTERACTIVE -> ManagerStatusKind.Busy
        SemanticTone.NEUTRAL, SemanticTone.SUCCESS -> ManagerStatusKind.Unavailable
    },
    title = spec.title,
    summary = spec.summary,
    retryDescription = null,
)

internal fun NoticeCardSpec.toManagerStatus(): ManagerStatus = homeNoticeStatus(this)

internal fun homeStatusBadge(label: String, tone: SemanticTone): SemanticStatusBadgeSpec =
    semanticStatusBadge(label, tone)

internal data class SemanticStatusBadgeSpec(
    val label: String,
    val tone: SemanticTone,
) {
    init {
        require(label.isNotBlank())
    }

    val icon: PresentationIcon = toneVisual(tone).icon
    val softWrap: Boolean = true
    val maxLines: Int? = null
}

internal fun semanticStatusBadge(label: String, tone: SemanticTone): SemanticStatusBadgeSpec =
    SemanticStatusBadgeSpec(label, tone)

@Composable
internal fun SemanticStatusBadge(spec: SemanticStatusBadgeSpec, modifier: Modifier = Modifier) {
    val colors = LocalSoftVoWiFiColors.current
    val visual = toneVisual(spec.tone)
    val color = visual.badgeColor(colors)
    Row(
        modifier = modifier
            .background(visual.badgeSurface(colors), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = visual.icon.managerStatusIcon(),
            contentDescription = visual.semanticLabel,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = spec.label,
            color = color,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            softWrap = spec.softWrap,
        )
    }
}

private fun PresentationIcon.managerStatusIcon(): ImageVector = when (this) {
    PresentationIcon.PROGRESS -> MiuixIcons.Refresh
    PresentationIcon.ERROR, PresentationIcon.BACKSPACE -> MiuixIcons.Close
    PresentationIcon.INFO, PresentationIcon.SUCCESS, PresentationIcon.WARNING -> MiuixIcons.Info
    PresentationIcon.MESSAGE -> MiuixIcons.Info
    PresentationIcon.INCOMING_CALL, PresentationIcon.OUTGOING_CALL -> MiuixIcons.Info
}

private fun ToneVisual.badgeColor(colors: SoftVoWiFiColors) = when (lightColorRole) {
    SemanticColorRole.INTERACTIVE -> colors.interactive
    SemanticColorRole.NEUTRAL -> colors.neutralStatus
    SemanticColorRole.SUCCESS -> colors.success
    SemanticColorRole.WARNING -> colors.warning
    SemanticColorRole.ERROR -> colors.error
}

private fun ToneVisual.badgeSurface(colors: SoftVoWiFiColors) = when (lightColorRole) {
    SemanticColorRole.INTERACTIVE, SemanticColorRole.NEUTRAL -> colors.surfaceRaised
    SemanticColorRole.SUCCESS -> colors.successSurface
    SemanticColorRole.WARNING -> colors.warningSurface
    SemanticColorRole.ERROR -> colors.errorSurface
}
