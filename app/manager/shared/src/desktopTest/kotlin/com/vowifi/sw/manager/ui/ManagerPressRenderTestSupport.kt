package com.vowifi.sw.manager.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.jetbrains.skia.Bitmap
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.assertTrue

// 合成实心图标仅用于稳定测量绘制尺寸，生产控件、图标和主题均不改写。
internal val ManagerPressTestIcon = ImageVector.Builder(
    name = "PressTestSquare", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(0f, 0f); lineTo(24f, 0f); lineTo(24f, 24f); lineTo(0f, 24f); close()
    }
}.build()

/** 只读真实绘制结果，不添加 clickable、转发原始事件或保存图片。 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
internal class ManagerPressRenderFixture(
    private val scheduler: TestCoroutineScheduler,
    durationScale: Float = 0f,
    scroll: ScrollState? = null,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) : AutoCloseable {
    val haptics = mutableListOf<HapticFeedbackType>()
    lateinit var palette: SoftVoWiFiColors
    var communicationAccent = Color.Unspecified
    var managerAccent = Color.Unspecified
    var preferenceTitleColor = Color.Unspecified
    private val recorder = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
    }
    val environment = MotionTestEnvironment(scheduler, width = 320, height = 240, durationScale = durationScale) {
        SoftVoWiFiTheme(false) {
            val colors = LocalSoftVoWiFiColors.current
            val accent = communicationColors().accent
            val titleColor = MiuixTheme.colorScheme.onBackground
            val primary = MiuixTheme.colorScheme.primary
            SideEffect { palette = colors; communicationAccent = accent; preferenceTitleColor = titleColor; managerAccent = primary }
            CompositionLocalProvider(LocalHapticFeedback provides recorder, LocalDensity provides Density(1f, fontScale)) {
                val container = Modifier.fillMaxSize().background(colors.canvas).padding(24.dp)
                Column(if (scroll == null) container else container.verticalScroll(scroll)) {
                    Box(Modifier.testTag("manager-press-subject")) { content() }
                    if (scroll != null) Spacer(Modifier.height(600.dp))
                }
            }
        }
    }

    init { frames(3) }

    fun frames(count: Int = 1, stepMillis: Long = 16) {
        repeat(count) { environment.advanceFrameBy(stepMillis) }
    }

    fun nodes(unmerged: Boolean = true): List<SemanticsNode> = environment.scene.semanticsOwners.flatMap { owner ->
        fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
        tree(if (unmerged) owner.unmergedRootSemanticsNode else owner.rootSemanticsNode)
    }

    fun subjectBounds(): Rect = nodes().single {
        it.config.getOrNull(SemanticsProperties.TestTag) == "manager-press-subject"
    }.boundsInRoot

    fun actions(): List<SemanticsNode> = nodes(unmerged = false).filter { it.config.contains(SemanticsActions.OnClick) }

    fun textBounds(text: String): Rect = nodes().first {
        it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
    }.boundsInRoot

    fun pixel(point: Offset): Int = pixels { it.getColor(point.x.roundToInt(), point.y.roundToInt()) }

    fun inkBounds(color: Color, region: Rect = subjectBounds()): Rect = pixels { bitmap ->
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        val expected = color.toArgb()
        for (y in floor(region.top).toInt().coerceAtLeast(0) until ceil(region.bottom).toInt().coerceAtMost(bitmap.height)) {
            for (x in floor(region.left).toInt().coerceAtLeast(0) until ceil(region.right).toInt().coerceAtMost(bitmap.width)) {
                if (bitmap.getColor(x, y) == expected) {
                    left = minOf(left, x); top = minOf(top, y)
                    right = maxOf(right, x); bottom = maxOf(bottom, y)
                }
            }
        }
        assertTrue(right >= left && bottom >= top, "实际绘制结果必须存在目标颜色，不能以空图证明按压")
        Rect(left.toFloat(), top.toFloat(), (right + 1).toFloat(), (bottom + 1).toFloat())
    }

    /** Only the natural title rectangle: inflating it also picks up the summary below. */
    fun textInkSpan(text: String): Float = pixels { bitmap ->
        val region = textBounds(text)
        val subject = subjectBounds()
        assertTrue(region.left >= 0f && region.top >= 0f && region.right <= bitmap.width && region.bottom <= bitmap.height,
            "采样前提：完整自然标题须在画布内；region=$region")
        val background = bitmap.getColor(subject.center.x.roundToInt(), (subject.top + 6f).roundToInt())
        horizontalInkSpan(region, preferenceTitleColor.toArgb(), background, bitmap::getColor)
    }

    /** 在已知均匀背景上积分前景覆盖率，避免把抗锯齿边缘舍掉而误算 .88 图标尺寸。 */
    fun squareIconArea(color: Color): Float = pixels { bitmap ->
        val center = subjectBounds().center
        val foreground = color.toArgb()
        val background = bitmap.getColor((center.x + 16f).roundToInt(), center.y.roundToInt())
        fun channel(argb: Int, shift: Int) = argb ushr shift and 255
        val shifts = listOf(16, 8, 0)
        val delta = shifts.map { (channel(foreground, it) - channel(background, it)).toFloat() }
        val denominator = delta.sumOf { (it * it).toDouble() }.toFloat()
        assertTrue(denominator > 0f, "图标与背景必须能区分，前提失败不算按压复现")
        var area = 0f
        // 26x26 位于40dp圆形色层内部；不把圆形边缘的背景变化计为图标。
        for (y in (center.y - 13f).toInt() until (center.y + 13f).toInt()) {
            for (x in (center.x - 13f).toInt() until (center.x + 13f).toInt()) {
                val pixel = bitmap.getColor(x, y)
                val projection = shifts.indices.sumOf { index ->
                    ((channel(pixel, shifts[index]) - channel(background, shifts[index])) * delta[index]).toDouble()
                }.toFloat()
                area += (projection / denominator).coerceIn(0f, 1f)
            }
        }
        area
    }

    private fun <T> pixels(block: (Bitmap) -> T): T =
        environment.scene.render(scheduler.currentTime * 1_000_000L).use { image ->
            Bitmap.makeFromImage(image).use(block)
        }

    override fun close() = environment.close()
}

