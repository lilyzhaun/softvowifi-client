package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import top.yukonga.miuix.kmp.basic.Scaffold
import kotlin.test.*

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
class UnifiedLineControlsTest {
    private val a = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val b = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43)))
    private var time = 0L
    @Test fun callSelectorOnlyListsRunningEnginesEvenWhenTheyAreNotRegistered() {
        var picked: SimOwnerId? = null
        val lines = CallLineState(listOf(CallLineChoice(a, "SIM 1 · Skinny", true, true, false, false, true, running = true),
            CallLineChoice(b, "SIM 2 · Lebara", true, false, null, false, false)), selectedOwner = a)
        val scene = scene { CallLineEntry(lines, "", { picked = it }) }
        try {
            frame(scene); click(scene, "出向线路"); frame(scene)
            assertTrue(texts(scene).contains("选择拨出线路"))
            assertFalse(texts(scene).contains("SIM 2 · Lebara"))
            click(scene, "SIM 1 · Skinny"); frame(scene); assertEquals(a, picked)
        } finally { scene.close() }
    }
    @Test fun stoppedSelectorBrowsesHistoryWithoutSelectingOutgoing() {
        val choice = CallHistoryOwnerChoice(b, "历史线路 2", true)
        var picked: CallHistoryOwnerChoice? = null
        val scene = scene { CallLineEntry(CallLineState(), "", { error("历史不能改变拨出绑定") },
            historyOwners = listOf(choice), onSelectHistory = { picked = it }) }
        try {
            frame(scene); click(scene, "通话记录线路"); frame(scene)
            assertTrue(texts(scene).contains("选择通话记录线路"))
            click(scene, "历史线路 2"); frame(scene); assertSame(choice, picked)
        } finally { scene.close() }
    }
    @Test fun dialerHasBackButNoHistoryOrInternalLinePicker() {
        val scene = scene { PhoneDialerScreen("", {}, "SIM 1 · Skinny", false, false, null, {}, {}, {}) }
        try {
            frame(scene)
            val descriptions = nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            assertTrue("返回" in descriptions)
            assertFalse("历史" in descriptions)
            assertFalse("出向线路" in descriptions)
        } finally { scene.close() }
    }
    private fun scene(content: @Composable () -> Unit) = ImageComposeScene(width = 390, height = 844) {
        SoftVoWiFiTheme(false) { Scaffold(Modifier.fillMaxSize()) { content() } }
    }
    private fun frame(scene: ImageComposeScene) { repeat(12) { time += 100_000_000L; scene.render(time).close() } }
    private fun nodes(scene: ImageComposeScene) = scene.semanticsOwners.flatMap { owner ->
        fun tree(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::tree)
        tree(owner.rootSemanticsNode)
    }
    private fun texts(scene: ImageComposeScene) = nodes(scene).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
    private fun click(scene: ImageComposeScene, label: String) {
        val node = nodes(scene).last { (it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
            it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true) && it.config.contains(SemanticsActions.OnClick) }
        assertTrue(requireNotNull(node.config[SemanticsActions.OnClick].action).invoke())
    }
}
