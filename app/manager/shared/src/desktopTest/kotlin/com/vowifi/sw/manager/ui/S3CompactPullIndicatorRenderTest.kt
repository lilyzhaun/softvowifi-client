package com.vowifi.sw.manager.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import com.vowifi.sw.manager.data.RefreshResult
import com.vowifi.sw.manager.ui.S3HomeRefreshEntryDraftTest.HomeEntryDriver
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** R5：2026-10-15用户实机公共指示区反馈及固定20dp紧凑圆环要求；原Home真实指针/像素合同。 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class S3CompactPullIndicatorRenderTest {
    @Test fun bothLongArmedPullsKeepTheActualStrokeInsideOne20DpCircle() = onMotionTestThread { runTest {
        val samples = listOf(180f, 300f).map { distance ->
            HomeEntryDriver(testScheduler).use { d ->
                d.frames(30); d.holdReads = true
                val owner = d.listIdentity()
                arm(d, distance)
                val ink = stroke(d, "松开刷新", testScheduler.currentTime)
                assertEquals(0, d.refreshCalls, "达到阈值但仍持指，不能先执行读取")
                accept(d)
                assertEquals(owner, d.listIdentity())
                d.assertAtTop()
                d.releaseRead(RefreshResult.Completed); d.frames(100)
                assertEquals(RefreshResult.Completed, d.completedResult)
                assertEquals(1, d.refreshCalls); assertEquals(0, d.otherActions)
                assertEquals(owner, d.listIdentity()); d.assertAtTop()
                ink
            }
        }
        samples.forEach(::assertCompact) // 先完成两个距离的实际UP/一次读取守护，再断言像素几何。
    } }

    @Test fun heldRingReallyRotatesAndFailedReadPersistsUntilItsRealRetry() = onMotionTestThread { runTest {
        HomeEntryDriver(testScheduler).use { d ->
            d.frames(30); d.holdReads = true
            val owner = d.listIdentity()
            arm(d, 180f); accept(d); d.untilPullOccupationStable()
            d.environment.advanceFrameBy(MotionTokens.DurFast.toLong()) // held IO不结束，只让caption原交叉淡变交付真实paint。
            val first = stroke(d, "正在刷新", testScheduler.currentTime)
            d.environment.advanceFrameBy(96)
            val rotated = stroke(d, "正在刷新", testScheduler.currentTime)
            assertCompact(first); assertCompact(rotated)
            assertFalse(first.pixels.contentEquals(rotated.pixels), "held真实读取中的环形墨迹必须有旋转变化")
            assertTrue(d.producer?.isActive == true); assertEquals(null, d.readCompletedAtMillis)
            assertEquals(1, d.refreshCalls); assertEquals(owner, d.listIdentity()); d.assertAtTop()
            d.releaseRead(RefreshResult.Failed("合成读取失败")); d.frames(100)
            assertEquals(RefreshResult.Failed("合成读取失败"), d.completedResult)
            assertTrue(hasText(d, "刷新未完成")); d.frames(30)
            assertTrue(hasText(d, "刷新未完成"), "失败说明不能随指示动画收起而消失")
            val retry = d.nodes().single { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "重试刷新" } == true }
            d.holdReads = false; d.nextResult = RefreshResult.Completed
            d.environment.press(retry.boundsInRoot.center); d.frames(1)
            d.environment.release(retry.boundsInRoot.center)
            d.until("真实重试必须执行下一次读取") { d.refreshCalls == 2 && d.completedResult == RefreshResult.Completed }
            d.frames(100)
            assertFalse(hasText(d, "刷新未完成"))
            assertEquals(owner, d.listIdentity()); d.assertAtTop(); assertEquals(0, d.otherActions)
        }
    } }

    @Test fun leavingTheActualHomeCancelsOnlyItsWaiterAndDoesNotReplayFailure() = onMotionTestThread { runTest {
        HomeEntryDriver(testScheduler).use { d ->
            d.frames(30); d.holdReads = true
            val owner = d.listIdentity()
            arm(d, 180f); accept(d)
            assertEquals(owner, d.listIdentity()); d.assertAtTop()
            d.showHome = false; d.frames(2)
            assertTrue(d.producer?.isActive == true, "离页不能取消source-owned真实producer")
            d.releaseRead(RefreshResult.Completed); d.frames(2)
            assertTrue(d.producer?.isCompleted == true); assertFalse(d.producer?.isCancelled == true)
            d.showHome = true; d.frames(30)
            assertEquals(1, d.refreshCalls); assertEquals(0, d.otherActions)
            assertFalse(hasText(d, "刷新未完成")); d.assertAtTop()
        }
    } }

    private fun arm(d: HomeEntryDriver, distance: Float) {
        d.beginDownwardDrag(distance); d.assertRealDownwardInput(); d.frames(2)
        d.environment.advanceFrameBy(MotionTokens.DurFast.toLong()) // 保持持指，兼容新Row原文字淡变；不触发UP或业务。
        assertEquals(1f, d.pullProgress(), "前提：真实长下拉已达到公开阈值")
        assertTrue(hasText(d, "松开刷新"), "前提：原公共Threshold文案已呈现")
        assertEquals(0, d.refreshCalls, "UP前只能推进圆环进度，不启动业务")
    }

    private fun accept(d: HomeEntryDriver) {
        d.finishDrag(); d.until("UP后必须受理一次held读取") { d.refreshCalls == 1 }
        assertEquals(1, d.refreshCalls); assertTrue(d.producer?.isActive == true)
        assertEquals(null, d.readCompletedAtMillis)
    }

    private fun hasText(d: HomeEntryDriver, value: String) = d.nodes().any {
        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true
    }

    private fun stroke(d: HomeEntryDriver, label: String, now: Long): Stroke {
        fun tree(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::tree)
        val nodes = d.environment.scene.semanticsOwners.flatMap { tree(it.unmergedRootSemanticsNode) }
        val text = nodes.single { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == label } == true }
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(text.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
        val style = layouts.single().layoutInput
        val diameter = 20f * style.density.density
        val caption = text.boundsInRoot
        val wrapper = d.pullIndicator().boundsInRoot
        val list = d.nodes().single { it.config.contains(SemanticsActions.ScrollToIndex) }
        val bodyTop = list.children.filter { it.boundsInRoot.height > 0f }.minOf { it.boundsInRoot.top }
        // 实际header空白区止于自然body：不假设环在屏幕中心或caption正上方。
        val left = ceil(wrapper.left).toInt()
        val right = floor(wrapper.right).toInt()
        val top = ceil(wrapper.top).toInt()
        val bottom = floor(bodyTop - 1f).toInt()
        assertTrue(left >= 0 && right <= 390 && top >= 0 && bottom < 800 && bottom > top &&
            caption.left > left + 2 && caption.right < right - 2 && caption.top >= top && caption.bottom < bottom,
            "采样前提：真实caption及header区完整位于自然body之前；caption=$caption bodyTop=$bodyTop")
        val exclusions = nodes.mapNotNull { n ->
            val words = n.config.getOrNull(SemanticsProperties.Text).orEmpty()
            val kind = if (words.any { it.text.isNotEmpty() }) when {
                n === text -> "caption"
                words.any { it.text == "SoftVoWiFi" } -> "top-title"
                else -> "other-text"
            } else if (n.config.contains(SemanticsProperties.ContentDescription) && tree(n).none {
                it.config.contains(SemanticsActions.ScrollToIndex) ||
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("下拉刷新进度") == true }) "icon-owner" else null
            kind?.let { it to n.boundsInRoot.inflate(1f) } // 仅排字体/图标抗锯齿，不裁环来通过几何断言。
        }
        return d.environment.scene.render(now * 1_000_000L).use { image -> Bitmap.makeFromImage(image).use { bitmap ->
            val outside = listOf(left + 1, right - 2).flatMap { x -> listOf(-1, 1).map { dy ->
                bitmap.getColor(x, floor(caption.center.y).toInt() + dy) } }
            val background = outside.first()
            assertTrue(outside.all { rgbDistance(it, background) <= 2 }, "采样前提：caption两侧外部canvas必须同色")
            val colors = mutableMapOf<Int, Int>()
            for (y in ceil(caption.top).toInt() until floor(caption.bottom).toInt())
                for (x in ceil(caption.left).toInt() until floor(caption.right).toInt()) {
                    val pixel = bitmap.getColor(x, y)
                    if (rgbDistance(pixel, background) >= 24) colors[pixel] = (colors[pixel] ?: 0) + 1
                }
            val paint = colors.maxByOrNull { it.value }
            assertTrue(paint != null && paint.value >= 24, "采样前提：caption实际paint色须有至少24个同色核心像素；colors=${colors.size}")
            val color = requireNotNull(paint).key
            val width = right - left
            val mask = BooleanArray(width * (bottom - top))
            var x0 = right; var x1 = left; var y0 = bottom; var y1 = top
            // 环形底轨和旋转亮段不必与caption同不透明度；按实测canvas对比定位可见笔画。
            // 沿用caption核心的24级可见门槛与每个连通stroke至少24像素，几何容差不变。
            for (y in top until bottom) for (x in left until right)
                if (rgbDistance(bitmap.getColor(x, y), background) >= 24) {
                    x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y)
                    mask[(y - top) * width + x - left] = exclusions.none { it.second.contains(Offset(x + .5f, y + .5f)) }
                }
            val raw = Rect(x0.toFloat(), y0.toFloat(), (x1 + 1).toFloat(), (y1 + 1).toFloat())
            val blobs = strokeComponents(mask, width, left, top)
            val input = if (style.style.color == Color.Unspecified) "unspecified" else hex(style.style.color.toArgb())
            println("compact-stroke label=$label t=$now layout=$input paint=${hex(color)} canvas=${hex(background)} " +
                "core=${paint.value} caption=$caption header=[$left,$top,$right,$bottom] raw=$raw " +
                "intersections=${exclusions.filter { it.second.overlaps(raw) }.take(6)} strokes=$blobs")
            assertEquals(1, blobs.size, "采样前提：排除caption/其它文字/图标后须唯一真实stroke（各>=24核心像素）")
            val bounds = blobs.single()
            assertTrue(bounds.left > left && bounds.right < right && bounds.top > top && bounds.bottom < bottom &&
                exclusions.none { it.second.overlaps(bounds) }, "采样前提：真实stroke完整，不碰扫描/排除区裁剪边界；bounds=$bounds")
            val w = bounds.width.toInt()
            Stroke(bounds, diameter, IntArray(w * bounds.height.toInt()) { i ->
                bitmap.getColor(bounds.left.toInt() + i % w, bounds.top.toInt() + i / w) })
        } }
    }

    private fun strokeComponents(mask: BooleanArray, width: Int, left: Int, top: Int): List<Rect> {
        val queue = IntArray(mask.size)
        val result = mutableListOf<Rect>()
        for (seed in mask.indices) if (mask[seed]) {
            var head = 0; var tail = 1
            queue[0] = seed; mask[seed] = false
            var x0 = width; var x1 = 0; var y0 = mask.size / width; var y1 = 0
            while (head < tail) {
                val index = queue[head++]; val x = index % width; val y = index / width
                x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y)
                for (ny in maxOf(0, y - 1)..minOf(mask.size / width - 1, y + 1))
                    for (nx in maxOf(0, x - 1)..minOf(width - 1, x + 1)) {
                        val next = ny * width + nx
                        if (mask[next]) { mask[next] = false; queue[tail++] = next }
                    }
            }
            if (tail >= 24) result += Rect((left + x0).toFloat(), (top + y0).toFloat(), (left + x1 + 1).toFloat(), (top + y1 + 1).toFloat())
        }
        return result
    }

    private fun assertCompact(stroke: Stroke) {
        val b = stroke.bounds
        // 各边最多1物理像素栅格/抗锯齿余量，不按拖动距离扩大容限。
        val maximum = ceil(stroke.diameter).toInt() + 2
        assertTrue(b.height <= maximum && b.width <= maximum,
            "20dp固定圆环的实际stroke不得拉成长轮廓；bounds=$b diameterPx=${stroke.diameter}")
        assertTrue(b.width >= floor(stroke.diameter) - 2 && b.height >= floor(stroke.diameter) - 2,
            "实际阈值圆环不能用隐藏/缩小成点通过；bounds=$b")
        assertTrue(abs(b.width - b.height) <= 2f, "实际圆环宽高应接近1，仅容纳像素栅格差；bounds=$b")
    }

    private fun rgbDistance(a: Int, b: Int) = maxOf(
        abs((a ushr 16 and 255) - (b ushr 16 and 255)),
        abs((a ushr 8 and 255) - (b ushr 8 and 255)), abs((a and 255) - (b and 255)))
    private fun hex(value: Int) = value.toUInt().toString(16).padStart(8, '0')
    private data class Stroke(val bounds: Rect, val diameter: Float, val pixels: IntArray)
}
