package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.ConfigurationApplyAcceptance
import com.vowifi.sw.manager.model.ConfigurationApplyOutcome
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationIdentitySource
import com.vowifi.sw.manager.data.ConfigurationCodec
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.ui.ConfigurationEditor
import com.vowifi.sw.manager.ui.ConfigurationEditorDraft
import com.vowifi.sw.manager.ui.ConfigurationEditorModel
import com.vowifi.sw.manager.ui.SoftVoWiFiTheme
import com.vowifi.sw.manager.ui.configurationSourceSummary
import com.vowifi.sw.manager.ui.hasIdentityRealmOverride
import top.yukonga.miuix.kmp.basic.Scaffold
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class ConfigurationPolicyRenderTest {
    @Test fun summariesUseReportedSourcesWithoutInferringRegistrationOrAutomaticIdentity() {
        val ready = assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("ready").state.value)
        val baseline = ready.instances.first().copy(imsRealm = "")
        for ((source, expected) in listOf(
            "carrier" to "IMS 参数自动继承运营商策略、身份或默认值",
            "user" to "IMS 参数含手动覆盖；其余按已有配置",
            "identity" to "按已有配置（IMS 参数来自身份或默认值）",
            "unknown" to "按已有配置（部分参数来源未知）",
        )) {
            val instance = baseline.copy(imsPolicy = baseline.imsPolicy.copy(digestRealmSource = source))
            assertEquals(expected, configurationSourceSummary(instance))
            assertFalse(hasIdentityRealmOverride(instance))
        }
        val legacy = baseline.copy(imsRealm = "old.example.test", identitySource = ConfigurationIdentitySource.LEGACY)
        assertTrue(hasIdentityRealmOverride(legacy))
        assertEquals("按已有配置（含已有 IMS 域值）", configurationSourceSummary(legacy))
        assertFalse(hasIdentityRealmOverride(legacy.copy(imsPolicy = legacy.imsPolicy.copy(digestRealmSource = "carrier"))))
    }

    @Test fun inheritedCarrierAndExplicitSecurityUseAccurateProductionCopy() {
        val ready = assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("ready").state.value)
        for ((security, source) in listOf("swu_udp_aka" to "carrier", "tcp_ipsec_3gpp" to "default", "tcp_ipsec_3gpp" to "user")) {
            val baseline = ready.instances.first()
            val digestSource = if (source == "default") "identity" else source
            val instance = baseline.copy(lifecycle = ConfigurationLifecycle.STOPPED,
                imsPolicy = baseline.imsPolicy.copy(security = security, securitySource = source,
                    digestRealm = "ims.spark.co.nz", digestRealmSource = digestSource))
            val draft = ConfigurationEditorDraft(instance)
            var submissions = 0
            var backend = instance
            var patch = ""
            val scene = ImageComposeScene(width = 375, height = 2800, density = Density(1f)) {
                SoftVoWiFiTheme(forceDark = false) {
                    Scaffold {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
                            ConfigurationEditor(ConfigurationEditorModel(instance, ready.copy(instances = listOf(instance)), true, null),
                                draft, expanded = true,
                                onSubmit = { _, next, complete ->
                                    submissions++
                                    patch = ConfigurationCodec.encodeChanges(ConfigurationEditorDraft(backend).overrides(), next)
                                    backend = backend.copy(imsPolicy = backend.imsPolicy.copy(
                                        digestRealm = next.imsDigestRealm?.ifEmpty { "ims.spark.co.nz" }.orEmpty(),
                                        digestRealmSource = if (next.imsDigestRealm.isNullOrEmpty()) "carrier" else "user"))
                                    complete(ConfigurationApplyOutcome.Confirmed(backend))
                                    ConfigurationApplyAcceptance.Accepted
                                })
                        }
                    }
                }
            }
            try {
                repeat(8) { scene.render((it + 1) * 100_000_000L).close() }
                val basicTexts = nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                assertTrue("高级设置" in basicTexts)
                assertTrue(basicTexts.any { it.contains("配置就绪不代表注册成功") })
                assertFalse("DNS" in basicTexts)
                assertFalse("IMS realm" in basicTexts)
                assertFalse("Digest认证域（可选）" in basicTexts)
                click(scene, "高级设置")
                repeat(8) { scene.render((it + 10) * 100_000_000L).close() }
                val texts = nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                assertTrue("$security\n来源：$source" in texts)
                assertFalse("默认 TCP + IPsec" in texts)
                val selectedLabel = if (source == "user") "TCP + IPsec（显式）" else "自动（运营商策略优先）"
                assertTrue(selectedLabel in texts, "Missing selected label: $selectedLabel")
                assertEquals(if (source == "user") security else "", draft.overrides().imsSipSecurity)
                assertTrue(texts.any { it.startsWith("留空优先继承匹配的运营商策略，未匹配时使用归属国家（Home MCC）") })
                assertTrue("Digest认证域（可选）" in texts)
                assertTrue("IMS realm" in texts)
                assertTrue(texts.any { it.contains("仅用于 challenge / HA1 realm，不改变 IMPI、IMPU 或 REGISTER 地址") })
                assertTrue("已解析 Digest 认证域" in texts)
                assertTrue("ims.spark.co.nz\n来源：$digestSource" in texts)
                assertFalse(draft.dirty)
                assertEquals(0, submissions)
                for ((index, value) in listOf("Digest.Example.test", "").withIndex()) {
                    val input = nodes(scene).last { it.config.getOrNull(SemanticsActions.SetText) != null }
                    assertTrue(requireNotNull(input.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
                    repeat(8) { scene.render((it + 30 + index * 50) * 100_000_000L).close() }
                    click(scene, "高级设置")
                    repeat(8) { scene.render((it + 40 + index * 50) * 100_000_000L).close() }
                    assertEquals(value, draft.imsDigestRealm.text.toString())
                    assertFalse(nodes(scene).any { it.config.getOrNull(SemanticsActions.SetText) != null })
                    assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "放弃修改" } == true })
                    click(scene, "高级设置")
                    repeat(8) { scene.render((it + 50 + index * 50) * 100_000_000L).close() }
                    assertEquals(value, draft.imsDigestRealm.text.toString())
                    click(scene, "高级设置")
                    repeat(8) { scene.render((it + 60 + index * 50) * 100_000_000L).close() }
                    click(scene, "保存配置")
                    assertEquals("""{"imsDigestRealm":"$value"}""", patch)
                    assertEquals(index + 1, submissions)
                    assertFalse(draft.dirty)
                    click(scene, "高级设置")
                    repeat(8) { scene.render((it + 70 + index * 50) * 100_000_000L).close() }
                }
            } finally { scene.close() }
        }
    }

    @Test fun compactPolicyEditorRendersAndDiscardKeepsBackendUnchanged() = render(1f)
    @Test fun largeFontPolicyEditorRendersAndDiscardKeepsBackendUnchanged() = render(2f)

    private fun render(scale: Float) {
        val ready = assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("ready").state.value)
        val instance = ready.instances.first()
        val draft = ConfigurationEditorDraft(instance)
        draft.imsSipSecurity = "swu_udp_aka"
        draft.paniInitial = "wlan-node-country"
        var submissions = 0
        val height = if (scale == 1f) 2800 else 5200
        val scene = ImageComposeScene(width = 375, height = height, density = Density(1f, scale)) {
            SoftVoWiFiTheme(forceDark = scale == 2f) {
                Scaffold {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    ConfigurationEditor(ConfigurationEditorModel(instance, ready, true, null), draft, expanded = true,
                        onSubmit = { _, _, _ -> submissions++; ConfigurationApplyAcceptance.Accepted })
                }
                }
            }
        }
        try {
            repeat(8) { scene.render((it + 1) * 100_000_000L).close() }
            click(scene, "高级设置")
            repeat(8) { scene.render((it + 10) * 100_000_000L).close() }
            val directory = Files.createDirectories(Path.of("build/reports/configuration-policy-render"))
            scene.render(900_000_000L).use { image ->
                requireNotNull(image.encodeToData()).use { data -> Files.write(directory.resolve("policy-${scale.toInt()}x.png"), data.bytes) }
            }
            val texts = nodes(scene).filter { it.config.getOrNull(SemanticsProperties.Text) != null }
            assertTrue(texts.any { it.config[SemanticsProperties.Text].any { text -> text.text == "安全边界警告" } })
            val policyStart = texts.first { it.config[SemanticsProperties.Text].any { text -> text.text == "IMS SIP 安全策略" } }.boundsInRoot.top
            for (node in texts.filter { it.boundsInRoot.top >= policyStart }) {
                val layouts = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                for (layout in layouts) {
                    assertFalse(layout.didOverflowHeight, layout.layoutInput.text.text)
                    for (line in 0 until layout.lineCount) {
                        assertFalse(layout.isLineEllipsized(line), layout.layoutInput.text.text)
                        assertTrue(layout.getLineRight(line) - layout.getLineLeft(line) <= node.boundsInRoot.width + 1f,
                            "${layout.layoutInput.text.text}: glyph width exceeds node")
                    }
                    assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
                    assertTrue(layout.size.height <= node.boundsInRoot.height + 1f, layout.layoutInput.text.text)
                }
            }
            assertEquals(0, submissions)
            click(scene, "放弃修改")
            repeat(8) { scene.render((it + 10) * 100_000_000L).close() }
            click(scene, "取消")
            assertEquals("swu_udp_aka", draft.imsSipSecurity)
            assertEquals("tcp_ipsec_3gpp", instance.imsPolicy.security)
            assertEquals(0, submissions)
            repeat(8) { scene.render((it + 20) * 100_000_000L).close() }
            click(scene, "放弃修改")
            repeat(8) { scene.render((it + 30) * 100_000_000L).close() }
            click(scene, "放弃修改")
            assertEquals("", draft.imsSipSecurity)
            assertEquals("", draft.paniInitial)
            assertEquals(0, submissions)
        } finally { scene.close() }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        return scene.semanticsOwners.flatMap { descend(it.rootSemanticsNode) }
    }

    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).last { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true &&
                node.config.getOrNull(SemanticsActions.OnClick) != null
        }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }
}
