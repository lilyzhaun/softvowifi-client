package com.vowifi.sw.manager.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
) {
    presentation.sections.forEach { section ->
        when (section) {
            HomeSectionId.NOTICES -> items(presentation.notices, key = { "home-notice-${it.title}" }) { notice ->
                HomeNoticeCard(notice, { onOpenNotice(notice.destination) }, Modifier.padding(bottom = homeLayoutContract.cardGapDp.dp))
            }
            HomeSectionId.HERO -> item("home-hero") {
                HomeTopGrid(presentation)
            }
            HomeSectionId.ACTIONS -> if (presentation.actions.isNotEmpty()) {
                item("home-actions-title") { SmallTitle("SIM 引擎") }
                item("home-sim-controls") {
                    Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(vertical = 4.dp)) {
                        presentation.actions.forEach { row ->
                            HomeEngineRow(row) { dispatchHomeAction(row.action, onStart, onStop, onProvision) }
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
private fun HomeEngineRow(row: HomeSimActionPresentation, onAction: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = homeTopGridLayout(HomeTopGridSpec(), HomeTopGridEnvironment(maxWidth.value, LocalDensity.current.fontScale)) == HomeTopGridLayout.STACKED
        val action: @Composable () -> Unit = {
            ManagerTextAction(row.action.label, homeManagerAction(row.action.id), onAction,
                enabled = row.action.enabled, busy = row.action.busy)
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
}
