@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.data.AndroidSuResult
import com.vowifi.sw.manager.model.ConfigurationProvisionOutcome
import com.vowifi.sw.manager.model.ConfigurationProvisionState
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.SimHandle
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], shadows = [ConfigurationProvisionCommandShadow::class], instrumentedPackages = ["com.vowifi.sw.manager.data"])
class S3ConfigurationProvisionStateHostTest {
    @Test fun createdCompletionLeavesDnsBlankForTheLiveNetworkWithoutApplying() = runBlocking {
        ConfigurationProvisionHostFixture().use { fixture ->
            fixture.ready()
            try {
                fixture.source.provision(fixture.target)
                fixture.createHold.awaitEntered()
                assertEquals(setOf(fixture.target), fixture.source.provisioning.value.pending)
                fixture.createHold.release()
                assertEquals(ConfigurationProvisionOutcome.Created, fixture.finished(fixture.target).completions.getValue(fixture.target).outcome)
                val ready = fixture.source.state.value as ConfigurationUiState.Ready
                assertEquals("", ready.instances.single().dns)
                assertTrue(ready.instances.single().dnsOverrides.isEmpty())
                assertEquals(1, ConfigurationProvisionCommandShadow.creates.get())
                assertEquals("新建配置不得写入 DNS 覆盖", 0, ConfigurationProvisionCommandShadow.applies.get())
            } finally { fixture.createHold.release() }
        }
    }

    @Test fun existingConfiguredTargetCompletesUnchangedWithoutRecreatingIt() = runBlocking {
        ConfigurationProvisionHostFixture().use { fixture ->
            fixture.ready()
            fixture.source.provision(fixture.target)
            fixture.createHold.awaitEntered()
            fixture.createHold.release()
            val created = fixture.finished(fixture.target).completions.getValue(fixture.target)
            val readback = fixture.holdReadback()
            try {
                fixture.source.provision(fixture.target)
                readback.awaitEntered()
                assertEquals(setOf(fixture.target), fixture.source.provisioning.value.pending)
                assertEquals(created, fixture.source.provisioning.value.completions.getValue(fixture.target))
                readback.release()
                val completed = fixture.finished(fixture.target).completions.getValue(fixture.target)
                assertEquals(ConfigurationProvisionOutcome.Unchanged, completed.outcome)
                assertTrue(completed.sequence > created.sequence)
                assertEquals(1, ConfigurationProvisionCommandShadow.creates.get())
            } finally { readback.release() }
        }
    }

    @Test fun pendingSurvivesCreateAcknowledgementUntilActualCatalogReadback() = runBlocking {
        ConfigurationProvisionHostFixture().use { fixture ->
            fixture.ready()
            val readback = fixture.holdReadback()
            try {
                fixture.source.provision(fixture.target)
                fixture.createHold.awaitEntered()
                fixture.createHold.release()
                readback.awaitEntered()
                assertEquals(ConfigurationUiState.Empty, fixture.source.state.value)
                assertEquals(setOf(fixture.target), fixture.source.provisioning.value.pending)
                assertTrue(fixture.source.provisioning.value.completions.isEmpty())
                assertEquals(RefreshResult.Busy, withTimeout(3000) { fixture.source.refreshIdentitiesAndAwait() })
                readback.release()
                assertEquals(ConfigurationProvisionOutcome.Created, fixture.finished(fixture.target).completions.getValue(fixture.target).outcome)
                assertEquals(1, ConfigurationProvisionCommandShadow.creates.get())
            } finally { fixture.createHold.release(); readback.release() }
        }
    }

    @Test fun knownRootFailureAndUnconfirmedWriteHaveDistinctTypedTerminalsWithoutReplay() = runBlocking {
        for ((result, expected) in listOf(
            AndroidSuResult(false, 1, "{\"ok\":false,\"code\":\"IDENTITY_MISMATCH\",\"message\":\"identity mismatch\"}", false) to ConfigurationProvisionOutcome.Failed,
            AndroidSuResult(true, -1, "", false) to ConfigurationProvisionOutcome.Unconfirmed,
            AndroidSuResult(false, 1, "{\"ok\":true,\"code\":\"CREATED\",\"message\":\"config created\"}", false) to ConfigurationProvisionOutcome.Unconfirmed,
            AndroidSuResult(false, 1, "not a command receipt", false) to ConfigurationProvisionOutcome.Unconfirmed,
        )) {
            ConfigurationProvisionHostFixture().use { fixture ->
                fixture.ready()
                ConfigurationProvisionCommandShadow.createResult = result
                try {
                    fixture.source.provision(fixture.target)
                    fixture.createHold.awaitEntered()
                    assertEquals(setOf(fixture.target), fixture.source.provisioning.value.pending)
                    fixture.createHold.release()
                    val completed = fixture.finished(fixture.target)
                    assertTrue(completed.pending.isEmpty())
                    assertEquals(expected, completed.completions.getValue(fixture.target).outcome)
                    assertEquals(1, ConfigurationProvisionCommandShadow.creates.get())
                } finally { fixture.createHold.release() }
            }
        }
    }

