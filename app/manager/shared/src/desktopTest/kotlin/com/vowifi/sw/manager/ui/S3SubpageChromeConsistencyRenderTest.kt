package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import top.yukonga.miuix.kmp.basic.Text
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.test.*

/** Real chrome and scrolling content. Flat-colour occlusion tests surface blend, not a GPU
 * blur kernel; an invalid Home Backdrop control is a sampling precondition, never product RED. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class S3SubpageChromeConsistencyRenderTest {
    @Test
    fun ordinaryBlurSettingsOccludesBroadColourLikeTheFoldedHomeHeader() = onMotionTestThread { runTest {
        val home = measure(testScheduler, root = true, ordinaryBlur = true)
        assertTrue(home.ratio in 0.01..0.16,
            "Backdrop/折叠材料采样前提：普通Blur首页须实际采到正文变化并有折叠遮挡；Raster不支持不能算产品RED；$home")
        val settings = measure(testScheduler, root = false, ordinaryBlur = true)
        assertTrue(settings.ratio <= home.ratio + .05,
            "同普通Blur、液态关闭时，设置顶栏不应比折叠首页明显更透；home=$home settings=$settings")
    } }

    @Test
    fun settingsFallbackOccludesUnderpassingContentLikeTheFoldedHomeHeader() = onMotionTestThread { runTest {
        val home = measure(testScheduler, root = true, ordinaryBlur = false)
        assertTrue(home.changed <= 3 && home.canvasError <= 3,
            "对照前提：实际首页折叠顶栏须为稳定不透明fallback；$home")
        val settings = measure(testScheduler, root = false, ordinaryBlur = false)
        assertTrue(settings.changed <= home.changed + 3 && settings.canvasError <= 3,
            "无模糊回退的设置顶栏不能透出真实滚动正文；home=$home settings=$settings")
    } }

    @Test
    fun theSettingsTitleKeepsOneSharpForegroundWhileRealContentPassesUnderIt() = onMotionTestThread { runTest {
        Driver(testScheduler, false, ordinaryBlur = true).use { d ->
            d.prepareUnderpass()
            val title = d.titleBounds()
            val original = d.patch(title)
            val core = original.indices.filter { rgb(original[it], d.foreground) <= 3 }
            assertTrue(core.size >= 8, "字形采样前提：真实标题至少有8个实色核心点")
            repeat(3) {
                val before = d.axis()
                d.swapBody(); d.wheel(2f); d.frames(2)
                assertTrue(d.axis() > before, "输入前提：正文必须真实继续滚动")
                assertTrue(d.hasUnderpassingBand(), "几何前提：真实正文仍在标题下方")
                assertEquals(1, d.titleNodes().size, "设置顶栏只暴露一份当前标题")
                val current = d.titleBounds()
                assertEquals(title.left, current.left, .1f)
                assertEquals(title.top, current.top, .1f)
                assertEquals(title.size, current.size)
                val pixels = d.patch(current)
                assertEquals(original.size, pixels.size)
                assertTrue(core.all { index -> rgb(pixels[index], d.foreground) <= 3 },
                    "真实标题核心不可随下穿内容换色/滚动而变淡或重影；frame=$it")
            }
        }
    } }

    private data class Probe(val changed: Int, val body: Int, val canvasError: Int) {
        val ratio get() = changed.toDouble() / body
    }
    private fun measure(scheduler: TestCoroutineScheduler, root: Boolean, ordinaryBlur: Boolean): Probe =
        Driver(scheduler, root, ordinaryBlur).use { d ->
            d.prepareUnderpass()
            val before = d.pixel(d.headerPoint()); val body = d.pixel(Offset(350f, 440f))
            d.swapBody(); d.frames(2)
            val after = d.pixel(d.headerPoint()); val delta = rgb(body, d.pixel(Offset(350f, 440f)))
            assertTrue(delta > 150, "采样前提：实际正文必须已换色；root=$root ordinaryBlur=$ordinaryBlur")
            Probe(rgb(before, after), delta, maxOf(rgb(before, d.canvas), rgb(after, d.canvas)))
        }

    private class Driver(private val scheduler: TestCoroutineScheduler, private val root: Boolean, ordinaryBlur: Boolean) : AutoCloseable {
        private val changed = mutableStateOf(false)
        private val spec = appChromeSpec(AppearanceSettings.Default.copy(blurRequested = ordinaryBlur,
            liquidGlassRequested = false, predictiveBackRequested = false, hapticsEnabled = true), blurSupported = ordinaryBlur)
        init {
            assertEquals(ordinaryBlur, spec.effectiveBlur, "材质接线前提")
            assertFalse(spec.effectiveLiquidGlass || spec.communicationLiquidGlass, "液态shader不属于本次对照")
        }
        private val back = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher()
        }
        private val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        private val window = object : WindowInfo {
            override val isWindowFocused = true
            override val containerSize = IntSize(390, 520)
            override val containerDpSize = DpSize(390.dp, 520.dp)
        }
        var canvas = 0
            private set
        var foreground = 0
            private set
        private val body: LazyListScope.() -> Unit = {
            items(80, key = { "chrome-band-$it" }) { index ->
                Box(Modifier.fillMaxWidth().height(80.dp)
                    .background(if (changed.value) Color(0xFFE02090) else Color(0xFF1078C0))
                    .testTag("chrome-band-$index")) {
                    Text("下穿正文 $index", Modifier.align(androidx.compose.ui.Alignment.CenterStart))
                }
            }
        }
        private val environment = MotionTestEnvironment(scheduler, 390, 520) {
            CompositionLocalProvider(LocalWindowInfo provides window, LocalLifecycleOwner provides lifecycle,
                LocalNavigationEventDispatcherOwner provides back) {
                SoftVoWiFiTheme(false) {
                    canvas = LocalSoftVoWiFiColors.current.canvas.copy(alpha = 1f).toArgb()
                    foreground = LocalSoftVoWiFiColors.current.textPrimary.toArgb()
                    CompositionLocalProvider(LocalSoftVoWiFiChromeEnvironment provides SoftVoWiFiChromeEnvironment(spec, null)) {
                        // The exact AppPageTopBar production path used by Home, not a hand-drawn oracle.
                        if (root) AppPageScaffold(TITLE, content = body)
                        else AppSubpageScene(AppRoute.SettingsDiagnostics, spec, titleOverride = TITLE, onBack = {}) {
                            AppPageScaffold("诊断", showTopBar = false, content = body)
                        }
                    }
                }
            }
        }
        fun frames(count: Int) { repeat(count) { environment.advanceFrameBy() } }
        private fun nodes(): List<SemanticsNode> {
            fun descend(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::descend)
            return environment.scene.semanticsOwners.flatMap { descend(it.rootSemanticsNode) }
        }
        fun titleNodes() = nodes().filter { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == TITLE } == true &&
                n.boundsInRoot.width > 0f && n.boundsInRoot.height > 0f &&
                n.boundsInRoot.top >= 0f && n.boundsInRoot.bottom <= 80f
        }
        fun titleBounds(): Rect {
            val titles = titleNodes()
            assertTrue(titles.isNotEmpty(), "标题采样前提：必须得到实际小顶栏可见文字矩形；root=$root")
            return titles.minBy { it.boundsInRoot.height }.boundsInRoot
        }
        private fun list() = nodes().single { it.config.contains(SemanticsActions.ScrollBy) }
        fun axis() = list().config[SemanticsProperties.VerticalScrollAxisRange].value()
        fun wheel(delta: Float) { environment.scroll(Offset(195f, 380f), Offset(0f, delta)) }
        fun headerPoint() = Offset(350f, titleBounds().center.y)
        fun hasUnderpassingBand(): Boolean {
            if (titleNodes().isEmpty()) return false
            val point = headerPoint()
            return nodes().any { n -> n.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("chrome-band-") == true &&
                n.boundsInRoot.contains(point) }
        }
        fun prepareUnderpass() {
            frames(12)
            repeat(40) {
                if (axis() <= 0f || !hasUnderpassingBand()) { wheel(4f); frames(3) }
            }
            frames(24)
            assertTrue(axis() > 0f, "滚动采样前提：真实指针必须移动该列表；root=$root")
            assertTrue(hasUnderpassingBand(), "下穿采样前提：真实正文key必须覆盖顶栏旁的采样点；root=$root")
            val title = titleBounds()
            assertTrue(title.height in 12f..40f && title.bottom <= 80f,
                "折叠采样前提：首页应已到小标题几何，而非大标题展开态；root=$root bounds=$title")
            assertTrue(patch(title).count { rgb(it, foreground) <= 3 } >= 8,
                "标题绘制前提：所选小标题须实际绘出，不能取alpha=0的大/小标题语义；root=$root")
        }
        fun swapBody() { changed.value = !changed.value }
        fun pixel(point: Offset): Int = environment.scene.render(scheduler.currentTime * 1_000_000L).use { image ->
            Bitmap.makeFromImage(image).use { it.getColor(point.x.toInt(), point.y.toInt()) }
        }
        fun patch(bounds: Rect): IntArray = environment.scene.render(scheduler.currentTime * 1_000_000L).use { image ->
            val left = bounds.left.toInt(); val top = bounds.top.toInt()
            val width = ceil(bounds.right).toInt() - left
            val height = ceil(bounds.bottom).toInt() - top
            assertTrue(width in 1..240 && height in 1..64, "局部标题采样前提：不读取整图")
            Bitmap.makeFromImage(image).use { bitmap ->
                IntArray(width * height) { i -> bitmap.getColor(left + i % width, top + i / width) }
            }
        }
        override fun close() { environment.close(); back.navigationEventDispatcher.dispose() }
    }

    private companion object {
        const val TITLE = "材质基准"
        fun rgb(a: Int, b: Int): Int = listOf(16, 8, 0).sumOf { shift ->
            abs(((a ushr shift) and 255) - ((b ushr shift) and 255))
        }
    }
}
