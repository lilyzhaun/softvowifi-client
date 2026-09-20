package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AppDestinationTest {
    @Test
    fun destinationsAreExactlyTheFourProductAreas() {
        assertEquals(
            listOf(
                AppDestination.HOME,
                AppDestination.CALLS,
                AppDestination.MESSAGES,
                AppDestination.SETTINGS,
            ),
            AppDestination.entries,
        )
        assertEquals(listOf("首页", "通话", "短信", "设置"), AppDestination.entries.map { it.label })
        assertEquals(
            listOf(
                AppDestinationContent.HOME,
                AppDestinationContent.CALLS,
                AppDestinationContent.MESSAGES,
                AppDestinationContent.SETTINGS,
            ),
            AppDestination.entries.map { it.content },
        )
    }

    @Test
    fun platformAliasesMapToTheFourDestinations() {
        listOf("status", "home").forEach { alias ->
            assertEquals(AppDestination.HOME, AppDestination.fromAlias(alias))
        }
        listOf("configuration", "diagnostics", "settings").forEach { alias ->
            assertEquals(AppDestination.SETTINGS, AppDestination.fromAlias(alias))
        }
        assertEquals(AppDestination.CALLS, AppDestination.fromAlias("calls"))
        assertEquals(AppDestination.MESSAGES, AppDestination.fromAlias("messages"))
        assertNull(AppDestination.fromAlias("unknown"))
    }
}
