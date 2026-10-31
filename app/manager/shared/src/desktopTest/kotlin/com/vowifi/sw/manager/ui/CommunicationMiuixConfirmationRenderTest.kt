@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.vowifi.sw.manager.desktop.s4SmsHost
import com.vowifi.sw.manager.ui.testing.*
import kotlinx.coroutines.test.runTest
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import kotlin.test.*

// miuix 0.9.3 @ c36fab72391801d1e3ea5a00f966bf16bac28d4c, Apache-2.0:
// DialogContentLayout.kt's public OverlayDialog default exit; not the retired custom T7 DurFast.
internal const val MIUIX_CONFIRMATION_EXIT_MILLIS = 260L

// Supply real window dimensions for the official OverlayDialog slide in a headless scene.
internal fun miuixConfirmationTestWindow(width: Int, height: Int): WindowInfo = object : WindowInfo {
    override val isWindowFocused = true
    override val containerSize = IntSize(width, height)
    override val containerDpSize = DpSize(width.dp, height.dp)
}

class CommunicationMiuixConfirmationRenderTest {
    @Test fun sharedConfirmationMatchesOfficialMiuixTitleSummaryButtonsAndSurface() = onMotionTestThread { runTest {
        val title = "删除会话？"
        val summary = "将删除本机保存的该会话全部短信，无法撤销。"
        val back = object : NavigationEventDispatcherOwner { override val navigationEventDispatcher = NavigationEventDispatcher() }
        val window = miuixConfirmationTestWindow(420, 900)
        try {
            for (dark in listOf(false, true)) {
                var reference by mutableStateOf(true)
                var cancels = 0; var confirms = 0
                MotionTestEnvironment(testScheduler, 420, 900) {
                    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides back, LocalWindowInfo provides window) {
                        s4SmsHost { SoftVoWiFiTheme(dark) {
                            key(reference) {
                                if (reference) OverlayDialog(show = true, title = title, summary = summary, onDismissRequest = {}) {
                                    Row {
                                        TextButton("取消", {}, Modifier.weight(1f), minHeight = 48.dp)
                                        Spacer(Modifier.width(20.dp))
                                        TextButton("删除", {}, Modifier.weight(1f), minHeight = 48.dp,
                                            colors = ButtonDefaults.textButtonColorsPrimary())
                                    }
                                } else CommunicationConfirmation(title, summary, "删除", { cancels++ }, { confirms++ })
                            }
                        } }
                    }
                }.use { e ->
                    fun geometry() = listOf(s3TextBounds(s4Nodes(e), title), s3TextBounds(s4Nodes(e), summary),
                        s4Action(e, "取消").boundsInRoot, s4Action(e, "删除").boundsInRoot)
                    s4Frames(e, 60)
                    val expected = geometry()
                    val surfaceRegion = androidx.compose.ui.geometry.Rect(12f, 850f, 76f, 890f)
                    val expectedPixels = s3SmallPatch(e, testScheduler, surfaceRegion)
                    val primary = s4Action(e, "删除").boundsInRoot
                    val primaryRegion = androidx.compose.ui.geometry.Rect(primary.left + 16f, primary.center.y - 4f,
                        primary.left + 32f, primary.center.y + 4f)
                    val expectedPrimary = s3SmallPatch(e, testScheduler, primaryRegion)
                    reference = false; s4Frames(e, 60)
                    assertEquals(expected, geometry(), "共享确认必须采用官方OverlayDialog默认几何，而非自绘alert：dark=$dark")
                    assertContentEquals(expectedPixels.pixels,
                        s3SmallPatch(e, testScheduler, surfaceRegion).pixels,
                        "实际表面/圆角/标题/按钮必须与同宿主官方参考一致")
                    assertContentEquals(expectedPrimary.pixels, s3SmallPatch(e, testScheduler, primaryRegion).pixels,
                        "确认删除须采用miuix官方蓝色主按钮，不是红字灰底次按钮：dark=$dark")
                    assertEquals(0, confirms); assertEquals(0, cancels)
                    s4Tap(e, s4Action(e, "取消").boundsInRoot.center); s4Frames(e, 30)
                    assertEquals(0, confirms); assertEquals(1, cancels)
                }
            }
        } finally { back.navigationEventDispatcher.dispose() }
    } }
}
