package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.navigation.AppRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmsDualSimBindingTest {
    @Test fun emptyAutomaticDraftFollowsRegisteredDefaultWithoutCreatingSelectionEvent() = smsOnEdt {
        SmsDualSimDriver().use { page ->
            page.source.historyChoices.value = smsChoices(selected = smsOwnerB)
            page.source.publish(smsReady(smsOwnerB))
            page.frame()
            assertTrue(page.hasText("发送线路：Synthetic B"))
            assertTrue(page.source.selections.isEmpty())
            page.edit(0, smsPeerB)
            page.edit(1, "synthetic default B")
            page.send()
            assertEquals(smsOwnerB, page.source.sends.single().owner)
        }
    }

    @Test fun unsolicitedSingletonBDoesNotReplaceOrSendExistingADraft() = smsOnEdt {
        SmsDualSimDriver().use { page ->
            page.edit(0, smsPeerA)
            page.edit(1, "synthetic bound A")
            page.source.historyChoices.value = smsChoices(listOf(smsOwnerB), selected = smsOwnerB)
            page.source.publish(smsReady(smsOwnerB))
            page.frame()
            assertEquals(smsPeerA, page.field(0))
            assertEquals("synthetic bound A", page.field(1))
            assertFalse(page.canSend())
            assertTrue(page.source.selections.isEmpty())
            assertTrue(page.source.sends.isEmpty())
            page.source.publish(smsReady(smsOwnerA))
            page.frame()
            page.send()
            assertEquals(smsOwnerA, page.source.sends.single().owner)
        }
    }

    @Test fun nonemptyUnboundDraftRequiresExplicitBindingAndMovesOnlyOnce() = smsOnEdt {
        val source = SmsDualSimSource(smsReady(authorized = false).copy(ownerId = null), smsChoices())
        SmsDualSimDriver(source).use { page ->
            page.edit(0, smsPeerB)
            page.edit(1, "synthetic unbound")
            source.publish(smsReady(smsOwnerB))
            page.frame()
            assertEquals(smsPeerB, page.field(0))
            assertEquals("synthetic unbound", page.field(1))
            assertFalse(page.canSend())
            assertTrue(source.selections.isEmpty())
            page.selectAtRoot(smsOwnerB)
            assertEquals(smsPeerB, page.field(0))
            assertEquals("synthetic unbound", page.field(1))
            page.selectAtRoot(smsOwnerA)
            assertEquals("", page.field(0))
            assertEquals("", page.field(1))
            page.selectAtRoot(smsOwnerB)
            assertEquals("synthetic unbound", page.field(1))
            page.send()
            assertEquals(smsOwnerB, source.sends.single().owner)
        }
    }

    @Test fun historicalDisplayOwnerCannotAdoptUnboundTextWithoutExplicitChoice() = smsOnEdt {
        val source = SmsDualSimSource(smsReady(authorized = false), smsChoices(selected = smsOwnerA))
        SmsDualSimDriver(source).use { page ->
            page.edit(0, smsPeerA)
            page.edit(1, "synthetic unbound history")
            source.publish(smsReady())
            page.frame()
            assertEquals("synthetic unbound history", page.field(1))
            assertFalse(page.canSend())
            assertTrue(source.selections.isEmpty())
            page.selectAtRoot(smsOwnerA)
            assertEquals("synthetic unbound history", page.field(1))
            page.send()
            assertEquals(smsOwnerA, source.sends.single().owner)
        }
    }

    @Test fun viewingAThreadAndReturningDoesNotRebindExistingBDraft() = smsOnEdt {
        val source = SmsDualSimSource(smsReady(smsOwnerB), smsChoices(selected = smsOwnerB))
        SmsDualSimDriver(source).use { page ->
            page.edit(0, smsPeerB)
            page.edit(1, "synthetic B new")
            page.route(AppRoute.MessageThread(smsThreadA.handle))
            page.edit(0, "synthetic A reply")
            page.route(AppRoute.NewMessage)
            assertEquals(smsPeerB, page.field(0))
            assertEquals("synthetic B new", page.field(1))
            page.send()
            assertEquals(smsOwnerB, source.sends.single().owner)
            page.route(AppRoute.MessageThread(smsThreadA.handle))
            assertEquals("synthetic A reply", page.field(0))
        }
    }

    @Test fun coldThreadDoesNotSeedNewMessageOwnerFromItsReplyAuthorization() = smsOnEdt {
        val source = SmsDualSimSource(smsReady(smsOwnerB), smsChoices(selected = smsOwnerB))
        SmsDualSimDriver(source, AppRoute.MessageThread(smsThreadA.handle)).use { page ->
            page.route(AppRoute.NewMessage)
            page.edit(0, smsPeerB)
            page.edit(1, "synthetic new B")
            page.send()
            assertEquals(smsOwnerB, source.sends.single().owner)
            assertTrue(source.selections.isEmpty())
        }
    }

    @Test fun explicitSelectionWaitsForMatchingBackendAuthorization() = smsOnEdt {
        SmsDualSimDriver().use { page ->
            page.source.publishSelections = false
            page.selectAtRoot(smsOwnerB)
            assertEquals(listOf(smsOwnerB), page.source.selections)
            page.edit(0, smsPeerB)
            page.edit(1, "synthetic waiting B")
            assertFalse(page.canSend(), "old authorized A cannot authorize the B draft")
            page.source.publish(smsReady(smsOwnerB, authorized = false))
            page.frame()
            assertEquals("synthetic waiting B", page.field(1))
            assertFalse(page.canSend())
            page.source.publish(smsReady(smsOwnerB))
            page.frame()
            page.send()
            assertEquals(smsOwnerB, page.source.sends.single().owner)
        }
    }
}
