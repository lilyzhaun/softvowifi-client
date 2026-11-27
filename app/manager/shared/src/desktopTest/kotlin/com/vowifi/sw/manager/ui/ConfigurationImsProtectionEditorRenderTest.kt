package com.vowifi.sw.manager.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
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
class ConfigurationImsProtectionEditorRenderTest {
    @Test fun savedUIStateKeepsOnlyPositionKeysAndRestoresTheInMemoryDraft() = onMotionTestThread { runTest {
        val ready = ready()
        val row = ready.instances.first().copy(lifecycle = ConfigurationLifecycle.STOPPED,
            imsProtection = ConfigurationImsProtection(), configVersion = 1, configRevision = INITIAL)
        val draft = ConfigurationEditorDraft(row)
        val selected = ConfigurationImsProtection(listOf(ConfigurationImsSuite.NULL_SHA1, ConfigurationImsSuite.AES128_SHA1))
        draft.replaceImsProtection(selected)
        fun uiOnly(value: Any?): Boolean = when (value) {
            null, is Boolean, is Int, is Float, is Double -> true
            is State<*> -> uiOnly(value.value)
            is String -> value == row.instanceId.value || value.startsWith("configuration-") && ConfigurationImsSuite.entries.none { it.wire in value }
            is List<*> -> value.all(::uiOnly)
            is Map<*, *> -> value.all { (key, item) -> uiOnly(key) && uiOnly(item) }
            else -> false
        }
        assertFalse(uiOnly(selected)); assertFalse(uiOnly(draft))
        assertFalse(uiOnly("configuration-ims-protection-suite-esp-null-sha1-96"))
        fun scene(registry: SaveableStateRegistry) = MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold { CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft,
                    onSubmit = { _, _, _ -> error("saving UI state must not submit configuration") },
                    section = ConfigurationSection.PROTOCOL)
            } } }
        }
        val registry = SaveableStateRegistry(null, ::uiOnly)
        val saved = scene(registry).use { e ->
            frames(e); scrollTo(e, "IMS 保护组合 2")
            registry.performSave().also { assertTrue(it.values.flatten().all(::uiOnly)) }
        }
        scene(SaveableStateRegistry(saved, ::uiOnly)).use { e ->
            frames(e)
            assertNotNull(visible(e, "IMS 保护组合 2"))
            assertEquals(selected, draft.imsProtection)
            assertTrue(draft.dirty)
        }
    } }

    @Test fun realEditorOnlySubmitsTheCompleteOrderedGroupAfterExplicitChoices() = onMotionTestThread { runTest {
        val ready = ready()
        var row by mutableStateOf(ready.instances.first().copy(lifecycle = ConfigurationLifecycle.STOPPED,
            imsProtection = ConfigurationImsProtection(), configVersion = 1, configRevision = INITIAL))
        val draft = ConfigurationEditorDraft(row)
        var writes = 0
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft,
                    onSubmit = { _, next, complete ->
                        writes++
                        val patch = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(ConfigurationEditorDraft(row).overrides(), next)).jsonObject
                        assertEquals(setOf("imsProtection"), patch.getValue("overrides").jsonObject.keys)
                        assertEquals(listOf(ConfigurationImsSuite.NULL_SHA1, ConfigurationImsSuite.AES128_SHA1), next.imsProtection!!.suites)
                        row = row.copy(imsProtection = next.imsProtection, configRevision = NEXT)
                        complete(ConfigurationApplyOutcome.Confirmed(row))
                        ConfigurationApplyAcceptance.Accepted
                    },
                    section = ConfigurationSection.PROTOCOL)
            } }
        }.use { e ->
            frames(e); scrollTo(e, "新建 IMS 保护组"); tap(e, "新建 IMS 保护组")
            tap(e, "保存配置")
            assertEquals(0, writes)
            assertEquals(ConfigurationEditStatus.Invalid, draft.status)
            assertTrue(configurationEditSummary(draft).contains("请检查"))
            scrollTo(e, "添加 IMS 组合"); tap(e, "添加 IMS 组合"); tap(e, "AES128 / SHA1")
            scrollTo(e, "添加 IMS 组合"); tap(e, "添加 IMS 组合"); tap(e, "ESP NULL / SHA1")
            assertEquals(listOf(ConfigurationImsSuite.AES128_SHA1, ConfigurationImsSuite.NULL_SHA1), draft.imsProtection!!.suites)
            // The inserted second row is below the retained viewport anchor.
            scrollTo(e, "IMS 报价顺序 · 2"); tap(e, "IMS 报价顺序 · 2"); tap(e, "第 1 位")
            assertEquals(0, writes)
            assertEquals(1, draft.modifiedCount)
            tap(e, "保存配置")
            assertEquals(1, writes)
            assertEquals(ConfigurationEditStatus.Saved, draft.status)
        }
    } }

    @Test fun unknownGroupClearAndRunningSaveKeepTheExistingConfirmations() = onMotionTestThread { runTest {
        val ready = ready()
        var row by mutableStateOf(ready.instances.first().copy(lifecycle = ConfigurationLifecycle.RUNNING,
            imsProtection = null, configVersion = 1, configRevision = INITIAL))
        val draft = ConfigurationEditorDraft(row)
        var writes = 0
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft,
                    onSubmit = { _, next, complete ->
                        writes++
                        assertEquals(ConfigurationImsProtection(), next.imsProtection)
                        row = row.copy(imsProtection = next.imsProtection, configRevision = NEXT)
                        complete(ConfigurationApplyOutcome.Confirmed(row))
                        ConfigurationApplyAcceptance.Accepted
                    },
                    section = ConfigurationSection.PROTOCOL)
            } }
        }.use { e ->
            frames(e); scrollTo(e, "清除 IMS 保护组"); tap(e, "清除 IMS 保护组"); tap(e, "取消")
            assertNull(draft.imsProtection)
            tap(e, "清除 IMS 保护组"); tap(e, "清除整组")
            assertEquals(ConfigurationImsProtection(), draft.imsProtection)
            assertFalse(nodes(e).any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "新建 IMS 保护组" } == true })
            tap(e, "保存并重启"); assertEquals(0, writes); tap(e, "取消")
            assertEquals(0, writes)
            tap(e, "保存并重启"); tap(e, "保存并重启")
            assertEquals(1, writes)
            assertEquals(ConfigurationEditStatus.Saved, draft.status)
            scrollTo(e, "新建 IMS 保护组")
            assertNotNull(visible(e, "新建 IMS 保护组"))
        }
    } }

    private fun ready() = assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("ready").state.value)
    private fun frames(e: MotionTestEnvironment) = repeat(35) { e.advanceFrameBy(16) }
    private fun nodes(e: MotionTestEnvironment): List<SemanticsNode> = e.scene.semanticsOwners.flatMap { owner ->
        fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
        visit(owner.unmergedRootSemanticsNode)
    }.filter { node -> generateSequence(node.layoutInfo) { it.parentInfo }.all { it.isPlaced } }
    private fun visible(e: MotionTestEnvironment, text: String) = nodes(e).firstOrNull { node ->
        if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } != true || node.boundsInRoot.width <= 0f || node.positionInRoot.y < 0f) false
        else {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.isNotEmpty() && layouts.all { !it.didOverflowHeight && it.size.height <= node.boundsInRoot.height + 1f && node.positionInRoot.y + it.size.height <= 850 }
        }
    }
    private fun tap(e: MotionTestEnvironment, text: String) {
        val node = nodes(e).last { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true && it.boundsInRoot.width > 0f }
        val action = generateSequence(node) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
        assertTrue(action.config[SemanticsActions.OnClick].action!!.invoke()); frames(e)
    }
    private fun scrollTo(e: MotionTestEnvironment, text: String, direction: Float = 1f) {
        repeat(48) {
            if (visible(e, text) != null) return
            nodes(e).first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 240f * direction)
            repeat(5) { e.advanceFrameBy(16) }
        }
        error("text not reachable: $text")
    }
    private companion object {
        const val INITIAL = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val NEXT = "r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
