package com.vowifi.sw

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.telephony.SubscriptionManager
import androidx.activity.ComponentActivity
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder
import org.robolectric.util.ReflectionHelpers

internal class CallEntryFixture : AutoCloseable {
    val context = RuntimeEnvironment.getApplication() as SoftVoWiFiApplication
    val ownerA = SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43))!!
    val ownerB = SimOwnerId.parse("sim-owner-v1:" + "b".repeat(43))!!
    val first = CallDualSocketPeer()
    val second = CallDualSocketPeer()
    val audioStarts = CopyOnWriteArrayList<CallAudioSession>()
    @Volatile var secondPid = 2L
    private val databaseType = Class.forName("com.vowifi.sw.manager.data.SoftVoWiFiCallDatabase").asSubclass(RoomDatabase::class.java)
    private val database = Room.inMemoryDatabaseBuilder(context, databaseType).setDriver(AndroidSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO.limitedParallelism(1)).build()
    val repository = Class.forName("com.vowifi.sw.manager.data.AndroidCallHistoryRepository")
        .getConstructor(databaseType).newInstance(database) as CallHistoryRepository
    val source: AndroidCallSource
    val delegate: CallSource
    val runtime: SoftVoWiFiRuntime

    init {
        CallEntryPlatform.fixture = this
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.POST_NOTIFICATIONS)
        val subscriptions = listOf(CallDualBackendFixture.ICCID_A, CallDualBackendFixture.ICCID_B).mapIndexed { slot, owner ->
            SubscriptionInfoBuilder.newBuilder().setId(slot + 1).setSimSlotIndex(slot).setIccId(owner).buildSubscriptionInfo()
        }
        shadowOf(context.getSystemService(SubscriptionManager::class.java)).setActiveSubscriptionInfoList(subscriptions)
        source = AndroidCallSource(context, repository)
        delegate = ReflectionHelpers.getField(source, "delegate")
        val notifications = AndroidIncomingCallNotificationCoordinator(context, source.state,
            IncomingCallNotificationCallbacks(source::answer, source::reject), IncomingCallActionReceiver::class.java,
            CallActivity::class.java, source::act)
        runtime = SoftVoWiFiRuntime::class.java.cast(sun.reflect.ReflectionFactory.getReflectionFactory()
            .newConstructorForSerialization(SoftVoWiFiRuntime::class.java, Any::class.java.getDeclaredConstructor()).newInstance())!!
        SoftVoWiFiRuntime::class.java.declaredMethods.singleOrNull { it.name == "\$\$robo\$init" }?.let {
            it.isAccessible = true
            it.invoke(runtime)
        }
        ReflectionHelpers.setField(runtime, "calls", source)
        ReflectionHelpers.setField(runtime, "history", repository)
        ReflectionHelpers.setField(runtime, "notifications", notifications)
        ReflectionHelpers.setField(runtime, "networkObserver", com.vowifi.sw.manager.data.AndroidUnderlayObserver(context, publish = { false }))
    }

    fun instanceJson(): String = buildJsonArray {
        listOf(CallDualBackendFixture.ICCID_A, CallDualBackendFixture.ICCID_B).forEachIndexed { index, owner ->
            add(buildJsonObject { put("ok", true); put("iccid", owner); put("state", "running"); put("pid", if (index == 0) 1 else secondPid) })
        }
    }.toString()

    suspend fun connected() {
        withTimeout(3000) { source.lines.first { it.choices.count { line -> line.syncReady && line.registered == true } == 2 } }
        shadowOf(Looper.getMainLooper()).idle()
    }

    suspend fun ready(predicate: (CallUiState.Ready) -> Boolean): CallUiState.Ready {
        val ready = withTimeout(3000) { delegate.state.first { it is CallUiState.Ready && predicate(it) } } as CallUiState.Ready
        shadowOf(Looper.getMainLooper()).idle()
        return ready
    }

    suspend fun history(outcome: CallOutcome) {
        withTimeout(3000) { source.history.first { it is CallHistoryState.Ready && it.records.singleOrNull()?.outcome == outcome } }
    }

    override fun close() {
        runtime.stopObservingNetworks()
        runtime.notifications.stop()
        source.close()
        first.close(); second.close(); repository.close()
    }
}

class CallEntryActivity : ComponentActivity() {
    internal lateinit var calls: CallPermissionActions
    internal lateinit var processRuntime: SoftVoWiFiRuntime
    val serviceRequest = CompletableDeferred<Intent>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        processRuntime = (application as SoftVoWiFiApplication).runtime()
        calls = CallPermissionActions(this, processRuntime.calls.lifecycle)
    }
    override fun onResume() {
        super.onResume()
        processRuntime.calls.lifecycle.setVisible(this, true)
        calls.resume()
    }
    override fun onPostResume() { super.onPostResume(); calls.resume() }
    override fun onPause() { processRuntime.calls.lifecycle.setVisible(this, false); super.onPause() }
    override fun startForegroundService(service: Intent): ComponentName {
        serviceRequest.complete(Intent(service))
        return requireNotNull(service.component)
    }
}
