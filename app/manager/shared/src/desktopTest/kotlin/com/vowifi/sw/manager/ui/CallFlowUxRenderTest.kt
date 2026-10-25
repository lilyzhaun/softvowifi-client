@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.desktop.s4SmsHost
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.testing.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import kotlin.math.min

class CallFlowUxRenderTest {
    @Test fun knownNumberDetailExposesVisibleCallbackAndCopyActions() = onMotionTestThread { runTest {
        val owner = OWNER_A
        val record = historyWrite(peer = "+12025550123", outcome = CallOutcome.REJECTED, endedAtMs = T0 + 1000).toRecord(owner)
        val history = CallHistoryState.Ready(listOf(record), owner)
        val handle = callHistoryGroups(history).single().handle
        MotionTestEnvironment(testScheduler, 420, 900) { s4SmsHost {
            CallHistoryDetailScreen(handle, history, { _, _ -> })
        } }.use { e ->
            s4Frames(e, 30)
            assertTrue(s4HasText(e, "1 次通话 · 当前线路本机记录"), "前提：真实详情号码组已呈现")
            assertTrue(s4HasText(e, "回拨"), "已知号码详情须显示回拨，即使当前不可用也解释原因")
            assertTrue(s4HasText(e, "复制号码"))
        }
    } }

    @Test fun formattedPasteUsesTheRealEditorAndLeavesTheCursorAfterInsertedDigits() = onMotionTestThread { runTest {
        var number by mutableStateOf("99")
        MotionTestEnvironment(testScheduler, 420, 1000) { s4SmsHost {
            PhoneDialerScreen(number, { number = it }, "合成线路", true, false, null, {}, {}, {})
        } }.use { e ->
            s4Frames(e, 10)
            fun editor() = s4Nodes(e).single { it.config.contains(SemanticsActions.SetText) }
            assertTrue(editor().config[SemanticsActions.SetText].action!!(AnnotatedString("9 (555) 123-4567 9")))
            s4Frames(e, 2)
            assertEquals("955512345679", number)
            assertEquals(TextRange(number.length), editor().config[SemanticsProperties.TextSelectionRange])
            editor().config[SemanticsActions.SetText].action!!(AnnotatedString("bad123")); s4Frames(e, 2)
            assertEquals("955512345679", number)
            assertTrue(s4HasText(e, "请输入电话号码，暂不支持 USSD"), "拒绝输入也须在既有提示位说明")
        }
    } }

    @Test fun firstHistoryReadDoesNotFlashBefore150ms() = onMotionTestThread { runTest {
        var history by mutableStateOf<CallHistoryState>(CallHistoryState.Loading)
        MotionTestEnvironment(testScheduler, 420, 900) { s4SmsHost {
            CallsScreen(idle(), true, {}, history, {}, {}, {}, {}, {})
        } }.use { e ->
            e.advanceFrameBy(1); e.advanceFrameBy(100)
            assertFalse(s4HasText(e, "正在读取通话记录"), "L1：150ms内不得闪加载面板")
            history = CallHistoryState.Empty; repeat(4) { e.advanceFrameBy(0) }; s4Frames(e, 20)
            assertFalse(s4HasText(e, "正在读取通话记录"))
            assertTrue(s4HasText(e, "暂无通话记录"))
        }
    } }

    @Test fun slowFirstReadHoldsLoading300msAndFadesInTheRealContentIncludingZeroMotion() = onMotionTestThread { runTest {
        for (scale in listOf(1f, 0f)) {
            var history by mutableStateOf<CallHistoryState>(CallHistoryState.Loading)
            MotionTestEnvironment(testScheduler, 420, 900, scale) { s4SmsHost {
                CallsScreen(idle(), true, {}, history, {}, {}, {}, {}, {})
            } }.use { e ->
                advance(e, 1); advance(e, 148)
                assertFalse(s4HasText(e, "正在读取通话记录"))
                advance(e, 2)
                assertTrue(s4HasText(e, "正在读取通话记录"), "150ms后慢首读应实际显示加载")
                history = CallHistoryState.Empty; repeat(4) { e.advanceFrameBy(0) }
                advance(e, 298)
                assertTrue(s4HasText(e, "正在读取通话记录"), "显示后不足300ms，不能提前揭露内容")
                advance(e, 2)
                if (scale != 0f) assertTrue(s4HasText(e, "正在读取通话记录"), "正常动效仍须保留退出阶段")
                advance(e, 120)
                assertFalse(s4HasText(e, "正在读取通话记录"))
                assertTrue(s4HasText(e, "暂无通话记录"))
                val bounds = s3TextBounds(s4Nodes(e), "暂无通话记录")
                val region = Rect(bounds.left, bounds.top, min(bounds.right, bounds.left + 80f), min(bounds.bottom, bounds.top + 48f))
                val entering = s3SmallPatch(e, testScheduler, region)
                advance(e, 240)
                val settled = s3SmallPatch(e, testScheduler, region)
                assertTrue(settled.pixels.count { s3RgbDistance(it, settled.pixels.first()) > 8 } >= 8,
                    "终态采样必须包含实际标题字形，不能比较空白")
                if (scale != 0f) assertTrue(s4Difference(entering, settled) > 0, "内容DurBase淡入须有真实字形中帧")
                else assertEquals(0, s4Difference(entering, settled), "零动画无残留淡入")
            }
        }
    } }

