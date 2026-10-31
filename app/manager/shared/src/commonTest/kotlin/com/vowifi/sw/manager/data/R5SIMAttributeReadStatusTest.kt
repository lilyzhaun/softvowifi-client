package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.SIMAttributeReadState
import com.vowifi.sw.manager.ui.configurationSIMIdentitySummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class R5SIMAttributeReadStatusTest {
    private fun read(status: String) = ConfigurationCodec.show(redactedShowJson(extra = buildJsonObject {
        put("simAttributeReadStatus", Json.parseToJsonElement(status))
    }))

    @Test fun everyStateRemainsIndependentThroughDecodeAndTheRealEvaluator() {
        for (state in SIMAttributeReadState.entries) {
            val snapshot = read("""{"spn":"${state.wireValue}","gid1":"denied","gid2":"absent"}""")
            val instance = ConfigurationEvaluator.instance(ConfigurationInstanceId("sim-1"), snapshot)
            assertEquals(state, instance.simAttributeReadStatus?.spn)
            assertEquals(SIMAttributeReadState.DENIED, instance.simAttributeReadStatus?.gid1)
            assertEquals(SIMAttributeReadState.ABSENT, instance.simAttributeReadStatus?.gid2)
            val summary = configurationSIMIdentitySummary(instance.simAttributeReadStatus)
            assertTrue("GID1：权限不足" in summary)
            assertFalse("匹配成功" in summary)
        }
    }

    @Test fun oldModulesAndUnknownValuesCannotClaimAnObservationSucceeded() {
        assertNull(ConfigurationCodec.show(redactedShowJson()).simAttributeReadStatus)
        assertTrue("未取得" in configurationSIMIdentitySummary(null))
        val status = read("""{"spn":"private-future-value","gid2":"unreadable"}""").simAttributeReadStatus
        assertEquals(SIMAttributeReadState.UNKNOWN, status?.spn)
        assertEquals(SIMAttributeReadState.UNKNOWN, status?.gid1)
        assertEquals(SIMAttributeReadState.UNREADABLE, status?.gid2)
        assertFalse("private-future-value" in configurationSIMIdentitySummary(status))
        assertFalse("private-future-value" in status.toString())
    }

    @Test fun malformedStateShapesRemainBoundaryErrors() {
        for (body in listOf("true", "[]", """{"spn":123}""", """{"gid1":{"value":"private"}}""")) {
            assertFailsWith<ConfigurationFormatException.Unparseable> { read(body) }
        }
        assertFailsWith<ConfigurationFormatException.IdentityLeak> {
            read("""{"spn":"0000000000000000001"}""")
        }
    }
}
