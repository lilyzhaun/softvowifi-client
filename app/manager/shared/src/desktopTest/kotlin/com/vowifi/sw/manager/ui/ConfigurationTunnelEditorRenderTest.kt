package com.vowifi.sw.manager.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import com.vowifi.sw.manager.data.ConfigurationCodec
import com.vowifi.sw.manager.desktop.FixtureConfigurationSource
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.navigation.ConfigurationSection
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import top.yukonga.miuix.kmp.basic.Scaffold
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConfigurationTunnelEditorRenderTest {
    @Test fun realSwuProtocolEditorCancelsLocallyThenSavesAndRemovesOnlyTheSubgroup() = onMotionTestThread { runTest {
        val ready = ready()
        var row by mutableStateOf(ready.instances.first().copy(lifecycle = ConfigurationLifecycle.STOPPED,
            swuAccess = ConfigurationSwuAccess(), configVersion = 1, configRevision = INITIAL))
        val draft = ConfigurationEditorDraft(row)
        var writes = 0
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft,
                    onSubmit = { _, next, complete ->
                        writes++
                        val patch = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(ConfigurationEditorDraft(row).overrides(), next)).jsonObject
                        assertEquals(setOf("swuAccess"), patch.getValue("overrides").jsonObject.keys)
                        val access = patch.getValue("overrides").jsonObject.getValue("swuAccess").jsonObject
                        assertEquals(JsonPrimitive("ims"), access["requestedApn"])
                        assertEquals(JsonPrimitive(1358), access["interfaceMtu"])
                        if (writes == 1) assertEquals(Json.parseToJsonElement("""{"home":"ipv4","roaming":"ipv6"}"""), access["innerProtocol"])
                        else assertFalse("innerProtocol" in access)
                        row = row.copy(swuAccess = next.swuAccess, configRevision = if (writes == 1) NEXT else INITIAL)
                        complete(ConfigurationApplyOutcome.Confirmed(row))
                        ConfigurationApplyAcceptance.Accepted
                    },
                    section = ConfigurationSection.CONNECTION)
            } }
        }.use { e ->
            frames(e); scrollTo(e, "设置 SWu 接入"); tap(e, "设置 SWu 接入")
            assertNotNull(visible(e, "本地内层协议"), "真实接入编辑器必须提供本地/漫游协议选择")
            tap(e, "本地内层协议"); tap(e, "IPv4")
            tap(e, "漫游内层协议"); tap(e, "IPv6")
            tap(e, "取消"); assertFalse(draft.dirty); assertEquals(0, writes)
            tap(e, "设置 SWu 接入")
            tap(e, "本地内层协议"); tap(e, "IPv4")
            tap(e, "漫游内层协议"); tap(e, "IPv6")
            tap(e, "确定"); assertEquals(1, draft.modifiedCount)
            tap(e, "保存配置"); assertEquals(1, writes); assertEquals(ConfigurationEditStatus.Saved, draft.status)
            scrollTo(e, "编辑 SWu 接入"); tap(e, "编辑 SWu 接入")
            tap(e, "本地内层协议"); tap(e, "兼容双栈")
            tap(e, "确定"); assertEquals(1, draft.modifiedCount)
            tap(e, "保存配置"); assertEquals(2, writes); assertEquals(ConfigurationEditStatus.Saved, draft.status)
        }
    } }

    @Test fun realEditorSavesAndExplicitlyClearsTheWholeSwuAccessGroup() = onMotionTestThread { runTest {
        val ready = ready()
        var row by mutableStateOf(ready.instances.first().copy(lifecycle = ConfigurationLifecycle.STOPPED,
            swuAccess = ConfigurationSwuAccess(), configVersion = 1, configRevision = INITIAL))
        val draft = ConfigurationEditorDraft(row)
        var writes = 0
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft,
                    onSubmit = { _, next, complete ->
                        writes++
                        val patch = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(ConfigurationEditorDraft(row).overrides(), next)).jsonObject
                        assertEquals(setOf("swuAccess"), patch.getValue("overrides").jsonObject.keys)
                        assertEquals(if (writes == 1) Json.parseToJsonElement("""{"purpose":"ims","requestedApn":"voice.example.test","interfaceMtu":1340}""")
                            else JsonNull, patch.getValue("overrides").jsonObject["swuAccess"])
                        row = row.copy(swuAccess = next.swuAccess, configRevision = if (writes == 1) NEXT else INITIAL)
                        complete(ConfigurationApplyOutcome.Confirmed(row))
                        ConfigurationApplyAcceptance.Accepted
                    },
                    section = ConfigurationSection.CONNECTION)
            } }
        }.use { e ->
            frames(e); scrollTo(e, "设置 SWu 接入"); tap(e, "设置 SWu 接入")
            fun input(previous: String, value: String, settle: Boolean = true) {
                val field = nodes(e).last { it.config.getOrNull(SemanticsProperties.EditableText)?.text == previous }
                assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
                if (settle) frames(e)
            }
            input("ims", "voice.example.test"); input("1358", "1340", settle = false)
            assertEquals(0, writes)
            assertFalse(draft.dirty)
            tap(e, "确定")
            assertEquals(1, draft.modifiedCount)
            assertEquals("voice.example.test", draft.swuAccess!!.policy!!.requestedApn)
            tap(e, "保存配置")
            assertEquals(1, writes); assertEquals(ConfigurationEditStatus.Saved, draft.status)
            scrollTo(e, "清除 SWu 接入"); tap(e, "清除 SWu 接入"); tap(e, "取消")
            assertFalse(draft.dirty); assertEquals(1, writes)
            tap(e, "清除 SWu 接入"); tap(e, "清除整组")
            assertEquals(ConfigurationSwuAccess(), draft.swuAccess)
            tap(e, "保存配置")
            assertEquals(2, writes); assertEquals(ConfigurationEditStatus.Saved, draft.status)
        }
    } }

    @Test fun savedScrollStateNeverSerializesTunnelChoicesOrTheInMemoryDraft() = onMotionTestThread { runTest {
        val ready = ready()
        val row = ready.instances.first().copy(lifecycle = ConfigurationLifecycle.STOPPED,
            tunnelSecurity = ConfigurationTunnelSecurity(), configVersion = 1, configRevision = INITIAL)
        val policy = ConfigurationTunnelSecurity(ConfigurationTunnelPolicy(listOf(ConfigurationIkeSuite.CBC128_SHA256_DH14), listOf(ConfigurationChildSuite.GCM128)))
        val draft = ConfigurationEditorDraft(row)
        assertTrue(draft.replaceTunnelSecurity(policy))
        val before = draft.overrides()
        val forbidden = ConfigurationIkeSuite.entries.map { it.wire } + ConfigurationChildSuite.entries.map { it.wire } +
            ConfigurationTunnelLayout.entries.map { it.wire }
        fun uiOnly(value: Any?): Boolean = when (value) {
            null, is Boolean, is Int, is Float, is Double -> true
            is State<*> -> uiOnly(value.value)
            is String -> value == row.instanceId.value || value.startsWith("configuration-") && forbidden.none(value::contains)
            is List<*> -> value.all(::uiOnly)
            is Map<*, *> -> value.all { (key, item) -> uiOnly(key) && uiOnly(item) }
            else -> false
        }
        assertFalse(uiOnly(policy))
        assertFalse(uiOnly(draft))
        assertTrue(forbidden.none(::uiOnly))
        assertFalse(uiOnly("configuration-tunnel-ike-${policy.policy!!.ikeSuites.single().wire}"))
        fun scene(registry: SaveableStateRegistry) = MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold { CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft,
                    onSubmit = { _, _, _ -> error("UI state saving must not submit configuration") },
                    section = ConfigurationSection.PROTOCOL)
            } } }
        }
        val registry = SaveableStateRegistry(null, ::uiOnly)
        val saved = scene(registry).use { e ->
            frames(e); scrollTo(e, "Child 套件 1")
            registry.performSave().also { assertTrue(it.values.flatten().all(::uiOnly)) }
        }
        scene(SaveableStateRegistry(saved, ::uiOnly)).use { e ->
            frames(e)
            assertNotNull(visible(e, "Child 套件 1"), "真实新scene应恢复原滚动位置")
            assertEquals(before, draft.overrides())
            assertTrue(draft.dirty)
        }
    } }

    @Test fun realEditorKeepsIncompletePolicyUnsavedThenSubmitsTheOrderedWholeGroup() = onMotionTestThread { runTest {
        val ready = ready()
        var row by mutableStateOf(ready.instances.first().copy(lifecycle = ConfigurationLifecycle.STOPPED,
            tunnelSecurity = ConfigurationTunnelSecurity(), configVersion = 1, configRevision = INITIAL))
        val draft = ConfigurationEditorDraft(row)
        var writes = 0
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft,
                    onSubmit = { _, next, complete ->
                        writes++
                        val patch = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(ConfigurationEditorDraft(row).overrides(), next)).jsonObject
                        assertEquals(setOf("tunnelSecurity"), patch.getValue("overrides").jsonObject.keys)
                        val policy = next.tunnelSecurity!!.policy!!
                        assertEquals(listOf(ConfigurationIkeSuite.CBC256_SHA256_DH14, ConfigurationIkeSuite.CBC128_SHA256_DH14), policy.ikeSuites)
                        assertEquals(listOf(ConfigurationChildSuite.GCM128), policy.childSuites)
                        assertEquals(ConfigurationTunnelLayout.SINGLE_COMBINED, policy.layout)
                        assertEquals(14, policy.childPFSGroup)
                        assertEquals(JsonPrimitive(14), patch.getValue("overrides").jsonObject.getValue("tunnelSecurity").jsonObject["childPFSGroup"])
                        row = row.copy(tunnelSecurity = next.tunnelSecurity, configRevision = NEXT)
                        complete(ConfigurationApplyOutcome.Confirmed(row))
                        ConfigurationApplyAcceptance.Accepted
                    },
                    section = ConfigurationSection.PROTOCOL)
            } }
        }.use { e ->
            frames(e); scrollTo(e, "新建隧道报价"); tap(e, "新建隧道报价")
            tap(e, "保存配置")
            assertEquals(0, writes)
            assertEquals(ConfigurationEditStatus.Invalid, draft.status)
            scrollTo(e, "添加 IKE 套件"); tap(e, "添加 IKE 套件"); tap(e, "CBC128 / SHA256 / DH14")
            scrollTo(e, "添加 IKE 套件"); tap(e, "添加 IKE 套件"); tap(e, "CBC256 / SHA256 / DH14")
            assertEquals(listOf(ConfigurationIkeSuite.CBC128_SHA256_DH14, ConfigurationIkeSuite.CBC256_SHA256_DH14), draft.tunnelSecurity!!.policy!!.ikeSuites)
            // LazyColumn keeps its anchor; the second priority control is below the viewport.
            scrollTo(e, "IKE 优先顺序 · 2"); tap(e, "IKE 优先顺序 · 2"); tap(e, "第 1 位")
            scrollTo(e, "添加 Child 套件"); tap(e, "添加 Child 套件"); tap(e, "GCM128 / AEAD")
            scrollTo(e, "IKE 报价布局", -1f); tap(e, "IKE 报价布局"); tap(e, "合并单报价")
            scrollTo(e, "Child 重键 PFS"); tap(e, "Child 重键 PFS"); tap(e, "MODP 2048 · DH14")
            assertEquals(0, writes)
            assertEquals(1, draft.modifiedCount)
            tap(e, "保存配置")
            assertEquals(1, writes)
            assertEquals(ConfigurationEditStatus.Saved, draft.status)
        }
    } }

    @Test fun realEditorSelectsEsnAsACompleteChildTupleWithoutChangingPfs() = onMotionTestThread { runTest {
        val ready = ready()
        val legacy = ConfigurationTunnelPolicy(listOf(ConfigurationIkeSuite.CBC256_SHA256_DH14),
            ConfigurationChildSuite.entries.filter { !it.esn }, childPFSGroup = 14)
        var row by mutableStateOf(ready.instances.first().copy(lifecycle = ConfigurationLifecycle.STOPPED,
            tunnelSecurity = ConfigurationTunnelSecurity(legacy), configVersion = 1, configRevision = INITIAL))
        val draft = ConfigurationEditorDraft(row)
        var writes = 0
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft,
                    onSubmit = { _, next, complete ->
                        writes++
                        val selected = next.tunnelSecurity!!.policy!!
                        assertEquals(legacy.childSuites + ConfigurationChildSuite.GCM128_ESN, selected.childSuites)
                        assertEquals(14, selected.childPFSGroup)
                        val patch = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(ConfigurationEditorDraft(row).overrides(), next)).jsonObject
                        assertEquals(setOf("tunnelSecurity"), patch.getValue("overrides").jsonObject.keys)
                        row = row.copy(tunnelSecurity = next.tunnelSecurity, configRevision = NEXT)
                        complete(ConfigurationApplyOutcome.Confirmed(row))
                        ConfigurationApplyAcceptance.Accepted
                    },
                    section = ConfigurationSection.PROTOCOL)
            } }
        }.use { e ->
            frames(e); scrollTo(e, "添加 Child 套件"); tap(e, "添加 Child 套件")
            tap(e, "GCM128 / AEAD / ESN")
            assertEquals(0, writes)
            assertTrue(draft.dirty)
            assertEquals(ConfigurationChildSuite.GCM128_ESN, draft.tunnelSecurity!!.policy!!.childSuites.last())
            tap(e, "保存配置")
            assertEquals(1, writes)
            assertEquals(ConfigurationEditStatus.Saved, draft.status)
        }
    } }

    @Test fun unknownGroupRequiresConfirmedClearAndRunningSaveKeepsTheRestartConfirmation() = onMotionTestThread { runTest {
        val ready = ready()
        var row by mutableStateOf(ready.instances.first().copy(lifecycle = ConfigurationLifecycle.RUNNING,
            tunnelSecurity = null, configVersion = 1, configRevision = INITIAL))
        val draft = ConfigurationEditorDraft(row)
        var writes = 0
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft,
                    onSubmit = { _, next, complete ->
                        writes++
                        assertEquals(ConfigurationTunnelSecurity(), next.tunnelSecurity)
                        row = row.copy(tunnelSecurity = next.tunnelSecurity, configRevision = NEXT)
                        complete(ConfigurationApplyOutcome.Confirmed(row))
                        ConfigurationApplyAcceptance.Accepted
                    },
                    section = ConfigurationSection.PROTOCOL)
            } }
        }.use { e ->
            frames(e); scrollTo(e, "清除隧道报价"); tap(e, "清除隧道报价"); tap(e, "取消")
            assertNull(draft.tunnelSecurity)
            tap(e, "清除隧道报价"); tap(e, "清除整组")
            assertEquals(ConfigurationTunnelSecurity(), draft.tunnelSecurity)
            assertFalse(texts(e).contains("新建隧道报价"))
            tap(e, "保存并重启")
            assertEquals(0, writes)
            tap(e, "取消")
            assertEquals(0, writes)
            tap(e, "保存并重启"); tap(e, "保存并重启")
            assertEquals(1, writes)
            assertEquals(ConfigurationEditStatus.Saved, draft.status)
            scrollTo(e, "新建隧道报价")
            assertNotNull(visible(e, "新建隧道报价"))
        }
    } }

    private fun ready() = assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("ready").state.value)
    private fun frames(e: MotionTestEnvironment) = repeat(35) { e.advanceFrameBy(16) }
    private fun nodes(e: MotionTestEnvironment): List<SemanticsNode> = e.scene.semanticsOwners.flatMap { owner ->
        fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
        visit(owner.unmergedRootSemanticsNode)
    }
    private fun texts(e: MotionTestEnvironment) = nodes(e).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    private fun visible(e: MotionTestEnvironment, text: String) = nodes(e).firstOrNull { node ->
        if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } != true || node.boundsInRoot.width <= 0f || node.positionInRoot.y < 0f) false
        else {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.isNotEmpty() && layouts.all { it.size.height <= node.boundsInRoot.height + 1f && node.positionInRoot.y + it.size.height <= 850 }
        }
    }
    private fun tap(e: MotionTestEnvironment, text: String) {
        val node = nodes(e).last { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true && it.boundsInRoot.width > 0f }
        val action = generateSequence(node) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
        assertTrue(action.config[SemanticsActions.OnClick].action!!.invoke()); frames(e)
    }
    private fun scrollTo(e: MotionTestEnvironment, text: String, direction: Float = 1f) {
        repeat(80) {
            if (visible(e, text) != null) return
            nodes(e).first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 200f * direction)
            repeat(5) { e.advanceFrameBy(16) }
        }
        error("text not reachable: $text")
    }
    private companion object {
        const val INITIAL = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val NEXT = "r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
