@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.ConfigurationFact
import android.telephony.SubscriptionManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [AutomaticSmscCommandShadow::class, RootFrameworkShadow::class], instrumentedPackages = ["com.vowifi.sw.manager.data"])
class ConfigurationRuntimeFactsHostTest {
    @Test fun aSubscriptionRemovedDuringTheReadCannotReceiveLateRuntimeFacts() = runBlocking {
        var remove = false
        AutomaticSmscCommandShadow.running = true
        S3ConfigurationIoFixture(runtimeRead = {
            if (remove) shadowOf(RuntimeEnvironment.getApplication().getSystemService(SubscriptionManager::class.java))
                .setActiveSubscriptionInfoList(emptyList())
            ConfigurationFactsCodec.runtime(status(buildJsonObject {
                put("configRevision", "r1_abcdefghijklmnopabcdefghijklmnop"); put("sessionSeconds", 300); put("sessionRefresher", "remote")
            }))
        }).use { fixture ->
            fixture.ready()
            remove = true
            fixture.source.refreshAndAwait()
            val rows = (fixture.source.state.value as? ConfigurationUiState.Ready)?.instances.orEmpty()
            assertTrue(rows.all { it.runtimeFacts == null })
            assertTrue(S3ConfigurationCommandShadow.commands.none { " config apply " in it || " start " in it })
        }
    }

    @Test fun actualCatalogPublishesFreshRuntimeFactsAndDoesNotKeepThemAfterFailureOrStop() = runBlocking {
        var reads = 0
        var facts = ConfigurationFactsCodec.runtime(status())
        AutomaticSmscCommandShadow.running = true
        S3ConfigurationIoFixture(runtimeRead = { key -> assertEquals(S3_CONFIG_TARGET, key); reads++; facts },
            smscRead = { error("Runtime facts must remain the sole authority when present") }).use { fixture ->
            fixture.ready()
            fun row() = (fixture.source.state.value as ConfigurationUiState.Ready).instances.single { it.instanceId == fixture.targetId }
            assertEquals(600L, row().runtimeFacts?.registrationSeconds)
            val known = checkNotNull(facts)
            S3ConfigurationCommandShadow.smsc = ""
            facts = known.copy(loaded = known.loaded.copy(fields = listOf(ConfigurationFact("smsc", "+19995550123", "user", "+19995550123"))))
            fixture.source.refreshAndAwait()
            assertEquals("An old running manual value is not an automatic SIM value", "", row().resolvedSmsc)
            facts = known.copy(loaded = known.loaded.copy(fields = listOf(ConfigurationFact("smsc", "+19995550123", "sim"))))
            fixture.source.refreshAndAwait()
            assertEquals("+19995550123", row().resolvedSmsc)
            S3ConfigurationCommandShadow.smsc = "+15550000001"
            facts = null
            assertEquals(RefreshResult.Completed, fixture.source.refreshAndAwait())
            assertNull(row().runtimeFacts)
            val before = reads
            AutomaticSmscCommandShadow.running = false
            assertEquals(RefreshResult.Completed, fixture.source.refreshAndAwait())
            assertEquals(before, reads)
            assertNull(row().runtimeFacts)
            assertTrue(S3ConfigurationCommandShadow.commands.none { " config apply " in it || " start " in it })
        }
    }

    @Test fun aCurrentStatusDecodeFailureClearsThePreviouslyObservedSimSmsc() = runBlocking {
        assertAutomaticSmscFollowsAcceptedStatus(heldCatalogFailure = false)
    }

    @Test fun aLaterValidStatusRestoresSimSmscEvenWhenAnOlderCatalogFailurePublishesLast() = runBlocking {
        assertAutomaticSmscFollowsAcceptedStatus(heldCatalogFailure = true)
    }

