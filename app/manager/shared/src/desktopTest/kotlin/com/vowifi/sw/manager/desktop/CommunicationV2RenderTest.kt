package com.vowifi.sw.manager.desktop

import kotlin.test.assertFalse

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.vowifi.sw.manager.data.CallLineChoice
import com.vowifi.sw.manager.data.CallLineState
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.MessageHistoryChoices
import com.vowifi.sw.manager.model.MessageHistoryLine
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class CommunicationV2RenderTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val choices = MessageHistoryChoices(
        listOf(MessageHistoryLine(owner, 2, true, simLabel = "SIM 2 · Lebara")), owner, true,
    )
    private var time = 0L

    @Test fun messageLineEntryIsCompactAndStillOpensSelection() {
        var opened = 0
        val scene = ImageComposeScene(width = 390, height = 200) {
            SoftVoWiFiTheme(false) { MessageLineEntry(choices) { opened++ } }
        }
        try {
            frame(scene)
            val target = nodes(scene).single { it.config.contains(SemanticsActions.OnClick) }
            assertTrue(target.boundsInRoot.width < 300f, "线路入口不能再是全宽设置项")
            assertTrue(target.boundsInRoot.width >= 48f && target.boundsInRoot.height >= 48f)
            requireNotNull(target.config[SemanticsActions.OnClick].action).invoke()
            assertEquals(1, opened)
        } finally { scene.close() }
    }

    @Test fun conversationTimeIsAtTrailingTopNotBelowPreview() {
        val scene = ImageComposeScene(width = 390, height = 200) {
            SoftVoWiFiTheme(false) {
                ConversationRow(ConversationRowPresentation("Service", "S", "合成测试预览", "09:38", null), {}, unreadCount = 2)
            }
        }
        try {
            frame(scene)
            val peer = text(scene, "Service").boundsInRoot
            val preview = text(scene, "合成测试预览").boundsInRoot
            val timestamp = text(scene, "09:38").boundsInRoot
            assertTrue(timestamp.left > peer.left && timestamp.top < preview.top, "时间应在右上信息栏")
            assertTrue(timestamp.left >= peer.right, "时间不能覆盖发件人")
        } finally { scene.close() }
    }

    @Test fun rootSearchExpandsInHeaderAboveTheLineLikePhone() {
        val scene = ImageComposeScene(width = 390, height = 844) {
            SoftVoWiFiTheme(false) {
                MessagesScreen(MessageUiState.Unavailable("测试不可写"), {}, {}, historyChoices = choices)
            }
        }
        try {
            frame(scene)
            assertFalse(nodes(scene).any { it.config.contains(SemanticsActions.SetText) })
            nodes(scene).single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("搜索短信") == true && it.config.contains(SemanticsActions.OnClick) }
                .config[SemanticsActions.OnClick].action!!.invoke(); frame(scene)
            val line = text(scene, "SIM 2 · Lebara").boundsInRoot
            val search = nodes(scene).single { it.config.contains(SemanticsActions.SetText) }.boundsInRoot
            assertTrue(search.bottom <= line.top, "展开的搜索属于固定头部，线路入口仍在列表顶部")
        } finally { scene.close() }
    }

    @Test fun rootUsesTrailingTimeAtNormalPhoneWidth() {
        val scene = ImageComposeScene(width = 390, height = 844) {
            SoftVoWiFiTheme(false) {
                MessagesScreen(MessageUiState.Ready(listOf(com.vowifi.sw.manager.model.MessageRecord.Incoming(
                    "synthetic", "Service", "正常字号预览", 60_000)), com.vowifi.sw.manager.model.MessageOrigin.ENGINE,
                    ownerId = owner), {}, {}, historyChoices = choices)
            }
        }
        try {
            frame(scene)
            val timestamp = text(scene, messageDateSpec(60_000).label).boundsInRoot
            assertTrue(timestamp.left > text(scene, "Service").boundsInRoot.left)
            assertTrue(timestamp.top < text(scene, "正常字号预览").boundsInRoot.top)
        } finally { scene.close() }
    }

    private fun frame(scene: ImageComposeScene) { repeat(8) { time += 100_000_000L; scene.render(time).close() } }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        descend(owner.unmergedRootSemanticsNode)
    }
    private fun text(scene: ImageComposeScene, text: String) = nodes(scene).first {
        it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == text &&
            it.config.contains(SemanticsActions.GetTextLayoutResult)
    }
}
