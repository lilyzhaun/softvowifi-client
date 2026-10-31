@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.testing.*
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.test.*

class HomeSimCardsRenderTest {
    @Test fun eachSimHasASeparateCardWithAnUnlabelledSwitchAndCompactStateMetadata() = onMotionTestThread { runTest {
        val rows = listOf(sim(0), sim(1))
        val presentation = project(rows)
        var background = 0
        var badgeSurface = 0
        var starts = 0
        MotionTestEnvironment(testScheduler, 420, 1400) { SoftVoWiFiTheme(false) {
            val color = MiuixTheme.colorScheme.background
            val chipColor = LocalSoftVoWiFiColors.current.surfaceRaised
            SideEffect { background = color.toArgb(); badgeSurface = chipColor.toArgb() }
            LazyColumn(Modifier.fillMaxSize().background(color)) { homeDashboardItems(presentation, { starts++ }, {}, {}, {}, {}) }
        } }.use { e ->
            s4Frames(e, 20)
            val nodes = s4Nodes(e)
            assertTrue(nodes.none { node -> node.config.getOrNull(SemanticsProperties.Text)?.any {
                it.text == "启动引擎" || it.text == "停止引擎"
            } == true }, "开关本身已表达启停，卡片不再重复绘制动作文字")
            val switches = s4Nodes(e, false).filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch }.sortedBy { it.boundsInRoot.top }
            assertEquals(2, switches.size)
            presentation.actions.forEachIndexed { index, row ->
                val title = s3TextBounds(nodes, row.title)
                val toggle = switches[index].boundsInRoot
                assertEquals(title.center.y, toggle.center.y, 2f, "开关与卡名同行居中，不与整段摘要对齐")
                assertTrue(title.right + 12 <= toggle.left && toggle.width >= 48, "卡名与轨道不能碰撞：title=$title switch=$toggle")
                assertEquals(listOf("启动引擎"), switches[index].config[SemanticsProperties.ContentDescription],
                    "只移除重复可见label，不能移除开关无障碍动作名")
            }
            val status = nodes.filter { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == "已停止" }.sortedBy { it.boundsInRoot.top }
            assertTrue(nodes.none { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text.contains("PLMN") } == true },
                "首页不再显示PLMN，设置中的原信息保持")
            val tunnel = nodes.filter { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text?.startsWith("隧道 ") == true }.sortedBy { it.boundsInRoot.top }
            val ims = nodes.filter { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text?.startsWith("IMS ") == true &&
                it.boundsInRoot.top > s3TextBounds(nodes, "SIM 引擎").bottom }.sortedBy { it.boundsInRoot.top }
            assertEquals(2, status.size); assertEquals(2, tunnel.size); assertEquals(0, ims.size,
                "停止时只有隧道未知，不同时铺开IMS")
            for (index in 0..1) {
                val title = s3TextBounds(nodes, presentation.actions[index].title)
                val icon = nodes.single { node ->
                    node.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("信息") &&
                        kotlin.math.abs(node.boundsInRoot.center.y - status[index].boundsInRoot.center.y) <= 2f
                }.boundsInRoot
                assertEquals(16f, icon.width, .5f, "实际信息图标仍为16dp")
                assertEquals(icon.left, title.left, .5f,
                    "SIM标题起点须与下方信息圆圈图标左缘对齐，不是气泡外框或状态文字")
                assertEquals(44f, status[index].boundsInRoot.left, .5f, "下方气泡和文字不能跟着标题平移")
                // miuix 0.9.3 centers its 49dp track inside our 64dp slot; 404dp is the slot edge.
                assertEquals(49f, switches[index].boundsInRoot.width, .5f, "官方开关轨道宽度保持")
                val switchRight = 420f - 16f - (64f - 49f) / 2f
                assertEquals(switchRight, switches[index].boundsInRoot.right, .5f,
                    "开关仍居中于原64dp槽位，不因标题缩进移动")
                assertEquals(8f, status[index].boundsInRoot.top - 4f - switches[index].touchBoundsInRoot.bottom, .5f,
                    "卡名/48dp开关行与气泡外沿之间只留8dp，不再留原12dp")
                assertEquals(status[index].boundsInRoot.center.y, tunnel[index].boundsInRoot.center.y, 2f,
                    "状态小气泡与隧道在同一摘要行，不能保留原多余信息行/分割线")
                assertTrue(status[index].boundsInRoot.right < tunnel[index].boundsInRoot.left,
                    "摘要内容不能碰撞或互相覆盖")
            }
            // 16dp card inset + half the 12dp sibling gap; a single merged card remains white here.
            e.scene.render(testScheduler.currentTime * 1_000_000L).use { image -> Bitmap.makeFromImage(image).use { bitmap ->
                assertTrue(s3RgbDistance(background, bitmap.getColor(210, (switches[1].boundsInRoot.top - 22).toInt())) <= 2,
                    "两张官方SIM卡片之间必须露出页面背景，不能仍连在同一大卡内")
                status.forEach { node ->
                    val b = node.boundsInRoot
                    assertTrue(s3RgbDistance(badgeSurface, bitmap.getColor((b.left - 4).toInt(), b.center.y.toInt())) <= 2,
                        "已停止须恢复原HomeStatusChip的真实气泡底色，不能只把文字移到同排")
                }
            } }
            // The library exposes the 28dp track in semantics, but its real hit target is 48dp.
            val edge = switches.first().boundsInRoot.center + Offset(0f, 23f)
            assertTrue(edge.y > switches.first().boundsInRoot.bottom)
            s4Tap(e, edge); assertEquals(1, starts, "48dp实际触控区必须覆盖语义轨道之外，而非把轨道高度冒触控高度")
        }
    } }

    @Test fun longCarrierAtDoubleFontKeepsItsIdentityFactsAndCreationActionReadable() = onMotionTestThread { runTest {
        for (unconfigured in listOf(false, true)) {
            val instance = sim(0).copy(homeCarrierLabel = "合成超长归属运营商名称用于自然换行",
                presence = if (unconfigured) SimPresence.ACTIVE_UNCONFIGURED else SimPresence.ACTIVE_MATCHED,
                lifecycle = if (unconfigured) EngineLifecycle.CONFIG_MISSING else EngineLifecycle.STOPPED,
                canProvision = unconfigured)
            val presentation = project(listOf(instance))
            MotionTestEnvironment(testScheduler, 320, 2200) { SoftVoWiFiTheme(true) {
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    LazyColumn { homeDashboardItems(presentation, {}, {}, {}, {}, {}) }
                }
            } }.use { e ->
                s4Frames(e, 20)
                val title = s3TextBounds(s4Nodes(e), presentation.actions.single().title)
                assertTrue(title.left >= 0 && title.right <= 320 && title.bottom < 2200)
                val texts = s4Nodes(e).filter { it.boundsInRoot.top >= title.top &&
                    it.boundsInRoot.bottom <= s3TextBounds(s4Nodes(e), "状态信息").top &&
                    it.config.contains(SemanticsActions.GetTextLayoutResult) }
                assertTrue(texts.size >= 3, "前提：实际身份/状态及连接或创建说明必须呈现")
                assertTrue(s4Nodes(e).none { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text.contains("PLMN") } == true })
                if (!unconfigured) {
                    assertTrue(texts.any { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == "已停止" })
                    assertTrue(texts.any { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text?.startsWith("隧道 ") == true })
                    assertFalse(texts.any { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text?.startsWith("IMS ") == true })
                }
                texts.forEach { node ->
                    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                    assertTrue(layouts.isNotEmpty())
                    layouts.forEach { layout ->
                        val text = layout.layoutInput.text.text
                        // FlowRow's intrinsic pass may retain a full-width paragraph while the
                        // text node shrinks to its actual glyph width. Check real lines and glyph
                        // extents, not paragraph-container width (didOverflowWidth).
                        assertFalse(layout.didOverflowHeight || layout.multiParagraph.didExceedMaxLines,
                            "200%窄屏不能裁掉行：$text")
                        assertEquals(text.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = false),
                            "必须完整排版全部文本：$text")
                        for (line in 0 until layout.lineCount) {
                            assertFalse(layout.isLineEllipsized(line), "不能用省略号隐藏内容：$text")
                            assertTrue(layout.getLineLeft(line) >= -.5f && layout.getLineRight(line) <= layout.size.width + .5f,
                                "实际字形须在节点内：$text line=$line right=${layout.getLineRight(line)} size=${layout.size}")
                        }
                        assertTrue(node.boundsInRoot.left >= 0 && node.boundsInRoot.right <= 320 && node.boundsInRoot.bottom < 2200,
                            "实际文字节点须完整处于窗口内：$text")
                    }
                }
                if (unconfigured) {
                    assertTrue(s4Nodes(e).none { it.config.getOrNull(SemanticsProperties.Role) == Role.Switch })
                    val create = s4Action(e, "创建配置").boundsInRoot
                    assertTrue(create.top >= title.bottom && create.height >= 48 && create.right <= 320)
                }
            }
        }
    } }

    @Test fun currentProgressReplacesThePreviousPhaseAndLongEnglishWrapsWithoutClipping() = onMotionTestThread { runTest {
        for (fontScale in listOf(1f, 2f)) {
            var pending by mutableStateOf(false)
            var successColor = 0
            var runtime by mutableStateOf(EngineRuntimeStatus("CONNECTING", reconnects = 0, ims = "REGISTERING",
                call = "IDLE", transport = "UDP", pcscf = emptyList(), tunnelProgress = "EAP_AKA_AUTHENTICATION"))
            MotionTestEnvironment(testScheduler, if (fontScale == 1f) 420 else 320, 2200) { SoftVoWiFiTheme(fontScale == 2f) {
                val success = LocalSoftVoWiFiColors.current.success.toArgb()
                SideEffect { successColor = success }
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    val presentation = project(listOf(sim(0).copy(lifecycle = EngineLifecycle.RUNNING, runtime = runtime,
                        canStart = false, canStop = true, operationPending = pending)))
                    LazyColumn { homeDashboardItems(presentation, {}, {}, {}, {}, {}) }
                }
            } }.use { e ->
                fun check(summary: String, previous: String?, expectedBadge: String) {
                    s4Frames(e, 4)
                    val nodes = s4Nodes(e)
                    val cardNodes = nodes.filter { it.boundsInRoot.top >= s3TextBounds(nodes, "SIM 引擎").bottom &&
                        it.boundsInRoot.bottom <= s3TextBounds(nodes, "状态信息").top }
                    if (previous != null) assertTrue(cardNodes.none { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == previous } == true })
                    val node = cardNodes.single { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == summary }
                    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                    assertTrue(layouts.isNotEmpty())
                    layouts.forEach { layout ->
                        assertFalse(layout.didOverflowHeight || layout.multiParagraph.didExceedMaxLines)
                        assertEquals(summary.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = false))
                        for (line in 0 until layout.lineCount) {
                            assertFalse(layout.isLineEllipsized(line))
                            assertTrue(layout.getLineLeft(line) >= -.5f && layout.getLineRight(line) <= layout.size.width + .5f)
                        }
                    }
                    assertTrue(node.boundsInRoot.right <= if (fontScale == 1f) 420f else 320f)
                    val badge = s3TextBounds(cardNodes, expectedBadge)
                    if (fontScale == 1f) {
                        assertEquals(badge.center.y, node.boundsInRoot.center.y, 2f)
                        assertTrue(badge.right < node.boundsInRoot.left)
                    }
                }
                check("隧道 EAP-AKA authentication", null, "隧道连接中")
                runtime = runtime.copy(tunnel = "CONNECTED", imsProgress = "SENDING_AUTHENTICATED_REGISTER")
                check("IMS Authenticated REGISTER", "隧道 EAP-AKA authentication", "隧道已连接")
                runtime = runtime.copy(ims = "REGISTERED", imsProgress = "")
                check("IMS 已注册", "IMS Authenticated REGISTER", "运行中")
                // 上面的4帧仍验证即时状态；仅最终颜色像素等原有文字/颜色过渡结束后取样。
                s4Frames(e, 20)
                val cardNodes = s4Nodes(e).filter { it.boundsInRoot.top >= s3TextBounds(s4Nodes(e), "SIM 引擎").bottom }
                assertEquals(1, cardNodes.count { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == "IMS 已注册" },
                    "IMS已注册只在右侧出现一次，不占用最终运行中气泡")
                val badge = s3TextBounds(cardNodes, "运行中")
                e.scene.render(testScheduler.currentTime * 1_000_000L).use { image -> Bitmap.makeFromImage(image).use { bitmap ->
                    var greenPixels = 0
                    for (y in badge.top.toInt() until badge.bottom.toInt()) for (x in badge.left.toInt() until badge.right.toInt()) {
                        if (s3RgbDistance(successColor, bitmap.getColor(x, y)) <= 20) greenPixels++
                    }
                    assertTrue(greenPixels >= 8, "最终运行中文字必须实际采用现有成功绿，不只测tone枚举")
                } }
                pending = true
                check("IMS 已注册", null, "正在处理")
                pending = false
                runtime = runtime.copy(tunnel = "BACKOFF")
                check("隧道 等待重连", "IMS 已注册", "运行中")
            }
        }
    } }

    private fun sim(index: Int) = SimControlInstance(SimHandle("sim-${index + 1}"), "SIM ${index + 1}",
        SimPresence.ACTIVE_MATCHED, index, "合成运营商", "001", "01", EngineLifecycle.STOPPED,
        canStart = true, canStop = false, canApply = true, homeCarrierLabel = "合成线路")
    private fun project(rows: List<SimControlInstance>) = homeDashboardPresentation(RegistrationUiState.Empty,
        SimControlUiState.Ready(rows), EnvironmentDiagnosticsUiState.Ready(DiagnosticCheckStatus.PASS, emptyList()), 0)
}
