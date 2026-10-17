@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.NavigationBarDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** T3：复用S4只读helper，真实指针、独立目标字形及颜色时钟；不改徽标或滑块守护。 */
class S5BottomColorRenderTest {
    @Test fun normalAndFloatingTabsExposeTwoDpInkOnTheirOwnRealFocusLeafWithoutSelecting() = onMotionTestThread { runTest {
        val failures = mutableListOf<String>()
        for (floating in listOf(false, true)) for (scale in listOf(1f, 0f)) {
            var active by mutableStateOf(true); var accent = 0
            var mode: androidx.compose.ui.input.InputModeManager? = null
            val requests = mutableListOf<AppDestination>()
            val e = MotionTestEnvironment(testScheduler, 390, 300, scale) {
                SoftVoWiFiTheme(false) {
                    val colors = LocalSoftVoWiFiColors.current
                    val manager = androidx.compose.ui.platform.LocalInputModeManager.current
                    SideEffect { mode = manager; accent = colors.interactive.toArgb() }
                    CompositionLocalProvider(LocalDensity provides Density(1f), LocalAppRouteActive provides active) {
                        Box(Modifier.fillMaxSize().background(colors.canvas)) {
                            BottomNavigationBar(AppDestination.HOME, { requests += it },
                                appChromeSpec(AppearanceSettings.Default.copy(floatingNavigation = floating,
                                    blurRequested = false, liquidGlassRequested = false), false), null,
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                        }
                    }
                }
            }
            try {
                s4Frames(e, 40)
                fun tab() = s4Nodes(e, false).single { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
                    s4Matches(it, AppDestination.SETTINGS.label) }
                val bounds = tab().boundsInRoot
                assertTrue(bounds.width >= 48f && bounds.height >= 48f && bounds.top >= 1f && bounds.bottom <= 300f,
                    "前提：真实tab完整可达，floating=$floating bounds=$bounds")
                failures += s7RemainingFocusCheck(e, testScheduler, assertNotNull(mode), accent,
                    "bottom floating=$floating scale=$scale", ::tab, { active = false })
                assertTrue(requests.isEmpty(), "Tab/Touch空白/route失活不选择底栏项，不触P7物理业务")
                assertEquals(true, s4Nodes(e, false).single { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
                    s4Matches(it, AppDestination.HOME.label) }.config.getOrNull(SemanticsProperties.Selected))
            } finally { e.close() }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    } }

    @Test fun bothOpaqueBarsCommitPointerSelectionBeforeTintSettlesAndZeroUsesTheSameTerminal() = onMotionTestThread { runTest {
        val failures = mutableListOf<String>()
        val terminals = mutableMapOf<Boolean, List<IntArray>>()
        val terminalMasks = mutableMapOf<Boolean, List<List<Int>>>()
        val channels = intArrayOf(16, 8, 0)
        for (floating in listOf(false, true)) for (scale in listOf(1f, 0f)) {
            val selected = mutableStateOf(AppDestination.HOME)
            val requests = mutableListOf<AppDestination>()
            var committedAt: Long? = null
            var from = 0; var target = 0; var surface = 0; var indicatorBackground = 0
            var rawFrom = Color.Unspecified; var rawTarget = Color.Unspecified
            var reference by mutableStateOf<Triple<TextLayoutResult, Rect, Int>?>(null)
            var referenceTint by mutableStateOf(Color.Black)
            val referenceTag = "s5-bottom-target-reference"
            val context = "floating=$floating scale=$scale"
            fun expect(value: Boolean, message: String) { if (!value) failures += "$context: $message" }
            val env = MotionTestEnvironment(testScheduler, 390, 300, scale) {
                SoftVoWiFiTheme(false) {
                    val colors = LocalSoftVoWiFiColors.current; val scheme = MiuixTheme.colorScheme
                    SideEffect {
                        from = (if (floating) colors.textPrimary else scheme.onSurfaceContainer
                            .copy(alpha = NavigationBarDefaults.UnselectedAlpha).compositeOver(colors.surface)).toArgb()
                        target = (if (floating) scheme.primary else scheme.onSurfaceContainer).toArgb()
                        rawFrom = if (floating) colors.textPrimary else scheme.onSurfaceContainer.copy(alpha = NavigationBarDefaults.UnselectedAlpha)
                        rawTarget = if (floating) scheme.primary else scheme.onSurfaceContainer
                        surface = colors.surface.toArgb()
                        indicatorBackground = scheme.primary.copy(alpha = .15f).compositeOver(colors.surface).toArgb()
                    }
                    CompositionLocalProvider(LocalDensity provides Density(1f)) {
                        Box(Modifier.fillMaxSize().background(colors.canvas)) {
                            BottomNavigationBar(selected.value, { requests += it; committedAt = testScheduler.currentTime; selected.value = it },
                                appChromeSpec(AppearanceSettings.Default.copy(floatingNavigation = floating,
                                    blurRequested = false, liquidGlassRequested = false), false), null,
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                            reference?.let { (layout, region, background) ->
                                Box(Modifier.align(Alignment.TopStart).size(96.dp, 64.dp)
                                    .background(Color(background)).testTag(referenceTag)) {
                                    // 独立文本布局/绘制，复用已核字号字重而不复用生产段落的原生绘制缓存。
                                    BasicText(layout.layoutInput.text, style = layout.layoutInput.style.copy(color = referenceTint),
                                        modifier = Modifier.graphicsLayer {
                                            translationX = region.left - kotlin.math.floor(region.left)
                                            translationY = region.top - kotlin.math.floor(region.top)
                                        }, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }
            try {
                val label = AppDestination.SETTINGS.label
                var textLayout: TextLayoutResult? = null
                fun tab() = s4Nodes(env).single { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab && s4Matches(it, label) }
                fun geometry(): List<Rect> {
                    val owner = tab().boundsInRoot
                    val text = s4Nodes(env).single { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == label &&
                        it.config.contains(SemanticsActions.GetTextLayoutResult) && owner.contains(it.boundsInRoot.center) }
                    val layouts = mutableListOf<TextLayoutResult>()
                    assertTrue(requireNotNull(text.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts))
                    val layout = layouts.single(); assertEquals(1, layout.lineCount); assertFalse(layout.isLineEllipsized(0))
                    textLayout = layout
                    val bounds = text.boundsInRoot
                    assertEquals(layout.size.width.toFloat(), bounds.width, 1f, "自然文字而非整行ROI")
                    val glyph = Rect(bounds.left, bounds.top, bounds.left + layout.size.width, bounds.top + layout.size.height)
                    val width = if (floating) MiuixIcons.Settings.defaultWidth.value else NavigationBarDefaults.IconSize.value
                    val height = if (floating) MiuixIcons.Settings.defaultHeight.value else NavigationBarDefaults.IconSize.value
                    val top = if (floating) glyph.top - 1f - height else owner.top + NavigationBarDefaults.IconTopPadding.value
                    val icon = Rect(owner.center.x - width / 2, top, owner.center.x + width / 2, top + height)
                    assertTrue(icon.top >= owner.top && icon.bottom <= glyph.top && glyph.bottom <= owner.bottom)
                    return listOf(owner, glyph, icon)
                }
                s4Frames(env, 40)
                val fixed = geometry(); val regions = fixed.drop(1)
                fun sample() = regions.map { s3SmallPatch(env, testScheduler, it) }
                val before = sample()
                assertTrue(s3RgbDistance(from, target) > 24, "主题两端须可区分")
                // 前态容差1才足以区分纯色核与97%覆盖的AA边缘；更大容差会在高对比终色产生8级误差。
                val iconCore = before[1].pixels.indices.filter { s3RgbDistance(before[1].pixels[it], from) <= 1 }
                s4Tap(env, fixed.first().center)
                val start = assertNotNull(committedAt, "必须由真实pointer触发父状态提交")
                repeat(4) { env.advanceFrameBy(0) }
                assertEquals(start, testScheduler.currentTime, "同刻消费不推进150ms期限")
                assertEquals(listOf(AppDestination.SETTINGS), requests); assertEquals(AppDestination.SETTINGS, selected.value)
                assertEquals(true, tab().config.getOrNull(SemanticsProperties.Selected), "颜色未完成时Selected已成立")
                assertEquals(1, s4Nodes(env).count { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab &&
                    it.config.getOrNull(SemanticsProperties.Selected) == true })
                val immediate = sample(); expect(fixed == geometry(), "提交帧不得重排/缩放tab和自然文字/图标定位")
                env.advanceFrameBy(80)
                val middle = sample(); expect(fixed == geometry(), "80ms几何不得改变")
                env.advanceFrameBy(70)
                val end = sample(); expect(fixed == geometry(), "150ms几何不得改变")
                assertEquals(start + 150, testScheduler.currentTime)
                fun background(patch: S3SmallPatch): Int {
                    val value = patch.pixels.toList().groupingBy { it }.eachCount().maxBy { it.value }.key
                    assertTrue(s3RgbDistance(value, surface) <= 2 || floating && s3RgbDistance(value, indicatorBackground) <= 2,
                        "采样前提：固定自然区域背景必须来自主题surface/原opaque指示器，$context bg=${value.toUInt().toString(16)}")
                    return value
                }
                val endBackground = background(end[0]); val middleBackground = background(middle[0])
                val layout = assertNotNull(textLayout)
                assertEquals(if (floating) FontWeight.Normal else FontWeight.Bold, layout.layoutInput.style.fontWeight,
                    "原Normal→Bold合同保留；不能把before的Normal覆盖率当作选后Bold模板")
                reference = Triple(layout, regions[0], endBackground)
                referenceTint = rawTarget
                var referenceBounds: Rect? = null
                var referencePixels: S3SmallPatch? = null
                var referenceMask = emptyList<Int>()
                repeat(6) {
                    if (referenceMask.size >= 4) return@repeat
                    env.advanceFrameBy(0)
                    val node = s4Nodes(env, true).singleOrNull {
                        it.config.getOrNull(SemanticsProperties.TestTag) == referenceTag
                    } ?: return@repeat
                    val placed = node.boundsInRoot
                    referenceBounds = placed
                    assertEquals(96f, placed.width, .01f, "采样前提：独立Canvas必须真实placed为96×64")
                    assertEquals(64f, placed.height, .01f)
                    assertEquals(kotlin.math.floor(placed.left), placed.left, "参考原点须像素对齐，保留原glyph子像素相位")
                    assertEquals(kotlin.math.floor(placed.top), placed.top)
                    val actualRegion = Rect(placed.left, placed.top,
                        placed.left + end[0].width, placed.top + end[0].height)
                    assertTrue(actualRegion.right <= placed.right && actualRegion.bottom <= placed.bottom,
                        "采样前提：只能从已放置参考Canvas内取对应自然glyph区域")
                    val pixels = s3SmallPatch(env, testScheduler, actualRegion)
                    referencePixels = pixels
                    referenceMask = pixels.pixels.indices.filter { s3Coverage(pixels.pixels[it], endBackground, target) >= .15f }
                }
                assertEquals(start + 150, testScheduler.currentTime, "独立小参考不得推迟150ms采样")
                val referencePalette = referencePixels?.pixels?.toList()?.groupingBy { it }?.eachCount()?.entries
                    ?.sortedByDescending { it.value }?.take(6)?.joinToString { "${it.key.toUInt().toString(16)}:${it.value}" }
                println("S5_TINT_REFERENCE|$context|bounds=$referenceBounds|palette=$referencePalette|mask=${referenceMask.size}|target=${target.toUInt().toString(16)}")
                assertNotNull(referenceBounds, "采样前提：六个同刻帧内必须出现唯一已placed参考Canvas，不能从猜测原点空白取产品RED")
                val referencePatch = assertNotNull(referencePixels, "采样前提：必须真实绘制并采到参考Canvas")
                assertEquals(end[0].width to end[0].height, referencePatch.width to referencePatch.height)
                val textMask = referenceMask
                assertTrue(textMask.size >= 4, "采样前提：独立目标字形必须有可辨AA覆盖，不要求12sp CJK存在实心像素")
                println("S5_TINT_SAMPLE|$context|leaf=1|core=${iconCore.size}|region=${regions[1]}")
                assertTrue(iconCore.size >= 4, "采样前提：图标仍需真实已知fromTint纯色核心；$context core=${iconCore.size}")
                val masks = listOf(textMask, iconCore)
                // Compose颜色按Oklab插值；文字AA还由原生renderer做gamma混色，不能强加RGB直线盒。
                reference = Triple(layout, regions[0], middleBackground)
                referenceTint = lerp(rawFrom, rawTarget, MotionTokens.EaseStandard.transform(80f / MotionTokens.DurFast))
                repeat(6) { env.advanceFrameBy(0) }
                val placedReference = assertNotNull(referenceBounds)
                val expectedMiddle = s3SmallPatch(env, testScheduler, Rect(placedReference.left, placedReference.top,
                    placedReference.left + end[0].width, placedReference.top + end[0].height))
                assertEquals(start + 150, testScheduler.currentTime, "中帧参考同刻生成，不能推迟已保存的150ms终帧")
                val actualTerminal = if (scale == 0f) immediate else end
                for (leaf in regions.indices) {
                    val mask = masks[leaf]; val name = if (leaf == 0) "文字" else "图标"
                    fun terminal(index: Int) = if (leaf == 0) referencePatch.pixels[index] else target
                    val endError = mask.maxOf { s3RgbDistance(end[leaf].pixels[it], terminal(it)) }
                    expect(endError <= 5, "$name 150ms未到独立已知主题终色；error=$endError")
                    if (scale == 0f) {
                        expect(mask.all { s3RgbDistance(immediate[leaf].pixels[it], terminal(it)) <= 5 }, "$name 零动画同刻未到终色")
                    } else if (leaf == 0) {
                        val middleError = mask.maxOf { s3RgbDistance(middle[0].pixels[it], expectedMiddle.pixels[it]) }
                        expect(middleError <= 5, "文字80ms须匹配独立目标色/规范曲线的真实AA参考；error=$middleError")
                        assertTrue(mask.any { s3RgbDistance(expectedMiddle.pixels[it], referencePatch.pixels[it]) > 3 },
                            "已知80ms参考必须确有可辨中间色，不能用终值作oracle")
                        expect(mask.any { s3RgbDistance(middle[0].pixels[it], referencePatch.pixels[it]) > 3 },
                            "文字80ms不能瞬切到150ms终值")
                    } else {
                        fun endpoints(index: Int): Pair<Int, Int> {
                            return from to target
                        }
                        val measurable = mask.filter { val (a, b) = endpoints(it); s3RgbDistance(a, b) > 6 }
                        val progress = measurable.map { val (a, b) = endpoints(it); s3Coverage(middle[leaf].pixels[it], a, b).toDouble() }.average()
                        val interior = measurable.count { index ->
                            val pixel = middle[leaf].pixels[index]
                            val (fromShade, toShade) = endpoints(index)
                            channels.all { shift -> val a = fromShade ushr shift and 255; val b = toShade ushr shift and 255
                                (pixel ushr shift and 255) in (minOf(a, b) - 5)..(maxOf(a, b) + 5) } &&
                                s3RgbDistance(pixel, fromShade) > 3 && s3RgbDistance(pixel, toShade) > 3
                        }
                        expect(progress > .03 && progress < .97 && interior >= mask.size * .8,
                            "$name 80ms须为同位置固定glyph覆盖的颜色中帧，不能瞬切/空图/背景滑块替代；progress=$progress covered=$interior/${mask.size}")
                    }
                    if (leaf == 0 && scale == 0f) println("S5_TINT_SAMPLE|$context|leaf=0|aa=${mask.size}|endError=$endError|region=${regions[0]}")
                }
                val core = masks.indices.map { leaf -> masks[leaf].map { actualTerminal[leaf].pixels[it] }.toIntArray() }
                if (scale == 1f) { terminals[floating] = core; terminalMasks[floating] = masks } else core.forEachIndexed { leaf, pixels ->
                    expect(terminalMasks.getValue(floating)[leaf] == masks[leaf], "零动画必须比较同一glyph像素位置：leaf=$leaf")
                    val normal = terminals.getValue(floating)[leaf]
                    expect(normal.size == pixels.size && pixels.indices.all { s3RgbDistance(pixels[it], normal[it]) <= 5 }, "零动画与正常150ms终值不一致：leaf=$leaf")
                }
                assertEquals(listOf(AppDestination.SETTINGS), requests, "小参考绘制与颜色采样不能新增选择业务")
            } finally { env.close() }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    } }
}
