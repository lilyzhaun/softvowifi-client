package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.vowifi.sw.manager.data.CallSessionClock
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
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
