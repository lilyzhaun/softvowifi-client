package com.vowifi.sw.manager.desktop

import com.vowifi.sw.manager.model.ConfigurationProvisionOutcome
import com.vowifi.sw.manager.model.SimHandle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 仅 fixture 接口/同步内存事务合同，不作为 Android 业务成功证据。
class S3ConfigurationProvisionStateFixtureTest {
    @Test fun synchronousCreationPublishesTypedResultAndPreservesOtherHandleCompletion() {
        val source = FixtureConfigurationSource("empty")
        val first = SimHandle("sim-1")
        val second = SimHandle("sim-2")
        source.provision(first)
        val firstCompletion = source.provisioning.value.completions.getValue(first)
        assertEquals(ConfigurationProvisionOutcome.Created, firstCompletion.outcome)
        assertTrue(source.provisioning.value.pending.isEmpty())
        source.provision(second)
        assertEquals(firstCompletion, source.provisioning.value.completions.getValue(first))
        assertEquals(ConfigurationProvisionOutcome.Created, source.provisioning.value.completions.getValue(second).outcome)
        assertTrue(source.provisioning.value.completions.getValue(second).sequence > firstCompletion.sequence)
        val before = source.provisioning.value
        source.close()
        source.provision(first)
        assertEquals(before, source.provisioning.value)
    }
}
