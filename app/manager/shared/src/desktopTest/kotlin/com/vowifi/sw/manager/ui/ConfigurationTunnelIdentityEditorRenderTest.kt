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
class ConfigurationTunnelIdentityEditorRenderTest {
    @Test fun realEditorChangesWireTypeSavesWholeGroupAndClearsThroughConfirmation() = onMotionTestThread { runTest {
        val ready = assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("ready").state.value)
        var row by mutableStateOf(ready.instances.first().copy(lifecycle = ConfigurationLifecycle.STOPPED,
            tunnelIdentity = ConfigurationTunnelIdentity(), configVersion = 1, configRevision = INITIAL))
        val draft = ConfigurationEditorDraft(row)
        var writes = 0
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft,
                    onSubmit = { _, next, complete ->
                        writes++
                        val patch = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(ConfigurationEditorDraft(row).overrides(), next)).jsonObject
                        assertEquals(setOf("tunnelIdentity"), patch.getValue("overrides").jsonObject.keys)
                        if (writes == 1) {
                            assertEquals(ConfigurationIkeIdType.KEY_ID, next.tunnelIdentity!!.policy!!.localType)
                            assertEquals(ConfigurationPeerIdentity(ConfigurationIkeIdType.FQDN, "peer.example.invalid"), next.tunnelIdentity!!.policy!!.peer)
                        } else assertEquals(JsonNull, patch.getValue("overrides").jsonObject["tunnelIdentity"])
                        row = row.copy(tunnelIdentity = next.tunnelIdentity, configRevision = if (writes == 1) NEXT else INITIAL)
                        complete(ConfigurationApplyOutcome.Confirmed(row))
                        ConfigurationApplyAcceptance.Accepted
                    },
                    section = ConfigurationSection.PROTOCOL)
            } }
        }.use { e ->
            frames(e); scrollTo(e, "新建 IKE 身份策略"); tap(e, "新建 IKE 身份策略")
            scrollTo(e, "本地 IDi 类型"); tap(e, "本地 IDi 类型"); tap(e, "KEY_ID")
            scrollTo(e, "对端身份约束"); tap(e, "对端身份约束"); tap(e, "FQDN")
            tap(e, "保存配置")
            assertEquals(0, writes); assertNotNull(draft.identityError)
            scrollTo(e, "预期对端身份"); tap(e, "预期对端身份")
            enter(e, "*.example.invalid"); tap(e, "确定")
            assertEquals("", draft.tunnelIdentity!!.policy!!.peer!!.value)
            enter(e, "peer.example.invalid"); tap(e, "确定")
            assertEquals(0, writes)
            assertEquals(1, draft.modifiedCount)
            assertFalse(draft.onlyNextCallChanges)
            tap(e, "保存配置")
            assertEquals(1, writes); assertEquals(ConfigurationEditStatus.Saved, draft.status)
            scrollTo(e, "清除 IKE 身份策略"); tap(e, "清除 IKE 身份策略"); tap(e, "取消")
            assertFalse(draft.dirty)
            tap(e, "清除 IKE 身份策略"); tap(e, "清除整组"); tap(e, "保存配置")
            assertEquals(2, writes); assertEquals(ConfigurationEditStatus.Saved, draft.status)
            assertEquals(ConfigurationTunnelIdentity(), row.tunnelIdentity)
        }
    } }

    private fun frames(e: MotionTestEnvironment) = repeat(35) { e.advanceFrameBy(16) }
    private fun nodes(e: MotionTestEnvironment): List<SemanticsNode> = e.scene.semanticsOwners.flatMap { owner ->
        fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
        visit(owner.unmergedRootSemanticsNode)
    }
    private fun visible(e: MotionTestEnvironment, text: String) = nodes(e).firstOrNull {
        it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true &&
            it.boundsInRoot.width > 0f && it.boundsInRoot.top >= 0f && it.boundsInRoot.bottom <= 850f
    }
    private fun tap(e: MotionTestEnvironment, text: String) {
        val node = nodes(e).last { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true && it.boundsInRoot.width > 0f }
        val action = generateSequence(node) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
        assertTrue(action.config[SemanticsActions.OnClick].action!!.invoke()); frames(e)
    }
    private fun scrollTo(e: MotionTestEnvironment, text: String) {
        repeat(80) {
            if (visible(e, text) != null) return
            nodes(e).first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 200f)
            repeat(5) { e.advanceFrameBy(16) }
        }
        error("identity editor row not reachable: $text")
    }
    private fun enter(e: MotionTestEnvironment, text: String) {
        val input = nodes(e).single { it.config.getOrNull(SemanticsActions.SetText) != null }
        assertTrue(input.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(text))); frames(e)
    }
    companion object {
        const val INITIAL = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val NEXT = "r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
