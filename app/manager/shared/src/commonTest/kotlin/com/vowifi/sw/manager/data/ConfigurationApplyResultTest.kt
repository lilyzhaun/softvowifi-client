package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigurationApplyResultTest {

    @Test fun busyRequiresACompleteWriteRefusalReceipt() {
        val refused = ConfigurationCommandOutcome(false, false, false, 9,
            """{"ok":false,"code":"BUSY","message":"software business idle state not confirmed"}""")
        assertEquals("Busy", configurationApplyResult(refused).name)
        assertEquals(ConfigurationCommandApplyResult.Busy, configurationApplyResult(refused.copy(output =
            """{ "message": "text with \"ok\":true", "code": "BUSY", "ok": false }""")))
        for (unknown in listOf(refused.copy(timedOut = true), refused.copy(overflowed = true),
            refused.copy(writeFailed = true), refused.copy(exitCode = 0), refused.copy(output = ""),
            refused.copy(output = refused.output.replace("false", "true")),
            refused.copy(output = refused.output.replace("false", "\"false\"")),
            refused.copy(output = """{"ok":true,"ok":false,"code":"BUSY","message":"test"}"""),
            refused.copy(output = """{"ok":false,"code":"BUSY","code":"IO_FAILED","message":"test"}"""),
            refused.copy(output = """{"ok":false,"code":"BUSY","message":"a","message":"b"}"""))) {
            assertEquals(ConfigurationCommandApplyResult.Unconfirmed, configurationApplyResult(unknown))
        }
    }

    @kotlin.test.Test
    fun revisionConflictIsNotCollapsedIntoAnUnexplainedWriteFailure() {
        val outcome = ConfigurationCommandOutcome(false, false, false, 1,
            """{"ok":false,"code":"CONFIG_REVISION_CONFLICT","message":"config changed; refresh before saving"}""")
        kotlin.test.assertEquals("RevisionConflict", configurationApplyResult(outcome).name)
        kotlin.test.assertEquals("Unconfirmed", configurationApplyResult(outcome.copy(timedOut = true)).name)
    }
    @Test fun moduleUnchangedResponseIsNotUnconfirmed() {
        val result = configurationApplyResult(ConfigurationCommandOutcome(false, false, false, 0,
            "{\"ok\":true,\"code\":\"UNCHANGED\",\"message\":\"config unchanged\"}"))
        assertEquals(ConfigurationCommandApplyResult.Unchanged, result)
    }
    @Test fun onlyExplicitSuccessIsAppliedAndBrokenTransportIsUnconfirmed() {
        val success = ConfigurationCommandOutcome(false, false, false, 0, "{\"ok\":true,\"code\":\"APPLIED\",\"message\":\"config applied\"}")
        assertEquals(ConfigurationCommandApplyResult.Applied, configurationApplyResult(success))
        assertEquals(ConfigurationCommandApplyResult.Unconfirmed, configurationApplyResult(success.copy(timedOut = true)))
        assertEquals(ConfigurationCommandApplyResult.Unconfirmed, configurationApplyResult(success.copy(overflowed = true)))
        assertEquals(ConfigurationCommandApplyResult.Unconfirmed, configurationApplyResult(success.copy(writeFailed = true)))
        assertEquals(ConfigurationCommandApplyResult.Unconfirmed, configurationApplyResult(success.copy(output = "")))
        assertEquals(ConfigurationCommandApplyResult.Unconfirmed,
            configurationApplyResult(success.copy(output = success.output.replace("APPLIED", "FUTURE_SUCCESS"))))
        assertEquals(ConfigurationCommandApplyResult.Unconfirmed,
            configurationApplyResult(success.copy(exitCode = 3, output = success.output.replace("APPLIED", "UNCHANGED"))))
        assertEquals(ConfigurationCommandApplyResult.Failed,
            configurationApplyResult(success.copy(exitCode = 1, output = "{\"ok\":false,\"code\":\"APPLY_FAILED\",\"message\":\"failed\"}")))
    }
}
