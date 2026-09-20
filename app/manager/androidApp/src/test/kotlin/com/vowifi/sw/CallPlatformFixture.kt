package com.vowifi.sw

import android.content.Context
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.robolectric.util.ReflectionHelpers

internal class CallPlatformFixture(var stage: String = "IDLE") {
    var token = "synthetic-call"
    var epoch = "synthetic-epoch"
    var revision = 1
    val commands = mutableListOf<String>()
    val microphone = CallMicrophoneLease()
    val controls = MutableStateFlow(CallAudioControls())
    private val dialGate = CompletableDeferred<Unit>()
    val primary = object : EngineMessageTransport {
        override val events = MutableSharedFlow<EngineProtocolFrame.Event>()
        override suspend fun request(method: String, params: JsonObject, timeoutMillis: Long): EngineProtocolFrame.Response {
            commands += method
            if (method == "dial") dialGate.await()
            return EngineProtocolFrame.Response(1, null, EngineProtocolError(2005, "canceled"))
        }
    }
    val source = EngineCallSource.connect(
        EngineMessageRuntime({ listOf(EngineInstanceState("synthetic-owner", EngineLifecycle.RUNNING, 1)) }, { primary }, { 0 }),
        object : CallAudioController {
            override val events = MutableSharedFlow<CallAudioEvent>()
            override suspend fun start(session: CallAudioSession) { check(microphone.permits(session)) }
            override fun stop() = Unit
        }, CallControlTransport { _, method, _, _ ->
            if (method == "getCallSnapshot") snapshot() else {
                commands += method
                EngineProtocolFrame.Response(1, buildJsonObject { put("state", "IDLE") }, null)
            }
        }, Dispatchers.Unconfined,
    )
    fun lifecycle(context: Context) = AndroidCallLifecycle(context, source, microphone, {}, controls)
    fun snapshot() = EngineProtocolFrame.Response(1, buildJsonObject {
        put("epoch", epoch); put("revision", revision); put("stage", stage)
        put("confirmedElapsedMs", if (stage == "IN_CALL") 12000 else 0); put("dtmfSupported", stage == "IN_CALL")
        if (stage != "IDLE") { put("callId", token); put("direction", "INCOMING"); put("peer", "+12025550123") }
    }, null)
    fun facade(context: Context): AndroidCallSource = shell(AndroidCallSource::class.java).also {
        ReflectionHelpers.setField(it, "delegate", source)
        ReflectionHelpers.setField(it, "state", source.state)
        ReflectionHelpers.setField(it, "history", source.history)
        ReflectionHelpers.setField(it, "audioControls", controls)
        ReflectionHelpers.setField(it, "lifecycle", lifecycle(context))
    }
    fun coordinator(context: Context) = AndroidIncomingCallNotificationCoordinator(context, source.state,
        IncomingCallNotificationCallbacks({}, {}), IncomingCallActionReceiver::class.java, CallActivity::class.java, source::act)
    fun close() { source.close(); dialGate.complete(Unit) }
    private fun <T : Any> shell(type: Class<T>): T = type.cast(sun.reflect.ReflectionFactory.getReflectionFactory()
        .newConstructorForSerialization(type, Any::class.java.getDeclaredConstructor()).newInstance())!!.also { instance ->
        type.declaredMethods.singleOrNull { it.name == "\$\$robo\$init" }?.let { method ->
            method.isAccessible = true
            method.invoke(instance)
        }
    }
}
