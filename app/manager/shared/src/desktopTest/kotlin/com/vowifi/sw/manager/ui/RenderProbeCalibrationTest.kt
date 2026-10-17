package com.vowifi.sw.manager.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.runTest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import org.jetbrains.skia.Rect as SkiaRect

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RenderProbeCalibrationTest {
    @Test fun naturalFourteenSpLabelMayBeEighteenPixelsButClippingAndEllipsisCannotPass() {
        val natural = Rect(145f, 128f, 201f, 146f)
        val layoutSize = IntSize(56, 18) // Actual 14sp Text layout, not the requested 20sp lineHeight.
        val subject = Rect(16f, 16f, 624f, 169f)
        assertCompleteNaturalLabel(natural, layoutSize, subject, ellipsized = false)
        for (clipped in listOf(Rect(145f, 128f, 201f, 145f), Rect(145f, 128f, 200f, 146f),
            Rect(16f, 128f, 624f, 146f))) {
            assertFailsWith<AssertionError> { assertCompleteNaturalLabel(clipped, layoutSize, subject, ellipsized = false) }
        }
        assertFailsWith<AssertionError> { assertCompleteNaturalLabel(natural, layoutSize, subject, ellipsized = true) }
        assertFailsWith<AssertionError> {
            assertCompleteNaturalLabel(natural, layoutSize, Rect(16f, 16f, 624f, 154f), ellipsized = false)
        }
    }

    @Test fun syntheticCoverageSpanKeepsPixelUnitsAndRejectsUnscaledAndPointEightEight() {
        fun measured(scale: Float): Float = horizontalInkSpan(Rect(0f, 0f, 128f, 18f), 0xff000000.toInt(), -1) { x, _ ->
            val coverage = (min(x + 1f, 64f + 50f * scale) - max(x.toFloat(), 64f - 50f * scale)).coerceIn(0f, 1f)
            val grey = ((1f - coverage) * 255f).roundToInt()
            0xff000000.toInt() or (grey shl 16) or (grey shl 8) or grey
        }
        val baseline = measured(1f)
        assertEquals(100f, baseline, .01f, "二阶矩跨度仍以原生像素计，不变成比例或小量纲")
        assertEquals(baseline * .96f, measured(.96f), .8f)
        for (wrong in listOf(1f, .88f)) {
            assertFailsWith<AssertionError> { assertEquals(baseline * .96f, measured(wrong), .8f) }
        }
    }

    @Test fun realPreferenceTitleSpanTracksKnownLayerScaleWithoutSamplingItsSummary() = onMotionTestThread { runTest {
        val title = "合成设置导航"
        ManagerPressRenderFixture(testScheduler) {
            val row = managerNavigatePreference(title, "仅用于合成测试的摘要", ManagerPressTestIcon)
            ManagerNavigatePreferenceItem(row, {})
        }.use { fixture ->
            val baseline = fixture.textInkSpan(title)
            val region = fixture.textBounds(title)
            val summary = fixture.textBounds("仅用于合成测试的摘要")
            assertTrue(region.bottom <= summary.top,
                "采样前提：原标题完整裁取区域不能含摘要；region=$region summary=$summary")
            val subject = fixture.subjectBounds()
            val background = fixture.pixel(Offset(subject.center.x, subject.top + 6f))
            assertTrue(baseline * .04f > .8f, "原.8px容差必须能分辨1.0与.96")
            // Calibrate a known raster transform, not a freshly hinted font strike at each size.
            // Real P4 rendering is separately exercised by the unchanged preference press tests.
            fixture.environment.scene.render(testScheduler.currentTime * 1_000_000L).use { image ->
                Surface.makeRasterN32Premul(image.width, image.height).use { surface ->
                    for (known in listOf(.96f, 1f, .88f)) {
                        val center = subject.center
                        val titleBounds = Rect(center + (region.topLeft - center) * known,
                            center + (region.bottomRight - center) * known)
                        assertTrue(titleBounds.left >= region.left && titleBounds.top >= region.top &&
                            titleBounds.right <= region.right && titleBounds.bottom <= region.bottom,
                            "采样前提：固定区域必须完整容纳同一标题栅格，scale=$known")
                        surface.canvas.clear(background)
                        // Strict source cropping keeps adjacent summary pixels out of the filter kernel.
                        surface.canvas.drawImageRect(image, SkiaRect.makeLTRB(region.left, region.top, region.right, region.bottom),
                            SkiaRect.makeLTRB(titleBounds.left, titleBounds.top, titleBounds.right, titleBounds.bottom),
                            SamplingMode.LINEAR, null, true)
                        val span = surface.makeImageSnapshot().use { scaled ->
                            Bitmap.makeFromImage(scaled).use { bitmap ->
                                horizontalInkSpan(region, fixture.preferenceTitleColor.toArgb(), background, bitmap::getColor)
                            }
                        }
                        assertEquals(baseline * known, span, .8f, "相同真实标题的已知图层缩放=$known")
                        if (known == .96f) assertEquals(baseline * .96f, span, .8f)
                        else assertFailsWith<AssertionError> { assertEquals(baseline * .96f, span, .8f) }
                    }
                }
            }
        }
    } }
}
