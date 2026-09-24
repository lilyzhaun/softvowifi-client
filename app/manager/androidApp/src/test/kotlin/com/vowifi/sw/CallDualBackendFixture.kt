package com.vowifi.sw

import android.app.Application
import android.net.LocalSocket
import android.os.Looper
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

internal class CallDualBackendFixture {
    val context: Application = RuntimeEnvironment.getApplication()
    val ownerA = SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43))!!
    val ownerB = SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43))!!
    val first = CallDualSocketPeer()
    val second = CallDualSocketPeer()
    val microphone = CallMicrophoneLease()
    val audioEvents = MutableSharedFlow<CallAudioEvent>(extraBufferCapacity = 4)
    val starts = CopyOnWriteArrayList<CallAudioSession>()
    val controls = MutableStateFlow(CallAudioControls())
    private val databaseType = Class.forName("com.vowifi.sw.manager.data.SoftVoWiFiCallDatabase").asSubclass(RoomDatabase::class.java)
    private val database = Room.inMemoryDatabaseBuilder(context, databaseType).setDriver(AndroidSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(1)).build()
    val repository = Class.forName("com.vowifi.sw.manager.data.AndroidCallHistoryRepository")
        .getConstructor(databaseType).newInstance(database) as CallHistoryRepository
    @Volatile var instances = listOf(EngineInstanceState(ICCID_A, EngineLifecycle.RUNNING, 1), EngineInstanceState(ICCID_B, EngineLifecycle.RUNNING, 2))
    @Volatile var activeRoster: List<String?>? = listOf(ICCID_A, ICCID_B)
    @Volatile var rosterFailure = false
    val source = CallSourceAggregate(
        EngineMessageRuntime({ instances }, { owner -> AndroidCallEventTransport(owner, peer(owner)::socket) },
            { 1000 }, { check(!rosterFailure) { "synthetic roster failure" }; activeRoster }),
        object : CallAudioController {
            override val events = audioEvents
            override suspend fun start(session: CallAudioSession) {
                check(microphone.permits(session))
                starts += session
            }
            override fun stop() = Unit
        }, CallControlTransport { owner, method, params, timeout ->
            AndroidCallControlTransport(peer(owner)::socket).request(owner, method, params, timeout)
        }, Dispatchers.Unconfined, repository,
        { if (it == ICCID_A) ownerA else ownerB }, { "host-outgoing" }, { if (it == ICCID_A) "SIM 1" else "SIM 2" },
    )
    val lifecycle = AndroidCallLifecycle(context, source, microphone, {}, controls)
    private fun peer(owner: String) = if (owner == ICCID_A) first else second
    fun ready() = source.state.value as? CallUiState.Ready
    suspend fun connected() = await { source.lines.value.choices.count { it.syncReady } == 2 }
    fun close() { lifecycle.close(); source.close(); first.close(); second.close(); repository.close() }
    companion object {
        const val ICCID_A = "8900000000000000001"
        const val ICCID_B = "8900000000000000002"
        suspend fun await(predicate: suspend () -> Boolean) = withTimeout(8_000) {
            while (!predicate()) { shadowOf(Looper.getMainLooper()).idle(); delay(10) }
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}

internal class CallDualSocketPeer {
    val sockets = CopyOnWriteArrayList<CallTestSocket>()
    val commands = CopyOnWriteArrayList<String>()
    val dialRequest = CompletableDeferred<JsonObject>()
    @Volatile var snapshotError: Int? = null
    @Volatile var stage = "IDLE"
    @Volatile var direction = "INCOMING"
    @Volatile var revision = 0
    val token = "host-shared-token"
    fun socket(): LocalSocket = CallTestSocket(::receive).also(sockets::add)
    private fun receive(socket: CallTestSocket, line: String) {
        val request = Json.parseToJsonElement(line).jsonObject
        val id = request.getValue("id").jsonPrimitive.long
        when (val method = request.getValue("method").jsonPrimitive.content) {
            "hello" -> socket.respond(id, buildJsonObject { put("version", 1) })
            "getCallSnapshot" -> if (snapshotError != null) socket.send(buildJsonObject {
                put("id", id)
                put("error", buildJsonObject { put("code", snapshotError); put("message", "synthetic snapshot failure") })
            }) else socket.respond(id, buildJsonObject {
                put("epoch", "host-epoch"); put("revision", revision); put("stage", stage)
                put("confirmedElapsedMs", 0); put("dtmfSupported", stage == "IN_CALL")
                if (stage != "IDLE") { put("callId", token); put("direction", direction) }
            })
            "dial" -> {
                dialRequest.complete(request.getValue("params").jsonObject)
                commands += method; stage = "IN_CALL"; direction = "OUTGOING"; revision++
                socket.respond(id, buildJsonObject { put("callId", token); put("state", "IN_CALL") })
            }
            "answer" -> {
                commands += method
                socket.respond(id, buildJsonObject { put("callId", token); put("state", "RINGING") })
            }
            "reject", "hangup" -> {
                commands += method; stage = "IDLE"; revision++
                socket.respond(id, buildJsonObject { put("state", "IDLE") })
            }
            else -> error("unexpected call test method")
        }
    }
    fun incoming(next: String, reason: String? = null) {
        stage = if (next == "ENDED") "IDLE" else next
        direction = "INCOMING"; revision++
        val event = buildJsonObject {
            put("event", "incomingCall")
            put("data", buildJsonObject {
                put("callId", token); put("state", next); put("epoch", "host-epoch"); put("revision", revision)
                if (reason != null) put("reason", reason)
            })
        }
        sockets.filterNot { it.closed }.forEach { it.send(event) }
    }
    fun close() { sockets.forEach { it.close() } }
}
