@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.Manifest
import android.media.*
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Looper
import android.telecom.TelecomManager
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.EngineProtocolFrame
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, shadows = [CoexistenceAudioManagerShadow::class,
    CoexistenceRecordingMonitorShadow::class, PublicationAudioTrackShadow::class, PublicationLocalSocketShadow::class,
    RootFrameworkShadow::class])
class CallAudioPublicationHostTest {
    @Test fun recordingSilencedAfterTheFinalCheckCannotPublishASuccessfulAudioSession() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE)
        val manager = app.getSystemService(AudioManager::class.java)
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        shadowOf(app.getSystemService(TelecomManager::class.java)).setIsInCall(false)
        CoexistenceRecordingMonitorShadow.reset()
        PublicationAudioTrackShadow.entered = CountDownLatch(1)
        PublicationAudioTrackShadow.resume = CountDownLatch(1)
        val microphone = CallMicrophoneLease()
        val transport = object : EngineMessageTransport {
            override val events = MutableSharedFlow<EngineProtocolFrame.Event>()
            override suspend fun request(method: String, params: JsonObject, timeoutMillis: Long) =
                EngineProtocolFrame.Response(1, null, null)
        }
        val owner = CallAudioSession("synthetic-publication-owner", "synthetic-publication-token", 1, transport)
        microphone.serviceReady("synthetic-lease")
        assertTrue(microphone.bind("synthetic-lease", owner.iccid, owner.generation, transport, owner.token))
        val controller = AndroidCallAudioController(app, microphone, CallControlTransport { _, _, _, _ ->
            EngineProtocolFrame.Response(1, null, null)
        })
        val lock = ReflectionHelpers.getField<Any>(controller, "lock")
        val worker = AtomicReference<Thread>()
        val executor = Executors.newSingleThreadExecutor()
        val result = executor.submit<Boolean> {
            worker.set(Thread.currentThread())
            try { runBlocking { controller.start(owner) }; true }
            catch (_: AndroidCallAudioException) { false }
        }
        try {
            assertTrue("真实设备启动必须到达最后的音量设置", PublicationAudioTrackShadow.entered.await(5, TimeUnit.SECONDS))
            synchronized(lock) {
                PublicationAudioTrackShadow.resume.countDown()
                val bean = ManagementFactory.getThreadMXBean()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var atPublication = false
                while (!atPublication && System.nanoTime() < deadline) {
                    val info = bean.getThreadInfo(worker.get().id)
                    atPublication = info?.threadState == Thread.State.BLOCKED &&
                        info.lockInfo?.identityHashCode == System.identityHashCode(lock) &&
                        info.lockOwnerId == Thread.currentThread().id
                    if (!atPublication) LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
                }
                assertTrue("工作线程须已通过末次失败检查并等待Controller发布锁", atPublication)
                val config = shadowOf(manager).createActiveRecordingConfiguration(3, MediaRecorder.AudioSource.VOICE_COMMUNICATION, "")
                ReflectionHelpers.setField(config, "mClientSilenced", true)
                requireNotNull(CoexistenceRecordingMonitorShadow.callback).onRecordingConfigChanged(listOf(config))
            }
            assertFalse("发布前已收到录音静音失败，start不能仍返回成功", result.get(5, TimeUnit.SECONDS))
            assertNull(ReflectionHelpers.getField<Any?>(controller, "session"))
            assertNull(CoexistenceRecordingMonitorShadow.callback)
            assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        } finally {
            PublicationAudioTrackShadow.resume.countDown()
            controller.release()
            microphone.clear("synthetic-lease")
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}

@Implements(AudioTrack::class)
class PublicationAudioTrackShadow : ShadowAudioTrack() {
    @Implementation fun setVolume(volume: Float): Int {
        if (CoexistenceRecordingMonitorShadow.callback != null && entered.count > 0) {
            entered.countDown()
            if (!resume.await(5, TimeUnit.SECONDS)) throw IllegalStateException("bounded publication barrier expired")
        }
        return AudioTrack.SUCCESS
    }
    companion object {
        var entered = CountDownLatch(1)
        var resume = CountDownLatch(1)
    }
}

/** No real engine/socket traffic; workers fail finitely if an invalid startup is published. */
@Implements(LocalSocket::class)
class PublicationLocalSocketShadow {
    @Implementation fun connect(address: LocalSocketAddress) = Unit
    @Implementation fun setSoTimeout(value: Int) = Unit
    @Implementation fun getInputStream(): InputStream = object : InputStream() {
        override fun read(): Int = throw IOException("synthetic closed audio input")
    }
    @Implementation fun getOutputStream(): OutputStream = object : OutputStream() {
        override fun write(value: Int) = throw IOException("synthetic closed audio output")
    }
    @Implementation fun shutdownInput() = Unit
    @Implementation fun shutdownOutput() = Unit
    @Implementation fun close() = Unit
}
