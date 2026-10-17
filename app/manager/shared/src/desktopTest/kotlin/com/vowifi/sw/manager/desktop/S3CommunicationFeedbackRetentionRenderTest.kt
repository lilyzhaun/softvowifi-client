@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.data.CallHistoryDraft
import com.vowifi.sw.manager.data.CallHistoryWrite
import com.vowifi.sw.manager.data.StoredMessageConversation
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import top.yukonga.miuix.kmp.basic.Scaffold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// 当前 host 的保持测试，不是新全局 host 的 RED；每个场景始终只有一个真实生产 host。
class S3CommunicationFeedbackRetentionRenderTest {
    @Test fun oneCurrentHostReplacesCopyKindsWithoutStackingOrMovingContent() = onMotionTestThread { runTest {
        S3FeedbackFixture(testScheduler).use { fixture ->
            val before = fixture.text("合成正文").boundsInRoot
            fixture.copyMessage()
            assertEquals(listOf("已复制"), fixture.feedbackLabels())
            assertEquals(1, fixture.clipboard.writes)
            fixture.advanceBy(600)
            fixture.copyNumber()
            assertEquals(2, fixture.clipboard.writes)
            assertNotNull(fixture.clipboard.entry)
            assertEquals(listOf("已复制号码"), fixture.feedbackLabels())
            assertEquals(before, fixture.text("合成正文").boundsInRoot, "提示替换不能改变正文几何")
            assertEquals(LiveRegionMode.Polite, fixture.text("已复制号码").config.getOrNull(SemanticsProperties.LiveRegion))
            fixture.advanceTo(fixture.clipboard.lastWriteAt + 2200)
            fixture.frames(2)
            assertTrue(fixture.feedbackLabels().isEmpty())
            fixture.advanceBy(2200)
            assertTrue(fixture.feedbackLabels().isEmpty(), "不能排队复活被替换的旧复制提示")
        }
    } }

    @Test fun repeatedSuccessfulCopyRestartsTheAccessibilityRecommendedLifetime() = onMotionTestThread { runTest {
        val accessibility = S3RecommendedTimeout(5000)
        S3FeedbackFixture(testScheduler, accessibility = accessibility).use { fixture ->
            fixture.copyMessage()
            val firstCopiedAt = fixture.clipboard.lastWriteAt
            fixture.advanceTo(firstCopiedAt + 2500)
            assertEquals(listOf("已复制"), fixture.feedbackLabels(), "不能在默认2000ms到期时忽略推荐时长")
            fixture.copyMessage()
            val secondCopiedAt = fixture.clipboard.lastWriteAt
            assertEquals(2, fixture.clipboard.writes)
            fixture.advanceTo(firstCopiedAt + 5100)
            assertEquals(listOf("已复制"), fixture.feedbackLabels(), "同种复制成功必须重置本次计时")
            fixture.advanceTo(secondCopiedAt + 4999)
            assertEquals(listOf("已复制"), fixture.feedbackLabels())
            fixture.advanceTo(secondCopiedAt + 5100)
            fixture.frames(2)
            assertTrue(fixture.feedbackLabels().isEmpty())
            assertTrue(accessibility.requests.isNotEmpty())
            assertTrue(accessibility.requests.all { it == S3TimeoutRequest(2000, false, true, false) })
        }
    } }

    @Test fun actualThreadCopyAvoidsItsMeasuredComposerAndKeepsTheTimelineStill() = onMotionTestThread { runTest {
        S3FeedbackFixture(testScheduler, thread = true).use { fixture ->
            val editor = fixture.nodes().single { it.config.contains(SemanticsActions.SetText) }
            assertTrue(requireNotNull(editor.config[SemanticsActions.SetText].action)
                .invoke(AnnotatedString("合成草稿第一行\n第二行\n第三行")))
            fixture.frames(4)
            val composerBefore = fixture.nodes().single { it.config.contains(SemanticsActions.SetText) }.boundsInRoot
            val bubbleBefore = fixture.text("合成正文").boundsInRoot
            fixture.copyMessage()
            assertEquals(1, fixture.clipboard.writes)
            assertEquals(listOf("已复制"), fixture.feedbackLabels())
            val result = fixture.text("已复制").boundsInRoot
            val composerAfter = fixture.nodes().single { it.config.contains(SemanticsActions.SetText) }.boundsInRoot
            assertEquals(composerBefore, composerAfter, "复制提示不参与输入区测量")
            assertEquals(bubbleBefore, fixture.text("合成正文").boundsInRoot, "不靠推高时间线给提示让位")
            assertTrue(result.bottom <= composerAfter.top - 8f, "实际 composerHeight 必须让复制提示避开输入区")
            assertEquals(LiveRegionMode.Polite, fixture.text("已复制").config.getOrNull(SemanticsProperties.LiveRegion))
        }
    } }

    @Test fun inactiveRouteClearsTheCurrentResultAndReentryDoesNotRestoreIt() = onMotionTestThread { runTest {
        S3FeedbackFixture(testScheduler, thread = true).use { fixture ->
            fixture.copyMessage()
            assertEquals(listOf("已复制"), fixture.feedbackLabels())
            fixture.active.value = false
            fixture.frames(2)
            assertTrue(fixture.feedbackLabels().isEmpty())
            fixture.active.value = true
            fixture.frames(2)
            assertTrue(fixture.feedbackLabels().isEmpty())
            assertEquals(1, fixture.clipboard.writes)
        }
    } }
}

