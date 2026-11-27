package com.vowifi.sw.manager.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
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
class ConfigurationEpdgSelectionEditorRenderTest {
    @Test fun realEditorBuildsFiltersAndOrderThenSubmitsOneCompleteGroup() = onMotionTestThread { runTest {
        val ready = assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("ready").state.value)
        var row by mutableStateOf(ready.instances.first().copy(lifecycle = ConfigurationLifecycle.STOPPED,
            epdgFqdn = "", epdgSelection = ConfigurationEpdgSelection(), configVersion = 1, configRevision = INITIAL))
        val draft = ConfigurationEditorDraft(row)
        var writes = 0
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft,
                    onSubmit = { _, next, complete ->
                        writes++
                        val patch = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(ConfigurationEditorDraft(row).overrides(), next)).jsonObject
                        assertEquals(setOf("epdgSelection"), patch.getValue("overrides").jsonObject.keys)
                        val selected = next.epdgSelection!!.candidates!!
                        assertEquals(listOf(ConfigurationEpdgKind.HOME_PLMN, ConfigurationEpdgKind.REGISTERED_PLMN), selected.map { it.kind })
                        assertEquals(ConfigurationEpdgAccess.ROAMING, selected[1].access)
                        assertEquals(listOf("00101"), selected[1].registeredPlmns)
                        row = row.copy(epdgSelection = next.epdgSelection, configRevision = NEXT)
                        complete(ConfigurationApplyOutcome.Confirmed(row))
                        ConfigurationApplyAcceptance.Accepted
                    },
                    section = ConfigurationSection.CONNECTION)
            } }
        }.use { e ->
            frames(e); seek(e, "新建 ePDG 候选策略"); tap(e, "新建 ePDG 候选策略")
            tap(e, "保存配置"); assertEquals(0, writes); assertEquals(ConfigurationEditStatus.Invalid, draft.status)
            assertNotNull(draft.selectionError)
            seek(e, "添加 ePDG 候选"); tap(e, "添加 ePDG 候选"); tap(e, "当前注册 PLMN")
            seek(e, "访问条件 · 1", -1f); tap(e, "访问条件 · 1"); tap(e, "仅漫游")
            seek(e, "注册 PLMN 限定 · 1"); tap(e, "注册 PLMN 限定 · 1")
            val field = nodes(e).last { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("00101")))
            frames(e); tap(e, "确定")
            seek(e, "添加 ePDG 候选"); tap(e, "添加 ePDG 候选"); tap(e, "归属 PLMN")
            seek(e, "候选顺序 · 2"); tap(e, "候选顺序 · 2"); tap(e, "第 1 位")
            assertEquals(1, draft.modifiedCount); assertEquals(0, writes)
            tap(e, "保存配置"); assertEquals(1, writes); assertEquals(ConfigurationEditStatus.Saved, draft.status)
        }
    } }

    @Test fun savedStateContainsOnlyUiKeysAndRestoresTheInMemoryPolicy() = onMotionTestThread { runTest {
        val ready = assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("ready").state.value)
        val row = ready.instances.first().copy(epdgFqdn = "", lifecycle = ConfigurationLifecycle.STOPPED,
            epdgSelection = ConfigurationEpdgSelection(), configVersion = 1, configRevision = INITIAL)
        val selected = ConfigurationEpdgSelection(listOf(ConfigurationEpdgCandidate(ConfigurationEpdgKind.REGISTERED_PLMN,
            access = ConfigurationEpdgAccess.ROAMING, registeredPlmns = listOf("00101"))))
        val draft = ConfigurationEditorDraft(row).apply { replaceEpdgSelection(selected) }
        fun uiOnly(value: Any?): Boolean = when (value) {
            null, is Boolean, is Int, is Float, is Double -> true
            is State<*> -> uiOnly(value.value)
            is String -> value == row.instanceId.value || value.startsWith("configuration-") && "00101" !in value && "registered-plmn" !in value
            is List<*> -> value.all(::uiOnly)
            is Map<*, *> -> value.all { (key, item) -> uiOnly(key) && uiOnly(item) }
            else -> false
        }
        assertFalse(uiOnly(selected)); assertFalse(uiOnly(draft))
        fun scene(registry: SaveableStateRegistry) = MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold { CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft,
                    onSubmit = { _, _, _ -> error("UI state must not save configuration") },
                    section = ConfigurationSection.CONNECTION)
            } } }
        }
        val registry = SaveableStateRegistry(null, ::uiOnly)
        val saved = scene(registry).use { e ->
            frames(e); seek(e, "访问条件 · 1")
            registry.performSave().also { assertTrue(it.values.flatten().all(::uiOnly)) }
        }
        scene(SaveableStateRegistry(saved, ::uiOnly)).use { e ->
            frames(e); assertNotNull(visible(e, "访问条件 · 1"))
            assertEquals(selected, draft.epdgSelection); assertTrue(draft.dirty)
        }
    } }

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
    private fun seek(e: MotionTestEnvironment, text: String, initialDirection: Float = 1f) {
        repeat(48) {
            if (visible(e, text) != null) return
            val positioned = nodes(e).firstOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
            val direction = positioned?.let { if (it.positionInRoot.y < 0f) -1f else 1f } ?: initialDirection
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
