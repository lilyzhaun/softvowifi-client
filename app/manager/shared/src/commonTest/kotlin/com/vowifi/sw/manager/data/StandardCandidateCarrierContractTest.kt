package com.vowifi.sw.manager.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StandardCandidateCarrierContractTest {
    @Test fun skinnyCandidateOmitsAllFourRawPolicyOverrides() {
        val ready = assertIs<CandidateAssembly.Ready>(StandardCandidateAssembler.assemble(
            RawSubscriptionIdentity("8986000000000000001", 1, 2, "530", "05", null, null, 2105),
        ))
        val fields = (Json.parseToJsonElement(ready.payload) as JsonObject).keys
        assertEquals(emptySet(), fields.intersect(setOf("imsDigestRealm", "imsSipSecurity", "paniInitial", "paniCountry")))
    }

    @Test fun automaticCandidateKeepsRawMatchInputsAndOmitsAllPolicyOverrides() {
        for (mnc in listOf("03", "003")) {
            for (carrierId in listOf(null, -1, 0, 760, 761)) {
                val ready = assertIs<CandidateAssembly.Ready>(StandardCandidateAssembler.assemble(
                    RawSubscriptionIdentity("8986000000000000001", 1, 2, "454", mnc, null, null, carrierId),
                ))
                val expected = Json.parseToJsonElement("""{
                    "iccid":"8986000000000000001","slot":1,"subscriptionId":2,
                    "mcc":"454","mnc":"$mnc","mncLen":${mnc.length},
                    "epdgFqdn":"","imsRealm":"ims.mnc003.mcc454.3gppnetwork.org",
                    "transport":"wifi","smsc":"","dns":"","enabled":true,
                    "imei":"","imsi":"","imsIdentityMode":"auto"
                }""") as JsonObject
                val fields = if (carrierId != null && carrierId > 0) expected + ("carrierId" to JsonPrimitive(carrierId)) else expected
                assertEquals(JsonObject(fields), Json.parseToJsonElement(ready.payload))
            }
        }
    }
}
