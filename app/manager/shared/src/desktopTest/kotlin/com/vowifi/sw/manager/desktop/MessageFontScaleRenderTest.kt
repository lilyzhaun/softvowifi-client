package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.vowifi.sw.manager.ui.IncomingMessageBubble
import com.vowifi.sw.manager.ui.OutgoingMessageBubble
import com.vowifi.sw.manager.ui.MessageThreadItem
import com.vowifi.sw.manager.ui.MessageTimestampSpec
import com.vowifi.sw.manager.ui.SoftVoWiFiTheme
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class MessageFontScaleRenderTest {
    @Test fun narrowIncomingAndOutgoingBubblesRenderAtSmallSystemScale() = render(0.85f)
    @Test fun narrowIncomingAndOutgoingBubblesRenderAboveTwoAndStillScroll() = render(2.1f)

    private fun render(scale: Float) {
        var scrolling: ScrollState? = null
        val body = "合成字号边界正文。".repeat(50)
        val timestamp = MessageTimestampSpec(1_700_000_000_000L)
        val scene = ImageComposeScene(width = 320, height = 480, density = Density(1f, scale)) {
            SoftVoWiFiTheme(forceDark = false) {
                val scroll = rememberScrollState()
                scrolling = scroll
                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                    IncomingMessageBubble(MessageThreadItem.IncomingBubble("synthetic-in", body, timestamp))
                    OutgoingMessageBubble(MessageThreadItem.OutgoingBubble("synthetic-out", body, timestamp, "已受理", null))
                }
            }
        }
        try {
            repeat(8) { scene.render((it + 1) * 16_666_667L).close() }
            val nodes = scene.semanticsOwners.flatMap { owner ->
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                walk(owner.rootSemanticsNode)
            }
            val texts = nodes.filter { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == body } }
            assertTrue(texts.isNotEmpty(), "production bubble text missing at scale=$scale")
            for (node in texts) {
                assertTrue(node.boundsInRoot.left >= 0f && node.boundsInRoot.right <= 320.1f, "horizontal overflow at scale=$scale")
            }
            val scroll = assertNotNull(scrolling)
            assertTrue(scroll.maxValue > 0, "long bubbles must retain scrolling")
            runBlocking { scroll.scrollTo(scroll.maxValue) }
            scene.render(200_000_000L).close()
            assertTrue(scroll.value > 0, "real scroll state did not move")
        } finally { scene.close() }
    }
}
