package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.vowifi.sw.manager.SoftVoWiFiApp
import com.vowifi.sw.manager.SoftVoWiFiSources
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.AppearanceSettings
import com.vowifi.sw.manager.ui.InMemoryAppearanceSettingsSource
import com.vowifi.sw.manager.ui.S3SmallPatch
import com.vowifi.sw.manager.ui.s3RgbDistance
import com.vowifi.sw.manager.ui.navigation.AppRoute
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import javax.swing.SwingUtilities
import java.lang.reflect.InvocationTargetException

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WarmRootRouteTest {
    @Test fun warmSnapIsConsumedAndLaterTabSelectionsHaveIntermediateFrames() = onEdt {
        Driver().use { driver ->
            driver.route(AppRoute.Calls)
            val forward = driver.tabFrames(2)
            assertTrue(forward.any { it > 1.01f && it < 1.99f }, "暖跳转后点击底栏必须有1到2之间的过程：$forward")
            driver.assertSelected(2)
            val backward = driver.tabFrames(0)
            assertTrue(backward.any { it > .01f && it < 1.99f }, "再次切换不能沿用旧SNAP：$backward")
            driver.assertSelected(0)
            driver.route(AppRoute.Messages)
            val repeated = driver.tabFrames(1)
            assertTrue(repeated.any { it > 1.01f && it < 1.99f }, "新暖跳转也应只消费一次：$repeated")
            driver.assertSelected(1)
        }
    }

    @Test fun warmCallsAfterSettledHomeShowsCallsContent() = onEdt {
        Driver().use { driver ->
            driver.assertVisible("SoftVoWiFi")
            assertFalse(driver.isVisible("搜索通话记录"))
            driver.route(AppRoute.Calls)
            assertEquals(listOf<AppRoute>(AppRoute.Calls), driver.consumed)
            driver.assertVisible("搜索通话记录")
            assertFalse(driver.isVisible("拨号盘"), "无注册引擎仍应隐藏创建入口")
            driver.assertSelected(1)
        }
    }

    @Test fun queuedWarmCallsAndRepeatedRequestAfterManualHome() = onEdt {
        Driver().use { driver ->
            driver.assertVisible("SoftVoWiFi")
            assertFalse(driver.isVisible("搜索通话记录"))
            driver.route(AppRoute.Calls)
            driver.assertVisible("搜索通话记录")
            driver.assertSelected(1)
            driver.tab(0)
            driver.assertVisible("SoftVoWiFi")
            driver.route(AppRoute.Calls)
            driver.assertVisible("搜索通话记录")
            driver.assertSelected(1)
            assertEquals(listOf<AppRoute>(AppRoute.Calls, AppRoute.Calls), driver.consumed)
        }
    }

    @Test fun latestWarmRouteWinsAndSubpageRequestDoesNotReplayOnResume() = onEdt {
        Driver().use { driver ->
            driver.enqueue(AppRoute.Calls)
            driver.enqueue(AppRoute.Messages)
            driver.frame()
            driver.assertVisible("搜索短信")
            assertFalse(driver.isVisible("新建短信"), "无注册引擎仍应隐藏创建入口")
            driver.assertSelected(2)
            driver.route(AppRoute.Dialpad)
            driver.assertVisible("1")
            driver.route(AppRoute.Calls)
            driver.assertVisible("搜索通话记录")
            driver.back()
            driver.assertVisible("SoftVoWiFi")
            driver.route(AppRoute.Calls)
            driver.tab(0)
            val count = driver.consumed.size
            driver.resume()
            driver.assertVisible("SoftVoWiFi")
            driver.assertSelected(0)
            assertEquals(count, driver.consumed.size)
            // 从真实外部incomingRoutes入口暖开设置子页；不直接调用导航policy或改栈。
            val beforeConfiguration = driver.consumed.size
            driver.route(AppRoute.SettingsConfiguration)
            driver.assertVisible("SIM 与网络配置")
            assertEquals(listOf<AppRoute>(AppRoute.SettingsConfiguration), driver.consumed.drop(beforeConfiguration))
            driver.back()
            driver.assertSelected(3)
            val afterConfiguration = driver.consumed.size
            driver.resume()
            driver.assertSelected(3)
            assertEquals(afterConfiguration, driver.consumed.size, "外部设置子页返回所属Settings后不能在resume重放")
        }
    }

    @Test fun tabsAndUserSwipeStillSynchronizeAfterWarmSnapAndBack() = onEdt {
        Driver().use { driver ->
            driver.route(AppRoute.Calls)
            driver.swipeLeft()
            driver.assertVisible("搜索短信")
            driver.assertSelected(2)
            driver.back()
            driver.assertVisible("SoftVoWiFi")
            driver.assertSelected(0)
            driver.tab(3)
            driver.assertSelected(3)
            driver.tab(1)
            driver.assertVisible("搜索通话记录")
            driver.back()
            driver.assertVisible("SoftVoWiFi")
        }
    }

    @Test fun realAboutSceneCoversAndRevealsStationaryRootChromeAtItsOwnBoundary() = onEdt {
        for (floating in listOf(false, true)) Driver(floating = floating, rootOverlay = true).use { d ->
            d.route(AppRoute.Settings)
            d.assertSelected(3)
            // 先用同一真实子页的稳态返回按钮校准局部 x；不推算库 easing/translation/持续时间。
            d.route(AppRoute.SettingsAbout)
            fun backNode() = d.nodes(true).singleOrNull {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("返回") == true &&
                    it.layoutInfo.isAttached && it.layoutInfo.isPlaced && !it.layoutInfo.isDeactivated
            }
            fun pendingBackScene(phase: String): SemanticsNode {
                val at = d.overlayClock()
                repeat(6) {
                    // enqueue只投递Channel；一轮render后LaunchedEffect仍可留下同刻待组合的scene。
                    d.overlayFrame(0)
                    val node = backNode()
                    if (node != null) {
                        assertEquals(at, d.overlayClock(), "首scene准入只消费同刻任务，不能把边界测量推晚")
                        return node
                    }
                }
                val candidates = d.nodes(true).filter {
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("返回") == true
                }
                println("S5_ROOT_SCENE|phase=$phase|floating=$floating|clock=${d.overlayClock()}|routes=${d.consumed.size}|backCount=${candidates.size}|" +
                    "nodes=${candidates.map { "${it.id}:placed=${it.layoutInfo.isPlaced},attached=${it.layoutInfo.isAttached},position=${if (it.layoutInfo.isAttached) it.positionInRoot else null},bounds=${it.boundsInRoot}" }}")
                return assertNotNull(backNode(), "同刻六轮后真实返回scene仍未唯一组合/放置，不能当作offscreen边界")
            }
            val backRestX = assertNotNull(backNode(), "前提：生产 About 返回按钮实际存在").positionInRoot.x
            d.back()
            d.assertSelected(3)
            fun naturalRegion(text: String): Rect {
                val b = d.nodes(true).filter { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true &&
                        node.boundsInRoot.width > 0 && node.boundsInRoot.center.x in 0f..420f
                }.maxBy { it.boundsInRoot.top }.boundsInRoot
                // 返回条只取头几个自然字形；所有 patch 至多 72×64，不取整行/正文。
                return Rect(floor(b.left), floor(b.top), minOf(ceil(b.right), floor(b.left) + 72), ceil(b.bottom))
            }
            val regions = listOf("首页", "设置", " · 返回通话").map(::naturalRegion)
            val root = regions.map(d::overlayPatch)
            var placedBack: SemanticsNode? = null
            fun sample(entering: Boolean): Pair<Float, List<S3SmallPatch>> {
                val back = if (entering) pendingBackScene("push") else backNode() ?: placedBack?.takeIf {
                    it.layoutInfo.isAttached && it.layoutInfo.isPlaced && !it.layoutInfo.isDeactivated
                }
                if (back != null) placedBack = back
                val edge = if (back == null) {
                    assertFalse(entering, "进入时真实子页须已组合，不以缺节点充当动画")
                    assertTrue(placedBack != null && placedBack?.layoutInfo?.isAttached == false,
                        "只有已观测的真实scene实际卸载才算pop完成，裁掉语义/暂缺node不能伪造边界")
                    d.assertSelected(3)
                    420f // 完成 pop 后真实子页已卸载；不是动画中人为注入的边界。
                } else back.positionInRoot.x - backRestX // 未裁剪的真实已placed节点位置，非boundsInRoot原点。
                return edge to regions.map(d::overlayPatch)
            }
            d.enqueue(AppRoute.SettingsAbout); d.overlayFrame(0)
            placedBack = pendingBackScene("push-first")
            println("S5_ROOT_SCENE|phase=push-first|floating=$floating|clock=${d.overlayClock()}|routes=${d.consumed.size}|" +
                "position=${placedBack?.positionInRoot}|bounds=${placedBack?.boundsInRoot}")
            val push = List(73) { index -> if (index != 0) d.overlayFrame(); sample(true) }
            d.assertVisible("关于")
            val covered = regions.map(d::overlayPatch)
            // 新的真实信息卡会覆盖底部区域；移动时须比较子页自身坐标，而非把终态同屏x当成同一内容。
            // 只采原三个文字高度带，每块84×至多64，保持真实像素与同一时刻，不造空白子页或整图。
            val referenceClock = d.overlayClock()
            val pageBands = regions.map { region ->
                val strips = List(5) { part -> d.overlayPatch(Rect(part * 84f, region.top, (part + 1) * 84f, region.bottom)) }
                S3SmallPatch(420, strips.first().height, IntArray(420 * strips.first().height) { i ->
                    val x = i % 420
                    strips[x / 84].pixels[(i / 420) * 84 + x % 84]
                })
            }
            assertEquals(referenceClock, d.overlayClock(), "空间参照采集不能推进动画/业务时钟")
            d.overlayBackStart(); d.overlayFrame(0)
            val pop = List(73) { index -> if (index != 0) d.overlayFrame(); sample(false) }
            d.assertSelected(3)

            fun verify(samples: List<Pair<Float, List<S3SmallPatch>>>, phase: String) {
                var uncovered = 0; var occluded = 0
                regions.indices.forEach { r ->
                    val base = root[r]; val end = covered[r]
                    val background = base.pixels.toList().groupingBy { it }.eachCount().maxBy { it.value }.key
                    val ink = base.pixels.indices.filter { i ->
                        s3RgbDistance(base.pixels[i], background) >= 48 && s3RgbDistance(base.pixels[i], end.pixels[i]) > 8
                    }
                    assertTrue(ink.size >= 4, "采样前提：自然公共字形与真实子页空白可区分，mode=$floating region=${regions[r]}")
                    samples.forEachIndexed { frame, (edge, patches) ->
                        ink.forEach { i ->
                            val x = regions[r].left + i % base.width + .5f
                            // 两像素边界带仅排除抗锯齿；两侧分别必须是原根字形/真实子页像素。
                            if (x < edge - 2) {
                                uncovered++
                                assertTrue(s3RgbDistance(patches[r].pixels[i], base.pixels[i]) <= 8,
                                    "S5：边界未到时根字形须原位保留，不能先卸载/淡出/平移；$phase frame=$frame edge=$edge region=${regions[r]}")
                             } else if (x > edge + 2) {
                                 occluded++
                                 assertEquals(edge.toInt().toFloat(), edge, "前提：真实IntOffset边界必须为整像素，不猜测亚像素插值")
                                 val localX = floor(x - edge).toInt()
                                 assertTrue(localX in 0 until 420)
                                 val expected = pageBands[r].pixels[(i / base.width) * 420 + localX]
                                 assertTrue(s3RgbDistance(patches[r].pixels[i], expected) <= 8,
                                     "S5：真正子页边界经过后须遮住根字形，不能把底栏/返回条画在子页之上；$phase frame=$frame edge=$edge")
                            }
                        }
                    }
                }
                assertTrue(uncovered > 0 && occluded > 0, "前提：必须实际观测边界两侧，不能只比终态")
                assertTrue(samples.any { (edge, _) -> regions.any { edge > it.left + 2 && edge < it.right - 2 } },
                    "前提：真实子页边界须穿过至少一个自然小字形区域，不能拿整块消失冒充渐次遮挡")
                println("S5_ABOUT_BOUNDARY|floating=$floating|phase=$phase|uncovered=$uncovered|occluded=$occluded|samples=${samples.size}")
            }
            assertTrue(push.first().first > regions.first().right + 2, "前提：首帧左侧根字形尚未被真实子页遮盖")
            assertTrue(push.last().first < 1f && pop.last().first >= 419f, "前提：实际进入/回退均已到终态")
            verify(push, "push"); verify(pop, "pop")
            regions.indices.forEach { r ->
                val restored = d.overlayPatch(regions[r])
                assertEquals(0, root[r].pixels.indices.count { s3RgbDistance(root[r].pixels[it], restored.pixels[it]) > 8 },
                    "回退后公共字形在同一根位置自然恢复，不重新平移入场")
            }
            assertEquals(0, d.ongoingOpens)
        }
    }

    @Test fun coveredRootCannotReceiveBlankAreaPointersAndRealThreadAcceptsItsFirstInputTap() = onEdt {
        Driver(floating = true, rootOverlay = true).use { d ->
            d.route(AppRoute.Settings)
            val home = d.nodes().single { it.config.getOrNull(SemanticsProperties.Selected) != null &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "首页" } == true }
            val stale = d.nodes().single { it.config.getOrNull(SemanticsProperties.Selected) == true &&
                it.config.contains(SemanticsActions.OnClick) }.config[SemanticsActions.OnClick].action!!
            val ongoing = d.nodes().single { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.TestTag) == "ongoing-call-action" }
            assertTrue(ongoing.config[SemanticsProperties.ContentDescription].single().contains("通话中"))
            val staleCall = ongoing.config[SemanticsActions.OnClick].action!!
            val oldTargets = listOf(home.boundsInRoot, ongoing.boundsInRoot)
            fun assertRootActionsWithdrawn() {
                assertTrue(d.nodes().none { n -> n.config.contains(SemanticsActions.OnClick) &&
                    ((n.config.getOrNull(SemanticsProperties.TestTag) == "ongoing-call-action" &&
                        (n.id == ongoing.id || n.boundsInRoot.contains(ongoing.boundsInRoot.center))) ||
                        n.config.getOrNull(SemanticsProperties.Text)?.any { it.text in listOf("首页", "通话", "短信", "设置") } == true) },
                    "覆盖首帧起撤原根条/底栏动作；新的子页顶部条不属于被遮挡根层")
            }
            d.enqueue(AppRoute.SettingsAbout); d.overlayFrame(0)
            assertRootActionsWithdrawn()
            repeat(73) { d.overlayFrame() }
            d.assertVisible("关于")
            val subpageCall = d.nodes().single { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.TestTag) == "ongoing-call-action" }
            assertTrue(subpageCall.id != ongoing.id && subpageCall.boundsInRoot.bottom < 200f,
                "新合同：About自己的顶栏下通话条可用，原根条仍遮挡/撤权")
            staleCall(); d.overlayFrame(0)
            assertEquals(0, d.ongoingOpens, "新的子页条不能复活旧根条的回调权限")
            oldTargets.forEach { target ->
                // 新增只读文字可以画到旧中心，但不能借子控件吞事件；只在原根目标内部找真正9×9空白。
                val xs = listOf(target.center.x, target.left + 6, target.right - 6,
                    (target.left + target.center.x) / 2, (target.right + target.center.x) / 2)
                val ys = listOf(target.center.y, target.top + 6, target.bottom - 6,
                    (target.top + target.center.y) / 2, (target.bottom + target.center.y) / 2)
                val p = assertNotNull(xs.flatMap { x -> ys.map { y -> Offset(x, y) } }.firstOrNull { candidate ->
                    val region = Rect(floor(candidate.x) - 4, floor(candidate.y) - 4, floor(candidate.x) + 5, floor(candidate.y) + 5)
                    region.left >= target.left && region.right <= target.right && region.top >= target.top && region.bottom <= target.bottom &&
                        d.nodes().none { n -> n.boundsInRoot.contains(candidate) &&
                            (n.config.contains(SemanticsActions.OnClick) || n.config.contains(SemanticsActions.SetText)) } &&
                        d.overlayPatch(region).pixels.distinct().size == 1
                }, "前提：原根命中区域内必须有真实无动作/无字形的9×9空白，不能换到目标外避开穿透")
                assertTrue(target.contains(p))
                assertTrue(d.nodes().none { n -> n.boundsInRoot.contains(p) &&
                    (n.config.contains(SemanticsActions.OnClick) || n.config.contains(SemanticsActions.SetText)) },
                    "前提：原根命中点当前确为真实子页空白，而非借子控件消费防穿透")
                val blank = d.overlayPatch(Rect(floor(p.x) - 4, floor(p.y) - 4, floor(p.x) + 5, floor(p.y) + 5))
                assertEquals(1, blank.pixels.distinct().size, "前提：只取 9×9 子页空白，不存整图")
                println("S5_ABOUT_BLANK|point=$p|oldTarget=$target|colors=${blank.pixels.distinct().size}")
                d.overlayTap(p); repeat(12) { d.overlayFrame() }
                d.assertVisible("关于"); assertRootActionsWithdrawn()
                assertEquals(0, d.ongoingOpens, "真实空白 Press/Up 不能穿透给旧返回条")
            }
            stale(); repeat(12) { d.overlayFrame() }
            d.assertVisible("关于"); assertRootActionsWithdrawn()
            d.back(); d.assertSelected(3)
            d.route(AppRoute.Messages)
            val row = d.nodes().single { it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "+155501" } == true }
            d.overlayTap(row.boundsInRoot.center)
            assertRootActionsWithdrawn()
            repeat(73) { d.overlayFrame() }
            val field = d.nodes().single { it.config.contains(SemanticsActions.SetText) &&
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("短信内容") == true }
            assertFalse(field.config.contains(SemanticsProperties.Disabled), "前提：真实会话 composer 可编辑")
            assertFalse(field.config.getOrNull(SemanticsProperties.Focused) == true, "前提：不是已经聚焦后补一次点击")
            val b = field.boundsInRoot
            assertTrue(b.left >= 0 && b.right <= 420 && b.top >= 0 && b.bottom <= 900 && b.width >= 48 && b.height >= 48,
                "前提：首点命中真实已测量输入框，不猜坐标/用第二次点击补救")
            val textBefore = field.config.getOrNull(SemanticsProperties.EditableText)?.text
            d.overlayTap(b.center)
            val focused = d.nodes().single { it.config.contains(SemanticsActions.SetText) &&
                it.config.getOrNull(SemanticsProperties.Focused) == true }
            assertTrue(focused.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("短信内容") == true,
                "首个真实 Press/Up 必须让生产会话输入聚焦，不能用语义 RequestFocus 或第二次点击")
            assertEquals(textBefore, focused.config.getOrNull(SemanticsProperties.EditableText)?.text)
            assertRootActionsWithdrawn(); assertEquals(0, d.ongoingOpens)
            d.back(); d.assertSelected(2)
        }
    }

    // 静止环境和人工scale0仅验证状态保留；在途resize由下一方法独立验证。
    @Test fun sameSceneWindowFontThemeAndZeroMotionRetainStoredThreadDraftOwnerAndAnchor() = onEdt {
        runTest {
            val stored = FakeMessageRepository()
            val owner = requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43)))
            repeat(50) { stored.insertIncomingIfAbsent(owner, "s7-$it", "wire-$it", "+155501",
                "合成历史 $it", 1_700_000_000_000L + it * 60_000L, 1_700_000_000_000L) }
            val wire = FakeEngineMessageTransport(); val calls = mutableListOf<String>()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val backend = EngineMessageSource(EngineMessageRuntime(
                { listOf(EngineMessagePersistenceFixtures.running(EngineMessagePersistenceFixtures.ICCID_A)) },
                { wire }, { 1_700_010_000_000L }, { listOf(EngineMessagePersistenceFixtures.ICCID_A) }),
                backgroundScope.coroutineContext + dispatcher, stored, { owner }, { error("本用例禁止新短信入库/发送") })
            val messages = object : MessageSource by backend {
                override fun refresh() { calls += "refresh"; backend.refresh() }
                override fun selectConversation(handle: String?) { calls += "select:$handle"; backend.selectConversation(handle) }
                override fun sendFromDisplayedOwner(owner: SimOwnerId?, number: String, text: String) { error("禁止发送") }
            }
            var scale by mutableFloatStateOf(1f)
            val motion = object : MotionDurationScale { override val scaleFactor get() = scale }
            try { Driver(messageOverride = messages, scheduler = testScheduler, motionScale = motion).use { d ->
                fun settle() { repeat(40) { d.overlayFrame(16) } }
                fun has(n: SemanticsNode, value: String) = n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
                fun editor() = d.nodes().single { it.config.contains(SemanticsActions.SetText) &&
                    it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("短信内容") == true }
                val messageLabels = (0 until 50).map { "合成历史 $it" }.toSet()
                fun messageLabel(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text
                    ?.let { com.vowifi.sw.manager.ui.stripMessageDisplayJoiners(it) }
                fun messageLeaves() = d.nodes(true).filter { node ->
                    node.children.isEmpty() && messageLabel(node) in messageLabels
                }
                d.route(AppRoute.Messages); settle()
                val row = d.nodes().single { it.config.contains(SemanticsActions.OnClick) && has(it, "+155501") }
                d.overlayTap(row.boundsInRoot.center); settle() // 真正App列表行打开真实存储会话，不直接填UiState。
                val ready = backend.state.value as MessageUiState.Ready
                val identity = assertNotNull(ready.window).identity
                assertEquals(owner, ready.ownerId); assertEquals(50, ready.records.size)
                val draft = "S7 synthetic retained draft"
                assertTrue(editor().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(draft))); settle()
                val list = d.nodes().single { it.config.contains(SemanticsActions.ScrollToIndex) }
                assertTrue(list.config[SemanticsActions.ScrollToIndex].action!!.invoke(12)); settle()
                val viewport = list.boundsInRoot
                val leaves = messageLeaves()
                val visibleLeaves = leaves.filter { it.boundsInRoot.height > 0 &&
                    it.boundsInRoot.top >= viewport.top && it.boundsInRoot.bottom <= viewport.bottom }
                assertTrue(visibleLeaves.isNotEmpty(), "S7_ENV_ANCHOR：真实正文叶须完整可见；leafCount=${leaves.size} visibleCount=${visibleLeaves.size}")
                val anchorNode = visibleLeaves.minBy { it.boundsInRoot.top }
                val anchor = assertNotNull(messageLabel(anchorNode),
                    "S7_ENV_ANCHOR：原unmerged正文叶须只有一个精确合成消息文本")
                assertEquals(1, leaves.count { messageLabel(it) == anchor }, "S7_ENV_ANCHOR：原消息正文叶须唯一，不读取合并父文本")
                val requests = calls.toList(); val business = wire.requests.toList(); val statusReads = wire.statusRequests
                val selectedOwner = backend.historyChoices.value.selectedOwner; val routeEvents = d.consumed.toList()
                assertEquals(owner, selectedOwner, "前提：真实源已确认当前历史owner，不以null保持冒充选线保留")
                val clock = d.overlayClock()
                d.environment(800, 600, font = 2f, dark = true); scale = 0f
                repeat(6) { d.overlayFrame(0) } // 原scene同刻消费，不推进业务时间、不重建App。
                assertEquals(clock, d.overlayClock())
                assertEquals(draft, editor().config[SemanticsProperties.EditableText].text)
                assertEquals(owner, (backend.state.value as MessageUiState.Ready).ownerId)
                assertEquals(identity, (backend.state.value as MessageUiState.Ready).window?.identity)
                assertEquals(selectedOwner, backend.historyChoices.value.selectedOwner)
                val retainedLeaves = messageLeaves().filter { messageLabel(it) == anchor }
                assertEquals(1, retainedLeaves.size, "S7_ENV_ANCHOR：环境变化后原精确正文叶须唯一")
                assertTrue(retainedLeaves.single().boundsInRoot.let { it.height > 0 && it.top >= 0 && it.bottom <= 600 }, "原真实消息锚仍可见")
                assertEquals(requests, calls); assertEquals(business, wire.requests); assertEquals(statusReads, wire.statusRequests)
                assertEquals(routeEvents, d.consumed); assertTrue(wire.requests.none { it.method == "sendSms" })
            } } finally { backend.close(); testScheduler.runCurrent(); stored.close() }
        }
    }

    @Test fun sameCompositionResizeFinishesAnActuallyMovingRootPagerAtScaleOneWithoutAnotherNavigation() = onEdt {
        runTest {
            val motion = object : MotionDurationScale { override val scaleFactor = 1f }
            for (floating in listOf(false, true)) Driver(floating = floating, scheduler = testScheduler, motionScale = motion).use { d ->
                fun position(width: Float) = d.nodes().single {
                    it.config.contains(SemanticsProperties.HorizontalScrollAxisRange)
                }.config[SemanticsProperties.HorizontalScrollAxisRange].value() / width
                fun navigationChoices() = listOf("首页", "通话", "短信", "设置").associateWith { label ->
                    val choices = d.nodes().filter { node -> node.config.contains(SemanticsProperties.Selected) &&
                        node.config.contains(SemanticsActions.OnClick) &&
                        (node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
                            node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true) }
                    assertEquals(1, choices.size, "S7_ENV_NAV：公开导航身份须唯一；label=$label floating=$floating")
                    choices.single()
                }
                fun assertNavigationSelection(label: String, width: Float, height: Float) {
                    val choices = navigationChoices() // 四个身份均须保留，但不要求四个中心都在旧420px视口内。
                    assertEquals(listOf(label), choices.filterValues { it.config[SemanticsProperties.Selected] }.keys.toList())
                    val bounds = choices.getValue(label).boundsInRoot
                    assertTrue(bounds.width > 0 && bounds.height > 0 && bounds.left >= 0 && bounds.right <= width &&
                        bounds.top >= 0 && bounds.bottom <= height, "S7_ENV_NAV：实际选定目标须在当前窗口可见；label=$label floating=$floating")
                }
                fun appearanceBounds() = d.nodes(true).filter { node ->
                    node.children.isEmpty() && node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == "外观设置" &&
                        node.boundsInRoot.let { it.width > 0 && it.height > 0 && it.left >= 0 && it.right <= 800 &&
                            it.top >= 0 && it.bottom <= 600 }
                }.singleOrNull()?.boundsInRoot
                assertNavigationSelection("首页", 420f, 900f); assertEquals(0f, position(420f), .001f)
                val selected = navigationChoices().getValue("设置")
                assertFalse(selected.config[SemanticsProperties.Selected])
                assertTrue(selected.boundsInRoot.let { it.width > 0 && it.height > 0 && it.left >= 0 && it.right <= 420 && it.top >= 0 && it.bottom <= 900 },
                    "S7_ENV_NAV：真实设置导航命中面须在原窗口可见")
                d.overlayTap(selected.boundsInRoot.center)
                var moving: Float? = null
                repeat(8) {
                    if (moving == null) {
                        d.overlayFrame(16)
                        val value = position(420f)
                        if (value > .01f && value < 2.99f) moving = value
                    }
                }
                assertNotNull(moving, "前提：真实底栏指针必须已启动未到终点的生产Pager动画，不能静止resize充当E4")
                assertEquals(moving, position(420f)); assertNavigationSelection("设置", 420f, 900f)
                val beforeClock = d.overlayClock(); val routes = d.consumed.toList()
                d.environment(800, 600, font = 1f, dark = false) // 同composition真实constraints＋WindowInfo；缩放始终1。
                repeat(6) { d.overlayFrame(0) }
                assertEquals(beforeClock, d.overlayClock(), "E4终值必须同刻，不以完整播放/人工scale0掩盖")
                assertEquals(1f, motion.scaleFactor)
                val resizePosition = position(800f)
                assertEquals(3f, resizePosition, .001f, "E4：真实在途Pager窗口变化后，同刻公开位置须到原Settings目标3；缺行不能先遮住此结果")
                val resizeLabel = assertNotNull(appearanceBounds(), "E4：已到目标后须有完整可见的真实外观设置正文叶")
                repeat(60) { d.overlayFrame(16) } // 仅在同刻证据封存后取原动画完整终点作独立对照。
                assertNavigationSelection("设置", 800f, 600f)
                assertNotNull(appearanceBounds(), "S7_ENV_NAV：当前新窗口须有真实可见的外观设置正文叶")
                val terminal = position(800f); val terminalLabel = assertNotNull(appearanceBounds())
                assertEquals(3f, terminal, .001f, "前提：同新窗口的完整播放目标确为Settings")
                assertEquals(terminal, resizePosition, .001f, "E4：窗口变化须将原在途动画直接落目标，而非继续spring")
                assertEquals(terminalLabel, resizeLabel, "同新窗口自然设置行边界须即时等于最终布局，不丢标签/重排后再追赶")
                assertEquals(routes, d.consumed, "resize不能重新投递任何外部路由或重播源入口")
            }
        }
    }

    private fun onEdt(block: () -> Unit) {
        try { SwingUtilities.invokeAndWait(block) }
        catch (failure: InvocationTargetException) { throw failure.targetException }
    }

    private class Driver(floating: Boolean? = null, private val rootOverlay: Boolean = false,
        messageOverride: MessageSource? = null, private val scheduler: TestCoroutineScheduler = TestCoroutineScheduler(),
        motionScale: MotionDurationScale? = null) : AutoCloseable {
        init { assertOwner() }
        private fun assertOwner() = assertTrue(SwingUtilities.isEventDispatchThread(), "WarmRoot scene access must run on EDT")
        private val backInput = object : NavigationEventInput() {
            fun back() = dispatchOnBackCompleted()
        }
        private val backOwner = object : NavigationEventDispatcherOwner {
            override val navigationEventDispatcher = NavigationEventDispatcher().apply { addInput(backInput) }
        }
        private val routes = Channel<AppRoute>(Channel.CONFLATED)
        val consumed = mutableListOf<AppRoute>()
        private val incoming = routes.receiveAsFlow().onEach { consumed.add(it) }
        private val lifecycle = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        }
        private val sources = SoftVoWiFiSources(
            FixtureObserverLogSource("empty"), FixtureEngineControlSource("unavailable"),
            FixtureSimControlSource("empty"), FixtureConfigurationSource("empty"),
            FixtureEnvironmentDiagnosticsSource("empty"), FixtureOperationalLogSource("empty"),
            messageOverride ?: if (rootOverlay) FixtureMessageSource("ready") else UnavailableMessageSource(),
            FixtureCallSource(if (rootOverlay) "incall" else "unavailable"),
        )
        var ongoingOpens = 0
            private set
        private var environmentSize by mutableStateOf(IntSize(420, 900))
        private var environmentFont by mutableFloatStateOf(1f)
        private var environmentDark by mutableStateOf(false)
        private val overlayWindow = object : WindowInfo {
            override val isWindowFocused get() = true
            override val containerSize get() = environmentSize
            override val containerDpSize get() = DpSize(environmentSize.width.dp, environmentSize.height.dp)
        }
        private var time = 0L
        private val scene = ImageComposeScene(width = 420, height = 900,
            coroutineContext = StandardTestDispatcher(scheduler) + (motionScale ?: EmptyCoroutineContext)) {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycle,
                LocalNavigationEventDispatcherOwner provides backOwner,
                LocalWindowInfo provides if (rootOverlay || messageOverride != null || motionScale != null) overlayWindow else LocalWindowInfo.current,
                LocalDensity provides if (messageOverride != null || motionScale != null) Density(1f, environmentFont) else LocalDensity.current) {
                SoftVoWiFiApp(sources, {}, false, "test", incomingRoutes = incoming, forceDark = environmentDark,
                    appearanceSource = InMemoryAppearanceSettingsSource(if (floating == null) AppearanceSettings.Default
                        else AppearanceSettings.Default.copy(floatingNavigation = floating)),
                    onOpenCall = if (rootOverlay) ({ ongoingOpens++ }) else null)
            }
        }
        init { frame() }
        fun environment(width: Int, height: Int, font: Float, dark: Boolean) {
            assertOwner(); environmentSize = IntSize(width, height); environmentFont = font; environmentDark = dark
            scene.constraints = Constraints.fixed(width, height)
        }
        fun frame(count: Int = 40) { assertOwner(); repeat(count) {
            scheduler.runCurrent()
            time += 16_666_667L
            scene.render(time).close()
            scheduler.runCurrent()
        } }
        fun overlayFrame(milliseconds: Long = 8) {
            assertOwner(); scheduler.advanceTimeBy(milliseconds); scheduler.runCurrent()
            time += milliseconds * 1_000_000L
            scene.render(time).close(); scheduler.runCurrent()
        }
        fun overlayBackStart() { assertOwner(); backInput.back() }
        fun overlayClock(): Pair<Long, Long> { assertOwner(); return time to scheduler.currentTime }
        fun overlayTap(point: Offset) {
            assertOwner()
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = time / 1_000_000L, type = PointerType.Touch)
            scheduler.runCurrent(); overlayFrame(8)
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = time / 1_000_000L, type = PointerType.Touch)
            scheduler.runCurrent(); overlayFrame(0)
        }
        fun overlayPatch(region: Rect): S3SmallPatch = scene.render(time).use { image ->
            Bitmap.makeFromImage(image).use { bitmap ->
                val x = floor(region.left).toInt(); val y = floor(region.top).toInt()
                val w = ceil(region.right).toInt() - x; val h = ceil(region.bottom).toInt() - y
                assertTrue(w in 1..96 && h in 1..64 && x >= 0 && y >= 0 && x + w <= bitmap.width && y + h <= bitmap.height,
                    "采样前提：仅复制完整可见的小文字/图标区域")
                S3SmallPatch(w, h, IntArray(w * h) { i -> bitmap.getColor(x + i % w, y + i / w) })
            }
        }
        fun enqueue(route: AppRoute) { assertTrue(routes.trySend(route).isSuccess) }
        fun route(route: AppRoute) { enqueue(route); frame() }
        fun back() { backInput.back(); frame() }
        fun swipeLeft() {
            scene.sendPointerEvent(PointerEventType.Press, Offset(360f, 450f), type = PointerType.Touch)
            repeat(10) { step ->
                scheduler.runCurrent()
                time += 16_666_667L
                scene.sendPointerEvent(PointerEventType.Move, Offset(360f - (step + 1) * 30f, 450f), type = PointerType.Touch)
                scene.render(time).close()
            }
            scene.sendPointerEvent(PointerEventType.Release, Offset(60f, 450f), type = PointerType.Touch)
            frame()
        }
        fun resume() {
            lifecycle.lifecycle.currentState = Lifecycle.State.CREATED
            frame()
            lifecycle.lifecycle.currentState = Lifecycle.State.RESUMED
            frame()
        }
        private fun tabs() = visibleNodes().filter { it.config.getOrNull(SemanticsProperties.Selected) != null }
        fun assertSelected(index: Int) {
            assertEquals(4, tabs().size)
            assertEquals(listOf(index), tabs().mapIndexedNotNull { position, node ->
                position.takeIf { node.config[SemanticsProperties.Selected] }
            })
        }
        fun tab(index: Int) {
            assertTrue(requireNotNull(tabs()[index].config[SemanticsActions.OnClick].action).invoke())
            frame()
        }
        fun tabFrames(index: Int): List<Float> {
            assertTrue(requireNotNull(tabs()[index].config[SemanticsActions.OnClick].action).invoke())
            return List(40) {
                frame(1)
                nodes().first { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }
                    .config[SemanticsProperties.HorizontalScrollAxisRange].value() / 420f
            }
        }
        private fun visibleNodes() = nodes().filter { node ->
            val bounds = node.boundsInRoot
            bounds.width > 0 && bounds.height > 0 && bounds.center.x in 0f..420f && bounds.center.y in 0f..900f
        }
        fun isVisible(text: String) = visibleNodes().any {
            it.config.getOrNull(SemanticsProperties.Text)?.any { item -> item.text == text } == true ||
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(text) == true
        }
        fun assertVisible(text: String) = assertTrue(isVisible(text),
            "Expected visible $text, actual=" + visibleNodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text })
        fun nodes(unmerged: Boolean = false): List<SemanticsNode> { assertOwner(); return scene.semanticsOwners.flatMap { owner ->
            fun descend(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descend)
            descend(if (unmerged) owner.unmergedRootSemanticsNode else owner.rootSemanticsNode)
        } }
        override fun close() { assertOwner(); scene.close(); scheduler.runCurrent(); routes.close(); backOwner.navigationEventDispatcher.dispose() }
    }
}
