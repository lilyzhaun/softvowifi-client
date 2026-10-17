package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.OutgoingMessageStatus
import com.vowifi.sw.manager.ui.navigation.AppRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmsDualSimNavigationTest {
    @Test fun unavailableNewMessageCanPickEitherEmptyHistoryLine() = smsOnEdt {
        val source = SmsDualSimSource(MessageUiState.Unavailable("请选择发送短信的 SIM。"), smsChoices())
        SmsDualSimDriver(source, initial = null).use { page ->
            page.click("新建短信")
            assertEquals(2, page.fields().size)
            assertTrue(source.selections.isEmpty())
            assertFalse(page.canSend())
            page.openPicker()
            assertTrue(page.hasText("Synthetic A"))
            assertTrue(page.hasText("Synthetic B"))
            page.click("Synthetic B")
            page.trace("unavailable-after-pick-B")
            assertEquals(listOf(smsOwnerB), source.selections)
            assertEquals(smsOwnerB, source.historyChoices.value.selectedOwner, "C2必须读取原source已发布的选择")
            page.settlePickerExit()
            page.edit(0, smsPeerB)
            page.trace("unavailable-after-recipient")
            page.edit(1, "synthetic B")
            page.send()
            assertEquals(smsOwnerB, source.sends.single().owner)
        }
    }

    @Test fun backendAuthorizedSingletonSendsWithoutSyntheticSelection() = smsOnEdt {
        val source = SmsDualSimSource(smsReady(), smsChoices(listOf(smsOwnerA)))
        SmsDualSimDriver(source).use { page ->
            page.edit(0, smsPeerA)
            page.edit(1, "synthetic A")
            page.send()
            assertEquals(smsOwnerA, source.sends.single().owner)
            assertTrue(source.selections.isEmpty())
        }
    }

    @Test fun partialRosterDoesNotAutoSelectItsOnlyVisibleActiveLine() = assertNoAutomaticSelection(true, false)
    @Test fun unknownRosterDoesNotAutoSelectItsOnlyVisibleLine() = assertNoAutomaticSelection(null, false)
    @Test fun completeVisibleSingletonStillCannotOverrideBackendRefusal() = assertNoAutomaticSelection(true, true)

    private fun assertNoAutomaticSelection(active: Boolean?, complete: Boolean) = smsOnEdt {
        val source = SmsDualSimSource(smsReady(authorized = false),
            smsChoices(listOf(smsOwnerA), active = active, complete = complete, selected = smsOwnerA))
        SmsDualSimDriver(source).use { page ->
            page.edit(0, smsPeerA)
            page.edit(1, "synthetic unbound")
            assertFalse(page.canSend())
            assertTrue(source.selections.isEmpty())
            assertTrue(source.sends.isEmpty())
        }
    }

    @Test fun explicitRootSelectionRoundTripPreservesEachOwnersRecipientAndBody() = smsOnEdt {
        SmsDualSimDriver().use { page ->
            page.edit(0, smsPeerA)
            page.edit(1, "synthetic A")
            page.selectAtRoot(smsOwnerB)
            assertEquals("", page.field(0))
            assertEquals("", page.field(1))
            page.edit(0, smsPeerB)
            page.edit(1, "synthetic B")
            page.selectAtRoot(smsOwnerA)
            assertEquals(smsPeerA, page.field(0))
            assertEquals("synthetic A", page.field(1))
            page.selectAtRoot(smsOwnerB)
            assertEquals(smsPeerB, page.field(0))
            assertEquals("synthetic B", page.field(1))
            assertEquals(listOf(smsOwnerB, smsOwnerA, smsOwnerB), page.source.selections)
        }
    }

    @Test fun loadingDoesNotReplaceTheBoundDraftWithNullOwnerDraft() =
        assertTemporaryUnavailable(MessageUiState.Loading)

    @Test fun unavailableDoesNotReplaceTheBoundDraftWithNullOwnerDraft() =
        assertTemporaryUnavailable(MessageUiState.Unavailable("synthetic temporary disconnect"))

    private fun assertTemporaryUnavailable(temporary: MessageUiState) = smsOnEdt {
        SmsDualSimDriver().use { page ->
            page.edit(0, smsPeerA)
            page.edit(1, "synthetic bound A")
            page.source.publish(temporary)
            page.frame()
            assertFalse(page.canSend())
            assertEquals(smsPeerA, page.field(0), "temporary availability must not change the draft owner")
            assertEquals("synthetic bound A", page.field(1))
            page.source.publish(smsReady())
            page.frame()
            assertEquals(smsPeerA, page.field(0))
            assertEquals("synthetic bound A", page.field(1))
            page.selectAtRoot(smsOwnerB)
            assertEquals("", page.field(0))
            assertEquals("", page.field(1))
        }
    }

    @Test fun originalConversationRepliesOnAEvenWhenRootSelectionIsB() = smsOnEdt {
        val source = SmsDualSimSource(smsReady(smsOwnerB), smsChoices(selected = smsOwnerB))
        SmsDualSimDriver(source, AppRoute.MessageThread(smsThreadA.handle)).use { page ->
            assertEquals(smsThreadA.handle.value, source.conversation)
            assertEquals(smsOwnerB, source.historyChoices.value.selectedOwner)
            page.edit(0, "synthetic reply A")
            page.send()
            assertEquals(smsOwnerA, source.sends.single().owner)
            assertEquals(smsPeerA, source.sends.single().recipient)
            assertTrue(source.selections.isEmpty())
        }
    }

    @Test fun delayedAcceptanceForADoesNotClearOrDescribeBDraft() = smsOnEdt {
        SmsDualSimDriver().use { page ->
            page.edit(0, smsPeerA)
            page.edit(1, "synthetic submitted A")
            page.send()
            page.selectAtRoot(smsOwnerB)
            page.edit(0, smsPeerB)
            page.edit(1, "synthetic unsent B")
            page.source.complete(smsOwnerA, OutgoingMessageStatus.ACCEPTED)
            page.frame()
            assertEquals(smsPeerB, page.field(0))
            assertEquals("synthetic unsent B", page.field(1))
            assertFalse(page.hasText("已受理，不代表接收设备已收到。"))
            assertEquals(1, page.source.sends.size)
            assertEquals(smsOwnerA, page.source.sends.single().owner)
        }
    }

    @Test fun delayedAcceptanceCannotClearOrDescribeNewlyEditedADraft() = smsOnEdt {
        SmsDualSimDriver().use { page ->
            page.edit(0, smsPeerA)
            page.edit(1, "synthetic submitted A")
            page.send()
            val pending = page.source.state.value as MessageUiState.Ready
            page.source.publish(pending.copy(sendBusy = false))
            page.frame()
            page.edit(1, "synthetic revised A")
            page.source.complete(smsOwnerA, OutgoingMessageStatus.ACCEPTED)
            page.frame()
            assertEquals(smsPeerA, page.field(0))
            assertEquals("synthetic revised A", page.field(1))
            assertFalse(page.hasText("已受理，不代表接收设备已收到。"))
            assertEquals(1, page.source.sends.size)
        }
    }

    @Test fun failedRetainsBoundRecipientAndBodyWithoutRetry() =
        assertRetained(OutgoingMessageStatus.FAILED, "发送未完成，草稿已保留。")

    @Test fun unknownRetainsBoundRecipientAndBodyWithoutRetry() =
        assertRetained(OutgoingMessageStatus.UNKNOWN, "发送结果未知，请先核对；不会自动重发。")

    private fun assertRetained(status: OutgoingMessageStatus, notice: String) = smsOnEdt {
        SmsDualSimDriver().use { page ->
            page.edit(0, smsPeerA)
            page.edit(1, "synthetic retained A")
            page.send()
            page.source.complete(smsOwnerA, status)
            page.frame()
            assertEquals(smsPeerA, page.field(0))
            assertEquals("synthetic retained A", page.field(1))
            assertTrue(page.hasText(notice))
            assertEquals(1, page.source.sends.size)
        }
    }
}
