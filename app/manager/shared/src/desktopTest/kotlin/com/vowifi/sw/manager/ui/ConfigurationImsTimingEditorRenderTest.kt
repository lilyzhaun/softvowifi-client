package com.vowifi.sw.manager.ui

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
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
class ConfigurationImsTimingEditorRenderTest {
    @Test fun realEditorValidatesScalarAndSavesOnlyTheWholeImsGroup() = onMotionTestThread { runTest {
        val ready = assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("ready").state.value)
        var row by mutableStateOf(ready.instances.first().copy(lifecycle = ConfigurationLifecycle.STOPPED,
            imsTiming = ConfigurationImsTiming(), configVersion = 1, configRevision = INITIAL))
        val draft = ConfigurationEditorDraft(row)
        var writes = 0
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft,
                    onSubmit = { _, next, complete ->
                        writes++
                        val patch = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(ConfigurationEditorDraft(row).overrides(), next)).jsonObject
                        assertEquals(setOf("imsTiming"), patch.getValue("overrides").jsonObject.keys)
                        assertEquals(60L, next.imsTiming!!.policy!!.registration.refreshPercent)
                        assertEquals(128000L, next.imsTiming!!.policy!!.transaction.fMillis)
                        row = row.copy(imsTiming = next.imsTiming, configRevision = NEXT)
                        complete(ConfigurationApplyOutcome.Confirmed(row)); ConfigurationApplyAcceptance.Accepted
                    },
                    section = ConfigurationSection.PROTOCOL)
            } }
        }.use { e ->
            frames(e); scrollTo(e, "新建 IMS 计时"); tap(e, "新建 IMS 计时")
            scrollTo(e, "刷新时点"); tap(e, "刷新时点")
            enter(e, "100"); tap(e, "确定")
            assertEquals(50L, draft.imsTiming!!.policy!!.registration.refreshPercent)
            assertNotNull(visible(e, "请输入有效数值；单位和范围见上方说明"))
            enter(e, "60"); tap(e, "确定")
            assertEquals(0, writes); assertEquals(1, draft.modifiedCount)
            tap(e, "保存配置")
            assertEquals(1, writes); assertEquals(ConfigurationEditStatus.Saved, draft.status)
        }
    } }

    @Test fun unknownClearAndRunningSaveKeepBothConfirmations() = onMotionTestThread { runTest {
        val ready = assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("ready").state.value)
        var row by mutableStateOf(ready.instances.first().copy(lifecycle = ConfigurationLifecycle.RUNNING,
            imsTiming = null, configVersion = 1, configRevision = INITIAL))
        val draft = ConfigurationEditorDraft(row); var writes = 0
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(true) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft,
                    onSubmit = { _, next, complete ->
                        writes++; assertEquals(ConfigurationImsTiming(), next.imsTiming)
                        row = row.copy(imsTiming = next.imsTiming, configRevision = NEXT)
                        complete(ConfigurationApplyOutcome.Confirmed(row)); ConfigurationApplyAcceptance.Accepted
                    },
                    section = ConfigurationSection.PROTOCOL)
            } }
        }.use { e ->
            frames(e); scrollTo(e, "清除 IMS 计时"); tap(e, "清除 IMS 计时"); tap(e, "取消")
            assertNull(draft.imsTiming)
            tap(e, "清除 IMS 计时"); tap(e, "清除整组")
            assertEquals(ConfigurationImsTiming(), draft.imsTiming); assertNull(visible(e, "新建 IMS 计时"))
            tap(e, "保存并重启"); tap(e, "取消"); assertEquals(0, writes)
            tap(e, "保存并重启"); tap(e, "保存并重启")
            assertEquals(1, writes); assertEquals(ConfigurationEditStatus.Saved, draft.status)
            scrollTo(e, "新建 IMS 计时")
        }
    } }

    private fun frames(e: MotionTestEnvironment) = repeat(35) { e.advanceFrameBy(16) }
    private fun nodes(e: MotionTestEnvironment): List<SemanticsNode> = e.scene.semanticsOwners.flatMap { owner ->
        fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
        visit(owner.unmergedRootSemanticsNode)
    }
    private fun visible(e: MotionTestEnvironment, text: String) = nodes(e).firstOrNull {
        it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true &&
            it.boundsInRoot.width > 0f && it.boundsInRoot.top >= 0 && it.boundsInRoot.bottom <= 850
    }
    private fun tap(e: MotionTestEnvironment, text: String) {
        val node = nodes(e).last { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true && it.boundsInRoot.width > 0f }
        val owner = generateSequence(node) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
        assertTrue(owner.config[SemanticsActions.OnClick].action!!.invoke()); frames(e)
    }
    private fun enter(e: MotionTestEnvironment, text: String) {
        val input = nodes(e).single { it.config.getOrNull(SemanticsActions.SetText) != null }
        assertTrue(input.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text))); frames(e)
    }
    private fun scrollTo(e: MotionTestEnvironment, text: String) {
        repeat(100) {
            if (visible(e, text) != null) return
            nodes(e).first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 200f)
            repeat(5) { e.advanceFrameBy(16) }
        }
        error("text not reachable: $text")
    }
    companion object {
        const val INITIAL = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val NEXT = "r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
