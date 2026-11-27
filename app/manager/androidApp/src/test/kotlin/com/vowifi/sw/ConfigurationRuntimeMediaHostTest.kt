@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [AutomaticSmscCommandShadow::class, RootFrameworkShadow::class], instrumentedPackages = ["com.vowifi.sw.manager.data", "com.vowifi.sw.root"])
class ConfigurationRuntimeMediaHostTest {
    @Test fun actualSocketProjectsOnlyTheLiveSdesSuiteWithoutChangingSavedPolicy() = runBlocking {
        AutomaticSmscCommandShadow.running = true
        try {
            MediaReadFixture().use { f ->
                f.connect()
                S3ConfigurationIoFixture(runtimeRead = { key -> f.pool.configurationFacts(key) }).use { fixture ->
                    fixture.ready()
                    f.pool.onRuntimeFactsChange = { key, change -> fixture.source.runtimeFactsChanged(key, change) }
                    val before = S3ConfigurationCommandShadow.commands.toList()
                    val original = (fixture.source.state.value as ConfigurationUiState.Ready).instances.single { it.instanceId == fixture.targetId }
                    f.media = JsonObject(f.media!! + mapOf("security" to buildJsonObject { put("keying", "sdes"); put("suite", "AES_CM_128_HMAC_SHA1_80") },
                        "quality" to buildJsonObject { put("revision", 13) }))
                    f.change(1)
                    val ready = withTimeout(2_500) { fixture.source.state.first { state ->
                        (state as? ConfigurationUiState.Ready)?.instances?.singleOrNull { it.instanceId == fixture.targetId }
                            ?.runtimeFacts?.call?.media?.quality?.revision == 13L
                    } } as ConfigurationUiState.Ready
                    val updated = ready.instances.single { it.instanceId == fixture.targetId }
                    val media = updated.runtimeFacts!!.call!!.media!!
                    assertEquals(ConfigurationSrtpSuite.AES_CM_128, media.security!!.suite)
                    assertEquals("sdes", media.security!!.keying)
                    assertNull(media.quality.receive)
                    assertNull(media.quality.send)
                    assertEquals(original, updated.copy(runtimeFacts = original.runtimeFacts))
                    assertEquals(before, S3ConfigurationCommandShadow.commands.toList())
                    assertEquals(1, f.sockets)
                }
            }
        } finally { AutomaticSmscCommandShadow.running = false }
    }

    @Test fun actualSocketHintProjectsPCMUAndStaticReceivePTWithoutChangingSavedConfiguration() = runBlocking {
        AutomaticSmscCommandShadow.running = true
        try {
            MediaReadFixture().use { f ->
                f.connect()
                S3ConfigurationIoFixture(runtimeRead = { key -> f.pool.configurationFacts(key) }).use { fixture ->
                    fixture.ready()
                    f.pool.onRuntimeFactsChange = { key, change -> fixture.source.runtimeFactsChanged(key, change) }
                    val before = S3ConfigurationCommandShadow.commands.toList()
                    val original = (fixture.source.state.value as ConfigurationUiState.Ready).instances.single { it.instanceId == fixture.targetId }
                    f.media = JsonObject(f.media!! + mapOf("codec" to JsonPrimitive("pcmu"), "framing" to JsonPrimitive("sample"),
                        "clockRate" to JsonPrimitive(8000), "payloadType" to JsonPrimitive(110), "receivePayloadType" to JsonPrimitive(0),
                        "quality" to buildJsonObject { put("revision", 9) }))
                    f.change(1)
                    val ready = withTimeout(2_500) { fixture.source.state.first { state ->
                        (state as? ConfigurationUiState.Ready)?.instances?.singleOrNull { it.instanceId == fixture.targetId }
                            ?.runtimeFacts?.call?.media?.quality?.revision == 9L
                    } } as ConfigurationUiState.Ready
                    val updated = ready.instances.single { it.instanceId == fixture.targetId }
                    val media = updated.runtimeFacts!!.call!!.media!!
                    assertEquals("pcmu", media.codec.wire)
                    assertEquals("sample", media.framing.wire)
                    assertEquals(110, media.payloadType)
                    assertEquals(0, media.receivePayloadType)
                    assertNull(media.quality.receive)
                    assertNull(media.quality.send)
                    assertEquals(original, updated.copy(runtimeFacts = original.runtimeFacts))
                    assertEquals(before, S3ConfigurationCommandShadow.commands.toList())
                    assertEquals(1, f.sockets)
                }
            }
        } finally { AutomaticSmscCommandShadow.running = false }
    }

