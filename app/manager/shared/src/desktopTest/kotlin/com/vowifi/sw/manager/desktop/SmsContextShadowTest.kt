package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.ui.smsContextShadow
import com.vowifi.sw.manager.ui.smsContextMenuCompositing
import org.jetbrains.skia.Bitmap
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class SmsContextShadowTest {
    @Test fun smsShadowMustNotCreateAnElevationRenderNode() {
        val modifier = Modifier.smsContextShadow(RoundedCornerShape(16.dp)) { 1f }
        assertFalse(modifier.foldIn(false) { found, element ->
            found || element.javaClass.simpleName == "ShadowGraphicsLayerElement"
        }, "SMS阴影不得再使用会短暂投射矩形的elevation节点")
    }

    @Test fun animatedShadowStaysSoftOutsideTheRoundedOutlineAndClearsAtZero() = smsOnEdt {
        var progress by mutableFloatStateOf(0f)
        val shape = RoundedCornerShape(16.dp)
        val scene = ImageComposeScene(width = 260, height = 180) {
            Box(Modifier.fillMaxSize().background(Color.White).padding(start = 40.dp, top = 40.dp)) {
                Box(Modifier.size(180.dp, 96.dp).smsContextShadow(shape) { progress }.background(Color.White, shape))
            }
        }
        var time = 0L
        try {
            for (value in listOf(0f, .1f, .25f, .5f, .75f, 1f, .75f, .5f, .25f, .1f, 0f)) {
                progress = value
                repeat(3) { time += 16_000_000L; scene.render(time).close() }
                scene.render(time).use { image -> Bitmap.makeFromImage(image).use { pixels ->
                    // Sweep the entire outer ring plus the clipped corners, not only the inside.
                    for (y in 24..152) for (x in 24..236) {
                        val outside = x < 40 || x >= 220 || y < 40 || y >= 136
                        val corner = (x <= 43 || x >= 216) && (y <= 43 || y >= 132)
                        if (outside || corner) {
                            val pixel = pixels.getColor(x, y)
                            val brightness = pixel and 255
                            assertTrue(brightness >= 224, "外圈不得出现深色矩形，alpha必须有界：p=$value, x=$x, y=$y, brightness=$brightness")
                            if (value == 0f) assertEquals(255, brightness, "结束不得残留矩形或阴影")
                        }
                    }
                    assertEquals(-1, pixels.getColor(130, 80), "白色正文表面保持原色")
                    if (value == 1f) assertTrue((pixels.getColor(130, 138) and 255) < 255, "保留向下的轻微投影，不把反馈整个删掉")
                } }
            }
        } finally { scene.close() }
    }

    @Test fun translucentSmsMenuDoesNotClipItsShadowToARectangularAlphaBuffer() = smsOnEdt {
        assertEquals(CompositingStrategy.ModulateAlpha, smsContextMenuCompositing)
        val shape = RoundedCornerShape(16.dp)
        val scene = ImageComposeScene(width = 260, height = 180) {
            Box(Modifier.fillMaxSize().background(Color.White).padding(start = 40.dp, top = 40.dp)) {
                Box(Modifier.size(180.dp, 96.dp).graphicsLayer {
                    alpha = .5f
                    compositingStrategy = smsContextMenuCompositing
                }.smsContextShadow(shape) { 1f }.background(Color.White, shape))
            }
        }
        try {
            repeat(4) { scene.render((it+1)*16_000_000L).close() }
            scene.render(80_000_000L).use { image -> Bitmap.makeFromImage(image).use { pixels ->
                val exterior = pixels.getColor(130, 138) and 255
                assertTrue(exterior in 224..254, "半透明进退中投影应自然延伸到矩形外，而不是被离屏缓冲裁掉：$exterior")
            } }
        } finally { scene.close() }
    }
}
