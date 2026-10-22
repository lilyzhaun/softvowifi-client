@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.SubscriptionDiscovery
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [AutomaticSmscCommandShadow::class], instrumentedPackages = ["com.vowifi.sw.manager.data"])
class ConfigurationAutomaticSmscHostTest {
    @Test fun catalogReadsOnlyTheMatchedRunningSimAndNeverPersistsTheResult() = runBlocking {
        val reads = AtomicInteger()
        var value: String? = "+19995550123"
        AutomaticSmscCommandShadow.running = true
        S3ConfigurationIoFixture(smscRead = { owner -> assertEquals(S3_CONFIG_TARGET, owner); reads.incrementAndGet(); value }).use { fixture ->
            fixture.ready()
            assertEquals("A saved manual SMSC must not be relabelled automatic", 0, reads.get())
            S3ConfigurationCommandShadow.smsc = ""
            assertEquals(RefreshResult.Completed, fixture.source.refreshAndAwait())
            fun row() = (fixture.source.state.value as ConfigurationUiState.Ready).instances.single { it.instanceId == fixture.targetId }
            assertEquals("前提：模拟公开读回已运行", ConfigurationLifecycle.RUNNING, row().lifecycle)
            assertEquals("前提：磁盘覆盖已清空", "", row().smsc)
            assertTrue("前提：当前SIM匹配，实际=${row().systemSubscription}", row().systemSubscription is SubscriptionDiscovery.Matched)
            assertEquals(1, reads.get())
            assertEquals("+19995550123", row().resolvedSmsc)
            assertEquals("", row().smsc)
            value = null
            fixture.source.refreshAndAwait()
            assertEquals("A failed new read must clear the old automatic display", "", row().resolvedSmsc)
            val beforeStop = reads.get()
            AutomaticSmscCommandShadow.running = false
            fixture.source.refreshAndAwait()
            assertEquals("Stopped instances must not cause a connection or SIM read", beforeStop, reads.get())
            assertEquals("", row().resolvedSmsc)
            assertTrue(S3ConfigurationCommandShadow.commands.none { " config apply " in it || " start " in it })
        }
    }

    @Test fun sharedPoolExtractsOnlySmscAndRejectsBadOrRetiredResults() = runBlocking {
        val methods = mutableListOf<String>()
        var smsc: JsonElement = JsonPrimitive("+19995550123")
        AndroidEngineClientPool {
            CallTestSocket { socket, line ->
                val request = Json.parseToJsonElement(line).jsonObject
                val method = request.getValue("method").jsonPrimitive.content
                methods += method
                socket.respond(request.getValue("id").jsonPrimitive.long, when (method) {
                    "hello" -> buildJsonObject { put("version", 1); put("engineVersion", "test") }
                    "getConfig" -> buildJsonObject { put("smsc", smsc); put("ignored", "not exposed") }
                    else -> buildJsonObject { put("pong", true) }
                })
            }
        }.use { pool ->
            assertNull(pool.automaticSmsc(S3_CONFIG_TARGET))
            assertTrue("Do not open an instance just to display a default", methods.isEmpty())
            pool.clientFor(S3_CONFIG_TARGET).request("ping")
            assertEquals("+19995550123", pool.automaticSmsc(S3_CONFIG_TARGET))
            smsc = JsonPrimitive(123)
            assertNull(pool.automaticSmsc(S3_CONFIG_TARGET))
            smsc = JsonPrimitive("not a service centre")
            assertNull(pool.automaticSmsc(S3_CONFIG_TARGET))
            pool.remove(S3_CONFIG_TARGET)?.close()
            assertNull(pool.automaticSmsc(S3_CONFIG_TARGET))
            assertEquals("The existing connection must be reused", 1, methods.count { it == "hello" })
            assertTrue(methods.all { it in setOf("hello", "ping", "getConfig") })
        }
    }

    @Test fun cancellingAnInFlightReadPropagatesInsteadOfPublishingUnknown() = runBlocking {
        val requested = CompletableDeferred<Unit>()
        val returned = AtomicBoolean()
        AndroidEngineClientPool {
            CallTestSocket { socket, line ->
                val request = Json.parseToJsonElement(line).jsonObject
                if (request.getValue("method").jsonPrimitive.content == "getConfig") requested.complete(Unit)
                else socket.respond(request.getValue("id").jsonPrimitive.long, buildJsonObject { put("version", 1) })
            }
        }.use { pool ->
            pool.clientFor(S3_CONFIG_TARGET).request("ping")
            val read = launch { pool.automaticSmsc(S3_CONFIG_TARGET); returned.set(true) }
            withTimeout(3_000) { requested.await() }
            read.cancelAndJoin()
            assertFalse("Cancellation must not be swallowed by the closed-client handler", returned.get())
        }
    }
}

@Implements(value = AndroidSuCommand::class, isInAndroidSdk = false)
internal class AutomaticSmscCommandShadow {
    @Implementation fun run(command: String): AndroidSuResult {
        val result: AndroidSuResult = S3ConfigurationCommandShadow().run(command)
        return if (command.contains(" config show ") && running) result.copy(output = result.output.replace("\"stopped\"", "\"running\"")) else result
    }
    @Implementation fun run(command: String, stdinText: String, timeoutSeconds: Long): AndroidSuResult =
        error("Displaying automatic values must not write configuration")
    companion object { @Volatile var running = false }
}