    @Test fun aStoppedOwnerCannotBeRepopulatedByAnAlreadyHeldRuntimeReply() = runBlocking {
        AutomaticSmscCommandShadow.running = true
        try {
            MediaReadFixture().use { f ->
                f.connect()
                S3ConfigurationIoFixture(runtimeRead = { key -> f.pool.configurationFacts(key) }).use { fixture ->
                    fixture.ready()
                    f.pool.onRuntimeFactsChange = { key, change -> fixture.source.runtimeFactsChanged(key, change) }
                    f.holdStatus = true
                    f.media = JsonObject(f.media!! + ("quality" to buildJsonObject { put("revision", 9) }))
                    f.change(1)
                    val old = withTimeout(2_500) { f.held.receive() }
                    AutomaticSmscCommandShadow.running = false
                    assertEquals(RefreshResult.Completed, fixture.source.refreshAndAwait())
                    val before = S3ConfigurationCommandShadow.commands.toList()
                    f.holdStatus = false
                    old.reply()
                    awaitMediaRuntimeRead(fixture.source)
                    val stopped = (fixture.source.state.value as ConfigurationUiState.Ready).instances.single { it.instanceId == fixture.targetId }
                    assertEquals(ConfigurationLifecycle.STOPPED, stopped.lifecycle)
                    assertNull(stopped.runtimeFacts)
                    assertEquals(before, S3ConfigurationCommandShadow.commands.toList())
                }
            }
        } finally { AutomaticSmscCommandShadow.running = false }
    }

    @Test fun malformedOrReorderedHintsAndARetiredPoolOwnerCannotStartAnotherRead() = runBlocking {
        AutomaticSmscCommandShadow.running = true
        try {
            MediaReadFixture().use { f ->
                f.connect()
                S3ConfigurationIoFixture(runtimeRead = { key -> f.pool.configurationFacts(key) }).use { fixture ->
                    fixture.ready()
                    val hints = Channel<EngineRuntimeFactsChange>(4)
                    f.pool.onRuntimeFactsChange = { key, change ->
                        fixture.source.runtimeFactsChanged(key, change)
                        check(hints.trySend(change).isSuccess)
                    }
                    f.media = JsonObject(f.media!! + ("quality" to buildJsonObject { put("revision", 9) }))
                    f.change(5)
                    val first = withTimeout(2_500) { hints.receive() }
                    withTimeout(2_500) { fixture.source.state.first { state ->
                        (state as? ConfigurationUiState.Ready)?.instances?.singleOrNull { it.instanceId == fixture.targetId }
                            ?.runtimeFacts?.call?.media?.quality?.revision == 9L
                    } }
                    awaitMediaRuntimeRead(fixture.source)
                    val reads = f.methods.count { it == "getStatus" }
                    f.change(4)
                    assertEquals(4L, withTimeout(2_500) { hints.receive() }.sequence)
                    f.changeRaw(buildJsonObject { put("sequence", 0) })
                    f.changeRaw(buildJsonObject { put("sequence", "6") })
                    f.changeRaw(buildJsonObject { put("sequence", 6); put("peer", "not-public") })
                    f.connect() // Same socket ping is a readLoop barrier for the preceding frames.
                    assertTrue(hints.tryReceive().isFailure)
                    assertEquals(reads, f.methods.count { it == "getStatus" })
                    val retired = f.pool.remove(S3_CONFIG_TARGET)!!
                    try {
                        fixture.source.runtimeFactsChanged(S3_CONFIG_TARGET, first)
                        awaitMediaRuntimeRead(fixture.source)
                        val kept = (fixture.source.state.value as ConfigurationUiState.Ready).instances.single { it.instanceId == fixture.targetId }
                        assertEquals(9L, kept.runtimeFacts?.call?.media?.quality?.revision)
                        assertEquals(reads, f.methods.count { it == "getStatus" })
                        assertEquals(1, f.sockets)
                    } finally { retired.close() }
                }
            }
        } finally { AutomaticSmscCommandShadow.running = false }
    }