    private suspend fun assertAutomaticSmscFollowsAcceptedStatus(heldCatalogFailure: Boolean) = coroutineScope {
        val simSmsc = "+19995550123"
        val statusReply = AtomicReference(status(simSmsc = simSmsc))
        val holdNext = AtomicBoolean(false)
        val catalogReceived = CompletableDeferred<Unit>()
        val releaseCatalog = CompletableDeferred<Unit>()
        val methods = CopyOnWriteArrayList<String>()
        lateinit var peer: CallTestSocket
        AutomaticSmscCommandShadow.running = true
        try {
            AndroidEngineClientPool {
                CallTestSocket { socket, line ->
                    val request = Json.parseToJsonElement(line).jsonObject
                    val method = request.getValue("method").jsonPrimitive.content
                    methods += method
                    socket.respond(request.getValue("id").jsonPrimitive.long, when (method) {
                        "hello" -> buildJsonObject { put("version", 1) }
                        "ping" -> buildJsonObject { put("pong", true) }
                        "getStatus" -> statusReply.get()
                        else -> error("Unexpected status request")
                    })
                }.also { peer = it }
            }.use { pool ->
                pool.clientFor(S3_CONFIG_TARGET).request("ping")
                S3ConfigurationIoFixture(runtimeObservationRead = { key ->
                    val observed = pool.configurationObservation(key)
                    if (holdNext.compareAndSet(true, false)) {
                        check(observed != null && observed.facts == null) { "Expected a received but undecodable status" }
                        catalogReceived.complete(Unit)
                        releaseCatalog.await()
                    }
                    observed
                }).use { fixture ->
                    fun row(state: ConfigurationUiState) =
                        (state as? ConfigurationUiState.Ready)?.instances?.singleOrNull { it.instanceId == fixture.targetId }
                    fun notifyRuntimeChange() = peer.send(buildJsonObject {
                        put("event", "callMediaChanged"); put("data", buildJsonObject { put("sequence", 1) })
                    })

                    fixture.ready()
                    S3ConfigurationCommandShadow.smsc = ""
                    assertEquals(RefreshResult.Completed, fixture.source.refreshAndAwait())
                    val initial = checkNotNull(row(fixture.source.state.value))
                    assertEquals("", initial.smsc)
                    assertEquals(simSmsc, initial.resolvedSmsc)
                    assertEquals(600L, initial.runtimeFacts?.registrationSeconds)
                    val rootBeforeEvent = S3ConfigurationCommandShadow.commands.toList()
                    val readsBeforeEvent = methods.count { it == "getStatus" }
                    pool.onRuntimeFactsChange = { key, change -> fixture.source.runtimeFactsChanged(key, change) }
                    statusReply.set(buildJsonObject { put("configuration", "bad") })

                    if (heldCatalogFailure) {
                        holdNext.set(true)
                        val refreshing = async { fixture.source.refreshAndAwait() }
                        try {
                            withTimeout(3_000) { catalogReceived.await() }
                            statusReply.set(status(simSmsc = simSmsc, registrationSeconds = 599L))
                            notifyRuntimeChange()
                            withTimeout(3_000) {
                                fixture.source.state.first { row(it)?.runtimeFacts?.registrationSeconds == 599L }
                            }
                        } finally { releaseCatalog.complete(Unit) }
                        assertEquals(RefreshResult.Completed, refreshing.await())
                    } else {
                        notifyRuntimeChange()
                        withTimeout(3_000) {
                            fixture.source.state.first { row(it)?.let { current -> current.runtimeFacts == null } == true }
                        }
                        assertEquals(rootBeforeEvent, S3ConfigurationCommandShadow.commands.toList())
                    }

                    val result = checkNotNull(row(fixture.source.state.value))
                    assertEquals(initial.configRevision, result.configRevision)
                    assertEquals("", result.smsc)
                    assertEquals(if (heldCatalogFailure) 599L else null, result.runtimeFacts?.registrationSeconds)
                    assertEquals("The automatic value must come from the accepted status",
                        if (heldCatalogFailure) simSmsc else "", result.resolvedSmsc)
                    assertTrue(S3ConfigurationCommandShadow.commands.none { " config apply " in it || " start " in it })
                    assertEquals(1, methods.count { it == "hello" })
                    assertEquals(if (heldCatalogFailure) 2 else 1, methods.count { it == "getStatus" } - readsBeforeEvent)
                }
            }
        } finally { AutomaticSmscCommandShadow.running = false }
    }

