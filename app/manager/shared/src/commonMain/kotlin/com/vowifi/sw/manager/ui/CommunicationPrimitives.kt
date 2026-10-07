package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.offset

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.TextStyle
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.CallDirection
import com.vowifi.sw.manager.model.CallOutcome
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Messages
import top.yukonga.miuix.kmp.icon.extended.Phone
import top.yukonga.miuix.kmp.icon.extended.Refresh

@Composable
internal fun OverallStatusHero(spec: OverallStatusHeroSpec, modifier: Modifier = Modifier) {
    val visual = toneVisual(spec.tone)
    val colors = LocalSoftVoWiFiColors.current
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
        colors = CardDefaults.defaultColors(colors.surface, colors.textPrimary),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(visual.icon.image(), contentDescription = visual.semanticLabel, tint = visual.color(colors), modifier = Modifier.size(24.dp))
                Text(spec.title, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, softWrap = true)
            }
            Text(spec.summary, color = colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp, softWrap = true)
            SemanticStatusBadge(spec.statusText, spec.tone)
        }
    }
}

@Composable
internal fun NoticeCard(spec: NoticeCardSpec, modifier: Modifier = Modifier) {
    val visual = toneVisual(spec.tone)
    val colors = LocalSoftVoWiFiColors.current
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
        colors = CardDefaults.defaultColors(visual.surface(colors), colors.textPrimary),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(visual.icon.image(), contentDescription = visual.semanticLabel, tint = visual.color(colors), modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(spec.title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, softWrap = true)
                Text(spec.summary, color = colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp, softWrap = true)
            }
        }
    }
}

@Composable
internal fun SectionTitle(spec: SectionTitleSpec, modifier: Modifier = Modifier) {
    BasicComponent(modifier = modifier, title = spec.title, summary = spec.summary)
}

@Composable
internal fun CompactActionRow(spec: CompactActionRowSpec, onAction: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalSoftVoWiFiColors.current
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
        colors = CardDefaults.defaultColors(colors.surface, colors.textPrimary),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(spec.title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, softWrap = true)
            Text(spec.summary, color = colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp, softWrap = true)
            SemanticStatusBadge(spec.statusText, spec.tone)
            Button(
                onClick = onAction,
                enabled = spec.action.enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                    if (spec.action.busy) progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                },
                colors = ButtonDefaults.buttonColors(
                    color = colors.interactive,
                    disabledColor = colors.separator,
                    contentColor = colors.surface,
                    disabledContentColor = colors.textDisabled,
                ),
            ) {
                Text(spec.action.label, fontSize = 14.sp, lineHeight = 20.sp, softWrap = true)
            }
        }
    }
}

@Composable
internal fun CallHistoryRow(spec: CallHistoryRowPresentation, modifier: Modifier = Modifier,
    divider: Boolean = true, stackedMetadata: Boolean = false) {
    val colors = LocalSoftVoWiFiColors.current
    val palette = phoneColors()
    val missed = spec.outcome == CallOutcome.MISSED
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 80.dp).padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(if (missed) palette.missed.copy(alpha = .08f) else palette.tonal.copy(alpha = .6f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(CallPhoneIcons.Phone, null, Modifier.size(24.dp), tint = if (missed) palette.missed else palette.accent)
                Icon(if (spec.direction == CallDirection.INCOMING) CommunicationIncoming else CommunicationOutgoing,
                    null, Modifier.size(12.dp).align(Alignment.TopEnd).offset(x = (-5).dp, y = 6.dp), tint = if (missed) palette.missed else palette.accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(displayCallNumber(spec.peerDisplay), fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium,
                    color = if (missed) palette.missed else palette.text)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    val direction = if (spec.direction == CallDirection.INCOMING) "呼入" else "呼出"
                    val summary = when (spec.outcome) {
                        CallOutcome.MISSED -> "未接来电"
                        CallOutcome.COMPLETED -> "$direction · ${spec.durationText}"
                        else -> "$direction · ${spec.outcomeText} · ${spec.durationText}"
                    }
                    Text(summary, color = palette.secondary,
                        fontSize = 14.sp, lineHeight = 20.sp, style = TextStyle(fontFeatureSettings = "tnum"))
                }
                if (stackedMetadata) Text(spec.timeText, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 16.sp,
                    style = TextStyle(fontFeatureSettings = "tnum"))
            }
            if (!stackedMetadata) Text(spec.timeText, color = palette.secondary, fontSize = 13.sp, lineHeight = 20.sp,
                style = TextStyle(fontFeatureSettings = "tnum"))
        }
        if (divider) HorizontalDivider(Modifier.padding(start = 64.dp), color = palette.secondary.copy(alpha = .16f), thickness = .5.dp)
    }
}

@Composable
internal fun DialKeyButton(spec: DialKeyVisualSpec, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = LocalSoftVoWiFiColors.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Surface(onClick = onClick, enabled = spec.enabled, shape = CircleShape, color = colors.surfaceRaised,
            modifier = Modifier.size(72.dp).semantics {
            contentDescription = spec.semanticsLabel
        }) {
            Box(contentAlignment = Alignment.Center) {
                if (spec.key == DialKey.BACKSPACE) Icon(CommunicationBackspace, null, Modifier.size(28.dp))
                else Text(spec.key.emittedText.orEmpty(), fontSize = 24.sp, lineHeight = 32.sp, color = colors.textPrimary)
            }
        }
    }
}

@Composable
private fun SemanticStatusBadge(text: String, tone: SemanticTone) {
    val colors = LocalSoftVoWiFiColors.current
    val visual = toneVisual(tone)
    Row(
        modifier = Modifier.background(visual.surface(colors), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(visual.icon.image(), contentDescription = null, tint = visual.color(colors), modifier = Modifier.size(16.dp))
        Text(text, color = visual.color(colors), fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, softWrap = true)
    }
}

private fun ToneVisual.color(colors: SoftVoWiFiColors): Color = when (lightColorRole) {
    SemanticColorRole.INTERACTIVE -> colors.interactive
    SemanticColorRole.NEUTRAL -> colors.neutralStatus
    SemanticColorRole.SUCCESS -> colors.success
    SemanticColorRole.WARNING -> colors.warning
    SemanticColorRole.ERROR -> colors.error
}

private fun ToneVisual.surface(colors: SoftVoWiFiColors): Color = when (lightColorRole) {
    SemanticColorRole.INTERACTIVE, SemanticColorRole.NEUTRAL -> colors.surfaceRaised
    SemanticColorRole.SUCCESS -> colors.successSurface
    SemanticColorRole.WARNING -> colors.warningSurface
    SemanticColorRole.ERROR -> colors.errorSurface
}

private fun PresentationIcon.image(): ImageVector = when (this) {
    PresentationIcon.PROGRESS -> MiuixIcons.Refresh
    PresentationIcon.ERROR, PresentationIcon.BACKSPACE -> MiuixIcons.Close
    PresentationIcon.MESSAGE -> MiuixIcons.Messages
    PresentationIcon.INCOMING_CALL, PresentationIcon.OUTGOING_CALL -> MiuixIcons.Phone
    PresentationIcon.INFO, PresentationIcon.SUCCESS, PresentationIcon.WARNING -> MiuixIcons.Info
}
