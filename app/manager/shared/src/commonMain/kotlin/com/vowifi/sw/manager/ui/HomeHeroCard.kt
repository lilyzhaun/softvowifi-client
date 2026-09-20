package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text

@Composable
internal fun HomeTopGrid(presentation: HomeDashboardPresentation) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val spec = presentation.topGrid
        val environment = HomeTopGridEnvironment(maxWidth.value, LocalDensity.current.fontScale)
        val gap = homeLayoutContract.topGridInternalGapDp.dp
        when (homeTopGridLayout(spec, environment)) {
            HomeTopGridLayout.SPLIT -> Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
                HomeStatusHero(presentation.hero, Modifier.weight(spec.heroColumnWeight).fillMaxHeight())
                Column(
                    Modifier.weight(spec.metricColumnWeight).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    presentation.metrics.forEach { HomeMetricCard(it, Modifier.weight(1f)) }
                }
            }
            HomeTopGridLayout.STACKED -> Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                HomeStatusHero(presentation.hero)
                presentation.metrics.forEach { HomeMetricCard(it) }
            }
        }
    }
}

@Composable
internal fun HomeStatusHero(
    hero: HomeHeroPresentation,
    modifier: Modifier = Modifier,
) {
    val colors = LocalSoftVoWiFiColors.current
    val text = homeHeroText(hero, LocalDensity.current.fontScale)
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
        colors = CardDefaults.defaultColors(hero.tone.homeSurface(colors), colors.textPrimary),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeStatusChip(hero.statusText, hero.tone)
            Text(text.title, color = colors.textPrimary, fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold)
            Text(text.summary, color = colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun HomeMetricCard(metric: HomeMetricPresentation, modifier: Modifier = Modifier) {
    val colors = LocalSoftVoWiFiColors.current
    Card(
        modifier = modifier.fillMaxWidth(),
        insideMargin = PaddingValues(16.dp),
        colors = CardDefaults.defaultColors(colors.surface, colors.textPrimary),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(metric.value, color = colors.textPrimary, fontSize = 26.sp, lineHeight = 32.sp,
                fontWeight = FontWeight.SemiBold, style = TextStyle(fontFeatureSettings = "tnum"))
            Text(metric.label, color = colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}
