package com.vowifi.sw.manager.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.data.StoredMessageConversation
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** R9 exercises the real thread's search editor and list, including a stable-key result set. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ManagerScrollFeedbackRenderTest {
    @Test fun upwardThreadDragDoesNotDismissKeyboard() = noImeDismissal(atBottom = true, distance = -80f)
    @Test fun downwardThreadDragAwayFromBottomDoesNotDismissKeyboard() = noImeDismissal(atBottom = false, distance = 80f)

    private fun noImeDismissal(atBottom: Boolean, distance: Float) = onMotionTestThread { runTest {
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val conversation = StoredMessageConversation(owner, "12025550123")
        val records = List(40) { MessageRecord.Incoming("drag-$it", "+12025550123", "合成消息 $it", 1790848800000L + it * 1000L, isRead = true) }
        var hides = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() = Unit
            override fun hide() { hides++ }
        }
        MotionTestEnvironment(testScheduler, width = 390, height = 740) {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                SoftVoWiFiTheme(false) {
                    MessageThreadScreen(conversation.handle, MessageUiState.Ready(records, MessageOrigin.ENGINE,
                        composerEnabled = false, ownerId = owner), { _, _ -> error("滚动不能发送") })
                }
            }
        }.use { environment ->
            fun frames(n: Int) { repeat(n) { environment.advanceFrameBy() } }
            fun nodes(): List<SemanticsNode> {
                fun visit(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::visit)
                return environment.scene.semanticsOwners.flatMap { visit(it.rootSemanticsNode) }
            }
            fun list() = nodes().single { it.config.contains(SemanticsActions.ScrollToIndex) }
            fun editor() = nodes().single { it.config.contains(SemanticsActions.SetText) &&
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("短信内容") == true }
            frames(40)
            if (!atBottom) {
                assertTrue(list().config[SemanticsActions.ScrollToIndex].action!!.invoke(15)); frames(10)
            }
            val axis = list().config[SemanticsProperties.VerticalScrollAxisRange]
            if (atBottom) assertEquals(axis.maxValue(), axis.value(), .1f)
            else assertTrue(axis.value() < axis.maxValue())
            assertTrue(editor().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("合成草稿")))
            assertTrue(editor().config[SemanticsActions.RequestFocus].action!!.invoke()); frames(5)
            assertTrue(editor().config[SemanticsProperties.Focused])
            val before = hides
            val start = Offset(8f, list().boundsInRoot.center.y)
            environment.press(start)
            repeat(5) { index -> frames(1); environment.move(start + Offset(0f, (index + 1) * distance / 5f)) }
            environment.release(start + Offset(0f, distance)); frames(30)
            assertEquals(before, hides, "非底部向下起手不能收起输入法")
            assertTrue(editor().config[SemanticsProperties.Focused])
            assertEquals("合成草稿", editor().config[SemanticsProperties.EditableText].text)
        }
    } }

    @Test fun threadQueryChangeResetsSearchButRefreshKeepsItsPosition() = onMotionTestThread { runTest {
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val conversation = StoredMessageConversation(owner, "12025550123")
        val records = List(60) { MessageRecord.Incoming("search-$it", "+12025550123", "合成消息 $it", 1790848800000L + it * 1000L, isRead = true) }
        val state = mutableStateOf(MessageUiState.Ready(records, MessageOrigin.ENGINE, composerEnabled = false, ownerId = owner))
        MotionTestEnvironment(testScheduler, width = 390, height = 740) {
            SoftVoWiFiTheme(false) { MessageThreadScreen(conversation.handle, state.value, { _, _ -> error("搜索不能发送") }) }
        }.use { environment ->
            fun frames(n: Int) { repeat(n) { environment.advanceFrameBy() } }
            fun nodes(): List<SemanticsNode> {
                fun visit(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::visit)
                return environment.scene.semanticsOwners.flatMap { visit(it.rootSemanticsNode) }
            }
            fun list() = nodes().single { it.config.contains(SemanticsActions.ScrollToIndex) }
            fun axis() = list().config[SemanticsProperties.VerticalScrollAxisRange]
            fun search(value: String) {
                val editor = nodes().single { it.config.contains(SemanticsActions.SetText) &&
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("搜索已加载消息") == true }
                assertTrue(editor.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
                frames(5)
            }
            frames(40)
            val searchButton = nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "搜索会话" } == true }.boundsInRoot.center
            environment.press(searchButton); frames(2); environment.release(searchButton); frames(30)
            search("合成")
            assertTrue(list().config[SemanticsActions.ScrollToIndex].action!!.invoke(25)); frames(10)
            assertTrue(axis().value() > 0f, "前提：实际搜索结果已滚离顶部")
            val position = axis().value()
            state.value = state.value.copy(records = records.mapIndexed { i, r -> if (i == 59) r.copy(preview = "合成消息 已刷新") else r })
            frames(5)
            assertEquals(position, axis().value(), "后台刷新不能重置搜索滚动")
            search("合成消息")
            assertEquals(0f, axis().value(), "R9：新搜索词立即归位，即使命中结果具有相同稳定键")
        }
    } }
}
