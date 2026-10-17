package com.vowifi.sw.manager.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Initial/reentered results are baseline, not fresh failure events. No pending-edge inference. */
@Composable
internal fun ConfigurationCompletionFeedback(sequence: Long, rejected: Boolean) {
    val seen = remember { mutableLongStateOf(sequence) }
    val active = rememberUpdatedState(LocalAppRouteActive.current)
    val reject = rememberUpdatedState(rememberManagerActionHandler(managerActionFeedback(ManagerAction.Rejected)) { })
    LaunchedEffect(sequence) {
        if (sequence > seen.longValue) {
            seen.longValue = sequence
            if (active.value && rejected) reject.value()
        }
    }
}

/** Public BasicComponent content slot keeps its padding/title/body styles; only summary changes. */
@Composable
internal fun ConfigurationCompletionSummary(
    title: String,
    summary: String,
    sequence: Long,
    startAction: (@Composable () -> Unit)? = null,
) {
    val target = summary to sequence
    val current = rememberUpdatedState(target)
    val titleColor = BasicComponentDefaults.titleColor().color
    val summaryColor = BasicComponentDefaults.summaryColor().color
    BasicComponent(startAction = startAction) {
        Text(title, fontSize = MiuixTheme.textStyles.headline1.fontSize, fontWeight = FontWeight.Medium, color = titleColor)
        AnimatedContent(targetState = target, contentAlignment = Alignment.TopStart,
            transitionSpec = { (fadeIn(MotionTokens.Crossfade.alphaSpec) togetherWith fadeOut(MotionTokens.Crossfade.alphaSpec)) using null },
            label = "configuration-completion-summary",
        ) { shown ->
            Text(shown.first, fontSize = MiuixTheme.textStyles.body2.fontSize, color = summaryColor,
                modifier = if (shown == current.value) Modifier else Modifier.clearAndSetSemantics { })
        }
    }
}