    @Test fun actualSocketHintUpdatesOnlyTheOwnedRuntimeLeafWithoutAnyRootCommand() = runBlocking {
        AutomaticSmscCommandShadow.running = true
        try {
            MediaReadFixture().use { f ->
                f.connect()
                S3ConfigurationIoFixture(runtimeRead = { key -> f.pool.configurationFacts(key) }).use { fixture ->
                    fixture.ready()
                    f.pool.onRuntimeFactsChange = { key, change -> fixture.source.runtimeFactsChanged(key, change) }
                    val before = S3ConfigurationCommandShadow.commands.toList()
                    val original = (fixture.source.state.value as ConfigurationUiState.Ready).instances.single { it.instanceId == fixture.targetId }
                    f.media = JsonObject(f.media!! + mapOf("sendPtimeMs" to JsonPrimitive(240),
                        "quality" to buildJsonObject { put("revision", 9) }))
                    f.change(1)
                    val ready = withTimeout(2_500) { fixture.source.state.first { state ->
                        (state as? ConfigurationUiState.Ready)?.instances?.singleOrNull { it.instanceId == fixture.targetId }
                            ?.runtimeFacts?.call?.media?.quality?.revision == 9L
                    } } as ConfigurationUiState.Ready
                    val updated = ready.instances.single { it.instanceId == fixture.targetId }
                    assertEquals(240, updated.runtimeFacts!!.call!!.media!!.sendPtimeMs)
                    assertEquals(original, updated.copy(runtimeFacts = original.runtimeFacts))
                    assertEquals(before, S3ConfigurationCommandShadow.commands.toList())
                    assertEquals(1, f.sockets)
                }
            }
        } finally { AutomaticSmscCommandShadow.running = false }
    }

    @Test fun aBurstDuringHeldStatusReadDropsTheOldReplyAndReadsOnlyTheNewestState() = runBlocking {
        AutomaticSmscCommandShadow.running = true
        try {
            MediaReadFixture().use { f ->
                f.connect()
                S3ConfigurationIoFixture(runtimeRead = { key -> f.pool.configurationFacts(key) }).use { fixture ->
                    fixture.ready()
                    val allHints = CompletableDeferred<Unit>()
                    f.pool.onRuntimeFactsChange = { key, change ->
                        fixture.source.runtimeFactsChanged(key, change)
                        if (change.sequence == 17L) allHints.complete(Unit)
                    }
                    val before = S3ConfigurationCommandShadow.commands.toList()
                    val readsBefore = f.methods.count { it == "getStatus" }
                    f.holdStatus = true
                    f.media = JsonObject(f.media!! + ("quality" to buildJsonObject { put("revision", 4) }))
                    f.change(1)
                    val first = withTimeout(2_500) { f.held.receive() }
                    f.media = JsonObject(f.media!! + mapOf("epoch" to JsonPrimitive("next-epoch"), "revision" to JsonPrimitive(5),
                        "quality" to buildJsonObject { put("revision", 9) }))
                    for (sequence in 2..17) f.change(sequence.toLong())
                    withTimeout(2_500) { allHints.await() }
                    first.reply()
                    val last = withTimeout(2_500) { f.held.receive() }
                    val stillOld = (fixture.source.state.value as ConfigurationUiState.Ready).instances.single { it.instanceId == fixture.targetId }
                    assertEquals(2L, stillOld.runtimeFacts?.call?.media?.quality?.revision)
                    f.holdStatus = false
                    last.reply()
                    withTimeout(2_500) { fixture.source.state.first { state ->
                        (state as? ConfigurationUiState.Ready)?.instances?.singleOrNull { it.instanceId == fixture.targetId }
                            ?.runtimeFacts?.call?.media?.quality?.revision == 9L
                    } }
                    assertEquals(readsBefore + 2, f.methods.count { it == "getStatus" }) // Held old + latest, not 17 reads.
                    assertEquals(before, S3ConfigurationCommandShadow.commands.toList())
                }
            }
        } finally { AutomaticSmscCommandShadow.running = false }
    }

