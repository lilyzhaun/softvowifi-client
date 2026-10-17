@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.ui

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.desktop.s4SmsHost
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.testing.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*

class S7CallStagePoliteRenderTest {
    @Test fun realStagesAndFailureArePoliteWithoutAnnouncingDurationTicks() = onMotionTestThread {
        val dispatcher = StandardTestDispatcher()
        runTest(dispatcher) {
            val f = CallAggregateFixture(backgroundScope.coroutineContext + dispatcher, onlyFirst = true)
            val token = "s7-polite-call"; val faults = mutableListOf<String>(); var callbacks = 0
            fun ready() = assertIs<CallUiState.Ready>(f.source.state.value)
            fun snapshot(revision: Long, elapsed: Long) {
                f.first.enqueueSnapshot(EngineProtocolFrame.Response(1, buildJsonObject {
                    put("epoch", "fixture-epoch"); put("revision", revision)
                    put("stage", "IN_CALL"); put("callId", token); put("direction", "INCOMING")
                    put("peer", "+155501"); put("confirmedElapsedMs", elapsed); put("dtmfSupported", false)
                }, null))
                f.source.refresh()
            }
            try {
                runCurrent(); val startupQueries = f.first.snapshotRequests.size
                assertTrue(startupQueries > 0, "前提：原fixture已真正读取初始权威快照")
                MotionTestEnvironment(testScheduler, 390, 844, dispatcher = dispatcher) {
                    s4SmsHost {
                        val state by f.source.state.collectAsState()
                        val audio by f.source.audioControls.collectAsState()
                        (state as? CallUiState.Ready)?.let {
                            CallScreenContent(it, "synthetic-line", audio,
                                onAction = { callbacks++ }, onMute = { callbacks++ }, onSpeaker = { callbacks++ },
                                onDigit = { callbacks++ }, onRestoreAudio = { callbacks++ }, onMinimize = { callbacks++ },
                                onReturn = { callbacks++ }, ticking = true)
                        }
                    }
                }.use { e ->
                    fun settle() { runCurrent(); s4Frames(e, 30) }
                    fun spoken(n: SemanticsNode): List<String> =
                        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                            listOfNotNull(n.config.getOrNull(SemanticsProperties.StateDescription))
                    fun tree(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::tree)
                    val durationPattern = Regex("""\d+:\d{2}(?::\d{2})?|计时|秒""")
                    fun hasDuration(n: SemanticsNode) = tree(n).any {
                        (spoken(it) + it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty())
                            .any(durationPattern::containsMatchIn)
                    }
                    fun region(label: String): Pair<Int, List<String>>? {
                        val service = s4Nodes(e, false)
                        assertTrue(service.any {
                            label in spoken(it) && it.boundsInRoot.width > 0 && it.boundsInRoot.height > 0 &&
                                it.boundsInRoot.top >= 0 && it.boundsInRoot.bottom <= 844
                        }, "前提：真实当前文字须在服务语义树可见：$label")
                        val emitters = service.filter {
                            label in spoken(it) && it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite
                        }.distinctBy { it.id }
                        if (emitters.size != 1) {
                            faults += "$label：须恰有一个Polite节点，实际${emitters.size}"; return null
                        }
                        val emitter = emitters.single(); val raw = s4Nodes(e, true)
                        val parents = mutableMapOf<Int, SemanticsNode>()
                        raw.forEach { p -> p.children.forEach { parents[it.id] = p } }
                        val original = assertNotNull(raw.singleOrNull { it.id == emitter.id })
                        if (hasDuration(original)) faults += "$label：播报子树混入计时"
                        if (spoken(emitter).any { it != label }) faults += "$label：播报区域混入非当前阶段／结果文字"
                        generateSequence(parents[original.id]) { parents[it.id] }.forEach {
                            if (it.config.contains(SemanticsProperties.LiveRegion) && hasDuration(it))
                                faults += "$label：上层LiveRegion包含计时，不能整Row播报"
                        }
                        return emitter.id to spoken(emitter).toList()
                    }
                    f.first.emitIncoming(token, "RINGING", peer = "+155501"); settle()
                    assertEquals(CallPhase.Ringing, ready().phase); val ringing = region("来电")
                    f.first.emitVersioned("incomingCall", token, "RINGING", "fixture-epoch", 1); settle()
                    assertEquals(CallPhase.Ringing, ready().phase)
                    assertEquals(ringing, region("来电"), "更高revision同阶段不能重建／改写播报")
                    f.first.emitVersioned("incomingCall", token, "IN_CALL", "fixture-epoch", 2); settle()
                    assertEquals(CallPhase.InCall, ready().phase); snapshot(3, 0); settle()
                    val firstDuration = assertNotNull(ready().clock).formatted()
                    assertTrue(s4Nodes(e, false).any { spoken(it).any { text -> firstDuration in text } },
                        "前提：原生产计时文字实际可见")
                    val inCall = region("通话中")
                    f.first.emitVersioned("incomingCall", token, "IN_CALL", "fixture-epoch", 4); settle()
                    assertEquals(inCall, region("通话中"))
                    snapshot(5, 5_000); settle(); e.advanceFrameBy(1_000)
                    val nextDuration = assertNotNull(ready().clock).formatted()
                    assertNotEquals(firstDuration, nextDuration, "前提：真实可信计时必须确实改变")
                    assertTrue(s4Nodes(e, false).any { spoken(it).any { text -> nextDuration in text } })
                    assertEquals(inCall, region("通话中"), "计时改变不能刷新阶段播报节点／语义")
                    f.first.emitVersioned("incomingCall", token, "ENDED", "fixture-epoch", 6, reason = "failed"); settle()
                    assertEquals(CallPhase.Idle, ready().phase)
                    assertEquals(CallTerminalKind.FAILED, assertNotNull(ready().terminal).kind)
                    assertEquals(CallFailureMessages.NETWORK, ready().operationMessage)
                    val ended = region("通话已结束"); val failure = region(CallFailureMessages.NETWORK)
                    s4Frames(e, 30)
                    assertEquals(ended, region("通话已结束")); assertEquals(failure, region(CallFailureMessages.NETWORK))
                    assertEquals(0, callbacks)
                    assertTrue(f.first.requests.isEmpty(), "严禁answer/dial/hangup/DTMF等业务请求")
                    assertTrue(f.first.snapshotRequests.size >= startupQueries + 2,
                        "须实际执行两次可信refresh；正常只读轮询不算业务请求")
                    assertTrue(f.first.snapshotRequests.size >= 2 && f.first.snapshotRequests.all { it.method == "getCallSnapshot" })
                    assertTrue((f.second.requests + f.second.snapshotRequests).isEmpty())
                    assertEquals(1, f.audio.starts.size)
                    assertTrue(faults.isEmpty(), faults.distinct().joinToString("\n"))
                }
            } finally { f.source.close(); runCurrent(); f.repository.close() }
        }
    }
}
