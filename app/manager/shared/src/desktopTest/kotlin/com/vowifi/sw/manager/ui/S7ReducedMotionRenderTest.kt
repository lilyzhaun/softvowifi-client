@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.vowifi.sw.manager.desktop.s4SmsHost
import com.vowifi.sw.manager.data.OWNER_A
import com.vowifi.sw.manager.data.MessageRemarkSaveResult
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class S7ReducedMotionRenderTest {
    /** 同一真实scene内1→0；只证明按压/T7及其真实挂起回调，不冒充所有场景或Android设置实测。 */
    @Test fun dynamicZeroConvergesVisiblePressAndConfirmationWithoutFinishingHeldBusinessOrReplayingInput() = onMotionTestThread {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher, timeout = 20.seconds) {
            var factor by mutableFloatStateOf(1f)
            val scale = object : MotionDurationScale { override val scaleFactor get() = factor }
            val back = object : NavigationEventDispatcherOwner { override val navigationEventDispatcher = NavigationEventDispatcher() }
            val window = miuixConfirmationTestWindow(390, 844)
            val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
            var requested by mutableStateOf(false); var busy by mutableStateOf(false)
            var pressClicks = 0; var confirms = 0; var cancels = 0; var starts = 0; var completions = 0
            var inputMode: InputModeManager? = null
            var observedScale: MotionDurationScale? = null
            try {
                MotionTestEnvironment(testScheduler, 390, 844, dispatcher = dispatcher, motionScale = scale) {
                    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides back, LocalWindowInfo provides window,
                        LocalDensity provides Density(1f, 1f)) {
                        s4SmsHost {
                            val platformMode = LocalInputModeManager.current
                            SideEffect { inputMode = platformMode }
                            val scope = rememberCoroutineScope()
                            SideEffect { observedScale = scope.coroutineContext[MotionDurationScale] }
                            val startRequest: () -> Unit = {
                                starts++; busy = true
                                scope.launch { entered.complete(Unit); release.await(); completions++; busy = false }
                                Unit
                            }
                            Column(Modifier.fillMaxSize().padding(24.dp)) {
                                ManagerTextAction("合成填色按钮宽度测量", ManagerAction.SaveConfiguration, { pressClicks++ })
                                CommunicationTextAction("打开动态确认", { requested = true }, enabled = !busy)
                                ManagerTextAction("原挂起请求", ManagerAction.SaveConfiguration, startRequest, busy = busy)
                            }
                            if (requested) CommunicationConfirmation("动态缩放确认", "仅验证原宿主交接", "确认原请求",
                                { cancels++; requested = false }, { confirms++; requested = false; startRequest() })
                        }
                    }
                }.use { e ->
                    fun consumeNow() {
                        val at = testScheduler.currentTime
                        repeat(12) {
                            androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                            testScheduler.runCurrent(); e.advanceFrameBy(0)
                        }
                        assertEquals(at, testScheduler.currentTime, "零动画收敛泵不得偷推进时钟")
                    }
                    fun dialog() = s4Nodes(e, false).singleOrNull {
                        it.config.contains(SemanticsProperties.IsDialog) && s4Matches(it, "动态缩放确认")
                    }
                    fun patch(region: Rect) = s3SmallPatch(e, testScheduler, region)
                    s4Frames(e, 24)
                    // Desktop pointer injection does not switch the platform input mode itself.
                    assertTrue(assertNotNull(inputMode).requestInputMode(InputMode.Touch)); consumeNow()
                    assertEquals(1f, scale.scaleFactor)
                    val button = s4Action(e, "合成填色按钮宽度测量").boundsInRoot
                    assertTrue(button.width >= 120f && button.height >= 48f)
                    val edge = Rect(button.left, button.center.y - 8f, button.left + 12f, button.center.y + 8f)
                    val rest = patch(edge)
                    e.press(button.center); consumeNow(); e.advanceFrameBy(32); consumeNow()
                    assertEquals(0, pressClicks)
                    assertEquals(button, s4Action(e, "合成填色按钮宽度测量").boundsInRoot)
                    assertTrue(s4Difference(rest, patch(edge)) >= 4,
                        "正向前提：scale1真实held按压须已经绘出填色表面变化，不能测空白或零初始化")
                    val pressZeroAt = testScheduler.currentTime
                    factor = 0f; consumeNow(); e.release(button.center); consumeNow()
                    assertEquals(pressZeroAt, testScheduler.currentTime)
                    assertEquals(1, pressClicks, "真实release业务不等待动画或90ms最短可见")
                    assertContentEquals(rest.pixels, patch(edge).pixels,
                        "1→0后按压绘制须回到同一normal rest，无残留tint/scale")
                    assertEquals(button, s4Action(e, "合成填色按钮宽度测量").boundsInRoot)

                    factor = 1f; consumeNow()
                    val pendingPoint = s4Action(e, "原挂起请求").boundsInRoot.center
                    val dimRegion = Rect(8f, 400f, 24f, 416f)
                    val panelRegion = Rect(187f, 780f, 203f, 796f)
                    val clear = patch(dimRegion); val clearPanel = patch(panelRegion)
                    s4Tap(e, s4Action(e, "打开动态确认").boundsInRoot.center); consumeNow()
                    s4Frames(e, 60); consumeNow()
                    val initial = assertNotNull(dialog(), "前提：官方OverlayDialog须已呈现").boundsInRoot
                    fun titlePosition() = s4Nodes(e).single {
                        it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == "动态缩放确认"
                    }.positionInRoot
                    val initialTitle = titlePosition()
                    val dimmed = patch(dimRegion)
                    assertTrue(initial.left > 0f && initial.right < 390f && initial.top > 422f)
                    assertTrue(s4Difference(clear, dimmed) >= 4, "正向前提：原宿主确实绘出遮罩")
                    val confirmPoint = s4Action(e, "确认原请求").boundsInRoot.center
                    s4Tap(e, confirmPoint); consumeNow()
                    val exitAt = testScheduler.currentTime
                    assertEquals(0, confirms); assertEquals(0, starts); assertFalse(entered.isCompleted)
                    e.advanceFrameBy(16); consumeNow()
                    val exiting = assertNotNull(dialog(), "scale1的原退出宿主必须仍在").boundsInRoot
                    val movingTitle = titlePosition()
                    assertTrue(movingTitle.y > initialTitle.y && movingTitle.y < 844f && movingTitle.x == initialTitle.x,
                        "正向前提：官方手机退出有可见向下位移，不测layer外的固定语义壳：initial=$initialTitle moving=$movingTitle host=$exiting")
                    val middle = patch(dimRegion)
                    assertTrue(s4Difference(clear, middle) >= 4 && s4Difference(dimmed, middle) >= 4,
                        "正向前提：遮罩退出须有真实中间像素")
                    assertEquals(0, confirms); assertEquals(0, starts)
                    assertEquals(exitAt + 16L, testScheduler.currentTime)

                    val zeroAt = testScheduler.currentTime
                    factor = 0f; consumeNow()
                    assertEquals(0f, assertNotNull(observedScale, "实际popup调用组合必须收到平台尺度").scaleFactor)
                    assertEquals(zeroAt, testScheduler.currentTime, "动态0须在下一有效同刻帧序列收敛，不等剩余官方退场")
                    assertNull(dialog(), "动态0后原T7宿主须真正卸载，不只透明化")
                    assertTrue(s4Actions(e, "确认原请求").isEmpty(), "旧确认入口不得残留命中语义")
                    assertContentEquals(clear.pixels, patch(dimRegion).pixels, "动态0后无遮罩残留")
                    assertContentEquals(clearPanel.pixels, patch(panelRegion).pixels, "动态0后底部原宿主绘制完全回收")
                    assertEquals(1, confirms); assertEquals(0, cancels); assertEquals(1, starts)
                    assertTrue(entered.isCompleted, "确认完成须实际进入原挂起handler")
                    assertFalse(release.isCompleted); assertTrue(busy); assertEquals(0, completions,
                        "移除动画不能替真实pending完成业务")
                    val pending = assertNotNull(s4Nodes(e, false).singleOrNull { s4Matches(it, "原挂起请求") &&
                        it.config.contains(SemanticsActions.OnClick) }, "真实busy控件须仍在同一场景")
                    assertTrue(pending.config.contains(SemanticsProperties.Disabled))
                    assertTrue(s4Nodes(e).any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
                    // 指针打到原确认位置与真实busy位置，不调用保存的onConfirm或注入Interaction。
                    e.press(confirmPoint); e.release(confirmPoint); consumeNow()
                    e.press(pendingPoint); e.release(pendingPoint); consumeNow()
                    assertEquals(1, confirms); assertEquals(1, starts); assertEquals(0, completions); assertTrue(busy)
                    e.advanceFrameBy(MotionTokens.DurFast.toLong()); consumeNow()
                    assertEquals(1, confirms); assertEquals(1, starts); assertEquals(0, completions)
                    assertNull(dialog()); assertContentEquals(clear.pixels, patch(dimRegion).pixels)
                    release.complete(Unit); consumeNow()
                    assertFalse(busy); assertEquals(1, completions); assertEquals(1, starts); assertEquals(1, confirms)
                    assertEquals(1, pressClicks, "T7/zero/旧位置指针不得重放先前按压业务")
                }
            } finally { release.complete(Unit); testScheduler.runCurrent(); back.navigationEventDispatcher.dispose() }
        }
    }

    /** 唯一真实Popup host换尺度时的编辑保全；不是Android IME或TalkBack验收。 */
    @Test fun dynamicPopupHostScaleChangePreservesRemarkEditorTextSelectionFocusAndExactlyOneCancel() = onMotionTestThread {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher, timeout = 20.seconds) {
            var factor by mutableFloatStateOf(1f)
            val scale = object : MotionDurationScale { override val scaleFactor get() = factor }
            val editor = MessageRemarkEditor(OWNER_A, "原合成备注", 1, "SIM 1 · 合成线路")
            var shown by mutableStateOf<MessageRemarkEditor?>(editor)
            var presented: MessageRemarkEditor? = null; var submits = 0; var dismisses = 0
            val back = object : NavigationEventDispatcherOwner { override val navigationEventDispatcher = NavigationEventDispatcher() }
            try {
                MotionTestEnvironment(testScheduler, 440, 1800, dispatcher = dispatcher, motionScale = scale) {
                    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides back, LocalDensity provides Density(1f, 1f)) {
                        s4SmsHost { shown?.let { original ->
                            SideEffect { presented = original }
                            MessageRemarkDialog(original, { dismisses++; shown = null }) { _, _ ->
                                submits++; MessageRemarkSaveResult.Failed
                            }
                        } }
                    }
                }.use { e ->
                    fun consumeNow() {
                        val at = testScheduler.currentTime
                        repeat(6) { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications(); testScheduler.runCurrent(); e.advanceFrameBy(0) }
                        assertEquals(at, testScheduler.currentTime)
                    }
                    fun fields() = s4Nodes(e, false).filter { it.config.contains(SemanticsActions.SetText) }
                    fun titles() = s4Nodes(e).filter { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "线路备注" } == true }
                    s4Frames(e, 113) // 正常1倍完整sheet先稳定；不是以零初始化掩盖重建。
                    assertEquals(1f, scale.scaleFactor); assertEquals(1, titles().size)
                    val input = assertNotNull(fields().singleOrNull(), "前提：真实备注输入须唯一可达")
                    assertTrue(assertNotNull(input.config[SemanticsActions.RequestFocus].action).invoke())
                    val text = "synthetic remark 0710"
                    assertTrue(assertNotNull(input.config[SemanticsActions.SetText].action).invoke(AnnotatedString(text)))
                    consumeNow()
                    val edited = assertNotNull(fields().singleOrNull())
                    assertTrue(assertNotNull(edited.config[SemanticsActions.SetSelection].action).invoke(3, 8, false)); consumeNow()
                    val focused = assertNotNull(fields().singleOrNull())
                    assertEquals(text, editor.input); assertEquals(TextRange(3, 8), focused.config[SemanticsProperties.TextSelectionRange])
                    assertEquals(true, focused.config.getOrNull(SemanticsProperties.Focused), "前提：输入真实聚焦")
                    assertTrue(focused.boundsInRoot.top >= 0 && focused.boundsInRoot.bottom <= 1800 && focused.boundsInRoot.width >= 48)
                    val owner = editor.owner; val error = editor.error; val busy = editor.busy
                    assertFalse(busy); assertNull(error); assertEquals(0, submits); assertEquals(0, dismisses)
                    val failures = mutableListOf<String>()
                    fun preserve(phase: String) {
                        if (presented !== editor || shown !== editor) failures += "$phase：外部editor引用丢失"
                        if (editor.owner != owner || editor.input != text || editor.error != error || editor.busy != busy)
                            failures += "$phase：editor owner/input/error/busy改变"
                        if (titles().size != 1) failures += "$phase：原sheet不再唯一可见"
                        val field = fields().singleOrNull()
                        if (field == null) failures += "$phase：真实备注输入丢失或重复" else {
                            if (field.config[SemanticsProperties.EditableText].text != text) failures += "$phase：输入文字丢失"
                            if (field.config.getOrNull(SemanticsProperties.TextSelectionRange) != TextRange(3, 8)) failures += "$phase：光标/选区丢失"
                            if (field.config.getOrNull(SemanticsProperties.Focused) != true) failures += "$phase：真实焦点丢失"
                            if (field.config.contains(SemanticsProperties.Disabled) || field.boundsInRoot.height <= 0 ||
                                field.boundsInRoot.top < 0 || field.boundsInRoot.bottom > 1800) failures += "$phase：输入不可达"
                        }
                        assertEquals(0, submits, "换尺度不能保存或清空备注"); assertEquals(0, dismisses, "换尺度不能取消原sheet")
                    }
                    val zeroAt = testScheduler.currentTime
                    factor = 0f; consumeNow(); assertEquals(zeroAt, testScheduler.currentTime); preserve("1→0同刻")
                    factor = 1f; consumeNow(); s4Frames(e, 113); preserve("0→1正常稳定")
                    // 不补写input/selection/焦点；仍从原生产取消入口真实pointer退出。
                    val cancel = assertNotNull(s4Actions(e, "取消").distinctBy { it.id }.singleOrNull {
                        !it.config.contains(SemanticsProperties.Disabled) &&
                            it.boundsInRoot.width in 48f..220f && it.boundsInRoot.height in 48f..120f
                    }, "原sheet实际取消控件须唯一，不能把整个可点击面板当按钮；已观测保全缺口=$failures")
                    s4Tap(e, cancel.boundsInRoot.center); consumeNow()
                    assertEquals(0, dismisses, "原sheet须先退场，不能点取消就释放editor")
                    s4Frames(e, 113); consumeNow()
                    assertEquals(1, dismisses); assertNull(shown); assertTrue(titles().isEmpty()); assertTrue(fields().isEmpty())
                    assertEquals(text, editor.input); assertEquals(owner, editor.owner); assertEquals(error, editor.error)
                    assertEquals(busy, editor.busy); assertEquals(0, submits)
                    s4Frames(e, 24); assertEquals(1, dismisses, "原取消只交接一次")
                    assertTrue(failures.isEmpty(), failures.joinToString("\n"))
                }
            } finally { back.navigationEventDispatcher.dispose() }
        }
    }
}
