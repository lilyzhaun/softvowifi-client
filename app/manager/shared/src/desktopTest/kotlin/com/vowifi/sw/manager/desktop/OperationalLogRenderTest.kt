package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.vowifi.sw.manager.data.EngineOperationalLogParser
import com.vowifi.sw.manager.data.OperationalLogEvaluator
import com.vowifi.sw.manager.data.OperationalLogSource
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import com.vowifi.sw.manager.ui.navigation.AppRoute
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class OperationalLogRenderTest {
    private var time = 0L

    @Test fun nativeNewestRowOpensScrollableSanitizedDetailAndCopiesOnlyDisplayedContent() {
        val source = RenderSource()
        val clipboard = TestClipboard()
        val scene = scene(source, clipboard)
        try {
            frame(scene)
            val timestamps = nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                .map { it.text }.filter { it.startsWith("2026-") }
            assertTrue(timestamps.first().endsWith("00:29"), "first visible entry must be newest: $timestamps")
            assertTrue(source.entries.last().timestamp.endsWith("00:29.000"), "model keeps millisecond precision for details")
            screenshot(scene, "newest")
            clickRow(scene)
            frame(scene)
            assertTrue(hasText(scene, "技术详情"))
            screenshot(scene, "detail")
            click(scene, "复制详情")
            frame(scene)
            val copied = (requireNotNull(clipboard.entry).nativeClipEntry as Transferable).getTransferData(DataFlavor.stringFlavor)
            assertEquals(source.entries.last().safeDetail, copied)
            assertFalse(copied.toString().contains("synthetic-token"))
            scrollDetailToEnd(scene)
            assertTextVisible(scene, "显示内容已截断", 900f)
            click(scene, "关闭")
            frame(scene)
            assertFalse(hasText(scene, "技术详情"))
            assertEquals(source.first, source.ready().selectedFileId)
        } finally { scene.close() }
    }

    @Test fun nativeFilePickerChangesImmediatelyAndCachedSnapshotIsDisclosed() {
        val source = RenderSource()
        val scene = scene(source, TestClipboard())
        try {
            frame(scene)
            click(scene, "日志来源")
            frame(scene)
            // U4统一日志入口名称；旧模块无groupKey仍保持独立文件选择与真实读取守护。
            click(scene, "运行日志 •••• 0002")
            frame(scene)
            assertEquals(source.second, source.ready().selectedFileId)
            assertTrue(hasText(scene, "正在读取所选日志"))
            assertFalse(hasText(scene, source.entries.last().safeSummary))
            screenshot(scene, "loading")
            source.finish(cached = true)
            frame(scene)
            assertTrue(hasText(scene, "显示上次读取的记录，刷新可更新。"))
            screenshot(scene, "cache")
            click(scene, "级别")
            frame(scene)
            click(scene, "错误")
            frame(scene)
            assertEquals(OperationalLogLevel.ERROR, source.ready().filter.level)
            assertTrue(source.ready().filteredEntries.isEmpty())
        } finally { scene.close() }
    }

    @Test fun nativeNarrowDarkLargeFontDetailKeepsCopyAndCloseReachable() {
        val source = RenderSource()
        val scene = scene(source, TestClipboard(), width = 320, height = 720, font = 2f, dark = true)
        try {
            frame(scene)
            scroll(scene, 580f)
            frame(scene)
            clickRow(scene)
            frame(scene)
            assertTrue(hasText(scene, "技术详情"))
            assertActionBounds(scene, "复制详情", 720f)
            assertActionBounds(scene, "关闭", 720f)
            screenshot(scene, "detail-dark-large")
            scrollDetailToEnd(scene)
            assertTextVisible(scene, "显示内容已截断", 720f)
            screenshot(scene, "detail-dark-large-bottom")
            assertActionBounds(scene, "复制详情", 720f)
            assertActionBounds(scene, "关闭", 720f)
            click(scene, "复制详情")
            click(scene, "关闭")
            frame(scene)
            assertFalse(hasText(scene, "技术详情"))
        } finally { scene.close() }
    }

    private fun scene(source: RenderSource, clipboard: TestClipboard, width: Int = 420, height: Int = 900,
        font: Float = 1f, dark: Boolean = false) = ImageComposeScene(width, height, Density(1f, font)) {
        SoftVoWiFiTheme(forceDark = dark) {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                AppSubpageScene(AppRoute.SettingsLogs, appChromeSpec(AppearanceSettings(true, false), false), onBack = {}) {
                    val state by source.state.collectAsState()
                    SettingsLogsScreen(state, SettingsLogsActions(source::selectFile, source::setKindFilter,
                        source::setLevelFilter, source::setInstanceFilter, {}), false)
                }
            }
        }
    }
    private fun frame(scene: ImageComposeScene) { repeat(8) { time += 100_000_000L; scene.render(time).close() } }
    private fun nodes(scene: ImageComposeScene): List<SemanticsNode> = scene.semanticsOwners.flatMap { owner ->
        fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
        descend(owner.rootSemanticsNode)
    }
    private fun hasText(scene: ImageComposeScene, text: String) = nodes(scene).any { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
    }
    private fun action(scene: ImageComposeScene, text: String) = nodes(scene).first { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true &&
            node.config.getOrNull(SemanticsActions.OnClick) != null
    }
    private fun click(scene: ImageComposeScene, text: String) {
        assertTrue(requireNotNull(action(scene, text).config[SemanticsActions.OnClick].action).invoke())
    }
    private fun clickRow(scene: ImageComposeScene) {
        val row = nodes(scene).first { it.config.getOrNull(SemanticsActions.OnClick)?.label == "查看日志详情" }
        assertTrue(requireNotNull(row.config[SemanticsActions.OnClick].action).invoke())
    }
    private fun scroll(scene: ImageComposeScene, amount: Float) {
        val node = nodes(scene).last { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
        requireNotNull(node.config[SemanticsActions.ScrollBy].action).invoke(0f, amount)
    }
    private fun scrollDetailToEnd(scene: ImageComposeScene) {
        repeat(64) {
            val node = nodes(scene).filter { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
                .maxBy { it.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() }
            val range = node.config[SemanticsProperties.VerticalScrollAxisRange]
            val before = range.value()
            val maximum = range.maxValue()
            if (before >= maximum) {
                assertEquals(maximum, before, "detail scroll must reach its semantic end")
                return
            }
            assertTrue(requireNotNull(node.config[SemanticsActions.ScrollBy].action).invoke(0f, maximum - before))
            frame(scene)
        }
        val range = nodes(scene).filter { it.config.getOrNull(SemanticsActions.ScrollBy) != null }
            .maxBy { it.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() }
            .config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(range.maxValue(), range.value(), "detail scroll must reach its semantic end")
    }
    private fun assertActionBounds(scene: ImageComposeScene, label: String, height: Float) {
        val bounds = action(scene, label).boundsInRoot
        assertTrue(bounds.height >= 48 && bounds.top >= 0 && bounds.bottom <= height, "$label: $bounds")
    }
    private fun assertTextVisible(scene: ImageComposeScene, text: String, height: Float) {
        val bounds = nodes(scene).first { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true
        }.boundsInRoot
        assertTrue(bounds.top >= 0 && bounds.bottom <= height, "$text bounds=$bounds")
    }
    private fun screenshot(scene: ImageComposeScene, name: String) {
        scene.render(time).use { image ->
            requireNotNull(image.encodeToData()).use { data ->
                Files.write(Path.of("/tmp/opencode/issue29-native-$name.png"), data.bytes)
            }
        }
    }
    private class TestClipboard : Clipboard {
        var entry: ClipEntry? = null
        override val nativeClipboard: Any = Any()
        override suspend fun getClipEntry(): ClipEntry? = entry
        override suspend fun setClipEntry(clipEntry: ClipEntry?) { entry = clipEntry }
    }
    private class RenderSource : OperationalLogSource {
        val first = "a".repeat(64)
        val second = "b".repeat(64)
        private val files = listOf(OperationalLogFile(first, "engine.log", "0001"), OperationalLogFile(second, "launcher.log", "0002"))
        val entries = EngineOperationalLogParser().parse((0..29).joinToString("\n") { index ->
            "2026-09-11 12:00:${index.toString().padStart(2, '0')}.000 [INFO] [ims] [0001] " +
                if (index == 29) "最新事件 · "+ "这是合成的运行状态说明，供长条目阅读验证。".repeat(140) + " token=synthetic-token"
                else "合成事件 $index"
        }).entries
        override val state = MutableStateFlow<OperationalLogUiState>(OperationalLogUiState.Ready(entries, files, first))
        fun ready() = state.value as OperationalLogUiState.Ready
        override fun refresh() = finish()
        override fun selectFile(fileId: String) {
            state.value = ready().copy(entries = emptyList(), filteredEntries = emptyList(), selectedFileId = fileId,
                loadInfo = OperationalLogLoadInfo(reading = true))
        }
        fun finish(cached: Boolean = false) {
            val selectedEntries = if (ready().selectedFileId == first) entries else EngineOperationalLogParser().parse(
                "2026-09-11 12:01:00.000 [INFO] [engine] [0002] 启动日志合成快照",
            ).entries
            state.value = ready().copy(entries = selectedEntries, filteredEntries = OperationalLogEvaluator.filter(selectedEntries, ready().filter),
                loadInfo = OperationalLogLoadInfo(cached = cached))
        }
        override fun setKindFilter(kind: OperationalLogKind?) { state.value = OperationalLogEvaluator.withFilter(state.value, ready().filter.copy(kind = kind)) }
        override fun setLevelFilter(level: OperationalLogLevel?) { state.value = OperationalLogEvaluator.withFilter(state.value, ready().filter.copy(level = level)) }
        override fun setInstanceFilter(displayLabel: String?) { state.value = OperationalLogEvaluator.withFilter(state.value, ready().filter.copy(displayLabel = displayLabel)) }
    }
}
