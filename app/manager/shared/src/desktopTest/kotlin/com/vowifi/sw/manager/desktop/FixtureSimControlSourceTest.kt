package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EnvironmentDiagnosticsUiState
import com.vowifi.sw.manager.model.RegistrationUiState
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SimPresence
import com.vowifi.sw.manager.ui.SoftVoWiFiTheme
import com.vowifi.sw.manager.ui.homeDashboardItems
import com.vowifi.sw.manager.ui.homeDashboardPresentation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FixtureSimControlSourceTest {
    @Test fun stackedHomeKeepsShortRedPocketTitleOnOneLine() {
        assertHomeTitleFlow("RedPocket", "310", "260", 1f, false, "redpocket")
    }

    @Test fun stackedHomeKeepsShortCjkTitleOnOneLine() {
        assertHomeTitleFlow("中国联通", "460", "01", 1f, false, "cjk")
    }

    @Test fun stackedHomeWrapsLongCjkTitleWithoutClipping() {
        assertHomeTitleFlow("用于验证归属运营商长名称自然换行且完整显示的合成测试", "460", "01", 1f, true, "long-cjk")
    }

    @Test fun stackedHomeWrapsRedPocketAt200PercentWithoutShrinking() {
        assertHomeTitleFlow("RedPocket", "310", "260", 2f, true, "redpocket-200")
    }

    @Test fun stackedHomeWrapsLongNameAt200PercentWithoutClipping() {
        assertHomeTitleFlow("RedPocket Public Long Name Test", "310", "260", 2f, true, "long-name-200")
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    private fun assertHomeTitleFlow(name: String, mcc: String, mnc: String, fontScale: Float, wraps: Boolean, evidence: String) {
        val original = assertIs<SimControlUiState.Ready>(FixtureSimControlSource("matched-running").state.value).instances.single()
        val instance = original.copy(homeCarrierLabel = name, mcc = mcc, mnc = mnc)
        val presentation = homeDashboardPresentation(
            RegistrationUiState.Empty, SimControlUiState.Ready(listOf(instance)), EnvironmentDiagnosticsUiState.Loading, 0,
        )
        val row = presentation.actions.single()
        var stopped: SimHandle? = null
        val scene = ImageComposeScene(width = 344, height = 1800, density = Density(1f, fontScale)) {
            SoftVoWiFiTheme(forceDark = fontScale == 2f) {
                LazyColumn {
                    homeDashboardItems(presentation, {}, { stopped = it }, {}, {},
                        onRefresh = { error("停止回调测试不得刷新") })
                }
            }
        }
        try {
            repeat(8) { scene.render((it + 1) * 100_000_000L).close() }
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            val nodes = scene.semanticsOwners.flatMap { descend(it.rootSemanticsNode) }
            val titles = nodes.filter { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == row.title } == true }
            assertEquals(1, titles.size, "The SIM slot, flag and carrier must share one complete text node")
            val title = titles.single()
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(requireNotNull(title.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts))
            val layout = layouts.single()
            assertEquals(row.title, layout.layoutInput.text.text)
            assertEquals(fontScale, layout.layoutInput.density.fontScale)
            assertTrue(layout.layoutInput.softWrap)
            assertEquals(wraps, layout.lineCount > 1)
            assertFalse(layout.hasVisualOverflow)
            assertEquals(row.title.length, layout.getLineEnd(layout.lineCount - 1))
            assertTrue(layout.size.height <= title.boundsInRoot.height)
            assertTrue(nodes.none { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains("PLMN") } == true },
                "首页不显示PLMN，设置页保留原信息")
            val action = nodes.single { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(row.action.label) == true &&
                    node.config.getOrNull(SemanticsProperties.Role) == Role.Switch &&
                    node.config.getOrNull(SemanticsActions.OnClick) != null
            }
            assertTrue(action.touchBoundsInRoot.height >= 48f, "原48dp触控目标保持；官方轨道绘制高度独立于扩展触控区")
            assertEquals(title.boundsInRoot.center.y, action.boundsInRoot.center.y, 2f,
                "用户批准的新卡片以卡名/开关同顶行，取代旧内容下置动作")
            assertTrue(title.boundsInRoot.right + 12f <= action.boundsInRoot.left,
                "长名自然增高但不得与开关碰撞")
            assertTrue(action.boundsInRoot.bottom <= 1800f)
            assertTrue(requireNotNull(action.config[SemanticsActions.OnClick].action).invoke())
            assertEquals(instance.handle, stopped)
            val directory = Files.createDirectories(Path.of("build/reports/home-title"))
            scene.render(900_000_000L).use { image ->
                requireNotNull(image.encodeToData()).use { data -> Files.write(directory.resolve("$evidence.png"), data.bytes) }
            }
        } finally { scene.close() }
    }

    @Test
    fun dualUsesOpaqueHandlesAndActiveSubscriptionFirstOrdering() {
        val ready = assertIs<SimControlUiState.Ready>(FixtureSimControlSource("dual").state.value)

        assertEquals(listOf("sim-1", "sim-2"), ready.instances.map { it.handle.value })
        assertEquals(listOf(0, 1), ready.instances.map { it.slotIndex })
        assertEquals(SimPresence.ACTIVE_UNCONFIGURED, ready.instances[0].presence)
        assertEquals(SimPresence.ACTIVE_MATCHED, ready.instances[1].presence)
        assertTrue(ready.instances.all { instance ->
            instance.displayLabel.contains("SIM") && !instance.displayLabel.contains("8986")
        })
    }

    @Test
    fun provisionModeMakesBothPublicPlmnsProvisionable() {
        val ready = assertIs<SimControlUiState.Ready>(FixtureSimControlSource("provision").state.value)

        assertEquals(listOf(0, 1), ready.instances.map { it.slotIndex })
        assertEquals(listOf("460", "505"), ready.instances.map { it.mcc })
        assertEquals(listOf("09", "03"), ready.instances.map { it.mnc })
        assertTrue(ready.instances.all { it.presence == SimPresence.ACTIVE_UNCONFIGURED })
        assertTrue(ready.instances.all { it.canProvision })
        assertTrue(ready.instances.all { it.displayLabel.startsWith("SIM ") })
        assertPrivate(ready.instances.joinToString(" "))
    }

    @Test
    fun retainedModesExposeNoPublicInstances() {
        val running = assertIs<SimControlUiState.Ready>(
            FixtureSimControlSource("removed-running").state.value,
        )
        val stopped = assertIs<SimControlUiState.Ready>(
            FixtureSimControlSource("removed-stopped").state.value,
        )

        assertTrue(running.instances.isEmpty())
        assertTrue(stopped.instances.isEmpty())
    }

    @Test
    fun boundedModesExposeTheirTypedTopLevelStates() {
        assertTrue(
            assertIs<SimControlUiState.Ready>(FixtureSimControlSource("empty").state.value)
                .instances.isEmpty(),
        )
        assertIs<SimControlUiState.PermissionDenied>(FixtureSimControlSource("permission").state.value)
        assertIs<SimControlUiState.Failure>(FixtureSimControlSource("failure").state.value)
        assertEquals(SimControlUiState.Loading, FixtureSimControlSource("loading").state.value)
    }

    @Test
    fun startAndStopUseTypedHandleAndUpdateInPlace() {
        val source = FixtureSimControlSource("matched-running")
        val initial = assertIs<SimControlUiState.Ready>(source.state.value).instances.single()

        source.stop(initial.handle)
        val stopped = assertIs<SimControlUiState.Ready>(source.state.value).instances.single()
        assertEquals(initial.handle, stopped.handle)
        assertEquals(EngineLifecycle.STOPPED, stopped.lifecycle)
        assertTrue(stopped.canApply)

        source.start(stopped.handle)
        val running = assertIs<SimControlUiState.Ready>(source.state.value).instances.single()
        assertEquals(initial.handle, running.handle)
        assertEquals(EngineLifecycle.RUNNING, running.lifecycle)
        assertTrue(running.canApply)
    }

    @Test
    fun transitionalAndUnconfiguredRowsCannotApply() {
        listOf(SimPresence.ACTIVE_MATCHED, SimPresence.REMOVED_CONFIG_RETAINED).forEach { presence ->
            assertFalse(fixtureCanApply(presence, EngineLifecycle.STARTING))
            assertFalse(fixtureCanApply(presence, EngineLifecycle.STOPPING))
        }
        assertFalse(fixtureCanApply(SimPresence.ACTIVE_UNCONFIGURED, EngineLifecycle.STOPPED))
        assertFalse(fixtureCanApply(SimPresence.ACTIVE_UNCONFIGURED, EngineLifecycle.RUNNING))
    }

    private fun assertPrivate(text: String) {
        assertFalse(Regex("""\d{15,}""").containsMatchIn(text), text)
        listOf("iccid", "imsi", "imei", "msisdn", "line1", "subId").forEach { token ->
            assertFalse(text.contains(token, ignoreCase = true), text)
        }
    }
}
