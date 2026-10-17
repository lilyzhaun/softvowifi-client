package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import kotlin.math.abs
import kotlin.test.*

/** The bubble sits on the frosted bar, so it may only refract the bar, never the sharp page under it. */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class LiquidBottomBarBubbleBackdropRenderTest {
    @Test
    fun restingAndDraggedBubbleShowTheFrostedBarInsteadOfThePageBehindIt() = onMotionTestThread { runTest {
        Driver(testScheduler).use { d ->
            assertTrue(d.energy(d.pageProbe()) > 60f, "采样前提：栏外页面须为清晰细条纹")
            val bar = d.energy(d.barProbe())
            assertTrue(bar < 15f, "采样前提：远离气泡的底栏须已真实模糊，当前 $bar")
            val resting = d.energy(d.bubbleProbe())
            d.pressBubble()
            val dragged = d.energy(d.bubbleProbe())
            val values = "静止气泡 $resting，按住气泡 $dragged，底栏 $bar"
            assertTrue(resting < bar + 15f, "静止气泡透到了底栏下方的清晰页面：$values")
            assertTrue(dragged < bar + 15f, "按住拖动的气泡透到了底栏下方的清晰页面：$values")
        }
    } }

    private class Driver(private val scheduler: TestCoroutineScheduler) : AutoCloseable {
        private val spec = appChromeSpec(AppearanceSettings.Default.copy(blurRequested = true, liquidGlassRequested = true,
            predictiveBackRequested = false, hapticsEnabled = true), blurSupported = true)
        init { assertTrue(spec.effectiveLiquidGlass, "材质接线前提：液态玻璃须生效") }
        private val blank = ImageVector.Builder("blank", 24.dp, 24.dp, 24f, 24f).build()
        private val environment = MotionTestEnvironment(scheduler, WIDTH, HEIGHT) {
            SoftVoWiFiTheme(false) {
                val page = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().layerBackdrop(page).drawBehind {
                        var x = 0f
                        while (x < size.width) {
                            drawRect(Color.Black, Offset(x, 0f), Size(STRIPE, size.height))
                            drawRect(Color.White, Offset(x + STRIPE, 0f), Size(STRIPE, size.height))
                            x += STRIPE * 2
                        }
                    })
                    SlidingFloatingBottomBar(selectedIndex = 0, onSelected = {}, spec = spec, backdrop = page,
                        items = List(4) { SlidingBarItem("", blank) },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
                }
            }
        }
        init { frames(30) }

        fun frames(count: Int) { repeat(count) { environment.advanceFrameBy() } }
        private fun tabs(): List<Rect> {
            fun descend(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::descend)
            return environment.scene.semanticsOwners.flatMap { descend(it.rootSemanticsNode) }
                .filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
                .map { it.boundsInRoot }.sortedBy { it.left }
                .also { assertEquals(4, it.size, "采样前提：须找到四个真实底栏标签") }
        }
        private fun core(r: Rect) = Rect(r.left + r.width * 0.3f, r.top + r.height * 0.3f, r.right - r.width * 0.3f, r.bottom - r.height * 0.3f)
        fun bubbleProbe() = core(tabs()[0])
        fun barProbe() = core(tabs()[2])
        fun pageProbe(): Rect { val bar = tabs()[0]; return Rect(bar.left, 8f, bar.right, bar.top - 40f) }
        fun pressBubble() { environment.press(bubbleProbe().center); frames(40) }

        /** Mean horizontal luminance step: sharp 2px stripes are near 255, frosted glass is near 0. */
        fun energy(r: Rect): Float = environment.scene.render(scheduler.currentTime * 1_000_000L).use { image ->
            Bitmap.makeFromImage(image).use { bitmap ->
                var total = 0f; var count = 0
                for (y in r.top.toInt() until r.bottom.toInt()) for (x in r.left.toInt() until r.right.toInt() - 1) {
                    total += abs(luminance(bitmap.getColor(x, y)) - luminance(bitmap.getColor(x + 1, y))); count++
                }
                assertTrue(count > 50, "采样前提：区域须有足够像素")
                total / count
            }
        }
        private fun luminance(argb: Int) = 0.299f * ((argb ushr 16) and 255) + 0.587f * ((argb ushr 8) and 255) + 0.114f * (argb and 255)
        override fun close() = environment.close()
    }

    private companion object {
        const val WIDTH = 420
        const val HEIGHT = 240
        const val STRIPE = 2f
    }
}
