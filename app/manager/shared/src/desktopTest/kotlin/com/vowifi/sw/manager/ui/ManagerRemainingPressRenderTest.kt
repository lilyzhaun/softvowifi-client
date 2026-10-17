package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.data.StoredMessageConversation
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import top.yukonga.miuix.kmp.basic.Scaffold
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S1 remaining production consumers only: notification permission P5 and view-new P2.
 * Real screen/state owners are rendered; no copied button, fake hasNewMessages or screenshot.
 * ScrollToIndex prepares the real lazy-list owner; tested actions use pointer Press/Release.
 * Zero animation isolates retained draw amplitudes, not system/device animation acceptance.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalCoroutinesApi::class)
class ManagerRemainingPressRenderTest {
    @Test fun remainingProductionNoticeAndViewLatestUseTheirActualKeyboardFocusOwner() = onMotionTestThread { runTest {
        val failures = mutableListOf<String>()
        for (thread in listOf(false, true)) {
            val notice = NotificationProbe(); val latest = ThreadProbe()
            var mode: androidx.compose.ui.input.InputModeManager? = null; var accent = 0
            Fixture(testScheduler) {
                val manager = androidx.compose.ui.platform.LocalInputModeManager.current
                val ink = if (thread) LocalSoftVoWiFiColors.current.interactive else phoneColors().accent
                androidx.compose.runtime.SideEffect { mode = manager; accent = ink.toArgb() }
                CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f)) {
                    if (thread) latest.Content() else notice.Content()
                }
            }.use { fixture ->
                if (thread) latest.prepareViewNew(fixture) else fixture.frames(20)
                val label = if (thread) ViewNewLabel else NotificationLabel
                fixture.requireReachable(fixture.action(label))
                val bounds = fixture.action(label).boundsInRoot
                assertTrue(bounds.top >= 1f && bounds.bottom <= Height, "前提：真实按钮完整处于Scene内")
                failures += s7RemainingFocusCheck(fixture.environment, testScheduler, kotlin.test.assertNotNull(mode),
                    accent, "remaining thread=$thread scale=0", { fixture.action(label) },
                    { if (thread) latest.active = false else notice.active = false }, reverse = thread)
                assertEquals(0, notice.opens); assertEquals(0, latest.sends)
                assertTrue(fixture.haptics.isEmpty())
                if (thread) {
                    assertTrue(fixture.hasAction(ViewNewLabel), "Tab不能执行查看新消息或清除原标志")
                    assertFalse(latest.latestId in latest.visibleIds, "未发Enter不能提前滚到最新")
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    } }

    @Test fun notificationPermissionUsesP5ContentAlphaWithoutScalingItsClickTarget() = onMotionTestThread { runTest {
        val probe = NotificationProbe()
        Fixture(testScheduler) { probe.Content() }.use { fixture ->
            fixture.frames(20)
            val action = fixture.action(NotificationLabel)
            fixture.requireReachable(action)
            val original = action.boundsInRoot
            val rest = fixture.ink(original, original)
            fixture.environment.press(original.center)
            fixture.frames(15) // Let the real lazy-container clickable emit its delayed Press.
            val pressedBounds = fixture.action(NotificationLabel).boundsInRoot
            val pressed = fixture.ink(original, original, foreground = rest.foreground)
            assertEquals(0, probe.opens, "按压不能提前打开系统设置")
            fixture.environment.release(original.center)
            fixture.frames(8)
            assertEquals(1, probe.opens, "须先证明真实提示Text确实接收到pointer点击，未命中不算P5复现")
            assertEquals(listOf(HapticFeedbackType.ContextClick), fixture.haptics)

            assertEquals(original.width, pressedBounds.width, .2f, "开放提示行P5不缩放点击几何")
            assertEquals(original.height, pressedBounds.height, .2f)
            assertEquals(.82f, pressed.coverage / rest.coverage, .025f,
                "P5仅内容alpha=.82；不能用整行.96缩放或空白图冒充变暗")
        }
    } }

    @Test fun notificationPermissionInactiveThenRestoredCannotCommitTheOldPointer() = onMotionTestThread { runTest {
        val probe = NotificationProbe()
        Fixture(testScheduler) { probe.Content() }.use { fixture ->
            fixture.frames(20)
            fixture.tap(fixture.action(NotificationLabel))
            assertEquals(1, probe.opens, "取消合同前须确认原生产提示入口可以真正命中")
            probe.opens = 0
            fixture.haptics.clear()
            val position = fixture.action(NotificationLabel).boundsInRoot.center
            fixture.environment.press(position)
            fixture.frames(15)
            probe.active = false
            fixture.frames(3)
            probe.active = true
            fixture.frames(3)
            fixture.environment.release(position)
            fixture.frames(8)
            assertEquals(0, probe.opens, "route失活须取消真实clickable，恢复不能复活旧手指")
            assertTrue(fixture.haptics.isEmpty())
            fixture.tap(fixture.action(NotificationLabel))
            assertEquals(1, probe.opens, "恢复后新的真实点击仍可用，不以永久禁用掩盖旧手势问题")
        }
    } }

    @Test fun actualViewNewMessageButtonUsesP2ScaleWithAStableClickTarget() = onMotionTestThread { runTest {
        val probe = ThreadProbe()
        Fixture(testScheduler) { probe.Content() }.use { fixture ->
            probe.prepareViewNew(fixture)
            val action = fixture.action(ViewNewLabel)
            fixture.requireReachable(action)
            val original = action.boundsInRoot
            val text = fixture.text(ViewNewLabel).boundsInRoot
            val region = Rect(text.left - 4f, text.top - 4f, text.right + 4f, text.bottom + 4f)
            val rest = fixture.ink(region, original)
            fixture.environment.press(original.center)
            fixture.frames(15)
            val pressedBounds = fixture.action(ViewNewLabel).boundsInRoot
            // Normalize by the actual pressed foreground as well: a library overlay can dim
            // text without shrinking it, and must not accidentally satisfy the .96 P2 scale.
            val pressed = fixture.ink(region, original)
            assertFalse(probe.latestId in probe.visibleIds, "按下只能按压，不能提前执行查看新消息")
            assertTrue(fixture.haptics.isEmpty(), "§4.9：按下不发点击触感")
            fixture.environment.release(original.center)
            fixture.frames(120)
            probe.assertReachedLatest(fixture)

            assertEquals(listOf(HapticFeedbackType.ContextClick), fixture.haptics, "§4.9：普通点击实际执行时恰好一次ContextClick")

            assertEquals(original.width, pressedBounds.width, .2f, "P2缩放只能绘制，不能缩小点击几何")
            assertEquals(original.height, pressedBounds.height, .2f)
            assertEquals(.96f * .96f, pressed.coverage / rest.coverage, .025f,
                "真实P2内容应随.96缩放，不能只保留miuix默认按压色层")
            assertEquals(0, probe.sends, "查看最新消息不改变原发送业务")
        }
    } }

    @Test fun actualViewNewMessageButtonInactiveThenRestoredDoesNotRunTheOldPointer() = onMotionTestThread { runTest {
        val probe = ThreadProbe()
        Fixture(testScheduler) { probe.Content() }.use { fixture ->
            probe.prepareViewNew(fixture)
            fixture.tap(fixture.action(ViewNewLabel))
            fixture.frames(120)
            probe.assertReachedLatest(fixture) // Positive pointer proof before cancellation checks.
            probe.prepareViewNew(fixture)
            val action = fixture.action(ViewNewLabel)
            fixture.requireReachable(action)
            val position = action.boundsInRoot.center
            fixture.haptics.clear()
            fixture.environment.press(position)
            fixture.frames(15)
            probe.active = false
            fixture.frames(3)
            probe.active = true
            fixture.frames(3)
            fixture.environment.release(position)
            fixture.frames(120)

            assertTrue(fixture.hasAction(ViewNewLabel),
                "原hasNewMessages必须保持；失活后的旧pointer不得清标志并滚到最新")
            assertFalse(probe.latestId in probe.visibleIds, "不能只隐藏按钮而已经执行了原滚动动作")
            assertTrue(fixture.haptics.isEmpty())
            fixture.tap(fixture.action(ViewNewLabel))
            fixture.frames(120)
            probe.assertReachedLatest(fixture)
            assertEquals(0, probe.sends)
        }
    } }

    private class NotificationProbe {
        var active by mutableStateOf(true)
        var opens = 0
        @Composable fun Content() {
            CompositionLocalProvider(LocalAppRouteActive provides active) {
                Scaffold(Modifier.fillMaxSize()) {
                    MessagesScreen(MessageUiState.Empty, onOpenThread = {}, onNewMessage = {},
                        notificationsEnabled = false, onNotificationSettings = { opens++ })
                }
            }
        }
    }

    private class ThreadProbe {
        private val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
        private val peer = "+12025550123"
        private val identity = StoredMessageConversation(owner, peer.drop(1))
        var active by mutableStateOf(true)
        var records by mutableStateOf<List<MessageRecord>>(List(24) { incoming(it) })
        var visibleIds: List<String> = emptyList()
        var sends = 0
        val latestId: String get() = records.last().id

        @Composable fun Content() {
            AppSubpageScene(AppRoute.MessageThread(identity.handle), appChromeSpec(AppearanceSettings.Default.copy(
                blurRequested = false, liquidGlassRequested = false), false), routeActive = active, onBack = {}) {
                MessageThreadScreen(identity.handle,
                    MessageUiState.Ready(records, MessageOrigin.ENGINE, composerEnabled = false, ownerId = owner),
                    onSend = { _, _ -> sends++ },
                    onVisible = { handle, ids -> if (handle == identity.handle) visibleIds = ids })
            }
        }

        fun prepareViewNew(fixture: Fixture) {
            fixture.frames(32)
            assertTrue(latestId in visibleIds, "前提：生产线程初次/上次查看最新后确实位于原最新消息")
            val list = fixture.nodes().single { it.config.contains(SemanticsActions.ScrollToIndex) }
            assertTrue(requireNotNull(list.config[SemanticsActions.ScrollToIndex].action).invoke(0),
                "仅调用真实LazyColumn的公开滚动所有者，不复制列表/按钮或手改hasNewMessages")
            fixture.frames(24)
            assertFalse(latestId in visibleIds, "前提：已离开末尾，不能在自动跟随状态下测试查看新消息")
            val newIndex = records.size
            records = records + incoming(newIndex)
            fixture.frames(24)
            assertTrue(fixture.hasAction(ViewNewLabel),
                "前提：必须由原latestId/previousLatestId/hasNewMessages逻辑产生原按钮；缺入口不算按压RED")
            assertFalse(latestId in visibleIds, "前提：新增消息尚未被原自动跟随显示")
        }

        fun assertReachedLatest(fixture: Fixture) {
            assertTrue(latestId in visibleIds, "须证明真实按钮pointer执行了原animateScrollToItem，未命中不算P2复现")
            assertFalse(fixture.hasAction(ViewNewLabel), "原hasNewMessages完成后应按已有逻辑清除")
        }

        private fun incoming(index: Int): MessageRecord.Incoming = MessageRecord.Incoming(
            "remaining-message-$index", peer, "合成历史 $index：只用于剩余按钮入口与按压合同。", 1790848800000L + index * 1000L,
            isRead = true,
        )
    }

    private data class InkSample(val coverage: Float, val foreground: Int)

    private class Fixture(private val scheduler: TestCoroutineScheduler, content: @Composable () -> Unit) : AutoCloseable {
        val haptics = mutableListOf<HapticFeedbackType>()
        private val recorder = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType }
        }
        private val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        val environment = MotionTestEnvironment(scheduler, width = Width, height = Height, durationScale = 0f) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle, LocalHapticFeedback provides recorder) {
                SoftVoWiFiTheme(false) {
                    PhoneActionFeedback(AppearanceSettings.Default.copy(blurRequested = false, liquidGlassRequested = false),
                        blurSupported = false, content = content)
                }
            }
        }

        fun frames(count: Int) {
            assertTrue(count in 1..120, "有界帧数；不轮询等待按钮出现")
            repeat(count) { environment.advanceFrameBy() }
        }
        fun tap(node: SemanticsNode) {
            requireReachable(node)
            environment.press(node.boundsInRoot.center)
            environment.advanceFrameBy(8)
            environment.release(node.boundsInRoot.center)
            frames(8)
        }
        fun requireReachable(node: SemanticsNode) {
            val r = node.boundsInRoot
            assertTrue(r.width >= 48f && r.height >= 48f && r.center.x in 0f..Width.toFloat() && r.center.y in 0f..Height.toFloat(),
                "真实生产动作必须已测量且可触达，不以点击失败冒充行为失败")
        }
        fun nodes(unmerged: Boolean = false): List<SemanticsNode> = environment.scene.semanticsOwners.flatMap { owner ->
            fun tree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::tree)
            tree(if (unmerged) owner.unmergedRootSemanticsNode else owner.rootSemanticsNode)
        }
        private fun matches(node: SemanticsNode, label: String): Boolean =
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true ||
                node.children.any { matches(it, label) }
        fun hasAction(label: String): Boolean = nodes().any { it.config.contains(SemanticsActions.OnClick) && matches(it, label) }
        fun action(label: String): SemanticsNode = nodes().single {
            it.config.contains(SemanticsActions.OnClick) && matches(it, label)
        }
        fun text(label: String): SemanticsNode = nodes(unmerged = true).last {
            it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true
        }

        /** Project real antialiased text coverage on its uniform background; no image is saved. */
        fun ink(region: Rect, actionBounds: Rect, foreground: Int? = null): InkSample =
            environment.scene.render(scheduler.currentTime * 1_000_000L).use { image ->
                Bitmap.makeFromImage(image).use { bitmap ->
                    fun channel(pixel: Int, shift: Int) = pixel ushr shift and 255
                    val shifts = listOf(16, 8, 0)
                    val background = bitmap.getColor(actionBounds.center.x.roundToInt().coerceIn(0, Width - 1),
                        (actionBounds.bottom - 3f).roundToInt().coerceIn(0, Height - 1))
                    val left = floor(region.left).toInt().coerceIn(0, Width)
                    val top = floor(region.top).toInt().coerceIn(0, Height)
                    val right = ceil(region.right).toInt().coerceIn(left, Width)
                    val bottom = ceil(region.bottom).toInt().coerceIn(top, Height)
                    var strongest = background
                    var maxDistance = 0.0
                    if (foreground == null) for (y in top until bottom) for (x in left until right) {
                        val pixel = bitmap.getColor(x, y)
                        val distance = shifts.sumOf { shift ->
                            val delta = channel(pixel, shift) - channel(background, shift)
                            (delta * delta).toDouble()
                        }
                        if (distance > maxDistance) { maxDistance = distance; strongest = pixel }
                    }
                    val ink = foreground ?: strongest
                    val delta = shifts.map { (channel(ink, it) - channel(background, it)).toFloat() }
                    val denominator = delta.sumOf { (it * it).toDouble() }.toFloat()
                    assertTrue(denominator > 100f, "文字与背景必须真实可分；空图或错误取样不算按压复现")
                    var area = 0f
                    for (y in top until bottom) for (x in left until right) {
                        val pixel = bitmap.getColor(x, y)
                        val projection = shifts.indices.sumOf { i ->
                            ((channel(pixel, shifts[i]) - channel(background, shifts[i])) * delta[i]).toDouble()
                        }.toFloat()
                        area += (projection / denominator).coerceIn(0f, 1f)
                    }
                    assertTrue(area > 10f, "必须观察到实际文字覆盖，不能以颜色采样零值通过")
                    InkSample(area, ink)
                }
            }
        override fun close() = environment.close()
    }

    private companion object {
        const val Width = 390
        const val Height = 800
        const val NotificationLabel = "短信通知未开启，点此打开系统设置。短信仍会保存。"
        const val ViewNewLabel = "查看新消息"
    }
}
