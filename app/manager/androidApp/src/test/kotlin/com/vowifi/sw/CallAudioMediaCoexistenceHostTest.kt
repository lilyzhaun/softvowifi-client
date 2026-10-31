@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package com.vowifi.sw

import android.Manifest
import android.media.*
import android.os.Looper
import android.telecom.TelecomManager
import com.vowifi.sw.manager.data.AndroidCallAudioDevices
import com.vowifi.sw.manager.data.AndroidCallAudioException
import com.vowifi.sw.manager.model.CallAudioControls
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.shadows.ShadowAudioTrack

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE, shadows = [CoexistenceAudioManagerShadow::class,
    CoexistenceRecordingMonitorShadow::class, RootFrameworkShadow::class])
class CallAudioMediaCoexistenceHostTest {
    @Test fun conversationRequestsDuckingRatherThanPausingTheUsersExistingMedia() = withConversation { fixture ->
        val request = requireNotNull(shadowOf(fixture.manager).lastAudioFocusRequest).audioFocusRequest
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, request.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, request.audioAttributes.contentType)
        assertEquals("开启通话不能要求现有媒体暂停", AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, request.focusGain)
    }

    @Test fun mediaPlaybackMessageClearsWhenTheOtherAppStopsPlayback() = withConversation { fixture ->
        val platform = shadowOf(fixture.manager)
        val voice = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        platform.setActivePlaybackConfigurationsFor(listOf(voice, media), true)
        requireNotNull(platform.lastAudioFocusRequest).listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("正在同时播放其他音频，通话音量已降低", fixture.route.message)
        platform.setActivePlaybackConfigurationsFor(listOf(voice), true)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull("其它音频结束后共存提示应清除", fixture.route.message)
        assertFalse("停止播放不等于系统重新授予焦点", fixture.policy.hasAudioFocus)
        assertEquals("不能借清提示改变现有共存增益", 0.5f, fixture.policy.outputGain, 0f)
        platform.setActivePlaybackConfigurationsFor(listOf(voice, media), true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("再次播放须重新显示，而非永久隐藏", "正在同时播放其他音频，通话音量已降低", fixture.route.message)
        platform.setActivePlaybackConfigurationsFor(emptyList(), true)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(fixture.route.message)
        assertEquals(0, fixture.ended)
        assertEquals(1, CoexistenceAudioManagerShadow.requests)
    }

    @Test fun playbackChangesCannotClearAnActualRouteFailureMessage() = withConversation { fixture ->
        val platform = shadowOf(fixture.manager)
        val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        platform.setActivePlaybackConfigurationsFor(listOf(media), true)
        requireNotNull(platform.lastAudioFocusRequest).listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertFalse(fixture.devices.enableSpeaker()) // No speaker in this fixture's available devices.
        assertEquals("无法切换音频设备", fixture.route.message)
        platform.setActivePlaybackConfigurationsFor(emptyList(), true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("媒体结束只能清共存说明，不能清真实路由错误", "无法切换音频设备", fixture.route.message)
    }

    @Test fun closingConversationUnregistersPlaybackUpdatesAndIgnoresQueuedOldCallbacks() = withConversation { fixture ->
        val callback = requireNotNull(CoexistenceAudioManagerShadow.lastPlaybackCallback)
        shadowOf(fixture.manager).setActivePlaybackConfigurationsFor(listOf(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()), false)
        val queued = fixture.manager.activePlaybackConfigurations
        assertEquals(1, CoexistenceAudioManagerShadow.playbackCallbacks.size)
        fixture.close()
        assertTrue(CoexistenceAudioManagerShadow.playbackCallbacks.isEmpty())
        val last = fixture.route
        callback.onPlaybackConfigChanged(queued)
        assertEquals(last, fixture.route)
        assertEquals(0, fixture.ended)
    }

    @Test fun playbackEndsBeforeTheLossCallbackCannotCreateAStaleHint() = withConversation { fixture ->
        val platform = shadowOf(fixture.manager)
        val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build()
        platform.setActivePlaybackConfigurationsFor(listOf(media), true)
        platform.setActivePlaybackConfigurationsFor(emptyList(), true)
        requireNotNull(platform.lastAudioFocusRequest).listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertNull("焦点丢失不是仍在播放的证明", fixture.route.message)
        assertFalse(fixture.policy.hasAudioFocus)
        assertEquals(0, fixture.ended)
    }

    @Test fun optionalPlaybackObserverRegistrationFailureCannotBreakTheCall() = withConversation(
        beforeCreate = { CoexistenceAudioManagerShadow.denyPlaybackRegistration = true },
    ) { fixture ->
        requireNotNull(shadowOf(fixture.manager).lastAudioFocusRequest).listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertNull(fixture.route.message)
        assertEquals(0, fixture.ended)
        assertTrue(CoexistenceAudioManagerShadow.playbackCallbacks.isEmpty())
        val pcm = ByteArray(640) { 42 }
        assertEquals(640, fixture.devices.write(pcm, 0, pcm.size))
        assertEquals(640, fixture.written)
        assertEquals(640, fixture.devices.read(pcm))
        assertTrue(pcm.all { it == 42.toByte() })
    }

    @Test fun playbackObserverFailureAfterPlatformRegistrationRollsBackTheLocalCallback() = withConversation(
        beforeCreate = { CoexistenceAudioManagerShadow.throwAfterPlaybackRegistration = true },
    ) { fixture ->
        assertTrue("回调注册异常不能留下AudioManager本地引用", CoexistenceAudioManagerShadow.registeredThenThrew)
        assertTrue(CoexistenceAudioManagerShadow.playbackCallbacks.isEmpty())
        requireNotNull(shadowOf(fixture.manager).lastAudioFocusRequest).listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        val pcm = ByteArray(640) { 42 }
        assertEquals(640, fixture.devices.write(pcm, 0, pcm.size))
        assertEquals(640, fixture.written)
        assertEquals(0, fixture.ended)
        val oldCallback = requireNotNull(CoexistenceAudioManagerShadow.lastPlaybackCallback)
        oldCallback.onPlaybackConfigChanged(emptyList())
        assertNull("回滚后的旧回调不能恢复已撤销的共存状态", fixture.route.message)
    }

    @Test fun anUnavailablePlaybackSnapshotIsUnknownAndLaterCallbacksCanRecoverTheHint() = withConversation { fixture ->
        CoexistenceAudioManagerShadow.denyPlaybackRead = true
        requireNotNull(shadowOf(fixture.manager).lastAudioFocusRequest).listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertNull("查询失败不能猜测媒体仍在播放", fixture.route.message)
        CoexistenceAudioManagerShadow.denyPlaybackRead = false
        val platform = shadowOf(fixture.manager)
        val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build()
        repeat(3) {
            platform.setActivePlaybackConfigurationsFor(listOf(media), true)
            assertNotNull(fixture.route.message)
            platform.setActivePlaybackConfigurationsFor(emptyList(), true)
            assertNull(fixture.route.message)
        }
        assertEquals(0, fixture.ended)
        assertEquals(1, CoexistenceAudioManagerShadow.requests)
    }

    @Test fun strictPolicyDoesNotRegisterTheConversationOnlyPlaybackObserver() {
        val manager = RuntimeEnvironment.getApplication().getSystemService(AudioManager::class.java)
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        CoexistenceAudioManagerShadow.playbackCallbacks.clear()
        val policy = com.vowifi.sw.manager.data.AndroidCallAudioPolicy(manager, {}, {})
        try {
            assertTrue(policy.acquire())
            assertTrue(CoexistenceAudioManagerShadow.playbackCallbacks.isEmpty())
        } finally { policy.close() }
    }

    @Test fun permanentMediaFocusLossWithoutAnyLaterGainKeepsLiveDuplexAndCommunicationRoute() =
        mediaLoss(AudioManager.AUDIOFOCUS_LOSS)

    @Test fun duckableMediaFocusLossKeepsLiveDuplexInsteadOfZeroingTheMicrophone() =
        mediaLoss(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

    private fun mediaLoss(loss: Int) = withConversation { fixture ->
        val platform = shadowOf(fixture.manager)
        platform.setActivePlaybackConfigurationsFor(listOf(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
        ))
        val listener = requireNotNull(platform.lastAudioFocusRequest).listener
        listener.onAudioFocusChange(loss)
        assertEquals("普通媒体抢占不能结束通话音频", 0, fixture.ended)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, fixture.manager.mode)
        assertSame(fixture.headset, fixture.manager.communicationDevice)
        assertFalse(fixture.policy.hasAudioFocus)
        assertEquals(0.5f, fixture.policy.outputGain, 0f)
        repeat(3) {
            val pcm = ByteArray(640) { 42 }
            val before = fixture.written
            assertEquals(640, fixture.devices.write(pcm, 0, pcm.size))
            assertEquals("没有GAIN也必须继续写入新通话PCM", before + 640, fixture.written)
            val captured = ByteArray(640)
            assertEquals(640, fixture.devices.read(captured))
            assertTrue("用户未静音时不能因媒体焦点将麦克风清零", captured.all { it == 42.toByte() })
        }
        assertEquals("不得循环抢回其它软件的音频焦点", 1, CoexistenceAudioManagerShadow.requests)
        fixture.devices.close()
        assertEquals(AudioManager.MODE_NORMAL, fixture.manager.mode)
        assertNotNull(platform.lastAbandonedAudioFocusRequest)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals("关闭后迟到GAIN不能复活通话", AudioManager.MODE_NORMAL, fixture.manager.mode)
    }

    @Test fun aRealSystemCallStillInterruptsRatherThanMixingWithTheCall() = withConversation { fixture ->
        fixture.manager.mode = AudioManager.MODE_IN_CALL
        requireNotNull(shadowOf(fixture.manager).lastAudioFocusRequest).listener
            .onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals("系统电话不能被媒体共存策略忽略", 1, fixture.ended)
        assertEquals(AudioManager.MODE_IN_CALL, fixture.manager.mode)
    }

    @Test fun aPhoneModeChangeAfterPermanentFocusLossStillEndsOnlyOnceAndDoesNotRestoreOverThePhone() =
        withConversation { fixture ->
            val listener = requireNotNull(shadowOf(fixture.manager).lastAudioFocusRequest).listener
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            assertEquals(0, fixture.ended)
            fixture.manager.mode = AudioManager.MODE_IN_CALL
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, fixture.ended)
            assertEquals(AudioManager.MODE_IN_CALL, fixture.manager.mode)
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            assertEquals(1, fixture.ended)
        }

    @Test fun recordingSilencedCallbackStopsTheActualDevicesAndLateCallbacksCannotRepeatIt() = withConversation { fixture ->
        assertTrue(CoexistenceRecordingMonitorShadow.registeredBeforeStart)
        val callback = requireNotNull(CoexistenceRecordingMonitorShadow.callback)
        val config = shadowOf(fixture.manager).createActiveRecordingConfiguration(3, MediaRecorder.AudioSource.VOICE_COMMUNICATION, "")
        org.robolectric.util.ReflectionHelpers.setField(config, "mClientSilenced", true)
        callback.onRecordingConfigChanged(listOf(config))
        assertEquals(1, fixture.ended)
        fixture.close()
        callback.onRecordingConfigChanged(listOf(config))
        assertEquals("已退役录音回调不能再次中断", 1, fixture.ended)
        assertFalse(org.robolectric.util.ReflectionHelpers.getField(fixture.devices, "recordingCallbackRegistered"))
        assertNull(CoexistenceRecordingMonitorShadow.callback)
    }

    @Test fun recordingAlreadySilencedAtStartCannotClaimLiveAudio() = withConversation(
        beforeStart = { fixture ->
            val config = shadowOf(fixture.manager).createActiveRecordingConfiguration(3, MediaRecorder.AudioSource.VOICE_COMMUNICATION, "")
            org.robolectric.util.ReflectionHelpers.setField(config, "mClientSilenced", true)
            CoexistenceRecordingMonitorShadow.configuration = config
        }, failStart = true,
    ) { fixture ->
        assertTrue(CoexistenceRecordingMonitorShadow.registeredBeforeStart)
        assertEquals(0, fixture.written)
    }

    @Test fun recordingCallbackRegistrationDeniedCannotLeaveCoexistenceRunningUnprotected() = withConversation(
        beforeStart = { CoexistenceRecordingMonitorShadow.denyRegistration = true }, failStart = true,
    ) { fixture -> assertEquals(0, fixture.written) }

    @Test fun revokedRecordingPermissionAtFocusLossDoesNotGetTreatedAsOrdinaryMedia() = withConversation { fixture ->
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        requireNotNull(shadowOf(fixture.manager).lastAudioFocusRequest).listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(1, fixture.ended)
    }

    @Test fun telecomCallAtFocusLossStillInterruptsWhenTheAudioModeHasNotChangedYet() = withConversation { fixture ->
        val telecom = RuntimeEnvironment.getApplication().getSystemService(TelecomManager::class.java)
        shadowOf(telecom).setIsInCall(true)
        requireNotNull(shadowOf(fixture.manager).lastAudioFocusRequest).listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(1, fixture.ended)
    }

    @Test fun aValidGainRestoresOutputButDoesNotNeedASecondFocusRequest() = withConversation { fixture ->
        val listener = requireNotNull(shadowOf(fixture.manager).lastAudioFocusRequest).listener
        for (loss in listOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)) {
            listener.onAudioFocusChange(loss)
            assertFalse(fixture.policy.hasAudioFocus)
            assertEquals(0.5f, fixture.policy.outputGain, 0f)
            listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            assertTrue(fixture.policy.hasAudioFocus)
            assertEquals(1f, fixture.policy.outputGain, 0f)
        }
        assertEquals(1, CoexistenceAudioManagerShadow.requests)
    }

    private class Fixture(
        val manager: AudioManager,
        val headset: AudioDeviceInfo,
    ) {
        lateinit var devices: AndroidCallAudioDevices
        var ended = 0
        var written = 0
        var route = CallAudioControls()
        val policy get() = org.robolectric.util.ReflectionHelpers.getField<com.vowifi.sw.manager.data.AndroidCallAudioPolicy>(devices, "policy")
        fun close() { if (::devices.isInitialized) devices.close() }
    }

    private fun withConversation(beforeStart: (Fixture) -> Unit = {}, failStart: Boolean = false,
                                 beforeCreate: () -> Unit = {}, block: (Fixture) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE)
        val manager = app.getSystemService(AudioManager::class.java)
        val platform = shadowOf(manager)
        val phone = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE).build()
        val headset = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()
        platform.lockCommunicationDevice(false)
        platform.setAvailableCommunicationDevices(listOf(phone, headset))
        platform.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        manager.mode = AudioManager.MODE_NORMAL
        CoexistenceAudioManagerShadow.requests = 0
        CoexistenceAudioManagerShadow.playbackCallbacks.clear()
        CoexistenceAudioManagerShadow.lastPlaybackCallback = null
        CoexistenceAudioManagerShadow.denyPlaybackRegistration = false
        CoexistenceAudioManagerShadow.denyPlaybackRead = false
        CoexistenceAudioManagerShadow.throwAfterPlaybackRegistration = false
        CoexistenceAudioManagerShadow.registeredThenThrew = false
        CoexistenceRecordingMonitorShadow.reset()
        val telecom = app.getSystemService(TelecomManager::class.java)
        shadowOf(telecom).setIsInCall(false)
        val fixture = Fixture(manager, headset)
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInByteArray(buffer: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                buffer.fill(42, offset, offset + size)
                return size
            }
        })
        val writes = ShadowAudioTrack.OnAudioDataWrittenListener { _, bytes, _ -> fixture.written += bytes.size }
        ShadowAudioTrack.addAudioDataListener(writes)
        try {
            beforeCreate()
            fixture.devices = AndroidCallAudioDevices.create(app, { fixture.ended++; fixture.close() }, { fixture.route = it })
            beforeStart(fixture)
            if (failStart) {
                val failure = assertThrows(Exception::class.java) { fixture.devices.start() }
                assertTrue(failure is AndroidCallAudioException || failure is SecurityException)
                fixture.close()
                assertEquals(AudioManager.MODE_NORMAL, manager.mode)
                assertNull(CoexistenceRecordingMonitorShadow.callback)
            } else fixture.devices.start()
            shadowOf(Looper.getMainLooper()).idle()
            if (!failStart) assertSame(headset, manager.communicationDevice)
            block(fixture)
        } finally {
            fixture.close()
            CoexistenceAudioManagerShadow.denyPlaybackRead = false
            ShadowAudioRecord.clearSource()
            ShadowAudioTrack.removeAudioDataListener(writes)
            platform.setAvailableCommunicationDevices(emptyList())
            platform.setActivePlaybackConfigurationsFor(emptyList())
            manager.mode = AudioManager.MODE_NORMAL
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
}