    @Test fun actualCatalogRefreshReconcilesNewMediaGenerationAndDoesNotWriteOrRetainItAfterStop() = runBlocking {
        AutomaticSmscCommandShadow.running = true
        try {
            MediaReadFixture().use { f ->
                f.connect()
                S3ConfigurationIoFixture(runtimeRead = { key -> f.pool.configurationFacts(key) }).use { fixture ->
                    fixture.ready()
                    fun row() = (fixture.source.state.value as ConfigurationUiState.Ready).instances.single { it.instanceId == fixture.targetId }
                    assertEquals("media-epoch", row().runtimeFacts?.call?.media?.epoch)
                    f.media = JsonObject(f.media!! + mapOf("epoch" to JsonPrimitive("next-epoch"), "revision" to JsonPrimitive(4),
                        "quality" to buildJsonObject { put("revision", 0) }))
                    assertEquals(RefreshResult.Completed, fixture.source.refreshAndAwait())
                    val media = row().runtimeFacts!!.call!!.media!!
                    assertEquals("next-epoch", media.epoch)
                    assertEquals(4L, media.revision)
                    assertNull(media.quality.receive)
                    assertNull(media.quality.send)
                    val reads = f.methods.count { it == "getStatus" }
                    AutomaticSmscCommandShadow.running = false
                    assertEquals(RefreshResult.Completed, fixture.source.refreshAndAwait())
                    assertNull(row().runtimeFacts)
                    assertEquals(reads, f.methods.count { it == "getStatus" })
                    assertTrue(S3ConfigurationCommandShadow.commands.none { " config apply " in it || " start " in it })
                    assertEquals(1, f.sockets)
                }
            }
        } finally { AutomaticSmscCommandShadow.running = false }
    }

    @Test fun actualPoolReadUsesOnlyTheExistingOwnerAndTypedStatusWithoutControlWrites() = runBlocking {
        MediaReadFixture().use { f ->
            assertNull(f.pool.configurationFacts(S3_CONFIG_TARGET))
            assertEquals(0, f.sockets)
            f.connect()
            val facts = f.pool.configurationFacts(S3_CONFIG_TARGET)!!
            assertEquals("r1_ponmlkjihgfedcbaponmlkjihgfedcba", facts.loaded.configRevision)
            assertEquals("r1_abcdefghijklmnopabcdefghijklmnop", facts.call!!.configRevision)
            val media = facts.call!!.media!!
            assertEquals(ConfigurationSpeechCodec.AMR_WB, media.codec)
            assertEquals(3L, media.revision)
            assertEquals(2L, media.quality.revision)
            assertEquals(-2, media.quality.send!!.totalLost)
            assertNull(media.quality.receive)
            assertEquals(listOf("hello", "ping", "getStatus"), f.methods.toList())
            assertEquals(1, f.sockets)
        }
    }

    @Test fun retiredConnectionReplyCannotBeRelabeledAsTheReplacementOwnersRuntime() = runBlocking {
        MediaReadFixture().use { f ->
            f.connect()
            f.retireDuringRead = true
            assertNull(f.pool.configurationFacts(S3_CONFIG_TARGET))
            assertEquals(1, f.methods.count { it == "getStatus" })
            assertEquals(1, f.sockets)
        }
    }

