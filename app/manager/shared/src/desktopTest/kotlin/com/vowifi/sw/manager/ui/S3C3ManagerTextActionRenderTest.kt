@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.ui

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Refresh
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// C3：busy只由真实点击受理、受控完成回调驱动；原宽度/alpha失败回执见任务记录。
class S3C3ManagerTextActionRenderTest {
    @Test fun acceptedBusyKeepsTheInitialNaturalWidthWithoutFutureCandidatesApi() = onMotionTestThread { runTest {
        val busy = mutableStateOf(false)
        val completed = CompletableDeferred<Unit>()
        var submissions = 0
        ManagerPressRenderFixture(testScheduler, durationScale = 1f) {
            val scope = rememberCoroutineScope()
            ManagerTextAction("刷新状态", ManagerAction.Refresh, {
                submissions++
                busy.value = true
                scope.launch { completed.await(); busy.value = false }
            }, modifier = Modifier.testTag("s3-c3-action"), busy = busy.value)
        }.use { fixture ->
            try {
                val initial = fixture.controlBounds()
                fixture.environment.press(initial.center)
                fixture.frames(stepMillis = 8)
                assertEquals(0, submissions)
                fixture.environment.release(initial.center)
                assertEquals(1, submissions)
                fixture.frames(2)
                assertTrue(busy.value)
                assertFalse(completed.isCompleted)
                assertEquals(initial.width, fixture.controlBounds().width, .5f,
                    "C3：同一标签仅插入 spinner 也不得改变首次自然宽度；不靠未来 candidates API")
                completed.complete(Unit)
                fixture.frames(16)
                assertFalse(busy.value)
                assertEquals(initial.width, fixture.controlBounds().width, .5f)
            } finally { completed.complete(Unit) }
        }
    } }

