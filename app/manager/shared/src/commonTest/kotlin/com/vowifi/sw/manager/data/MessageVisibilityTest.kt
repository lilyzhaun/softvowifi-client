package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageVisibilityTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val other = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
    private val handle = messageConversationHandle(owner, "15550000001")
    private val state = MessageUiState.Ready(
        listOf(
            MessageRecord.Incoming("seen", "+15550000001", "synthetic", 1),
            MessageRecord.Incoming("unseen", "+15550000001", "synthetic", 2),
        ), MessageOrigin.ENGINE, ownerId = owner,
    )

    @Test fun resumedSelectedThreadMarksOnlyObservedIds() {
        val reads = mutableListOf<ObservedIncomingReadRequest>()
        val visibility = MessageVisibility({}, reads::add)
        visibility.setResumed(true)
        visibility.observe(state, handle, listOf("seen", "unknown"))
        assertEquals(listOf("seen"), reads.single().observedMessageIds)
        assertEquals(owner, reads.single().ownerId)
    }

    @Test fun pausedActivityImmediatelyClearsSuppressionAndRejectsLateLayout() {
        val reads = mutableListOf<ObservedIncomingReadRequest>()
        var selected: com.vowifi.sw.manager.ui.navigation.ConversationHandle? = null
        val visibility = MessageVisibility({ selected = it }, reads::add)
        visibility.setResumed(true)
        visibility.observe(state, handle, emptyList())
        visibility.setResumed(false)
        visibility.observe(state, handle, listOf("seen"))
        assertNull(selected)
        assertEquals(0, reads.size)
    }

    @Test fun hiddenPagerAndWrongOwnerHandleNeverMarkRead() {
        val reads = mutableListOf<ObservedIncomingReadRequest>()
        val visibility = MessageVisibility({}, reads::add)
        visibility.setResumed(true)
        visibility.observe(state, null, listOf("seen"))
        visibility.observe(state, messageConversationHandle(other, "15550000001"), listOf("seen"))
        assertEquals(0, reads.size)
    }
}
