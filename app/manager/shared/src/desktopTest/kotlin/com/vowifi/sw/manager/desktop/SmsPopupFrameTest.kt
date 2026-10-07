package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.ui.*
import org.jetbrains.skia.Bitmap
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class SmsPopupFrameTest {
    @Test fun smsSourceRemainsPaintedAcrossPopupOwnershipAndDoesNotRecordAnotherWindowLayer() {
        var presented by mutableStateOf(false)
        var target: CommunicationContextTarget? = null
        val scene = ImageComposeScene(width = 120, height = 80) {
            val current = rememberCommunicationContextTarget()
            val ownership = presented
            SideEffect { target = current; current.presented = ownership }
            Box(Modifier.size(100.dp, 60.dp).communicationContextSource(current, selected = true,
                hideUnderPreview = false).background(Color.White))
        }
        var time = 0L
        try {
            for (value in listOf(false, true, false, true, false)) {
                presented = value
                repeat(4) {
                    time += 16_000_000L
                    scene.render(time).use { image -> Bitmap.makeFromImage(image).use { pixels ->
                        assertEquals(-1, pixels.getColor(30, 30), "短信原对象不能在另一窗口交接时变成空白")
                    } }
                    assertEquals(IntSize.Zero, requireNotNull(target).layer.size, "短信不跨窗口录制/重用RenderNode")
                }
            }
        } finally { scene.close() }
    }

    @Test fun acceptedCallSourceStillHandsItsDrawingToTheExistingPreviewPath() {
        var presented by mutableStateOf(false)
        val scene = ImageComposeScene(width = 120, height = 80) {
            val target = rememberCommunicationContextTarget()
            val ownership = presented
            SideEffect { target.presented = ownership }
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Box(Modifier.size(100.dp, 60.dp).communicationContextSource(target, selected = true).background(Color.White))
            }
        }
        var time = 0L
        fun color(): Int { repeat(4) { time += 16_000_000L; scene.render(time).close() }; return scene.render(time).use { image ->
            Bitmap.makeFromImage(image).use { it.getColor(30, 30) }
        } }
        try {
            assertEquals(-1, color())
            presented = true; assertEquals(Color.Black.toArgb(), color())
            presented = false; assertEquals(-1, color())
        } finally { scene.close() }
    }
}
