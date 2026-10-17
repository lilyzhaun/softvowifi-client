package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.vowifi.sw.manager.data.CallSessionClock
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds
import org.jetbrains.skia.Bitmap
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class CallScreensRenderTest {
    private var time = 0L
    private val binding = CallBinding("synthetic-owner", "synthetic-call", 1, "synthetic-epoch")
    private fun ready(phase: CallPhase) = CallUiState.Ready(phase, CallAudioState.Active, CallOrigin.DESKTOP_FIXTURE,
        peer = CallPeer.parse("+12025550123"), binding = binding, direction = if (phase == CallPhase.Ringing) CallDirection.INCOMING else CallDirection.OUTGOING,
        dtmfSupported = phase == CallPhase.InCall, clock = if (phase == CallPhase.InCall) CallSessionClock(138000) { 0 } else null)

    @Test fun rendersThreeProductionCompositionsAndAccessibleVariants() {
        render("dialer-light-390", false, 390, 844) { PhoneDialerScreen("+12025550123", {}, "SIM 2 · T-Mobile", true, false, null, {}, {}, {}) }
        render("incoming-dark-390", true, 390, 844) { phone(ready(CallPhase.Ringing)) }
        render("incall-dark-390", true, 390, 844) { phone(ready(CallPhase.InCall)) }
        render("incall-light-390", false, 390, 844) { phone(ready(CallPhase.InCall)) }
        render("dialer-dark-390", true, 390, 844) { PhoneDialerScreen("+12025550123", {}, "SIM 2 · T-Mobile", true, false, null, {}, {}, {}) }
        render("incoming-light-390", false, 390, 844) { phone(ready(CallPhase.Ringing)) }
        render("incall-light-320", false, 320, 740) { phone(ready(CallPhase.InCall)) }
        render("incoming-dark-320-font200", true, 320, 740, 2f) { phone(ready(CallPhase.Ringing)) }
        render("dialer-light-320-font200", false, 320, 740, 2f) { PhoneDialerScreen("+12025550123", {}, "SIM 2 · T-Mobile", true, false, null, {}, {}, {}) }
        render("incall-dark-landscape", true, 800, 390) { phone(ready(CallPhase.InCall)) }
        render("ended-dark", true, 390, 844) { CallEndContent(null, {}) }
        render("ended-reason-light", false, 390, 844) { CallEndContent("对方未接听", {}) }
        render("loading-dark", true, 390, 844) { CallLoadingContent({}, {}) }
        render("loading-error-light", false, 390, 844) { CallLoadingContent({}, {}, "暂时无法确认通话状态，请刷新重试") }
    }

    @Test fun unknownAndEndedLargeTextKeepSeparatedReachableRealActions() {
        var refreshes = 0
        var returns = 0
        var ended by mutableStateOf(false)
        val scene = scene(false, 320, 740, 2f) {
            if (ended) CallEndContent("对方未接听", { returns++ })
            else CallLoadingContent({ refreshes++ }, { returns++ }, "暂时无法确认通话状态，请刷新重试")
        }
        try {
            frame(scene)
            val scroll = nodes(scene).single { it.config.contains(SemanticsActions.ScrollBy) }
            requireNotNull(scroll.config[SemanticsActions.ScrollBy].action).invoke(0f, 4000f); frame(scene)
            assertTrue(button(scene, "返回").boundsInRoot.bottom <= 740)
            assertTrue(button(scene, "刷新").boundsInRoot.bottom < button(scene, "返回").boundsInRoot.top)
            assertTrue(nodes(scene).none { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
            click(scene, "刷新"); click(scene, "返回")
            assertEquals(1, refreshes); assertEquals(1, returns)
            screenshot(scene, "loading-error-light-320-font200")
            ended = true; frame(scene)
            assertTrue(button(scene, "返回通话记录").boundsInRoot.bottom <= 740)
            click(scene, "返回通话记录")
            assertEquals(2, returns)
            screenshot(scene, "ended-reason-light-320-font200")
        } finally { scene.close() }
    }

    @Test fun primaryActionsAreNotTogglesAndAudioReportsConfirmedState() {
        var state by mutableStateOf(ready(CallPhase.Ringing))
        val scene = scene(true, 390, 844) { phone(state) }
        try {
            frame(scene)
            assertNull(button(scene, "接听").config.getOrNull(SemanticsProperties.StateDescription))
            assertFalse(button(scene, "接听").config.contains(SemanticsProperties.Selected))
            state = ready(CallPhase.InCall); frame(scene)
            assertEquals(false, button(scene, "静音").config.getOrNull(SemanticsProperties.Selected))
            assertEquals(true, button(scene, "免提").config.getOrNull(SemanticsProperties.Selected))
            state = state.copy(audio = CallAudioState.Idle); frame(scene)
            assertTrue(button(scene, "静音").config.contains(SemanticsProperties.Disabled))
        } finally { scene.close() }
    }

    @Test fun dialerEditsLongZeroAndDeleteThenCallsExactDraft() {
        var number by mutableStateOf("")
        var submitted: String? = null
        val scene = scene(false, 390, 844) {
            PhoneDialerScreen(number, { number = it }, "SIM 2 · T-Mobile", true, false, null, { submitted = number }, {}, {})
        }
        try {
            frame(scene)
            click(scene, "1"); frame(scene)
            click(scene, "2"); frame(scene)
            longClick(scene, "0"); frame(scene)
            assertEquals("+12", number)
            click(scene, "删除号码"); frame(scene)
            assertEquals("+1", number)
            longClick(scene, "删除号码"); frame(scene)
            assertEquals("", number)
            val field = nodes(scene).first { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(requireNotNull(field.config[SemanticsActions.SetText].action).invoke(AnnotatedString("+12025550123")))
            frame(scene); click(scene, "拨打")
            assertEquals("+12025550123", submitted)
        } finally { scene.close() }
    }

    @Test fun activeActionsUseCallbacksAndKeypadDoesNotStartAnotherCall() {
        val actions = mutableListOf<CallAction>()
        val digits = mutableListOf<Char>()
        var muted = false
        var speaker = false
        var minimized = false
        val scene = scene(true, 390, 844) {
            CallScreenContent(ready(CallPhase.InCall), "SIM 2 · T-Mobile", CallAudioControls(),
                onAction = actions::add, onMute = { muted = true }, onSpeaker = { speaker = true }, onDigit = digits::add,
                onRestoreAudio = {}, onMinimize = { minimized = true }, ticking = false)
        }
        try {
            frame(scene); click(scene, "静音"); click(scene, "免提")
            assertTrue(muted && speaker)
            click(scene, "拨号键盘"); frame(scene)
            click(scene, "5"); frame(scene)
            assertEquals(listOf('5'), digits)
            assertTrue(actions.isEmpty())
            screenshot(scene, "incall-keypad-dark")
            clickText(scene, "关闭键盘"); frame(scene)
            click(scene, "挂断")
            assertEquals(listOf(CallAction.Hangup), actions)
            clickText(scene, "返回应用")
            assertTrue(minimized)
        } finally { scene.close() }
    }

    @Test fun incomingAndOutgoingUseDistinctRealControlsAndLargeTargets() {
        val actions = mutableListOf<CallAction>()
        var state by mutableStateOf(ready(CallPhase.Ringing))
        val scene = scene(false, 320, 740, 2f) { phone(state, actions::add) }
        try {
            frame(scene)
            val answer = button(scene, "接听")
            assertTrue(answer.boundsInRoot.width >= 48 && answer.boundsInRoot.height >= 48)
            click(scene, "接听"); click(scene, "拒接")
            assertEquals(listOf(CallAction.Answer, CallAction.Reject), actions)
            state = ready(CallPhase.Dialing); frame(scene)
            click(scene, "取消")
            assertEquals(CallAction.Cancel, actions.last())
            state = state.copy(phase = CallPhase.Canceling, cancelPending = true); frame(scene)
            assertTrue(button(scene, "正在取消").config.contains(SemanticsProperties.Disabled))
        } finally { scene.close() }
    }

    @Test fun connectingHasRealHangupAndEndingKeepsVisibleBusyControl() {
        val actions = mutableListOf<CallAction>()
        var state by mutableStateOf(ready(CallPhase.Connecting).copy(audio = CallAudioState.Idle, direction = CallDirection.INCOMING))
        val scene = scene(true, 320, 740) { phone(state, actions::add) }
        try {
            frame(scene)
            val hangup = button(scene, "挂断")
            assertFalse(hangup.config.contains(SemanticsProperties.Disabled))
            assertTrue(hangup.boundsInRoot.top >= 0 && hangup.boundsInRoot.bottom <= 740)
            screenshot(scene, "connecting-dark-320")
            click(scene, "挂断")
            assertEquals(listOf(CallAction.Hangup), actions)
            state = state.copy(phase = CallPhase.Ending); frame(scene)
            val ending = button(scene, "正在挂断")
            assertTrue(ending.config.contains(SemanticsProperties.Disabled))
            assertTrue(ending.boundsInRoot.top >= 0 && ending.boundsInRoot.bottom <= 740)
            screenshot(scene, "ending-dark-320")
        } finally { scene.close() }
    }

    @Test fun largeFontDialerCanScrollItsCallActionIntoTheViewport() {
        val scene = scene(false, 320, 740, 2f) {
            PhoneDialerScreen("+12025550123", {}, "SIM 2 · T-Mobile", true, false, null, {}, {}, {})
        }
        try {
            frame(scene)
            val scrolling = nodes(scene).first { it.config.contains(SemanticsActions.ScrollBy) }
            assertTrue(requireNotNull(scrolling.config[SemanticsActions.ScrollBy].action).invoke(0f, 4000f))
            frame(scene)
            val call = button(scene, "拨打")
            assertTrue(call.boundsInRoot.top >= 0 && call.boundsInRoot.bottom <= 740)
            screenshot(scene, "dialer-light-320-font200-bottom")
        } finally { scene.close() }
    }

    @Test fun unknownRegistrationDoesNotPretendToBeAnActiveConversation() {
        var refreshed = false
        val scene = scene(true, 390, 844) {
            CallScreenContent(ready(CallPhase.InCall).copy(sync = CallSyncState.Unregistered), "当前绑定线路", CallAudioControls(),
                onAction = {}, onMute = {}, onSpeaker = {}, onDigit = {}, onRestoreAudio = {}, onMinimize = {},
                ticking = false, onRefresh = { refreshed = true })
        }
        try {
            frame(scene)
            val text = nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            assertTrue("线路未注册" in text)
            assertTrue(text.none { it.startsWith("通话中") })
            click(scene, "重新同步")
            assertTrue(refreshed)
            screenshot(scene, "unregistered-dark")
        } finally { scene.close() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun realSourceStagesKeepRegionsAndBlendTextAndActionGroups() = onMotionTestThread {
        runTest(UnconfinedTestDispatcher(), timeout = 20.seconds) {
            for (incoming in listOf(true, false)) {
                val wire = FakeCallTransport()
                val audio = FakeCallAudioController()
                val dialGate = if (incoming) null else wire.enqueueGate()
                val backend = sourceOf(listOf(callRunning(CALL_ICCID_A)), wire, audio,
                    monotonicNow = { testScheduler.currentTime })
                try {
                    if (incoming) wire.emitIncoming(CALL_TOKEN, "RINGING", peer = "+12025550123")
                    else { backend.dial("+12025550123"); wire.emitCallState(CALL_TOKEN, "DIALING") }
                    assertEquals(if (incoming) CallPhase.Ringing else CallPhase.Dialing, backend.ready().phase)
                    MotionTestEnvironment(testScheduler, width = 390, height = 844) {
                        val actual by backend.state.collectAsState()
                        SoftVoWiFiTheme(false) {
                            CallScreenContent(actual as CallUiState.Ready, "SIM 2 · T-Mobile", CallAudioControls(),
                                onAction = { backend.ready().binding?.let { b -> backend.act(b, it) } },
                                onMute = backend::mute, onSpeaker = backend::speaker, onDigit = {},
                                onRestoreAudio = {}, onMinimize = {})
                        }
                    }.use { env ->
                        repeat(24) { env.advanceFrameBy() }
                        fun text(prefix: String) = nodes(env.scene).first { node ->
                            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(prefix) } == true
                        }
                        fun identity() = listOf(text("SIM 2 · T-Mobile").boundsInRoot,
                            text("+1 202 555 0123").boundsInRoot, button(env.scene, "返回应用").boundsInRoot)
                        fun footerBottom() = listOf("接听", "拒接", "取消", "挂断").mapNotNull { label ->
                            nodes(env.scene).firstOrNull { node ->
                                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true &&
                                    node.config.contains(SemanticsActions.OnClick)
                            }?.boundsInRoot?.bottom
                        }.max()
                        val fixedIdentity = identity()
                        val fixedBottom = footerBottom()
                        val status = text(if (incoming) "来电" else "正在呼叫").boundsInRoot
                        val statusRoi = Rect(12f, status.top - 4f, 378f, status.bottom + 4f)
                        val footerRoi = Rect(12f, fixedBottom - 224f, 378f, (fixedBottom + 28f).coerceAtMost(844f))
                        fun patch(rect: Rect) = s6Patch(env, testScheduler.currentTime, rect)
                        suspend fun change(phase: CallPhase, action: suspend () -> Unit) {
                            val oldText = patch(statusRoi); val oldGroup = patch(footerRoi)
                            action()
                            assertEquals(phase, backend.ready().phase, "先确认真实source阶段，不能只改UI投影")
                            repeat(2) { env.advanceFrameBy(0) }
                            env.advanceFrameBy(80); val out80 = patch(footerRoi)
                            assertEquals(fixedIdentity, identity(), "80ms身份区不重排")
                            assertEquals(fixedBottom, footerBottom(), 2f, "动作组固定底边")
                            env.advanceFrameBy(20); val middleText = patch(statusRoi)
                            env.advanceFrameBy(80); val in180 = patch(footerRoi)
                            env.advanceFrameBy(120)
                            val newText = patch(statusRoi); val newGroup = patch(footerRoi)
                            assertEquals(fixedIdentity, identity(), "真实阶段变化后身份/返回入口固定")
                            assertEquals(fixedBottom, footerBottom(), 2f)
                            s6AssertCrossfade(oldText, middleText, newText)
                            s6AssertPartial(oldGroup, out80, "80ms仍有旧动作但非瞬切/满强度")
                            s6AssertPartial(newGroup, in180, "180ms新动作组必须处于真实淡入中帧")
                        }
                        if (incoming) change(CallPhase.Connecting) { click(env.scene, "接听") }
                        change(CallPhase.InCall) {
                            if (incoming) wire.emitIncoming(CALL_TOKEN, "IN_CALL")
                            else wire.emitCallState(CALL_TOKEN, "IN_CALL")
                        }
                        dialGate?.complete(Unit)
                        env.advanceFrameBy(0)
                        assertEquals(listOf(if (incoming) "answer" else "dial"), wire.requests.map { it.method })
                        assertEquals(1, audio.starts.size, "阶段动画不能重复启动音频")
                        // legacy无版本事件不携带可信elapsed；真实计时只能来自现有权威快照入口。
                        val snapshotsBeforeClock = wire.snapshotRequests.size
                        backend.refresh(); repeat(3) { env.advanceFrameBy(0) }
                        assertTrue(wire.snapshotRequests.size > snapshotsBeforeClock)
                        assertEquals(CallPhase.InCall, backend.ready().phase)
                        assertEquals(1, audio.starts.size, "采纳同通话快照不能重复启动音频")
                        val clock = requireNotNull(backend.ready().clock)
                        val firstDuration = clock.formatted()
                        env.advanceFrameBy(1000)
                        val nextDuration = clock.formatted()
                        assertNotEquals(firstDuration, nextDuration)
                        assertTrue(nodes(env.scene).any { n -> n.config.getOrNull(SemanticsProperties.Text)
                            ?.any { it.text.endsWith(nextDuration) } == true }, "真实时钟逐秒更新")
                        val tick = patch(statusRoi)
                        env.advanceFrameBy(100)
                        assertContentEquals(tick, patch(statusRoi), "计时换秒后不得再交叉淡入/滚动")
                    }
                } finally { backend.close(); dialGate?.complete(Unit) }
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun realSourceCrossesInCallAndTerminalWithoutReplacingIdentityFooterOrStageSlot() = onMotionTestThread {
        runTest(UnconfinedTestDispatcher(), timeout = 20.seconds) {
            val wire = FakeCallTransport(); val audio = FakeCallAudioController()
            val backend = sourceOf(listOf(callRunning(CALL_ICCID_A)), wire, audio, monotonicNow = { testScheduler.currentTime })
            var heldHangup: CompletableDeferred<Unit>? = null
            val faults = mutableListOf<String>()
            try {
                wire.emitIncoming(CALL_TOKEN, "RINGING", peer = "+12025550123")
                backend.answer()
                assertEquals(CallPhase.Connecting, backend.ready().phase)
                MotionTestEnvironment(testScheduler, width = 390, height = 844) {
                    val actual by backend.state.collectAsState()
                    val ready = actual as CallUiState.Ready
                    SoftVoWiFiTheme(false) {
                        // T6 now enters the same production host, including the public terminal return callback.
                        CallScreenContent(ready, "SIM 2 · T-Mobile", CallAudioControls(),
                            onAction = { backend.ready().binding?.let { binding -> backend.act(binding, it) } },
                            onMute = backend::mute, onSpeaker = backend::speaker, onDigit = {},
                            onRestoreAudio = {}, onMinimize = {}, ticking = false, onReturn = {})
                    }
                }.use { env ->
                    repeat(24) { env.advanceFrameBy() }
                    // 结束页背景本身可点击并合并语义；位置测量必须读真实文字叶而非整屏合并节点。
                    fun text(prefix: String) = s6LeafNodes(env.scene).firstOrNull { n ->
                        n.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(prefix) } == true
                    }
                    fun identity() = listOf(text("SIM 2 · T-Mobile")?.boundsInRoot, text("+1 202 555 0123")?.boundsInRoot)
                    fun footer() = nodes(env.scene).filter { n -> n.config.contains(SemanticsActions.OnClick) &&
                        n.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it in listOf("挂断", "正在挂断", "返回通话记录") } == true
                    }.maxOfOrNull { it.boundsInRoot.bottom }
                    val fixedIdentity = identity(); assertTrue(fixedIdentity.all { it != null })
                    val originalCall = assertNotNull(backend.ready().binding, "真实Connecting须有原call binding")
                    val fixedFooter = assertNotNull(footer())
                    val stage = assertNotNull(text("正在接通")).boundsInRoot
                    val region = Rect(stage.center.x - 48f, stage.top - 4f, stage.center.x + 48f, stage.bottom + 4f)
                    fun image() = s6Patch(env, testScheduler.currentTime, region)
                    suspend fun crossing(phase: CallPhase, title: String, change: suspend () -> Unit) {
                        val before = image(); change()
                        assertEquals(phase, backend.ready().phase, "必须先由原生产source确认阶段")
                        if (phase == CallPhase.Idle) {
                            val endedCall = assertNotNull(backend.ready().terminal,
                                "真实Idle必须先发布typed terminal，不能用UI补造旧身份").binding
                            assertEquals(CallSyncState.Ready, backend.ready().sync, "终态宿主须有真实Ready授权前提")
                            assertTrue(originalCall.sameCall(endedCall), "typed终态须匹配原显示call，不比较或泄露token原值")
                        }
                        repeat(3) { env.advanceFrameBy(0) }
                        env.advanceFrameBy(100)
                        val middle = image()
                        if (identity() != fixedIdentity) faults += "$phase：跨branch的100ms身份槽已重排/被替换；期望=$fixedIdentity，实际=${identity()}"
                        if (footer()?.let { kotlin.math.abs(it - fixedFooter) <= 2f } != true) faults += "$phase：固定footer已移动；期望=$fixedFooter，实际=${footer()}"
                        val currentStage = assertNotNull(text(title), "真实当前阶段文字须存在，不用原layout空白当动画")
                        if (kotlin.math.abs(currentStage.boundsInRoot.center.y - stage.center.y) > .5f)
                            faults += "$phase：当前阶段离开原固定文字slot；期望=$stage，实际=${currentStage.boundsInRoot}"
                        env.advanceFrameBy(200)
                        val terminal = image()
                        if (middle.contentEquals(before) || middle.contentEquals(terminal))
                            faults += "$phase：100ms没有原位置旧/新文字交接，局部新叶首次组合不算跨branch淡入淡出"
                        if (identity() != fixedIdentity) faults += "$phase：终态身份槽未保留；期望=$fixedIdentity，实际=${identity()}"
                    }
                    crossing(CallPhase.InCall, "通话中") { wire.emitIncoming(CALL_TOKEN, "IN_CALL") }
                    assertEquals(1, audio.starts.size)
                    heldHangup = wire.enqueueGate()
                    crossing(CallPhase.Ending, "正在挂断") { click(env.scene, "挂断") }
                    crossing(CallPhase.Idle, "通话已结束") { heldHangup!!.complete(Unit) }
                    assertEquals(listOf("answer", "hangup"), wire.requests.map { it.method })
                    assertEquals(1, audio.starts.size, "阶段转移不重放音频启动")
                    assertTrue(faults.isEmpty(), faults.joinToString("\n"))
                }
            } finally { backend.close(); heldHangup?.complete(Unit) }
        }
    }

    // C11呈现层：confirmed是调用方已确认的投影；不是Android facade端到端确认替身。
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun confirmedAudioProjectionAloneChangesSelectionAndTintAfterRealPointerRequests() = onMotionTestThread {
        runTest(UnconfinedTestDispatcher(), timeout = 20.seconds) {
            for (label in listOf("静音", "免提")) for (scale in listOf(1f, 0f)) {
                var confirmed by mutableStateOf(CallAudioControls())
                val requests = mutableListOf<String>(); var from = 0; var target = 0
                MotionTestEnvironment(testScheduler, 390, 844, scale) {
                    SoftVoWiFiTheme(false) {
                        val palette = phoneColors()
                        SideEffect { from = palette.surface.toArgb(); target = palette.tonal.toArgb() }
                        CallScreenContent(ready(CallPhase.InCall), "SIM 1 · 合成线路", confirmed,
                            onAction = { error("不允许通话业务") }, onMute = { requests += "静音" }, onSpeaker = { requests += "免提" },
                            onDigit = {}, onRestoreAudio = {}, onMinimize = {}, ticking = false)
                    }
                }.use { e ->
                    repeat(40) { e.advanceFrameBy() }
                    // combinedClickable合并语义后内部face tag不一定导出；真实72dp命中面与静止圆面同槽。
                    fun face() = button(e.scene, label).boundsInRoot
                    val fixed = face()
                    assertEquals(72f, fixed.width, .1f); assertEquals(72f, fixed.height, .1f)
                    val region = Rect(fixed.left + 20f, fixed.top + 10f, fixed.left + 28f, fixed.top + 18f)
                    fun patch() = s3SmallPatch(e, testScheduler, region)
                    val before = patch(); assertTrue(before.pixels.all { s3RgbDistance(it, from) <= 2 })
                    s4Tap(e, button(e.scene, label).boundsInRoot.center)
                    assertEquals(listOf(label), requests)
                    repeat(50) { e.advanceFrameBy() } // 只在确认前消退原按压；确认后的150ms边界不延长。
                    assertEquals(false, button(e.scene, label).config.getOrNull(SemanticsProperties.Selected))
                    assertEquals(CallAudioControls(), confirmed); assertEquals(fixed, face())
                    assertEquals(0, s4Difference(before, patch()), "点击只请求；未有已确认投影不得乐观选色")
                    val confirmedAt = testScheduler.currentTime
                    confirmed = if (label == "静音") CallAudioControls(muted = true) else CallAudioControls(speakerOn = true)
                    repeat(4) { e.advanceFrameBy(0) }
                    assertEquals(true, button(e.scene, label).config.getOrNull(SemanticsProperties.Selected))
                    val instant = patch(); e.advanceFrameBy(80); val middle = patch()
                    e.advanceFrameBy(70); val terminal = patch()
                    assertEquals(confirmedAt + 150, testScheduler.currentTime); assertEquals(fixed, face())
                    assertEquals(listOf(label), requests)
                    assertTrue(terminal.pixels.all { s3RgbDistance(it, target) <= 2 }, "150ms主题选中面色")
                    if (scale == 0f) assertTrue(instant.pixels.all { s3RgbDistance(it, target) <= 2 }, "zero同刻终色")
                    else assertTrue(middle.pixels.all { s3RgbDistance(it, from) > 2 && s3RgbDistance(it, target) > 2 }, "80ms真实面色中帧")
                }
            }
            // 8bit量化可能掩盖10ms差；配合真实像素检查公开调用点的精确时长，不把160ms判合格。
            val relative = "com/vowifi/sw/manager/ui/InCallScreenContent.kt"
            val code = listOf("shared/src/commonMain/kotlin/", "src/commonMain/kotlin/", "app/manager/shared/src/commonMain/kotlin/")
                .map { java.io.File(it + relative) }.first { it.isFile }.readText()
            for (label in listOf("静音", "免提")) {
                val start = code.indexOf("PhoneButton(\"$label\""); assertTrue(start >= 0)
                val next = code.indexOf("PhoneButton(", start + 1).let { if (it < 0) code.length else it }
                val call = code.substring(start, next)
                assertTrue("MotionTokens.DurFast" in call && "MotionTokens.EaseStandard" in call,
                    "仅两音频键的公开颜色spec须150ms/EaseStandard")
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun normalScaleThemeChangeImmediatelyRecolorsTheRealMuteFaceWithoutBusinessOrRelayout() = onMotionTestThread {
        runTest(UnconfinedTestDispatcher()) {
            var dark by mutableStateOf(false); var callbacks = 0; var expectedSurface = 0
            val state = ready(CallPhase.InCall); val audio = CallAudioControls()
            MotionTestEnvironment(testScheduler, 390, 844, durationScale = 1f) {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    SoftVoWiFiTheme(forceDark = dark) {
                        val surface = phoneColors().surface
                        SideEffect { expectedSurface = surface.toArgb() }
                        CallScreenContent(state, "synthetic-line", audio,
                            onAction = { callbacks++ }, onMute = { callbacks++ }, onSpeaker = { callbacks++ },
                            onDigit = { callbacks++ }, onRestoreAudio = { callbacks++ }, onMinimize = { callbacks++ },
                            onRefresh = { callbacks++ }, onReturn = { callbacks++ }, ticking = false)
                    }
                }
            }.use { e ->
                repeat(30) { e.advanceFrameBy(16) }
                fun mute() = button(e.scene, "静音")
                fun semantics() = mute().config.run {
                    Triple(getOrNull(SemanticsProperties.Selected), contains(SemanticsProperties.Disabled), contains(SemanticsActions.OnClick))
                }
                val bounds = mute().boundsInRoot; val originalSemantics = semantics()
                assertEquals(72f, bounds.width); assertEquals(72f, bounds.height)
                assertEquals(Triple(false, false, true), originalSemantics)
                val left = kotlin.math.floor(bounds.left) + 6f; val top = kotlin.math.floor(bounds.center.y) - 4f
                val roi = Rect(left, top, left + 8f, top + 8f) // 面内左侧8×8，避开中央30dp图标及圆弧边。
                assertTrue(roi.right < bounds.center.x - 15f && roi.left > bounds.left && roi.bottom < bounds.bottom)
                fun patch() = s6Patch(e, testScheduler.currentTime, roi)
                val before = patch(); val oldExpected = expectedSurface
                dark = true; val changedAt = testScheduler.currentTime
                repeat(4) { e.advanceFrameBy(0) }
                val immediate = patch(); val immediateBounds = mute().boundsInRoot; val immediateSemantics = semantics()
                val immediateAt = testScheduler.currentTime
                repeat(10) { e.advanceFrameBy(20) } // 正常scale=1完整200ms终点先封存，再判断同刻。
                val terminal = patch(); val terminalExpected = expectedSurface
                assertEquals(64, before.size); assertEquals(64, immediate.size); assertEquals(64, terminal.size)
                assertTrue(before.all { s6Distance(it, oldExpected) <= 2 }, "前提：ROI确是原主题真实静音面")
                assertTrue(terminal.all { s6Distance(it, terminalExpected) <= 2 }, "前提：200ms已画出真正新主题surface")
                assertTrue(s6Distance(oldExpected, terminalExpected) > 8 && before.indices.all { s6Distance(before[it], terminal[it]) > 8 },
                    "前提：原面与真实新色必须确有差异，不能采到未变背景／图标")
                assertEquals(changedAt, immediateAt); assertEquals(1f, e.durationScale)
                assertEquals(bounds, immediateBounds); assertEquals(bounds, mute().boundsInRoot)
                assertEquals(originalSemantics, immediateSemantics); assertEquals(originalSemantics, semantics())
                assertEquals(0, callbacks); assertEquals(CallPhase.InCall, state.phase); assertFalse(audio.muted)
                assertContentEquals(terminal, immediate, "E6：主题变化同刻须直接到真实新色，不继续业务色tween")
            }
        }
    }

    private fun s6LeafNodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        descend(owner.unmergedRootSemanticsNode)
    }

    private fun s6Patch(env: MotionTestEnvironment, at: Long, rect: Rect): IntArray =
        env.scene.render(at * 1_000_000L).use { image -> Bitmap.makeFromImage(image).use { bitmap ->
            val left = kotlin.math.floor(rect.left).toInt().coerceIn(0, image.width)
            val top = kotlin.math.floor(rect.top).toInt().coerceIn(0, image.height)
            val right = kotlin.math.ceil(rect.right).toInt().coerceIn(left, image.width)
            val bottom = kotlin.math.ceil(rect.bottom).toInt().coerceIn(top, image.height)
            IntArray((right - left) * (bottom - top)) { i -> bitmap.getColor(left + i % (right - left), top + i / (right - left)) }
        } }

    private fun s6Distance(a: Int, b: Int): Int = maxOf(
        kotlin.math.abs((a and 255) - (b and 255)),
        kotlin.math.abs(((a ushr 8) and 255) - ((b ushr 8) and 255)),
        kotlin.math.abs(((a ushr 16) and 255) - ((b ushr 16) and 255)))

    private fun s6AssertCrossfade(old: IntArray, mid: IntArray, target: IntArray) {
        val canvas = old.first()
        fun exclusive(from: IntArray, absent: IntArray) = from.indices.filter {
            s6Distance(from[it], canvas) > 25 && s6Distance(absent[it], canvas) < 5
        }
        for ((endpoint, mask) in listOf(old to exclusive(old, target), target to exclusive(target, old))) {
            assertTrue(mask.size >= 8, "前提：同一自然文字ROI须有旧/新独占墨迹")
            assertTrue(mask.count { s6Distance(mid[it], canvas) > 2 &&
                s6Distance(mid[it], canvas) < s6Distance(endpoint[it], canvas) - 2 } >= 8,
                "100ms必须同时画出旧与新文字的部分强度，语义中只有新文字不等于视觉已接线")
        }
    }

    private fun s6AssertPartial(endpoint: IntArray, middle: IntArray, message: String) {
        val canvas = endpoint.first()
        assertTrue(endpoint.indices.count { s6Distance(endpoint[it], canvas) > 25 &&
            s6Distance(middle[it], canvas) > 2 && s6Distance(middle[it], canvas) < s6Distance(endpoint[it], canvas) - 2 } >= 12, message)
    }

    @Composable private fun phone(state: CallUiState.Ready, action: (CallAction) -> Unit = {}) {
        CallScreenContent(state, "SIM 2 · T-Mobile", CallAudioControls(speakerOn = true),
            onAction = action, onMute = {}, onSpeaker = {}, onDigit = {}, onRestoreAudio = {}, onMinimize = {}, ticking = false)
    }
    private fun scene(dark: Boolean, width: Int, height: Int, scale: Float = 1f, content: @Composable () -> Unit) =
        ImageComposeScene(width = width, height = height) {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) { SoftVoWiFiTheme(forceDark = dark) { content() } }
        }
    private fun render(name: String, dark: Boolean, width: Int, height: Int, scale: Float = 1f, content: @Composable () -> Unit) {
        val scene = scene(dark, width, height, scale, content)
        try { frame(scene); screenshot(scene, name) } finally { scene.close() }
    }
    private fun frame(scene: ImageComposeScene) { repeat(12) { time += 100_000_000L; scene.render(time).close() } }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        descend(owner.rootSemanticsNode)
    }
    private fun button(scene: ImageComposeScene, label: String) = nodes(scene).first {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true && it.config.contains(SemanticsActions.OnClick)
    }
    private fun click(scene: ImageComposeScene, label: String) { assertTrue(requireNotNull(button(scene, label).config[SemanticsActions.OnClick].action).invoke()) }
    private fun longClick(scene: ImageComposeScene, label: String) { assertTrue(requireNotNull(button(scene, label).config[SemanticsActions.OnLongClick].action).invoke()) }
    private fun clickText(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).first { (it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true) && it.config.contains(SemanticsActions.OnClick) }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }
    private fun screenshot(scene: ImageComposeScene, name: String) {
        scene.render(time).use { image -> requireNotNull(image.encodeToData()).use { data ->
            Files.write(Path.of("/tmp/opencode/phone-polish-$name.png"), data.bytes)
        } }
    }
}