/** Adapt only the own-record monitor Binder boundary; keep real AudioRecord + PCM shadows. */
@Implements(className = "android.media.AudioRecordingMonitorImpl", isInAndroidSdk = false)
class CoexistenceRecordingMonitorShadow {
    @org.robolectric.annotation.RealObject lateinit var monitor: Any
    @Implementation fun registerAudioRecordingCallback(executor: java.util.concurrent.Executor, next: AudioManager.AudioRecordingCallback) {
        if (denyRegistration) throw SecurityException("synthetic recording callback denied")
        val record = org.robolectric.util.ReflectionHelpers.getField<AudioRecord>(monitor, "mClient")
        registeredBeforeStart = record.recordingState != AudioRecord.RECORDSTATE_RECORDING
        callback = next
    }
    @Implementation fun unregisterAudioRecordingCallback(previous: AudioManager.AudioRecordingCallback) { callback = null }
    @Implementation fun getActiveRecordingConfiguration(): AudioRecordingConfiguration? = configuration
    companion object {
        var callback: AudioManager.AudioRecordingCallback? = null
        var configuration: AudioRecordingConfiguration? = null
        var registeredBeforeStart = false
        var denyRegistration = false
        fun reset() { callback = null; configuration = null; registeredBeforeStart = false; denyRegistration = false }
    }
}

