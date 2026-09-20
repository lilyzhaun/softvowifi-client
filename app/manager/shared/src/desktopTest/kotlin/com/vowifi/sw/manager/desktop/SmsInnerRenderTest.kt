package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import com.vowifi.sw.manager.ui.navigation.AppRoute
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.data.StoredMessageConversation
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import kotlin.test.Test
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Scaffold

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class SmsInnerRenderTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val peer = "+12025550123"
    private var time = 0L
    private val lifecycle = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }

    @Test fun longPressOffersExplicitActionsInsteadOfCopyingImmediately() = runBlocking {
        val hostClipboard = java.awt.datatransfer.Clipboard("issue23-native-test")
        val clipboard = object : Clipboard {
            override val nativeClipboard: Any = hostClipboard
            override suspend fun getClipEntry(): ClipEntry? = hostClipboard.getContents(null)?.let(::ClipEntry)
            override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                hostClipboard.setContents(clipEntry?.nativeClipEntry as? Transferable, null)
            }
        }
        val state = MessageUiState.Ready(listOf(MessageRecord.Incoming("one", peer, "你好，稍后联系。", 1)), MessageOrigin.ENGINE, ownerId = owner)
        val scene = ImageComposeScene(width = 420, height = 900) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalClipboard provides clipboard) {
                SoftVoWiFiTheme(forceDark = false) { Scaffold(Modifier.fillMaxSize()) {
                    MessageThreadScreen(StoredMessageConversation(owner, peer.drop(1)).handle, state, { _, _ -> error("must not send") })
                } }
            }
        }
        try {
            frame(scene)
            val bubble = nodes(scene).first { it.config.getOrNull(SemanticsActions.OnLongClick) != null }
            assertTrue(requireNotNull(bubble.config[SemanticsActions.OnLongClick].action).invoke())
            frame(scene)
            assertTrue(nodes(scene).any { text(it, "复制正文") })
            assertTrue(nodes(scene).any { text(it, "消息详情") })
            clickText(scene, "复制正文"); frame(scene)
            val clip = requireNotNull(clipboard.getClipEntry()).nativeClipEntry as Transferable
            assertEquals("你好，稍后联系。", clip.getTransferData(DataFlavor.stringFlavor))
        } finally { scene.close() }
    }

    @Test fun recipientInputBusyDoubleTapAndMatchingSubmissionPreserveNewEdits() {
        val state = mutableStateOf(MessageUiState.Ready(emptyList(), MessageOrigin.ENGINE, composerEnabled = true, ownerId = owner))
        val recipient = mutableStateOf("")
        val body = mutableStateOf("")
        val sends = mutableListOf<Pair<String, String>>()
        val scene = scene(AppRoute.NewMessage) {
            NewMessageScreen(state.value, recipient.value, body.value, { recipient.value = it }, { body.value = it },
                { recipient.value = ""; body.value = "" }, { number, message -> sends.add(number to message) })
        }
        try {
            frame(scene)
            assertFalse(nodes(scene).any { text(it, "请输入有效号码，可使用开头的 + 和数字。") })
            setField(scene, 0, "++1"); frame(scene)
            assertTrue(nodes(scene).any { text(it, "请输入有效号码，可使用开头的 + 和数字。") })
            setField(scene, 0, "+1 202 555 0123"); setField(scene, 1, "你好，\n稍后联系。"); frame(scene)
            assertEquals(peer, recipient.value)
            screenshot(scene, "new-light")
            val send = nodes(scene).first { it.config.getOrNull(SemanticsProperties.StateDescription) == "可发送" }
            assertTrue(send.boundsInRoot.width >= 48f && send.boundsInRoot.height >= 48f)
            val click = requireNotNull(send.config[SemanticsActions.OnClick].action)
            click(); click(); frame(scene)
            assertEquals(1, sends.size)
            assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.StateDescription) == "发送中" })
            screenshot(scene, "new-pending")
            body.value = "新的草稿"
            state.value = state.value.copy(sendAttempt = MessageSendAttempt(owner, "attempt-one", peer, sends.single().second, OutgoingMessageStatus.ACCEPTED))
            frame(scene)
            assertEquals("新的草稿", body.value)
            val next = nodes(scene).first { it.config.getOrNull(SemanticsProperties.StateDescription) == "可发送" }
            requireNotNull(next.config[SemanticsActions.OnClick].action).invoke(); frame(scene)
            state.value = state.value.copy(sendAttempt = MessageSendAttempt(owner, "attempt-two", peer, "新的草稿", OutgoingMessageStatus.ACCEPTED))
            frame(scene)
            assertEquals("", body.value)
            assertEquals(2, sends.size)
        } finally { scene.close() }
    }

    @Test fun threadPointerActionsSearchDetailsAndFailedReeditNeverSend() = runBlocking {
        val failed = MessageRecord.Outgoing("failed", peer, "我会提前发消息。", 60000, OutgoingMessageStatus.FAILED)
        val unknown = MessageRecord.Outgoing("unknown", peer, "等待确认。", 120000, OutgoingMessageStatus.UNKNOWN)
        val state = mutableStateOf(MessageUiState.Ready(listOf(MessageRecord.Incoming("one", peer, "你好，下午方便吗？", 1), failed, unknown),
            MessageOrigin.ENGINE, composerEnabled = true, ownerId = owner))
        val route = AppRoute.MessageThread(StoredMessageConversation(owner, peer.drop(1)).handle)
        val scene = scene(route) { MessageThreadScreen(route.conversation, state.value, { _, _ -> error("reedit must not send") }) }
        try {
            frame(scene); screenshot(scene, "thread-light")
            setField(scene, 0, "保留的草稿"); frame(scene)
            longPress(scene, failed.preview); clickText(scene, "重新编辑"); frame(scene)
            screenshot(scene, "thread-replace")
            clickText(scene, "取消"); frame(scene)
            assertEquals("保留的草稿", editable(scene).single().config[SemanticsProperties.EditableText].text)
            longPress(scene, failed.preview); clickText(scene, "重新编辑"); frame(scene)
            clickText(scene, "替换草稿"); frame(scene)
            assertEquals(failed.preview, editable(scene).single().config[SemanticsProperties.EditableText].text)
            longPress(scene, unknown.preview)
            assertFalse(nodes(scene).any { text(it, "重新编辑") })
            clickText(scene, "消息详情"); frame(scene); screenshot(scene, "thread-details")
            clickText(scene, "关闭详情"); frame(scene)
            clickText(scene, "搜索会话"); frame(scene)
            setField(scene, 0, "下午"); frame(scene)
            assertTrue(nodes(scene).any { text(it, "你好，下午方便吗？") })
            assertFalse(nodes(scene).any { text(it, unknown.preview) })
            screenshot(scene, "thread-search")
            clickText(scene, "关闭会话搜索"); frame(scene)
            assertEquals(failed.preview, editable(scene).single().config[SemanticsProperties.EditableText].text)
            longPress(scene, unknown.preview)
            state.value = state.value.copy(records = state.value.records.filterNot { it.id == "unknown" })
            frame(scene)
            assertFalse(nodes(scene).any { text(it, "消息详情") })
        } finally { scene.close() }
    }

    @Test fun nativeNarrowLargeTextAndDarkReadonlyRemainEditable() {
        val state = MessageUiState.Ready(listOf(MessageRecord.Incoming("one", peer, "你好，稍后联系。", 1)),
            MessageOrigin.ENGINE, ownerId = owner)
        val body = mutableStateOf("草稿可以编辑，连接后再发送。")
        val scene = scene(AppRoute.NewMessage, dark = true, width = 320, fontScale = 2f, height = 720) {
            NewMessageScreen(state, peer, body.value, {}, { body.value = it }, {}, { _, _ -> error("read only") })
        }
        try {
            frame(scene); screenshot(scene, "new-dark-320-200")
            val send = nodes(scene).first { it.config.getOrNull(SemanticsProperties.StateDescription) == "不可发送" }
            assertTrue(send.boundsInRoot.height >= 48f && send.boundsInRoot.width >= 48f)
            assertTrue(send.boundsInRoot.bottom <= 720f)
            setField(scene, editable(scene).lastIndex, "仍可编辑"); frame(scene)
            assertEquals("仍可编辑", body.value)
        } finally { scene.close() }
        val route = AppRoute.MessageThread(StoredMessageConversation(owner, peer.drop(1)).handle)
        val thread = scene(route, dark = true) { MessageThreadScreen(route.conversation, state, { _, _ -> error("read only") }) }
        try { frame(thread); screenshot(thread, "thread-dark") } finally { thread.close() }
    }

    private fun scene(route: AppRoute.Subpage, dark: Boolean = false, width: Int = 420, fontScale: Float = 1f,
        height: Int = 900, content: @Composable () -> Unit): ImageComposeScene =
        ImageComposeScene(width = width, height = height, density = Density(1f, fontScale)) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
                SoftVoWiFiTheme(forceDark = dark) {
                    AppSubpageScene(route, appChromeSpec(AppearanceSettings(floatingNavigation = true, blurRequested = false), false),
                        titleOverride = if (route is AppRoute.MessageThread) peer else null, onBack = {}, content = content)
                }
            }
        }

    private fun editable(scene: ImageComposeScene) = nodes(scene).filter { it.config.getOrNull(SemanticsActions.SetText) != null }
    private fun setField(scene: ImageComposeScene, index: Int, value: String) {
        requireNotNull(editable(scene)[index].config[SemanticsActions.SetText].action).invoke(AnnotatedString(value))
    }
    private fun clickText(scene: ImageComposeScene, value: String) {
        val node = nodes(scene).single { text(it, value) && it.config.getOrNull(SemanticsActions.OnClick) != null }
        val center = node.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press, center)
        scene.sendPointerEvent(PointerEventType.Release, center)
        frame(scene)
    }
    private suspend fun longPress(scene: ImageComposeScene, value: String) {
        val center = nodes(scene).first { text(it, value) }.boundsInRoot.center
        scene.sendPointerEvent(PointerEventType.Press, center)
        delay(650)
        scene.sendPointerEvent(PointerEventType.Release, center)
        frame(scene)
    }
    private fun screenshot(scene: ImageComposeScene, name: String) {
        scene.render(time).use { image -> requireNotNull(image.encodeToData()).use { data ->
            Files.write(Path.of("/tmp/opencode/issue23-$name.png"), data.bytes)
        } }
    }

    private fun frame(scene: ImageComposeScene) { repeat(12) { time += 100_000_000L; scene.render(time).close() } }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        descend(owner.rootSemanticsNode)
    }
    private fun text(node: SemanticsNode, value: String) = node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
}