    @Test fun spinnerForegroundHasIntermediateEntryAlphaAfterAnAcceptedPointerTap() = onMotionTestThread { runTest {
        val busy = mutableStateOf(false)
        val completed = CompletableDeferred<Unit>()
        var submissions = 0
        var foreground = Color.Unspecified
        var track = Color.Unspecified
        var disabledButton = Color.Unspecified
        var diameter = 0f
        ManagerPressRenderFixture(testScheduler, durationScale = 1f) {
            val scope = rememberCoroutineScope()
            val colors = MiuixTheme.colorScheme
            val defaultDiameter = with(LocalDensity.current) { ProgressIndicatorDefaults.DefaultCircularProgressIndicatorSize.toPx() }
            SideEffect {
                foreground = colors.primary
                track = colors.secondaryContainer
                disabledButton = colors.disabledSecondaryVariant
                diameter = defaultDiameter
            }
            ManagerTextAction("刷新状态", ManagerAction.Refresh, {
                submissions++
                busy.value = true
                scope.launch { completed.await(); busy.value = false }
            }, modifier = Modifier.testTag("s3-c3-action"), busy = busy.value)
        }.use { fixture ->
            try {
                val point = fixture.controlBounds().center
                fixture.environment.press(point)
                fixture.frames(stepMillis = 8)
                assertEquals(0, submissions)
                fixture.environment.release(point)
                assertEquals(1, submissions)
                val acceptedAt = testScheduler.currentTime
                fixture.environment.advanceFrameBy(0)
                fixture.frames(3)
                val canvas = fixture.nodes().filter {
                    it.config.contains(SemanticsProperties.ProgressBarRangeInfo) &&
                        it.boundsInRoot.width in 8f..32f && it.boundsInRoot.height in 8f..32f
                }.minByOrNull { it.boundsInRoot.width * it.boundsInRoot.height }?.boundsInRoot
                    ?: error("采样前提：必须命中真实 spinner 小语义矩形；未命中不计 RED")
                // 唯一扩展依据是固定库的实际默认绘制直径，不扫描按钮或整图。
                val window = Rect(canvas.left, canvas.top, canvas.left + maxOf(canvas.width, diameter), canvas.top + maxOf(canvas.height, diameter))
                val textBounds = fixture.nodes().filter { it.config.contains(SemanticsProperties.Text) }.map { it.boundsInRoot }
                val backgroundPoint = Offset(fixture.controlBounds().center.x, fixture.controlBounds().top + 6)
                val background = fixture.pixel(backgroundPoint)
                val middle = s3SmallPatch(fixture.environment, testScheduler, window)
                val middleAt = testScheduler.currentTime
                fixture.frames(10)
                val settled = s3SmallPatch(fixture.environment, testScheduler, window)
                val settledAt = testScheduler.currentTime
                assertEquals(background, fixture.pixel(backgroundPoint), "采样前提：两帧按钮底色必须稳定")
                assertTrue(s3RgbDistance(foreground.toArgb(), background) >= 24, "采样前提：原前景对比阈值24保持")
                val left = floor(window.left).toInt()
                val top = floor(window.top).toInt()
                fun position(i: Int) = Offset(left + i % settled.width + .5f, top + i / settled.width + .5f)
                // During the real label crossfade the outgoing text's bounding rectangle overlaps
                // the circle. Reject non-blue pixels by RGB residual, rather than masking the
                // entire old text rectangle and accidentally measuring only the pale track.
                val samples = settled.pixels.indices
                fun coverage(pixel: Int): Float {
                    val alpha = s3Coverage(pixel, background, foreground.toArgb())
                    val residual = listOf(16, 8, 0).maxOf { shift ->
                        val bg = background ushr shift and 255
                        val fg = foreground.toArgb() ushr shift and 255
                        abs((pixel ushr shift and 255) - (bg + alpha * (fg - bg)))
                    }
                    return if (residual <= 8f) alpha else 0f
                }
                fun peak(patch: S3SmallPatch) = samples.maxOf { coverage(patch.pixels[it]) }
                val full = samples.filter { s3Coverage(settled.pixels[it], background, foreground.toArgb()) > .9f && s3RgbDistance(settled.pixels[it], foreground.toArgb()) <= 8 }
                val inside = full.count { canvas.contains(position(it)) }
                val outside = full.size - inside
                val foregroundBounds = if (full.isEmpty()) null else Rect(
                    full.minOf { position(it).x } - .5f, full.minOf { position(it).y } - .5f,
                    full.maxOf { position(it).x } + .5f, full.maxOf { position(it).y } + .5f,
                )
                val settledPeak = peak(settled)
                val middlePeak = peak(middle)
                val report = "canvas=$canvas，window=$window，textBounds=$textBounds，foregroundBounds=$foregroundBounds，" +
                    "foreground=${spinnerRgb(foreground.toArgb())}，track=${spinnerRgb(track.toArgb())}，disabledButton=${spinnerRgb(disabledButton.toArgb())}，background=${spinnerRgb(background)}，" +
                    "fullPixels=${full.size}，inside=$inside，outside=$outside，middlePeak=$middlePeak@${middleAt}ms(+${middleAt - acceptedAt}ms)，settledPeak=$settledPeak@${settledAt}ms(+${settledAt - acceptedAt}ms)"
                assertTrue(full.size >= 3 && settledPeak > .95f, "采样前提：必须实际命中至少三个近完整生产前景像素且峰值>0.95；$report；未满足不计 RED")
                assertEquals(0, outside, "公开size必须与实际分配匹配，完整前景不能再绘到Canvas之外；$report")
                assertTrue(busy.value)
                assertFalse(completed.isCompleted)
                assertEquals(1, submissions)
                val alpha = middlePeak / settledPeak
                assertTrue(alpha > .05 && alpha < .95, "C3：48ms/208ms前景峰值之比应0.05..0.95；实际=$alpha；$report；旧实现实际约1才证明探针有效")
            } finally { completed.complete(Unit) }
        }
    } }

