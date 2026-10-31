package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigurationApplyResultTest {

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