    @Test fun onlyAnExistingConnectionCanProvideRuntimeFactsAndFailureClearsTheValue() = runBlocking {
        val methods = mutableListOf<String>()
        var valid = true
        AndroidEngineClientPool {
            CallTestSocket { socket, line ->
                val request = Json.parseToJsonElement(line).jsonObject
                val method = request.getValue("method").jsonPrimitive.content
                methods += method
                socket.respond(request.getValue("id").jsonPrimitive.long, when (method) {
                    "hello" -> buildJsonObject { put("version", 1) }
                    "getStatus" -> if (valid) status() else buildJsonObject { put("configuration", "bad") }
                    else -> buildJsonObject { put("pong", true) }
                })
            }
        }.use { pool ->
            assertNull(pool.configurationFacts(KEY))
            assertTrue(methods.isEmpty())
            pool.clientFor(KEY).request("ping")
            val facts = pool.configurationFacts(KEY)
            assertEquals(600L, facts?.registrationSeconds)
            assertEquals("r1_abcdefghijklmnopabcdefghijklmnop", facts?.loaded?.configRevision)
            valid = false
            assertNull(pool.configurationFacts(KEY))
            pool.remove(KEY)?.close()
            assertNull(pool.configurationFacts(KEY))
            assertEquals(1, methods.count { it == "hello" })
            assertTrue(methods.all { it in setOf("hello", "ping", "getStatus") })
        }
    }

    @Test fun retiredConnectionCannotPublishLateFacts() = runBlocking {
        val requested = CompletableDeferred<Unit>()
        AndroidEngineClientPool {
            CallTestSocket { socket, line ->
                val request = Json.parseToJsonElement(line).jsonObject
                if (request.getValue("method").jsonPrimitive.content == "getStatus") requested.complete(Unit)
                else socket.respond(request.getValue("id").jsonPrimitive.long, buildJsonObject { put("version", 1) })
            }
        }.use { pool ->
            val client = pool.clientFor(KEY)
            client.request("ping")
            val oldConnection = client.existingConnection()!!
            val reading = async { pool.configurationFacts(KEY) }
            withTimeout(3_000) { requested.await() }
            pool.remove(KEY)?.close()
            assertFalse(oldConnection.isCurrent())
            assertNull(withTimeout(3_000) { reading.await() })
        }
    }

    @Test fun cancellingAnInFlightFactsReadDoesNotPublishUnknownAsSuccess() = runBlocking {
        val requested = CompletableDeferred<Unit>()
        var published = false
        AndroidEngineClientPool {
            CallTestSocket { socket, line ->
                val request = Json.parseToJsonElement(line).jsonObject
                if (request.getValue("method").jsonPrimitive.content == "getStatus") requested.complete(Unit)
                else socket.respond(request.getValue("id").jsonPrimitive.long, buildJsonObject { put("version", 1) })
            }
        }.use { pool ->
            pool.clientFor(KEY).request("ping")
            val read = launch { pool.configurationFacts(KEY); published = true }
            withTimeout(3_000) { requested.await() }
            read.cancelAndJoin()
            assertFalse(published)
        }
    }

