@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
@Config(sdk = [33], shadows = [RebuildSourceCommandShadow::class, RootFrameworkShadow::class],
    instrumentedPackages = ["com.vowifi.sw.manager.data"])
class ConfigurationRebuildSourceHostTest {
    @Test fun inactiveFileIsNotReadAndUsesTheNormalFormatGateWhenItsCardBecomesActive() = runBlocking {
        for (malformed in listOf(false, true)) {
            RebuildFixture(retained = true, malformedRetained = malformed).use { fixture ->
                fixture.ready()
                assertTrue(fixture.source.state.value is ConfigurationUiState.Ready)
                assertFalse(RebuildSourceCommandShadow.reads.contains(RETAINED_TEST_KEY))
                assertTrue(RebuildSourceCommandShadow.writes.isEmpty())
                fixture.activateRetained()
                fixture.settled()
                val refreshed = fixture.source.refreshAndAwait()
                if (malformed) assertTrue(refreshed is RefreshResult.Failed)
                else assertEquals(RefreshResult.Completed, refreshed)
                assertTrue(RebuildSourceCommandShadow.reads.contains(RETAINED_TEST_KEY))
                if (malformed) assertTrue(fixture.source.state.value is ConfigurationUiState.Failure)
                else {
                    val rows = (fixture.source.state.value as ConfigurationUiState.Ready).instances
                    assertEquals(2, rows.size)
                    assertTrue(rows.all { it.formatState == ConfigurationFormatState.LEGACY })
                }
                assertTrue(RebuildSourceCommandShadow.writes.isEmpty())
            }
        }
    }

    @Test fun refreshNeverRebuildsButExplicitStoppedActionUsesFreshCandidateAndReadsBack() = runBlocking {
        RebuildFixture().use { fixture ->
            fixture.ready()
            assertTrue(RebuildSourceCommandShadow.writes.isEmpty())
            fixture.source.refreshIdentitiesAndAwait()
            assertTrue(RebuildSourceCommandShadow.writes.isEmpty())
            assertTrue(fixture.rebuild())
            val completion = fixture.completed()
            assertEquals(ConfigurationProvisionOutcome.Updated, completion.outcome)
            assertEquals(1, RebuildSourceCommandShadow.writes.size)
            val (command, payload) = RebuildSourceCommandShadow.writes.single()
            assertTrue(command.endsWith(" config rebuild $REBUILD_TEST_KEY 54 0"))
            assertFalse(payload.contains("retired.example.test"))
            assertFalse(payload.contains("retired-dns"))
            assertFalse(payload.contains("configRevision"))
            assertEquals(ConfigurationFormatState.CURRENT, fixture.instance().formatState)
        }
    }

    @Test fun lostWriteResponseReadsBackWithoutReplayingOrPretendingConfirmation() = runBlocking {
        RebuildFixture().use { fixture ->
            fixture.ready()
            RebuildSourceCommandShadow.loseResponse = true
            assertTrue(fixture.rebuild())
            assertEquals(ConfigurationProvisionOutcome.Unconfirmed, fixture.completed().outcome)
            assertEquals(ConfigurationFormatState.CURRENT, fixture.instance().formatState)
            fixture.source.refreshAndAwait()
            assertEquals(1, RebuildSourceCommandShadow.writes.size)
            assertFalse(fixture.rebuild())
        }
    }

    @Test fun runningOrFutureConfigsCannotReachDestructiveRootCommand() = runBlocking {
        for ((format, lifecycle) in listOf("legacy" to "running", "unsupported" to "stopped")) {
            RebuildFixture(format, lifecycle).use { fixture ->
                fixture.ready()
                assertFalse(fixture.rebuild())
                assertTrue(RebuildSourceCommandShadow.writes.isEmpty())
            }
        }
    }
}

private const val REBUILD_TEST_KEY = "0000000000000000001"
private const val RETAINED_TEST_KEY = "0000000000000000002"

