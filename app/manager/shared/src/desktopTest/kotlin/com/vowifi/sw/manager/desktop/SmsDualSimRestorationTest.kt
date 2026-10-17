package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.navigation.AppRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmsDualSimRestorationTest {
    @Test fun restoredCollisionIsVisibleAtRootAndRecoversWithoutSendingOrLosingOwnerDrafts() = smsOnEdt {
        val source = unavailableSource()
        SmsDualSimDriver(source, initial = null, restored = collisionSnapshot()).use { page ->
            page.openPicker()
            page.click("Synthetic A")
            page.trace("restored-collision-root-after-A")
            assertTrue(source.selections.isEmpty(), "collision must not select or bind A")
            assertTrue(page.hasText(collisionNotice), "root must explain the rejected draft binding")
            // C2拒绝会保留原选择器；先由真实取消入口退出，不能穿过面板操作下层恢复入口。
            assertTrue(page.hasText("选择短信记录线路"))
            page.click("取消")
            page.settlePickerExit()
            page.capture("collision-root")
            page.click("处理未绑定草稿")
            page.trace("restored-collision-new-message")
            assertUnbound(page)
            page.openPicker()
            page.click("Synthetic B")
            page.trace("restored-collision-after-B")
            assertTrue(source.selections.isEmpty(), "B also has a draft and must not be overwritten")
            assertTrue(page.hasText("选择短信记录线路"), "C2拒绝B后同样保留选择器")
            page.click("取消")
            page.settlePickerExit()
            assertUnbound(page)
            page.capture("collision-recovery")
            clearWithCancelThenConfirm(page)
            assertEquals("", page.field(0))
            assertEquals("", page.field(1))
            assertFalse(page.hasText(collisionNotice))
            assertFalse(page.hasText("清空未绑定草稿"))
            assertTrue(source.selections.isEmpty())
            assertTrue(source.sends.isEmpty())
            source.publish(smsReady(smsOwnerA))
            page.frame()
            assertEquals("", page.field(1), "clearing must not adopt A or expose its saved draft automatically")
            assertFalse(page.canSend())
            assertTrue(source.selections.isEmpty())
            source.publish(MessageUiState.Unavailable("synthetic unavailable"))
            page.frame()
            page.route(AppRoute.Messages)
            assertFalse(page.hasText(collisionNotice))
            assertFalse(page.hasText("处理未绑定草稿"))
            page.selectAtRoot(smsOwnerA)
            assertEquals(smsPeerA, page.field(0))
            assertEquals("synthetic saved A", page.field(1))
            assertFalse(page.canSend())
            page.selectAtRoot(smsOwnerB)
            assertEquals(smsPeerB, page.field(0))
            assertEquals("synthetic saved B", page.field(1))
            assertFalse(page.canSend())
            source.publish(smsReady(smsOwnerB, authorized = false))
            page.frame()
            assertFalse(page.canSend())
            source.publish(smsReady(smsOwnerB))
            page.frame()
            assertTrue(page.canSend())
            assertTrue(source.sends.isEmpty())
            assertEquals(listOf(smsOwnerA, smsOwnerB), source.selections)
        }
    }

    @Test fun restoredUnboundDraftCanBeClearedWhenNotReadyWithoutFirstTriggeringCollision() = smsOnEdt {
        val source = unavailableSource()
        SmsDualSimDriver(source, initial = null, restored = collisionSnapshot()).use { page ->
            page.click("新建短信")
            assertUnbound(page)
            clearWithCancelThenConfirm(page)
            assertEquals("", page.field(0))
            assertEquals("", page.field(1))
            assertTrue(source.selections.isEmpty())
            assertTrue(source.sends.isEmpty())
            page.selectAtRoot(smsOwnerA)
            assertEquals("synthetic saved A", page.field(1))
            assertFalse(page.hasText("清空未绑定草稿"), "local recovery action must not clear bound drafts")
            page.selectAtRoot(smsOwnerB)
            assertEquals("synthetic saved B", page.field(1))
        }
    }

    @Test fun completeBindingSaveRecreateKeepsAWhenBackendInitiallyPresentsB() = smsOnEdt {
        val saved = SmsDualSimDriver().use { page ->
            page.edit(0, smsPeerA)
            page.edit(1, "synthetic persisted A")
            page.selectAtRoot(smsOwnerB)
            page.edit(0, smsPeerB)
            page.edit(1, "synthetic persisted B")
            page.selectAtRoot(smsOwnerA)
            page.save()
        }
        val source = SmsDualSimSource(smsReady(smsOwnerB), smsChoices(selected = smsOwnerB))
        SmsDualSimDriver(source, restored = saved).use { page ->
            assertEquals(smsPeerA, page.field(0))
            assertEquals("synthetic persisted A", page.field(1))
            assertTrue(page.hasText("发送线路：Synthetic A"))
            assertFalse(page.canSend())
            assertTrue(source.selections.isEmpty())
            assertTrue(source.sends.isEmpty())
            source.publish(smsReady(smsOwnerA, authorized = false))
            page.frame()
            assertFalse(page.canSend())
            source.publish(smsReady(smsOwnerA))
            page.frame()
            assertTrue(page.canSend())
            page.selectAtRoot(smsOwnerB)
            assertEquals(smsPeerB, page.field(0))
            assertEquals("synthetic persisted B", page.field(1))
            assertTrue(source.sends.isEmpty())
        }
    }

    private fun clearWithCancelThenConfirm(page: SmsDualSimDriver) {
        page.click("清空未绑定草稿")
        assertTrue(page.hasText("清空未绑定草稿？"))
        page.click("取消")
        assertUnbound(page)
        page.click("清空未绑定草稿")
        page.click("确认清空")
    }

    private fun assertUnbound(page: SmsDualSimDriver) {
        assertEquals(smsPeerA, page.field(0))
        assertEquals(unboundBody, page.field(1))
        assertFalse(page.editable(0))
        assertFalse(page.editable(1))
        assertFalse(page.canSend())
    }

    private fun unavailableSource() = SmsDualSimSource(
        MessageUiState.Unavailable("synthetic unavailable"), smsChoices(),
    ).apply { publishSelections = false }

    private fun collisionSnapshot(): Map<String, List<Any?>> {
        val source = SmsDualSimSource(smsReady(authorized = false).copy(ownerId = null), smsChoices())
        val saved = SmsDualSimDriver(source).use { page ->
            page.edit(0, smsPeerA)
            page.edit(1, unboundBody)
            page.route(AppRoute.Messages)
            page.save()
        }
        val unbound = mapOf("" to listOf(smsPeerA, unboundBody))
        val entry = saved.entries.single { (_, values) ->
            values.any { (it as? MutableState<*>)?.value == unbound }
        }
        val restoredEntries = unbound + mapOf(
            smsOwnerA.wire() to listOf(smsPeerA, "synthetic saved A"),
            smsOwnerB.wire() to listOf(smsPeerB, "synthetic saved B"),
        )
        return saved + (entry.key to entry.value.map {
            if ((it as? MutableState<*>)?.value == unbound) mutableStateOf(restoredEntries) else it
        })
    }

    companion object {
        private const val unboundBody = "synthetic restored unbound"
        private const val collisionNotice = "该线路已有草稿。请先清空当前未选线路的草稿，再切换；两份草稿均已保留。"
    }
}
