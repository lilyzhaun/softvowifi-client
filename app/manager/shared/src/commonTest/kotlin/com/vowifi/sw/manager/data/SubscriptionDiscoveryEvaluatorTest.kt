package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.SubscriptionDiscovery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SubscriptionDiscoveryEvaluatorTest {
    @Test
    fun instanceDefaultsToNotAttempted() {
        val fromFactory = snapshotInstance()
        val fromShow = assertIs<ConfigurationUiState.Ready>(
            ConfigurationEvaluator.fromShow(redactedShowJson(), ConfigurationInstanceId(FAKE_INSTANCE_ID)),
        )

        assertEquals(SubscriptionDiscovery.NotAttempted, fromFactory.systemSubscription)
        assertEquals(SubscriptionDiscovery.NotAttempted, fromShow.instances.single().systemSubscription)
    }

    @Test
    fun readyAcceptsSafeMatchedSubscription() {
        val matched = matchedSubscription("alpha", "beta", 0, "001", "01")
        val instance = ConfigurationEvaluator.instance(
            ConfigurationInstanceId(FAKE_INSTANCE_ID),
            ConfigurationCodec.show(redactedShowJson()),
            matched,
        )

        val ready = assertIs<ConfigurationUiState.Ready>(ConfigurationEvaluator.ready(listOf(instance)))

        assertEquals(matched, ready.instances.single().systemSubscription)
        assertEquals("SIM 1 · alpha · 001 / 01", systemSubscriptionSummary(ready.instances.single().systemSubscription))
        assertPrivate(checkNotNull(systemSubscriptionSummary(matched)))
    }

    @Test
    fun readyRejectsDirectUnsafeMatchedFields() {
        val unsafe = listOf(
            SubscriptionDiscovery.Matched(UNSAFE_DIGITS, 0, null, null),
            SubscriptionDiscovery.Matched("alpha", 0, UNSAFE_DIGITS, "01"),
            SubscriptionDiscovery.Matched("alpha", 0, "001", UNSAFE_DIGITS),
        )

        unsafe.forEach { discovery ->
            val state = ConfigurationEvaluator.ready(
                listOf(snapshotInstance().copy(systemSubscription = discovery)),
            )
            val failure = assertIs<ConfigurationUiState.Failure>(state)
            assertEquals("配置包含敏感标识", failure.message)
            assertPrivate(failure.message)
        }
    }

    @Test
    fun readyAfterApplyPreservesDiscovery() {
        val matched = matchedSubscription("alpha", null, 1, "310", "260")
        val instance = snapshotInstance().copy(systemSubscription = matched)
        val applying = ConfigurationEvaluator.ready(
            instances = listOf(instance),
            applyingInstanceId = instance.instanceId,
        )

        val saved = assertIs<ConfigurationUiState.Ready>(ConfigurationEvaluator.readyAfterApply(applying))

        assertEquals(matched, saved.instances.single().systemSubscription)
        assertEquals("配置已保存", saved.message)
        assertPrivate(checkNotNull(saved.message))
    }

    @Test
    fun missingAndUnavailableKeepReady() {
        listOf(SubscriptionDiscovery.Missing, SubscriptionDiscovery.Unavailable).forEach { discovery ->
            val ready = assertIs<ConfigurationUiState.Ready>(
                ConfigurationEvaluator.ready(
                    listOf(snapshotInstance().copy(systemSubscription = discovery)),
                ),
            )
            assertEquals(discovery, ready.instances.single().systemSubscription)
        }
    }
}

private fun snapshotInstance() = ConfigurationEvaluator.instance(
    ConfigurationInstanceId(FAKE_INSTANCE_ID),
    ConfigurationCodec.show(redactedShowJson()),
)

private const val UNSAFE_DIGITS = "123456789012345"
