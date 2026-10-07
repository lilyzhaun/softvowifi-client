package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.*
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import top.yukonga.miuix.kmp.basic.Scaffold

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class CommunicationSheetLifecycleRenderTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val choices = MessageHistoryChoices(
        listOf(MessageHistoryLine(owner, 0, false, simLabel = "SIM 1 · 合成线路")), owner, true,
    )
    private var time = 0L

    @Test fun messagesCancelSlidesOutBeforeReleasingTheHost() = smsOnEdt {
        val scene = messagesScene()
        try {
            settle(scene)
            click(scene, "SIM 1 · 合成线路"); settle(scene)
            val top = text(scene, "选择短信记录线路").boundsInRoot.top
            click(scene, "取消")
            advance(scene, 96)
            val exiting = text(scene, "选择短信记录线路")
            assertTrue(exiting.boundsInRoot.top > top, "取消必须启动滑出，不能直接卸载宿主：$top → ${exiting.boundsInRoot.top}")
            assertTrue(action(scene, "取消").config.contains(SemanticsProperties.Disabled))
            settle(scene)
            assertFalse(hasText(scene, "选择短信记录线路"))
            click(scene, "SIM 1 · 合成线路"); settle(scene)
            assertTrue(hasText(scene, "选择短信记录线路"), "完整退场后必须能重新打开")
        } finally { scene.close() }
    }

    @Test fun remarkOpensOnlyAfterSelectorExitAndNeverSelectsTheLine() = smsOnEdt {
        var selections = 0
        val scene = messagesScene(onSelect = { selections++ })
        try {
            settle(scene)
            click(scene, "SIM 1 · 合成线路"); settle(scene)
            click(scene, "备注"); advance(scene, 32)
            assertTrue(hasText(scene, "选择短信记录线路"), "交接必须保留正在退场的选择器")
            assertFalse(hasText(scene, "线路备注"), "旧面板完成退场前不得打开新的编辑宿主")
            settle(scene)
            assertFalse(hasText(scene, "选择短信记录线路"))
            assertTrue(hasText(scene, "线路备注"))
            assertEquals(0, selections)
            click(scene, "取消"); settle(scene)
            assertFalse(hasText(scene, "线路备注"))
        } finally { scene.close() }
    }

    @Test fun inactiveRouteCancelsAPendingRemarkHandoff() = smsOnEdt {
        val active = mutableStateOf(true)
        val scene = messagesScene(active = { active.value })
        try {
            settle(scene)
            click(scene, "SIM 1 · 合成线路"); settle(scene)
            click(scene, "备注")
            active.value = false
            settle(scene)
            assertFalse(hasText(scene, "线路备注"), "失活后不得由迟到的退场回调打开备注")
            active.value = true; settle(scene)
            assertFalse(hasText(scene, "选择短信记录线路"))
            assertFalse(hasText(scene, "线路备注"))
        } finally { scene.close() }
    }

    @Test fun selectionIsDeferredUntilExitAndAcceptedOnlyOnce() = smsOnEdt {
        val selected = mutableListOf<SimOwnerId>()
        val scene = messagesScene(onSelect = selected::add)
        try {
            settle(scene)
            click(scene, "SIM 1 · 合成线路"); settle(scene)
            val choose = nodes(scene).single { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }
            val invoke = requireNotNull(choose.config[SemanticsActions.OnClick].action)
            invoke(); invoke()
            assertTrue(selected.isEmpty(), "旧宿主退场前不能变更后面的列表")
            settle(scene)
            assertEquals(listOf(owner), selected)
            assertFalse(hasText(scene, "选择短信记录线路"))
        } finally { scene.close() }
    }

    @Test fun callsCancelAlsoRetainsTheSheetThroughExit() = smsOnEdt {
        val scene = ImageComposeScene(width = 390, height = 844) {
            SoftVoWiFiTheme(false) { Scaffold(Modifier.fillMaxSize()) {
                CallLineEntry(CallLineState(), "", {}, historyOwners = listOf(CallHistoryOwnerChoice(owner, "合成历史", true)))
            } }
        }
        try {
            settle(scene)
            click(scene, "合成历史"); settle(scene)
            val top = text(scene, "选择通话记录线路").boundsInRoot.top
            click(scene, "取消"); advance(scene, 96)
            assertTrue(text(scene, "选择通话记录线路").boundsInRoot.top > top,
                "电话面板必须滑出：$top → ${text(scene, "选择通话记录线路").boundsInRoot.top}")
            settle(scene)
            assertFalse(hasText(scene, "选择通话记录线路"))
        } finally { scene.close() }
    }

    @Test fun zeroMotionClosesBeforeOpeningTheRemarkWithoutAStuckHost() = smsOnEdt {
        val motion = object : MotionDurationScale { override val scaleFactor = 0f }
        val scene = messagesScene(motion = motion)
        try {
            settle(scene)
            click(scene, "SIM 1 · 合成线路"); settle(scene)
            click(scene, "备注"); settle(scene)
            assertFalse(hasText(scene, "选择短信记录线路"))
            assertTrue(hasText(scene, "线路备注"))
            click(scene, "取消"); settle(scene)
            assertFalse(hasText(scene, "线路备注"))
        } finally { scene.close() }
    }

    @Test fun savingTitleDoesNotOverlapActionsAtTwoHundredPercent() = smsOnEdt {
        val result = CompletableDeferred<MessageRemarkSaveResult>()
        val editor = MessageRemarkEditor(owner, "合成备注")
        val scene = ImageComposeScene(width = 390, height = 250, density = Density(1f, 2f)) {
            SoftVoWiFiTheme(false) { Scaffold(Modifier.fillMaxSize()) {
                MessageRemarkDialog(editor, {}) { _, _ -> result.await() }
            } }
        }
        try {
            settle(scene)
            click(scene, "保存"); settle(scene)
            val title = text(scene, "线路备注").boundsInRoot
            val save = action(scene, "正在保存").boundsInRoot
            assertFalse(title.overlaps(save), "大字号下标题与保存中动作不能相互叠印")
            assertTrue(save.bottom <= 250f)
        } finally { result.cancel(); scene.close() }
    }

    @Test fun messageDetailsReleaseTheRecordOnlyAfterClosing() = smsOnEdt {
        var record: MessageRecord? by mutableStateOf(MessageRecord.Outgoing(
            "synthetic", "+12025550123", "合成正文", 1, OutgoingMessageStatus.UNKNOWN,
        ))
        val scene = ImageComposeScene(width = 390, height = 844) {
            SoftVoWiFiTheme(false) { Scaffold(Modifier.fillMaxSize()) {
                MessageThreadActions(record, "", false, { record = null }, {}, {}, SmsMessageAction.DETAILS)
            } }
        }
        try {
            settle(scene)
            val top = text(scene, "消息详情").boundsInRoot.top
            click(scene, "关闭详情"); advance(scene, 96)
            assertNotNull(record)
            assertTrue(text(scene, "消息详情").boundsInRoot.top > top)
            settle(scene)
            assertNull(record)
            assertFalse(hasText(scene, "消息详情"))
        } finally { scene.close() }
    }

    private fun messagesScene(
        active: () -> Boolean = { true }, onSelect: (SimOwnerId) -> Unit = {},
        motion: MotionDurationScale = object : MotionDurationScale { override val scaleFactor = 1f },
    ) = ImageComposeScene(width = 390, height = 844, coroutineContext = Dispatchers.Unconfined + motion) {
        SoftVoWiFiTheme(false) { Scaffold(Modifier.fillMaxSize()) { CompositionLocalProvider(LocalAppRouteActive provides active()) {
            MessagesScreen(MessageUiState.Unavailable("合成历史"), {}, {}, historyChoices = choices,
                onSelectHistoryOwner = onSelect)
        } } }
    }

    private fun advance(scene: ImageComposeScene, millis: Int) {
        repeat(millis / 16) { time += 16_000_000L; scene.render(time).close() }
    }
    private fun settle(scene: ImageComposeScene) = advance(scene, 1600)
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { owner ->
        fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
        tree(owner.rootSemanticsNode)
    }
    private fun hasText(scene: ImageComposeScene, label: String) = nodes(scene).any {
        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
    }
    private fun text(scene: ImageComposeScene, label: String) = nodes(scene).first {
        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
    }
    private fun action(scene: ImageComposeScene, label: String) = nodes(scene).single {
        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true &&
            it.config.contains(SemanticsActions.OnClick)
    }
    private fun click(scene: ImageComposeScene, label: String) {
        assertTrue(requireNotNull(action(scene, label).config[SemanticsActions.OnClick].action).invoke())
    }
}