    @Test fun busyRejectsDuplicatePointersAndZeroScaleUsesTheRealCompletionTerminal() = onMotionTestThread { runTest {
        val busy = mutableStateOf(false)
        val completed = CompletableDeferred<Unit>()
        var submissions = 0
        ManagerPressRenderFixture(testScheduler, durationScale = 0f) {
            val scope = rememberCoroutineScope()
            ManagerTextAction("刷新状态", ManagerAction.Refresh, {
                submissions++ // 无测试侧防重；重复调用必须由实际控件门禁拦截。
                busy.value = true
                scope.launch { completed.await(); busy.value = false }
            }, modifier = Modifier.testTag("s3-c3-action"), busy = busy.value)
        }.use { fixture ->
            try {
                fixture.tapControl()
                fixture.frames(2)
                assertTrue(busy.value)
                assertTrue(fixture.nodes().any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
                fixture.tapControl()
                fixture.frames(2)
                assertEquals(1, submissions)
                completed.complete(Unit)
                fixture.frames(2)
                assertFalse(busy.value)
                assertFalse(fixture.nodes().any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
                fixture.tapControl()
                fixture.frames(2)
                assertEquals(2, submissions, "零动画真实完成后恢复可操作，不等视觉计时")
            } finally { completed.complete(Unit) }
        }
    } }

    @Test fun allRealLabelCandidatesAreMeasuredBeforeTheFirstTapWithoutHiddenSemantics() = onMotionTestThread { runTest {
        val label = mutableStateOf("启动")
        val busy = mutableStateOf(false)
        val completed = CompletableDeferred<Unit>()
        var submissions = 0
        ManagerPressRenderFixture(testScheduler, durationScale = 1f) {
            val scope = rememberCoroutineScope()
            ManagerTextAction(label.value, ManagerAction.StartEngine, {
                submissions++; busy.value = true
                scope.launch { completed.await(); label.value = "操作结果已完成"; busy.value = false }
            }, modifier = Modifier.testTag("s3-c3-action"), busy = busy.value, busyLabel = "正在处理",
                labelCandidates = listOf("启动", "正在处理", "操作结果已完成"))
        }.use { fixture ->
            try {
                fun hasText(value: String) = fixture.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == value } == true }
                val initial = fixture.controlBounds()
                assertFalse(hasText("操作结果已完成"), "预量候选不能进入可访问语义")
                fixture.tapControl(); fixture.frames(2)
                assertEquals(1, submissions)
                assertTrue(hasText("正在处理"))
                // S7 §4.10: the real held request needs a spoken state, not only changed ink/spinner.
                assertEquals(listOf("正在处理"), fixture.nodes().mapNotNull {
                    it.config.getOrNull(SemanticsProperties.StateDescription)
                })
                assertEquals(initial.width, fixture.controlBounds().width, .5f)
                assertFalse(completed.isCompleted)
                completed.complete(Unit); fixture.frames(16)
                assertTrue(hasText("操作结果已完成"))
                assertFalse(hasText("正在处理"))
                assertFalse(fixture.nodes().any { it.config.contains(SemanticsProperties.StateDescription) })
                assertEquals(initial.width, fixture.controlBounds().width, .5f, "首次长终态也不能扩大按钮")
                assertEquals(1, submissions)
            } finally { completed.complete(Unit) }
        }
    } }

