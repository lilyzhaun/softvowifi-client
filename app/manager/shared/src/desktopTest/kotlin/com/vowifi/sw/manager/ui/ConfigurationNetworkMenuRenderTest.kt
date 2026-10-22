package com.vowifi.sw.manager.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.vowifi.sw.manager.desktop.FixtureConfigurationSource
import com.vowifi.sw.manager.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Scaffold

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class ConfigurationNetworkMenuRenderTest {
    @Test fun realRowsAndDialogsShowDefaultsWithoutFillingInputs() {
        val instance = automaticValuesInstance().copy(lifecycle = ConfigurationLifecycle.RUNNING, resolvedSmsc = "+19995550123")
        val draft = ConfigurationEditorDraft(instance)
        val outlets = ConfigurationNetworkOutlets(supported = true,
            automaticDns = mapOf("wifi" to "192.0.2.53:53", "sim1" to "[2001:db8::53]:53"))
        var submissions = 0
        val scene = ImageComposeScene(width = 375, height = 2200) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(instance,
                    ConfigurationUiState.Ready(listOf(instance), networkOutlets = outlets), true, null), draft,
                    onSubmit = { _, _, _ -> submissions++; ConfigurationApplyAcceptance.Unavailable })
            } }
        }
        var frame = 0L
        fun render() { repeat(8) { scene.render(++frame * 100_000_000L).close() } }
        fun hasText(text: String) = nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text } }
        fun assertEmptyEditor() {
            val inputs = nodes(scene).mapNotNull { it.config.getOrNull(SemanticsProperties.EditableText) }
            assertEquals(listOf(""), inputs.map { it.text }, "The automatic value is help text, not an input value")
        }
        try {
            render()
            assertTrue(hasText("自动：192.0.2.53:53（所选网络）"))
            assertTrue(hasText("自动：+19995550123（SIM 卡）"))
            click(scene, "DNS"); render()
            assertTrue(hasText("只对当前所选底层网络生效，每个网络单独保存。留空则使用自动值：192.0.2.53:53（所选网络）。"))
            assertEmptyEditor()
            click(scene, "取消"); render()
            assertFalse(draft.dirty)
            click(scene, "底层网络"); render()
            click(scene, "SIM 1 数据"); render()
            assertTrue(hasText("自动：[2001:db8::53]:53（所选网络）"))
            click(scene, "短信中心"); render()
            assertTrue(hasText("以 + 开头的短信中心号码。留空则使用自动值：+19995550123（SIM 卡）。"))
            assertEmptyEditor()
            click(scene, "取消"); render()
            assertEquals("", draft.smsc.text.toString())
            assertEquals("", draft.dns.text.toString())
            assertEquals(0, submissions)
        } finally { scene.close() }
    }

    @Test fun realDropdownSelectsExactNamesAndRetainsLostUnsavedSelection() {
        val ready = assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("ready").state.value)
        val instance = ready.instances.first().copy(transport = ConfigurationTransport.Interface("Meta"))
        val draft = ConfigurationEditorDraft(instance)
        val outlets = mutableStateOf(ConfigurationNetworkOutlets(listOf(
            ConfigurationNetworkInterface("Meta", true, true, "tun"),
            ConfigurationNetworkInterface("sim1", true, true, "tun"),
            ConfigurationNetworkInterface("tun_down", false, true, "tun"),
        ), true))
        var submissions = 0
        val scene = ImageComposeScene(width = 375, height = 2200) {
            SoftVoWiFiTheme(forceDark = false) {
                Scaffold {
                    ConfigurationSimEditor(ConfigurationEditorModel(instance,
                        ready.copy(instances = listOf(instance), networkOutlets = outlets.value), true, null),
                        draft, onSubmit = { _, _, _ -> submissions++; ConfigurationApplyAcceptance.Unavailable })
                }
            }
        }
        var frame = 0L
        fun render() { repeat(8) { scene.render(++frame * 100_000_000L).close() } }
        try {
            render()
            click(scene, "底层网络")
            render()
            assertFalse(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text.contains("tun_down") } })
            click(scene, "sim1 · UP")
            render()
            assertEquals(ConfigurationTransport.Interface("sim1"), draft.transport)
            assertTrue(nodes(scene).any { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty()
                .any { it.text.endsWith("\n未保存") } }, "改动的网络行标出未保存")
            outlets.value = ConfigurationNetworkOutlets(supported = true)
            render()
            assertEquals(ConfigurationTransport.Interface("sim1"), draft.transport)
            assertTrue(nodes(scene).any { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty()
                .any { it.text.contains("不会自动改选") } })
            click(scene, "底层网络")
            render()
            click(scene, "Wi-Fi")
            render()
            assertEquals(ConfigurationTransport.WIFI, draft.transport)
            assertEquals(0, submissions)
        } finally { scene.close() }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        return scene.semanticsOwners.flatMap { descend(it.rootSemanticsNode) }
    }

    private fun click(scene: ImageComposeScene, text: String) {
        val node = nodes(scene).last { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { value -> value.text == text } }
        val action = generateSequence(node) { it.parent }.mapNotNull { it.config.getOrNull(SemanticsActions.OnClick)?.action }.first()
        assertTrue(action())
    }
}
