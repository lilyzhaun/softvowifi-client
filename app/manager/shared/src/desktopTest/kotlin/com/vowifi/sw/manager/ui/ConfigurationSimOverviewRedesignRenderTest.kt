package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.data.ConfigurationCodec
import com.vowifi.sw.manager.desktop.FixtureConfigurationSource
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.navigation.AppNavigationState
import com.vowifi.sw.manager.ui.navigation.AppNavigator
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.navigation.ConfigurationSection
import com.vowifi.sw.manager.ui.navigation.ConfigurationSimHandle
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import top.yukonga.miuix.kmp.basic.Scaffold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConfigurationSimOverviewRedesignRenderTest {
    @Test fun defaultOverviewShowsOnlyEssentialControlsAndFourGroupedEntries() = onMotionTestThread { runTest {
        val ready = assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("ready").state.value)
        val instance = ready.instances.first().copy(
            lifecycle = ConfigurationLifecycle.RUNNING,
            transport = ConfigurationTransport.AUTO,
            smsc = "",
            epdgFqdn = "",
            imsRealm = "",
            dns = "",
            dnsOverrides = emptyMap(),
        )
        val state = ready.copy(instances = listOf(instance))
        val handle = requireNotNull(ConfigurationSimHandle.parse(instance.instanceId.value))
        val drafts = ConfigurationDraftStore()
        val actions = SettingsConfigurationActions(
            onSubmit = { _, _, _ -> error("must not submit") },
            onProvision = { error("must not provision") },
            onRefresh = { error("must not refresh") },
        )
        MotionTestEnvironment(testScheduler, width = 390, height = 844) {
            SoftVoWiFiTheme(false) {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Scaffold(Modifier.fillMaxSize()) {
                        SettingsConfigurationSimScreen(handle, state, null, actions, drafts)
                    }
                }
            }
        }.use { e ->
            frames(e)
            val all = texts(e)
            assertTrue("运行状态" in all && "SIM 标识" in all && "配置模式" in all)
            assertTrue("全部自动 · 仅需按需调整底层网络" in all)
            assertTrue("常用设置" in all && "底层网络" in all)
            assertTrue("高级与诊断" in all)
            assertTrue("连接与接入" in all && "通话与语音" in all && "协议与安全" in all && "生效状态与协商快照" in all)
            for (hidden in listOf(
                "ePDG 地址",
                "IMS 域",
                "短信中心",
                "SIP 安全策略",
                "初始 PANI",
                "IMS 高级参数",
                "通话信令",
                "语音媒体",
                "解析与运行",
                "当前协商快照",
                "查看本地资格回执",
                "恢复全部高级参数为自动",
            )) {
                assertFalse(hidden in all, "普通用户默认页不应直接堆叠高级或诊断项：$hidden")
            }
        }
    } }

    @Test fun drillDownSharesDraftSurfacesUnsavedAndErrorsAndGuardsLeavingSim() = onMotionTestThread { runTest {
        val ready = assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("ready").state.value)
        var instance by mutableStateOf(
            ready.instances.first().copy(
                lifecycle = ConfigurationLifecycle.STOPPED,
                transport = ConfigurationTransport.AUTO,
                smsc = "",
                epdgFqdn = "",
                imsRealm = "",
                dns = "",
                dnsOverrides = emptyMap(),
                imsProtection = ConfigurationImsProtection(),
                configVersion = 1,
                configRevision = INITIAL,
            ),
        )
        val handle = requireNotNull(ConfigurationSimHandle.parse(instance.instanceId.value))
        val drafts = ConfigurationDraftStore()
        var navState by mutableStateOf(
            AppNavigator.open(
                AppNavigator.push(AppNavigationState.initial(AppRoute.Settings), AppRoute.SettingsConfiguration),
                AppRoute.SettingsConfigurationSim(handle),
            ),
        )
        var writes = 0
        val actions = SettingsConfigurationActions(
            onSubmit = { _, _, _ -> writes++; ConfigurationApplyAcceptance.Accepted },
            onProvision = { error("must not provision") },
            onRefresh = { error("must not refresh") },
            onOpenSection = { sim, section ->
                navState = AppNavigator.open(navState, AppRoute.SettingsConfigurationSimSection(sim, section))
            },
        )
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val spec = appChromeSpec(AppearanceSettings.Default, false)
        MotionTestEnvironment(testScheduler, width = 390, height = 844) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalDensity provides Density(1f)) {
                SoftVoWiFiTheme(false) {
                    val top = navState.topRoute as AppRoute.Subpage
                    AppSubpageScene(top, spec, onBack = { navState = AppNavigator.back(navState) }) {
                        when (top) {
                            is AppRoute.SettingsConfigurationSim -> SettingsConfigurationSimScreen(
                                top.sim, ready.copy(instances = listOf(instance)), null, actions, drafts,
                            )
                            is AppRoute.SettingsConfigurationSimSection -> SettingsConfigurationSimSectionScreen(
                                top.sim, top.section, ready.copy(instances = listOf(instance)), null, actions, drafts,
                            )
                            else -> Scaffold(Modifier.fillMaxSize()) {}
                        }
                    }
                }
            }
        }.use { e ->
            frames(e)
            tap(e, "连接与接入")
            assertEquals(AppRoute.SettingsConfigurationSimSection(handle, ConfigurationSection.CONNECTION), navState.topRoute)
            assertTrue("短信中心" in texts(e))
            tap(e, "短信中心")
            val input = nodes(e).single { it.config.contains(SemanticsActions.SetText) }
            assertTrue(input.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("+100200300")))
            frames(e)
            tap(e, "确定")
            assertTrue("1 项修改未保存" in texts(e), "详情子页内底部保存栏立即出现")

            // Returning from a detail section to the SIM overview must not nag prematurely.
            tapDescription(e, "返回")
            assertEquals(AppRoute.SettingsConfigurationSim(handle), navState.topRoute)
            assertFalse("保存修改？" in texts(e))
            assertTrue("已自定义 1 项高级参数 · 未保存" in texts(e))
            assertTrue("已自定义 1 项 · 未保存" in texts(e))
            assertTrue("1 项修改未保存" in texts(e))

            // Enter protocol section, verify expert notice and invalid state surfacing back on overview.
            tap(e, "协议与安全")
            assertEquals(AppRoute.SettingsConfigurationSimSection(handle, ConfigurationSection.PROTOCOL), navState.topRoute)
            assertTrue("专家参数 · 谨慎修改" in texts(e))
            scrollTo(e, "新建 IMS 保护组")
            tap(e, "新建 IMS 保护组")
            tap(e, "保存配置")
            assertEquals(0, writes)
            assertNotNull(drafts.draftFor(instance).protectionError)
            tapDescription(e, "返回")
            assertEquals(AppRoute.SettingsConfigurationSim(handle), navState.topRoute)
            assertTrue("含无效设置，请进入检查" in texts(e), "无效设置须在总览分组行清晰标出")

            // Leaving the SIM overview with unsaved edits triggers the leave guard.
            tapDescription(e, "返回")
            assertEquals(AppRoute.SettingsConfigurationSim(handle), navState.topRoute)
            assertTrue("保存修改？" in texts(e))
            assertEquals(0, writes)
        }
    } }

    @Test fun resetAdvancedToAutomaticConfirmsPreservesTransportAndSavesPatch() = onMotionTestThread { runTest {
        val ready = assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("ready").state.value)
        var instance by mutableStateOf(
            ready.instances.first().copy(
                lifecycle = ConfigurationLifecycle.STOPPED,
                transport = ConfigurationTransport.WIFI,
                smsc = "",
                epdgFqdn = "epdg.custom.invalid",
                imsRealm = "",
                dns = "",
                dnsOverrides = emptyMap(),
                imsPolicy = ready.instances.first().imsPolicy.copy(
                    security = "swu_udp_aka",
                    securitySource = "user",
                ),
                callSignaling = ConfigurationCallSignaling(includePANI = false),
                configVersion = 1,
                configRevision = INITIAL,
            ),
        )
        val draft = ConfigurationEditorDraft(instance)
        var writes = 0
        var lastOverrides: JsonObject? = null
        MotionTestEnvironment(testScheduler, width = 390, height = 844) {
            SoftVoWiFiTheme(false) {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Scaffold(Modifier.fillMaxSize()) {
                        ConfigurationSimEditor(
                            model = ConfigurationEditorModel(instance, ready.copy(instances = listOf(instance)), true, null),
                            draft = draft,
                            onSubmit = { _, next, complete ->
                                writes++
                                val patch = Json.parseToJsonElement(
                                    ConfigurationCodec.encodeRevisionChanges(ConfigurationEditorDraft(instance).overrides(), next),
                                ).jsonObject
                                lastOverrides = patch.getValue("overrides").jsonObject
                                instance = instance.copy(
                                    epdgFqdn = next.epdgFqdn.orEmpty(),
                                    imsPolicy = instance.imsPolicy.copy(
                                        security = if (next.imsSipSecurity.isNullOrEmpty()) "tcp_ipsec_3gpp" else next.imsSipSecurity,
                                        securitySource = if (next.imsSipSecurity.isNullOrEmpty()) "carrier" else "user",
                                    ),
                                    callSignaling = next.callSignaling,
                                    configRevision = NEXT,
                                )
                                complete(ConfigurationApplyOutcome.Confirmed(instance))
                                ConfigurationApplyAcceptance.Accepted
                            },
                        )
                    }
                }
            }
        }.use { e ->
            frames(e)
            assertTrue("已自定义 3 项高级参数" in texts(e))
            scrollTo(e, "恢复为自动")
            tap(e, "恢复为自动")
            assertTrue("恢复全部高级参数为自动？" in texts(e))
            tap(e, "取消")
            assertFalse(draft.dirty)
            assertEquals(0, writes)

            tap(e, "恢复为自动")
            tapLast(e, "恢复为自动")
            assertEquals(0, writes, "确认恢复自动只写入本地草稿，点击保存后才落盘")
            assertTrue(draft.dirty)
            assertEquals(ConfigurationTransport.WIFI, draft.transport)
            assertEquals("", draft.epdgFqdn.text.toString())
            assertEquals("", draft.imsSipSecurity)
            assertEquals(ConfigurationCallSignaling(), draft.callSignaling)
            assertTrue("保存配置" in texts(e))

            tap(e, "保存配置")
            assertEquals(1, writes)
            assertFalse(draft.dirty)
            val sent = assertNotNull(lastOverrides)
            assertFalse("transport" in sent, "底层网络未被修改")
            assertEquals(JsonPrimitive(""), sent["epdgFqdn"])
            assertEquals(JsonPrimitive(""), sent["imsSipSecurity"])
            assertTrue("callSignaling" in sent)
            scrollTo(e, "全部自动 · 仅需按需调整底层网络", direction = -1f)
            assertFalse("恢复全部高级参数为自动" in texts(e))
        }
    } }

    @Test fun narrowLargeFontDarkModeOverviewRendersWithoutClippingOrEllipsis() = onMotionTestThread { runTest {
        val ready = assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("ready").state.value)
        val instance = ready.instances.first().copy(
            lifecycle = ConfigurationLifecycle.RUNNING,
            transport = ConfigurationTransport.WIFI,
            epdgFqdn = "epdg.custom.invalid",
        )
        val draft = ConfigurationEditorDraft(instance)
        MotionTestEnvironment(testScheduler, width = 320, height = 900) {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                SoftVoWiFiTheme(forceDark = true) {
                    Scaffold(Modifier.fillMaxSize()) {
                        ConfigurationSimEditor(
                            model = ConfigurationEditorModel(instance, ready.copy(instances = listOf(instance)), true, null),
                            draft = draft,
                            onSubmit = { _, _, _ -> error("geometry check must not write") },
                        )
                    }
                }
            }
        }.use { e ->
            frames(e)
            val observed = mutableSetOf<String>()
            repeat(12) {
                val scroll = nodes(e).first { it.config.contains(SemanticsActions.ScrollBy) }
                val viewport = scroll.boundsInRoot
                val placedTexts = nodes(e).filter { node ->
                    node.config.getOrNull(SemanticsProperties.Text) != null &&
                        generateSequence(node.layoutInfo) { it.parentInfo }.all { it.isPlaced } &&
                        node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f
                }
                for (node in placedTexts) {
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                    for (layout in layouts) {
                        assertFalse(layout.didOverflowHeight, layout.layoutInput.text.text)
                        for (line in 0 until layout.lineCount) {
                            assertFalse(layout.isLineEllipsized(line), layout.layoutInput.text.text)
                            assertTrue(layout.getLineRight(line) - layout.getLineLeft(line) <= node.boundsInRoot.width + 1f,
                                layout.layoutInput.text.text)
                        }
                        if (layout.size.height <= node.boundsInRoot.height + 1f) {
                            observed += layout.layoutInput.text.text
                        }
                    }
                }
                val range = requireNotNull(scroll.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange))
                if (range.value() >= range.maxValue()) return@repeat
                scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, viewport.height / 2f)
                frames(e)
            }
            assertTrue(setOf("常用设置", "底层网络", "高级与诊断", "连接与接入", "通话与语音", "协议与安全", "生效状态与协商快照", "恢复全部高级参数为自动").all { it in observed },
                "200% 字号窄屏必须完整呈现所有核心概览元素：$observed")
        }
    } }

    private fun frames(e: MotionTestEnvironment) = repeat(35) { e.advanceFrameBy(16) }

    private fun nodes(e: MotionTestEnvironment): List<SemanticsNode> = e.scene.semanticsOwners.flatMap { owner ->
        fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
        visit(owner.unmergedRootSemanticsNode)
    }

    private fun texts(e: MotionTestEnvironment): List<String> =
        nodes(e).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }

    private fun visible(e: MotionTestEnvironment, text: String): SemanticsNode? = nodes(e).firstOrNull { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true &&
            node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f &&
            node.positionInRoot.y >= 0f && node.boundsInRoot.bottom <= 844f
    }

    private fun scrollTo(e: MotionTestEnvironment, text: String, direction: Float = 1f) {
        repeat(40) {
            if (visible(e, text) != null) return
            nodes(e).first { it.config.contains(SemanticsActions.ScrollBy) }
                .config[SemanticsActions.ScrollBy].action!!.invoke(0f, 220f * direction)
            repeat(5) { e.advanceFrameBy(16) }
        }
        error("text not reachable: $text")
    }

    private fun tap(e: MotionTestEnvironment, text: String) {
        val node = nodes(e).first { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true && it.boundsInRoot.width > 0f }
        val action = generateSequence(node) { it.parent }.firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }
        assertTrue(action(), "未命中可点击节点：$text")
        frames(e)
    }

    private fun tapLast(e: MotionTestEnvironment, text: String) {
        val node = nodes(e).last { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true && it.boundsInRoot.width > 0f }
        val action = generateSequence(node) { it.parent }.firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }
        assertTrue(action(), "未命中可点击节点：$text")
        frames(e)
    }

    private fun tapDescription(e: MotionTestEnvironment, description: String) {
        val node = nodes(e).last { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true }
        val action = generateSequence(node) { it.parent }.firstNotNullOf { it.config.getOrNull(SemanticsActions.OnClick)?.action }
        assertTrue(action(), "未命中描述节点：$description")
        frames(e)
    }

    private companion object {
        const val INITIAL = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val NEXT = "r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
