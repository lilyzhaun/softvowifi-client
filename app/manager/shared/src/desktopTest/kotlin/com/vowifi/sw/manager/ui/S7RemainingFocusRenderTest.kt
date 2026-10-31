@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.desktop.s4SmsHost
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import java.awt.Canvas
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** 真实生产控件、真实Tab及小范围绘制；不注入FocusInteraction，不执行Enter业务。 */
class S7RemainingFocusRenderTest {
    @Test fun fiveRemainingOwnersExposeTwoDpKeyboardInkAndWithdrawForTouchAndInactiveRoutes() = onMotionTestThread { runTest {
        val failures = mutableListOf<String>()
        for (kind in 0..4) for (scale in listOf(1f, 0f)) {
            var active by mutableStateOf(true); var calls = 0; var accent = 0
            var mode: InputModeManager? = null
            val haptics = mutableListOf<HapticFeedbackType>()
            val recorder = object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
            }
            val e = MotionTestEnvironment(testScheduler, 390, 900, scale) {
                s4SmsHost {
                    val manager = LocalInputModeManager.current
                    // Emphasized filled faces need their on-accent ink; accent on accent is invisible.
                    val ink = when (kind) { 1 -> top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onPrimary; 3 -> communicationColors().onAccent; 4 -> phoneColors().accent
                        else -> LocalSoftVoWiFiColors.current.interactive }
                    SideEffect { mode = manager; accent = ink.toArgb() }
                    CompositionLocalProvider(LocalDensity provides Density(1f), LocalAppRouteActive provides active,
                        LocalHapticFeedback provides recorder) {
                        Column(Modifier.fillMaxSize().padding(24.dp)) {
                            when (kind) {
                                0 -> LogEntryRow(OperationalLogEntry("10:00:00", OperationalLogLevel.INFO,
                                    OperationalLogKind.ENGINE, "合成实例", OperationalLogMessage.ENGINE_STARTED, "合成焦点日志"))
                                1 -> LogExportButton(logExportPresentation(true, false), { calls++ })
                                2 -> StatePanel(StatePanelSpec(CallPhoneIcons.Phone, StatePanelTone.INTERACTIVE,
                                    "合成状态", "仅验证原恢复入口", retryDescription = "合成重试"), onRetry = { calls++ })
                                3 -> MessageComposer("合成草稿", MessageComposeSpec(MessageComposeMode.ENABLED, null),
                                    MessageImeOwner.COMPOSE_COMPOSER_PADDING, {}, false, true, { calls++ }, { calls++ })
                                else -> PhoneNavigation("合成返回", { calls++ }, CallPhoneIcons.Back, showText = true)
                            }
                        }
                    }
                }
            }
            try {
                repeat(24) { e.advanceFrameBy() }
                fun owner(): SemanticsNode = s4Nodes(e, false).filter { it.config.contains(SemanticsActions.OnClick) }.single {
                    when (kind) {
                        0 -> it.config[SemanticsActions.OnClick].label == "查看日志详情"
                        1 -> s4Matches(it, "导出日志")
                        2 -> s4Matches(it, "合成重试")
                        3 -> it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("发送短信") == true
                        else -> s4Matches(it, "合成返回")
                    }
                }
                val bounds = owner().boundsInRoot
                assertTrue(bounds.width >= 48f && bounds.height >= 48f && bounds.top >= 1f && bounds.bottom < 900f,
                    "前提：900px真实Scene中的原动作必须完整可达，kind=$kind bounds=$bounds")
                failures += s7RemainingFocusCheck(e, testScheduler, assertNotNull(mode), accent,
                    "kind=$kind scale=$scale", ::owner, { active = false }, reverse = kind == 3, curved = kind == 3)
                assertEquals(0, calls, "Tab/Touch/失活不能触发发送、导出或恢复")
                assertTrue(haptics.isEmpty())
                if (kind == 0) assertEquals(1, s4Nodes(e, false).count { it.config.contains(SemanticsActions.OnClick) },
                    "日志详情不能被键盘遍历打开")
            } finally { e.close() }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    } }
}

