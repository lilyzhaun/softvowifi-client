@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.data.AndroidSuResult
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationUiState
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
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
@Config(sdk = [33], shadows = [S3ConfigurationCommandShadow::class],
    instrumentedPackages = ["com.vowifi.sw.manager.data"])
class S3ConfigurationRefreshBehaviorHostTest {
    @Test fun heldOrdinaryReadDoesNotUpgradeToOrReplayIdentityWork() = runBlocking {
        S3ConfigurationIoFixture().use { fixture ->
            val before = fixture.ready()
            val lists = S3ConfigurationCommandShadow.listCalls()
            val hold = fixture.holdList()
            try {
                S3ConfigurationCommandShadow.smsc = "+15550000002"
                fixture.source.refresh()
                hold.awaitEntered()
                fixture.source.refresh()
                fixture.source.refreshIdentities()
                assertEquals(before, fixture.source.state.value)
                assertEquals(lists + 1, S3ConfigurationCommandShadow.listCalls())
                assertTrue(S3ConfigurationCommandShadow.identities().isEmpty())
                hold.release()
                fixture.awaitSmsc("+15550000002")
                assertEquals(lists + 1, S3ConfigurationCommandShadow.listCalls())
                assertTrue(S3ConfigurationCommandShadow.identities().isEmpty())
            } finally { hold.release() }
        }
    }

    @Test fun explicitIdentityIsExclusiveAndReadsBackOnlyTheActiveStoppedTarget() = runBlocking {
        S3ConfigurationIoFixture().use { fixture ->
            val before = fixture.ready()
            val lists = S3ConfigurationCommandShadow.listCalls()
            val hold = fixture.holdIdentity()
            try {
                fixture.source.refreshIdentities()
                hold.awaitEntered()
                fixture.source.refreshIdentities()
                fixture.source.refresh()
                assertEquals(before, fixture.source.state.value)
                assertEquals(listOf(ConfigurationControlCommand.refreshIdentityLine(S3_CONFIG_TARGET, 1, 0)),
                    S3ConfigurationCommandShadow.identities())
                S3ConfigurationCommandShadow.smsc = "+15550000003"
                hold.release()
                fixture.awaitSmsc("+15550000003")
                assertEquals(lists + 2, S3ConfigurationCommandShadow.listCalls()) // identity 前读 + 真实读回。
                assertEquals(1, S3ConfigurationCommandShadow.identities().size)
                assertNull((fixture.source.state.value as ConfigurationUiState.Ready).message)
            } finally { hold.release() }
        }
    }
}

internal const val S3_CONFIG_TARGET = "0000000000000000001"

