package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.FixtureMessageSource
import com.vowifi.sw.manager.data.MessageFixtureMode
import com.vowifi.sw.manager.data.fixtureUnreadSummary
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.MessageUnreadSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MessageUnreadBadgeTest {
    @Test
    fun knownZeroHidesButLoadingAndUnknownRemainDistinct() {
        assertNull(messageUnreadBadge(MessageUnreadSummary.Ready(0)))
        assertEquals("...", assertNotNull(messageUnreadBadge(MessageUnreadSummary.Loading)).label)
        assertEquals("?", assertNotNull(messageUnreadBadge(MessageUnreadSummary.Unavailable(null))).label)
        assertEquals("?", assertNotNull(messageUnreadBadge(MessageUnreadSummary.Unavailable(0))).label)
    }

    @Test
    fun allOwnerTotalCapsOnlyVisualLabelAndRetainsActualCount() {
        assertEquals("5", assertNotNull(messageUnreadBadge(MessageUnreadSummary.Ready(5))).label)
        val badge = assertNotNull(messageUnreadBadge(MessageUnreadSummary.Ready(3_000_000_000)))
        assertEquals("99+", badge.label)
        assertEquals(3_000_000_000L, badge.total)
        assertEquals(true, badge.current)
    }

    @Test
    fun staleCountIsRetainedAndVisiblyUncertain() {
        val badge = assertNotNull(messageUnreadBadge(MessageUnreadSummary.Unavailable(5)))
        assertEquals("5?", badge.label)
        assertEquals(5L, badge.total)
        assertEquals(false, badge.current)
    }

    @Test
    fun fixtureSummaryCountsDistinctSyntheticUnreadIdsNotHardcodedBadgeNumbers() {
        val incoming = MessageRecord.Incoming("synth-1", "+155501", "synthetic", 1)
        val state = MessageUiState.Ready(
            listOf(incoming, incoming, incoming.copy(id = "synth-2"), incoming.copy(id = "synth-read", isRead = true)),
            MessageOrigin.DESKTOP_FIXTURE,
        )
        assertEquals(MessageUnreadSummary.Ready(2), fixtureUnreadSummary(state))
        assertEquals(MessageUnreadSummary.Loading, FixtureMessageSource(MessageFixtureMode.LOADING).unreadSummary.value)
        assertEquals(MessageUnreadSummary.Ready(0), FixtureMessageSource(MessageFixtureMode.EMPTY).unreadSummary.value)
        val source = FixtureMessageSource(MessageFixtureMode.LOADING)
        source.refresh()
        assertEquals(fixtureUnreadSummary(source.state.value), source.unreadSummary.value)
    }
}
