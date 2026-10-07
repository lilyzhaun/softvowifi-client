package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class PhoneCompleteFlowTest {
    private var time = 0L
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val number = "+12025550123"
    private fun ready() = CallUiState.Ready(CallPhase.Idle, CallAudioState.Idle, CallOrigin.ENGINE)

    @Test fun anOutgoingLineCannotOverrideRecoveringActiveOrMissingCallState() = smsOnEdt {
        var state: CallUiState by mutableStateOf(ready())
        var calls = 0
        val scene = scene { CallsDialpadScreen(state, number, {}, { calls++ }, outgoingEnabled = true) }
        try {
            frame(scene)
            assertFalse(button(scene, "拨打").config.contains(SemanticsProperties.Disabled))
            for (blocked in listOf(ready().copy(sync = CallSyncState.Recovering), ready().copy(sync = CallSyncState.Unavailable),
                ready().copy(phase = CallPhase.InCall), CallUiState.Loading, CallUiState.PermissionDenied("合成拒权"))) {
                state = blocked; frame(scene)
                val dial = button(scene, "拨打")
                assertTrue(dial.config.contains(SemanticsProperties.Disabled), "线路资格不能覆盖当前通话/同步状态：$blocked")
                dial.config[SemanticsActions.OnClick].action?.invoke()
                assertEquals(0, calls)
            }
            state = ready(); frame(scene); click(scene, "拨打")
            assertEquals(1, calls, "恢复后只执行用户明确的一次动作")
        } finally { scene.close() }
    }

    @Test fun leavingDialerDisablesNumberKeysDeletionAndDialWithoutLosingTheDraft() = smsOnEdt {
        var active by mutableStateOf(true)
        var draft by mutableStateOf(number)
        var calls = 0
        val scene = scene {
            CompositionLocalProvider(LocalAppRouteActive provides active) {
                PhoneDialerScreen(draft, { draft = it }, "合成线路", true, false, null, { calls++ }, {}, {})
            }
        }
        try {
            frame(scene)
            active = false; frame(scene)
            val input = nodes(scene).single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("电话号码") == true }
            assertTrue(input.config.contains(SemanticsProperties.Disabled), "退场页不应继续接收号码输入")
            for (label in listOf("1", "删除号码", "拨打")) {
                val action = button(scene, label)
                assertTrue(action.config.contains(SemanticsProperties.Disabled), "失活动作仍可用：$label")
                action.config[SemanticsActions.OnClick].action?.invoke()
            }
            assertEquals(number, draft); assertEquals(0, calls)
            active = true; frame(scene)
            val restored = nodes(scene).single { it.config.contains(SemanticsActions.SetText) }
            assertFalse(restored.config.contains(SemanticsProperties.Disabled))
            requireNotNull(restored.config[SemanticsActions.SetText].action).invoke(AnnotatedString("12")); frame(scene)
            assertEquals("12", draft)
            click(scene, "删除号码"); assertEquals("1", draft)
            assertEquals(0, calls)
        } finally { scene.close() }
    }

    @Test fun minimizedCallDescribesSynchronizationTruthAndStillOpensTheCurrentSession() = smsOnEdt {
        var state by mutableStateOf(ready().copy(phase = CallPhase.InCall))
        var opens = 0
        val scene = scene { CallsScreen(state, true, {}, CallHistoryState.Empty, {}, {}, {}, {}, {}, onOpenCall = { opens++ }) }
        try {
            frame(scene)
            val labels = mapOf(CallSyncState.Recovering to "正在恢复通话状态", CallSyncState.Unavailable to "通话状态待确认",
                CallSyncState.Unregistered to "线路未注册", CallSyncState.OwnerChanged to "通话线路已变化")
            for ((sync, title) in labels) {
                state = state.copy(sync = sync); frame(scene)
                assertTrue(has(scene, "$title · 返回通话"), "根入口不能把${sync}写成已确认通话中")
                assertFalse(has(scene, "通话中 · 返回通话"))
                click(scene, "$title · 返回通话")
            }
            assertEquals(4, opens)
            capture(scene, "minimized")
            state = state.copy(sync = CallSyncState.Ready); frame(scene)
            assertTrue(has(scene, "通话中 · 返回通话"))
        } finally { scene.close() }
    }

    @Test fun callDetailLoadingFailureAndMissingRecordsAreDistinctAndDoNotDelete() = smsOnEdt {
        val record = requireNotNull(CallHistoryWrite.parse(CallHistoryDraft("synthetic", CallDirection.OUTGOING,
            number, CallTimesDraft(1000, 2000, 62000, 60), CallOutcome.COMPLETED))).toRecord(owner)
        val initial = CallHistoryState.Ready(listOf(record), owner)
        val handle = callHistoryGroups(initial).single().handle
        var history: CallHistoryState by mutableStateOf(CallHistoryState.Loading)
        var deletes = 0
        val scene = scene { CallHistoryDetailScreen(handle, history) { _, _ -> deletes++ } }
        try {
            frame(scene)
            assertTrue(has(scene, "正在读取通话记录"))
            assertFalse(has(scene, "记录已变化或线路已切换，请返回通话列表。"), "读取中不能暗示历史已消失")
            assertTrue(nodes(scene).any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
            history = CallHistoryState.Failure("合成存储读取错误"); frame(scene)
            assertTrue(has(scene, "无法读取通话记录"))
            assertTrue(has(scene, "合成存储读取错误"))
            assertFalse(nodes(scene).any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
            history = initial; frame(scene)
            assertTrue(has(scene, "时长：1 分 0 秒"))
            assertEquals(0, deletes)
            history = CallHistoryState.Empty; frame(scene)
            assertTrue(has(scene, "通话记录不可用"))
            assertFalse(has(scene, "时长：1 分 0 秒"))
            assertEquals(0, deletes)
        } finally { scene.close() }
    }

    @Test fun dialerFailureOffersARealRecoveryEntryBesideThePreservedNumber() = smsOnEdt {
        var draft by mutableStateOf(number)
        var state: CallUiState by mutableStateOf(CallUiState.Failure("合成连接失败"))
        var refreshes = 0
        val scene = scene { CallsDialpadScreen(state, draft, { draft = it },
            { error("failure must not dial") }, onRefresh = { refreshes++; state = CallUiState.Loading }) }
        try {
            frame(scene)
            assertTrue(has(scene, "刷新通话状态"), "要求刷新必须提供恢复入口")
            assertEquals(number, draft)
            assertTrue(button(scene, "拨打").config.contains(SemanticsProperties.Disabled))
            assertTrue(button(scene, "刷新通话状态").boundsInRoot.let { it.top >= 0 && it.bottom <= 844 && it.height >= 48 },
                "正常字号恢复入口必须可见且有48dp目标")
            capture(scene, "dialer-recovery")
            click(scene, "刷新通话状态")
            assertEquals(1, refreshes)
            assertFalse(has(scene, "刷新通话状态"), "真实读取中不重复提供刷新")
            assertEquals(number, draft)
            state = ready(); frame(scene)
            assertFalse(has(scene, "刷新通话状态"))
            assertFalse(button(scene, "拨打").config.contains(SemanticsProperties.Disabled))
        } finally { scene.close() }
    }

    @Test fun rootFailureRefreshRetainsHistoryAndLoadingHasProgress() = smsOnEdt {
        var state: CallUiState by mutableStateOf(CallUiState.Failure("合成连接失败"))
        var refreshes = 0
        val scene = scene { CallsScreen(state, true, {}, CallHistoryState.Empty, {}, {}, {}, {}, {},
            onRefresh = { refreshes++; state = CallUiState.Loading }) }
        try {
            frame(scene)
            click(scene, "刷新通话状态")
            assertEquals(1, refreshes)
            assertTrue(has(scene, "正在读取通话状态"))
            assertTrue(nodes(scene).any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
            assertTrue(has(scene, "暂无通话记录"), "刷新通话状态不应抹除独立历史状态")
            assertFalse(has(scene, "刷新通话状态"))
        } finally { scene.close() }
    }

    @Test fun anIdleSnapshotFailureStillProvidesRootRecoveryWithoutClaimingAnActiveCall() = smsOnEdt {
        var state by mutableStateOf(ready().copy(sync = CallSyncState.Unavailable, operationMessage = CallFailureMessages.SNAPSHOT))
        var refreshes = 0
        val scene = scene { CallsScreen(state, true, {}, CallHistoryState.Empty, {}, {}, {}, {}, {},
            onRefresh = { refreshes++; state = state.copy(sync = CallSyncState.Recovering) }) }
        try {
            frame(scene)
            assertTrue(has(scene, CallFailureMessages.SNAPSHOT))
            click(scene, "刷新通话状态")
            assertEquals(1, refreshes)
            assertFalse(has(scene, "刷新通话状态"))
            assertFalse(has(scene, "通话中 · 返回通话"))
            assertTrue(has(scene, "暂无通话记录"))
        } finally { scene.close() }
    }

    private fun scene(content: @Composable () -> Unit) = ImageComposeScene(width = 390, height = 844) {
        CompositionLocalProvider(LocalPhoneBack provides {}) { SoftVoWiFiTheme(false, content = content) }
    }
    private fun frame(scene: ImageComposeScene) { repeat(24) { time += 40_000_000L; scene.render(time).close() } }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
        tree(owner.rootSemanticsNode)
    }
    private fun has(scene: ImageComposeScene, label: String) = nodes(scene).any {
        it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    }
    private fun button(scene: ImageComposeScene, label: String) = assertNotNull(nodes(scene).firstOrNull {
        (it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
            it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true) && it.config.contains(SemanticsActions.OnClick)
    }, "缺少真实动作：$label")
    private fun click(scene: ImageComposeScene, label: String) {
        requireNotNull(button(scene, label).config[SemanticsActions.OnClick].action).invoke(); frame(scene)
    }
    private fun capture(scene: ImageComposeScene, name: String) = scene.render(time).use { image ->
        requireNotNull(image.encodeToData()).use { data ->
            java.nio.file.Files.write(java.nio.file.Path.of("/tmp/opencode/phone-flow-$name.png"), data.bytes)
        }
    }
}