    @Test fun inactiveRouteKeepsRealPendingAndZeroScaleCompletionWithoutAcceptingPointers() = onMotionTestThread { runTest {
        val active = mutableStateOf(true)
        val busy = mutableStateOf(false)
        val completed = CompletableDeferred<Unit>()
        var submissions = 0
        ManagerPressRenderFixture(testScheduler, durationScale = 0f) {
            val scope = rememberCoroutineScope()
            CompositionLocalProvider(LocalAppRouteActive provides active.value) {
                ManagerTextAction("启动", ManagerAction.StartEngine, {
                    submissions++; busy.value = true
                    scope.launch { completed.await(); busy.value = false }
                }, modifier = Modifier.testTag("s3-c3-action"), busy = busy.value, busyLabel = "正在处理")
            }
        }.use { fixture ->
            try {
                fixture.tapControl(); fixture.frames(2)
                val bounds = fixture.controlBounds()
                active.value = false; fixture.frames(2)
                assertTrue(busy.value)
                assertTrue(fixture.nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "正在处理" } == true })
                fixture.tapControl(); fixture.frames(2)
                assertEquals(1, submissions)
                completed.complete(Unit); fixture.frames(2)
                assertFalse(busy.value)
                assertFalse(fixture.nodes().any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
                assertEquals(bounds, fixture.controlBounds(), "失活不能改变已测稳定按钮几何")
                fixture.tapControl(); fixture.frames(2)
                assertEquals(1, submissions, "完成不能绕过仍失活的route门禁")
            } finally { completed.complete(Unit) }
        }
    } }

    @Test fun optionalRotationOnlyTurnsTheInternalIconNotTheHitTargetOrPressTint() = onMotionTestThread { runTest {
        val rotation = mutableFloatStateOf(0f)
        var submissions = 0
        ManagerPressRenderFixture(testScheduler, durationScale = 0f) {
            ManagerIconAction(MiuixIcons.Refresh, "旋转探针", ManagerAction.Refresh, { submissions++ }, iconRotation = rotation)
        }.use { fixture ->
            val bounds = fixture.subjectBounds()
            val sample = Offset(bounds.center.x, bounds.center.y + 18f)
            fixture.environment.press(bounds.center); fixture.frames(2)
            val pressed = fixture.pixel(sample)
            rotation.floatValue = 45f; fixture.frames(2)
            assertEquals(bounds, fixture.subjectBounds(), "48dp命中不参与旋转")
            assertEquals(pressed, fixture.pixel(sample), "40dp按压色层不能跟Icon旋转")
            assertEquals(0, submissions)
            fixture.environment.release(bounds.center); fixture.frames(2)
            assertEquals(1, submissions)
        }
    } }
}

private fun ManagerPressRenderFixture.controlBounds() = nodes().single {
    it.config.getOrNull(SemanticsProperties.TestTag) == "s3-c3-action"
}.boundsInRoot

private fun ManagerPressRenderFixture.tapControl() {
    val point = controlBounds().center
    environment.press(point)
    frames(stepMillis = 8)
    environment.release(point)
}

private fun spinnerRgb(argb: Int): String =
    "#${argb.toUInt().toString(16).padStart(8, '0')} RGB(${argb ushr 16 and 255},${argb ushr 8 and 255},${argb and 255})"

// 三文件共用的局部只读探针；仅复制限定小矩形，不保存整图、不替代生产动画。
internal data class S3SmallPatch(val width: Int, val height: Int, val pixels: IntArray)

internal fun s3SmallPatch(environment: MotionTestEnvironment, scheduler: TestCoroutineScheduler, region: Rect): S3SmallPatch =
    environment.scene.render(scheduler.currentTime * 1_000_000L).use { image ->
        Bitmap.makeFromImage(image).use { bitmap ->
            val left = floor(region.left).toInt()
            val top = floor(region.top).toInt()
            val width = ceil(region.right).toInt() - left
            val height = ceil(region.bottom).toInt() - top
            assertTrue(width in 1..96 && height in 1..64, "采样前提：只允许小文字/图标区域")
            assertTrue(left >= 0 && top >= 0 && left + width <= bitmap.width && top + height <= bitmap.height,
                "采样前提：目标必须完整可见，裁剪不能当作动画")
            S3SmallPatch(width, height, IntArray(width * height) { i -> bitmap.getColor(left + i % width, top + i / width) })
        }
    }

internal fun s3TextBounds(nodes: List<SemanticsNode>, text: String): Rect = nodes.first {
    it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
}.boundsInRoot

internal fun s3RgbDistance(a: Int, b: Int): Int = listOf(16, 8, 0).maxOf {
    abs((a ushr it and 255) - (b ushr it and 255))
}

internal fun s3Coverage(pixel: Int, background: Int, foreground: Int): Float {
    var numerator = 0f
    var denominator = 0f
    for (shift in listOf(16, 8, 0)) {
        val delta = ((foreground ushr shift and 255) - (background ushr shift and 255)).toFloat()
        numerator += ((pixel ushr shift and 255) - (background ushr shift and 255)) * delta
        denominator += delta * delta
    }
    assertTrue(denominator > 0, "采样前提：前景与背景不能同色")
    return (numerator / denominator).coerceIn(0f, 1f)
}
