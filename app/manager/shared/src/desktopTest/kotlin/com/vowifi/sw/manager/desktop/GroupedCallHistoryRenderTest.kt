package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import com.vowifi.sw.manager.ui.navigation.*
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class GroupedCallHistoryRenderTest {
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private var time = 0L
    private fun record(id: String, phone: String) = requireNotNull(CallHistoryWrite.parse(CallHistoryDraft(id,
        CallDirection.OUTGOING, phone, CallTimesDraft(1000, 2000, 62000, 60), CallOutcome.COMPLETED))).toRecord(owner)

    @Test fun groupingDetailAndBothDeletionConfirmationsUseRealCallbacks() {
        val initial = CallHistoryState.Ready(listOf(record("one", "155501"), record("two", "155501"), record("other", "155502")), owner)
        var history by mutableStateOf<CallHistoryState>(initial)
        var navigation by mutableStateOf(AppNavigationState.initial(AppRoute.Calls))
        val deletes = mutableListOf<Set<CallHistoryRecordId>>()
        val callbacks = mutableListOf<String>()
        val delete: (CallHistoryState.Ready, Set<CallHistoryRecordId>) -> Unit = { expected, ids ->
            assertSame(history, expected)
            deletes.add(ids)
            history = CallHistoryState.Ready(expected.records.filterNot { it.recordId in ids }, owner)
        }
        val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val scene = ImageComposeScene(width = 390, height = 844) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle) {
            SoftVoWiFiTheme(false) {
                AppShell(navigation, { navigation = it }, appearanceSettings = AppearanceSettings.Default, blurSupported = false) { route ->
                    when (route) {
                        AppRoute.Calls -> CallsScreen(CallUiState.Unavailable("合成历史"), true, {}, history, {}, {}, {}, {}, {},
                            onOpenHistory = { navigation = AppNavigator.push(navigation, AppRoute.CallHistoryDetail(it)) },
                            callbackEnabled = true, onCallback = callbacks::add, onDeleteRecords = delete)
                        is AppRoute.CallHistoryDetail -> CallHistoryDetailScreen(route.history, history, delete)
                        else -> Box(Modifier.fillMaxSize())
                    }
                }
            }
            }
        }
        try {
            frame(scene)
            assertTrue(texts(scene).contains("2 次通话"))
            long(scene, "155501"); click(scene, "回拨")
            assertEquals(listOf("155501"), callbacks)
            click(scene, "155501")
            assertIs<AppRoute.CallHistoryDetail>(navigation.topRoute)
            assertEquals(2, texts(scene).count { it == "时长：1 分 0 秒" })
            scene.render(time).use { image -> org.jetbrains.skia.Bitmap.makeFromImage(image).use { bitmap ->
                assertEquals(0xFFF3F7F5.toInt(), bitmap.getColor(2, 180), "详情画布与电话一致")
                assertEquals(0xFFF3F7F5.toInt(), bitmap.getColor(2, 20), "宿主顶栏与详情一致")
            } }
            val directory = java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/reports/phone-compact-details"))
            scene.render(time).use { image -> image.encodeToData()!!.use {
                java.nio.file.Files.write(directory.resolve("detail.png"), it.bytes)
            } }
            val occurrence = nodes(scene).first { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "删除通话记录" }
            requireNotNull(occurrence.config[SemanticsActions.OnLongClick].action).invoke(); frame(scene)
            click(scene, "删除"); click(scene, "取消")
            assertTrue(deletes.isEmpty())
            requireNotNull(occurrence.config[SemanticsActions.OnLongClick].action).invoke(); frame(scene)
            click(scene, "删除"); click(scene, "删除")
            assertEquals(1, deletes.single().size)
            navigation = AppNavigator.back(navigation); frame(scene)
            long(scene, "155501"); click(scene, "删除"); click(scene, "删除")
            assertEquals(2, deletes.size)
            assertEquals("155502", assertIs<CallHistoryState.Ready>(history).records.single().peer?.digits())
        } finally { scene.close() }
    }
    @Test fun historyCallbackPreservesTheGlobalPrefixInTheRealMenu() {
        val history = CallHistoryState.Ready(listOf(record("synthetic-global", "+12025550123")), owner)
        val callbacks = mutableListOf<String>()
        val scene = ImageComposeScene(width = 390, height = 844) {
            SoftVoWiFiTheme(false) {
                CallsScreen(CallUiState.Unavailable("合成历史"), true, {}, history, {}, {}, {}, {}, {},
                    callbackEnabled = true, onCallback = callbacks::add)
            }
        }
        try {
            frame(scene)
            val row = nodes(scene).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "通话记录操作" }
            assertTrue(requireNotNull(row.config[SemanticsActions.OnLongClick].action).invoke()); frame(scene)
            click(scene, "回拨")
            assertEquals(listOf("+12025550123"), callbacks)
        } finally { scene.close() }
    }

    private fun frame(scene: ImageComposeScene) { repeat(12) { time += 100_000_000L; scene.render(time).close() } }
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { owner ->
        fun tree(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::tree)
        tree(owner.rootSemanticsNode)
    }
    private fun texts(scene: ImageComposeScene) = nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    private fun click(scene: ImageComposeScene, text: String) {
        val node = nodes(scene).lastOrNull { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true && it.config.contains(SemanticsActions.OnClick) }
        assertNotNull(node, "缺少真实动作 $text；当前合成界面文本：${texts(scene)}")
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke()); frame(scene)
    }
    private fun long(scene: ImageComposeScene, text: String) {
        val node = nodes(scene).last { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true && it.config.contains(SemanticsActions.OnLongClick) }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnLongClick].action).invoke()); frame(scene)
    }
}
