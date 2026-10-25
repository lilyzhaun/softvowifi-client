@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.vowifi.sw.manager.desktop

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.ICCID_A
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.MO_ID
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.NOW_MS
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.SYNTH_NUMBER
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.running
import com.vowifi.sw.manager.data.EngineMessagePersistenceFixtures.sourceOf
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.*
import com.vowifi.sw.manager.ui.testing.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.abs
import kotlin.test.*

/** 输入为真实 EngineMessageSource 的 typed state/event；不创建 S3 第二结果层、不改 C13。 */
class S4SmsMutationRenderTest {
    @Test fun nonLatestTimestampIncomingUsesItsNewStableIdForIntermediateEntrance() = onMotionTestThread { runTest {
        s4Live { source, transport ->
            var bodyInk = 0; var timestampInk = 0; var surface = 0
            MotionTestEnvironment(testScheduler, 420, 900) { s4SmsHost { PhoneStyle {
                bodyInk = LocalSoftVoWiFiColors.current.textPrimary.toArgb()
                timestampInk = communicationColors().metadata.toArgb(); surface = communicationColors().field.toArgb()
                MessageThreadScreen(s4LiveIdentity().handle, source.state.collectAsState().value, source::sendSms)
            } } }.use { e ->
                e.s4Frames(24)
                val latestId = assertIs<MessageUiState.Ready>(source.state.value).records.last().id
                transport.emitReceived("late-mt", SYNTH_NUMBER, "88", NOW_MS / 1000 - 1)
                testScheduler.runCurrent()
                val ready = assertIs<MessageUiState.Ready>(source.state.value)
                val added = ready.records.single { it.engineSmsId == "late-mt" }
                assertIs<MessageRecord.Incoming>(added); assertEquals("msg-1", added.id)
                assertEquals(latestId, ready.records.last().id, "前提：新信并非 lastOrNull/latestId")
                fun incomingGlyphBounds(terminal: Boolean): Rect {
                    // 只交付当前时刻尚未完成的布局，不推进80ms/终态时钟；几何与随后像素来自同一绘制姿态。
                    val time = testScheduler.currentTime
                    e.advanceFrameBy(0)
                    e.scene.render(time * 1_000_000L).close()
                    assertEquals(time, testScheduler.currentTime)
                    val actual = assertIs<MessageUiState.Ready>(source.state.value)
                    val projected = assertNotNull(projectMessageThread(s4LiveIdentity().handle, actual.ownerId, actual, ::messageDateSpec))
                    val message = projected.items.filterIsInstance<MessageThreadItem.IncomingBubble>().single { it.recordId == added.id }
                    assertEquals("88", message.body)
                    assertEquals(1, actual.records.count { it.preview == message.body }, "采样前提：自然正文唯一对应新stable id")
                    val viewport = s4List(e).boundsInRoot
                    val bubble = s4Nodes(e).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "消息操作" &&
                        s4HasLabel(it, message.body) && it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }
                    fun contains(outer: Rect, inner: Rect) = inner.left >= outer.left && inner.top >= outer.top &&
                        inner.right <= outer.right && inner.bottom <= outer.bottom
                    assertTrue(contains(viewport, bubble.boundsInRoot), "采样前提：所属真实气泡完整可见")
                    // 只为此合成短气泡直采59×53左右的已知区域；不扫描整屏，也不按墨迹反选ROI。
                    val paintedBubble = s3SmallPatch(e, testScheduler, bubble.boundsInRoot)
                    fun describe(name: String, pixels: S3SmallPatch) {
                        val colors = pixels.pixels.toList().groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
                        println("S4_SMS_MT_PAINT|terminal=$terminal|part=$name|id=${added.id}|t=$time|" +
                            "bubble=${bubble.boundsInRoot}|patch=${pixels.width}x${pixels.height}|" +
                            "contrast=${pixels.pixels.count { s3RgbDistance(it, surface) >= 48 }}|" +
                            "minBody=${pixels.pixels.minOf { s3RgbDistance(it, bodyInk) }}|minTimestamp=${pixels.pixels.minOf { s3RgbDistance(it, timestampInk) }}|" +
                            "rgb=${colors.take(4).joinToString { "${it.key.toUInt().toString(16)}:${it.value}" }}")
                    }
                    describe("whole-known-bubble", paintedBubble)
                    fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
                    val owner = e.scene.semanticsOwners.flatMap { descendants(it.unmergedRootSemanticsNode) }.single { it.id == bubble.id }
                    val candidates = descendants(owner).filter { node ->
                        node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == message.body &&
                            node.config.contains(SemanticsActions.GetTextLayoutResult)
                    }
                    println("S4_SMS_MT_OWNER|id=${added.id}|bubble=${owner.id}|textCandidates=${candidates.map { it.id }}|t=$time")
                    val text = assertNotNull(candidates.singleOrNull(), "采样前提：只能唯一选择当前气泡自己的TextLayout叶")
                    assertTrue(text.layoutInfo.isAttached && text.layoutInfo.isPlaced && !text.layoutInfo.isDeactivated,
                        "采样前提：只读此气泡内实际已放置的Text，排除隐藏/复用语义节点")
                    val timestamp = messageTimestampLabel(message.timestamp.epochMs)
                    val timeNode = descendants(owner).single { node ->
                        node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == timestamp &&
                            node.config.contains(SemanticsActions.GetTextLayoutResult)
                    }
                    fun textLayout(node: SemanticsNode, expected: String): TextLayoutResult {
                        val results = mutableListOf<TextLayoutResult>()
                        assertTrue(requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(results))
                        return results.single().also {
                            assertEquals(expected, it.layoutInput.text.text)
                            assertEquals(1, it.lineCount); assertFalse(it.isLineEllipsized(0))
                        }
                    }
                    val layout = textLayout(text, message.body)
                    val timeLayout = textLayout(timeNode, timestamp)
                    val density = text.layoutInfo.density.density
                    val surfaceCoordinates = owner.layoutInfo.coordinates
                    val surfaceRoot = generateSequence(surfaceCoordinates) { it.parentCoordinates }.last()
                    val origin = surfaceRoot.localPositionOf(surfaceCoordinates, Offset.Zero)
                    val natural = surfaceRoot.localBoundingBoxOf(surfaceCoordinates, clipBounds = false)
                    // Compose ui-desktop 1.11.1 / Apache-2.0：LayoutNode.coordinates是innerCoordinator；
                    // NodeCoordinator.localToRoot有RectManager快路径，root.localPositionOf逐层经过实际layer。
                    // https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-desktop/1.11.1/ui-desktop-1.11.1-sources.jar
                    val chain = generateSequence(text.layoutInfo) { it.parentInfo }.take(8).map { info ->
                        val coordinate = info.coordinates
                        val root = generateSequence(coordinate) { it.parentCoordinates }.last()
                        "${info.semanticsId}:${coordinate::class.simpleName}:size=${coordinate.size},cached=${coordinate.localToRoot(Offset.Zero)}," +
                            "walked=${root.localPositionOf(coordinate, Offset.Zero)},unclipped=${root.localBoundingBoxOf(coordinate, clipBounds = false)},placed=${info.isPlaced}"
                    }.toList()
                    val timeCoordinates = timeNode.layoutInfo.coordinates
                    println("S4_SMS_MT_LAYOUT_PAINT|terminal=$terminal|id=${added.id}|naturalBubble=$natural|" +
                        "rawOwner=${owner.positionInRoot}|rawBody=${text.positionInRoot}|rawClipped=${text.boundsInRoot}|" +
                        "rawTimestamp=${timeNode.positionInRoot}|walkedTimestamp=${surfaceRoot.localPositionOf(timeCoordinates, Offset.Zero)}|" +
                        "timestampClipped=${timeNode.boundsInRoot}|direction=${layout.layoutInput.layoutDirection}|" +
                        "rowSpan=${natural.height + messageSubpageSpacing().threadItemGapDp * density}|chain=$chain")
                    // 先证明整气泡未经clip/scale，再按已核生产Column padding(12,8)、End和3dp间距映射。
                    // 不是把clip后的merged矩形当Text原点；段落尺寸/字符框均来自所属真实TextLayoutResult。
                    assertEquals(natural, owner.boundsInRoot, "采样前提：合成气泡必须完整、未经几何缩放地显示")
                    assertTrue(contains(viewport, natural))
                    val horizontal = 12f * density; val vertical = 8f * density; val gap = 3f * density
                    assertEquals(maxOf(layout.size.width, timeLayout.size.width) + 2f * horizontal, natural.width, .5f)
                    assertEquals(layout.size.height + gap + timeLayout.size.height + 2f * vertical, natural.height, .5f)
                    fun originFor(result: TextLayoutResult, top: Float): Offset {
                        val x = if (owner.layoutInfo.layoutDirection == androidx.compose.ui.unit.LayoutDirection.Ltr)
                            natural.width - horizontal - result.size.width else horizontal
                        return origin + Offset(x, top)
                    }
                    val bodyOrigin = originFor(layout, vertical)
                    val timeOrigin = originFor(timeLayout, vertical + layout.size.height + gap)
                    fun glyphBounds(result: TextLayoutResult, at: Offset): Rect {
                        val boxes = result.layoutInput.text.text.indices.map(result::getBoundingBox)
                        assertTrue(boxes.all { it.width > 0f && it.height > 0f })
                        val bounds = Rect(boxes.minOf { it.left }, boxes.minOf { it.top },
                            boxes.maxOf { it.right }, boxes.maxOf { it.bottom }).inflate(1f).translate(at)
                        assertTrue(contains(natural, bounds) && contains(viewport, bounds), "完整字形必须在此气泡及viewport内")
                        assertTrue(ceil(bounds.right) - floor(bounds.left) in 1f..96f &&
                            ceil(bounds.bottom) - floor(bounds.top) in 1f..64f)
                        return bounds
                    }
                    val glyphs = glyphBounds(layout, bodyOrigin)
                    val timeGlyphs = glyphBounds(timeLayout, timeOrigin)
                    println("S4_SMS_MT_REGIONS|terminal=$terminal|id=${added.id}|bodyOrigin=$bodyOrigin|body=$glyphs|" +
                        "timestampOrigin=$timeOrigin|timestamp=$timeGlyphs")
                    val bodyPixels = s3SmallPatch(e, testScheduler, glyphs)
                    val timePixels = s3SmallPatch(e, testScheduler, timeGlyphs)
                    describe("body-from-layout-and-padding", bodyPixels); describe("timestamp-from-layout-and-padding", timePixels)
                    if (terminal) {
                        fun isInk(pixel: Int, foreground: Int): Boolean {
                            val coverage = s3Coverage(pixel, surface, foreground)
                            val residual = listOf(16, 8, 0).maxOf { shift ->
                                val bg = (surface ushr shift and 255).toFloat()
                                abs((pixel ushr shift and 255) - (bg + coverage * ((foreground ushr shift and 255) - bg)))
                            }
                            return s3RgbDistance(pixel, surface) >= 48 && residual <= 2f
                        }
                        assertTrue(bodyPixels.pixels.count { isInk(it, bodyInk) } >= 3,
                            "E2真实绘制RED：新stable id正文在确定padding布局内必须有真实字符颜色，不能只显示空气泡")
                        assertTrue(timePixels.pixels.count { isInk(it, timestampInk) } >= 3,
                            "E2真实绘制RED：同气泡的真实时间戳也必须在独立metadata布局内绘制")
                    }
                    return glyphs
                }
                e.advanceFrameBy(0); e.s4Frames(5)
                val middleGlyphs = incomingGlyphBounds(terminal = false)
                val region = s4Visible(e, "88", inList = true)!!.boundsInRoot
                val middle = s3SmallPatch(e, testScheduler, middleGlyphs)
                e.s4Frames(24)
                val endGlyphs = incomingGlyphBounds(terminal = true)
                assertEquals(region.top, s4Visible(e, "88", inList = true)!!.boundsInRoot.top, .5f)
                val end = s3SmallPatch(e, testScheduler, endGlyphs)
                assertEquals(middleGlyphs.left, endGlyphs.left, .5f, "真实新MT文字不得随兄弟补位漂移")
                assertEquals(middleGlyphs.top, endGlyphs.top, .5f)
                assertEquals(middleGlyphs.width, endGlyphs.width, .5f)
                assertEquals(middleGlyphs.height, endGlyphs.height, .5f)
                val ratio = s4InkRatio(middle, end)
                assertTrue(ratio > .03 && ratio < .97, "E2：真实新 MT 的80ms须有中间墨迹，不能因不是 latestId 瞬显；ratio=$ratio")
                assertEquals(added.id, assertIs<MessageUiState.Ready>(source.state.value).records.single { it.preview == "88" }.id)
            }
        }
    } }

    @Test fun actualStatusReportCrossfadesInPlaceWithoutReenteringExistingBubble() = onMotionTestThread { runTest {
        s4Live { source, transport ->
            MotionTestEnvironment(testScheduler, 420, 900) { s4LiveThread(source) }.use { e ->
                e.s4Frames(24)
                val body = s4Visible(e, "11", inList = true)!!.boundsInRoot
                val region = s4Visible(e, "已受理", inList = true)!!.boundsInRoot
                val bodyGlyphs = s4SmsGlyphBounds(e, "11")
                val statusGlyphs = s4SmsGlyphBounds(e, "已受理")
                val start = s3SmallPatch(e, testScheduler, statusGlyphs)
                val ids = assertIs<MessageUiState.Ready>(source.state.value).records.map { it.id }
                transport.emitSent(MO_ID, SmsEngineStatus.PENDING); testScheduler.runCurrent()
                val ready = assertIs<MessageUiState.Ready>(source.state.value)
                assertEquals(ids, ready.records.map { it.id }); assertTrue(transport.requests.isEmpty())
                val outgoingRecord = ready.records.filterIsInstance<MessageRecord.Outgoing>().single()
                assertEquals("mo-old", outgoingRecord.id); assertEquals(SmsEngineStatus.PENDING, outgoingRecord.engineStatus)
                e.advanceFrameBy(0); e.s4Frames(2)
                val middle = s3SmallPatch(e, testScheduler, statusGlyphs)
                val bodyMiddle = s3SmallPatch(e, testScheduler, bodyGlyphs)
                e.s4Frames(24)
                val endBounds = s4Visible(e, "处理中", inList = true)!!.boundsInRoot
                assertEquals(region.left, endBounds.left, .5f); assertEquals(region.top, endBounds.top, .5f)
                assertEquals(region.width, endBounds.width, .5f, "前提：等长三字状态须有相同自然矩形")
                val endGlyphs = s4SmsGlyphBounds(e, "处理中")
                assertEquals(statusGlyphs.left, endGlyphs.left, .5f)
                assertEquals(statusGlyphs.top, endGlyphs.top, .5f)
                assertEquals(statusGlyphs.width, endGlyphs.width, .5f, "采样前提：真实三字STATUS字形范围须原位等宽")
                val end = s3SmallPatch(e, testScheduler, statusGlyphs)
                val background = end.pixels.toList().groupingBy { it }.eachCount().maxBy { it.value }.key
                val oldOnly = start.pixels.indices.filter { s3RgbDistance(start.pixels[it], background) >= 48 && s3RgbDistance(end.pixels[it], background) <= 2 }
                val newOnly = end.pixels.indices.filter { s3RgbDistance(end.pixels[it], background) >= 48 && s3RgbDistance(start.pixels[it], background) <= 2 }
                assertTrue(oldOnly.size >= 3 && newOnly.size >= 3, "字形对比前提：实际旧/新字形各至少三个独占核心点")
                val outgoing = oldOnly.map { s3RgbDistance(middle.pixels[it], background).toDouble() / s3RgbDistance(start.pixels[it], background) }.average()
                val incoming = newOnly.map { s3RgbDistance(middle.pixels[it], background).toDouble() / s3RgbDistance(end.pixels[it], background) }.average()
                assertTrue(outgoing > .03 && outgoing < .97 && incoming > .03 && incoming < .97,
                    "C12：32ms须同时绘制淡出/淡入墨迹；old=$outgoing new=$incoming")
                assertEquals(body.top, s4Visible(e, "11", inList = true)!!.boundsInRoot.top, .5f)
                assertTrue(s4InkRatio(bodyMiddle, s3SmallPatch(e, testScheduler, bodyGlyphs)) > .97, "状态报告不是新气泡，不得重演 E2")
            }
        }
    } }

    @Test fun realFailedReportPaintsErrorInkAndZeroScaleHasNoResidualOldStatus() = onMotionTestThread { runTest {
        s4Live { source, transport ->
            var error = 0; var surface = 0; var metadata = 0
            try {
            MotionTestEnvironment(testScheduler, 420, 900, durationScale = 0f) {
                s4SmsHost {
                    // Match the actual thread's PhoneStyle palette, not the outer default palette.
                    PhoneStyle {
                        error = LocalSoftVoWiFiColors.current.error.toArgb(); surface = communicationColors().outgoing.toArgb()
                        metadata = communicationColors().metadata.toArgb()
                        MessageThreadScreen(s4LiveIdentity().handle, source.state.collectAsState().value, source::sendSms)
                    }
                }
            }.use { e ->
                e.s4Frames(4)
                val before = assertIs<MessageUiState.Ready>(source.state.value).records.map { it.id }
                transport.emitSent(MO_ID, SmsEngineStatus.FAILED); testScheduler.runCurrent()
                e.advanceFrameBy(0); e.s4Frames(2)
                val ready = assertIs<MessageUiState.Ready>(source.state.value)
                assertEquals(before, ready.records.map { it.id })
                assertEquals(OutgoingMessageStatus.FAILED, ready.records.filterIsInstance<MessageRecord.Outgoing>().single().status)
                val projected = assertNotNull(projectMessageThread(s4LiveIdentity().handle, ready.ownerId, ready, ::messageDateSpec))
                    .items.filterIsInstance<MessageThreadItem.OutgoingBubble>().single()
                assertTrue(projected.statusFailed, "typed FAILED必须真实透传到气泡，不能根据自由文案猜错误色")
                val region = s4SmsGlyphBounds(e, "发送未完成")
                assertTrue(s3RgbDistance(error, surface) >= 48, "真实错误色/气泡底色必须可区分")
                fun colorTrace(phase: String, pixels: S3SmallPatch) {
                    val colors = pixels.pixels.toList().groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
                        .take(6).joinToString { "${it.key.toUInt().toString(16)}:${it.value}" }
                    val sent = ready.records.filterIsInstance<MessageRecord.Outgoing>().single()
                    println("S4_SMS_FAILED_RGB|phase=$phase|t=${testScheduler.currentTime}|status=${sent.status}|engine=${sent.engineStatus}|" +
                        "statusFailed=${projected.statusFailed}|error=${error.toUInt().toString(16)}|surface=${surface.toUInt().toString(16)}|" +
                        "metadata=${metadata.toUInt().toString(16)}|minError=${pixels.pixels.minOf { s3RgbDistance(it, error) }}|" +
                        "errorCore=${pixels.pixels.count { s3RgbDistance(it, error) <= 8 }}|rgb=$colors")
                }
                colorTrace("32ms-before-zero-consumption", s3SmallPatch(e, testScheduler, region))
                // 只消费同一32ms时刻的组合/动画任务；绝不把零动画色验收推到更晚的墙钟。
                repeat(3) { e.advanceFrameBy(0) }
                val patch = s3SmallPatch(e, testScheduler, region)
                colorTrace("32ms-after-zero-consumption", patch)
                // 实测12sp细字：纯error≤8仅1点，但ffda4e53/ffda5258共45点为正常AA混色。
                // 不扩大RGB阈值：用真实surface→error的覆盖率/逐通道残差，并排除metadata混色线。
                fun residual(pixel: Int, foreground: Int): Double {
                    val coverage = s3Coverage(pixel, surface, foreground).toDouble()
                    return listOf(16, 8, 0).maxOf { shift ->
                        val bg = (surface ushr shift and 255).toDouble()
                        abs((pixel ushr shift and 255) - (bg + coverage * ((foreground ushr shift and 255) - bg)))
                    }
                }
                val errorInk = patch.pixels.filter { s3RgbDistance(it, surface) >= 48 &&
                    residual(it, error) <= 2.0 && residual(it, metadata) > 2.0 }
                println("S4_SMS_FAILED_AA|t=${testScheduler.currentTime}|points=${errorInk.size}|" +
                    "coverage=${errorInk.minOfOrNull { s3Coverage(it, surface, error) }}..${errorInk.maxOfOrNull { s3Coverage(it, surface, error) }}|" +
                    "maxErrorResidual=${errorInk.maxOfOrNull { residual(it, error) }}|minMetadataResidual=${errorInk.minOfOrNull { residual(it, metadata) }}")
                assertTrue(errorInk.size >= 3,
                    "C12：至少三个高对比字形点必须准确符合真实error混色且不能符合metadata混色")
                assertNull(s4Visible(e, "已受理", inList = true))
                // 原颜色判断仍在32ms；另取同ROI的后继零动画帧，逐字节相同才可排除残留动画。
                e.advanceFrameBy(16)
                val terminal = s3SmallPatch(e, testScheduler, region)
                assertTrue(patch.pixels.contentEquals(terminal.pixels), "零动画32ms与后继48ms的同ROI必须逐字节一致")
            }
            } finally {
                // 真实source退出线程后读实际InboxSummary；仅输出typed枚举与投影结果，不合成UiState。
                source.selectConversation(null); testScheduler.runCurrent()
                val inbox = source.state.value as? MessageUiState.Inbox
                val summary = inbox?.page?.items?.singleOrNull()
                val row = inbox?.let { projectMessageConversations(it.ownerId, it).rows.singleOrNull() }
                println("S4_SMS_FAILED_INBOX|items=${inbox?.page?.items?.size}|status=${summary?.outgoingStatus}|" +
                    "engine=${summary?.engineStatus}|statusFailed=${row?.statusFailed}")
            }
        }
    } }

    @Test fun notificationAvailabilityNoticeExpandsAndReversesWithoutRemovingReadableHistory() = onMotionTestThread { runTest {
        S4SmsHistoryRig.create(testScheduler, sameThread = false, count = 3).use { rig ->
            val access = MutableStateFlow<Boolean?>(true)
            var settingsCalls = 0
            MotionTestEnvironment(testScheduler, 420, 900) { s4SmsHost {
                MessagesScreen(rig.selection.state.collectAsState().value, {}, {},
                    notificationsEnabled = access.collectAsState().value, onNotificationSettings = { settingsCalls++ },
                    historyChoices = rig.selection.historyChoices.collectAsState().value,
                    onSearchHistory = rig.selection::searchInbox, onLoadMore = { rig.inboxCalls++; rig.selection.loadMoreInbox() })
            } }.use { e ->
                e.s4Frames(24)
                val start = s4Visible(e, "p-2", inList = true)!!.boundsInRoot.top
                val ids = rig.inbox.page.items.map { it.latestMessageId }
                access.value = false; e.advanceFrameBy(0); e.s4Frames(2)
                val middle = s4Visible(e, "p-2", inList = true)!!.boundsInRoot.top
                e.s4Frames(48)
                val end = s4Visible(e, "p-2", inList = true)!!.boundsInRoot.top
                assertTrue(end - start >= 24f, "前提：紧凑提示确实推开当前可读列表")
                assertTrue(middle > start + .5f && middle < end - .5f, "L7：插入提示须经过真实高度中间帧")
                assertEquals(ids, rig.inbox.page.items.map { it.latestMessageId })
                s4Tap(e, "短信通知未开启，点此打开系统设置。短信仍会保存。"); e.s4Frames(2)
                assertEquals(1, settingsCalls, "指针前提：通知提示确实可行动")
                access.value = true; e.advanceFrameBy(0); e.s4Frames(2)
                val reverse = s4Visible(e, "p-2", inList = true)!!.boundsInRoot.top
                e.s4Frames(48)
                assertTrue(reverse > start + .5f && reverse < end - .5f)
                assertEquals(start, s4Visible(e, "p-2", inList = true)!!.boundsInRoot.top, .5f)
                assertEquals(ids, rig.inbox.page.items.map { it.latestMessageId }); assertEquals(0, rig.inboxCalls)
            }
        }
    } }

    @Test fun actualConversationDeleteWaitsForStorageRestoresFailureAndOnlyThenFadesAndClosesItsGap() = onMotionTestThread { runTest {
        S4SmsHistoryRig.create(testScheduler, sameThread = false, count = 3).use { rig ->
            // 复用原fake的实际记录与history实现；该有限仓储表是本测试source唯一读写视图。
            // 本用例以独立有限表保留受控DELETE写门；不是在UI层过滤列表。
            val table = (0 until 3).associate { index ->
                val id = "id-$index"
                id to requireNotNull(rig.stored.stored(id))
            }.toMutableMap()
            val targetStored = requireNotNull(table["id-1"])
            val target = StoredMessageConversation(targetStored.ownerId, targetStored.conversationKey)
            var entered = CompletableDeferred<Unit>()
            var release = CompletableDeferred<Unit>()
            var failWrite = true
            var deleteCalls = 0
            var commits = 0
            val repository = object : MessageRepository by rig.stored {
                override val history = FakeMessageHistoryRepository { table.values.toList() }
                override suspend fun listConversations() = table.values.map {
                    StoredMessageConversation(it.ownerId, it.conversationKey)
                }.distinct()
                override suspend fun listByOwner(ownerId: SimOwnerId): List<MessageRecord> = table.values
                    .filter { it.ownerId == ownerId }.sortedWith(compareBy({ it.messageAtMs }, { it.id })).map { it.toRecord() }
                override suspend fun countUnreadIncoming(): Long = table.values.count { it.incoming && !it.isRead }.toLong()
                override suspend fun countUnreadByOwner(): Map<SimOwnerId, Long> = table.values.filter { it.countsAsUnread }
                    .groupingBy { it.ownerId }.eachCount().mapValues { it.value.toLong() }
                override suspend fun changeConversation(identity: StoredMessageConversation, action: MessageConversationAction): Boolean {
                    assertEquals(target, identity); assertEquals(MessageConversationAction.DELETE, action)
                    deleteCalls++; entered.complete(Unit)
                    release.await()
                    if (failWrite) throw MessageStorageException()
                    val removed = table.entries.removeAll { (_, row) ->
                        row.ownerId == identity.owner && row.conversationKey == identity.key
                    }
                    if (removed) commits++
                    return removed
                }
            }
            val transport = FakeEngineMessageTransport()
            val source = EngineMessageSource(
                runtime = EngineMessageRuntime(listInstances = { listOf(running(ICCID_A)) }, transportFor = { transport },
                    clock = { NOW_MS }, activeIccids = { listOf(ICCID_A) }),
                parentContext = coroutineContext, repository = repository,
                ownerIdFor = { if (it == ICCID_A) rig.owner else null }, nextMessageId = sequentialIds("s4-delete"),
            )
            val backOwner = object : NavigationEventDispatcherOwner {
                override val navigationEventDispatcher = NavigationEventDispatcher()
            }
            val results = mutableListOf<MessageConversationActionResult>()
            var opens = 0
            try {
                testScheduler.runCurrent()
                fun inbox() = assertIs<MessageUiState.Inbox>(source.state.value)
                assertEquals(rig.owner, inbox().ownerId)
                val originalRecords = repository.listByOwner(rig.owner)
                val originalHandles = inbox().page.items.map { it.identity.handle }
                assertEquals(3, originalRecords.size); assertEquals(3, originalHandles.size)
                assertTrue(transport.statusRequests > 0, "前提：真实Session已接通合成transport")
                MotionTestEnvironment(testScheduler, 420, 900) { s4SmsHost {
                    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides backOwner) {
                        MessagesScreen(source.state.collectAsState().value, { opens++ }, {},
                            historyChoices = source.historyChoices.collectAsState().value,
                            onSearchHistory = source::searchHistory, onLoadMore = source::loadMoreHistory,
                            onConversationAction = { handle, action ->
                                source.changeConversation(handle.value, action).also { results += it }
                            })
                    }
                } }.use { e ->
                    e.s4Frames(32)
                    fun row(label: String): SemanticsNode = s4Nodes(e).single {
                        it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "会话操作" && s4HasLabel(it, label)
                    }
                    fun textBounds(label: String) = s4SmsGlyphBounds(e, label)
                    fun patch(label: String) = s3SmallPatch(e, testScheduler, textBounds(label))
                    fun gap() = textBounds("p-0").top - textBounds("p-2").top
                    fun startDeleteThroughActualMenu() {
                        val bounds = row("p-1").boundsInRoot
                        val viewport = s4List(e).boundsInRoot
                        assertTrue(bounds.height >= 48f && bounds.left >= viewport.left && bounds.right <= viewport.right &&
                            bounds.top >= viewport.top && bounds.bottom <= viewport.bottom, "长按前提：目标真实行完整可见")
                        e.press(bounds.center); e.s4Frames(40); e.release(bounds.center); e.s4Frames(24)
                        assertEquals(0, opens, "长按不能先触发打开会话")
                        assertNotNull(s4Visible(e, "标为已读"), "前提：真实未读会话的长按菜单已出现")
                        s4Tap(e, "删除"); e.s4Frames(40)
                        assertNotNull(s4Visible(e, "删除会话？"), "前提：必须经过真实确认，不能直接调后端")
                        // T7取代旧32ms即时交接前提：真实原宿主退出完成才交接；E2仍从真实写门/写回计时。
                        val confirmHost = s4Nodes(e).single { it.config.contains(SemanticsProperties.IsDialog) }
                        assertTrue(confirmHost.layoutInfo.isAttached, "前提：必须跟踪原DialogLayout实际宿主")
                        val callsBeforeConfirm = deleteCalls
                        s4Tap(e, "删除") // 原helper仅持有8ms后真实Release；返回后未推进下一帧。
                        val releasedAt = testScheduler.currentTime
                        val exitMillis = (MotionTokens.DurFast * e.durationScale).toLong()
                        val deadline = releasedAt + exitMillis
                        fun consumeConfirmationNow() {
                            val at = testScheduler.currentTime
                            // 不因metadata暂稳提前return；宿主卸载及onDismissFinished都消费同一时刻pending。
                            repeat(6) { e.advanceFrameBy(0) }
                            assertEquals(at, testScheduler.currentTime, "T7同刻泵不得延长确认退场deadline")
                        }
                        consumeConfirmationNow()
                        if (exitMillis > 0) {
                            e.advanceFrameBy(exitMillis - 1); consumeConfirmationNow()
                            assertTrue(confirmHost.layoutInfo.isAttached, "T7退场期限前原宿主不能提前卸载")
                            assertFalse(entered.isCompleted, "T7退场期限前不可提前进入仓储")
                            assertEquals(callsBeforeConfirm, deleteCalls)
                        }
                        repeat((exitMillis / 16 + 2).toInt()) {
                            val attached = confirmHost.layoutInfo.isAttached
                            if (attached) {
                                assertFalse(entered.isCompleted, "T7：原宿主仍在退出时不能提前进入repository")
                                assertEquals(callsBeforeConfirm, deleteCalls)
                            }
                            if ((attached || !entered.isCompleted) && testScheduler.currentTime < deadline) {
                                e.advanceFrameBy(minOf(16L, deadline - testScheduler.currentTime))
                                consumeConfirmationNow()
                            }
                        }
                        consumeConfirmationNow()
                        println("S5_SMS_T7_HANDOFF|scale=${e.durationScale}|release=$releasedAt|deadline=$deadline|" +
                            "now=${testScheduler.currentTime}|hostAttached=${confirmHost.layoutInfo.isAttached}|" +
                            "ioStarted=${entered.isCompleted}|beforeCalls=$callsBeforeConfirm|calls=$deleteCalls|" +
                            "ownerMatches=${inbox().ownerId == rig.owner}|targetPresent=${inbox().page.items.any { it.identity == target }}|" +
                            "result=${results.lastOrNull()}")
                        assertTrue(testScheduler.currentTime <= deadline, "T7：只允许原scale下DurFast，不套500ms等待")
                        if (e.durationScale == 0f) assertEquals(releasedAt, testScheduler.currentTime,
                            "零动画同刻完成真实宿主退出及交接，不推迟到后继帧")
                        assertFalse(confirmHost.layoutInfo.isAttached, "T7：必须是原DialogLayout实际卸载，不是标题裁掉")
                        assertTrue(s4Nodes(e).none { it.config.contains(SemanticsProperties.IsDialog) }, "不得残留确认宿主")
                        assertTrue(entered.isCompleted, "前提：确认必须进入真实repository写门")
                    }
                    val originalTarget = patch("p-1")
                    val originalAbove = patch("p-2")
                    val originalBelow = patch("p-0")
                    val originalGap = gap()
                    startDeleteThroughActualMenu()
                    e.s4Frames(16)
                    assertEquals(1, deleteCalls); assertEquals(0, commits); assertTrue(results.isEmpty())
                    assertEquals(originalRecords, repository.listByOwner(rig.owner))
                    assertEquals(originalHandles, inbox().page.items.map { it.identity.handle })
                    assertEquals(originalGap, gap(), .5f, "仓储提交前不能先腾空目标或推动兄弟会话")
                    assertEquals(MotionTokens.AlphaPending.toDouble(), s4InkRatio(patch("p-1"), originalTarget), .08,
                        "pending仅将真实目标行降至AlphaPending，不是整列表禁用色")
                    assertTrue(s4InkRatio(patch("p-2"), originalAbove) > .97)
                    assertTrue(s4InkRatio(patch("p-0"), originalBelow) > .97)
                    assertTrue(s4Nodes(e).none { s4HasLabel(it, "p-1") &&
                        (it.config.contains(SemanticsActions.OnClick) || it.config.contains(SemanticsActions.OnLongClick)) &&
                        !it.config.contains(SemanticsProperties.Disabled) }, "pending目标不能保留可用点击／长按入口")
                    val pendingPoint = textBounds("p-1").center
                    e.press(pendingPoint); e.advanceFrameBy(8); e.release(pendingPoint); e.s4Frames(2)
                    assertEquals(0, opens); assertEquals(1, deleteCalls)

                    // 第一次真实仓储失败：原记录／目标墨迹／可重试菜单恢复，不能伪装成功删除。
                    release.complete(Unit); testScheduler.runCurrent(); e.s4Frames(40)
                    assertEquals(listOf(MessageConversationActionResult.FAILED), results)
                    assertEquals(0, commits); assertEquals(originalRecords, repository.listByOwner(rig.owner))
                    assertEquals(originalHandles, inbox().page.items.map { it.identity.handle })
                    assertTrue(s4InkRatio(patch("p-1"), originalTarget) > .97, "失败恢复完整原墨迹")
                    assertFalse(row("p-1").config.contains(SemanticsProperties.Disabled))
                    assertNotNull(s4Visible(e, "无法确认操作结果，请刷新列表后确认。"))

                    // 同一个真实界面重新长按／确认，只重试这一个目标，不重建UI来抹掉失败。
                    val actualRemovedSpan = row("p-0").boundsInRoot.top - row("p-1").boundsInRoot.top
                    assertTrue(actualRemovedSpan > 24f, "几何前提：实际相邻行跨度可测")
                    entered = CompletableDeferred(); release = CompletableDeferred(); failWrite = false
                startDeleteThroughActualMenu(); e.s4Frames(16)
                assertEquals(2, deleteCalls); assertEquals(0, commits)
                assertEquals(originalRecords, repository.listByOwner(rig.owner))
                var stableFrames = 0
                var previousPositions = textBounds("p-2").top to textBounds("p-0").top
                for (sample in 0 until 12) {
                    if (stableFrames >= 2) break
                    e.advanceFrameBy(16)
                    val positions = textBounds("p-2").top to textBounds("p-0").top
                    stableFrames = if (positions == previousPositions) stableFrames + 1 else 0
                    previousPositions = positions
                }
                assertTrue(stableFrames >= 2, "采样前提：真实写门仍held时，旧失败notice必须已经收稳")
                    val beforeCommitTarget = textBounds("p-1")
                    val beforeCommitPatch = s3SmallPatch(e, testScheduler, beforeCommitTarget)
                    val aboveBefore = textBounds("p-2").top
                    val gapBefore = gap()
                    // 行跨度在重试前从真实可交互行取值；pending撤掉动作语义后不靠旧OnClick定位。
                    val previousChange = inbox().page.change
                    release.complete(Unit); testScheduler.runCurrent()
                    assertEquals(1, commits); assertEquals(2, deleteCalls)
                    assertEquals(listOf(MessageConversationActionResult.FAILED, MessageConversationActionResult.APPLIED), results)
                    assertEquals(originalRecords.filterNot { it.id == targetStored.id }, repository.listByOwner(rig.owner))
                    assertEquals(originalHandles.filterNot { it == target.handle }, inbox().page.items.map { it.identity.handle })
                    val deletion = assertNotNull(inbox().page.change, "缺能力：真实删除写回须发布USER_DELETE，而非靠长度猜淡出")
                    assertEquals(HistoryChangeReason.USER_DELETE, deletion.reason)
                    assertEquals(setOf(target.handle), deletion.ids)
                previousChange?.let { assertTrue(deletion.scope === it.scope); assertTrue(deletion.version > it.version) }

                // runCurrent只证明数据完成，不证明UI已绘制removing；先在同一时刻交付首帧。
                repeat(2) { zero ->
                    e.advanceFrameBy(0)
                    val shift = textBounds("p-2").top - aboveBefore
                    val visibleSlot = s3SmallPatch(e, testScheduler, beforeCommitTarget.translate(Offset(0f, shift)))
                    println("S4_SMS_DELETE_PHASE|zero=$zero|t=${testScheduler.currentTime}|applied=${results.last()}|" +
                        "readbackMissing=${inbox().page.items.none { it.identity == target }}|" +
                        "targetSemantics=${s4Visible(e, "p-1", inList = true) != null}|slotInk=${s4InkRatio(visibleSlot, beforeCommitPatch)}")
                }

                    val gaps = mutableListOf<Float>()
                    var fading: S3SmallPatch? = null
                    repeat(24) { frame ->
                        e.advanceFrameBy(16); gaps += gap()
                    if (frame == 1) {
                            // 失败notice此时也可能收起；以上方兄弟为锚抵消整列表共同位移，不裁掉目标字形。
                            val shift = textBounds("p-2").top - aboveBefore
                        fading = s3SmallPatch(e, testScheduler, beforeCommitTarget.translate(Offset(0f, shift)))
                        println("S4_SMS_DELETE_PHASE|phase=32ms|t=${testScheduler.currentTime}|slotInk=${s4InkRatio(requireNotNull(fading), beforeCommitPatch)}")
                        }
                    }
                    val fade = s4InkRatio(assertNotNull(fading), beforeCommitPatch)
                    assertTrue(fade > .03 && fade < .97, "写回后32ms必须仍有真实淡出中间墨迹，不能直接撤绘制；ratio=$fade")
                    e.s4Frames(40)
                    val gapAfter = gap()
                    assertEquals(actualRemovedSpan, gapBefore - gapAfter, 2f, "终值只合拢目标实际行跨度")
                    assertTrue(gaps.any { it < gapBefore - .5f && it > gapAfter + .5f }, "后续兄弟合拢必须存在真实位置中间帧")
                    assertNull(s4Visible(e, "p-1", inList = true))
                    assertTrue(s4InkRatio(patch("p-2"), originalAbove) > .97)
                    assertTrue(s4InkRatio(patch("p-0"), originalBelow) > .97)
                    assertEquals(0, opens); assertEquals(2, deleteCalls); assertEquals(1, commits)
                    assertTrue(transport.requests.isEmpty(), "删除不能伪造发送RPC或新短信")
                }
            } finally {
                release.complete(Unit); source.close(); testScheduler.runCurrent(); rig.stored.close()
            }
        }
    } }

    // 2026-10-17用户要求取代原“自输入区方向OffsetSmall+ScaleEnterFrom”：输入栏发送改由发送飞行落到真实排队气泡（规范5.8）。
    @Test fun actualQueuedMoLandsFromComposerFlightAndStatusReportDoesNotReplayEntrance() = onMotionTestThread { runTest {
        S4SmsHistoryRig.create(testScheduler, sameThread = true, count = 2).use { rig ->
            val queueEntered = CompletableDeferred<Unit>()
            val queueRelease = CompletableDeferred<Unit>()
            val transportRelease = CompletableDeferred<Unit>()
            var queueCalls = 0
            val repository = object : MessageRepository by rig.stored {
                override suspend fun insertQueued(ownerId: SimOwnerId, messageId: String, peerAddress: String, body: String, nowMs: Long) {
                    assertEquals(rig.owner, ownerId)
                    queueCalls++; queueEntered.complete(Unit); queueRelease.await()
                    rig.stored.insertQueued(ownerId, messageId, peerAddress, body, nowMs)
                }
            }
            val transport = FakeEngineMessageTransport(
                sendResult = EngineMessagePersistenceFixtures.sendOk(MO_ID, 202, 1), requestGate = transportRelease)
            val source = EngineMessageSource(
                runtime = EngineMessageRuntime(listInstances = { listOf(running(ICCID_A)) }, transportFor = { transport },
                    clock = { NOW_MS }, activeIccids = { listOf(ICCID_A) }),
                parentContext = coroutineContext, repository = repository,
                ownerIdFor = { if (it == ICCID_A) rig.owner else null }, nextMessageId = sequentialIds("s4-queued-mo"),
            )
            try {
                testScheduler.runCurrent(); source.selectConversation(rig.identity.handle.value); testScheduler.runCurrent()
                fun thread() = assertIs<MessageUiState.Ready>(source.state.value)
                val originalIds = thread().records.map { it.id }
                assertEquals(2, originalIds.size); assertTrue(thread().composerEnabled)
                val flight = MessageSendFlightState()
                var canvas = 0; var outgoing = 0
                MotionTestEnvironment(testScheduler, 420, 900) { s4SmsHost {
                    PhoneStyle {
                        val colors = communicationColors()
                        SideEffect { canvas = colors.timeline.toArgb(); outgoing = colors.outgoing.toArgb() }
                    }
                    val displayed = source.state.collectAsState().value
                    MessageThreadScreen(rig.identity.handle, displayed, { number, body ->
                        source.sendFromDisplayedOwner((displayed as? MessageUiState.Available)?.ownerId, number, body)
                    }, historyChoices = source.historyChoices.collectAsState().value, sendFlight = flight)
                } }.use { e ->
                    e.s4Frames(24)
                    fun bodyNode(): SemanticsNode {
                        fun leaves(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::leaves)
                        return e.scene.semanticsOwners.flatMap { leaves(it.unmergedRootSemanticsNode) }.single {
                            it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == "88" &&
                                it.config.contains(SemanticsActions.GetTextLayoutResult) &&
                                !it.config.contains(SemanticsActions.SetText)
                        }
                    }
                    fun bubble(): SemanticsNode = s4Nodes(e).single {
                        it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "消息操作" && s4HasLabel(it, "88")
                    }
                    val input = s4Nodes(e).single { s4HasLabel(it, "短信内容") && it.config.contains(SemanticsActions.SetText) }
                    assertTrue(requireNotNull(input.config[SemanticsActions.SetText].action).invoke(AnnotatedString("88")))
                    e.s4Frames(2); s4Tap(e, "发送短信"); e.advanceFrameBy(0); e.s4Frames(2)
                    assertTrue(queueEntered.isCompleted, "前提：真实发送入口须到sendFromDisplayedOwner并进入queue写门")
                    assertEquals(1, queueCalls); assertEquals(0, rig.stored.insertQueuedCalls)
                    assertEquals(originalIds, thread().records.map { it.id })
                    assertNull(s4Visible(e, "88", inList = true), "queue写入前不得先造出OutgoingBubble")
                    assertTrue(transport.requests.isEmpty(), "queue落库前不能发送RPC")
                    val oldChange = thread().window?.change
                    queueRelease.complete(Unit); testScheduler.runCurrent()
                    val queued = thread().records.filterIsInstance<MessageRecord.Outgoing>().single()
                    assertEquals("88", queued.preview); assertEquals(OutgoingMessageStatus.QUEUED, queued.status)
                    assertEquals(queued.id, assertNotNull(rig.stored.stored(queued.id)).id)
                    assertEquals(originalIds + queued.id, thread().records.map { it.id })
                    assertEquals(1, queueCalls); assertEquals(1, rig.stored.insertQueuedCalls)
                    assertEquals(listOf("sendSms"), transport.requests.map { it.method })
                    val inserted = assertNotNull(thread().window?.change, "缺能力：真实queue读回须发布新MO稳定id的LIVE_INSERT")
                    assertEquals(HistoryChangeReason.LIVE_INSERT, inserted.reason); assertEquals(setOf(queued.id), inserted.ids)
                    oldChange?.let { assertTrue(inserted.scope === it.scope); assertTrue(inserted.version > it.version) }

                    e.advanceFrameBy(0); e.s4Frames(2)
                    assertNotNull(s4Visible(e, "88", inList = true), "前提：真实首次到底已让新MO进入可见时间线")
                    fun pixel(x: Float, y: Float) = e.scene.render(testScheduler.currentTime * 1_000_000L).use { image ->
                        org.jetbrains.skia.Bitmap.makeFromImage(image).use { it.getColor(floor(x).toInt(), floor(y).toInt()) }
                    }
                    val carried = assertNotNull(flight.flight, "真实queue落库后的新MO须由发送飞行承接")
                    assertEquals(queued.id, carried.id); assertEquals("88", carried.body)
                    assertTrue(flight.hides(queued.id), "飞行中真实气泡保持隐藏")
                    val hidden = bubble().boundsInRoot
                    val probe = androidx.compose.ui.geometry.Offset(hidden.right - 4f, hidden.center.y)
                    val ghost = flight.frame(carried, 12f, 8f)
                    assertFalse(ghost.contains(probe), "像素前提：探针不在当前飞行层内")
                    assertTrue(s3RgbDistance(pixel(probe.x, probe.y), canvas) <= 3, "飞行落地前真实气泡不得与飞行层重影")
                    e.s4Frames(40)
                    assertNull(flight.flight, "飞行在有限时间内落地")
                    val end = bodyNode().boundsInRoot
                    val endBubble = bubble().boundsInRoot
                    assertEquals(hidden, endBubble, "落地前后真实气泡几何不变：飞行层承担全部位移，真实气泡不再缩放或平移")
                    assertTrue(end.left >= endBubble.left && end.right <= endBubble.right, "正文在气泡内")
                    assertTrue(s3RgbDistance(pixel(endBubble.right - 4f, endBubble.center.y), outgoing) <= 3,
                        "落地后由真实气泡原色接管")
                    val composerTop = s4Nodes(e).single { s4HasLabel(it, "短信内容") && it.config.contains(SemanticsProperties.EditableText) }.boundsInRoot.top
                    assertTrue(endBubble.bottom <= composerTop, "落地气泡停在悬浮输入栏上方")
                    assertEquals(originalIds + queued.id, thread().records.map { it.id })
                    assertEquals(1, queueCalls); assertEquals(1, rig.stored.insertQueuedCalls)

                    // 让同一条真实发送得到ACCEPTED后稳定composer，再投递真实smsSent报告。
                    // 不把发送完成时正常的输入区高度变化误判成气泡重新入场。
                    transportRelease.complete(Unit); testScheduler.runCurrent(); e.s4Frames(40)
                    val accepted = thread().records.filterIsInstance<MessageRecord.Outgoing>().single()
                    assertEquals(queued.id, accepted.id); assertEquals(MO_ID, accepted.engineSmsId)
                    assertEquals(OutgoingMessageStatus.ACCEPTED, accepted.status)
                    val beforeReport = bodyNode().boundsInRoot
                    val beforeGlyphs = s4SmsGlyphBounds(e, "88")
                    val originalInk = s3SmallPatch(e, testScheduler, beforeGlyphs)
                    transport.emitSent(MO_ID, SmsEngineStatus.PENDING); testScheduler.runCurrent()
                    assertEquals(originalIds + queued.id, thread().records.map { it.id })
                    assertEquals(SmsEngineStatus.PENDING, thread().records.filterIsInstance<MessageRecord.Outgoing>().single().engineStatus)
                    e.advanceFrameBy(0); e.s4Frames(2)
                    val reportMiddle = bodyNode().boundsInRoot
                    assertEquals(beforeReport.left, reportMiddle.left, .5f); assertEquals(beforeReport.top, reportMiddle.top, .5f)
                    assertEquals(beforeReport.width, reportMiddle.width, .5f); assertEquals(beforeReport.height, reportMiddle.height, .5f)
                    assertTrue(s4InkRatio(s3SmallPatch(e, testScheduler, beforeGlyphs), originalInk) > .97,
                        "状态报告只更新原气泡内容，不能重演新MO入场")
                    e.s4Frames(32)
                    assertEquals(queued.id, thread().records.filterIsInstance<MessageRecord.Outgoing>().single().id)
                    assertEquals(1, queueCalls); assertEquals(1, rig.stored.insertQueuedCalls)
                    assertEquals(1, rig.stored.bindAcceptedCalls); assertEquals(1, transport.requests.size)
                }
            } finally {
                queueRelease.complete(Unit); transportRelease.complete(Unit)
                source.close(); testScheduler.runCurrent(); rig.stored.close()
            }
        }
    } }
}

