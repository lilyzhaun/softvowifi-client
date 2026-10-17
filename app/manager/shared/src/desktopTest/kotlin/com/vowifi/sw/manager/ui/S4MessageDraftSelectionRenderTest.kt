@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/** 只测既有绑定契约；源的 choices/state 全来自真实 EngineMessageSource，不手填业务状态。 */
class S4MessageDraftSelectionRenderTest {
    @Test fun realSourceRejectionPreservesBoundAndUnboundOwnersAndBothExistingDrafts() = onMotionTestThread {
        runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
            val stored = FakeMessageRepository()
            stored.insertIncomingIfAbsent(OWNER_A, "draft-a", "draft-a", "155501", "synthetic", 1, 1)
            stored.insertIncomingIfAbsent(OWNER_B, "draft-b", "draft-b", "155502", "synthetic", 2, 2)
            val rejected = assertNotNull(SimOwnerId.parse("sim-owner-v1:" + "c".repeat(43)))
            val source = EngineMessageSource(
                EngineMessageRuntime({ emptyList() }, { error("本测试不允许连接引擎") }, { 3 }, { emptyList() }),
                backgroundScope.coroutineContext, stored, { null }, { "unused" })
            val requests = mutableListOf<SimOwnerId>()
            var rejectedConfirmation: Boolean? = null
            var session by mutableIntStateOf(0)
            lateinit var bound: BoundMessageDraft
            try {
                MotionTestEnvironment(testScheduler, 390, 300) {
                    SoftVoWiFiTheme(false) {
                        key(session) {
                            val draft = rememberBoundMessageDraft(source.state.collectAsState().value, false) { owner ->
                                requests += owner
                                source.selectHistoryOwner(owner)
                                source.historyChoices.value.selectedOwner == owner
                            }
                            SideEffect { bound = draft }
                            Column {
                                CommunicationTextAction("选择 A", { draft.confirmOwner(OWNER_A); Unit })
                                CommunicationTextAction("选择 B", { draft.selectOwner(OWNER_B) })
                                CommunicationTextAction("选择失效线路", {
                                    if (session == 0) draft.selectOwner(rejected)
                                    else rejectedConfirmation = draft.confirmOwner(rejected)
                                })
                            }
                        }
                    }
                }.use { e ->
                    s4Frames(e, 12)
                    assertEquals(setOf(OWNER_A, OWNER_B), source.historyChoices.value.lines.map { it.owner }.toSet())
                    assertFalse(source.historyChoices.value.lines.any { it.owner == rejected }, "真实拒绝前提：目标不在源候选中")
                    s4Tap(e, s4Action(e, "选择 A").boundsInRoot.center); s4Frames(e, 2)
                    assertEquals(OWNER_A, source.historyChoices.value.selectedOwner); assertEquals(OWNER_A, bound.owner)
                    bound.draft.updateRecipient("155501"); bound.draft.updateBody("synthetic draft A"); s4Frames(e, 2)
                    val a = bound.draft.recipient to bound.draft.body
                    s4Tap(e, s4Action(e, "选择 B").boundsInRoot.center); s4Frames(e, 2)
                    assertEquals(OWNER_B, source.historyChoices.value.selectedOwner); assertEquals(OWNER_B, bound.owner)
                    assertEquals(listOf(OWNER_A, OWNER_B), requests, "Unit/Boolean 两入口共用 choose，每次只选源一次")
                    bound.draft.updateRecipient("155502"); bound.draft.updateBody("synthetic draft B"); s4Frames(e, 2)
                    val b = bound.draft.recipient to bound.draft.body
                    val before = source.state.value
                    s4Tap(e, s4Action(e, "选择失效线路").boundsInRoot.center); s4Frames(e, 2)
                    assertEquals(rejected, requests.last(), "必须真实进入旧绑定回调及 MessageSource 拒绝路径")
                    assertSame(before, source.state.value); assertEquals(OWNER_B, source.historyChoices.value.selectedOwner)
                    val boundOwnerAfterRejection = bound.owner
                    val shownAfterRejection = bound.draft.recipient to bound.draft.body
                    // 仍经真实绑定/API 重新浏览 A/B，证明两份保存输入均未丢失；不改私有草稿 map。
                    s4Tap(e, s4Action(e, "选择 A").boundsInRoot.center); s4Frames(e, 2)
                    val restoredA = bound.draft.recipient to bound.draft.body
                    s4Tap(e, s4Action(e, "选择 B").boundsInRoot.center); s4Frames(e, 2)
                    val restoredB = bound.draft.recipient to bound.draft.body

                    // 独立新 UI 会话：仅重建真实 remember 状态，源仍发布 B；不手设 ownerWire。
                    session++; s4Frames(e, 2)
                    assertNull(bound.owner)
                    bound.draft.updateRecipient("155503"); bound.draft.updateBody("synthetic unbound"); s4Frames(e, 2)
                    val unbound = bound.draft.recipient to bound.draft.body
                    val beforeUnbound = source.state.value
                    s4Tap(e, s4Action(e, "选择失效线路").boundsInRoot.center); s4Frames(e, 2)
                    assertEquals(rejected, requests.last()); assertSame(beforeUnbound, source.state.value)
                    assertEquals(false, rejectedConfirmation, "Boolean 确认必须返回真实源拒绝，不由 Unit 推成功")
                    assertEquals(OWNER_B, source.historyChoices.value.selectedOwner)
                    val unboundOwnerAfterRejection = bound.owner
                    val unboundAfterRejection = bound.draft.recipient to bound.draft.body

                    assertEquals(a, restoredA, "拒绝不能改 A 草稿")
                    assertEquals(b, restoredB, "拒绝不能改 B 草稿")
                    assertEquals(listOf<SimOwnerId?>(OWNER_B, null),
                        listOf(boundOwnerAfterRejection, unboundOwnerAfterRejection), "源拒绝不能提前改绑定或搬未绑定草稿")
                    assertEquals(b, shownAfterRejection, "拒绝后仍显示原 B 草稿")
                    assertEquals(unbound, unboundAfterRejection, "拒绝后未绑定输入保持原状")
                }
            } finally { source.close(); stored.close() }
        }
    }
}
