package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.vowifi.sw.manager.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import top.yukonga.miuix.kmp.basic.Text

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class SmsInnerMotionRenderTest {
    private var time = 0L

    @Test fun durationRecomposesWhenTheSameMotionScaleChangesToZeroAndBack() {
        val factor = mutableStateOf(1f)
        val motion = object : MotionDurationScale { override val scaleFactor get() = factor.value }
        val scene = ImageComposeScene(width = 200, height = 100, coroutineContext = motion) {
            SoftVoWiFiTheme(false) { Text(smsInnerMotionMillis().toString()) }
        }
        try {
            frame(scene); assertEquals(listOf("120"), texts(scene))
            factor.value = 0f; frame(scene); assertEquals(listOf("0"), texts(scene))
            factor.value = 1f; frame(scene); assertEquals(listOf("120"), texts(scene))
        } finally { scene.close() }
    }

    @Test fun parentRecompositionDuringLongPressUsesLatestCallbackWithoutCancellingGesture() = runBlocking {
        val revision = mutableStateOf(1)
        val calls = mutableListOf<Pair<Int, String>>()
        val scene = ImageComposeScene(width = 420, height = 200) {
            val current = revision.value
            CompositionLocalProvider(LocalSmsBubbleAction provides { id -> calls.add(current to id) }) {
                SoftVoWiFiTheme(false) { IncomingMessageBubble(MessageThreadItem.IncomingBubble("message-one", "合成正文", MessageTimestampSpec(1))) }
            }
        }
        try {
            frame(scene)
            val center = nodes(scene).first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "合成正文" } == true }.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, center)
            delay(150)
            revision.value = 2; frame(scene)
            delay(550)
            scene.sendPointerEvent(PointerEventType.Release, center); frame(scene)
            assertEquals(listOf(2 to "message-one"), calls)
        } finally { scene.close() }
    }

    @Test fun replacingMessageIdentityDuringLongPressCancelsOldGesture() = runBlocking {
        val identity = mutableStateOf("first")
        val calls = mutableListOf<String>()
        val scene = ImageComposeScene(width = 420, height = 200) {
            CompositionLocalProvider(LocalSmsBubbleAction provides { id -> calls.add(id) }) {
                SoftVoWiFiTheme(false) { IncomingMessageBubble(MessageThreadItem.IncomingBubble(identity.value, "合成正文", MessageTimestampSpec(1))) }
            }
        }
        try {
            frame(scene)
            val center = nodes(scene).first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "合成正文" } == true }.boundsInRoot.center
            scene.sendPointerEvent(PointerEventType.Press, center); delay(150)
            identity.value = "second"; frame(scene); delay(550)
            scene.sendPointerEvent(PointerEventType.Release, center); frame(scene)
            assertEquals(emptyList(), calls)
        } finally { scene.close() }
    }

    private fun frame(scene: ImageComposeScene) { repeat(4) { time += 100_000_000L; scene.render(time).close() } }
    private fun texts(scene: ImageComposeScene) = nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        descend(owner.rootSemanticsNode)
    }
}
