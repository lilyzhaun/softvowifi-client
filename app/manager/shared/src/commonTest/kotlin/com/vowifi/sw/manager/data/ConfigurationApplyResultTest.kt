package com.vowifi.sw.manager.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigurationApplyResultTest {
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
