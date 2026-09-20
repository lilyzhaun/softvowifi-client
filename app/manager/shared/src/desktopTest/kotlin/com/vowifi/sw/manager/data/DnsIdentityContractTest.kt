package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationOverrides
import com.vowifi.sw.manager.model.ConfigurationTransport
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DnsIdentityContractTest {
    @Test
    fun freshCandidateAndExplicitRealmKeepTheirSeparateContracts() {
        val candidate = assertIs<CandidateAssembly.Ready>(StandardCandidateAssembler.assemble(
            RawSubscriptionIdentity("0000000000000000001", 0, 1, "001", "01", null, null),
        )).payload
        val parsed = Json.parseToJsonElement(candidate).jsonObject
        assertEquals("auto", parsed.getValue("imsIdentityMode").jsonPrimitive.content)
        assertEquals("", parsed.getValue("dns").jsonPrimitive.content)
        val manual = ConfigurationCodec.encode(ConfigurationOverrides(
            ConfigurationTransport.WIFI, "", "", "explicit.realm.test", "",
        ))
        assertEquals("explicit.realm.test",
            Json.parseToJsonElement(manual).jsonObject.getValue("imsRealm").jsonPrimitive.content)
        System.getenv("DNS_REPAIR_PAYLOAD_DIR")?.let { directory ->
            File(directory, "candidate.json").writeText(candidate)
            File(directory, "explicitRealm.json").writeText(manual)
        }
    }
}
