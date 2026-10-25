@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.*
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.navigationevent.*
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.desktop.S4SmsHistoryRig
import com.vowifi.sw.manager.desktop.s4SmsHost
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import java.awt.Canvas
import java.awt.event.KeyEvent as AwtKeyEvent
import kotlin.math.floor
import kotlin.test.*

class S7RootMenuAccessibilityRenderTest {
    private fun tabRing(e: MotionTestEnvironment, scheduler: TestCoroutineScheduler, label: String, longLabel: String,
        accent: Int, mode: InputModeManager, haptics: List<HapticFeedbackType>): List<String> {
        fun pump() { repeat(4) { e.advanceFrameBy(0) } }
        fun row() = s4Nodes(e, false).single { s4Matches(it, label) && it.config.getOrNull(SemanticsActions.OnLongClick)?.label == longLabel }
        fun focused(): Boolean {
            val target = row(); val raw = s4Nodes(e, true)
            val parents = raw.flatMap { p -> p.children.map { it.id to p } }.toMap()
            return raw.filter { it.config.getOrNull(SemanticsProperties.Focused) == true }.any { n ->
                generateSequence(n) { parents[it.id] }.firstOrNull {
                    it.config.contains(SemanticsActions.OnLongClick) || it.config.contains(SemanticsActions.OnClick)
                }?.id == target.id
            }
        }
        val clock = scheduler.currentTime; val pulses = haptics.size; val failures = mutableListOf<String>()
        val original = row(); val bounds = original.boundsInRoot
        assertFalse(original.config.contains(SemanticsProperties.Disabled))
        assertTrue(bounds.width >= 4 && bounds.height >= 5 && bounds.left >= 0 && bounds.right <= 420 && bounds.top >= 1 && bounds.bottom <= 1000)
        assertEquals(bounds.top, original.positionInRoot.y); assertEquals(bounds.height, original.size.height.toFloat())
        val x = floor(bounds.center.x) - 2f; val y = floor(bounds.top) - 1f; val roi = Rect(x, y, x + 4f, y + 5f)
        fun patch() = s3SmallPatch(e, scheduler, roi)
        fun ink(p: S3SmallPatch) = p.pixels.count { it == accent }
        val before = patch(); assertEquals(0, ink(before), "前提：原行顶部无天然accent，不能把图标当焦点环：$label")
        val keyboard = Canvas()
        try {
            for (attempt in 0 until 24) {
                for (down in listOf(true, false)) e.scene.sendKeyEvent(KeyEvent(Key.Tab,
                    if (down) KeyEventType.KeyDown else KeyEventType.KeyUp, nativeEvent = AwtKeyEvent(keyboard,
                        if (down) AwtKeyEvent.KEY_PRESSED else AwtKeyEvent.KEY_RELEASED,
                        scheduler.currentTime, 0, AwtKeyEvent.VK_TAB, AwtKeyEvent.CHAR_UNDEFINED)))
                pump(); if (focused()) break
            }
            if (!focused()) failures += "§4.10真实Tab无法到达此长按动作节点：$longLabel/$label"
            else {
                assertEquals(InputMode.Keyboard, mode.inputMode); assertEquals(bounds, row().boundsInRoot)
                val after = patch(); assertEquals(4 to 5, after.width to after.height)
                if (ink(after) < 2 || s4Difference(before, after) < 2) failures += "§4.10原长按节点缺2dp强调色描边：$longLabel/$label"
            }
        } finally {
            assertTrue(mode.requestInputMode(InputMode.Touch)); pump(); assertEquals(InputMode.Touch, mode.inputMode)
            assertEquals(bounds, row().boundsInRoot); assertEquals(clock, scheduler.currentTime); assertEquals(pulses, haptics.size)
            if (ink(patch()) != 0) failures += "§4.10切回Touch后焦点环未撤：$label"
        }
        return failures
    }
    private fun modalRejects(e: MotionTestEnvironment, title: String, actions: List<CustomAccessibilityAction>, effects: () -> Any) {
        fun dialogs() = s4Nodes(e, false).filter { it.config.contains(SemanticsProperties.IsDialog) && s4Matches(it, title) }.distinctBy { it.id }.size
        val before = effects(); assertEquals(1, dialogs(), "前提：原确认框必须唯一"); val accepted = actions.map { it.action() }; s4Frames(e, 6)
        assertEquals(before, effects(), "确认未决时旧动作不得改变callback/clipboard/IO"); assertEquals(1, dialogs(), "原确认框须仍唯一")
        assertEquals(List(actions.size) { false }, accepted, "确认未决时每个缓存CustomAction均须拒绝")
    }
    @Test fun actualRootCustomActionsShareMenusConfirmationAndRejectStaleAuthority() = onMotionTestThread {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val saved = FakeCallHistoryRepository()
            saved.upsert(OWNER_A, historyWrite(correlation = "s7-synthetic", peer = "155501",
                connectedAtMs = T0 + 1000, endedAtMs = T0 + 2000, durationSec = 1, outcome = CallOutcome.COMPLETED))
            saved.selectOwner(OWNER_A)
            val callGate = CompletableDeferred<Unit>(); val smsGate = CompletableDeferred<Unit>()
            var deletes = 0; var callbacks = 0; val smsActions = mutableListOf<MessageConversationAction>()
            val repository = object : CallHistoryRepository by saved {
                override suspend fun deleteByIds(ownerId: SimOwnerId, ids: Set<CallHistoryRecordId>) {
                    deletes++; callGate.await(); saved.deleteByIds(ownerId, ids)
                }
            }
            val calls = CallSourceAggregate(EngineMessageRuntime({ emptyList() }, { error("no engine") }, { T0 }, { emptyList() }),
                FakeCallAudioController(), CallControlTransport { _, _, _, _ -> error("no call") },
                backgroundScope.coroutineContext + dispatcher, repository, { null }, { "unused" })
            val rig = S4SmsHistoryRig.create(testScheduler, sameThread = false, count = 1)
            val clipboard = object : Clipboard {
                override val nativeClipboard: Any = Any(); var writes = 0
                override suspend fun getClipEntry(): ClipEntry? = null
                override suspend fun setClipEntry(clipEntry: ClipEntry?) { assertNotNull(clipEntry); writes++ }
            }
            val back = object : NavigationEventDispatcherOwner { override val navigationEventDispatcher = NavigationEventDispatcher() }
            var active by mutableStateOf(true); var callsPage by mutableStateOf(true); var canCall by mutableStateOf(true)
            var longMillis = 0L
            var accent = 0; var inputMode: InputModeManager? = null; val keyboardFaults = mutableListOf<String>()
            val haptics = mutableListOf<HapticFeedbackType>()
            val feedback = object : HapticFeedback { override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType } }
            try {
                MotionTestEnvironment(testScheduler, 420, 1000, dispatcher = dispatcher) {
                    s4SmsHost {
                        CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides back,
                            LocalAppRouteActive provides active, LocalClipboard provides clipboard, LocalHapticFeedback provides feedback) {
                            val timeout = LocalViewConfiguration.current.longPressTimeoutMillis
                            val ink = phoneColors().accent.toArgb(); val mode = LocalInputModeManager.current
                            SideEffect { longMillis = timeout; accent = ink; inputMode = mode }
                            if (callsPage) {
                                val snapshot = calls.historySnapshot.collectAsState().value
                                CallsScreen(calls.state.collectAsState().value, true, {}, snapshot.view, {}, {}, {}, {}, {},
                                    callbackEnabled = canCall, onCallback = { assertEquals("155501", it); callbacks++ },
                                    historySnapshot = snapshot, onDeleteRecordsAndAwait = calls::deleteHistoryAndAwait)
                            } else MessagesScreen(rig.selection.state.collectAsState().value, {}, {},
                                historyChoices = rig.selection.historyChoices.collectAsState().value,
                                onConversationAction = { _, action ->
                                    smsActions += action
                                    if (action == MessageConversationAction.DELETE) smsGate.await()
                                    MessageConversationActionResult.APPLIED
                                })
                        }
                    }
                }.use { e ->
                    fun settle() = s4Frames(e, 50)
                    fun actions(label: String) = s4Nodes(e, false).filter { s4Matches(it, label) }
                        .flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }
                    fun named(label: String, name: String) = assertNotNull(actions(label).singleOrNull { it.label == name },
                        "S7：实际生产行缺少唯一CustomActions $name")
                    fun tap(label: String) { s4Tap(e, s4Action(e, label).boundsInRoot.center); settle() }
                    fun longMenu(label: String, menu: String) {
                        val row = s4Nodes(e, false).single { s4Matches(it, label) &&
                            it.config.getOrNull(SemanticsActions.OnLongClick)?.label == menu }
                        assertTrue(longMillis in 1L..3000L)
                        e.press(row.boundsInRoot.center); s4Frames(e, (longMillis / 16).toInt() + 3)
                        e.release(row.boundsInRoot.center); settle()
                    }
                    settle(); val before = assertIs<CallHistoryState.Ready>(calls.historySnapshot.value.view)
                    keyboardFaults += tabRing(e, testScheduler, "155501", "通话记录操作", accent, assertNotNull(inputMode), haptics)
                    assertEquals(0, callbacks); assertEquals(0, clipboard.writes); assertEquals(0, deletes)
                    val callActions = listOf("回拨", "复制号码", "删除").map { named("155501", it) }
                    assertTrue(callActions[0].action()); settle(); assertEquals(1, callbacks)
                    assertTrue(callActions[1].action()); settle(); assertEquals(1, clipboard.writes)
                    assertTrue(callActions[2].action()); settle(); assertTrue(s4HasText(e, "删除该号码的通话记录？"))
                    modalRejects(e, "删除该号码的通话记录？", callActions) { listOf(callbacks, clipboard.writes, deletes) }
                    assertEquals(0, deletes); tap("取消"); assertEquals(0, deletes)
                    canCall = false; settle(); assertFalse(callActions[0].action()); assertEquals(1, callbacks)
                    assertTrue(actions("155501").none { it.label == "回拨" })
                    canCall = true; active = false; settle()
                    callActions.forEach { assertFalse(it.action()) }; settle()
                    assertTrue(actions("155501").isEmpty()); assertEquals(0, deletes); assertEquals(1, clipboard.writes)
                    assertFalse(s4HasText(e, "删除该号码的通话记录？")); active = true; settle()
                    longMenu("155501", "通话记录操作"); tap("回拨"); assertEquals(2, callbacks)
                    longMenu("155501", "通话记录操作"); tap("复制号码"); assertEquals(2, clipboard.writes)
                    assertTrue(named("155501", "删除").action()); settle(); assertEquals(0, deletes)
                    tap("删除"); assertEquals(1, deletes); assertEquals(before.records, saved.listByOwner(OWNER_A))
                    assertTrue(calls.historySnapshot.value.deletePending.isNotEmpty())
                    callActions.forEach { assertFalse(it.action()) }; settle()
                    assertTrue(actions("155501").isEmpty()); assertEquals(1, deletes)
                    assertEquals(2, callbacks); assertEquals(2, clipboard.writes)
                    callGate.complete(Unit); settle(); assertTrue(saved.listByOwner(OWNER_A).isEmpty())
                    callsPage = false; settle(); assertIs<MessageUiState.Inbox>(rig.selection.state.value)
                    keyboardFaults += tabRing(e, testScheduler, "p-0", "会话操作", accent, assertNotNull(inputMode), haptics)
                    assertTrue(smsActions.isEmpty()); assertEquals(2, callbacks); assertEquals(2, clipboard.writes); assertEquals(1, deletes)
                    val mark = named("p-0", "标为已读"); val delete = named("p-0", "删除")
                    assertTrue(mark.action()); settle(); assertEquals(listOf(MessageConversationAction.MARK_READ), smsActions)
                    longMenu("p-0", "会话操作"); tap("标为已读")
                    assertEquals(List(2) { MessageConversationAction.MARK_READ }, smsActions)
                    active = false; settle(); assertFalse(mark.action()); assertFalse(delete.action()); settle()
                    assertTrue(actions("p-0").isEmpty()); assertEquals(2, smsActions.size)
                    assertFalse(s4HasText(e, "删除会话？")); active = true; settle()
                    assertTrue(named("p-0", "删除").action()); settle(); assertTrue(s4HasText(e, "删除会话？"))
                    modalRejects(e, "删除会话？", listOf(mark, delete)) { listOf(smsActions.toList(), callbacks, clipboard.writes, deletes) }
                    tap("取消"); assertEquals(2, smsActions.size); assertFalse(s4HasText(e, "删除会话？")); assertTrue(delete.action()); settle(); assertTrue(s4HasText(e, "删除会话？"))
                    assertEquals(2, smsActions.size); tap("删除")
                    assertEquals(List(2) { MessageConversationAction.MARK_READ } + MessageConversationAction.DELETE, smsActions)
                    assertFalse(mark.action()); assertFalse(delete.action()); settle()
                    assertTrue(actions("p-0").isEmpty()); assertEquals(3, smsActions.size)
                    assertFalse(s4HasText(e, "删除会话？")); smsGate.complete(Unit)
                    assertTrue(keyboardFaults.isEmpty(), keyboardFaults.joinToString("\n"))
                }
            } finally {
                callGate.complete(Unit); smsGate.complete(Unit); runCurrent(); calls.close(); runCurrent()
                saved.close(); rig.close(); rig.stored.close(); back.navigationEventDispatcher.dispose()
            }
        }
    }

    @Test fun actualOccurrenceDeleteConfirmsCancelsAndRejectsCachedRoutePendingRecordAndOwnerAuthority() = onMotionTestThread {
        runTest(UnconfinedTestDispatcher()) {
            val saved = FakeCallHistoryRepository(); val gate = CompletableDeferred<Unit>(); var deletes = 0
            for (owner in listOf(OWNER_A, OWNER_B)) saved.upsert(owner, historyWrite(correlation = "s7-occurrence-$owner",
                peer = "155501", connectedAtMs = T0 + 1000, endedAtMs = T0 + 2000, durationSec = 1, outcome = CallOutcome.COMPLETED))
            saved.selectOwner(OWNER_A)
            val repository = object : CallHistoryRepository by saved {
                override suspend fun deleteByIds(ownerId: SimOwnerId, ids: Set<CallHistoryRecordId>) {
                    assertEquals(OWNER_A, ownerId); assertEquals(1, ids.size); deletes++; gate.await(); saved.deleteByIds(ownerId, ids)
                }
            }
            val source = CallSourceAggregate(EngineMessageRuntime({ emptyList() }, { error("no engine") }, { T0 }, { emptyList() }),
                FakeCallAudioController(), CallControlTransport { _, _, _, _ -> error("no call") }, backgroundScope.coroutineContext, repository, { null }, { "unused" })
            val back = object : NavigationEventDispatcherOwner { override val navigationEventDispatcher = NavigationEventDispatcher() }
            var active by mutableStateOf(true)
            var accent = 0; var inputMode: InputModeManager? = null; val keyboardFaults = mutableListOf<String>()
            val haptics = mutableListOf<HapticFeedbackType>()
            val feedback = object : HapticFeedback { override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType } }
            try {
                runCurrent(); val before = assertIs<CallHistoryState.Ready>(source.historySnapshot.value.view)
                val id = assertNotNull(before.records.single().recordId); val handle = callHistoryGroups(before).single().handle
                val sibling = saved.listByOwner(OWNER_B)
                MotionTestEnvironment(testScheduler, 420, 1000) { s4SmsHost {
                    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides back, LocalAppRouteActive provides active, LocalHapticFeedback provides feedback) {
                        val ink = phoneColors().accent.toArgb(); val mode = LocalInputModeManager.current
                        SideEffect { accent = ink; inputMode = mode }
                        val snapshot by source.historySnapshot.collectAsState()
                        CallHistoryDetailScreen(handle, snapshot.view, source::deleteHistory, snapshot, source::deleteHistoryAndAwait)
                    }
                } }.use { e ->
                    fun settle() = s4Frames(e, 50)
                    fun actions() = s4Nodes(e, false).filter { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "删除通话记录" }
                        .flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }
                    fun tap(label: String) { s4Tap(e, s4Action(e, label).boundsInRoot.center); settle() }
                    settle()
                    val row = s4Nodes(e, false).single { it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "删除通话记录" }
                    assertFalse(row.config.contains(SemanticsProperties.Disabled))
                    assertTrue(row.boundsInRoot.width > 48 && row.boundsInRoot.height > 48 && row.boundsInRoot.top >= 0 && row.boundsInRoot.bottom <= 1000)
                    assertTrue(s4Matches(row, "时长：${exactCallDuration(before.records.single().durationSec)}"), "前提：真实单条记录行及完整长按语义已组合")
                    keyboardFaults += tabRing(e, testScheduler, "时长：${exactCallDuration(before.records.single().durationSec)}", "删除通话记录", accent, assertNotNull(inputMode), haptics)
                    assertEquals(0, deletes); assertEquals(before.records, saved.listByOwner(OWNER_A))
                    val delete = assertNotNull(actions().singleOrNull { it.label == "删除" }, "真实Occurrence行缺删除CustomAction"); assertEquals(1, actions().size)
                    assertTrue(delete.action()); settle(); assertTrue(s4HasText(e, "删除这条通话记录？")); assertEquals(0, deletes)
                    modalRejects(e, "删除这条通话记录？", listOf(delete)) { listOf(deletes, source.historySnapshot.value.view, saved.closed) }
                    tap("取消"); assertEquals(0, deletes); assertEquals(before.records, saved.listByOwner(OWNER_A))
                    active = false; settle(); assertFalse(delete.action()); settle(); assertEquals(0, deletes)
                    assertFalse(s4HasText(e, "删除这条通话记录？")); active = true; settle()
                    assertTrue(delete.action()); settle(); tap("删除")
                    assertEquals(1, deletes); assertEquals(setOf(id), source.historySnapshot.value.deletePending)
                    assertEquals(OWNER_A, assertIs<CallHistoryState.Ready>(source.historySnapshot.value.view).owner)
                    assertEquals(before.records, saved.listByOwner(OWNER_A)); assertFalse(delete.action()); settle(); assertTrue(actions().isEmpty())
                    gate.complete(Unit); settle(); assertTrue(saved.listByOwner(OWNER_A).isEmpty()); assertFalse(delete.action()); settle()
                    source.selectHistoryOwner(source.historyOwners.value.single { it.owner == OWNER_B }); settle()
                    assertEquals(OWNER_B, assertIs<CallHistoryState.Ready>(source.historySnapshot.value.view).owner)
                    assertFalse(delete.action()); settle(); assertEquals(1, deletes); assertEquals(sibling, saved.listByOwner(OWNER_B))
                    assertFalse(s4HasText(e, "删除这条通话记录？"))
                    assertTrue(keyboardFaults.isEmpty(), keyboardFaults.joinToString("\n"))
                }
            } finally { gate.complete(Unit); runCurrent(); source.close(); runCurrent(); saved.close(); back.navigationEventDispatcher.dispose() }
        }
    }

    @Test fun actualStoredFailedBubbleActionsSharePointerFlowProtectDraftAndRejectCachedModalRecordOwnerAndRoute() = onMotionTestThread {
        runTest(UnconfinedTestDispatcher()) {
            val saved = FakeMessageRepository(); val wire = FakeEngineMessageTransport(); val owner = assertNotNull(ownerFor(EngineMessagePersistenceFixtures.ICCID_A))
            val other = assertNotNull(ownerFor(EngineMessagePersistenceFixtures.ICCID_B)); val body = "synthetic-failed-body"; val draft = "synthetic-protected-draft"
            saved.insertQueued(owner, "s7-failed", "+155501", body, T0); saved.markFailed(owner, "s7-failed", T0 + 1)
            saved.insertQueued(owner, "s7-accepted", "+155501", "synthetic-accepted-body", T0 + 2); saved.bindAccepted(owner, "s7-accepted", "s7-wire", T0 + 3)
            saved.insertIncomingIfAbsent(owner, "s7-next", "s7-next-wire", "+155502", "synthetic-next-record", T0 + 4, T0 + 4)
            saved.insertIncomingIfAbsent(other, "s7-other", "s7-other-wire", "+155503", "synthetic-other-owner", T0 + 5, T0 + 5)
            val source = EngineMessagePersistenceFixtures.sourceOf(listOf(EngineMessagePersistenceFixtures.running(EngineMessagePersistenceFixtures.ICCID_A)), wire, saved)
            val first = StoredMessageConversation(owner, "155501"); val next = StoredMessageConversation(owner, "155502"); val otherThread = StoredMessageConversation(other, "155503")
            val clipboard = object : Clipboard {
                override val nativeClipboard: Any = Any(); var writes = 0
                override suspend fun getClipEntry(): ClipEntry? = null
                override suspend fun setClipEntry(clipEntry: ClipEntry?) { assertNotNull(clipEntry); writes++ }
            }
            val back = object : NavigationEventDispatcherOwner { override val navigationEventDispatcher = NavigationEventDispatcher() }
            var active by mutableStateOf(true); var sends = 0; var longMillis = 0L
            var accent = 0; var inputMode: InputModeManager? = null; val keyboardFaults = mutableListOf<String>()
            val haptics = mutableListOf<HapticFeedbackType>()
            val feedback = object : HapticFeedback { override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { haptics += hapticFeedbackType } }
            try {
                runCurrent(); source.selectConversation(first.handle.value); runCurrent()
                assertEquals(OutgoingMessageStatus.FAILED, assertIs<MessageUiState.Ready>(source.state.value).records.filterIsInstance<MessageRecord.Outgoing>().single { it.id == "s7-failed" }.status)
                MotionTestEnvironment(testScheduler, 420, 1000) { s4SmsHost {
                    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides back, LocalAppRouteActive provides active, LocalClipboard provides clipboard, LocalHapticFeedback provides feedback) {
                        val state by source.state.collectAsState(); val handle = (state as? MessageUiState.Ready)?.window?.identity?.handle ?: first.handle
                        val timeout = LocalViewConfiguration.current.longPressTimeoutMillis
                        val ink = phoneColors().accent.toArgb(); val mode = LocalInputModeManager.current
                        SideEffect { longMillis = timeout; accent = ink; inputMode = mode }
                        MessageThreadScreen(handle, state, { _, _ -> sends++ })
                    }
                } }.use { e ->
                    fun settle() = s4Frames(e, 50)
                    fun editor() = s4Nodes(e, false).single { it.config.contains(SemanticsProperties.EditableText) && it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("短信内容") == true }
                    fun text() = editor().config[SemanticsProperties.EditableText].text
                    fun actions(label: String) = s4Nodes(e, false).filter { s4Matches(it, label) }.flatMap { it.config.getOrNull(SemanticsActions.CustomActions).orEmpty() }
                    fun named(name: String) = assertNotNull(actions(body).singleOrNull { it.label == name }, "真实FAILED气泡缺CustomAction $name")
                    fun tap(label: String) {
                        val candidates = s4Actions(e, label).distinctBy { it.id }.filter { n -> val b = n.boundsInRoot
                            !n.config.contains(SemanticsProperties.Disabled) && b.width in 1f..300f && b.height in 48f..64f && b.left >= 0 && b.top >= 0 && b.right <= 420 && b.bottom <= 1000 }
                        assertEquals(1, candidates.size, "真实可见最小动作必须唯一：$label，排除整屏/对话根而非取first")
                        s4Tap(e, candidates.single().boundsInRoot.center); settle()
                    }
                    fun pointer(name: String) {
                        val row = s4Nodes(e, false).single { s4Matches(it, body) && it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "消息操作" }
                        assertTrue(longMillis in 1L..3000L); e.press(row.boundsInRoot.center); s4Frames(e, (longMillis / 16).toInt() + 3)
                        e.release(row.boundsInRoot.center); settle(); tap(name)
                    }
                    settle(); assertTrue(editor().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(draft))); settle()
                    val bubble = s4Nodes(e, false).single { s4Matches(it, body) && it.config.getOrNull(SemanticsActions.OnLongClick)?.label == "消息操作" }
                    assertFalse(bubble.config.contains(SemanticsProperties.Disabled))
                    assertTrue(bubble.boundsInRoot.width > 0 && bubble.boundsInRoot.height > 0 && bubble.boundsInRoot.top >= 0 && bubble.boundsInRoot.bottom <= 1000,
                        "前提：真实存储FAILED正文及消息操作长按语义须唯一可见，不能空树冒充缺动作")
                    keyboardFaults += tabRing(e, testScheduler, body, "消息操作", accent, assertNotNull(inputMode), haptics)
                    assertEquals(draft, text()); assertEquals(0, sends); assertEquals(0, clipboard.writes); assertTrue(wire.requests.isEmpty())
                    val cached = listOf("复制正文", "消息详情", "重新编辑").map(::named)
                    fun rejected() { val writes = clipboard.writes; val before = text(); cached.forEach { assertFalse(it.action()) }; settle(); assertEquals(writes, clipboard.writes); assertEquals(before, text()); assertEquals(0, sends) }
                    assertTrue(cached[0].action()); settle(); assertEquals(1, clipboard.writes); pointer("复制正文"); assertEquals(2, clipboard.writes)
                    assertTrue(cached[1].action()); settle(); assertTrue(s4HasText(e, "消息详情")); rejected(); tap("关闭详情"); assertFalse(s4HasText(e, "消息详情"))
                    pointer("消息详情"); assertTrue(s4HasText(e, "消息详情")); tap("关闭详情"); assertFalse(s4HasText(e, "消息详情"))
                    assertTrue(cached[2].action()); settle(); assertTrue(s4HasText(e, "替换现有草稿？")); assertEquals(draft, text()); rejected()
                    tap("取消"); assertEquals(draft, text()); pointer("重新编辑"); assertTrue(s4HasText(e, "替换现有草稿？"))
                    tap("替换草稿"); assertEquals(body, text()); assertEquals(0, sends)
                    assertEquals(setOf("复制正文", "消息详情"), actions("synthetic-accepted-body").map { it.label }.toSet(), "非FAILED不可重新编辑")
                    active = false; settle(); rejected(); assertTrue(actions(body).isEmpty()); active = true; settle()
                    source.selectConversation(next.handle.value); settle(); assertEquals(owner, assertIs<MessageUiState.Ready>(source.state.value).ownerId); rejected()
                    source.selectConversation(otherThread.handle.value); settle(); assertEquals(other, assertIs<MessageUiState.Ready>(source.state.value).ownerId); rejected()
                    assertFalse(s4HasText(e, "替换现有草稿？")); assertFalse(s4HasText(e, "消息详情")); assertEquals(2, clipboard.writes)
                    assertEquals(OutgoingMessageStatus.FAILED, saved.listByOwner(owner).filterIsInstance<MessageRecord.Outgoing>().single { it.id == "s7-failed" }.status)
                    assertTrue(wire.requests.isEmpty()); assertEquals(2, saved.insertQueuedCalls)
                    assertTrue(keyboardFaults.isEmpty(), keyboardFaults.joinToString("\n"))
                }
            } finally { source.close(); runCurrent(); saved.close(); back.navigationEventDispatcher.dispose() }
        }
    }
}
// S7先行合同：真实source/store/原pointer菜单及合成clipboard，无外部业务；迁入不等于已运行RED。
// SMS根列表核同一onConversationAction与pending，不冒充持久化；气泡保留FAILED/草稿确认/失权守护。
// CustomActions及Polite语义合同不代表TalkBack听觉或Android实机通过。
