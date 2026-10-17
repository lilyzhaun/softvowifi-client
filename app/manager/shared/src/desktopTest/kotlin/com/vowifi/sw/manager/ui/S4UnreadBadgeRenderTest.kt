@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.MessageUnreadSummary
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.runTest
import kotlin.math.sqrt
import kotlin.test.*

/** 三个真实调用点；已确认快照可控，不把 UI 夹具称为 Room 已读提交。 */
class S4UnreadBadgeRenderTest {
    @Test fun realRowAndBothBottomBarsDrawPopRollAndExitWithoutInventingZeroForUnknown() = onMotionTestThread { runTest {
        for (host in 0..2) for (scale in listOf(1f, 0f)) {
            val count = mutableIntStateOf(1)
            val summary = mutableStateOf<MessageUnreadSummary>(MessageUnreadSummary.Ready(1))
            val font = mutableStateOf(1f)
            var fill = 0; var foreground = 0
            val environment = MotionTestEnvironment(testScheduler, 390, 300, scale) {
                SoftVoWiFiTheme(false) {
                    val palette = LocalSoftVoWiFiColors.current; val communication = communicationColors()
                    SideEffect {
                        fill = (if (host == 0) communication.accent else palette.interactive).toArgb()
                        foreground = (if (host == 0) communication.onAccent else palette.onCommunicationAccent).toArgb()
                    }
                    CompositionLocalProvider(LocalDensity provides Density(1f, font.value), LocalMessageUnreadSummary provides summary.value) {
                        Box(Modifier.fillMaxSize().background(palette.canvas)) {
                            if (host == 0) ConversationRow(ConversationRowPresentation("合成会话", "合", "本地快照", "刚刚", null), {},
                                Modifier.fillMaxWidth().padding(16.dp), unreadCount = count.intValue)
                            else BottomNavigationBar(AppDestination.HOME, {}, appChromeSpec(AppearanceSettings.Default.copy(
                                floatingNavigation = host == 2, blurRequested = false, liquidGlassRequested = false), false), null,
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                        }
                    }
                }
            }
            try {
                fun publish(value: Int) { count.intValue = value; summary.value = MessageUnreadSummary.Ready(value.toLong()); environment.advanceFrameBy(0) }
                fun badge(description: String) = s4Nodes(environment).filter {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
                }.minBy { it.boundsInRoot.width * it.boundsInRoot.height }
                fun current(value: Int) = badge("$value 条未读短信").boundsInRoot
                fun hasCurrent(value: Int) = s4Nodes(environment).any {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("$value 条未读短信") == true
                }
                // 先校准实际 badge 的位置，再充分绘出 0；不把首次 composition 当业务入场。
                s4Frames(environment, 40)
                val bounds = current(1)
                val region = Rect(bounds.left - 2f, bounds.top - 2f, bounds.right + 2f, bounds.bottom + 2f)
                publish(0); s4Frames(environment, 40)
                assertFalse(hasCurrent(1)); assertFalse(hasCurrent(0))
                val zero = s3SmallPatch(environment, testScheduler, region)
                publish(1); s4Frames(environment, 2)
                val pop = s3SmallPatch(environment, testScheduler, region)
                s4Frames(environment, 40)
                assertEquals(bounds, current(1), "采样前提：真实 badge 的终态矩形稳定且没有裁剪")
                val one = s3SmallPatch(environment, testScheduler, region)
                assertTrue(s4Difference(zero, one) >= 4, "采样前提：实际 badge 必须绘出，不能比较两张空图")
                if (scale == 0f) assertEquals(0, s4Difference(pop, one)) else {
                    assertTrue(s4Difference(pop, zero) >= 2 && s4Difference(pop, one) >= 2, "C6：0→1 必须存在 pop 中间帧")
                    val early = s4InkSpan(zero, pop); val full = s4InkSpan(zero, one)
                    assertTrue(early.first < full.first && early.second < full.second, "pop 必须缩放而非只有透明度")
                }
                publish(2); s4Frames(environment, 2)
                val roll = s3SmallPatch(environment, testScheduler, region)
                assertFalse(hasCurrent(1)); assertTrue(hasCurrent(2), "只把真实当前数暴露给无障碍")
                s4Frames(environment, 40)
                assertEquals(bounds, current(2), "采样前提：1/2 字形的实际 badge 终态须等宽")
                val two = s3SmallPatch(environment, testScheduler, region)
                fun coverage(patch: S3SmallPatch, index: Int) = s3Coverage(patch.pixels[index], fill, foreground)
                val newGlyph = two.pixels.indices.filter { coverage(two, it) > .7f && coverage(one, it) < .03f }
                assertTrue(newGlyph.size >= 2 && s4Difference(one, two) >= 4, "采样前提：实际1/2字形必须可区分")
                if (scale == 0f) assertEquals(0, s4Difference(roll, two)) else {
                    assertTrue(s4Difference(roll, one) >= 2 && s4Difference(roll, two) >= 2)
                    val moved = newGlyph.count { index -> (-8..8).filter { it != 0 }.any { dy ->
                        val movedIndex = index + dy * two.width
                        movedIndex in two.pixels.indices && coverage(one, movedIndex) < .03f && coverage(two, movedIndex) < .03f &&
                            coverage(roll, movedIndex) > .1f
                    } }
                    assertTrue(moved >= 2, "C6：新字形在垂直偏移处须有墨迹，纯原位 crossfade 不算数字滚动")
                }
                val exitingNode = badge("2 条未读短信").id
                val exitAt = testScheduler.currentTime
                publish(0)
                println("S4_BADGE_FRAME|host=$host|scale=$scale|elapsed=0|original=$bounds|region=$region|retainedSemanticBounds=" +
                    s4Nodes(environment).singleOrNull { it.id == exitingNode }?.boundsInRoot)
                repeat(2) {
                    environment.advanceFrameBy()
                    println("S4_BADGE_FRAME|host=$host|scale=$scale|elapsed=${testScheduler.currentTime - exitAt}|retainedSemanticBounds=" +
                        s4Nodes(environment).singleOrNull { it.id == exitingNode }?.boundsInRoot)
                }
                val exit = s3SmallPatch(environment, testScheduler, region)
                val exitBounds = s4Nodes(environment).singleOrNull { it.id == exitingNode }?.boundsInRoot
                assertFalse(hasCurrent(2), "退出绘制不能仍声明旧数量为当前未读")
                s4Frames(environment, 40)
                val gone = s3SmallPatch(environment, testScheduler, region)
                assertEquals(0, s4Difference(zero, gone))
                if (scale == 0f) assertEquals(0, s4Difference(exit, gone)) else {
                    assertTrue(s4Difference(exit, two) >= 2 && s4Difference(exit, gone) >= 2, "2→0 保留实际退场中间绘制")
                    val shrinking = s4InkSpan(zero, exit); val full = s4InkSpan(zero, two)
                    println("S4_BADGE_EXIT|host=$host|scale=$scale|sampleElapsed=32|region=$region|shrinking=$shrinking|full=$full|toFull=${s4Difference(exit, two)}|toGone=${s4Difference(exit, gone)}")
                    val layer = assertNotNull(exitBounds, "采样前提：t32 的实际退场层仍保留")
                    assertTrue(layer.width < bounds.width && layer.height < bounds.height,
                        "几何交叉前提：实际层双轴缩小，像素结论不能由语义代替")
                    // 真实回执：原层21×20，t32边界双轴变小；低阈AA span为19×20，整数高度仍量化成20。
                    // 以无徽标背景的实际 RGB 墨迹作权重，再除总墨迹质量；纯透明度变化不会改变二阶矩。
                    val weightedExit = weightedBadgeInkSize(zero, exit)
                    val weightedFull = weightedBadgeInkSize(zero, two)
                    println("S4_BADGE_MOMENT|host=$host|scale=$scale|sampleElapsed=32|layer=$layer|exit=$weightedExit|full=$weightedFull")
                    assertTrue(weightedExit.first < weightedFull.first && weightedExit.second < weightedFull.second,
                        "退场的归一化实际墨迹尺寸须双轴严格缩小，不只是淡出")
                }
                publish(120); s4Frames(environment, 40)
                val capped = current(120); val cap = s3SmallPatch(environment, testScheduler, capped)
                publish(999); s4Frames(environment, 40)
                assertEquals(capped, current(999)); assertEquals(0, s4Difference(cap, s3SmallPatch(environment, testScheduler, capped)), "99+ 实际绘制不变")
                assertEquals("99+", if (host == 0) unreadCountLabel(count.intValue) else messageUnreadBadge(summary.value)?.label)
                if (host != 0) {
                    font.value = 2f; s4Frames(environment, 40)
                    assertEquals(8f, current(999).width, .5f); assertEquals(8f, current(999).height, .5f, "大字保留8dp点徽标")
                    val dot = s3SmallPatch(environment, testScheduler, current(999))
                    assertTrue(dot.pixels.count { s3RgbDistance(it, fill) <= 2 } >= 4, "实际点徽标不能是空节点")
                    for (unknown in listOf(MessageUnreadSummary.Loading, MessageUnreadSummary.Unavailable(0), MessageUnreadSummary.Unavailable(2))) {
                        summary.value = unknown; environment.advanceFrameBy(0)
                        repeat(20) { environment.advanceFrameBy(); assertFalse(hasCurrent(0)); assertFalse(hasCurrent(2)); assertFalse(hasCurrent(999)) }
                        val description = when (unknown) {
                            MessageUnreadSummary.Loading -> "正在读取全部短信未读数"
                            else -> "上次确认 ${(unknown as MessageUnreadSummary.Unavailable).lastKnownTotal} 条未读短信，当前数量无法确认"
                        }
                        assertTrue(badge(description).boundsInRoot.width > 8f, "未知保持非当前文本徽标，不能被大字规则压成当前点")
                    }
                }
            } finally { environment.close() }
        }
    } }
}

private fun weightedBadgeInkSize(background: S3SmallPatch, ink: S3SmallPatch): Pair<Double, Double> {
    assertEquals(background.width to background.height, ink.width to ink.height)
    val weights = DoubleArray(ink.pixels.size) { i -> s3RgbDistance(background.pixels[i], ink.pixels[i]).toDouble() }
    val mass = weights.sum()
    assertTrue(mass > 0, "采样前提：实际墨迹质量不能为零")
    val centerX = weights.indices.sumOf { i -> weights[i] * (i % ink.width + .5) } / mass
    val centerY = weights.indices.sumOf { i -> weights[i] * (i / ink.width + .5) } / mass
    val x = sqrt(weights.indices.sumOf { i ->
        val delta = i % ink.width + .5 - centerX
        weights[i] * delta * delta
    } / mass)
    val y = sqrt(weights.indices.sumOf { i ->
        val delta = i / ink.width + .5 - centerY
        weights[i] * delta * delta
    } / mass)
    assertTrue(x > 0 && y > 0, "采样前提：墨迹必须有真实双轴尺寸")
    return x to y
}
