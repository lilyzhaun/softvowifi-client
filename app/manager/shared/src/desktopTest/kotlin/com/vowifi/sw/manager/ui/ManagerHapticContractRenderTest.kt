package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.data.CallHistoryDraft
import com.vowifi.sw.manager.data.CallHistoryWrite
import com.vowifi.sw.manager.data.MessageConversationAction
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.data.StoredMessageConversation
import com.vowifi.sw.manager.model.CallDirection
import com.vowifi.sw.manager.model.CallHistoryState
import com.vowifi.sw.manager.model.CallOutcome
import com.vowifi.sw.manager.model.CallRecord
import com.vowifi.sw.manager.model.CallTimesDraft
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.navigation.AppNavigationState
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.PullToRefreshState
import top.yukonga.miuix.kmp.basic.RefreshState
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import org.jetbrains.skia.Bitmap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S1 §4.9/5.1: real pointer events, production controls and a recording platform delegate.
 * Written before production changes. Compilation/RED execution belongs to the coordinator.
 * PullToRefresh is a pinned-library capability probe, not S3 production refresh integration.
 * No semantic action invocation, internal library API, screenshot or device is used.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ManagerHapticContractRenderTest {
    private val syntheticOwner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val syntheticPeer = "+12025550123"

    @Test fun dialKeyUsesVirtualKeyExactlyOnceOnRelease() = onMotionTestThread { runTest {
        val haptics = RecordingHaptics()
        val digits = mutableListOf<Char>()
        phoneScene(haptics) { CallKeypad(true, digits::add) }.use { scene ->
            scene.warm()
            val position = scene.button("1").boundsInRoot.center
            scene.press(position)
            scene.advanceFrameBy(8)
            assertTrue(digits.isEmpty(), "按下不能输入号码")
            assertTrue(haptics.events.isEmpty(), "按下不能提前发点击触感")
            scene.release(position)
            scene.advanceFrameBy()
            assertEquals(listOf('1'), digits)
            assertEquals(listOf(HapticFeedbackType.VirtualKey), haptics.events,
                "规范4.9：拨号键不是ContextClick，且一个输入只发一次")
        }
    } }

    @Test fun zeroLongPressUsesLongPressAndReleaseNeverAddsZeroOrAClickHaptic() = onMotionTestThread { runTest {
        val haptics = RecordingHaptics()
        val digits = mutableListOf<Char>()
        var pluses = 0
        phoneScene(haptics) { CallKeypad(true, digits::add, onPlus = { pluses++ }) }.use { scene ->
            scene.warm()
            assertTrue(haptics.longPressMillis > 0, "须取得场景真实长按时长")
            val position = scene.button("0").boundsInRoot.center
            scene.press(position)
            scene.advanceFor(haptics.longPressMillis + 32)
            assertEquals(1, pluses, "真实长按在抬起之前成立且只执行一次")
            assertTrue(digits.isEmpty())
            val recognized = haptics.events.toList()
            scene.release(position)
            scene.advanceFor(64)
            assertEquals(1, pluses)
            assertTrue(digits.isEmpty(), "长按后抬起不能再输入0")
            assertEquals(recognized, haptics.events, "抬起不能补发普通点击触感")
            assertEquals(listOf(HapticFeedbackType.LongPress), recognized,
                "规范4.9：长按成立须LongPress，平台和应用不能重复发")
        }
    } }

    @Test fun phoneMuteKeepsRealClickAndLongPressActionsWithoutAnyHaptic() = onMotionTestThread { runTest {
        val haptics = RecordingHaptics()
        val digits = mutableListOf<Char>()
        var pluses = 0
        phoneScene(haptics, enabled = false) { CallKeypad(true, digits::add, { pluses++ }) }.use { scene ->
            scene.warm()
            assertTrue(haptics.longPressMillis > 0, "须取得场景真实长按时长")
            scene.tap(scene.button("2").boundsInRoot.center)
            val position = scene.button("0").boundsInRoot.center
            scene.press(position)
            scene.advanceFor(haptics.longPressMillis + 32)
            scene.release(position)
            scene.advanceFor(64)
            assertEquals(listOf('2'), digits)
            assertEquals(1, pluses)
            assertTrue(haptics.events.isEmpty(), "关触感只禁反馈，不禁真实输入或长按动作")
        }
    } }

    @Test fun disabledBusyAndMovedOutsidePhoneActionsDoNotInvokeOrVibrate() = onMotionTestThread { runTest {
        val haptics = RecordingHaptics()
        var enabled by mutableStateOf(false)
        var busy by mutableStateOf(false)
        var clicks = 0
        phoneScene(haptics) { PhoneButton("受管动作", { clicks++ }, enabled = enabled, busy = busy) }.use { scene ->
            scene.warm()
            scene.tap(scene.button("受管动作").boundsInRoot.center)
            enabled = true
            busy = true
            scene.advanceFor(64)
            scene.tap(scene.button("受管动作").boundsInRoot.center)
            busy = false
            scene.advanceFor(64)
            scene.press(scene.button("受管动作").boundsInRoot.center)
            scene.advanceFrameBy()
            scene.move(Offset(380f, 620f))
            scene.advanceFrameBy()
            scene.release(Offset(380f, 620f))
            scene.advanceFor(64)
            assertEquals(0, clicks)
            assertTrue(haptics.events.isEmpty(), "禁用、忙碌和手势移出取消均不发触感")
        }
    } }

    @Test fun phoneRouteInactiveThenRestoredCannotCommitTheOldFinger() = onMotionTestThread { runTest {
        val haptics = RecordingHaptics()
        var active by mutableStateOf(true)
        var clicks = 0
        phoneScene(haptics) {
            CompositionLocalProvider(LocalAppRouteActive provides active) { PhoneButton("旧手指门禁", { clicks++ }) }
        }.use { scene ->
            scene.warm()
            val position = scene.button("旧手指门禁").boundsInRoot.center
            scene.press(position)
            scene.advanceFrameBy(8)
            active = false
            scene.advanceFor(32)
            active = true
            scene.advanceFor(32)
            scene.release(position)
            scene.advanceFor(32)
            assertEquals(0, clicks, "route失活必须取消真实clickable的旧手指，恢复后不能补提交")
            assertTrue(haptics.events.isEmpty())
            scene.tap(scene.button("旧手指门禁").boundsInRoot.center)
            assertEquals(1, clicks, "恢复后的新真实点击仍然可用")
            assertEquals(listOf(HapticFeedbackType.ContextClick), haptics.events)
        }
    } }

    @Test fun communicationTextRouteInactiveThenRestoredCannotCommitTheOldFinger() = onMotionTestThread { runTest {
        val haptics = RecordingHaptics()
        var active by mutableStateOf(true)
        var clicks = 0
        phoneScene(haptics) {
            CompositionLocalProvider(LocalAppRouteActive provides active) { CommunicationTextAction("受管文字动作", { clicks++ }) }
        }.use { scene ->
            scene.warm()
            val position = scene.button("受管文字动作").boundsInRoot.center
            scene.press(position)
            scene.advanceFrameBy(8)
            active = false
            scene.advanceFor(32)
            active = true
            scene.advanceFor(32)
            scene.release(position)
            scene.advanceFor(32)
            assertEquals(0, clicks, "不能只撤视觉而让旧clickable保留手势")
            assertTrue(haptics.events.isEmpty())
            scene.tap(scene.button("受管文字动作").boundsInRoot.center)
            assertEquals(1, clicks)
            assertEquals(listOf(HapticFeedbackType.ContextClick), haptics.events)
        }
    } }

    @Test fun disabledConversationRowCannotCommitOldPointerAfterReenable() = onMotionTestThread { runTest {
        val haptics = RecordingHaptics()
        var enabled by mutableStateOf(true)
        var opens = 0
        phoneScene(haptics) {
            ConversationRow(ConversationRowPresentation(syntheticPeer, "", "合成预览", "10:00", null),
                onClick = { opens++ }, enabled = enabled)
        }.use { scene ->
            scene.warm()
            val source = scene.nodes().single { it.config.contains(SemanticsActions.OnClick) }
            scene.press(source.boundsInRoot.center)
            scene.advanceFrameBy(8)
            enabled = false
            scene.advanceFor(32)
            enabled = true
            scene.advanceFor(32)
            scene.release(source.boundsInRoot.center)
            scene.advanceFor(32)
            assertEquals(0, opens)
            assertTrue(haptics.events.isEmpty())
            scene.tap(scene.nodes().single { it.config.contains(SemanticsActions.OnClick) }.boundsInRoot.center)
            assertEquals(1, opens)
            assertEquals(listOf(HapticFeedbackType.ContextClick), haptics.events)
        }
    } }

    @Test fun conversationMenuMustForwardItsChangingGateToTheRealRowClickable() = onMotionTestThread { runTest {
        val haptics = RecordingHaptics()
        var enabled by mutableStateOf(true)
        var opens = 0
        val actions = mutableListOf<MessageConversationAction>()
        phoneScene(haptics) {
            MessageConversationMenu(ConversationRowPresentation(syntheticPeer, "", "合成预览", "10:00", null),
                unreadCount = 0, onOpen = { opens++ }, onAction = { actions += it }, enabled = enabled)
        }.use { scene ->
            scene.warm()
            val position = scene.nodes().single {
                it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "会话操作"
            }.boundsInRoot.center
            scene.press(position)
            scene.advanceFrameBy(8)
            enabled = false
            scene.advanceFor(32)
            enabled = true
            scene.advanceFor(32)
            scene.release(position)
            scene.advanceFor(32)
            assertEquals(0, opens, "MessageConversationMenu必须将其enabled传给真实Row，否则旧手指恢复后仍提交")
            assertTrue(actions.isEmpty())
            assertTrue(haptics.events.isEmpty())
            scene.tap(scene.nodes().single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "会话操作" }.boundsInRoot.center)
            assertEquals(1, opens)
        }
    } }

    @Test fun longOnlyPressUsesTheActualDesktopTapDelay() {
        assertEquals(0L, communicationTapIndicationDelayMillis(), "Desktop沿用真实零延迟，不照搬miuix默认150ms")
    }

    @Test fun productionCallHistoryRowLongPressOpensItsOwnMenuWithoutOpeningDetailsOnRelease() = onMotionTestThread { runTest {
        val haptics = RecordingHaptics()
        val group = callHistoryGroups(CallHistoryState.Ready(listOf(syntheticCallRecord()), syntheticOwner)).single()
        var details = 0
        var callbacks = 0
        var deletions = 0
        phoneScene(haptics) {
            LongPressSurface {
                CallHistoryGroupRow(group, { details++ }, callbackEnabled = true,
                    onCallback = { callbacks++ }, onDelete = { deletions++ })
            }
        }.use { scene ->
            scene.longPressOwnedMenu("通话记录操作", haptics) {
                assertEquals(0, details, "长按及抬起不能执行生产行的普通详情导航")
                assertEquals(0, callbacks)
                assertEquals(0, deletions, "只打开原菜单，不执行回拨或删除")
            }
        }
    } }

    @Test fun productionCallOccurrenceLongPressOpensItsOwnMenuWithoutDeletingOnRelease() = onMotionTestThread { runTest {
        val haptics = RecordingHaptics()
        var deletions = 0
        phoneScene(haptics) {
            LongPressSurface { CallHistoryOccurrence(syntheticCallRecord(), { deletions++ }) }
        }.use { scene ->
            scene.longPressOwnedMenu("删除通话记录", haptics) {
                assertEquals(0, deletions, "真实单条长按由CallHistoryOccurrence持有菜单，抬起不能删除")
            }
        }
    } }

    @Test fun productionCallOccurrenceDimsBeforeItsRealLongPressWithoutAddingAClick() = onMotionTestThread { runTest {
        val haptics = RecordingHaptics()
        var deletions = 0
        verifyLongOnlyDimming("删除通话记录", haptics) {
            LongPressSurface { CallHistoryOccurrence(syntheticCallRecord(), { deletions++ }) }
        }
        assertEquals(0, deletions)
    } }

    @Test fun productionThreadBubbleDimsBeforeItsRealLongPressWithoutAddingAClick() = onMotionTestThread { runTest {
        val haptics = RecordingHaptics()
        val handle = StoredMessageConversation(syntheticOwner, syntheticPeer.drop(1)).handle
        val state = MessageUiState.Ready(listOf(
            MessageRecord.Incoming("s1-dim-incoming", syntheticPeer, "合成按压测量文字", 1790848800000L, isRead = true),
        ), MessageOrigin.ENGINE, composerEnabled = false, ownerId = syntheticOwner)
        var sends = 0
        verifyLongOnlyDimming("消息操作", haptics, settleInitialContent = true) {
            AppSubpageScene(AppRoute.MessageThread(handle), appChromeSpec(AppearanceSettings.Default.copy(
                blurRequested = false, liquidGlassRequested = false), false), onBack = {}) {
                MessageThreadScreen(handle, state, onSend = { _, _ -> sends++ })
            }
        }
        assertEquals(0, sends)
    } }

    @Test fun productionCallOccurrenceShortReleaseIsNotCanceledByItsOwnLongDetector() = onMotionTestThread { runTest {
        val haptics = RecordingHaptics()
        var deletions = 0
        verifyLongOnlyShortRelease("删除通话记录", haptics) {
            LongPressSurface { CallHistoryOccurrence(syntheticCallRecord(), { deletions++ }) }
        }
        assertEquals(0, deletions)
    } }

    @Test fun productionThreadBubbleShortReleaseIsNotCanceledByItsOwnLongDetector() = onMotionTestThread { runTest {
        val haptics = RecordingHaptics()
        val handle = StoredMessageConversation(syntheticOwner, syntheticPeer.drop(1)).handle
        val state = MessageUiState.Ready(listOf(
            MessageRecord.Incoming("s1-short-incoming", syntheticPeer, "合成短按测量文字", 1790848800000L, isRead = true),
        ), MessageOrigin.ENGINE, composerEnabled = false, ownerId = syntheticOwner)
        var sends = 0
        verifyLongOnlyShortRelease("消息操作", haptics) {
            AppSubpageScene(AppRoute.MessageThread(handle), appChromeSpec(AppearanceSettings.Default.copy(
                blurRequested = false, liquidGlassRequested = false), false), onBack = {}) {
                MessageThreadScreen(handle, state, onSend = { _, _ -> sends++ })
            }
        }
        assertEquals(0, sends)
    } }

    @Test fun productionConversationLongPressOpensItsOwnMenuWithoutOpeningThreadOnRelease() = onMotionTestThread { runTest {
        val haptics = RecordingHaptics()
        var threads = 0
        val actions = mutableListOf<MessageConversationAction>()
        phoneScene(haptics) {
            LongPressSurface {
                MessageConversationMenu(ConversationRowPresentation(syntheticPeer, "", "合成预览", "10:00", null),
                    unreadCount = 0, onOpen = { threads++ }, onAction = { actions += it }, enabled = true)
            }
        }.use { scene ->
            scene.longPressOwnedMenu("会话操作", haptics) {
                assertEquals(0, threads, "MessageConversationMenu的原long入口不能在抬起时执行普通线程导航")
                assertTrue(actions.isEmpty(), "长按识别不等于标未读或删除")
            }
        }
    } }

    @Test fun productionThreadBubbleLongPressUsesItsRealSelectionOwnerWithoutSendingOnRelease() = onMotionTestThread { runTest {
        val haptics = RecordingHaptics()
        val handle = StoredMessageConversation(syntheticOwner, syntheticPeer.drop(1)).handle
        val state = MessageUiState.Ready(listOf(
            MessageRecord.Incoming("s1-haptic-incoming", syntheticPeer, "仅用于触感复现的合成正文。", 1790848800000L,
                isRead = true),
        ), MessageOrigin.ENGINE, composerEnabled = false, ownerId = syntheticOwner)
        var sends = 0
        phoneScene(haptics) {
            // The actual thread owns LocalSmsBubbleAction/LocalSmsBubbleMenu and selectedId.
            // No test-provided fake long-press callback or clipboard substitution is involved.
            AppSubpageScene(AppRoute.MessageThread(handle), appChromeSpec(AppearanceSettings.Default.copy(
                blurRequested = false, liquidGlassRequested = false), false), onBack = {}) {
                MessageThreadScreen(handle, state, onSend = { _, _ -> sends++ })
            }
        }.use { scene ->
            scene.longPressOwnedMenu("消息操作", haptics) {
                assertEquals(0, sends)
                assertTrue(scene.nodes().any {
                    it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "消息操作" &&
                        it.config.getOrNull(SemanticsProperties.Selected) == true
                }, "必须观察到生产MessageThreadScreen的真实selectedId投影，不只计数假callback")
            }
        }
    } }

    @Test fun managedSwitchRowHasExactlyOneToggleHapticInEachDirection() = onMotionTestThread { runTest {
        val probe = ToggleProbe()
        managerScene(probe.haptics) { probe.Managed() }.use { scene ->
            scene.warm()
            scene.tap(scene.rowPosition())
            assertTrue(probe.checked)
            assertEquals(1, probe.changes)
            assertEquals(listOf(HapticFeedbackType.ToggleOn), probe.haptics.events)
            probe.haptics.events.clear()
            scene.tap(scene.rowPosition())
            assertFalse(probe.checked)
            assertEquals(2, probe.changes)
            assertEquals(listOf(HapticFeedbackType.ToggleOff), probe.haptics.events)
        }
    } }

    @Test fun managedSwitchThumbCannotDuplicateTheApplicationToggleHaptic() = onMotionTestThread { runTest {
        val probe = ToggleProbe()
        managerScene(probe.haptics) { probe.Managed() }.use { scene ->
            scene.warm()
            scene.tap(scene.thumbPosition(checked = false))
            assertTrue(probe.checked, "必须先证明真实thumb入口确实改变了状态")
            assertEquals(1, probe.changes)
            assertEquals(listOf(HapticFeedbackType.ToggleOn), probe.haptics.events,
                "miuix内部Toggle与应用回调不能各发一次")
        }
    } }

    @Test fun managedSwitchThumbDragCommitsOnceAndHasOneCommittedToggleHaptic() = onMotionTestThread { runTest {
        val probe = ToggleProbe()
        managerScene(probe.haptics) {
            // The real thumb can travel outside its 49dp track while staying inside the scene.
            Box(Modifier.width(280.dp)) { probe.Managed() }
        }.use { scene ->
            scene.warm()
            val track = scene.switchNode().boundsInRoot
            val start = scene.thumbPosition(checked = false)
            val end = start + Offset(56f, 0f)
            assertTrue(end.x > track.right && end.x < 390f,
                "终点在真实Switch外但窗口内；若只走toggleable点击会取消，不能冒充thumb拖动提交")
            scene.press(start)
            scene.advanceFrameBy()
            listOf(24f, 40f, 56f).forEach { distance ->
                scene.move(start + Offset(distance, 0f))
                scene.advanceFrameBy()
            }
            assertFalse(probe.checked)
            assertEquals(0, probe.changes, "拖动尚未提交时不得调用onCheckedChange")
            scene.release(end)
            scene.advanceFor(64)
            assertTrue(probe.checked, "必须先证明库draggable在离开点击目标后实际提交")
            assertEquals(1, probe.changes)
            assertEquals(listOf(HapticFeedbackType.ToggleOn), probe.haptics.events,
                "同一次thumb拖动提交不能由库位置反馈和应用提交各发一次")
        }
    } }

    @Test fun managedSwitchThumbDragReturnedToOriginAbortsWithoutCallbackOrHaptic() = onMotionTestThread { runTest {
        val probe = ToggleProbe()
        managerScene(probe.haptics) { Box(Modifier.width(280.dp)) { probe.Managed() } }.use { scene ->
            scene.warm()
            val track = scene.switchNode().boundsInRoot
            val start = scene.thumbPosition(checked = false)
            assertTrue(start.x + 56f > track.right && start.x + 56f < 390f)
            scene.press(start)
            scene.advanceFrameBy()
            // Real draggable consumes the outward and return path. This is aborted submission
            // by returning to origin, not a claim that a platform PointerCancel was injected.
            listOf(24f, 40f, 56f, 40f, 24f, 0f).forEach { distance ->
                scene.move(start + Offset(distance, 0f))
                scene.advanceFrameBy()
            }
            assertFalse(probe.checked)
            assertEquals(0, probe.changes)
            scene.release(start)
            scene.advanceFor(64)
            assertFalse(probe.checked)
            assertEquals(0, probe.changes,
                "拖回原位后的抬起不能退化成普通点击，也不能提交新checked")
            assertTrue(probe.haptics.events.isEmpty(), "未提交的返回取消不保留库内位置触感")
        }
    } }

    @Test fun mutedManagedSwitchRowStillChangesStateWithoutVibrating() = onMotionTestThread { runTest {
        val probe = ToggleProbe()
        managerScene(probe.haptics, enabled = false) { probe.Managed() }.use { scene ->
            scene.warm()
            scene.tap(scene.rowPosition())
            assertTrue(probe.checked)
            assertEquals(1, probe.changes)
            assertTrue(probe.haptics.events.isEmpty())
        }
    } }

    @Test fun mutedManagedSwitchThumbCannotBypassApplicationHapticsPreference() = onMotionTestThread { runTest {
        val probe = ToggleProbe()
        managerScene(probe.haptics, enabled = false) { probe.Managed() }.use { scene ->
            scene.warm()
            scene.tap(scene.thumbPosition(checked = false))
            assertTrue(probe.checked)
            assertEquals(1, probe.changes)
            assertTrue(probe.haptics.events.isEmpty(), "关闭应用触感后库内thumb触感也必须被门控")
        }
    } }

    @Test fun mutedRootAlsoGatesTheUnwrappedPublicMiuixSwitch() = onMotionTestThread { runTest {
        val probe = ToggleProbe()
        managerScene(probe.haptics, enabled = false) {
            Switch(probe.checked, { probe.checked = it; probe.changes++ })
        }.use { scene ->
            scene.warm()
            scene.tap(scene.thumbPosition(checked = false))
            assertTrue(probe.checked)
            assertEquals(1, probe.changes)
            assertTrue(probe.haptics.events.isEmpty(), "只有根门控才能覆盖没有应用handler的库控件")
        }
    } }

    @Test fun publicPullThresholdHasOneHapticWithoutASecondApplicationHaptic() = onMotionTestThread { runTest {
        verifyPullThreshold(enabled = true)
    } }

    @Test fun mutedRootGatesThePublicPullThresholdHaptic() = onMotionTestThread { runTest {
        verifyPullThreshold(enabled = false)
    } }

    private fun TestScope.verifyPullThreshold(enabled: Boolean) {
        val haptics = RecordingHaptics()
        var observed: PullToRefreshState? = null
        var refreshes = 0
        managerScene(haptics, enabled) {
            val state = rememberPullToRefreshState()
            SideEffect { observed = state }
            PullToRefresh(isRefreshing = false, onRefresh = { refreshes++ }, pullToRefreshState = state,
                modifier = Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState(), overscrollEffect = null)
                    .testTag("s1-pull-content")) { Spacer(Modifier.height(1200.dp)) }
            }
        }.use { scene ->
            scene.warm()
            val bounds = scene.nodes().single { it.config.getOrNull(SemanticsProperties.TestTag) == "s1-pull-content" }.boundsInRoot
            val start = Offset(bounds.center.x, bounds.top + 48f)
            scene.press(start)
            scene.advanceFrameBy()
            listOf(40f, 80f, 140f).forEach { distance ->
                scene.move(start + Offset(0f, distance))
                scene.advanceFrameBy()
            }
            assertEquals(RefreshState.ThresholdReached, observed?.refreshState,
                "须由真实touch滚动越过库阈值；前提不成立时不能冒充触感复现")
            assertEquals(1f, observed?.pullProgress)
            assertEquals(0, refreshes, "越过阈值但未抬起不能请求刷新")
            scene.move(start + Offset(0f, 180f))
            scene.advanceFrameBy()
            scene.release(start + Offset(0f, 180f))
            scene.advanceFrameBy()
            val expected = if (enabled) listOf(HapticFeedbackType.GestureThresholdActivate) else emptyList()
            assertEquals(expected, haptics.events,
                "同一次阈值达成只由库发一次且受应用门控；这里不验证S3真实刷新后端")
        }
    }

    private class RecordingHaptics : HapticFeedback {
        val events = mutableListOf<HapticFeedbackType>()
        var longPressMillis = 0L
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { events += hapticFeedbackType }
    }

    /** §5.1：原长按识别器与菜单仍执行；只读识别前P5，不伪造普通点击或菜单状态。 */
    private fun TestScope.verifyLongOnlyDimming(label: String, haptics: RecordingHaptics, settleInitialContent: Boolean = false,
        content: @Composable () -> Unit) {
        phoneScene(haptics, content = content).use { environment ->
            environment.warm()
            // E2新增首载整列表淡入；先让它结束，再独立测P5。按下后的时钟与所有按压断言不变。
            if (settleInitialContent) environment.advanceFor(MotionTokens.DurFast.toLong())
            val target = environment.nodes().single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == label }
            assertFalse(target.config.contains(SemanticsActions.OnClick), "长按专用目标不能增加空点击")
            val bounds = target.boundsInRoot
            assertTrue(bounds.left > 2f && bounds.right <= 390f && bounds.bottom <= 640f && bounds.height > 0f)
            val contrast = longOnlyContentContrast(environment, bounds)
            val resting = contrast()
            assertTrue(resting > 1000.0, "实际目标须与周围均匀背景可区分")
            assertTrue(haptics.longPressMillis >= 320)
            environment.press(bounds.center)
            environment.advanceFor(haptics.longPressMillis - 64)
            val held = contrast()
            val heldBounds = environment.nodes().single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == label }.boundsInRoot
            assertTrue(haptics.events.isEmpty())
            environment.advanceFor(160)
            assertTrue(environment.nodes().any {
                it.config.getOrNull(SemanticsProperties.TestTag) == "communication-context-preview"
            }, "原菜单必须实际识别长按，未命中不能算外观RED")
            environment.release(bounds.center)
            environment.advanceFor(64)
            assertEquals(listOf(HapticFeedbackType.LongPress), haptics.events)
            assertEquals(bounds, heldBounds, "P5不缩放长按目标")
            assertEquals(.82, held / resting, .02, "识别前内容必须按P5微暗，不能等菜单出现才回应")
        }
    }

    /** Normal Up is a real pressable Release, not cancellation caused by the same target's detector. */
    private fun TestScope.verifyLongOnlyShortRelease(label: String, haptics: RecordingHaptics,
        content: @Composable () -> Unit) {
        phoneScene(haptics, content = content).use { environment ->
            environment.warm()
            environment.advanceFor(160) // Let the existing item-entry fade finish before sampling press.
            val target = environment.nodes().single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == label }
            assertFalse(target.config.contains(SemanticsActions.OnClick), "短按不得创建空业务点击")
            val bounds = target.boundsInRoot
            assertTrue(bounds.left > 2f && bounds.right <= 390f && bounds.bottom <= 640f && bounds.height > 0f)
            val contrast = longOnlyContentContrast(environment, bounds)
            val resting = contrast()
            assertTrue(resting > 1000.0, "先确认实际长按内容可测，空图不能算成功")
            assertTrue(haptics.longPressMillis > 48L)
            environment.press(bounds.center)
            environment.advanceFor(32)
            val held = contrast()
            assertTrue(held < resting * .995, "必须先证明真实pressable已收到按压，未命中不算Release合同失败")
            environment.release(bounds.center)
            environment.advanceFor(16)
            assertTrue(contrast() < held,
                "成功Up后90ms窗口内仍向P5目标推进；若被本对象长按检测器误取消，alpha会立即反向恢复")
            assertTrue(haptics.events.isEmpty(), "长按专用对象的短按不发点击触感")
            environment.advanceFor(haptics.longPressMillis + 160)
            assertEquals(1.0, contrast() / resting, .02, "正常短按最终恢复原色")
            assertFalse(environment.nodes().any {
                it.config.getOrNull(SemanticsProperties.TestTag) == "communication-context-preview"
            }, "短按不打开菜单，原long检测器继续独占真实动作")
            assertTrue(haptics.events.isEmpty())
        }
    }

    /** Measure fixed foreground samples against the real surface, excluding rounded edges/background area. */
    private fun TestScope.longOnlyContentContrast(environment: MotionTestEnvironment, bounds: Rect): () -> Double {
        val left = floor(bounds.left).toInt() + 12
        val top = floor(bounds.top).toInt() + 12
        val width = ceil(bounds.right).toInt() - 12 - left
        val height = ceil(bounds.bottom).toInt() - 12 - top
        assertTrue(width > 0 && height > 0, "原长按内容必须有可测内区")
        fun sample(): Pair<Int, IntArray> = environment.scene.render(testScheduler.currentTime * 1_000_000L).use { image ->
            Bitmap.makeFromImage(image).use { bitmap ->
                bitmap.getColor(bounds.center.x.toInt(), bounds.top.toInt() + 4) to
                    IntArray(width * height) { index -> bitmap.getColor(left + index % width, top + index / width) }
            }
        }
        val (restingSurface, original) = sample()
        val surface = original.toList().groupingBy { it }.eachCount().maxBy { it.value }
        assertTrue(surface.value > original.size / 4, "原内区须具备可识别的均匀表面")
        fun difference(pixel: Int): Int = abs((pixel ushr 16 and 255) - (surface.key ushr 16 and 255)) +
            abs((pixel ushr 8 and 255) - (surface.key ushr 8 and 255)) + abs((pixel and 255) - (surface.key and 255))
        val foreground = original.indices.filter { difference(original[it]) > 96 }
        assertTrue(foreground.size >= 32, "必须存在实际文字/图标，不能用空白表面证明P5")
        return {
            val (currentSurface, pixels) = sample()
            assertEquals(restingSurface, currentSurface, "P5只微暗内容，原空白表面不铺色也不淡化")
            foreground.sumOf { difference(pixels[it]).toDouble() }
        }
    }

    private class ToggleProbe {
        val haptics = RecordingHaptics()
        var checked by mutableStateOf(false)
        var changes = 0
        @Composable fun Managed() {
            ManagerTogglePreferenceItem(managerTogglePreference(AppearancePreferenceKey.FLOATING_NAV,
                "受管开关", "合成状态", CallPhoneIcons.Phone, checked),
                onCheckedChange = { checked = it; changes++ })
        }
    }

    private fun syntheticCallRecord(): CallRecord = requireNotNull(CallHistoryWrite.parse(CallHistoryDraft(
        "s1-haptic-call", CallDirection.INCOMING, syntheticPeer,
        CallTimesDraft(1790848800000L, 1790848801000L, 1790848861000L, 60), CallOutcome.COMPLETED,
    ))).toRecord(syntheticOwner)

    @Composable private fun LongPressSurface(content: @Composable () -> Unit) {
        Scaffold(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 128.dp)) { content() }
        }
    }

    private fun TestScope.phoneScene(haptics: RecordingHaptics, enabled: Boolean = true,
        content: @Composable () -> Unit): MotionTestEnvironment = scene(haptics, enabled, manager = false, content = content)

    private fun TestScope.managerScene(haptics: RecordingHaptics, enabled: Boolean = true,
        content: @Composable () -> Unit): MotionTestEnvironment = scene(haptics, enabled, manager = true, content = content)

    private fun TestScope.scene(haptics: RecordingHaptics, enabled: Boolean, manager: Boolean,
        content: @Composable () -> Unit): MotionTestEnvironment {
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val settings = AppearanceSettings.Default.copy(hapticsEnabled = enabled,
            floatingNavigation = false, blurRequested = false, liquidGlassRequested = false)
        return MotionTestEnvironment(testScheduler, width = 390, height = 640) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalHapticFeedback provides haptics) {
                val timeout = LocalViewConfiguration.current.longPressTimeoutMillis
                SideEffect { haptics.longPressMillis = timeout }
                SoftVoWiFiTheme(false) {
                    if (manager) AppShell(AppNavigationState.initial(AppRoute.Home), {},
                        appearanceSettings = settings, blurSupported = false) { route ->
                        if (route == AppRoute.Home) content() else Box(Modifier.fillMaxSize())
                    } else PhoneActionFeedback(settings, blurSupported = false, content = content)
                }
            }
        }
    }

    private fun MotionTestEnvironment.warm() { repeat(8) { advanceFrameBy() } }
    private fun MotionTestEnvironment.advanceFor(milliseconds: Long) {
        assertTrue(milliseconds in 1L..5000L, "使用真实ViewConfiguration的有界等待，不猜固定长按时长")
        repeat((milliseconds / 16).toInt()) { advanceFrameBy() }
        if (milliseconds % 16 != 0L) advanceFrameBy(milliseconds % 16)
    }
    private fun MotionTestEnvironment.tap(position: Offset) {
        press(position)
        advanceFrameBy(8)
        release(position)
        advanceFor(32)
    }
    private fun MotionTestEnvironment.longPressOwnedMenu(label: String, haptics: RecordingHaptics,
        verifyOwnedState: () -> Unit) {
        warm()
        assertTrue(haptics.longPressMillis > 0, "须读取真实ViewConfiguration长按时长")
        val source = nodes().single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == label }
        val bounds = source.boundsInRoot
        assertTrue(bounds.width > 0f && bounds.height > 0f && bounds.center.x in 0f..390f && bounds.center.y in 0f..640f,
            "原生产长按目标必须已测量且真实可触达")
        press(bounds.center)
        advanceFor(haptics.longPressMillis + 96)
        assertTrue(nodes().any {
            it.config.getOrNull(SemanticsProperties.TestTag) == "communication-context-preview"
        }, "原生产菜单必须在抬起前实际打开；未打开只能算复现前提失败")
        verifyOwnedState()
        val recognized = haptics.events.toList()
        release(bounds.center)
        advanceFor(64)
        assertTrue(nodes().any {
            it.config.getOrNull(SemanticsProperties.TestTag) == "communication-context-preview"
        }, "抬起不能立即关闭或重开原生产菜单")
        verifyOwnedState()
        assertEquals(recognized, haptics.events, "长按成立后的抬起不能补发点击触感")
        assertEquals(listOf(HapticFeedbackType.LongPress), recognized,
            "原生产长按触发器只发LongPress一次，不是ContextClick或平台+应用双发")
    }
    private fun MotionTestEnvironment.nodes(): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
        tree(owner.rootSemanticsNode)
    }
    private fun MotionTestEnvironment.button(label: String): SemanticsNode = nodes().single {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true &&
            it.config.contains(SemanticsActions.OnClick)
    }
    private fun MotionTestEnvironment.rowPosition(): Offset = nodes().first {
        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "受管开关" } == true
    }.boundsInRoot.center
    private fun MotionTestEnvironment.switchNode(): SemanticsNode = nodes().single {
        it.config.getOrNull(SemanticsProperties.Role) == Role.Switch &&
            it.config.contains(SemanticsProperties.ToggleableState)
    }
    private fun MotionTestEnvironment.thumbPosition(checked: Boolean): Offset {
        val bounds = switchNode().boundsInRoot
        return Offset(bounds.left + bounds.width * (if (checked) .75f else .25f), bounds.center.y)
    }
}
