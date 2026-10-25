package com.vowifi.sw.manager.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.ConfigurationProvisionOutcome
import com.vowifi.sw.manager.model.ConfigurationProvisionState
import com.vowifi.sw.manager.model.EngineControlAction
import com.vowifi.sw.manager.model.EngineControlOutcome
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun LazyListScope.homeDashboardItems(
    presentation: HomeDashboardPresentation,
    onStart: (SimHandle) -> Unit,
    onStop: (SimHandle) -> Unit,
    onProvision: (SimHandle) -> Unit,
    onOpenNotice: (AppRoute.Subpage) -> Unit,
    onRefresh: () -> Unit,
    refreshEnabled: Boolean = true,
    refreshRunning: Boolean = false,
    provisioning: ConfigurationProvisionState = ConfigurationProvisionState(),
    callProtection: () -> Map<SimHandle, Int?> = { emptyMap() },
) {
    presentation.sections.forEach { section ->
        when (section) {
            HomeSectionId.NOTICES -> items(presentation.notices, key = { "home-notice-${it.title}" }) { notice ->
                val onAction = when (val action = notice.action) {
                    is HomeNoticeAction.Navigate -> ({ onOpenNotice(action.destination) })
                    is HomeNoticeAction.Refresh -> onRefresh
                }
                HomeNoticeCard(notice, onAction, Modifier.padding(bottom = homeLayoutContract.cardGapDp.dp),
                    refreshEnabled, refreshRunning)
            }
            HomeSectionId.HERO -> item("home-hero") {
                HomeTopGrid(presentation)
            }
            HomeSectionId.ACTIONS -> if (presentation.actions.isNotEmpty()) {
                item("home-actions-title") { SmallTitle("SIM 引擎") }
                item("home-sim-controls") {
                    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(vertical = 4.dp)) {
                        presentation.actions.forEach { row ->
                             key(row.action.handle) {
                                  HomeEngineRow(row, provisioning, callProtection) { dispatchHomeAction(row.action, onStart, onStop, onProvision) }
                            }
                        }
                    }
                }
            }
            HomeSectionId.FACTS -> {
                item("home-facts-title") { SmallTitle("状态信息") }
                item("home-facts") { HomeFactsCard(presentation.facts) }
            }
        }
    }
}

@Composable
private fun HomeFactsCard(facts: List<HomeFactPresentation>) {
    val colors = LocalSoftVoWiFiColors.current
    val contract = homeFactsLayoutContract()
    Card(
        modifier = Modifier.fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = contract.horizontalPadding, vertical = contract.verticalPadding),
        colors = CardDefaults.defaultColors(colors.surface, colors.textPrimary),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(contract.rowGap)) {
            facts.forEach { fact ->
                Column(verticalArrangement = Arrangement.spacedBy(contract.innerLabelValueGap)) {
                    Text(fact.label, color = colors.textSecondary, fontSize = contract.labelFontSize, lineHeight = contract.labelLineHeight)
                    Text(fact.value, color = colors.textPrimary, fontSize = contract.valueFontSize, lineHeight = contract.valueLineHeight)
                }
            }
        }
    }
}

