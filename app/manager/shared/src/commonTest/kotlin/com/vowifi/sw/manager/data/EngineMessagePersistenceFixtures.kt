package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineInstanceState
import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineProtocolError
import com.vowifi.sw.manager.model.EngineProtocolFrame
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object EngineMessagePersistenceFixtures {
    suspend fun sourceOf(
        instances: List<EngineInstanceState>,
        transport: FakeEngineMessageTransport,
        repository: MessageRepository = FakeMessageRepository(),
    ): EngineMessageSource = EngineMessageSource(
        runtime = EngineMessageRuntime(
            listInstances = { instances },
            transportFor = { transport },
            clock = { NOW_MS },
        ),
        parentContext = kotlin.coroutines.coroutineContext,
        repository = repository,
        ownerIdFor = ::ownerFor,
        nextMessageId = sequentialIds(),
    )

    fun sendOk(smsId: String, statusCode: Int, reference: Int): EngineProtocolFrame.Response =
        EngineProtocolFrame.Response(
            id = 20,
            result = buildJsonObject {
                put("smsId", smsId)
                put("statusCode", statusCode)
                put("reference", reference)
            },
            error = null,
        )

    fun sendError(code: Int, message: String): EngineProtocolFrame.Response =
        EngineProtocolFrame.Response(
            id = 21,
            result = null,
            error = EngineProtocolError(code, message),
        )

    fun running(iccid: String) = EngineInstanceState(iccid, EngineLifecycle.RUNNING, pid = 7)
    fun stopped(iccid: String) = EngineInstanceState(iccid, EngineLifecycle.STOPPED)

    const val SYNTH_NUMBER = "+155501"
    const val SYNTH_PEER = "+155502"
    const val SYNTH_TEXT = "synth-one"
    const val MO_ID = "mo-synth-1"
    const val MT_ID = "mt-synth-1"
    const val ICCID_A = "8986001"
    const val ICCID_B = "8986002"
    const val NOW_MS = 1_700_000_000_000L
}