    @Test fun missingTargetInReadbackCannotClaimCreatedEvenIfOtherRowsAreReady() = runBlocking {
        ConfigurationProvisionHostFixture(includeOther = true).use { fixture ->
            fixture.ready()
            ConfigurationProvisionCommandShadow.created.add(CONFIGURATION_PROVISION_OTHER)
            ConfigurationProvisionCommandShadow.hideTarget = true
            try {
                fixture.source.provision(fixture.target)
                fixture.createHold.awaitEntered()
                fixture.createHold.release()
                assertEquals(ConfigurationProvisionOutcome.Unconfirmed, fixture.finished(fixture.target).completions.getValue(fixture.target).outcome)
                assertTrue(fixture.source.state.value is ConfigurationUiState.Ready)
                assertEquals(1, ConfigurationProvisionCommandShadow.creates.get())
            } finally { fixture.createHold.release() }
        }
    }

    @Test fun anotherHandlesLastCompletionSurvivesPendingAndAutomaticWorkPublishesNoUserCompletion() = runBlocking {
        ConfigurationProvisionHostFixture(includeOther = true).use { fixture ->
            fixture.ready()
            fixture.source.provision(fixture.target)
            fixture.createHold.awaitEntered()
            fixture.createHold.release()
            val first = fixture.finished(fixture.target).completions.getValue(fixture.target)
            val secondHold = fixture.holdCreate()
            ConfigurationProvisionCommandShadow.createResult = AndroidSuResult(false, 1,
                "{\"ok\":false,\"code\":\"IDENTITY_MISMATCH\",\"message\":\"identity mismatch\"}", false)
            try {
                fixture.source.provision(fixture.other)
                secondHold.awaitEntered()
                assertEquals(setOf(fixture.other), fixture.source.provisioning.value.pending)
                assertEquals(first, fixture.source.provisioning.value.completions.getValue(fixture.target))
                fixture.source.provision(fixture.target)
                assertEquals(2, ConfigurationProvisionCommandShadow.creates.get())
                secondHold.release()
                val completed = fixture.finished(fixture.other)
                assertEquals(first, completed.completions.getValue(fixture.target))
                assertEquals(ConfigurationProvisionOutcome.Failed, completed.completions.getValue(fixture.other).outcome)
                assertTrue(completed.completions.getValue(fixture.other).sequence > first.sequence)
                withTimeout(3000) { fixture.source.refreshAndAwait() }
                assertEquals(completed.completions, fixture.source.provisioning.value.completions)
                assertTrue(fixture.source.provisioning.value.pending.isEmpty())
            } finally { secondHold.release() }
        }
    }

    @Test fun automaticBatchReportsRealPendingWithoutAUserCompletionEvent() = runBlocking {
        ConfigurationProvisionHostFixture(automatic = true).use { fixture ->
            try {
                fixture.createHold.awaitEntered()
                assertEquals(setOf(fixture.target), fixture.source.provisioning.value.pending)
                assertTrue(fixture.source.provisioning.value.completions.isEmpty())
                fixture.createHold.release()
                withTimeout(3000) { fixture.source.state.first { it is ConfigurationUiState.Ready } }
                fixture.joinProducer()
                assertTrue(fixture.source.provisioning.value.pending.isEmpty())
                assertTrue(fixture.source.provisioning.value.completions.isEmpty())
                assertEquals(1, ConfigurationProvisionCommandShadow.creates.get())
            } finally { fixture.createHold.release() }
        }
    }

    @Test fun closeCancelsAcceptedProvisionAndLateIoCannotPublishCreated() = runBlocking {
        ConfigurationProvisionHostFixture().use { fixture ->
            fixture.ready()
            try {
                fixture.source.provision(fixture.target)
                fixture.createHold.awaitEntered()
                fixture.source.close()
                val cancelled = fixture.source.provisioning.value
                assertTrue(cancelled.pending.isEmpty())
                assertEquals(ConfigurationProvisionOutcome.Cancelled, cancelled.completions.getValue(fixture.target).outcome)
                fixture.createHold.release()
                fixture.joinProducer()
                assertEquals(cancelled, fixture.source.provisioning.value)
                assertEquals(ConfigurationUiState.Empty, fixture.source.state.value)
                fixture.source.provision(fixture.target)
                assertEquals(1, ConfigurationProvisionCommandShadow.creates.get())
            } finally { fixture.createHold.release() }
        }
    }

    @Test fun unknownHandleCannotInventPendingCompletionOrExecuteCreate() = runBlocking {
        ConfigurationProvisionHostFixture().use { fixture ->
            fixture.ready()
            val before = fixture.source.provisioning.value
            fixture.source.provision(SimHandle("not-in-handle-store"))
            fixture.joinProducer()
            assertEquals(before, fixture.source.provisioning.value)
            assertEquals(0, ConfigurationProvisionCommandShadow.creates.get())
        }
    }
}

internal const val CONFIGURATION_PROVISION_OTHER = "0000000000000000002"

