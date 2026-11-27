@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidEngineClientPool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [NextCallCommandShadow::class, RootFrameworkShadow::class], instrumentedPackages = ["com.vowifi.sw.manager.data", "com.vowifi.sw.root"])
class ConfigurationSwuAccessFactsHostTest {
    @Test fun actualExistingConnectionAcceptsAccessFactsAndRejectsMalformedValues() = runBlocking {
        val methods = java.util.concurrent.CopyOnWriteArrayList<String>()
        var access: JsonElement? = null
        AndroidEngineClientPool {
            CallTestSocket { socket, line ->
                val request = Json.parseToJsonElement(line).jsonObject
                val method = request.getValue("method").jsonPrimitive.content
                methods += method
                val response = if (method == "getStatus") buildJsonObject {
                    put("tunnel", "BACKOFF")
                    put("configuration", buildJsonObject {
                        put("schema", 1)
                        put("loaded", buildJsonObject {
                            put("schema", 1); put("configRevision", REVISION)
                            put("resolutionRevision", "rf1_" + "a".repeat(64)); put("fields", buildJsonArray {})
                            access?.let { put("swuAccess", it) }
                        })
                        put("negotiated", buildJsonObject {})
                    })
                } else buildJsonObject { put("version", 1); put("pong", true) }
                socket.respond(request.getValue("id").jsonPrimitive.long, response)
            }
        }.use { pool ->
            assertNull(pool.configurationFacts(S3_CONFIG_TARGET)); assertTrue(methods.isEmpty())
            pool.clientFor(S3_CONFIG_TARGET).request("ping")
            assertNotNull(pool.configurationFacts(S3_CONFIG_TARGET)?.loaded)
            access = Json.parseToJsonElement("""{"state":"known","source":"user","value":$GROUP,"override":$GROUP}""")
            assertNotNull("real existing-connection facts consumer rejected the new complete access group", pool.configurationFacts(S3_CONFIG_TARGET)?.loaded)
            val inner = GROUP.dropLast(1) + """, "innerProtocol":{"home":"ipv4","roaming":"ipv6"}}"""
            access = Json.parseToJsonElement("""{"state":"known","source":"user","value":$inner,"override":$inner}""")
            val innerFacts = pool.configurationFacts(S3_CONFIG_TARGET)?.loaded
            assertNotNull("actual getStatus connection lost complete inner protocols", innerFacts)
            val actual = com.vowifi.sw.manager.data.ConfigurationSwuAccessCodec.read(innerFacts)
            assertNotNull(actual)
            assertEquals(Json.parseToJsonElement(inner), com.vowifi.sw.manager.data.ConfigurationSwuAccessCodec.encode(actual!!))
            access = Json.parseToJsonElement("""{"state":"unknown","source":"user"}""")
            assertNotNull(pool.configurationFacts(S3_CONFIG_TARGET)?.loaded)
            for (bad in listOf(GROUP.replace("1340", "\"1340\""), GROUP.replace("\"ims\"", "\"ut\""))) {
                access = Json.parseToJsonElement("""{"state":"known","source":"user","value":$bad,"override":$bad}""")
                assertNull(pool.configurationFacts(S3_CONFIG_TARGET))
            }
            assertEquals(1, methods.count { it == "hello" })
            assertTrue(methods.all { it in setOf("hello", "ping", "getStatus") })
        }
    }

    private companion object {
        const val REVISION = "r1_abcdefghijklmnopabcdefghijklmnop"
        const val GROUP = """{"purpose":"ims","requestedApn":"voice.example.test","interfaceMtu":1340}"""
    }
}
