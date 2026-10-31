package com.vowifi.sw

import android.content.Context
import android.telephony.SubscriptionManager
import com.vowifi.sw.manager.data.AndroidEngineControlSource
import com.vowifi.sw.manager.data.SimControlSource
import com.vowifi.sw.manager.model.*
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder

internal class HomeEngineControlHostFixture(firstRunning: Boolean = true, withProgress: Boolean = false) : AutoCloseable {
    val dispatcher = HomeControlDispatcher()
    val context = RuntimeEnvironment.getApplication()
    private val running = mutableMapOf(OWNER_A to firstRunning, OWNER_B to true)
    val requests = mutableListOf<Pair<String, String>>()
    val statusOverrides = mutableMapOf<String, JsonObject>()
    private val progressStreams = mutableMapOf<String, MutableSharedFlow<Any>>()
    private val progressConnections = mutableMapOf<String, Any>()
    val commands = mutableListOf<Pair<String, String?>>()
    val removed = mutableListOf<String>()
    var backendCloses = 0
    private var inFlight = 0
    var maxInFlight = 0
    var statusGate: CompletableDeferred<Unit>? = null
    var listGate: CompletableDeferred<Unit>? = null
    var startGate: CompletableDeferred<Unit>? = null
    var stopGate: CompletableDeferred<Unit>? = null
    var shellStopFailureOwner: String? = null
    val statusEntered = CompletableDeferred<Unit>()
    val listEntered = CompletableDeferred<Unit>()
    val startEntered = CompletableDeferred<Unit>()
    val stopEntered = CompletableDeferred<Unit>()
    val engine: AndroidEngineControlSource
    val sims: SimControlSource

    init {
        subscriptions(includeFirst = true)
        val shell: suspend (String, String?) -> String = ::shell
        val rpc: suspend (String, String) -> EngineProtocolFrame.Response = ::rpc
        val retire: (String) -> Unit = { removed += it }
        val close: () -> Unit = { backendCloses++ }
        val capabilities: suspend (String) -> ClientCapabilitiesResult = {
            ClientCapabilitiesResult.Unavailable(ClientCapabilitiesFailure.Unsupported)
        }
        engine = if (withProgress) {
            val events: (String) -> SharedFlow<Any> = { progressStreams.getOrPut(it) { MutableSharedFlow(extraBufferCapacity = 16) } }
            AndroidEngineControlSource::class.java.declaredConstructors.single { it.parameterCount == 7 }
                .apply { isAccessible = true }.newInstance(shell, rpc, retire, close, capabilities, events, dispatcher) as AndroidEngineControlSource
        } else AndroidEngineControlSource::class.java.declaredConstructors.single { it.parameterCount == 6 }
            .apply { isAccessible = true }.newInstance(shell, rpc, retire, close, capabilities, dispatcher) as AndroidEngineControlSource
        val inventoryType = Class.forName("com.vowifi.sw.manager.data.AndroidSubscriptionInventory")
        val inventory = inventoryType.getConstructor(Context::class.java).newInstance(context)
        val handlesType = Class.forName("com.vowifi.sw.manager.data.OpaqueIccidHandleStore")
        val handles = handlesType.getConstructor().newInstance()
        sims = Class.forName("com.vowifi.sw.manager.data.DefaultSimControlSource").constructors.single()
            .newInstance(inventory, engine, handles, dispatcher) as SimControlSource
        dispatcher.runCurrent()
        assertEquals(2, ready().instances.size)
        assertTrue(row(1).canStop)
    }

    fun ready() = sims.state.value as SimControlUiState.Ready
    fun row(slot: Int) = ready().instances.single { it.slotIndex == slot }

    fun progressConnection(owner: String) = progressConnections.getOrPut(owner) { Any() }
    fun replaceProgressConnection(owner: String): Any = Any().also { progressConnections[owner] = it }
    fun emitProgress(owner: String, sequence: Long, tunnel: String, ims: String, tunnelStep: String = "", imsStep: String = "",
        connection: Any = progressConnection(owner)) {
        val frame = EngineProtocolFrame.Event("connectionProgress", buildJsonObject {
            put("sequence", sequence); put("tunnel", tunnel); put("ims", ims)
            put("tunnelProgress", tunnelStep); put("imsProgress", imsStep)
        })
        val current: () -> Boolean = { progressConnections[owner] === connection }
        val event = Class.forName("com.vowifi.sw.manager.data.EngineConnectionProgressEvent").declaredConstructors
            .single { it.parameterCount == 3 }.apply { isAccessible = true }.newInstance(frame, connection, current)
        assertTrue(progressStreams.getValue(owner).tryEmit(event))
        dispatcher.runCurrent()
    }