/** 取实际单个Text的完整字形；不把合并语义／fillMaxWidth、时间戳或正文混进STATUS采样。 */
private fun s4SmsGlyphBounds(e: MotionTestEnvironment, label: String): Rect {
    fun leaves(node: androidx.compose.ui.semantics.SemanticsNode): List<androidx.compose.ui.semantics.SemanticsNode> =
        listOf(node) + node.children.flatMap(::leaves)
    val candidates = e.scene.semanticsOwners.flatMap { leaves(it.unmergedRootSemanticsNode) }.filter { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.let { stripMessageDisplayJoiners(it.text) == label } == true &&
            node.config.contains(SemanticsActions.GetTextLayoutResult)
    }
    println("S4_SMS_GLYPHS|label=$label|candidateIds=${candidates.map { it.id }}|semantics=${candidates.map { it.boundsInRoot }}")
    val node = assertNotNull(candidates.singleOrNull(), "采样前提：必须唯一命中真实单个Text，不能取合并气泡")
    val layouts = mutableListOf<TextLayoutResult>()
    assertTrue(requireNotNull(node.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts),
        "采样前提：真实Text须提供TextLayoutResult")
    val layout = layouts.single()
    val text = layout.layoutInput.text.text
    assertEquals(label, stripMessageDisplayJoiners(text), "采样前提：布局内容必须仅为目标STATUS／正文，不含时间戳")
    assertEquals(1, layout.lineCount, "采样前提：完整单行短文字，不能只裁可见的部分失败墨迹")
    assertFalse(layout.isLineEllipsized(0), "采样前提：完整目标文字不得省略")
    val boxes = text.indices.filter { text[it] != WORD_JOINER && !text[it].isWhitespace() }.map(layout::getBoundingBox)
    assertEquals(label.count { !it.isWhitespace() }, boxes.size, "采样前提：必须覆盖目标的全部可见字符")
    assertTrue(boxes.isNotEmpty() && boxes.all { it.width > 0f && it.height > 0f }, "采样前提：所有真实字符须有有效字形范围")
    val origin = node.positionInRoot
    // 外扩1px保留抗锯齿边缘，五字失败状态也取全部字符，不挑选容易通过的核心子集。
    val bounds = Rect(origin.x + boxes.minOf { it.left } - 1f, origin.y + boxes.minOf { it.top } - 1f,
        origin.x + boxes.maxOf { it.right } + 1f, origin.y + boxes.maxOf { it.bottom } + 1f)
    val viewport = s4List(e).boundsInRoot
    println("S4_SMS_GLYPHS|label=$label|owner=${node.id}|position=$origin|layoutSize=${layout.size}|characters=${boxes.size}|bounds=$bounds|viewport=$viewport")
    assertTrue(ceil(bounds.right) - floor(bounds.left) in 1f..96f && ceil(bounds.bottom) - floor(bounds.top) in 1f..64f,
        "采样前提：完整真实字形仍须满足原96×64小区域限制，bounds=$bounds")
    assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right && bounds.top >= viewport.top && bounds.bottom <= viewport.bottom,
        "采样前提：完整字形须在真实列表viewport内，不能用裁剪冒充透明度")
    return bounds
}

