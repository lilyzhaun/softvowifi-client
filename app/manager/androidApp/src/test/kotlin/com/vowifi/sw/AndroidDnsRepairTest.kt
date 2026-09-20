@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import com.vowifi.sw.manager.data.AndroidConfigurationCatalog
import com.vowifi.sw.manager.data.AndroidConfigurationCommands
import com.vowifi.sw.manager.data.AndroidConfigurationDns
import com.vowifi.sw.manager.data.AndroidSubscriptionDiscovery
import com.vowifi.sw.manager.data.AndroidSuCommand
import com.vowifi.sw.manager.data.AndroidSuResult
import com.vowifi.sw.manager.data.ConfigurationControlCommand
import com.vowifi.sw.manager.data.DnsEndpoint
import com.vowifi.sw.manager.data.NetworkDnsLookup
import com.vowifi.sw.manager.data.OpaqueIccidHandleStore
import com.vowifi.sw.manager.model.SimActiveJoinInput
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [DnsRepairCommandShadow::class],
    instrumentedPackages = ["com.vowifi.sw.manager.data"])
class AndroidDnsRepairTest {
    @Before
    fun reset() {
        DnsRepairCommandShadow.payloads.clear()
        DnsRepairCommandShadow.targetDns = ""
    }

    @Test fun automaticBootstrap() = exercise("automaticBootstrap") { dns, catalog ->
        val snapshot = catalog.load()
        dns.prepareAuto(active(), snapshot.existingKeys, snapshot.state)
    }

    @Test fun automaticCreated() = exercise("automaticCreated") { dns, _ ->
        dns.finishAutoProvision(active(), endpoint(), created = true)
    }

    @Test fun manualExisting() = exercise("manualExisting") { dns, catalog ->
        dns.finishManualExisting(catalog.load().state, TARGET, endpoint())
    }

    @Test fun manualCreated() = exercise("manualCreated") { dns, _ ->
        dns.finishManualCreated(TARGET, endpoint())
    }

    @Test fun explicitDnsIsNotRepaired() = runBlocking {
        DnsRepairCommandShadow.targetDns = "198.51.100.8:53"
        val (dns, catalog) = subject()
        val snapshot = catalog.load()
        dns.prepareAuto(active(), snapshot.existingKeys, snapshot.state)
        dns.finishAutoProvision(active(), endpoint(), created = true)
        dns.finishManualExisting(snapshot.state, TARGET, endpoint())
        dns.finishManualCreated(TARGET, endpoint())
        assertEquals(emptyList<Pair<String, String>>(), DnsRepairCommandShadow.payloads)
        assertEquals("198.51.100.8:53", DnsRepairCommandShadow.targetDns)
    }

    private fun exercise(
        name: String,
        action: suspend (AndroidConfigurationDns, AndroidConfigurationCatalog) -> Unit,
    ) = runBlocking {
        val (dns, catalog) = subject()
        action(dns, catalog)
        val (command, payload) = DnsRepairCommandShadow.payloads.single()
        assertEquals(ConfigurationControlCommand.applyLine(TARGET), command)
        System.getenv("DNS_REPAIR_PAYLOAD_DIR")?.let { directory ->
            File(directory, "$name.json").writeText(payload)
        }
        assertEquals(JsonObject(mapOf("dns" to JsonPrimitive(endpoint().render()))),
            Json.parseToJsonElement(payload))
    }

    private fun subject(): Pair<AndroidConfigurationDns, AndroidConfigurationCatalog> {
        val commands = AndroidConfigurationCommands()
        val catalog = AndroidConfigurationCatalog(commands,
            AndroidSubscriptionDiscovery(RuntimeEnvironment.getApplication()), OpaqueIccidHandleStore())
        return AndroidConfigurationDns(NetworkDnsLookup { endpoint() }, catalog, commands) to catalog
    }

    private fun active(): List<SimActiveJoinInput> = listOf(
        SimActiveJoinInput(TARGET, 0, mcc = "001", mnc = "01"),
        SimActiveJoinInput(OTHER, 1, mcc = "001", mnc = "01"),
    )

    private fun endpoint() = DnsEndpoint.v4("192.0.2.53")
}

@Implements(value = AndroidSuCommand::class, isInAndroidSdk = false)
internal class DnsRepairCommandShadow {
    @Implementation
    fun run(command: String): AndroidSuResult = success(when (command) {
        ConfigurationControlCommand.listLine() ->
            """[{"ok":true,"iccid":"$TARGET","state":"stopped"},{"ok":true,"iccid":"$OTHER","state":"stopped"}]"""
        ConfigurationControlCommand.showLine(TARGET) -> shown(targetDns)
        ConfigurationControlCommand.showLine(OTHER) -> shown("203.0.113.8:53")
        else -> error("Unexpected command")
    })

    @Implementation
    fun run(command: String, stdinText: String, timeoutSeconds: Long): AndroidSuResult {
        assertEquals(ConfigurationControlCommand.APPLY_TIMEOUT_SECONDS, timeoutSeconds)
        payloads += command to stdinText
        targetDns = Json.parseToJsonElement(stdinText).jsonObject.getValue("dns").let {
            (it as JsonPrimitive).content
        }
        return success("""{"ok":true}""")
    }

    private fun success(output: String): AndroidSuResult = AndroidSuResult(false, 0, output, false)

    private fun shown(dns: String) = """{"ok":true,"displayLabel":"0001","lifecycle":"stopped","transport":"sim2","smsc":"+15550000001","epdgFqdn":"","imsRealm":"ims.mnc001.mcc001.3gppnetwork.org","dns":"$dns","resolvedEpdgFqdn":"epdg.example.test","resolvedImsRealm":"home.card.test","mcc":"001","mnc":"01"}"""

    companion object {
        val payloads = mutableListOf<Pair<String, String>>()
        var targetDns = ""
    }
}

private const val TARGET = "0000000000000000001"
private const val OTHER = "0000000000000000002"