private class RebuildFixture(format: String = "legacy", lifecycle: String = "stopped",
    retained: Boolean = false, malformedRetained: Boolean = false) : AutoCloseable {
    val source: AndroidConfigurationSource
    val handle: SimHandle
    init {
        RebuildSourceCommandShadow.format = format
        RebuildSourceCommandShadow.lifecycle = lifecycle
        RebuildSourceCommandShadow.loseResponse = false
        RebuildSourceCommandShadow.retained = retained
        RebuildSourceCommandShadow.malformedRetained = malformedRetained
        RebuildSourceCommandShadow.reads.clear()
        RebuildSourceCommandShadow.writes.clear()
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION, true)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING, false)
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfoList(listOf(
            SubscriptionInfoBuilder.newBuilder().setId(54).setSimSlotIndex(0).setIccId(REBUILD_TEST_KEY)
                .setCarrierName("Synthetic").setMcc("310").setMnc("260").buildSubscriptionInfo(),
        ))
        val handles = OpaqueIccidHandleStore()
        handle = handles.handleFor(REBUILD_TEST_KEY)
        source = AndroidConfigurationSource(context, handles, AndroidSubscriptionInventory(context),
            readNetworkOutlets = { ConfigurationNetworkOutlets(supported = true) }, readAutomaticSmsc = { null })
    }
    suspend fun ready() = withTimeout(5000) {
        source.state.first { it is ConfigurationUiState.Ready }
        settled()
    }
    suspend fun settled() = withTimeout(5000) {
        val scope = source.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(source) as CoroutineScope
        scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
    }
    fun activateRetained() {
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.getSystemService(SubscriptionManager::class.java))
            .setActiveSubscriptionInfoList(listOf(REBUILD_TEST_KEY, RETAINED_TEST_KEY).mapIndexed { slot, key ->
                SubscriptionInfoBuilder.newBuilder().setId(54 + slot).setSimSlotIndex(slot).setIccId(key)
                    .setCarrierName("Synthetic").setMcc("310").setMnc("260").buildSubscriptionInfo()
            })
        assertEquals(2, (AndroidSubscriptionInventory(context).listActive() as SubscriptionInventoryResult.Active).subscriptions.size)
    }
    fun instance() = (source.state.value as ConfigurationUiState.Ready).instances.single()
    fun rebuild(): Boolean = source.javaClass.getMethod("rebuild", SimHandle::class.java).invoke(source, handle) as Boolean
    suspend fun completed() = withTimeout(5000) {
        source.provisioning.first { handle in it.completions }.completions.getValue(handle)
    }
    override fun close() { source.close() }
}

@Implements(value = AndroidSuCommand::class, isInAndroidSdk = false)
internal class RebuildSourceCommandShadow {
    @Implementation fun run(command: String): AndroidSuResult {
        if (command.contains(" config show ")) reads += command.substringAfterLast(' ')
        val output = when {
            command.endsWith(" list") -> """[{"ok":true,"iccid":"$REBUILD_TEST_KEY","state":"$lifecycle","pid":${if (lifecycle == "running") "4242" else "null"}}${if (retained) ",{\"ok\":true,\"iccid\":\"$RETAINED_TEST_KEY\",\"state\":\"stopped\",\"pid\":null}" else ""}]"""
            command.endsWith(" config show $RETAINED_TEST_KEY") -> if (malformedRetained) {
                """{"ok":false,"code":"CONFIG_INVALID","message":"config is invalid"}"""
            } else """{"ok":true,"formatState":"legacy","lifecycle":"stopped"}"""
            command.endsWith(" config show $REBUILD_TEST_KEY") -> if (format == "current") {
                """{"ok":true,"formatState":"current","configVersion":1,"configRevision":"r1_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","displayLabel":"0001","lifecycle":"$lifecycle","transport":"wifi","smsc":"","epdgFqdn":"","imsRealm":"","dns":"","resolvedEpdgFqdn":"epdg.example.test","resolvedImsRealm":"ims.example.test","mcc":"310","mnc":"260"}"""
            } else {
                """{"ok":true,"formatState":"$format","lifecycle":"$lifecycle","epdgFqdn":"retired.example.test","dns":"retired-dns"}"""
            }
            else -> error("unexpected read command")
        }
        return AndroidSuResult(false, 0, output, false)
    }
    @Implementation fun run(command: String, stdinText: String, timeoutSeconds: Long): AndroidSuResult {
        writes += command to stdinText
        assertEquals(ConfigurationControlCommand.CREATE_TIMEOUT_SECONDS, timeoutSeconds)
        assertTrue(command.endsWith(" config rebuild $REBUILD_TEST_KEY 54 0"))
        format = "current"
        return AndroidSuResult(loseResponse, 0,
            if (loseResponse) "" else """{"ok":true,"code":"APPLIED","message":"config applied"}""", false)
    }
    companion object {
        @Volatile var format = "legacy"
        @Volatile var lifecycle = "stopped"
        @Volatile var loseResponse = false
        @Volatile var retained = false
        @Volatile var malformedRetained = false
        val reads = CopyOnWriteArrayList<String>()
        val writes = CopyOnWriteArrayList<Pair<String, String>>()
    }
}
