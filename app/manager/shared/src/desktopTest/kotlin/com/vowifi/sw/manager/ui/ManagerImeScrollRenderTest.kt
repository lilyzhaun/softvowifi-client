package com.vowifi.sw.manager.ui

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

/** R10: the non-controllable platform path dismisses on downward drag, preserving the real composer draft. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ManagerImeScrollRenderTest {
    @Test fun threadDownwardDragAtBottomDismissesKeyboardAndKeepsDraft() = onMotionTestThread { runTest {
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val conversation = StoredMessageConversation(owner, "12025550123")
        val records = List(24) { MessageRecord.Incoming("ime-$it", "+12025550123", "合成消息 $it", 1790848800000L + it * 1000L, isRead = true) }
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
            fun editor() = nodes().single { it.config.contains(SemanticsActions.SetText) &&
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("短信内容") == true }
            frames(40)
            val list = nodes().single { it.config.contains(SemanticsActions.ScrollToIndex) }
            val axis = list.config[SemanticsProperties.VerticalScrollAxisRange]
            assertEquals(axis.maxValue(), axis.value(), .1f, "前提：生产线程已处于最新消息底部")
            assertTrue(editor().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("合成草稿")))
            assertTrue(editor().config[SemanticsActions.RequestFocus].action!!.invoke())
            frames(5)
            assertTrue(editor().config[SemanticsProperties.Focused])
            val before = hides
            val start = Offset(8f, list.boundsInRoot.center.y)
            environment.press(start)
            repeat(5) { index -> frames(1); environment.move(start + Offset(0f, (index + 1) * 16f)) }
            environment.release(start + Offset(0f, 80f)); frames(30)
            assertEquals(before + 1, hides, "R10：向下滚动开始时收起输入法恰好一次")
            assertFalse(editor().config[SemanticsProperties.Focused])
            assertEquals("合成草稿", editor().config[SemanticsProperties.EditableText].text)
        }
    } }
}
