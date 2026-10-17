@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class S3C4SemanticStatusBadgeRenderTest {
    @Test fun actualBadgeSurfaceHasAnIntermediateColorAfterPointerDrivenToneChange() = onMotionTestThread { runTest {
        val tone = mutableStateOf(SemanticTone.INTERACTIVE)
        var changes = 0
        ManagerPressRenderFixture(testScheduler, durationScale = 1f) {
            Column {
                SemanticStatusBadge(semanticStatusBadge("状态", tone.value), Modifier.testTag("s3-c4-badge"))
                ManagerTextAction("切换状态", ManagerAction.Refresh, { changes++; tone.value = SemanticTone.WARNING })
            }
        }.use { fixture ->
            val badge = fixture.badgeBounds()
            val point = Offset(badge.center.x, badge.top + 2)
            val start = fixture.pixel(point)
            val startAt = testScheduler.currentTime
            assertTrue(s3RgbDistance(start, fixture.palette.surfaceRaised.toArgb()) <= 2,
                "采样前提：纯色背景点未命中；point=$point，start=${s3C4Rgb(start)}@${startAt}ms，expected=${s3C4Rgb(fixture.palette.surfaceRaised.toArgb())}")
            fixture.tapChange()
            val acceptedAt = testScheduler.currentTime
            assertEquals(1, changes)
            fixture.environment.advanceFrameBy(0)
            // 已测32ms相对受理的颜色距离仅2：保留阈值，改在同一200ms过渡内的80ms取可分辨帧。
            fixture.frames(5)
            val middle = fixture.pixel(point)
            val middleAt = testScheduler.currentTime
            fixture.frames(13) // 起点与原288ms终态采样保持；仅中间点由32ms改80ms。
            val end = fixture.pixel(point)
            val endAt = testScheduler.currentTime
            val samples = "point=$point，start=${s3C4Rgb(start)}@${startAt}ms，acceptedAt=${acceptedAt}ms，" +
                "middle=${s3C4Rgb(middle)}@${middleAt}ms(+${middleAt - acceptedAt}ms)，end=${s3C4Rgb(end)}@${endAt}ms(+${endAt - acceptedAt}ms)，" +
                "distance(start,end)=${s3RgbDistance(start, end)}，distance(middle,start)=${s3RgbDistance(middle, start)}，distance(middle,end)=${s3RgbDistance(middle, end)}"
            assertTrue(s3RgbDistance(end, fixture.palette.warningSurface.toArgb()) <= 2,
                "终态必须仍来自真实语义色；expected=${s3C4Rgb(fixture.palette.warningSurface.toArgb())}；$samples")
            assertTrue(s3RgbDistance(start, end) >= 16, "采样前提：两种真实底色须可区分；$samples")
            assertTrue(s3RgbDistance(middle, start) > 2 && s3RgbDistance(middle, end) > 2,
                "C4：80ms 必须是两端之间的颜色，不能即时替换；$samples")
        }
    } }

    @Test fun actualBadgeTextHasBothOutgoingAndIncomingInkAtAnIntermediateFrame() = onMotionTestThread { runTest {
        val label = mutableStateOf("11") // 两个等宽数字避免同时测字宽 spring；并非自造 Text 组件。
        ManagerPressRenderFixture(testScheduler, durationScale = 1f) {
            Column {
                SemanticStatusBadge(semanticStatusBadge(label.value, SemanticTone.INTERACTIVE), Modifier.testTag("s3-c4-badge"))
                ManagerTextAction("切换文字", ManagerAction.Refresh, { label.value = "88" })
            }
        }.use { fixture ->
            val region = fixture.textBounds("11")
            val start = s3SmallPatch(fixture.environment, testScheduler, region)
            fixture.tapChange()
            fixture.environment.advanceFrameBy(0)
            fixture.frames(2)
            val middle = s3SmallPatch(fixture.environment, testScheduler, region)
            fixture.frames(16)
            val finalBounds = fixture.textBounds("88")
            assertEquals(region.left, finalBounds.left, .5f, "采样前提：文字起点必须一致")
            assertEquals(region.width, finalBounds.width, .5f, "采样前提：参考文字须等宽")
            assertEquals(region.top, finalBounds.top, .5f)
            val end = s3SmallPatch(fixture.environment, testScheduler, region)
            val background = fixture.palette.surfaceRaised.toArgb()
            val foreground = fixture.palette.interactive.toArgb()
            fun coverage(patch: S3SmallPatch, i: Int) = s3Coverage(patch.pixels[i], background, foreground)
            val oldOnly = start.pixels.indices.filter { coverage(start, it) > .7f && coverage(end, it) < .03f }
            val newOnly = start.pixels.indices.filter { coverage(end, it) > .7f && coverage(start, it) < .03f }
            assertTrue(oldOnly.size >= 2 && newOnly.size >= 2, "采样前提：实际字体必须有可区分的旧/新纯字形点")
            val outgoing = oldOnly.map { coverage(middle, it) / coverage(start, it) }.average()
            val incoming = newOnly.map { coverage(middle, it) / coverage(end, it) }.average()
            assertTrue(outgoing > .03 && outgoing < .97, "C4：旧文字应仍在淡出，实际=$outgoing")
            assertTrue(incoming > .03 && incoming < .97, "C4：新文字应仍在淡入，实际=$incoming")
            assertEquals(1, fixture.nodes().count { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "88" } == true })
        }
    } }

    @Test fun zeroScaleImmediatelyShowsTheRealTargetTextAndColor() = onMotionTestThread { runTest {
        val changed = mutableStateOf(false)
        ManagerPressRenderFixture(testScheduler, durationScale = 0f) {
            Column {
                SemanticStatusBadge(semanticStatusBadge(if (changed.value) "完成" else "等待",
                    if (changed.value) SemanticTone.SUCCESS else SemanticTone.INTERACTIVE), Modifier.testTag("s3-c4-badge"))
                ManagerTextAction("切换状态", ManagerAction.Refresh, { changed.value = true })
            }
        }.use { fixture ->
            fixture.tapChange()
            fixture.frames(2)
            assertTrue(changed.value)
            fixture.textBounds("完成")
            assertTrue(fixture.nodes().none { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "等待" } == true })
            val badge = fixture.badgeBounds()
            assertTrue(s3RgbDistance(fixture.pixel(Offset(badge.center.x, badge.top + 2)), fixture.palette.successSurface.toArgb()) <= 2)
        }
    } }

    @Test fun badgeWidthMovesTowardTheTargetWhileOnlyTheCurrentLabelIsAccessible() = onMotionTestThread { runTest {
        val label = mutableStateOf("等待")
        ManagerPressRenderFixture(testScheduler, durationScale = 1f) {
            Column {
                SemanticStatusBadge(semanticStatusBadge(label.value, SemanticTone.INTERACTIVE), Modifier.testTag("s3-c4-badge"))
                ManagerTextAction("切换文字", ManagerAction.Refresh, { label.value = "已完成状态汇总" })
            }
        }.use { fixture ->
            val start = fixture.badgeBounds()
            fixture.tapChange()
            fixture.environment.advanceFrameBy(0)
            fixture.frames(2)
            val middle = fixture.badgeBounds()
            assertEquals(1, fixture.nodes().count { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "已完成状态汇总" } == true })
            assertTrue(fixture.nodes().none { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "等待" } == true },
                "C4：出场文字只能绘制，不替代真实当前标签的可访问语义")
            fixture.frames(32)
            val end = fixture.badgeBounds()
            assertTrue(end.width - start.width >= 16, "采样前提：两个标签的实际自然宽度须不同")
            assertTrue(middle.width > start.width + 1 && middle.width < end.width - 1,
                "C4：宽度须经过局部 spring 中间尺寸，不能随文字瞬切")
            assertEquals(start.left, end.left, .5f)
            assertEquals(start.top, end.top, .5f)
        }
    } }
}

private fun ManagerPressRenderFixture.badgeBounds() = nodes().single {
    it.config.getOrNull(SemanticsProperties.TestTag) == "s3-c4-badge"
}.boundsInRoot

private fun ManagerPressRenderFixture.tapChange() {
    val point = actions().single().boundsInRoot.center
    environment.press(point)
    frames(stepMillis = 8)
    environment.release(point)
}

private fun s3C4Rgb(argb: Int): String =
    "#${argb.toUInt().toString(16).padStart(8, '0')} RGB(${argb ushr 16 and 255},${argb ushr 8 and 255},${argb and 255})"