// 只为已有两个固定复制事件准备单一 host 的叶级内容，不声称这是实际 App 同屏导航。
private class S3FeedbackFixture(
    private val scheduler: TestCoroutineScheduler,
    private val thread: Boolean = false,
    accessibility: AccessibilityManager? = null,
) : AutoCloseable {
    val active = mutableStateOf(true)
    val clipboard = S3RecordingClipboard(scheduler)
    private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
    private val peer = "+12025550123"
    private val lifecycle = object : LifecycleOwner {
        override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
    }
    private val record = requireNotNull(CallHistoryWrite.parse(CallHistoryDraft("one", CallDirection.OUTGOING,
        peer, CallTimesDraft(1000, 2000, 62000, 60), CallOutcome.COMPLETED))).toRecord(owner)
    val environment = MotionTestEnvironment(scheduler, width = 390, height = 844, durationScale = 0f) {
        CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalClipboard provides clipboard,
            LocalAccessibilityManager provides accessibility, LocalDensity provides Density(1f, 1f),
            LocalAppRouteActive provides active.value) {
            SoftVoWiFiTheme(false) {
                Scaffold(Modifier.fillMaxSize()) {
                    if (thread) {
                        // MessageThreadScreen 自己持有当前唯一 host；外层不再包第二个 host。
                        MessageThreadScreen(StoredMessageConversation(owner, peer.drop(1)).handle,
                            MessageUiState.Ready(listOf(MessageRecord.Incoming("message", peer, "合成正文", 1)),
                                MessageOrigin.ENGINE, composerEnabled = true, ownerId = owner),
                            { _, _ -> error("复制/输入测试不能发送短信") })
                    } else {
                        CommunicationFeedbackHost(bottomInset = 72.dp) {
                            Column(Modifier.padding(16.dp)) {
                                IncomingMessageBubble(MessageThreadItem.IncomingBubble("message", "合成正文", MessageTimestampSpec(1)))
                                CallHistoryGroupRow(callHistoryGroups(CallHistoryState.Ready(listOf(record), owner)).single(),
                                    { error("不应打开详情") }, false, { error("不应回拨") }, { error("不应删除") })
                            }
                        }
                    }
                }
            }
        }
    }
    init { frames(3) }
    fun frames(count: Int = 1) { repeat(count) { environment.advanceFrameBy(16) } }
    fun advanceBy(milliseconds: Long) { environment.advanceFrameBy(milliseconds); frames(2) }
    fun advanceTo(milliseconds: Long) {
        check(milliseconds >= scheduler.currentTime) { "测试时间不得回退" }
        environment.advanceFrameBy(milliseconds - scheduler.currentTime)
    }
    fun nodes(unmerged: Boolean = true): List<SemanticsNode> = environment.scene.semanticsOwners.flatMap { owner ->
        fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
        tree(if (unmerged) owner.unmergedRootSemanticsNode else owner.rootSemanticsNode)
    }
    fun text(label: String) = nodes().single {
        it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == label } == true
    }
    fun feedbackLabels() = nodes().flatMap { node ->
        node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
    }.filter { it == "已复制" || it == "已复制号码" }
    fun copyMessage() {
        val bubble = nodes(unmerged = false).single {
            it.config.getOrNull(SemanticsActions.OnLongClick)?.label == if (thread) "消息操作" else "复制短信"
        }
        assertTrue(requireNotNull(bubble.config[SemanticsActions.OnLongClick].action).invoke())
        frames(3)
        if (thread) tapMenu("复制正文")
        assertNotNull(clipboard.entry)
    }
    fun copyNumber() {
        val row = nodes(unmerged = false).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "通话记录操作" }
        assertTrue(requireNotNull(row.config[SemanticsActions.OnLongClick].action).invoke())
        frames(3)
        tapMenu("复制号码")
    }
    private fun tapMenu(label: String) {
        val point = nodes(unmerged = false).single {
            it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == label } == true
        }.boundsInRoot.center
        environment.press(point)
        environment.advanceFrameBy(8)
        environment.release(point)
        frames(4)
    }
    override fun close() = environment.close()
}

private class S3RecordingClipboard(private val scheduler: TestCoroutineScheduler) : Clipboard {
    override val nativeClipboard: Any = Any()
    var entry: ClipEntry? = null
    var writes = 0
    var lastWriteAt = 0L
    override suspend fun getClipEntry() = entry
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        entry = clipEntry
        writes++
        lastWriteAt = scheduler.currentTime
    }
}

private data class S3TimeoutRequest(val original: Long, val icons: Boolean, val text: Boolean, val controls: Boolean)
private class S3RecommendedTimeout(private val recommended: Long) : AccessibilityManager {
    val requests = mutableListOf<S3TimeoutRequest>()
    override fun calculateRecommendedTimeoutMillis(originalTimeoutMillis: Long, containsIcons: Boolean,
        containsText: Boolean, containsControls: Boolean): Long {
        requests += S3TimeoutRequest(originalTimeoutMillis, containsIcons, containsText, containsControls)
        return recommended
    }
}
