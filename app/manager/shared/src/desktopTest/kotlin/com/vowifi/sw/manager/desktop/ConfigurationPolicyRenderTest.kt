package com.vowifi.sw.manager.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import com.vowifi.sw.manager.model.ConfigurationApplyAcceptance
import com.vowifi.sw.manager.model.ConfigurationApplyOutcome
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationIdentitySource
import com.vowifi.sw.manager.data.ConfigurationCodec
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.ui.ConfigurationEditorDraft
import com.vowifi.sw.manager.ui.ConfigurationEditorModel
import com.vowifi.sw.manager.ui.ConfigurationSimEditor
import com.vowifi.sw.manager.ui.configurationSourceLabel
import com.vowifi.sw.manager.ui.SoftVoWiFiTheme
import com.vowifi.sw.manager.ui.configurationSourceSummary
import com.vowifi.sw.manager.ui.hasIdentityRealmOverride
import com.vowifi.sw.manager.ui.navigation.ConfigurationSection
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.runTest
import top.yukonga.miuix.kmp.basic.Scaffold
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
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
                        ConfigurationSimEditor(ConfigurationEditorModel(instance, ready.copy(instances = listOf(instance)), true, null),
                            draft,
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
            var frame = 0L
            fun render() = repeat(8) { scene.render(++frame * 100_000_000L).close() }
            fun texts() = nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            try {
                render()
                val overviewTexts = texts()
                assertTrue("协议与安全" in overviewTexts && "连接与接入" in overviewTexts, "总览只展示清晰的高级分组入口")
                assertFalse("IMS 高级参数" in overviewTexts, "高级参数收进专属子页，不在默认总览平铺")
                click(scene, "协议与安全")
                render()
                val texts = texts()
                assertTrue("IMS 高级参数" in texts, "协议与安全子页完整呈现 IMS 高级参数")
                val securityLabel = if (security == "swu_udp_aka") "SWu 内 UDP + AKA" else "TCP + IPsec"
                assertTrue("当前解析：$securityLabel · ${configurationSourceLabel(source)}" in texts, "磁盘解析值不能冒运行生效，来源以中文写明：$texts")
                assertFalse(texts.any { it.contains("来源：$source") }, "不再直接显示 wire 来源值")
                val selectedLabel = if (source == "user") "TCP + IPsec" else "自动"
                assertTrue(selectedLabel in texts, "Missing selected label: $selectedLabel")
                assertEquals(if (source == "user") security else "", draft.overrides().imsSipSecurity)
                assertTrue("国家参数" in texts && "Digest 认证域" in texts)
                val digestSummary = if (digestSource == "user") "ims.spark.co.nz"
                    else "自动：ims.spark.co.nz（${configurationSourceLabel(digestSource)}）"
                assertTrue(digestSummary in texts, "Digest 行直接给出当前值：$texts")
                assertFalse(texts.any { it.startsWith("已解析") }, "已解析值并入对应行，不再单列一遍")
                assertFalse(draft.dirty)
                assertEquals(0, submissions)
                assertFalse(texts.any { it == "保存配置" || it == "放弃修改" })
                for ((index, value) in listOf("Digest.Example.test", "").withIndex()) {
                    click(scene, "Digest 认证域")
                    render()
                    assertTrue(texts().any { it.contains("仅用于 challenge / HA1 realm，不改变 IMPI、IMPU 或 REGISTER 地址") })
                    val input = nodes(scene).single { it.config.getOrNull(SemanticsActions.SetText) != null }
                    assertTrue(requireNotNull(input.config[SemanticsActions.SetText].action).invoke(AnnotatedString(value)))
                    render()
                    click(scene, "确定")
                    render()
                    assertEquals(value, draft.imsDigestRealm.text.toString())
                    assertFalse(nodes(scene).any { it.config.getOrNull(SemanticsActions.SetText) != null })
                    assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "放弃修改" } == true })
                    click(scene, "保存配置")
                    render()
                    assertEquals("""{"imsDigestRealm":"$value"}""", patch)
                    assertEquals(index + 1, submissions)
                    assertFalse(draft.dirty)
                }
            } finally { scene.close() }
        }
    }

    @Test fun compactPolicyEditorRendersAndDiscardKeepsBackendUnchanged() = render(1f)
    @Test fun largeFontPolicyEditorRendersAndDiscardKeepsBackendUnchanged() = render(2f)

    private fun render(scale: Float) = onMotionTestThread { runTest {
        val ready = assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("ready").state.value)
        val instance = ready.instances.first()
        val draft = ConfigurationEditorDraft(instance)
        draft.imsSipSecurity = "swu_udp_aka"
        draft.paniInitial = "wlan-node-country"
        var submissions = 0
        val height = if (scale == 1f) 2800 else 5200
        val environment = MotionTestEnvironment(testScheduler, width = 375, height = height) {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale)) { SoftVoWiFiTheme(forceDark = scale == 2f) {
                Scaffold {
                    ConfigurationSimEditor(ConfigurationEditorModel(instance, ready, true, null), draft,
                        onSubmit = { _, _, _ -> submissions++; ConfigurationApplyAcceptance.Accepted },
                        section = ConfigurationSection.PROTOCOL)
                }
            } }
        }
        val scene = environment.scene
        fun frames() = repeat(8) { environment.advanceFrameBy(100) }
        try {
            frames()
            val directory = Files.createDirectories(Path.of("/var/tmp/vowifi-ims-policy-ui"))
            scene.render(testScheduler.currentTime * 1_000_000L).use { image ->
                requireNotNull(image.encodeToData()).use { data -> Files.write(directory.resolve("policy-${scale.toInt()}x.png"), data.bytes) }
            }
            // Inspect overlapping real viewports through the footer. A lazy
            // prefetch node may have empty bounds; an edge-clipped node must
            // become fully visible in another viewport before this can pass.
            val checked = mutableSetOf<Int>()
            val pending = mutableMapOf<Int, String>()
            val observed = mutableSetOf<String>()
            var reachedPolicy = false
            var reachedEnd = false
            var unplacedDraftScopeSeen = false
            for (attempt in 0 until 12) {
                val scroll = nodes(scene).first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
                val viewport = scroll.boundsInRoot
                val textNodes = nodes(scene).filter { it.config.getOrNull(SemanticsProperties.Text) != null }
                val placed = textNodes.filter { node ->
                    generateSequence(node.layoutInfo) { it.parentInfo }.all { it.isPlaced }
                }
                unplacedDraftScopeSeen = unplacedDraftScopeSeen || textNodes.any { node ->
                    node !in placed && node.config[SemanticsProperties.Text].any { it.text == "草稿保留范围" } && node.boundsInRoot.height > 0f
                }
                val texts = placed.filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
                val policyStart = texts.firstOrNull { it.config[SemanticsProperties.Text].any { text -> text.text == "SIP 安全策略" } }?.boundsInRoot?.top
                if (policyStart != null) reachedPolicy = true
                for (node in texts.filter { reachedPolicy && it.boundsInRoot.top >= (policyStart ?: viewport.top) }) {
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
                        if (layout.size.height > node.boundsInRoot.height + 1f) {
                            assertTrue(node.boundsInRoot.top <= viewport.top + 1f || node.boundsInRoot.bottom >= viewport.bottom - 1f,
                                "${layout.layoutInput.text.text}: text clipped inside the viewport")
                            if (node.id !in checked) pending[node.id] = "${layout.layoutInput.text.text}: bounds=${node.boundsInRoot}, layout=${layout.size}, viewport=$viewport, step=$attempt"
                        } else {
                            assertTrue(layout.size.height <= node.boundsInRoot.height + 1f)
                            checked += node.id
                            pending.remove(node.id)
                            observed += layout.layoutInput.text.text
                        }
                    }
                }
                val range = requireNotNull(scroll.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange))
                if (range.value() >= range.maxValue()) { reachedEnd = true; break }
                assertTrue(requireNotNull(scroll.config[SemanticsActions.ScrollBy].action).invoke(0f, viewport.height / 2f))
                frames()
            }
            println("policy viewport scale=$scale: unplaced draft-scope node had positive bounds=$unplacedDraftScopeSeen")
            assertTrue(reachedEnd, "必须到达实际页尾")
            assertTrue(pending.isEmpty(), "视口边缘的文字必须曾完整显示：$pending")
            assertTrue(setOf("SIP 安全策略", "安全边界警告", "隧道安全报价", "隧道期限与保活").all { it in observed },
                "协议与安全页核心分组与警告须完整显示：$observed")
            assertEquals(0, submissions)
            val save = clickable(scene, "保存配置").boundsInRoot
            val discard = clickable(scene, "放弃修改").boundsInRoot
            assertActionPair(discard, save, sideBySide = if (scale == 1f) true else null)
            click(scene, "放弃修改")
            frames()
            val cancel = clickable(scene, "取消").boundsInRoot
            val confirm = clickable(scene, "放弃修改").boundsInRoot
            assertActionPair(cancel, confirm, sideBySide = if (scale == 1f) true else null)
            click(scene, "取消")
            assertEquals("swu_udp_aka", draft.imsSipSecurity)
            assertEquals("tcp_ipsec_3gpp", instance.imsPolicy.security)
            assertEquals(0, submissions)
            frames()
            click(scene, "放弃修改")
            frames()
            click(scene, "放弃修改")
            assertEquals("", draft.imsSipSecurity)
            assertEquals("", draft.paniInitial)
            assertEquals(0, submissions)
        } finally { environment.close() }
    } }

    /** Equal-width pair: side by side (secondary left) when labels fit, else stacked full width with primary first. */
    private fun assertActionPair(secondary: Rect, primary: Rect, sideBySide: Boolean?) {
        assertEquals(secondary.width, primary.width, 1f, "两个动作等宽")
        val row = secondary.top == primary.top
        if (sideBySide != null) assertEquals(sideBySide, row, "secondary=$secondary primary=$primary")
        if (row) assertTrue(secondary.right <= primary.left) else assertTrue(primary.bottom <= secondary.top, "堆叠时主动作在上")
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> {
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        return scene.semanticsOwners.flatMap { descend(it.rootSemanticsNode) }
    }

    private fun clickable(scene: ImageComposeScene, label: String): SemanticsNode = nodes(scene).last { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true &&
            node.config.getOrNull(SemanticsActions.OnClick) != null
    }

    private fun click(scene: ImageComposeScene, label: String) {
        val node = clickable(scene, label)
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }
}
