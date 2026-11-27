package com.vowifi.sw.manager.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
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
class ConfigurationSignalingEditorRenderTest {
    @Test fun anUnknownGroupRequiresExplicitClearSaveAndReadbackBeforeIndividualFieldsAppear() = onMotionTestThread { runTest {
        val ready = assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("ready").state.value)
        var row by mutableStateOf(ready.instances.first().copy(lifecycle = ConfigurationLifecycle.RUNNING,
            callSignaling = null, configVersion = 1, configRevision = INITIAL))
        val draft = ConfigurationEditorDraft(row)
        var writes = 0
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft,
                    onSubmit = { _, next, complete ->
                        writes++
                        assertEquals(ConfigurationCallSignaling(), next.callSignaling)
                        row = row.copy(callSignaling = next.callSignaling, configRevision = NEXT)
                        complete(ConfigurationApplyOutcome.Confirmed(row, effect = ConfigurationApplyEffect.NextCall))
                        ConfigurationApplyAcceptance.Accepted
                    },
                    section = ConfigurationSection.VOICE)
            } }
        }.use { e ->
            frames(e)
            scrollTo(e, "清除信令覆盖")
            tap(e, "清除信令覆盖")
            tap(e, "取消")
            assertNull(draft.callSignaling)
            assertFalse(draft.dirty)
            tap(e, "清除信令覆盖")
            tap(e, "清除整组")
            assertEquals(ConfigurationCallSignaling(), draft.callSignaling)
            assertEquals(0, writes)
            assertFalse(texts(e).any { it == "启用会话计时" })
            tap(e, "保存配置")
            assertEquals(1, writes)
            scrollTo(e, "启用会话计时")
            assertNotNull(visibleText(e, "启用会话计时"))
        }
    } }

    @Test fun realDropdownAndTextDialogEditTheDraftAndSaveWithoutARestartConfirmation() = onMotionTestThread { runTest {
        val ready = assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("ready").state.value)
        var row by mutableStateOf(ready.instances.first().copy(lifecycle = ConfigurationLifecycle.RUNNING,
            callSignaling = ConfigurationCallSignaling(), configVersion = 1, configRevision = INITIAL))
        val draft = ConfigurationEditorDraft(row)
        var writes = 0
        var packet: JsonObject? = null
        MotionTestEnvironment(testScheduler, width = 375, height = 850) {
            SoftVoWiFiTheme(false) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft,
                    onSubmit = { _, next, complete ->
                        writes++
                        packet = Json.parseToJsonElement(ConfigurationCodec.encodeRevisionChanges(ConfigurationEditorDraft(row).overrides(), next)).jsonObject
                        row = row.copy(callSignaling = next.callSignaling, configRevision = NEXT)
                        complete(ConfigurationApplyOutcome.Confirmed(row, effect = ConfigurationApplyEffect.NextCall))
                        ConfigurationApplyAcceptance.Accepted
                    },
                    section = ConfigurationSection.VOICE)
            } }
        }.use { e ->
            frames(e)
            scrollTo(e, "附带接入网络信息")
            tap(e, "附带接入网络信息")
            tap(e, "关闭")
            assertEquals(false, draft.callSignaling?.includePANI)
            assertTrue(draft.onlySignalingChanges)
            assertEquals(0, writes, "field selection only changes the draft")
            assertFalse(texts(e).any { it == "保存并重启实例？" })
            tap(e, "保存配置")
            assertEquals(1, writes)
            assertEquals(ConfigurationEditStatus.Saved, draft.status)
            assertFalse(draft.dirty)
            assertEquals(Json.parseToJsonElement("""{"callSignaling":{"includePANI":false}}"""), packet?.get("overrides"))
            assertFalse(texts(e).any { it == "保存并重启实例？" })

            scrollTo(e, "会话期限", direction = -1f)
            tap(e, "会话期限")
            fun enter(value: String) {
                val input = nodes(e).single { it.config.getOrNull(SemanticsActions.SetText) != null }
                assertTrue(input.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value)))
                frames(e)
            }
            enter("89")
            tap(e, "确定")
            assertTrue(texts(e).any { it == "参数无效或与同组设置冲突，请检查后再确认" })
            assertNull(draft.callSignaling?.sessionTimer?.expiresSeconds)
            enter("1200")
            tap(e, "确定")
            assertEquals(1200, draft.callSignaling?.sessionTimer?.expiresSeconds)
            assertEquals(1, writes, "dialog confirmation must not persist")
        }
    } }

    @Test fun narrowTwoHundredPercentTextKeepsAllSeventeenRowsAndTheSaveActionReachable() = onMotionTestThread { runTest {
        val ready = assertIs<ConfigurationUiState.Ready>(FixtureConfigurationSource("ready").state.value)
        val longContext = "a".repeat(52) + "." + "b".repeat(52) + ".example"
        val row = ready.instances.first().copy(lifecycle = ConfigurationLifecycle.RUNNING, configVersion = 1, configRevision = INITIAL,
            callSignaling = ConfigurationCallSignaling(
                sessionTimer = ConfigurationSessionTimer(refreshMethod = "prefer-update", refresher = "uas"),
                reliableProvisional = ConfigurationReliableProvisional(uasMode = "sdp-only"),
                addressing = ConfigurationCallAddressing(phoneContext = longContext, contactTransport = "selected", requestURI = "sip")))
        val draft = ConfigurationEditorDraft(row)
        MotionTestEnvironment(testScheduler, width = 320, height = 900) {
            SoftVoWiFiTheme(true) { CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) { Scaffold {
                ConfigurationSimEditor(ConfigurationEditorModel(row, ready.copy(instances = listOf(row)), true, null), draft,
                    onSubmit = { _, _, _ -> error("geometry must not write") },
                    section = ConfigurationSection.VOICE)
            } } }
        }.use { e ->
            frames(e)
            CallSignalingField.entries.forEach { field ->
                scrollTo(e, field.title, viewportHeight = 900)
                val title = visibleText(e, field.title, 900) ?: error("missing ${field.wire}")
                assertCompleteText(title, field.wire)
                if (field == CallSignalingField.PhoneContext) {
                    val summary = "覆盖：$longContext\n${callSignalingFieldSummary(row, field, false)}"
                    scrollTo(e, summary, viewportHeight = 900)
                    assertCompleteText(visibleText(e, summary, 900)!!, "long phone context")
                }
            }
            draft.replaceCallSignaling(row.callSignaling!!.copy(includePANI = false))
            frames(e)
            val save = clickable(e, "保存配置")
            assertTrue(save.boundsInRoot.height >= 48f && save.boundsInRoot.width > 0f)
            assertTrue(save.positionInRoot.y >= 0f && save.boundsInRoot.bottom <= 900f)
            assertCompleteText(visibleText(e, "保存配置", 900)!!, "save label")
        }
    } }

    private fun frames(e: MotionTestEnvironment) = repeat(35) { e.advanceFrameBy(16) }
    private fun nodes(e: MotionTestEnvironment): List<SemanticsNode> = e.scene.semanticsOwners.flatMap { owner ->
        fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
        visit(owner.unmergedRootSemanticsNode)
    }
    private fun texts(e: MotionTestEnvironment) = nodes(e).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    private fun visibleText(e: MotionTestEnvironment, text: String, viewportHeight: Int = 850) = nodes(e).firstOrNull { node ->
        if (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } != true ||
            node.boundsInRoot.width <= 0f || node.positionInRoot.y < 0f || node.boundsInRoot.height <= 0f
        ) false else {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.isNotEmpty() && node.positionInRoot.y + layouts.maxOf { it.size.height } <= viewportHeight.toFloat()
        }
    }
    private fun clickable(e: MotionTestEnvironment, text: String): SemanticsNode {
        val node = nodes(e).last { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true && it.boundsInRoot.width > 0f }
        return generateSequence(node) { it.parent }.first { it.config.getOrNull(SemanticsActions.OnClick) != null }
    }
    private fun tap(e: MotionTestEnvironment, text: String) {
        assertTrue(clickable(e, text).config[SemanticsActions.OnClick].action!!.invoke(), text)
        frames(e)
    }
    private fun scrollTo(e: MotionTestEnvironment, text: String, direction: Float = 1f, viewportHeight: Int = 850) {
        repeat(40) {
            val visible = visibleText(e, text, viewportHeight)
            if (visible != null) return
            val scroll = nodes(e).first { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
            scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 200f * direction)
            repeat(5) { e.advanceFrameBy(16) }
        }
        error("text not reachable: $text")
    }
    private fun assertCompleteText(node: SemanticsNode, context: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        assertTrue(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { layout ->
            val geometry = "$context: size=${layout.size}, constraints=${layout.layoutInput.constraints}, " +
                "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, bounds=${node.boundsInRoot}, " +
                "lines=${(0 until layout.lineCount).map { line -> listOf(layout.getLineLeft(line), layout.getLineRight(line)) }}"
            assertFalse(layout.didOverflowHeight, geometry)
            // A wrap-content Text can retain a wider paragraph measurement box
            // (238px) than its node (136px), while its actual line is 135.9997px.
            // Check rendered line/character extents, not that unused box.
            assertTrue(layout.size.width <= node.boundsInRoot.width, geometry)
            for (line in 0 until layout.lineCount) {
                assertTrue(layout.getLineLeft(line) >= 0f, geometry)
                assertTrue(layout.getLineRight(line) <= layout.size.width, geometry)
            }
            layout.layoutInput.text.indices.filter { !layout.layoutInput.text[it].isWhitespace() }.forEach { offset ->
                val box = layout.getBoundingBox(offset)
                assertTrue(box.left >= 0f && box.right <= layout.size.width, "$geometry, character=$offset, box=$box")
            }
            for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line), context)
            assertEquals(layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1), context)
            assertTrue(layout.size.height <= node.boundsInRoot.height + 1f, context)
        }
    }
    private companion object {
        const val INITIAL = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val NEXT = "r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
