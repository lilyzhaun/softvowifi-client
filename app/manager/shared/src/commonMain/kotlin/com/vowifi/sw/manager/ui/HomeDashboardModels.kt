package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.ui.navigation.AppRoute

internal enum class HomeSectionId { NOTICES, HERO, ACTIONS, FACTS }

internal enum class HomeActionId { START, STOP, PROVISION }

internal enum class HomeTopGridLayout { SPLIT, STACKED }

internal data class HomeTopGridEnvironment(val availableWidthDp: Float, val fontScale: Float)

internal data class HomeTopGridSpec(
    val splitMinAvailableWidthDp: Int = 360,
    val largeFontThreshold: Float = 1.5f,
    val heroColumnWeight: Float = 1f,
    val metricColumnWeight: Float = 1f,
)

internal fun homeTopGridLayout(spec: HomeTopGridSpec, environment: HomeTopGridEnvironment): HomeTopGridLayout = when {
    environment.fontScale >= spec.largeFontThreshold -> HomeTopGridLayout.STACKED
    environment.availableWidthDp < spec.splitMinAvailableWidthDp -> HomeTopGridLayout.STACKED
    else -> HomeTopGridLayout.SPLIT
}

internal data class HomeHeroTextBlock(
    val title: String,
    val summaryLines: List<String>,
) {
    val summary: String = summaryLines.joinToString("\n")
    val visibleLines: List<String> = listOf(title) + summaryLines
}

internal fun homeNonBreakingPhrase(value: String): String = value.toList().joinToString("\u2060")

internal data class HomeHeroPresentation(
    val tone: SemanticTone,
    val statusText: String,
    val regularText: HomeHeroTextBlock,
    val largeText: HomeHeroTextBlock,
    val largeTextMinFontScale: Float = 1.5f,
) {
    val title: String = regularText.title
    val summary: String = regularText.summary
}

internal fun homeHeroText(
    hero: HomeHeroPresentation,
    fontScale: Float,
): HomeHeroTextBlock = if (fontScale >= hero.largeTextMinFontScale) {
    hero.largeText
} else {
    hero.regularText
}

internal data class HomeMetricPresentation(
    val value: String,
    val label: String,
    val tone: SemanticTone,
)

internal data class HomeActionPresentation(
    val id: HomeActionId,
    val handle: SimHandle,
    val label: String,
    val enabled: Boolean,
    val busy: Boolean,
) {
    val minimumTouchDp: Int = 48
}

internal data class HomeCompactRowGeometry(
    val trailingAction: Boolean = true,
    val fullWidthAction: Boolean = false,
    val minimumRowDp: Int = 72,
    val minimumTouchDp: Int = 48,
    val stackAtLargeFont: Boolean = true,
    val largeFontThreshold: Float = 1.5f,
)

internal data class HomeSimActionPresentation(
    val title: String,
    val summary: String,
    val statusText: String,
    val tone: SemanticTone,
    val action: HomeActionPresentation,
) {
    val titleFlow = TextFlowSpec()
    val stackAtLargeFont: Boolean = true
    val geometry = HomeCompactRowGeometry()
}

internal data class HomeFactFlowSpec(
    val softWrap: Boolean = true,
    val stackAtLargeFont: Boolean = true,
)

internal data class HomeFactPresentation(
    val label: String,
    val value: String,
    val flow: HomeFactFlowSpec = HomeFactFlowSpec(),
)

internal data class HomeNoticePresentation(
    val spec: NoticeCardSpec,
    val destination: AppRoute.Subpage,
) {
    val title: String get() = spec.title
    val summary: String get() = spec.summary
    val tone: SemanticTone get() = spec.tone
}

internal data class HomeDashboardPresentation(
    val notices: List<HomeNoticePresentation>,
    val hero: HomeHeroPresentation,
    val metrics: List<HomeMetricPresentation>,
    val actions: List<HomeSimActionPresentation>,
    val facts: List<HomeFactPresentation>,
) {
    val topGrid = HomeTopGridSpec(splitMinAvailableWidthDp = 328)
    val sections = listOf(HomeSectionId.NOTICES, HomeSectionId.HERO, HomeSectionId.ACTIONS, HomeSectionId.FACTS)
    val visibleStrings: List<String>
        get() = buildList {
            notices.forEach { add(it.title); add(it.summary) }
            hero.regularText.visibleLines.forEach(::add)
            hero.largeText.visibleLines.forEach(::add)
            add(hero.statusText)
            metrics.forEach { add(it.value); add(it.label) }
            actions.forEach { add(it.title); add(it.summary); add(it.statusText); add(it.action.label) }
            facts.forEach { add(it.label); add(it.value) }
        }
}
