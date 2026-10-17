package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.vowifi.sw.manager.data.MessageOwnerLabel
import com.vowifi.sw.manager.data.MessageRemarkSaveResult
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.MessageHistoryChoices
import com.vowifi.sw.manager.model.MessageHistoryLine
import com.vowifi.sw.manager.ui.MessageLinePicker
import com.vowifi.sw.manager.ui.MessageRemarkDialog
import com.vowifi.sw.manager.ui.MessageRemarkEditor
import com.vowifi.sw.manager.ui.SoftVoWiFiTheme
import com.vowifi.sw.manager.ui.MessagesScreen
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.MessageOrigin
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.yukonga.miuix.kmp.basic.Scaffold

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessageLinePickerRenderTest {
    private var time = 0L
    @Test fun remarkActionsRemainFullyVisibleWhenHostShrinksTo250dpAt200Percent() {
        val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        val editor = MessageRemarkEditor(owner, "测试".repeat(20), 1)
        var cancelled = false
        var saves = 0
        val scene = ImageComposeScene(width = 420, height = 250, density = Density(1f, 2f)) {
            SoftVoWiFiTheme(forceDark = false) {
                Scaffold(Modifier.fillMaxSize()) {
                    MessageRemarkDialog(editor, { cancelled = true }) { _, _ ->
                        saves++
                        MessageRemarkSaveResult.Failed
                    }
                }
            }
        }
        try {
            frame(scene)
            screenshot(scene, "issue11-remark-constrained-initial.png")
            assertActionBounds(scene, "保存", 250f)
            assertActionBounds(scene, "取消", 250f)
            val input = nodes(scene).single { it.config.getOrNull(SemanticsActions.SetText) != null }
            requireNotNull(input.config[SemanticsActions.SetText].action).invoke(AnnotatedString("备注".repeat(20)))
            frame(scene)
            revealText(scene, "40 / 40 字符")
            screenshot(scene, "issue11-remark-constrained-counter.png")
            click(scene, "保存")
            frame(scene)
            assertEquals(1, saves)
            assertEquals("备注".repeat(20), editor.input)
            assertActionBounds(scene, "保存", 250f)
            assertActionBounds(scene, "取消", 250f)
            revealText(scene, requireNotNull(editor.error))
            screenshot(scene, "issue11-remark-constrained-error.png")
            val scroll = nodes(scene).single { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null &&
                it.config.getOrNull(SemanticsActions.ScrollBy) != null }
            requireNotNull(scroll.config[SemanticsActions.ScrollBy].action).invoke(0f, 10_000f)
            frame(scene)
            assertActionBounds(scene, "清除备注", 250f)
            screenshot(scene, "issue11-remark-constrained-bottom.png")
            click(scene, "清除备注")
            frame(scene)
            assertEquals(2, saves)
            assertEquals("", editor.input)
            assertActionBounds(scene, "取消", 250f)
            click(scene, "取消")
            frame(scene)
            assertTrue(cancelled)
        } finally { scene.close() }
    }

    private fun revealText(scene: ImageComposeScene, text: String) {
        val scroll = nodes(scene).single { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null &&
            it.config.getOrNull(SemanticsActions.ScrollBy) != null }
        val move = requireNotNull(scroll.config[SemanticsActions.ScrollBy].action)
        move(0f, -10_000f)
        frame(scene)
        repeat(80) {
            val target = nodes(scene).single { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true }
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            requireNotNull(target.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts)
            if (layouts.isNotEmpty() && layouts.all { it.size.height <= target.boundsInRoot.height }) return
            move(0f, 16f)
            frame(scene)
        }
        error("Text cannot be fully exposed by scrolling")
    }

    private fun assertActionBounds(scene: ImageComposeScene, label: String, height: Float) {
        val action = nodes(scene).single { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true &&
                node.config.getOrNull(SemanticsActions.OnClick) != null
        }
        val bounds = action.boundsInRoot
        assertTrue(bounds.height >= 48f, "$label visible height=${bounds.height}")
        assertTrue(bounds.top >= 0 && bounds.bottom <= height, "$label bounds=$bounds")
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        requireNotNull(action.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts)
        assertTrue(layouts.isNotEmpty())
        assertTrue(layouts.all { it.size.height <= bounds.height }, "$label text clipped")
    }
    @Test fun rootHasSingleLineEntryBelowSearchAndOpensRealPicker() {
        val lines = ('a'..'t').mapIndexed { index, letter -> MessageHistoryLine(
            requireNotNull(SimOwnerId.parse("sim-owner-v1:" + letter.toString().repeat(43))), index, false,
            MessageOwnerLabel(index.toLong() + 1, if (index == 0) "出行备用" else "合成线路"),
        ) }
        val scene = ImageComposeScene(width = 420, height = 900, density = Density(1f)) {
            SoftVoWiFiTheme(forceDark = false) {
                Scaffold(Modifier.fillMaxSize()) {
                    MessagesScreen(MessageUiState.Ready(emptyList(), MessageOrigin.ENGINE, ownerId = lines.first().owner), {}, {},
                        historyChoices = MessageHistoryChoices(lines, lines.first().owner, true))
                }
            }
        }
        try {
            frame(scene)
            val root = nodes(scene)
            assertEquals(0, root.count { it.config.getOrNull(SemanticsActions.SetText) != null })
            val search = root.single { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("搜索短信") == true && it.config.contains(SemanticsActions.OnClick) }
            requireNotNull(search.config[SemanticsActions.OnClick].action).invoke(); frame(scene)
            assertEquals(1, nodes(scene).count { it.config.getOrNull(SemanticsActions.SetText) != null })
            assertEquals(1, root.count { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "出行备用" } == true })
            screenshot(scene, "issue11-root.png")
            click(scene, "出行备用")
            frame(scene)
            assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "选择短信记录线路" } == true })
        } finally { scene.close() }
    }
    @Test fun searchesTwentyLinesEditsWithoutSelectingAndRetriesFailedSave() = smsOnEdt {
        val lines = ('a'..'t').mapIndexed { index, letter -> MessageHistoryLine(
            requireNotNull(SimOwnerId.parse("sim-owner-v1:" + letter.toString().repeat(43))), index, false,
            MessageOwnerLabel(index.toLong() + 1, if (index == 19) "用于漫游的备用线路长中文备注以验证自动换行" else "合成线路"),
        ) }
        var picked: SimOwnerId? = null
        val source = SmsDualSimSource(smsReady(lines.first().owner), MessageHistoryChoices(lines, lines.first().owner, true))
        val clock = TestCoroutineScheduler()
        fun selectOwner(owner: SimOwnerId) {
            source.selectHistoryOwner(owner)
            picked = source.historyChoices.value.selectedOwner
        }
        var editor by mutableStateOf<MessageRemarkEditor?>(null)
        var fail = true
        var saves = 0
        val scene = ImageComposeScene(width = 420, height = 900, density = Density(1f), coroutineContext = StandardTestDispatcher(clock)) {
            SoftVoWiFiTheme(forceDark = false) {
                Scaffold(Modifier.fillMaxSize()) {
                    val editing = editor
                    val choices by source.historyChoices.collectAsState()
                    if (editing == null) MessageLinePicker(choices, {},
                        ::selectOwner, { editor = MessageRemarkEditor(it.owner, it.label?.remark.orEmpty(), it.label?.ordinal) }, {},
                        // C2：只提交原source一次，以其已发布owner确认；Unit回调本身不等于成功。
                        onConfirm = { owner -> selectOwner(owner); source.historyChoices.value.selectedOwner == owner })
                    else MessageRemarkDialog(editing, { editor = null }) { owner, _ ->
                        assertEquals(lines.last().owner, owner)
                        saves++
                        if (fail) MessageRemarkSaveResult.Failed else MessageRemarkSaveResult.Saved
                    }
                }
            }
        }
        try {
            frame(scene, clock)
            screenshot(scene, "issue11-lines.png")
            val search = nodes(scene).single { it.config.getOrNull(SemanticsActions.SetText) != null }
            assertTrue(requireNotNull(search.config[SemanticsActions.SetText].action).invoke(AnnotatedString("20")))
            frame(scene, clock)
            screenshot(scene, "issue11-lines-search.png")
            click(scene, "备注")
            frame(scene, clock)
            frame(scene, clock) // The selector exits before the editor starts entering.
            assertEquals(null, picked)
            assertEquals(lines.last().owner, editor?.owner)
            click(scene, "保存")
            frame(scene, clock)
            assertEquals(1, saves)
            assertTrue(editor?.error != null)
            assertEquals(lines.last().label?.remark, editor?.input)
            screenshot(scene, "issue11-remark-error.png")
            fail = false
            click(scene, "保存")
            frame(scene, clock)
            assertEquals(2, saves)
            assertEquals(null, editor)
            frame(scene, clock)
            val restoredSearch = nodes(scene).single { it.config.getOrNull(SemanticsActions.SetText) != null }
            requireNotNull(restoredSearch.config[SemanticsActions.SetText].action).invoke(AnnotatedString("20"))
            frame(scene, clock)
            click(scene, requireNotNull(lines.last().label).remark)
            frame(scene, clock)
            println("S4_SMS_LINE_CONFIRM|calls=${source.selections.size}|selectedIndex=${lines.indexOfFirst { it.owner == source.historyChoices.value.selectedOwner }}|" +
                "pickedIndex=${lines.indexOfFirst { it.owner == picked }}|clock=${clock.currentTime}|renderMs=${time / 1_000_000L}")
            assertEquals(lines.last().owner, picked)
            assertEquals(listOf(lines.last().owner), source.selections, "C2：原source选线只能提交一次")
        } finally { scene.close(); clock.runCurrent() }
    }

    @Test fun rendersLongCjkAtLargeFontWithBoundedScrollableRows() {
        val lines = ('a'..'t').mapIndexed { index, letter -> MessageHistoryLine(
            requireNotNull(SimOwnerId.parse("sim-owner-v1:" + letter.toString().repeat(43))), index, false,
            MessageOwnerLabel(index.toLong() + 1, "这是用于验证大字号和长中文换行的本地备用线路"),
        ) }
        val scene = ImageComposeScene(width = 420, height = 900, density = Density(1f, 2f)) {
            SoftVoWiFiTheme(forceDark = true) {
                Scaffold(Modifier.fillMaxSize()) {
                    MessageLinePicker(MessageHistoryChoices(lines, lines.first().owner, true), {}, {}, {}, {})
                }
            }
        }
        try {
            frame(scene)
            assertTrue(nodes(scene).any { it.config.getOrNull(SemanticsActions.SetText) != null })
            screenshot(scene, "issue11-lines-large.png")
        } finally { scene.close() }
    }

    private fun frame(scene: ImageComposeScene, clock: TestCoroutineScheduler? = null) {
        repeat(8) {
            clock?.runCurrent()
            time += 100_000_000L
            // C2驻留用协程delay；此测试的scheduler必须与实际渲染帧同钟。
            if (clock != null) { clock.advanceTimeBy(time / 1_000_000L - clock.currentTime); clock.runCurrent() }
            scene.render(time).close()
            clock?.runCurrent()
        }
    }

    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        descend(owner.rootSemanticsNode)
    }

    private fun click(scene: ImageComposeScene, text: String) {
        val target = nodes(scene).single { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true &&
                node.config.getOrNull(SemanticsActions.OnClick) != null
        }
        assertTrue(requireNotNull(target.config[SemanticsActions.OnClick].action).invoke())
    }

    private fun screenshot(scene: ImageComposeScene, name: String) {
        val directory = System.getProperty("issue11.evidence") ?: return
        scene.render(time).use { image ->
            requireNotNull(image.encodeToData()).use { data -> Files.write(Path.of(directory, name), data.bytes) }
        }
    }
}