/** Coverage-weighted horizontal second moment, expressed as an equivalent span in pixels. */
internal fun horizontalInkSpan(region: Rect, foreground: Int, background: Int, pixel: (Int, Int) -> Int): Float {
    val shifts = intArrayOf(16, 8, 0)
    fun channel(argb: Int, shift: Int) = argb ushr shift and 255
    val delta = DoubleArray(3) { (channel(foreground, shifts[it]) - channel(background, shifts[it])).toDouble() }
    val contrast = delta.sumOf { it * it }
    assertTrue(contrast > 0.0, "采样前提：标题与空白底色必须能区分")
    var mass = 0.0
    var first = 0.0
    var second = 0.0
    for (y in floor(region.top).toInt() until ceil(region.bottom).toInt()) {
        for (x in floor(region.left).toInt() until ceil(region.right).toInt()) {
            val argb = pixel(x, y)
            val coverage = (shifts.indices.sumOf { i ->
                (channel(argb, shifts[i]) - channel(background, shifts[i])) * delta[i]
            } / contrast).coerceIn(0.0, 1.0)
            val center = x + .5
            mass += coverage
            first += coverage * center
            second += coverage * center * center
        }
    }
    assertTrue(mass > 0.0, "采样前提：真实标题墨迹不能为空")
    // Include each pixel's 1/12 within-cell variance; a solid N-pixel bar has span exactly N.
    val variance = second / mass - (first / mass) * (first / mass) + 1.0 / 12.0
    assertTrue(variance > 0.0, "采样前提：真实标题必须具有横向跨度")
    return sqrt(12.0 * variance).toFloat()
}