    @Test fun existingPoolAndActualCatalogKeepCallCaptureSeparateFromTheNextCallDefault() = runBlocking {
        val methods = java.util.concurrent.CopyOnWriteArrayList<String>()
        var malformed = false
        val captured = buildJsonObject {
            put("configRevision", "r1_abcdefghijklmnopabcdefghijklmnop")
            put("sessionSeconds", 300); put("sessionRefresher", "remote")
        }
        AutomaticSmscCommandShadow.running = true
        try {
            AndroidEngineClientPool {
                CallTestSocket { socket, line ->
                    val request = Json.parseToJsonElement(line).jsonObject
                    val method = request.getValue("method").jsonPrimitive.content
                    methods += method
                    val result = when (method) {
                        "hello" -> buildJsonObject { put("version", 1) }
                        "getStatus" -> {
                            val root = status(if (malformed) JsonObject(captured + ("sessionSeconds" to JsonPrimitive(0))) else captured)
                            val configuration = root.getValue("configuration").jsonObject
                            val loaded = configuration.getValue("loaded").jsonObject
                            val nextLoaded = JsonObject(loaded + ("configRevision" to JsonPrimitive("r1_ponmlkjihgfedcbaponmlkjihgfedcba")))
                            JsonObject(root + ("configuration" to JsonObject(configuration + ("loaded" to nextLoaded))))
                        }
                        else -> buildJsonObject { put("pong", true) }
                    }
                    socket.respond(request.getValue("id").jsonPrimitive.long, result)
                }
            }.use { pool ->
                pool.clientFor(KEY).request("ping")
                S3ConfigurationIoFixture(runtimeRead = { key -> pool.configurationFacts(key) }).use { fixture ->
                    fixture.ready()
                    fun row() = (fixture.source.state.value as ConfigurationUiState.Ready).instances.single { it.instanceId == fixture.targetId }
                    assertEquals("r1_ponmlkjihgfedcbaponmlkjihgfedcba", row().runtimeFacts?.loaded?.configRevision)
                    assertEquals("r1_abcdefghijklmnopabcdefghijklmnop", row().runtimeFacts?.call?.configRevision)
                    assertEquals(300L, row().runtimeFacts?.call?.sessionSeconds)
                    assertEquals("remote", row().runtimeFacts?.call?.sessionRefresher)
                    malformed = true
                    assertEquals(RefreshResult.Completed, fixture.source.refreshAndAwait())
                    assertNull(row().runtimeFacts)
                    assertEquals(1, methods.count { it == "hello" })
                    assertTrue(methods.all { it in setOf("hello", "ping", "getStatus") })
                    assertTrue(S3ConfigurationCommandShadow.commands.none { " config apply " in it || " start " in it })
                }
            }
        } finally {
            AutomaticSmscCommandShadow.running = false
        }
    }

    @Test fun boundedMediaFactsTraverseTheExistingPoolAndCatalogWithoutNewConnectionsOrWrites() = runBlocking {
        val methods = java.util.concurrent.CopyOnWriteArrayList<String>()
        var malformed = false
        val group = Json.parseToJsonElement("""{"speechFormats":[{"codec":"amr-nb","framing":"oa","modeSet":[0,2,4],"initialMode":0,"payloadType":110}]}""")
        AutomaticSmscCommandShadow.running = true
        try {
            AndroidEngineClientPool {
                CallTestSocket { socket, line ->
                    val request = Json.parseToJsonElement(line).jsonObject
                    val method = request.getValue("method").jsonPrimitive.content
                    methods += method
                    val response = if (method == "getStatus") {
                        val root = status()
                        val configuration = root.getValue("configuration").jsonObject
                        val loaded = configuration.getValue("loaded").jsonObject
                        val media = buildJsonObject {
                            put("state", "known"); put("source", "user"); put("value", group)
                            if (!malformed) put("override", group)
                        }
                        JsonObject(root + ("configuration" to JsonObject(configuration +
                            ("loaded" to JsonObject(loaded + ("callMedia" to media))))))
                    } else buildJsonObject { put("version", 1); put("pong", true) }
                    socket.respond(request.getValue("id").jsonPrimitive.long, response)
                }
            }.use { pool ->
                assertNull(pool.configurationFacts(KEY))
                assertTrue(methods.isEmpty())
                pool.clientFor(KEY).request("ping")
                S3ConfigurationIoFixture(runtimeRead = { key -> pool.configurationFacts(key) }).use { fixture ->
                    fixture.ready()
                    fun row() = (fixture.source.state.value as ConfigurationUiState.Ready).instances.single { it.instanceId == fixture.targetId }
                    val media = checkNotNull(row().runtimeFacts?.loaded?.callMedia)
                    assertTrue(media.known)
                    assertEquals("user", media.source)
                    assertEquals(0, media.value?.speechFormats?.first()?.initialMode)
                    assertEquals(media.value, media.overrideValue)
                    malformed = true
                    assertEquals(RefreshResult.Completed, fixture.source.refreshAndAwait())
                    assertNull(row().runtimeFacts)
                    assertEquals(1, methods.count { it == "hello" })
                    assertTrue(methods.all { it in setOf("hello", "ping", "getStatus") })
                    assertTrue(S3ConfigurationCommandShadow.commands.none { " config apply " in it || " start " in it })
                }
            }
        } finally {
            AutomaticSmscCommandShadow.running = false
        }
    }