    @Test fun loadingTheNextOwnerKeepsTheOldDrawingButRevokesItsActionsBeforeReplacement() = onMotionTestThread { runTest {
        val oldHistory = CallHistoryState.Ready(listOf(historyWrite(peer = "155501", endedAtMs = T0 + 1000,
            outcome = CallOutcome.REJECTED).toRecord(OWNER_A)), OWNER_A)
        var history by mutableStateOf<CallHistoryState>(oldHistory)
        var opens = 0; var callbacks = 0
        MotionTestEnvironment(testScheduler, 420, 900) { s4SmsHost {
            CallsScreen(idle(), true, {}, history, {}, {}, {}, {}, {}, onOpenHistory = { opens++ },
                callbackEnabled = true, onCallback = { callbacks++ })
        } }.use { e ->
            s4Frames(e, 40)
            val old = s4Nodes(e).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "通话记录操作" }
            val oldClick = old.config[SemanticsActions.OnClick].action!!
            val oldCallback = old.config[SemanticsActions.CustomActions].single { it.label == "回拨" }.action
            val region = s3TextBounds(s4Nodes(e), "155501")
            val baseline = s3SmallPatch(e, testScheduler, region)
            assertTrue(baseline.pixels.distinct().size > 2, "采样前提：旧号码有实际字形")
            history = CallHistoryState.Loading
            repeat(6) { e.advanceFrameBy(0) }
            oldClick(); assertFalse(oldCallback())
            assertEquals(0, opens); assertEquals(0, callbacks)
            for (time in listOf(0L, 16L, 32L)) {
                advance(e, time)
                assertFalse(s4HasText(e, "正在读取通话记录"))
                assertEquals(0, s4Difference(baseline, s3SmallPatch(e, testScheduler, region)), "切线等待读取不能闪旧内容")
            }
            history = CallHistoryState.Ready(listOf(historyWrite(peer = "155502", endedAtMs = T0 + 1000,
                outcome = CallOutcome.REJECTED).toRecord(OWNER_B)), OWNER_B)
            s4Frames(e, 30)
            assertTrue(s4HasText(e, "155502")); assertFalse(s4HasText(e, "155501"))
            assertEquals(0, opens); assertEquals(0, callbacks)
        }
    } }

    @Test fun bothRootSearchActionsHideKeyboardWithoutLosingTheQueryOrClosingSearch() = onMotionTestThread { runTest {
        for (title in listOf("通话记录", "短信")) {
            var query by mutableStateOf("123")
            var closes = 0; var hides = 0
            val keyboard = object : SoftwareKeyboardController { override fun show() = Unit; override fun hide() { hides++ } }
            MotionTestEnvironment(testScheduler, 420, 900) { s4SmsHost {
                CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                    CommunicationSearchHeader(title, "搜索号码", true, query, {}, { query = it }, { closes++ })
                }
            } }.use { e ->
                s4Frames(e, 30)
                val editor = s4Nodes(e).single { it.config.contains(SemanticsActions.SetText) }
                assertEquals(ImeAction.Search, editor.config[SemanticsProperties.ImeAction])
                val before = hides
                assertTrue(editor.config[SemanticsActions.OnImeAction].action!!())
                s4Frames(e, 2)
                assertTrue(hides > before)
                assertEquals("123", query); assertEquals(0, closes)
                assertEquals(1, s4Nodes(e).count { it.config.contains(SemanticsActions.SetText) })
            }
        }
    } }

    private fun idle() = CallUiState.Ready(CallPhase.Idle, CallAudioState.Idle, CallOrigin.ENGINE)
    private fun advance(e: MotionTestEnvironment, millis: Long) {
        repeat((millis / 16).toInt()) { e.advanceFrameBy(16) }
        e.advanceFrameBy(millis % 16)
    }
}
