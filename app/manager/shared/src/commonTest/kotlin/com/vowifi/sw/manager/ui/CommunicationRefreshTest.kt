package com.vowifi.sw.manager.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNull
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import androidx.compose.ui.graphics.luminance

class CommunicationRefreshTest {
    @Test fun submittingDoesNotDiscardDraftBeforeSourceAcceptance() {
        assertFalse(messageDraftPolicy().clearAfterSubmission)
    }

    @Test fun inboxPreviewCanUseTwoLinesWithoutTruncatingIdentity() {
        assertEquals(2, messageListLayoutContract().previewMaxLines)
    }

    @Test fun readingOlderMessagesDoesNotTeleportButNearBottomFollows() {
        assertFalse(shouldFollowNewMessages(false, 20, 4))
        assertTrue(shouldFollowNewMessages(false, 20, 19))
        assertTrue(shouldFollowNewMessages(true, 20, 4))
    }

    @Test fun failureUnknownAndNewerDraftArePreserved() {
        assertFalse(shouldClearSubmittedDraft(OutgoingMessageStatus.FAILED, "草稿", "草稿"))
        assertFalse(shouldClearSubmittedDraft(OutgoingMessageStatus.UNKNOWN, "草稿", "草稿"))
        assertFalse(shouldClearSubmittedDraft(OutgoingMessageStatus.QUEUED, "草稿", "草稿"))
        assertFalse(shouldClearSubmittedDraft(OutgoingMessageStatus.ACCEPTED, "草稿", "新草稿"))
        assertTrue(shouldClearSubmittedDraft(OutgoingMessageStatus.ACCEPTED, "草稿", "草稿"))
    }

    @Test fun oldAcceptedRecordCannotClearCurrentSubmission() {
        val owner = messageProjectionOwner()
        val snapshot = listOf(owner.wire(), "+15550100", "草稿", "old")
        val old = MessageRecord.Outgoing("old", "+15550100", "草稿", 1, OutgoingMessageStatus.ACCEPTED)
        val state = MessageUiState.Ready(listOf(old), MessageOrigin.ENGINE, ownerId = owner)
        assertNull(submittedMessageOutcome(snapshot, state))
        assertNull(submittedMessageOutcome(snapshot, state.copy(records = listOf(old, old.copy(id = "new")))))
        val attempt = com.vowifi.sw.manager.model.MessageSendAttempt(owner, "new", "+15550100", "草稿", OutgoingMessageStatus.UNKNOWN)
        assertEquals(OutgoingMessageStatus.UNKNOWN, submittedMessageOutcome(snapshot, state.copy(sendAttempt = attempt)))
        assertEquals(OutgoingMessageStatus.ACCEPTED, submittedMessageOutcome(snapshot, state.copy(sendAttempt = attempt.copy(status = OutgoingMessageStatus.ACCEPTED))))
        assertNull(submittedMessageOutcome(snapshot, state.copy(ownerId = null, sendAttempt = attempt)))
        assertNull(submittedMessageOutcome(snapshot, state.copy(sendAttempt = attempt.copy(messageId = "old"))))
    }

    @Test fun visibleBadgeIsCappedWithoutChangingActualCount() {
        assertEquals("99", unreadCountLabel(99))
        assertEquals("99+", unreadCountLabel(123))
    }

    @Test fun subduedAccentRetainsReadablePairedForeground() {
        listOf(false, true).forEach { dark ->
            val colors = communicationThemeColors(dark)
            val channels = listOf(colors.interactive.red, colors.interactive.green, colors.interactive.blue)
            assertTrue(channels.max() - channels.min() < 0.25f)
            val luminances = listOf(colors.interactive.luminance(), colors.onCommunicationAccent.luminance())
            assertTrue((luminances.max() + 0.05f) / (luminances.min() + 0.05f) >= 4.5f)
        }
    }

    @Test fun communicationToolbarShieldIsRetainedAndSettingsMatchesCollapsedHome() {
        assertEquals(0.92f, appSubpageSceneContract(com.vowifi.sw.manager.ui.navigation.AppRoute.NewMessage).topBarBlendAlpha)
        // R7 supplement (2026-10-15): settings now share Home's collapsed material.
        assertEquals(topBarBlendAlpha(1f), appSubpageSceneContract(com.vowifi.sw.manager.ui.navigation.AppRoute.SettingsAppearance).topBarBlendAlpha)
    }
}
