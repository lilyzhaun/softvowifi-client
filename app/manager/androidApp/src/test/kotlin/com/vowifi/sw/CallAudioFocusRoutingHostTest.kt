package com.vowifi.sw

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Looper
import com.vowifi.sw.manager.data.AndroidCallAudioPolicy
import com.vowifi.sw.manager.model.CallAudioControls
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.shadows.ShadowAudioTrack

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class CallAudioFocusRoutingHostTest {
    private fun device(type: Int) = AudioDeviceInfoBuilder.newBuilder().setType(type).build()

    @Test fun transientFocusAndDuckKeepTheSameCommunicationSessionUntilGain() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        val platform = shadowOf(manager)
        platform.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        var ended = 0
        val policy = AndroidCallAudioPolicy(manager, { ended++ }, {})
        try {
            assertTrue(policy.acquire())
            for (loss in listOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)) {
                policy.onAudioFocusChange(loss)
                assertEquals("临时抢占不能销毁会话或上报永久Ended", 0, ended)
                assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
                assertNull(platform.lastAbandonedAudioFocusRequest)
                policy.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
                assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
            }
            policy.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            assertEquals(1, ended)
            assertEquals(AudioManager.MODE_NORMAL, manager.mode)
            policy.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals("已结束会话不能被迟到GAIN复活", AudioManager.MODE_NORMAL, manager.mode)
        } finally { policy.close() }
    }

    @Test fun connectedCommunicationHeadsetsWinOverPhoneAndSpeakerOffReturnsToHeadset() {
        for (type in listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET)) {
            val manager = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
            val platform = shadowOf(manager)
            val phone = device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            val speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
            val headset = device(type)
            platform.lockCommunicationDevice(false)
            platform.setAvailableCommunicationDevices(listOf(phone, speaker, headset))
            assertTrue(manager.setCommunicationDevice(phone))
            platform.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            var controls = CallAudioControls()
            val policy = AndroidCallAudioPolicy(manager, { fail("路由切换不应终止音频") }, { controls = it })
            try {
                assertTrue(policy.acquire())
                shadowOf(Looper.getMainLooper()).idle()
                assertSame("连接外设后不能强留在手机听筒：type=$type", headset, manager.communicationDevice)
                assertFalse(controls.earpiece)
                assertTrue(policy.speaker(true))
                assertSame(speaker, manager.communicationDevice)
                assertTrue(controls.speakerOn)
                assertTrue(policy.speaker(false))
                assertSame(headset, manager.communicationDevice)
                assertFalse(controls.speakerOn)
            } finally { policy.close(); platform.setAvailableCommunicationDevices(emptyList()) }
        }
    }

    @Test fun deviceArrivalAndRemovalRerouteButDoNotOverrideExplicitSpeaker() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        val platform = shadowOf(manager)
        val phone = device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        val speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val headset = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        platform.lockCommunicationDevice(false)
        platform.setAvailableCommunicationDevices(listOf(phone, speaker))
        platform.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val policy = AndroidCallAudioPolicy(manager, { fail("设备变化不能终止会话") }, {})
        try {
            assertTrue(policy.acquire()); shadowOf(Looper.getMainLooper()).idle()
            assertSame(phone, manager.communicationDevice)
            platform.addAvailableCommunicationDevice(headset, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(headset, manager.communicationDevice)
            assertTrue(policy.speaker(true))
            platform.removeAvailableCommunicationDevice(headset, true)
            platform.addAvailableCommunicationDevice(headset, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(speaker, manager.communicationDevice)
            assertTrue(policy.speaker(false))
            assertSame(headset, manager.communicationDevice)
            platform.removeAvailableCommunicationDevice(headset, true)
            shadowOf(Looper.getMainLooper()).idle()
            assertSame(phone, manager.communicationDevice)
        } finally { policy.close(); platform.setAvailableCommunicationDevices(emptyList()) }
    }

    @Test
    @Config(shadows = [CallAudioCleanupFailureShadow::class])
    fun platformRouteCleanupFailureCannotEscapeFocusCallbackOrSkipFocusRelease() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        var ended = 0
        val policy = AndroidCallAudioPolicy(manager, { ended++ }, {})
        assertTrue(policy.acquire())
        policy.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(1, ended)
        assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        assertNotNull(shadowOf(manager).lastAbandonedAudioFocusRequest)
        policy.close()
        policy.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(1, ended)
    }

    @Test
    @Suppress("DEPRECATION")
    fun temporaryFocusStopsActualPcmAndCaptureWithoutClosingAndGainRestoresOnlyLiveAudio() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        var devices: AutoCloseable? = null
        val policy = AndroidCallAudioPolicy(manager, { devices?.close() }, {})
        assertTrue(policy.acquire())
        val record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16_000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, 640)
        val track = AudioTrack(AudioManager.STREAM_VOICE_CALL, 16_000, AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT, 640, AudioTrack.MODE_STREAM)
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                buffer.fill(42, offset, offset + size)
                return size
            }
        })
        var written = 0
        val listener = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ -> written += data.size }
        ShadowAudioTrack.addAudioDataListener(listener)
        val type = Class.forName("com.vowifi.sw.manager.data.AndroidCallAudioDevices")
        val output = type.constructors.single().newInstance(record, track, policy,
            { fail("临时焦点暂停不能结束音频") }) as AutoCloseable
        devices = output
        val write = type.getMethod("write", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val read = type.getMethod("read", ByteArray::class.java)
        try {
            type.getMethod("start").invoke(output)
            val pcm = ByteArray(640) { 42 }
            assertEquals(640, write.invoke(devices, pcm, 0, pcm.size))
            assertEquals(640, written)
            for (loss in listOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)) {
                policy.onAudioFocusChange(loss)
                assertEquals(640, write.invoke(devices, pcm, 0, pcm.size))
                assertEquals("暂停期间排空socket但不得写入新语音", 640, written)
                val muted = ByteArray(640)
                assertEquals(640, read.invoke(devices, muted))
                assertTrue("暂停期间不上传麦克风内容", muted.all { it == 0.toByte() })
                assertEquals(AudioRecord.STATE_INITIALIZED, record.state)
                assertEquals(AudioTrack.STATE_INITIALIZED, track.state)
                policy.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            }
            assertEquals(640, write.invoke(devices, pcm, 0, pcm.size))
            assertEquals(1280, written)
            val live = ByteArray(640)
            assertEquals(640, read.invoke(devices, live))
            assertTrue(live.all { it == 42.toByte() })
            output.close()
            policy.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(AudioRecord.STATE_UNINITIALIZED, record.state)
            assertEquals(AudioTrack.STATE_UNINITIALIZED, track.state)
            assertEquals(AudioManager.MODE_NORMAL, manager.mode)
        } finally {
            output.close()
            ShadowAudioRecord.clearSource()
            ShadowAudioTrack.removeAudioDataListener(listener)
        }
    }

    @Test fun queuedDeviceCallbacksAfterCloseCannotChangeTheNextOwnersRouteOrMode() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        val platform = shadowOf(manager)
        val phone = device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        val speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val headset = device(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        platform.lockCommunicationDevice(false)
        platform.setAvailableCommunicationDevices(listOf(phone, speaker))
        platform.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val policy = AndroidCallAudioPolicy(manager, { fail("已关闭policy不得再结束会话") }, {})
        assertTrue(policy.acquire())
        platform.addAvailableCommunicationDevice(headset, true)
        policy.close()
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        assertTrue(manager.setCommunicationDevice(speaker))
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(policy.speaker(false))
            assertEquals(AudioManager.MODE_IN_COMMUNICATION, manager.mode)
            assertSame(speaker, manager.communicationDevice)
        } finally {
            manager.clearCommunicationDevice()
            manager.mode = AudioManager.MODE_NORMAL
            platform.setAvailableCommunicationDevices(emptyList())
        }
    }
}

@Implements(AudioManager::class)
class CallAudioCleanupFailureShadow : ShadowAudioManager() {
    @Implementation override fun clearCommunicationDevice() {
        throw IllegalStateException("synthetic audio service cleanup failure")
    }
}