private fun s4LiveIdentity() = StoredMessageConversation(requireNotNull(ownerFor(ICCID_A)), SYNTH_NUMBER.drop(1))
@Composable private fun s4LiveThread(source: EngineMessageSource) = s4SmsHost {
    MessageThreadScreen(s4LiveIdentity().handle, source.state.collectAsState().value, source::sendSms)
}
private suspend fun TestScope.s4Live(block: suspend (EngineMessageSource, FakeEngineMessageTransport) -> Unit) {
    val store = FakeMessageRepository(); val owner = requireNotNull(ownerFor(ICCID_A))
    store.insertIncomingIfAbsent(owner, "mt-old", "old-mt", SYNTH_NUMBER, "44", NOW_MS - 2_000, NOW_MS)
    store.insertQueued(owner, "mo-old", SYNTH_NUMBER, "11", NOW_MS)
    store.bindAccepted(owner, "mo-old", MO_ID, NOW_MS)
    val transport = FakeEngineMessageTransport()
    val source = sourceOf(listOf(running(ICCID_A)), transport, store)
    try {
        testScheduler.runCurrent(); source.selectConversation(s4LiveIdentity().handle.value); testScheduler.runCurrent()
        assertIs<MessageUiState.Ready>(source.state.value)
        block(source, transport)
    } finally { source.close(); testScheduler.runCurrent() }
}
