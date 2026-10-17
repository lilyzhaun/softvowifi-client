@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

// S6先行源合同：复用EngineCallSourceTestSupport/CallAggregateFixture，是否通过以真实运行回执为准。
// 全部输入来自真实source公开动作和原fake transport，不构造CallUiState、不写session/authority。
// 当前旧API只定位wire FAILED安全解释/聚合归属缺失；dial RPC拒绝仍是操作反馈，不当作已绑定Ended。
// typed增量核原binding、同Idle保留/新call清理与明确wire reason；无token操作失败不伪造结束身份。
class S6TerminalContractPreparationTest {
    @Test fun realSyncRecoveryAndDialRejectionStayDistinctFromWireFailureAndNewRinging() =
        runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
            val transport = FakeCallTransport().apply {
                enqueueSnapshot(protocolError(2006, "synthetic snapshot unavailable"))
            }
            val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
            try {
                runCurrent()
                val unavailable = assertIs<CallUiState.Ready>(source.state.value)
                assertEquals(CallSyncState.Unavailable, unavailable.sync)
                assertEquals(CallFailureMessages.SNAPSHOT, unavailable.operationMessage)
                source.refresh(); runCurrent()
                val recovered = assertIs<CallUiState.Ready>(source.state.value)
                assertEquals(CallSyncState.Ready, recovered.sync)
                assertEquals(CallPhase.Idle, recovered.phase)
                assertNull(recovered.operationMessage, "PR261：权威Idle清同步提示，不是一次真实call失败")

                transport.enqueue(protocolError(2001, "synthetic dial rejection"))
                source.dial(assertNotNull(recovered.binding), CALL_NUMBER); runCurrent()
                val rejected = assertIs<CallUiState.Ready>(source.state.value)
                assertEquals(listOf("dial"), transport.requests.map { it.method })
                assertEquals(CallPhase.Idle, rejected.phase)
                assertEquals(CallSyncState.Ready, rejected.sync)
                assertEquals(CallFailureMessages.BUSY, rejected.operationMessage)
                source.refresh(); runCurrent()
                assertEquals(CallFailureMessages.BUSY, assertIs<CallUiState.Ready>(source.state.value).operationMessage,
                    "真实dial RPC拒绝的操作提示不能被当同步提示删除；它不是wire Ended")

                for (reason in listOf("remote", "canceled", "failed")) {
                    val token = "s6-terminal-$reason"
                    transport.emitIncoming(token, "RINGING"); runCurrent()
                    val ringing = assertIs<CallUiState.Ready>(source.state.value)
                    assertEquals(CallPhase.Ringing, ringing.phase)
                    val original = assertNotNull(ringing.binding)
                    assertEquals(CALL_ICCID_A, original.owner); assertEquals(token, original.token)
                    assertNull(ringing.operationMessage, "新Ringing不能继承前一拒绝/终态文案")
                    transport.emitCallEnded(token, reason); runCurrent()
                    val ended = assertIs<CallUiState.Ready>(source.state.value)
                    assertEquals(CallPhase.Idle, ended.phase); assertEquals(CallSyncState.Ready, ended.sync)
                    assertEquals(CALL_ICCID_A, assertNotNull(ended.binding).owner)
                    // 现API明确缺口：失败reason不能像正常remote/canceled一样全部清空；不从自由文案反向分类。
                    val expected = if (reason == "failed") CallFailureMessages.NETWORK else null
                    assertEquals(expected, ended.operationMessage, "wire ${reason}须有确定安全解释，而非只在history保留reason")
                    source.refresh(); runCurrent()
                    assertEquals(expected, assertIs<CallUiState.Ready>(source.state.value).operationMessage)
                    assertEquals(listOf("dial"), transport.requests.map { it.method }, "结束及轮询不得另发业务动作")
                }
                transport.emitIncoming(CALL_OTHER, "RINGING"); runCurrent()
                val next = assertIs<CallUiState.Ready>(source.state.value)
                assertEquals(CallPhase.Ringing, next.phase); assertEquals(CALL_OTHER, assertNotNull(next.binding).token)
                assertNull(next.operationMessage, "新call清旧终态；typed字段的清理由后继合同另验")
            } finally { source.close(); runCurrent() }
        }

    @Test fun selectedBDoesNotEraseTheActuallyPresentedAFailureOrChangeTheOutgoingSelection() =
        runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
            val fixture = CallAggregateFixture(coroutineContext)
            try {
                runCurrent(); fixture.source.selectLine(OWNER_B); runCurrent()
                val outgoingB = assertNotNull(fixture.source.lines.value.outgoingBinding)
                assertEquals(OWNER_B, fixture.source.lines.value.selectedOwner)
                assertEquals(CallAggregateFixture.ICCID_B, outgoingB.owner)
                fixture.first.emitIncoming(CALL_TOKEN, "RINGING"); runCurrent()
                val ringingA = assertIs<CallUiState.Ready>(fixture.source.state.value)
                assertEquals(CallPhase.Ringing, ringingA.phase)
                val originalA = assertNotNull(ringingA.binding)
                assertEquals(CallAggregateFixture.ICCID_A, originalA.owner)
                val childA = assertNotNull(fixture.source.resolve(originalA))
                assertSame(childA.state.value, fixture.source.state.value)
                assertEquals(OWNER_B, fixture.source.lines.value.selectedOwner)

                fixture.first.emitCallEnded(CALL_TOKEN, "failed"); runCurrent()
                val endedA = assertIs<CallUiState.Ready>(childA.state.value)
                val after = assertIs<CallUiState.Ready>(fixture.source.state.value)
                assertEquals(CallPhase.Idle, endedA.phase); assertEquals(CallPhase.Idle, after.phase)
                assertEquals(CallAggregateFixture.ICCID_A, assertNotNull(endedA.binding).owner)
                assertEquals(CallOutcome.FAILED, fixture.repository.listByOwner(OWNER_A).single().outcome,
                    "前提：真实事件已写A失败历史，不能把聚合呈现丢失说成事件未发生")
                assertTrue(fixture.repository.listByOwner(OWNER_B).isEmpty())
                assertEquals(OWNER_B, fixture.source.lines.value.selectedOwner)
                assertSame(outgoingB, fixture.source.lines.value.outgoingBinding, "A结束不得自动更改B的外呼选择/授权")
                assertTrue(fixture.first.requests.isEmpty() && fixture.second.requests.isEmpty())
                // 当前旧API定位Aggregate:107–113丢失A；等价typed终态附带A的方案后继应核结束binding归属，
                // 不能将presentation的归属误用为lines.selectedOwner，也不能手填A状态假通过。
                assertEquals(originalA.owner, assertNotNull(after.binding).owner,
                    "S6旧API呈现RED：A原presented刚结束，不能在交付终态前被B Idle覆盖")
                assertEquals(CallFailureMessages.NETWORK, after.operationMessage, "A实际wire failed须到达Calls/Activity投影")
                val terminalA = assertNotNull(after.terminal)
                assertSame(originalA, terminalA.binding)
                assertEquals(CallTerminalKind.FAILED, terminalA.kind)
                assertSame(terminalA, endedA.terminal)

                fixture.second.emitIncoming(CALL_OTHER, "RINGING"); runCurrent()
                val next = assertIs<CallUiState.Ready>(fixture.source.state.value)
                assertEquals(CallPhase.Ringing, next.phase)
                val nextBinding = assertNotNull(next.binding)
                assertEquals(CallAggregateFixture.ICCID_B, nextBinding.owner)
                assertEquals(CALL_OTHER, nextBinding.token); assertNull(next.operationMessage)
                assertNull(next.terminal)
                assertEquals(OWNER_B, fixture.source.lines.value.selectedOwner)
            } finally { fixture.source.close(); runCurrent() }
        }

    @Test fun boundInCallTerminalRetainsOriginalAuthorityAcrossIdleAndClearsForTheNextCall() =
        runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
            val transport = FakeCallTransport()
            val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
            try {
                runCurrent()
                assertNull(assertIs<CallUiState.Ready>(source.state.value).terminal, "首屏Idle不是结束")
                transport.emitIncoming(CALL_TOKEN, "RINGING"); runCurrent()
                transport.emitIncoming(CALL_TOKEN, "IN_CALL"); runCurrent()
                val original = assertNotNull(assertIs<CallUiState.Ready>(source.state.value).binding)
                transport.emitCallEnded(CALL_TOKEN, "failed"); runCurrent()
                val ended = assertIs<CallUiState.Ready>(source.state.value)
                val terminal = assertNotNull(ended.terminal)
                assertEquals(CallPhase.Idle, ended.phase)
                assertSame(original, terminal.binding)
                assertTrue(original.sameCall(terminal.binding))
                assertEquals(original.owner, terminal.binding.owner)
                assertEquals(original.generation, terminal.binding.generation)
                assertEquals(original.epoch, terminal.binding.epoch)
                assertEquals(CALL_TOKEN, terminal.binding.token)
                assertEquals(CallTerminalKind.FAILED, terminal.kind)
                assertEquals(CallFailureMessages.NETWORK, terminal.message)
                source.refresh(); runCurrent()
                assertSame(terminal, assertIs<CallUiState.Ready>(source.state.value).terminal)
                transport.emitIncoming(CALL_OTHER, "RINGING"); runCurrent()
                val next = assertIs<CallUiState.Ready>(source.state.value)
                assertNull(next.terminal)
                assertFalse(original.sameCall(assertNotNull(next.binding)))
                transport.emitCallEnded(CALL_TOKEN, "failed"); runCurrent()
                assertSame(next, source.state.value, "既有旧token保护不能被终态实现改变")
                assertTrue(transport.requests.isEmpty())
            } finally { source.close(); runCurrent() }
        }

    @Test fun incomingTypedReasonsUseFixedFailureMessagesAndNormalReasonsStayNormal() =
        runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
            val transport = FakeCallTransport()
            val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
            try {
                runCurrent()
                val cases = listOf("timeout" to CallTerminalKind.TIMEOUT, "rejected" to CallTerminalKind.REJECTED,
                    "remote" to CallTerminalKind.NORMAL, "hangup" to CallTerminalKind.NORMAL, "canceled" to CallTerminalKind.NORMAL)
                for ((reason, kind) in cases) {
                    val token = "s6-typed-$reason"
                    transport.emitIncoming(token, "RINGING"); runCurrent()
                    val original = assertNotNull(assertIs<CallUiState.Ready>(source.state.value).binding)
                    transport.emitIncoming(token, "ENDED", reason = reason); runCurrent()
                    val ended = assertIs<CallUiState.Ready>(source.state.value)
                    val terminal = assertNotNull(ended.terminal)
                    assertEquals(CallPhase.Idle, ended.phase); assertSame(original, terminal.binding)
                    assertEquals(kind, terminal.kind)
                    val message = when (reason) {
                        "timeout" -> CallFailureMessages.NO_ANSWER
                        "rejected" -> CallFailureMessages.BUSY
                        else -> null
                    }
                    assertEquals(message, terminal.message); assertEquals(message, ended.operationMessage)
                }
                assertTrue(transport.requests.isEmpty())
            } finally { source.close(); runCurrent() }
        }

    @Test fun newDialClearsPreviousTerminalAndNoTokenRpcRejectionDoesNotCreateAnother() =
        runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
            val transport = FakeCallTransport()
            val source = sourceOf(listOf(callRunning(CALL_ICCID_A)), transport)
            try {
                runCurrent()
                transport.emitIncoming(CALL_TOKEN, "RINGING"); runCurrent()
                transport.emitCallEnded(CALL_TOKEN, "failed"); runCurrent()
                val previous = assertNotNull(assertIs<CallUiState.Ready>(source.state.value).terminal)
                val expected = assertNotNull(assertIs<CallUiState.Ready>(source.state.value).binding)
                val gate = transport.enqueueGate()
                transport.enqueue(protocolError(2001, "synthetic dial rejection"))
                source.dial(expected, CALL_NUMBER); runCurrent()
                val dialing = assertIs<CallUiState.Ready>(source.state.value)
                assertEquals(CallPhase.Dialing, dialing.phase)
                assertNull(dialing.terminal); assertNull(assertNotNull(dialing.binding).token)
                assertFalse(previous.binding.sameCall(assertNotNull(dialing.binding)))
                gate.complete(Unit); runCurrent()
                val rejected = assertIs<CallUiState.Ready>(source.state.value)
                assertEquals(CallPhase.Idle, rejected.phase)
                assertEquals(CallFailureMessages.BUSY, rejected.operationMessage)
                assertNull(rejected.terminal, "RPC拒绝仅操作反馈，不能伪造有token的结束授权")
                source.refresh(); runCurrent()
                assertNull(assertIs<CallUiState.Ready>(source.state.value).terminal)
                assertEquals(listOf("dial"), transport.requests.map { it.method })
            } finally { source.close(); runCurrent() }
        }

    @Test fun epochOwnerReplacementAndDetachDiscardOldTerminalWithoutInventingAnIdleEnd() =
        runTest(UnconfinedTestDispatcher(), timeout = 10.seconds) {
            val transport = FakeCallTransport()
            var instances = listOf(callRunning(CALL_ICCID_A))
            val source = sourceOf(instances, transport, listInstances = { instances })
            try {
                runCurrent()
                transport.emitIncoming(CALL_TOKEN, "RINGING"); runCurrent()
                transport.emitCallEnded(CALL_TOKEN, "failed"); runCurrent()
                val old = assertNotNull(assertIs<CallUiState.Ready>(source.state.value).terminal)
                transport.enqueueSnapshot(EngineProtocolFrame.Response(1, buildJsonObject {
                    put("epoch", "s6-new-epoch"); put("revision", 1); put("stage", "IDLE")
                    put("confirmedElapsedMs", 0); put("dtmfSupported", false)
                }, null))
                source.refresh(); runCurrent()
                val idle = assertIs<CallUiState.Ready>(source.state.value)
                assertNull(idle.terminal); assertEquals(CallPhase.Idle, idle.phase)
                assertEquals("s6-new-epoch", assertNotNull(idle.binding).epoch)
                transport.emitIncoming(CALL_OTHER, "RINGING"); runCurrent()
                transport.emitCallEnded(CALL_OTHER, "failed"); runCurrent()
                assertNotNull(assertIs<CallUiState.Ready>(source.state.value).terminal)
                instances = listOf(callRunning(CALL_ICCID_B)); source.refresh(); runCurrent()
                val changed = assertIs<CallUiState.Ready>(source.state.value)
                assertEquals(CALL_ICCID_B, assertNotNull(changed.binding).owner)
                assertNull(changed.terminal); assertFalse(old.binding.sameCall(assertNotNull(changed.binding)))
                instances = emptyList(); source.refresh(); runCurrent()
                assertIs<CallUiState.Unavailable>(source.state.value)
                instances = listOf(callRunning(CALL_ICCID_B)); source.refresh(); runCurrent()
                assertNull(assertIs<CallUiState.Ready>(source.state.value).terminal)
                assertTrue(transport.requests.isEmpty())
            } finally { source.close(); runCurrent() }
        }
}