    @Test fun malformedMediaIsUnknownWhileAnOlderEngineWithoutItKeepsItsExistingFacts() = runBlocking {
        MediaReadFixture().use { f ->
            f.connect()
            f.media = JsonObject(f.media!! + ("remote" to JsonPrimitive("not-public")))
            assertNull(f.pool.configurationFacts(S3_CONFIG_TARGET))
            f.media = null
            assertNull(f.pool.configurationFacts(S3_CONFIG_TARGET)!!.call!!.media)
            assertEquals(2, f.methods.count { it == "getStatus" })
            assertEquals(1, f.sockets)
        }
    }
}

private class MediaReadFixture : AutoCloseable {
    val methods = CopyOnWriteArrayList<String>()
    var sockets = 0
    var retireDuringRead = false
    @Volatile var holdStatus = false
    val held = Channel<HeldRuntimeReply>(2)
    private lateinit var liveSocket: CallTestSocket
    var media: JsonObject? = Json.parseToJsonElement("""{"epoch":"media-epoch","revision":3,"codec":"amr-wb","framing":"oa","clockRate":16000,"payloadType":96,"receivePayloadType":112,"sendPtimeMs":60,"receiveMaxPtimeMs":80,"direction":"sendrecv","inactivityTimeoutMs":60000,"quality":{"revision":2,"send":{"fractionLost":64,"jitterMs":20,"totalLost":-2}}}""").jsonObject
    private var retired: AutoCloseable? = null
    val pool: AndroidEngineClientPool = AndroidEngineClientPool {
        sockets++
        CallTestSocket { socket, line ->
            liveSocket = socket
            val request = Json.parseToJsonElement(line).jsonObject
            val id = request.getValue("id").jsonPrimitive.long
            val method = request.getValue("method").jsonPrimitive.content
            methods += method
            when (method) {
                "hello" -> socket.respond(id, buildJsonObject { put("version", 1) })
                "ping" -> socket.respond(id, buildJsonObject { put("pong", true) })
                "getStatus" -> {
                    if (retireDuringRead) retired = pool.remove(S3_CONFIG_TARGET)
                    val result = buildJsonObject {
                        put("configuration", buildJsonObject {
                            put("schema", 1)
                            put("loaded", buildJsonObject {
                                put("schema", 1); put("configRevision", "r1_ponmlkjihgfedcbaponmlkjihgfedcba")
                                put("resolutionRevision", "rf1_" + "a".repeat(64)); put("fields", JsonArray(emptyList()))
                            })
                            put("negotiated", JsonObject(emptyMap()))
                            put("call", buildJsonObject {
                                put("configRevision", "r1_abcdefghijklmnopabcdefghijklmnop")
                                media?.let { put("media", it) }
                            })
                        })
                    }
                    if (holdStatus) check(held.trySend(HeldRuntimeReply { socket.respond(id, result) }).isSuccess)
                    else socket.respond(id, result)
                }
                else -> error("Unexpected control write during runtime read")
            }
        }
    }
    suspend fun connect() { pool.clientFor(S3_CONFIG_TARGET).request("ping") }
    fun change(sequence: Long) = changeRaw(buildJsonObject { put("sequence", sequence) })
    fun changeRaw(data: JsonObject) { liveSocket.send(buildJsonObject {
        put("event", "callMediaChanged"); put("data", data)
    }) }
    override fun close() { retired?.close(); pool.close() }
}

private class HeldRuntimeReply(val reply: () -> Unit)

private suspend fun awaitMediaRuntimeRead(source: AndroidConfigurationSource) {
    val lock = source.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(source)
    val reader = synchronized(lock) {
        source.javaClass.getDeclaredField("runtimeReader").apply { isAccessible = true }.get(source) as Job?
    }
    withTimeout(2_500) { reader?.join() }
}