/** 只沿公开语义树映射同一action的实际focus叶，不把旁边文字或root当作Focused。 */
internal fun s7SameOwnerFocused(e: MotionTestEnvironment, owner: SemanticsNode): Boolean = s4Nodes(e, true).any { leaf ->
    leaf.config.getOrNull(SemanticsProperties.Focused) == true &&
        (generateSequence(leaf) { it.parent }.any { it.id == owner.id } ||
            leaf.boundsInRoot == owner.boundsInRoot && generateSequence(owner) { it.parent }.any { it.id == leaf.id })
}

internal fun s7RemainingFocusCheck(e: MotionTestEnvironment, scheduler: TestCoroutineScheduler,
    mode: InputModeManager, accent: Int, context: String, owner: () -> SemanticsNode,
    deactivate: () -> Unit, reverse: Boolean = false, curved: Boolean = false): List<String> {
    val failures = mutableListOf<String>(); val keyboard = Canvas()
    fun consume() { repeat(4) { e.advanceFrameBy(0) } }
    fun tab() {
        // Reverse traversal reaches Send without treating native multiline Tab editing as navigation.
        for (down in listOf(true, false)) e.scene.sendKeyEvent(KeyEvent(Key.Tab,
            if (down) KeyEventType.KeyDown else KeyEventType.KeyUp, isShiftPressed = reverse, nativeEvent = AwtKeyEvent(keyboard,
                if (down) AwtKeyEvent.KEY_PRESSED else AwtKeyEvent.KEY_RELEASED,
                scheduler.currentTime, if (reverse) AwtKeyEvent.SHIFT_DOWN_MASK else 0, AwtKeyEvent.VK_TAB, AwtKeyEvent.CHAR_UNDEFINED)))
        consume()
    }
    fun reach() {
        tab(); repeat(31) { if (!s7SameOwnerFocused(e, owner())) tab() }
        assertTrue(s7SameOwnerFocused(e, owner()), "前提：真实Tab须到达同一action的focus叶：$context")
        assertEquals(InputMode.Keyboard, mode.inputMode)
    }
    consume(); val bounds = owner().boundsInRoot
    assertFalse(s7SameOwnerFocused(e, owner()), "前提：不能预置Focus：$context")
    assertEquals(kotlin.math.floor(bounds.top), bounds.top, "前提：Density1顶边须像素对齐：$context")
    val left = kotlin.math.floor(bounds.center.x) - 2f
    val region = Rect(left, bounds.top - 1f, left + 4f, bounds.top + 4f)
    fun patch() = s3SmallPatch(e, scheduler, region)
    fun ink(p: S3SmallPatch) = p.pixels.count { s3RgbDistance(it, accent) <= 8 }
    val before = patch(); assertEquals(4 to 5, before.width to before.height)
    reach(); repeat(16) { e.advanceFrameBy() }; consume()
    assertEquals(bounds, owner().boundsInRoot, "焦点不能改变命中矩形：$context")
    val focused = patch()
    // A circle's tangent/inner edge is antialiased; unlike a rectangular edge it cannot fill
    // two whole pixel rows. The shared 2dp path is checked on flat edges by the strict branch.
    if (if (curved) ink(focused) < 2 else (1..2).any { y -> (0..3).none { x -> s3RgbDistance(focused.pixels[y * 4 + x], accent) <= 8 } })
        failures += "$context：真实Focused后缺顶边内连续2dp强调色"
    if (s4Difference(before, focused) < 2) failures += "$context：焦点须产生可见新描边，同色原填充不能冒充焦点环"
    if ((if (curved) listOf(0, 4) else listOf(0, 3, 4)).any { y -> (0..3).any { x -> s3RgbDistance(focused.pixels[y * 4 + x], before.pixels[y * 4 + x]) > 2 } })
        failures += "$context：2dp外沿改变，不能以粗环代替"
    val blank = Offset(4f, 4f); assertFalse(bounds.contains(blank))
    e.press(blank); e.release(blank)
    assertTrue(mode.requestInputMode(InputMode.Touch)); consume()
    if (s4Difference(before, patch()) != 0) failures += "$context：Touch模式未撤环"
    reach(); deactivate(); consume()
    val inactiveKeyboard = patch()
    // Disabling a real Send/entry can also change its confirmed availability colors. Compare
    // Keyboard/Touch at that same inactive business state, not against the old enabled face.
    assertTrue(mode.requestInputMode(InputMode.Touch)); consume()
    if (s4Difference(inactiveKeyboard, patch()) != 0) failures += "$context：route失活未立即撤环"
    return failures
}