// 复用 AndroidDnsRepairTest 的命令输出；catalog/identity reader/DNS/source 都是实际生产类。
internal class S3ConfigurationIoFixture : AutoCloseable {
    val source: AndroidConfigurationSource
    private val handles = OpaqueIccidHandleStore()
    val targetId = ConfigurationInstanceId(handles.handleFor(S3_CONFIG_TARGET).value)
    @Volatile var inventoryFailure: SubscriptionInventoryResult? = null
    private val holds = mutableListOf<S3IoHold>()
    init {
        S3ConfigurationCommandShadow.commands.clear()
        S3ConfigurationCommandShadow.nextList.set(null)
        S3ConfigurationCommandShadow.nextIdentity.set(null)
        S3ConfigurationCommandShadow.smsc = "+15550000001"
        S3ConfigurationCommandShadow.identityResult = AndroidSuResult(false, 0,
            "{\"ok\":true,\"code\":\"APPLIED\",\"message\":\"config applied\"}", false)
        DnsRepairCommandShadow.targetDns = "198.51.100.8:53" // 避免本测试混入 DNS 写动作。
        val context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION, true)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING, false)
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfoList(listOf(
            SubscriptionInfoBuilder.newBuilder().setId(1).setSimSlotIndex(0).setIccId(S3_CONFIG_TARGET)
                .setCarrierName("Synthetic").setMcc("001").setMnc("01").buildSubscriptionInfo(),
        ))
        val inventory = AndroidSubscriptionInventory(context)
        val controlledInventory = object : SubscriptionInventory by inventory {
            override fun listActive() = inventoryFailure ?: inventory.listActive()
        }
        source = AndroidConfigurationSource(context, handles, controlledInventory,
            NetworkDnsLookup { DnsEndpoint.v4("192.0.2.53") }, onCreated = { error("不应创建候选") })
    }
    suspend fun ready(): ConfigurationUiState.Ready = withTimeout(3000) {
        val state = source.state.first { it is ConfigurationUiState.Ready } as ConfigurationUiState.Ready
        joinCurrentProducer() // 初始夹具就绪还须真实 job 结束，不能以 Ready 推断 gate 已释放。
        state
    }
    suspend fun awaitSmsc(value: String) = withTimeout(3000) {
        source.state.first { it is ConfigurationUiState.Ready &&
            it.instances.single { row -> row.instanceId == targetId }.smsc == value }
        joinCurrentProducer()
    }
    private suspend fun joinCurrentProducer() {
        val scope = source.javaClass.getDeclaredField("scope").apply { isAccessible = true }.get(source) as CoroutineScope
        val lock = source.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(source)
        val completion = synchronized(lock) {
            val flight = source.javaClass.getDeclaredField("refreshInFlight").apply { isAccessible = true }.get(source)
            flight?.let { it.javaClass.getDeclaredField("completion").apply { isAccessible = true }.get(it) as Deferred<*> }
        }
        awaitS3ConfigurationProducer(scope, completion)
    }
    fun holdList() = S3IoHold().also { holds += it; S3ConfigurationCommandShadow.nextList.set(it) }
    fun holdIdentity() = S3IoHold().also { holds += it; S3ConfigurationCommandShadow.nextIdentity.set(it) }
    override fun close() { holds.forEach { it.release() }; source.close() }
}

internal suspend fun awaitS3ConfigurationProducer(scope: CoroutineScope, completion: Deferred<*>?) {
    // A producer can leave the parent job tree before its completion handler releases the gate.
    completion?.await()
    scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
}

@Implements(value = AndroidSuCommand::class, isInAndroidSdk = false)
internal class S3ConfigurationCommandShadow {
    @Implementation fun run(command: String): AndroidSuResult {
        commands += command
        if (command == ConfigurationControlCommand.listLine()) nextList.getAndSet(null)?.block()
        val result: AndroidSuResult = DnsRepairCommandShadow().run(command)
        return if (command == ConfigurationControlCommand.showLine(S3_CONFIG_TARGET))
            result.copy(output = result.output.replace("+15550000001", smsc)) else result
    }
    @Implementation fun run(command: String, stdinText: String, timeoutSeconds: Long): AndroidSuResult {
        assertEquals(ConfigurationControlCommand.refreshIdentityLine(S3_CONFIG_TARGET, 1, 0), command)
        assertEquals(ConfigurationControlCommand.CREATE_TIMEOUT_SECONDS, timeoutSeconds)
        assertTrue(stdinText.isNotBlank()) // 不保存或输出身份候选；stdin 的精确守护仍由旧 host 测试承担。
        commands += command
        nextIdentity.getAndSet(null)?.block()
        return identityResult
    }
    companion object {
        val commands = ConcurrentLinkedQueue<String>()
        val nextList = AtomicReference<S3IoHold?>()
        val nextIdentity = AtomicReference<S3IoHold?>()
        @Volatile var smsc = "+15550000001"
        @Volatile var identityResult = AndroidSuResult(false, 0,
            "{\"ok\":true,\"code\":\"APPLIED\",\"message\":\"config applied\"}", false)
        fun listCalls() = commands.count { it == ConfigurationControlCommand.listLine() }
        fun identities() = commands.filter { it.contains(" config refresh-identity ") }
    }
}