    fun homeAction(slot: Int) {
        val type = Class.forName("com.vowifi.sw.manager.ui.HomePresentationKt")
        val presentation = type.getDeclaredMethod("homeSimAction", SimControlInstance::class.java)
            .apply { isAccessible = true }.invoke(null, row(slot))
        val action = presentation.javaClass.getMethod("getAction").invoke(presentation)
        assertEquals(true, action.javaClass.getMethod("getEnabled").invoke(action))
        val start: (SimHandle) -> Unit = sims::start
        val stop: (SimHandle) -> Unit = sims::stop
        val provision: (SimHandle) -> Unit = { error("unexpected provision action") }
        type.methods.single { it.name == "dispatchHomeAction" }.invoke(null, action, start, stop, provision)
    }

    fun subscriptions(includeFirst: Boolean) {
        val owners = if (includeFirst) listOf(OWNER_A, OWNER_B) else listOf(OWNER_B)
        val rows = owners.map { owner ->
            val slot = if (owner == OWNER_A) 0 else 1
            SubscriptionInfoBuilder.newBuilder().setId(slot + 1).setSimSlotIndex(slot)
                .setIccId(owner).setCarrierName("Synthetic").buildSubscriptionInfo()
        }
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfoList(rows)
    }

    fun assertBPreserved() {
        assertEquals(EngineLifecycle.RUNNING, row(1).lifecycle)
        assertEquals(202L, row(1).pid)
        assertEquals("REGISTERED", row(1).runtime?.ims)
        assertFalse(commands.any { it.second == OWNER_B && it.first != "list" })
        assertFalse(requests.any { it.first == OWNER_B && it.second == "stop" })
        assertFalse(OWNER_B in removed)
        assertEquals(1, maxInFlight)
    }

    private suspend fun shell(command: String, owner: String?): String = io {
        commands += command to owner
        when (command) {
            "list" -> {
                listGate?.also { listGate = null; listEntered.complete(Unit) }?.await()
                buildJsonArray {
                    running.forEach { (key, active) -> add(buildJsonObject {
                        put("ok", true); put("iccid", key); put("state", if (active) "running" else "stopped")
                        if (active) put("pid", if (key == OWNER_A) 101 else 202)
                    }) }
                }.toString()
            }
            "start" -> {
                startGate?.also { startGate = null; startEntered.complete(Unit) }?.await()
                running[requireNotNull(owner)] = true
                "{}"
            }
            "stop" -> {
                if (owner == shellStopFailureOwner) throw IOException("synthetic stop failure")
                running[requireNotNull(owner)] = false
                "{}"
            }
            else -> error("unexpected control command")
        }
    }

    private suspend fun rpc(owner: String, method: String): EngineProtocolFrame.Response = io {
        requests += owner to method
        when (method) {
            "getStatus" -> {
                statusGate?.also { statusGate = null; statusEntered.complete(Unit) }?.await()
                EngineProtocolFrame.Response(1, statusOverrides[owner] ?: buildJsonObject {
                    put("tunnel", "CONNECTED"); put("reconnects", 0); put("ims", "REGISTERED")
                    put("call", "IDLE"); put("transport", "tcp"); put("pcscf", buildJsonArray {})
                }, null)
            }
            "stop" -> {
                stopGate?.also { stopGate = null; stopEntered.complete(Unit) }?.await()
                EngineProtocolFrame.Response(1, buildJsonObject {}, null)
            }
            else -> error("unexpected control request")
        }
    }

    private suspend fun <Result> io(block: suspend () -> Result): Result {
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        return try { block() } finally { inFlight-- }
    }

    override fun close() { sims.close(); engine.close(); dispatcher.runCurrent() }

    companion object {
        const val OWNER_A = "8900000000000000001"
        const val OWNER_B = "8900000000000000002"
    }
}

internal class HomeControlDispatcher : CoroutineDispatcher() {
    private val tasks = ConcurrentLinkedQueue<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
    fun runNext() { checkNotNull(tasks.poll()) { "missing control task" }.run() }
    fun runCurrent() {
        repeat(1000) { (tasks.poll() ?: return).run() }
        error("control test dispatcher did not settle")
    }
}
