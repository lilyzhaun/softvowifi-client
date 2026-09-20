package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeGridWidthTest {
    private val grid = HomeDashboardPresentation(
        notices = emptyList(),
        hero = HomeHeroPresentation(SemanticTone.NEUTRAL, "", HomeHeroTextBlock("", emptyList()), HomeHeroTextBlock("", emptyList())),
        metrics = emptyList(), actions = emptyList(), facts = emptyList(),
    ).topGrid

    @Test
    fun companionColumnsFitWhenContentIs328Dp() {
        val environment = HomeTopGridEnvironment(328f, 1f)
        val result = homeTopGridLayout(grid, environment)
        assertEquals(HomeTopGridLayout.SPLIT, result)
    }

    @Test
    fun companionColumnsFitWhenContentIs344Dp() {
        val environment = HomeTopGridEnvironment(344f, 1f)
        val result = homeTopGridLayout(grid, environment)
        assertEquals(HomeTopGridLayout.SPLIT, result)
    }

    @Test
    fun companionColumnsFitWhenContentIs360Dp() {
        val environment = HomeTopGridEnvironment(360f, 1f)
        val result = homeTopGridLayout(grid, environment)
        assertEquals(HomeTopGridLayout.SPLIT, result)
    }

    @Test
    fun companionColumnsFitWhenFlip2DensityAndRootInsetsAreApplied() {
        val environment = HomeTopGridEnvironment(1224f / (520f / 160f) - 24f, 1f)
        val result = homeTopGridLayout(grid, environment)
        assertEquals(HomeTopGridLayout.SPLIT, result)
    }

    @Test
    fun companionColumnsAreUnchangedWhenXiaomi13DensityAndRootInsetsAreApplied() {
        val environment = HomeTopGridEnvironment(1080f / 2.75f - 24f, 1f)
        val result = homeTopGridLayout(grid, environment)
        assertEquals(homeTopGridLayout(HomeTopGridSpec(), environment), result)
        assertEquals(1f, grid.heroColumnWeight)
        assertEquals(1f, grid.metricColumnWeight)
    }

    @Test
    fun gridStacksWhenContentCannotFitBothColumns() {
        val environment = HomeTopGridEnvironment(327f, 1f)
        val result = homeTopGridLayout(grid, environment)
        assertEquals(HomeTopGridLayout.STACKED, result)
    }

    @Test
    fun gridStacksWhenFontReachesAccessibilityThreshold() {
        val environments = listOf(HomeTopGridEnvironment(352.6154f, 1.5f), HomeTopGridEnvironment(960f, 2f))
        val results = environments.map { homeTopGridLayout(grid, it) }
        assertEquals(listOf(HomeTopGridLayout.STACKED, HomeTopGridLayout.STACKED), results)
    }
}
