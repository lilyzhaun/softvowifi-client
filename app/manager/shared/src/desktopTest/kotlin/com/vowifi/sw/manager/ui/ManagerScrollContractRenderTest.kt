package com.vowifi.sw.manager.ui

import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vowifi.sw.manager.ui.navigation.AppNavigationState
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.data.StoredMessageConversation
import com.vowifi.sw.manager.model.MessageRecord
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.model.MessageOrigin
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.jetbrains.skia.Bitmap
import top.yukonga.miuix.kmp.basic.Text
import kotlin.test.*

/** S2 §5.2 R2/R3/R7/R8/R9: production scaffolds, actual pointer/semantics, not a shadow state machine. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ManagerScrollContractRenderTest {
    @Test fun managementListDoesNotInstallASecondThemeOverscroll() = onMotionTestThread { runTest {
        var factories = 0
        val factory = object : OverscrollFactory {
            override fun createOverscrollEffect(): OverscrollEffect {
                factories++
                return object : OverscrollEffect {
                    override val isInProgress = false
                    override val node = object : Modifier.Node() {}
                    override fun applyToScroll(delta: Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
                        performScroll: (Offset) -> Offset): Offset = performScroll(delta)
                    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) { performFling(velocity) }
                }
            }
            override fun equals(other: Any?) = other === this
            override fun hashCode() = 31
        }
        Driver(testScheduler, Kind.MANAGER, factory = factory).use { driver ->
            driver.frames(3)
            assertTrue(driver.nodes().any { it.config.contains(SemanticsActions.ScrollBy) })
            assertEquals(0, factories, "R2：显式回弹拥有者不能再安装主题factory")
        }
    } }

    @Test fun callsQueryChangeResetsTheRealList() = queryReset(Kind.CALLS)
    @Test fun messagesQueryChangeResetsTheRealList() = queryReset(Kind.MESSAGES)
    private fun queryReset(kind: Kind) = onMotionTestThread { runTest {
        Driver(testScheduler, kind).use { d ->
            d.frames(5); d.scrollBy(420f); d.frames(40)
            assertTrue(d.axis().value() > 0f, "必须先真实滚动")
            d.query = "新条件"; d.frames(5)
            assertEquals(0f, d.axis().value(), "R9：条件改变后直接归位，不保留旧结果滚动")
        }
    } }

    @Test fun callsHeaderDividerAppearsOnlyAfterScrolling() = divider(Kind.CALLS)
    @Test fun messagesHeaderDividerAppearsOnlyAfterScrolling() = divider(Kind.MESSAGES)
    private fun divider(kind: Kind) = onMotionTestThread { runTest {
        Driver(testScheduler, kind).use { d ->
            d.frames(5); val rest = d.headerPixel()
            d.scrollBy(180f); d.frames(30)
            assertTrue(d.axis().value() > 0f)
            assertNotEquals(rest, d.headerPixel(), "R7：固定标题下缘应有实际分隔线绘制")
            d.scrollBy(-10000f); d.frames(40)
            assertEquals(0f, d.axis().value())
            assertEquals(rest, d.headerPixel(), "回到顶部后分隔线消失")
        }
    } }

    @Test fun callsFlingArrivesWithOneBoundaryHaptic() = onMotionTestThread { runTest {
        Driver(testScheduler, Kind.CALLS, count = 16).use { d ->
            d.frames(5)
            val start = Offset(180f, 400f)
            d.environment.press(start)
            repeat(5) { index -> d.frames(1); d.environment.move(start - Offset(0f, (index + 1) * 36f)) }
            d.environment.release(start - Offset(0f, 180f)); d.frames(140)
            assertTrue(d.axis().value() > 0f)
            assertEquals(d.axis().maxValue(), d.axis().value(), .1f, "必须真实甩到末端")
            assertEquals(listOf(HapticFeedbackType.TextHandleMove), d.haptics, "R3：抵达边界恰好一次")
        }
    } }

    @Test fun nativeThreadEffectAlsoEmitsOneBoundaryHaptic() = onMotionTestThread { runTest {
        Driver(testScheduler, Kind.THREAD, count = 16).use { d ->
            d.frames(40)
            d.scrollToIndex(0); d.frames(10)
            assertEquals(0f, d.axis().value())
            val start = Offset(180f, 360f)
            d.environment.press(start)
            repeat(5) { index -> d.frames(1); d.environment.move(start - Offset(0f, (index + 1) * 36f)) }
            d.environment.release(start - Offset(0f, 180f)); d.frames(140)
            assertTrue(d.axis().value() > 0f)
            assertEquals(d.axis().maxValue(), d.axis().value(), .1f, "原生effect的真实线程必须甩到末端")
            assertEquals(listOf(HapticFeedbackType.TextHandleMove), d.haptics)
        }
    } }

    @Test fun normalTabReselectionReturnsItsOwnListToTop() = reselect(false)
    @Test fun floatingTabReselectionReturnsItsOwnListToTop() = reselect(true)
    @Test fun shortCommunicationListsReboundAndZeroMotionSuppressesTravel() = onMotionTestThread { runTest {
        for (kind in listOf(Kind.CALLS, Kind.MESSAGES)) for (scale in listOf(1f, 0f)) {
            Driver(testScheduler, kind, count = 1, durationScale = scale).use { d ->
                d.frames(6)
                fun itemTop() = d.nodes().single { it.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == "条目 0" } == true }.boundsInRoot.top
                val top = itemTop()
                val start = Offset(8f, 200f)
                d.environment.press(start)
                repeat(8) { index -> d.frames(1); d.environment.move(start + Offset(0f, (index + 1) * 12f)) }
                d.frames(2)
                if (scale == 0f) assertEquals(top, itemTop(), .5f, "R2零动画不越界")
                else assertTrue(itemTop() > top + 8f, "R2短列表也必须真实位移")
                d.environment.release(start + Offset(0f, 96f)); d.frames(100)
                assertEquals(top, itemTop(), 1f, "原miuix弹簧回到静止边界")
                assertTrue(d.haptics.isEmpty(), "短列表拖动不制造抵达触感")
            }
        }
    } }
    private fun reselect(floating: Boolean) = onMotionTestThread { runTest {
        Driver(testScheduler, Kind.ROOT, floating = floating).use { d ->
            d.frames(20); d.scrollBy(1800f); d.frames(40)
            assertTrue(d.axis().value() > 0f)
            val target = d.nodes().first { it.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "首页" } == true }.boundsInRoot.center
            d.environment.press(target); d.frames(2); d.environment.release(target); d.frames(120)
            assertEquals(0f, d.axis().value(), "R8：真实点击当前底栏项必须回顶")
            assertEquals(AppRoute.Home, d.navigation.currentRoot)
        }
    } }

    private enum class Kind { MANAGER, CALLS, MESSAGES, ROOT, THREAD }
    private class Driver(val scheduler: TestCoroutineScheduler, kind: Kind, count: Int = 80,
        factory: OverscrollFactory? = null, floating: Boolean = true, durationScale: Float = 1f) : AutoCloseable {
        var query by mutableStateOf("")
        var navigation by mutableStateOf(AppNavigationState.initial())
        val haptics = mutableListOf<HapticFeedbackType>()
        private val back = object : NavigationEventDispatcherOwner { override val navigationEventDispatcher = NavigationEventDispatcher() }
        private val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        private val window = object : WindowInfo {
            override val isWindowFocused = true
            override val containerSize = IntSize(390, 520)
            override val containerDpSize = DpSize(390.dp, 520.dp)
        }
        private val feedback = object : HapticFeedback { override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType } }
        private val content: LazyListScope.() -> Unit = { items(count) { index -> Text("条目 $index", Modifier.fillMaxWidth().height(60.dp)) } }
        val environment = MotionTestEnvironment(scheduler, width = 390, height = 520, durationScale = durationScale) {
            CompositionLocalProvider(LocalWindowInfo provides window, LocalLifecycleOwner provides lifecycle, LocalNavigationEventDispatcherOwner provides back,
                LocalHapticFeedback provides feedback) {
                SoftVoWiFiTheme(false) {
                    CompositionLocalProvider(LocalOverscrollFactory provides if (kind == Kind.THREAD) LocalOverscrollFactory.current else factory) {
                        when (kind) {
                            Kind.MANAGER -> AppPageScaffold("管理列表", content = content)
                            Kind.CALLS -> PhoneHistoryScaffold(false, query, {}, {}, {}, false, {}, content = content)
                            Kind.MESSAGES -> MessagePageScaffold({}, false, false, query, {}, {}, {}, content = content)
                            Kind.THREAD -> {
                                val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
                                val conversation = StoredMessageConversation(owner, "12025550123")
                                MessageThreadScreen(conversation.handle, MessageUiState.Ready(
                                    List(count) { MessageRecord.Incoming("boundary-$it", "+12025550123", "合成消息 $it", 1790848800000L + it * 1000L, isRead = true) },
                                    MessageOrigin.ENGINE, composerEnabled = false, ownerId = owner), { _, _ -> error("甩动不能发送") })
                            }
                            Kind.ROOT -> AppShell(navigation, { navigation = it }, appearanceSettings = AppearanceSettings.Default.copy(floatingNavigation = floating), blurSupported = false) {
                                AppPageScaffold("管理列表", content = content)
                            }
                        }
                    }
                }
            }
        }
        fun frames(count: Int) { repeat(count) { environment.advanceFrameBy() } }
        fun nodes(): List<SemanticsNode> {
            fun visit(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::visit)
            return environment.scene.semanticsOwners.flatMap { visit(it.rootSemanticsNode) }
        }
        private fun list() = nodes().first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) && it.config.contains(SemanticsActions.ScrollBy) }
        fun axis() = list().config[SemanticsProperties.VerticalScrollAxisRange]
        fun scrollBy(amount: Float) { assertTrue(list().config[SemanticsActions.ScrollBy].action!!.invoke(0f, amount)) }
        fun scrollToIndex(index: Int) { assertTrue(list().config[SemanticsActions.ScrollToIndex].action!!.invoke(index)) }
        fun headerPixel(): Int {
            val y = list().boundsInRoot.top.toInt() - 1
            assertTrue(y > 0)
            return environment.scene.render(scheduler.currentTime * 1_000_000L).use { image -> Bitmap.makeFromImage(image).use { it.getColor(8, y) } }
        }
        override fun close() { environment.close(); back.navigationEventDispatcher.dispose() }
    }
}