internal class ConfigurationProvisionHostFixture(includeOther: Boolean = false, automatic: Boolean = false) : AutoCloseable {
    val handles = OpaqueIccidHandleStore()
    val target = handles.handleFor(S3_CONFIG_TARGET)
    val other = handles.handleFor(CONFIGURATION_PROVISION_OTHER)
    private val suppressAutomatic = AtomicBoolean(!automatic)
    private val holds = mutableListOf<S3IoHold>()
    val createHold: S3IoHold
    val source: AndroidConfigurationSource
    init {
        ConfigurationProvisionCommandShadow.reset()
        createHold = holdCreate()
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION, true)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING, false)
        val keys = if (includeOther) listOf(S3_CONFIG_TARGET, CONFIGURATION_PROVISION_OTHER) else listOf(S3_CONFIG_TARGET)
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfoList(keys.mapIndexed { index, key ->
            SubscriptionInfoBuilder.newBuilder().setId(index + 1).setSimSlotIndex(index).setIccId(key)
                .setMcc("001").setMnc("01").buildSubscriptionInfo()
        })
        val real = AndroidSubscriptionInventory(context)
        val inventory = object : SubscriptionInventory by real {
            override fun listActive(): SubscriptionInventoryResult = if (suppressAutomatic.get())
                SubscriptionInventoryResult.Failure("无法读取活动订阅") else real.listActive()
        }
        source = AndroidConfigurationSource(context, handles, inventory,
            readNetworkOutlets = { com.vowifi.sw.manager.model.ConfigurationNetworkOutlets(supported = true) })
    }
    suspend fun ready() {
        withTimeout(3000) { source.state.first { it != ConfigurationUiState.Loading } }
        joinProducer()
        suppressAutomatic.set(false)
    }
    suspend fun joinProducer() = withTimeout(3000) {
        val scope = source.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(source) as CoroutineScope
        scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
    }
    suspend fun finished(handle: SimHandle): ConfigurationProvisionState = withTimeout(3000) {
        source.provisioning.first { handle !in it.pending && handle in it.completions }
    }
    fun holdCreate() = S3IoHold().also { holds += it; ConfigurationProvisionCommandShadow.createHold.set(it) }
    fun holdReadback() = S3IoHold().also { holds += it; ConfigurationProvisionCommandShadow.readbackHold.set(it) }
    override fun close() { holds.forEach { it.release() }; source.close() }
}

// 系统命令边界提供受控回执；source / identityReader / provisioner / catalog 均为生产实现。
@Implements(value = AndroidSuCommand::class, isInAndroidSdk = false)
internal class ConfigurationProvisionCommandShadow {
    @Implementation fun run(command: String): AndroidSuResult {
        if (command == ConfigurationControlCommand.listLine()) {
            if (created.isNotEmpty()) readbackHold.getAndSet(null)?.block()
            val keys = created.toList().sorted().filterNot { hideTarget && it == S3_CONFIG_TARGET }
            return success(buildJsonArray { keys.forEach { key -> add(buildJsonObject {
                put("ok", true); put("iccid", key); put("state", "stopped")
            }) } }.toString())
        }
        return TwoCardConfigurationCommandShadow().run(command)
    }
    @Implementation fun run(command: String, stdinText: String, timeoutSeconds: Long): AndroidSuResult {
        assertTrue(stdinText.isNotBlank())
        if (command == ConfigurationControlCommand.applyLine(S3_CONFIG_TARGET)) {
            assertEquals(ConfigurationControlCommand.APPLY_TIMEOUT_SECONDS, timeoutSeconds)
            applies.incrementAndGet()
            return success("{\"ok\":true}")
        }
        val key = when (command) {
            ConfigurationControlCommand.createLine(1, 0) -> S3_CONFIG_TARGET
            ConfigurationControlCommand.createLine(2, 1) -> CONFIGURATION_PROVISION_OTHER
            else -> error("Unexpected provisioning command")
        }
        assertEquals(ConfigurationControlCommand.CREATE_TIMEOUT_SECONDS, timeoutSeconds)
        creates.incrementAndGet()
        createHold.getAndSet(null)?.block()
        val result = createResult
        if (!result.timedOut && result.exitCode == 0 && !result.writeFailed && !result.overflowed) created.add(key)
        return result
    }
    private fun success(text: String) = AndroidSuResult(false, 0, text, false)
    companion object {
        val created = ConcurrentHashMap.newKeySet<String>()
        val creates = AtomicInteger()
        val applies = AtomicInteger()
        val createHold = AtomicReference<S3IoHold?>()
        val readbackHold = AtomicReference<S3IoHold?>()
        @Volatile var hideTarget = false
        @Volatile var createResult = AndroidSuResult(false, 0, "{\"ok\":true,\"code\":\"CREATED\",\"message\":\"config created\"}", false)
        fun reset() {
            created.clear(); creates.set(0); applies.set(0)
            createHold.set(null); readbackHold.set(null)
            hideTarget = false
            createResult = AndroidSuResult(false, 0, "{\"ok\":true,\"code\":\"CREATED\",\"message\":\"config created\"}", false)
        }
    }
}
