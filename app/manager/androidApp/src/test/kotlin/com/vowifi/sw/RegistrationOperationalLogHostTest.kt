@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
package com.vowifi.sw

import android.app.Application
import android.telephony.ims.ImsRegistrationAttributes
import android.telephony.ims.RegistrationManager
import android.telephony.ims.stub.ImsRegistrationImplBase
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RegistrationOperationalLogHostTest {
    @Test fun realCallbackSourceAndLogCatalogExposeSystemRegistrationWithItsActualSlot() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "logs/ims-registration.jsonl").apply { parentFile!!.mkdirs() }
        file.delete()
        val writer = RegistrationObserver(77, 1, null, { it.run() }, file)
        val callback = RegistrationObserver::class.java.getDeclaredField("callback").apply { isAccessible = true }
            .get(writer) as RegistrationManager.RegistrationCallback
        // The public compile SDK exposes callback attributes, but not their system-only builder.
        // Use the verified SDK33 fixture builder; production still only consumes public callbacks.
        val builderType = Class.forName("android.telephony.ims.ImsRegistrationAttributes\$Builder")
        val builder = builderType.getConstructor(Int::class.javaPrimitiveType)
            .newInstance(ImsRegistrationImplBase.REGISTRATION_TECH_IWLAN)
        callback.onRegistered(builderType.getMethod("build").invoke(builder) as ImsRegistrationAttributes)
        val observer = AndroidObserverLogSource(context)
        val source = AndroidOperationalLogSource(
            LogCommand { AndroidSuResult(false, 1, "", false) },
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            registration = RegistrationOperationalLogReader(observer),
        )
        try {
            val ready = withTimeout(5000) { source.state.first {
                it is OperationalLogUiState.Ready && !it.loadInfo.reading
            } } as OperationalLogUiState.Ready
            assertEquals(SYSTEM_REGISTRATION_LOG_ID, ready.selectedFileId)
            assertEquals(RegistrationOperationalLogReader.FILE, ready.files.single())
            val entry = ready.entries.single()
            assertEquals("系统 IMS 已注册", entry.headline)
            assertEquals("SIM 2", entry.displayLabel)
            assertEquals(OperationalLogOrigin.OBSERVER, entry.origin)
            assertTrue(entry.safeSummary.contains("Wi-Fi"))
            assertFalse(entry.technical)
            assertNull(ready.loadInfo.issue)
            assertTrue(file.readText().contains("\"slot\":1"))
            source.close()
            assertEquals(RefreshResult.Completed, observer.refreshAndAwait())
        } finally { source.close(); observer.close(); file.delete() }
    }

    @Test fun legacySubIdIsNotMistakenForASimSlotAndFreeTextNeverEntersTheView() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "logs/ims-registration.jsonl").apply { parentFile!!.mkdirs() }
        file.writeText("""{"timestampMs":1790000000000,"subId":1,"state":"UNREGISTERED","reasonCode":123,"extraMessage":"PRIVATE-CANARY sip:user@example.invalid","featureTags":["PRIVATE-CANARY"]}""")
        val observer = AndroidObserverLogSource(context)
        try {
            val reader = RegistrationOperationalLogReader(observer)
            val entry = withTimeout(5000) { reader.read() }.tail.lines.single()
            assertEquals("槽位未知", entry.displayLabel)
            assertEquals("系统 IMS 未注册", entry.headline)
            assertTrue(entry.safeDetail.contains("123"))
            assertFalse(entry.safeSummary.contains("PRIVATE-CANARY"))
            assertFalse(entry.safeDetail.contains("PRIVATE-CANARY"))
            assertFalse(entry.safeDetail.contains("example.invalid"))
            assertEquals("槽位未知", reader.entry(RegistrationEvent(1, 1, "REGISTERED", slot = 9)).displayLabel)
        } finally { observer.close(); file.delete() }
    }
}