@Composable
private fun HomeEngineRow(row: HomeSimActionPresentation, provisioning: ConfigurationProvisionState,
    callProtection: () -> Map<SimHandle, Int?>, onAction: () -> Unit) {
    val creating = row.action.id == HomeActionId.PROVISION
    val provisionPending = creating && row.action.handle in provisioning.pending
    val provisionCompletion = if (creating) provisioning.completions[row.action.handle] else null
    val completion = row.action.controlCompletion.takeUnless { creating }
    val active = LocalAppRouteActive.current
    val haptics = LocalHapticFeedback.current
    var stopWarning by remember(row.action.handle) { mutableStateOf<String?>(null) }
    LaunchedEffect(active, row.action.id, row.action.enabled) {
        if (!active || row.action.id != HomeActionId.STOP || !row.action.enabled) stopWarning = null
    }
    val callProtected = row.action.id == HomeActionId.STOP && row.action.handle in callProtection()
    val requestAction = {
        val protectedSims = callProtection()
        if (row.action.id == HomeActionId.STOP && row.action.handle in protectedSims) {
            stopWarning = engineStopCallWarning(protectedSims[row.action.handle])
        } else onAction()
    }
    var seenCompletion by remember(row.action.handle) { mutableStateOf(completion?.sequence) }
    LaunchedEffect(completion?.sequence, active) {
        if (completion != null && completion.sequence != seenCompletion) {
            seenCompletion = completion.sequence
            if (active && completion.outcome == EngineControlOutcome.Failed) {
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
            }
        }
    }
    var seenProvisionCompletion by remember(row.action.handle) { mutableStateOf(provisionCompletion?.sequence) }
    LaunchedEffect(provisionCompletion?.sequence, active) {
        if (provisionCompletion != null && provisionCompletion.sequence != seenProvisionCompletion) {
            seenProvisionCompletion = provisionCompletion.sequence
            if (active && (provisionCompletion.outcome == ConfigurationProvisionOutcome.Failed ||
                    provisionCompletion.outcome == ConfigurationProvisionOutcome.Unconfirmed)) {
                haptics.performHapticFeedback(HapticFeedbackType.Reject)
            }
        }
    }
    val failure = if (!creating) homeEngineFailure(row.action) else if (provisionPending) null else when (provisionCompletion?.outcome) {
        ConfigurationProvisionOutcome.Failed -> "创建配置失败，请重试"
        ConfigurationProvisionOutcome.Unconfirmed -> "创建配置结果未确认，请刷新核对"
        else -> null
    }
    var retainedFailure by remember(row.action.handle) { mutableStateOf(failure) }
    SideEffect { if (failure != null) retainedFailure = failure }
    val space = MotionTokens.SpringSpatialFast
    val heightSpec = spring<IntSize>(dampingRatio = space.dampingRatio, stiffness = space.stiffness)
    Column {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val stacked = homeTopGridLayout(HomeTopGridSpec(), HomeTopGridEnvironment(maxWidth.value, LocalDensity.current.fontScale)) == HomeTopGridLayout.STACKED
            val action: @Composable () -> Unit = {
                ManagerTextAction(row.action.label, if (callProtected) ManagerAction.Navigate else homeManagerAction(row.action.id), requestAction,
                    enabled = row.action.enabled, busy = if (creating) provisionPending else row.action.busy,
                    busyLabel = if (creating) "正在创建" else "正在处理",
                    labelCandidates = if (creating) listOf(row.action.label, "正在创建")
                        else listOf("启动引擎", "停止引擎", "正在处理"))
            }
            if (stacked) {
                val colors = LocalSoftVoWiFiColors.current
                Column(
                    Modifier.fillMaxWidth().heightIn(min = row.geometry.minimumRowDp.dp)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(row.title, modifier = Modifier.fillMaxWidth(), fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        fontWeight = FontWeight.Medium, color = colors.textPrimary)
                    row.summary.split('\n').forEach { line ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val segments = line.split(" · ")
                            segments.forEachIndexed { index, segment ->
                                Text(segment + if (index < segments.lastIndex) " ·" else "",
                                    fontSize = MiuixTheme.textStyles.body2.fontSize, color = colors.textSecondary)
                            }
                        }
                    }
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HomeStatusChip(row.statusText, row.tone)
                        action()
                    }
                }
            } else BasicComponent(
                modifier = Modifier.fillMaxWidth().heightIn(min = row.geometry.minimumRowDp.dp),
                insideMargin = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                title = row.title,
                summary = row.summary,
                bottomAction = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HomeStatusChip(row.statusText, row.tone)
                        if (stacked) action()
                    }
                },
                endActions = { if (!stacked) action() },
            )
        }
        AnimatedVisibility(failure != null,
            enter = expandVertically(heightSpec, expandFrom = Alignment.Top) +
                fadeIn(tween(MotionTokens.DurBase, easing = MotionTokens.EaseEnter)),
            exit = shrinkVertically(heightSpec, shrinkTowards = Alignment.Top) +
                fadeOut(tween(MotionTokens.DurFast, easing = MotionTokens.EaseExit)), label = "home-engine-failure") {
            Text(failure ?: retainedFailure.orEmpty(), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                .then(if (failure == null) Modifier.clearAndSetSemantics { } else Modifier),
                color = LocalSoftVoWiFiColors.current.error, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
    stopWarning?.let { warning ->
        HomeEngineStopConfirmation(warning, onCancel = { stopWarning = null }, onConfirm = {
            stopWarning = null
            if (active && row.action.id == HomeActionId.STOP && row.action.enabled) onAction()
        })
    }
}

private fun homeEngineFailure(action: HomeActionPresentation): String? {
    val completion = action.controlCompletion ?: return null
    if (action.busy || completion.outcome != EngineControlOutcome.Failed) return null
    return when (completion.action) {
        EngineControlAction.Start -> "启动引擎失败，请重试".takeUnless { action.lifecycle == EngineLifecycle.RUNNING }
        EngineControlAction.Stop -> "停止引擎失败，请重试".takeUnless { action.lifecycle == EngineLifecycle.STOPPED }
    }
}
