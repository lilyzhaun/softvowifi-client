@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.ConfigurationFact
import android.telephony.SubscriptionManager
import kotlinx.coroutines.*
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
            ConfigurationFactsCodec.runtime(status())
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

    private fun status() = buildJsonObject {
        put("configuration", buildJsonObject {
            put("schema", 1)
            put("loaded", buildJsonObject {
                put("schema", 1); put("configRevision", "r1_abcdefghijklmnopabcdefghijklmnop")
                put("resolutionRevision", "rf1_" + "a".repeat(64)); put("fields", buildJsonArray {})
            })
            put("negotiated", buildJsonObject { put("registrationSeconds", 600) })
        })
    }

    companion object { private const val KEY = "0000000000000000001" }
}