@Implements(AudioManager::class)
class CoexistenceAudioManagerShadow : ShadowAudioManager() {
    @Implementation override fun requestAudioFocus(request: android.media.AudioFocusRequest): Int {
        requests++
        return super.requestAudioFocus(request)
    }
    @Implementation override fun registerAudioPlaybackCallback(callback: AudioManager.AudioPlaybackCallback, handler: android.os.Handler?) {
        if (denyPlaybackRegistration) throw SecurityException("synthetic playback observer registration denied")
        super.registerAudioPlaybackCallback(callback, handler)
        playbackCallbacks += callback
        lastPlaybackCallback = callback
        if (throwAfterPlaybackRegistration) {
            registeredThenThrew = true
            throw IllegalStateException("synthetic post-registration failure")
        }
    }
    @Implementation override fun unregisterAudioPlaybackCallback(callback: AudioManager.AudioPlaybackCallback) {
        super.unregisterAudioPlaybackCallback(callback)
        playbackCallbacks -= callback
    }
    @Implementation override fun getActivePlaybackConfigurations(): List<AudioPlaybackConfiguration> {
        if (denyPlaybackRead) throw IllegalStateException("synthetic playback snapshot unavailable")
        return super.getActivePlaybackConfigurations()
    }
    companion object {
        var requests = 0
        val playbackCallbacks = mutableSetOf<AudioManager.AudioPlaybackCallback>()
        var lastPlaybackCallback: AudioManager.AudioPlaybackCallback? = null
        var denyPlaybackRegistration = false
        var denyPlaybackRead = false
        var throwAfterPlaybackRegistration = false
        var registeredThenThrew = false
    }
}
