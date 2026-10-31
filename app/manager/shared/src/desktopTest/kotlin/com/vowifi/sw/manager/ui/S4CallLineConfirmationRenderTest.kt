@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import com.vowifi.sw.manager.data.CallAggregateFixture
import com.vowifi.sw.manager.data.OWNER_A
import com.vowifi.sw.manager.data.OWNER_B
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.ui.testing.MotionTestEnvironment
import com.vowifi.sw.manager.ui.testing.onMotionTestThread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import top.yukonga.miuix.kmp.basic.Scaffold
import kotlin.test.*

/** 挂起适配只门住真实 selectLine；确认必须取生产 aggregate 的 public lines 投影。 */
class S4CallLineConfirmationRenderTest {
    @Test fun realPickerWaitsForPublishedOwnerAndRetainsItsOriginalSheetUntilConfirmationDwellEnds() = onMotionTestThread { runTest(UnconfinedTestDispatcher()) {
        for (scale in listOf(1f, 0f)) {
            val backend = CallAggregateFixture(backgroundScope.coroutineContext)
            val requests = mutableListOf<SimOwnerId>()
            var selectionPermit = CompletableDeferred<Unit>()
            var sourceConfirmedAt: Long? = null
            var pixelsPerDp = 0f
            val environment = MotionTestEnvironment(testScheduler, 390, 844, scale) {
                SoftVoWiFiTheme(false) { Scaffold(Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    SideEffect { pixelsPerDp = density.density }
                    val lines by backend.source.lines.collectAsState()
                    CallLineEntry(lines, "未确认线路", requests::add, onConfirm = { owner ->
                        requests += owner
                        withTimeout(5_000) { selectionPermit.await() }
                        backend.source.selectLine(owner)
                        val accepted = backend.source.lines.value.selectedOwner == owner
                        if (accepted) sourceConfirmedAt = testScheduler.currentTime
                        accepted
                    })
                } }
            }
            try {
                s4Frames(environment, 80)
                assertEquals(OWNER_A, backend.source.lines.value.selectedOwner, "前提：生产源已确认 A")
                val target = backend.source.lines.value.choices.single { it.owner == OWNER_B }.label
                s4Tap(environment, s4Action(environment, "出向线路").boundsInRoot.center)
                s4Frames(environment, 80)
                fun row(): SemanticsNode {
                    // 两个真实源标签均可为“SIM”；按原picker的running顺序映射owner，不任选同名first。
                    val choices = backend.source.lines.value.choices.filter { it.running }
                    val rows = s4Nodes(environment, false).filter {
                        it.config.contains(SemanticsActions.OnClick) && it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton
                    }.sortedBy { it.boundsInRoot.top }
                    assertEquals(choices.size, rows.size, "命中前提：真实运行线路与面板单选行须一一对应")
                    assertEquals(rows.size, rows.map { it.boundsInRoot.top }.distinct().size, "命中前提：实际单选行垂直顺序须唯一")
                    val index = choices.indexOfFirst { it.owner == OWNER_B }
                    assertTrue(index >= 0, "命中前提：目标B必须仍是源发布的运行线路")
                    return rows[index].also { assertTrue(s4Matches(it, choices[index].label), "命中前提：目标行标签须匹配当前源投影") }
                }
                fun sheet() = s3TextBounds(s4Nodes(environment), "选择拨出线路")
                fun headerNode() = s4Nodes(environment).single {
                    it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "选择拨出线路" } == true
                }
                fun cancelDisabled() = s4Nodes(environment, false).single {
                    it.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                        it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("取消") == true &&
                        it.config.contains(SemanticsActions.OnClick)
                }.config.contains(SemanticsProperties.Disabled)
                val point = row().boundsInRoot.center
                assertEquals(false, row().config.getOrNull(SemanticsProperties.Selected))
                val top = sheet().top
                s4Tap(environment, point); s4Tap(environment, point)
                assertEquals(listOf(OWNER_B), requests, "C2：点击立即请求且 pending 重复点击不能重复提交")
                repeat(12) {
                    environment.advanceFrameBy()
                    assertEquals(OWNER_A, backend.source.lines.value.selectedOwner)
                    assertEquals(false, row().config.getOrNull(SemanticsProperties.Selected), "不能把请求意图画成确认")
                    assertEquals(top, sheet().top, .5f, "未确认前原 sheet 不得先退场")
                }
                // 真生产拒绝分支：请求等待期间 B 退役，selectLine 不得选中已停止线路。
                val inventory = backend.instances
                backend.instances = inventory.filterNot { it.iccid == CallAggregateFixture.ICCID_B }
                backend.source.refresh()
                selectionPermit.complete(Unit)
                s4Frames(environment, 80)
                assertEquals(OWNER_A, backend.source.lines.value.selectedOwner)
                assertTrue(s4HasText(environment, "选择拨出线路"), "真实拒绝后不能先卸载再重开")
                val rejectionText = "无法切换线路，请重试。"
                assertTrue(s4HasText(environment, rejectionText), "前提：真实拒绝已在原面板内说明")
                backend.instances = inventory; backend.source.refresh()
                s4Frames(environment, 80); requests.clear(); selectionPermit = CompletableDeferred()
                s4Tap(environment, row().boundsInRoot.center)
                assertEquals(listOf(OWNER_B), requests)
                assertEquals(OWNER_A, backend.source.lines.value.selectedOwner)
                // 新请求会清掉拒绝footnote，真实底部sheet缩短35px；不能沿用点击前的旧行/ROI。
                val pendingAt = testScheduler.currentTime
                var previousGeometry: Pair<Rect, Float>? = null
                var stableFrames = 0
                repeat(8) { pump ->
                    if (stableFrames < 2) {
                        environment.advanceFrameBy(0)
                        assertFalse(selectionPermit.isCompleted, "采样前提：真实选择仍挂住")
                        assertEquals(OWNER_A, backend.source.lines.value.selectedOwner)
                        assertEquals(false, row().config.getOrNull(SemanticsProperties.Selected))
                        assertTrue(row().config.contains(SemanticsProperties.Disabled), "采样前提：原picker已进入真实pending")
                        val geometry = row().boundsInRoot to sheet().top
                        val rejectionGone = !s4HasText(environment, rejectionText)
                        stableFrames = if (rejectionGone && geometry == previousGeometry) stableFrames + 1 else 0
                        previousGeometry = geometry
                        println("S4_C2_PENDING|scale=$scale|pump=$pump|elapsed=${testScheduler.currentTime - pendingAt}|rejectionGone=$rejectionGone|row=${geometry.first}|headerTop=${geometry.second}|stableFrames=$stableFrames")
                    }
                }
                assertEquals(pendingAt, testScheduler.currentTime, "pending几何校准只能泵同刻帧")
                assertTrue(stableFrames >= 2 && !s4HasText(environment, rejectionText), "采样前提：拒绝文案已退出且原行/header几何连续稳定")
                assertEquals(1f, pixelsPerDp, "采样前提：当前1×场景中20dp勾对应20px")
                val bounds = row().boundsInRoot
                val iconSize = 20f * pixelsPerDp
                val trailingInset = 16f * pixelsPerDp
                val naturalCheck = Rect(bounds.right - trailingInset - iconSize, bounds.center.y - iconSize / 2,
                    bounds.right - trailingInset, bounds.center.y + iconSize / 2)
                val check = Rect(naturalCheck.left - 4f, naturalCheck.top - 4f, naturalCheck.right + 4f, naturalCheck.bottom + 4f)
                assertTrue(check.contains(naturalCheck.topLeft) && check.contains(naturalCheck.bottomRight), "20dp自然勾选目标必须完整落在ROI")
                assertTrue(check.top > bounds.top && check.bottom < bounds.bottom, "ROI须位于原行内，不能采到其底部分隔线")
                val absent = s3SmallPatch(environment, testScheduler, check)
                val confirmedSheetTop = sheet().top
                val confirmedHeader = headerNode()
                val confirmedHeaderOrigin = confirmedHeader.positionInRoot
                // CommunicationSheet 的取消 enabled 只投影 visible && active（此场景active恒true），不受行pending影响。
                assertFalse(cancelDisabled(), "pending时原sheet仍打开；取消Disabled可区分之后真实的close/closing")
                assertEquals(listOf(OWNER_B), requests); assertFalse(selectionPermit.isCompleted)
                assertEquals(OWNER_A, backend.source.lines.value.selectedOwner)
                selectionPermit.complete(Unit)
                environment.advanceFrameBy(0)
                assertEquals(OWNER_B, backend.source.lines.value.selectedOwner)
                if (scale == 0f) {
                    val zeroConfirmedAt = assertNotNull(sourceConfirmedAt)
                    var zeroTimePumps = 0
                    repeat(8) {
                        if (s4HasText(environment, "选择拨出线路") && !cancelDisabled()) {
                            environment.advanceFrameBy(0); zeroTimePumps++
                        }
                    }
                    val hostPresent = s4HasText(environment, "选择拨出线路")
                    println("S4_C2_SHEET_ZERO|elapsed=${testScheduler.currentTime - zeroConfirmedAt}|hostPresent=$hostPresent|closing=${!hostPresent || cancelDisabled()}")
                    assertEquals(zeroConfirmedAt, testScheduler.currentTime, "零动画须在真实确认同刻关闭，不等待150ms")
                    assertTrue(!hostPresent || cancelDisabled(), "零动画真实close不得留驻")
                    // 旧零动画守护用备注真实close后100次render，不保证两个render已消费完整宿主退场。
                    // closing后仍须消费原T8完成/卸载；与上面的close消费共用至多8次同刻真实帧，不补业务时间。
                    repeat(8 - zeroTimePumps) {
                        if (s4HasText(environment, "选择拨出线路")) {
                            assertTrue(cancelDisabled(), "零动画完成消费前原动作必须已Disabled")
                            environment.advanceFrameBy(0); zeroTimePumps++
                            val retained = s4HasText(environment, "选择拨出线路")
                            assertEquals(zeroConfirmedAt, testScheduler.currentTime, "宿主完成消费不得推进业务停留时钟")
                            assertTrue(!retained || cancelDisabled(), "每个完成帧原动作须保持Disabled或宿主已消失")
                            println("S4_C2_SHEET_ZERO_FINISH|pump=$zeroTimePumps|elapsed=${testScheduler.currentTime - zeroConfirmedAt}|hostPresent=$retained|headerAttached=${confirmedHeader.layoutInfo.isAttached}")
                        }
                    }
                    assertEquals(zeroConfirmedAt, testScheduler.currentTime)
                    assertFalse(s4HasText(environment, "选择拨出线路"), "零动画直接到同一终态，不留宿主")
                    assertFalse(confirmedHeader.layoutInfo.isAttached, "零动画须真正移除原header布局，不仅隐藏服务树")
                } else {
                    val projectedAt = testScheduler.currentTime
                    // source 已为 B 不等于这一帧的 collectAsState/子组合已消费；只在同一时刻泵真实帧。
                    var pumps = 0
                    repeat(8) {
                        if (row().config.getOrNull(SemanticsProperties.Selected) != true) {
                            environment.advanceFrameBy(0); pumps++
                        }
                    }
                    println("S4_C2_PROJECTION|scale=$scale|zeroTimePumps=$pumps|elapsed=${testScheduler.currentTime - projectedAt}")
                    assertEquals(projectedAt, testScheduler.currentTime, "确认前提校准不能推进业务时钟")
                    assertEquals(true, row().config.getOrNull(SemanticsProperties.Selected))
                    assertEquals(bounds, row().boundsInRoot, "真实确认不得让已校准行/勾选目标移出ROI")
                    val confirmationAt = testScheduler.currentTime
                    assertEquals(assertNotNull(sourceConfirmedAt), confirmationAt, "150ms测量须绑定真实源确认，不后移到动画首帧")
                    // selected 的组合消费与子叶 transition 的首次 withFrame 不同；仅泵同刻真实帧启动绘制时钟。
                    // 若 enter 真丢失，这些 t0 样本会已等于 settled；不通过推进时间或改150ms掩盖。
                    val startFrames = List(2) {
                        environment.advanceFrameBy(0)
                        s3SmallPatch(environment, testScheduler, check)
                    }
                    assertEquals(0L, testScheduler.currentTime - confirmationAt, "绘制时钟启动不得产生实际留驻时间")
                    assertEquals(true, row().config.getOrNull(SemanticsProperties.Selected))
                    environment.advanceFrameBy(16)
                    val first = s3SmallPatch(environment, testScheduler, check)
                    val firstRowBounds = row().boundsInRoot
                    environment.advanceFrameBy(32)
                    val middle = s3SmallPatch(environment, testScheduler, check)
                    val middleRowBounds = row().boundsInRoot
                    environment.advanceFrameBy(96)
                    assertEquals(144L, testScheduler.currentTime - confirmationAt)
                    assertEquals(confirmedSheetTop, sheet().top, .5f, "确认后 144ms 仍属同一原 sheet")
                    assertFalse(cancelDisabled(), "144ms时visible仍true，不能只是关闭后首绘帧未动")
                    assertEquals(confirmedHeaderOrigin.y, confirmedHeader.positionInRoot.y, .5f)
                    val settled = s3SmallPatch(environment, testScheduler, check)
                    assertEquals(bounds, firstRowBounds); assertEquals(bounds, middleRowBounds)
                    assertEquals(bounds, row().boundsInRoot, "16/48/144ms均须保持同一真实行几何")
                    // 装饰 Icon 无动作语义；边界来自这28×28内真实绘出的勾选墨迹，不添加生产tag/probe。
                    fun iconInkBounds(patch: S3SmallPatch): Rect? {
                        val ink = patch.pixels.indices.filter { s3RgbDistance(absent.pixels[it], patch.pixels[it]) > 8 }
                        if (ink.isEmpty()) return null
                        return Rect(check.left + ink.minOf { it % patch.width }, check.top + ink.minOf { it / patch.width },
                            check.left + ink.maxOf { it % patch.width } + 1, check.top + ink.maxOf { it / patch.width } + 1)
                    }
                    startFrames.forEachIndexed { index, patch ->
                        println("S4_C2_POP_START|scale=$scale|frame=$index|elapsed=0|toAbsent=${s4Difference(patch, absent)}|toSettled=${s4Difference(patch, settled)}|iconInkBounds=${iconInkBounds(patch)}")
                    }
                    println("S4_C2_POP|scale=$scale|region=$check|unselectedRow=$bounds|firstElapsed=16|firstRow=$firstRowBounds|firstToAbsent=${s4Difference(first, absent)}|firstToSettled=${s4Difference(first, settled)}|firstIconInkBounds=${iconInkBounds(first)}|middleElapsed=48|middleRow=$middleRowBounds|middleToAbsent=${s4Difference(middle, absent)}|middleToSettled=${s4Difference(middle, settled)}|middleIconInkBounds=${iconInkBounds(middle)}|settledElapsed=144|settledRow=${row().boundsInRoot}|settledToAbsent=${s4Difference(settled, absent)}|settledIconInkBounds=${iconInkBounds(settled)}")
                    assertTrue(s4Difference(absent, settled) >= 4, "采样前提：28×28 内必须实际绘出勾选字形")
                    val settledInk = assertNotNull(iconInkBounds(settled))
                    assertTrue(settledInk.width > 1f && settledInk.height > 1f &&
                        settledInk.left >= naturalCheck.left && settledInk.top >= naturalCheck.top &&
                        settledInk.right <= naturalCheck.right && settledInk.bottom <= naturalCheck.bottom,
                        "实际双轴勾选墨迹须落在20dp自然目标内，不能以一条分隔线充当勾选")
                    assertTrue(s4Difference(first, absent) >= 2 && s4Difference(first, settled) >= 2,
                        "C2：首个16ms可绘帧必须是 pop 中间态，不以空图冒充缩放")
                    assertTrue(s4Difference(middle, absent) >= 2 && s4Difference(middle, settled) >= 2)
                    val initialInk = s4InkSpan(absent, first); val finalInk = s4InkSpan(absent, settled)
                    assertTrue(initialInk.first < finalInk.first && initialInk.second < finalInk.second, "实际勾选墨迹须放大")
                    environment.advanceFrameBy(5)
                    assertEquals(149L, testScheduler.currentTime - confirmationAt)
                    assertFalse(cancelDisabled(), "149ms仍不得调用close，完整150ms留驻不能被帧采样缩短")
                    assertEquals(confirmedHeaderOrigin.y, confirmedHeader.positionInRoot.y, .5f)
                    println("S4_C2_SHEET|elapsed=149|phase=open|headerOrigin=${confirmedHeader.positionInRoot}|headerBounds=${sheet()}")
                    environment.advanceFrameBy(1)
                    var closePumps = 0
                    repeat(8) {
                        if (!cancelDisabled()) { environment.advanceFrameBy(0); closePumps++ }
                    }
                    assertEquals(150L, testScheduler.currentTime - confirmationAt, "closing采样只泵同刻帧，不延长业务停留")
                    println("S4_C2_SHEET|elapsed=150|closing=${cancelDisabled()}|zeroTimePumps=$closePumps|sourceOwner=${backend.source.lines.value.selectedOwner}|headerOrigin=${confirmedHeader.positionInRoot}|headerBounds=${sheet()}")
                    assertTrue(cancelDisabled(), "150ms到点原CommunicationSheetState.close必须已生效，不能用晚退场掩盖迟调用")
                    // close与T8的LaunchedEffect/首个playTime=0帧分开验证；只给原宿主至多三个16ms绘制帧。
                    environment.advanceFrameBy(0)
                    assertEquals(150L, testScheduler.currentTime - confirmationAt)
                    val closingFrames = List(3) {
                        environment.advanceFrameBy()
                        val elapsed = testScheduler.currentTime - confirmationAt
                        assertTrue(cancelDisabled(), "原sheet开始closing后不能重新启用动作")
                        assertTrue(confirmedHeader.layoutInfo.isAttached, "T8滑出早期必须保留原header布局，不能直接卸载")
                        val origin = confirmedHeader.positionInRoot
                        val clipped = confirmedHeader.boundsInRoot
                        val serviceCount = s4Nodes(environment).count { node ->
                            node.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "选择拨出线路" } == true
                        }
                        println("S4_C2_SHEET|elapsed=$elapsed|phase=closing|headerOrigin=$origin|headerBounds=$clipped|serviceHeaderCount=$serviceCount")
                        elapsed to origin.y
                    }
                    val firstMove = assertNotNull(closingFrames.firstOrNull { it.second > confirmedHeaderOrigin.y + .5f },
                        "150ms已close后，原T8必须在至多48ms内出现自然向下位移，不能仅检查最终消失")
                    assertTrue(firstMove.first <= 198L, "T8首个非零绘制位移上界为close后的三个真实帧")
                    s4Frames(environment, 80)
                    assertFalse(s4HasText(environment, "选择拨出线路"))
                }
                assertEquals(OWNER_B, backend.source.lines.value.selectedOwner)
                s4Action(environment, "出向线路")
                assertTrue(s4HasText(environment, target), "入口终值取真实确认 owner")
            } finally { environment.close(); backend.source.close() }
        }
    } }
}