    @Test fun actualPoolAndCatalogPublishOnlyCurrentAuthenticatedTunnelFacts() = runBlocking {
        val methods = java.util.concurrent.CopyOnWriteArrayList<String>()
        var connected = true
        var malformed = false
        val tuple = Json.parseToJsonElement("""{"ike":{"encryption":12,"keyBits":256,"integrity":12,"prf":5,"dh":14},"child":{"encryption":20,"keyBits":128,"integrity":0,"esn":false,"pfs":0}}""").jsonObject
        AutomaticSmscCommandShadow.running = true
        try {
            AndroidEngineClientPool {
                CallTestSocket { socket, line ->
                    val request = Json.parseToJsonElement(line).jsonObject
                    val method = request.getValue("method").jsonPrimitive.content
                    methods += method
                    val result = when (method) {
                        "hello" -> buildJsonObject { put("version", 1) }
                        "getStatus" -> status(tunnel = if (malformed) JsonObject(tuple + ("key" to JsonPrimitive("not-public"))) else tuple,
                            tunnelState = if (connected) "CONNECTED" else "BACKOFF")
                        else -> buildJsonObject { put("pong", true) }
                    }
                    socket.respond(request.getValue("id").jsonPrimitive.long, result)
                }
            }.use { pool ->
                assertNull(pool.configurationFacts(KEY))
                assertTrue(methods.isEmpty())
                pool.clientFor(KEY).request("ping")
                S3ConfigurationIoFixture(runtimeRead = { key -> pool.configurationFacts(key) }).use { fixture ->
                    fixture.ready()
                    fun row() = (fixture.source.state.value as ConfigurationUiState.Ready).instances.single { it.instanceId == fixture.targetId }
                    assertEquals(256, row().runtimeFacts?.tunnel?.ike?.keyBits)
                    assertEquals(20, row().runtimeFacts?.tunnel?.child?.encryption)
                    connected = false
                    fixture.source.refreshAndAwait()
                    assertNull(row().runtimeFacts?.tunnel)
                    connected = true
                    malformed = true
                    fixture.source.refreshAndAwait()
                    assertNull(row().runtimeFacts)
                    pool.remove(KEY)?.close()
                    malformed = false
                    fixture.source.refreshAndAwait()
                    assertNull(row().runtimeFacts)
                    assertEquals(1, methods.count { it == "hello" })
                    assertTrue(methods.all { it in setOf("hello", "ping", "getStatus") })
                    assertTrue(S3ConfigurationCommandShadow.commands.none { " config apply " in it || " start " in it })
                }
            }
        } finally {
            AutomaticSmscCommandShadow.running = false
        }
    }

    private fun status(
        call: JsonObject? = null,
        tunnel: JsonObject? = null,
        tunnelState: String = "CONNECTED",
        simSmsc: String? = null,
        registrationSeconds: Long = 600L,
    ) = buildJsonObject {
        put("tunnel", tunnelState)
        put("configuration", buildJsonObject {
            put("schema", 1)
            put("loaded", buildJsonObject {
                put("schema", 1); put("configRevision", "r1_abcdefghijklmnopabcdefghijklmnop")
                put("resolutionRevision", "rf1_" + "a".repeat(64))
                put("fields", buildJsonArray {
                    if (simSmsc != null) add(buildJsonObject {
                        put("field", "smsc"); put("state", "known"); put("source", "sim"); put("value", simSmsc)
                    })
                })
            })
            put("negotiated", buildJsonObject { put("registrationSeconds", registrationSeconds) })
            call?.let { put("call", it) }
            tunnel?.let { put("tunnel", it) }
        })
    }

    companion object { private const val KEY = "0000000000000000001" }
}