// 三份候选仅共用原场景的只读语义/小区域采样，不创建替代控件或动画模型。
internal fun s4Nodes(environment: MotionTestEnvironment, unmerged: Boolean = true): List<SemanticsNode> {
    fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
    return environment.scene.semanticsOwners.flatMap { visit(if (unmerged) it.unmergedRootSemanticsNode else it.rootSemanticsNode) }
}
internal fun s4Matches(node: SemanticsNode, label: String): Boolean =
    if (label == "返回通话") node.config.getOrNull(SemanticsProperties.TestTag) == "ongoing-call-action"
    else
    node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true ||
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true || node.children.any { s4Matches(it, label) }
internal fun s4Actions(environment: MotionTestEnvironment, label: String) = s4Nodes(environment, false).filter {
    // Official OverlayDialog's scrim has its own Dismiss action. It is not a descendant button.
    it.config.contains(SemanticsActions.OnClick) && it.config[SemanticsActions.OnClick].label != "Dismiss" && s4Matches(it, label)
}
internal fun s4Action(environment: MotionTestEnvironment, label: String) = s4Actions(environment, label).single {
    !it.config.contains(SemanticsProperties.Disabled)
}
internal fun s4HasText(environment: MotionTestEnvironment, text: String) = s4Nodes(environment).any {
    it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true
}
internal fun s4Frames(environment: MotionTestEnvironment, count: Int) { repeat(count) { environment.advanceFrameBy() } }
internal fun s4Tap(environment: MotionTestEnvironment, point: Offset) {
    environment.press(point); environment.advanceFrameBy(8); environment.release(point); environment.advanceFrameBy(0)
}
internal fun s4Difference(a: S3SmallPatch, b: S3SmallPatch): Int {
    assertEquals(a.width to a.height, b.width to b.height, "采样前提：必须在同一小矩形比较")
    return a.pixels.indices.count { s3RgbDistance(a.pixels[it], b.pixels[it]) > 8 }
}
internal fun s4InkSpan(background: S3SmallPatch, ink: S3SmallPatch): Pair<Int, Int> {
    val indices = ink.pixels.indices.filter { s3RgbDistance(background.pixels[it], ink.pixels[it]) > 8 }
    assertTrue(indices.size >= 2, "采样前提：真实绘制墨迹不能为空")
    return (indices.maxOf { it % ink.width } - indices.minOf { it % ink.width } + 1) to
        (indices.maxOf { it / ink.width } - indices.minOf { it / ink.width } + 1)
}
