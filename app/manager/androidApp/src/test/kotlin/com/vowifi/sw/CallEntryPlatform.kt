package com.vowifi.sw

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.vowifi.sw.manager.data.*
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers

internal object CallEntryPlatform {
    lateinit var fixture: CallEntryFixture
}

@Implements(SoftVoWiFiApplication::class)
class CallEntryApplicationShadow : org.robolectric.shadows.ShadowApplication() {
    @Implementation fun onCreate() = Unit
    @Implementation fun runtime(): SoftVoWiFiRuntime = CallEntryPlatform.fixture.runtime
}

@Implements(className = "com.vowifi.sw.manager.data.AndroidSuCommand", isInAndroidSdk = false)
class CallEntryShellShadow {
    @Implementation fun run(command: String): Any {
        check(command == "/data/adb/modules/vowifi_sw/control.sh list")
        return Class.forName("com.vowifi.sw.manager.data.AndroidSuResult")
            .getConstructor(Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
                Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .newInstance(false, 0, CallEntryPlatform.fixture.instanceJson(), false, false)
    }
}

@Implements(className = "com.vowifi.sw.manager.data.AndroidSimOwnerHmac", isInAndroidSdk = false)
class CallEntryHmacShadow {
    @Implementation fun __constructor__() = Unit
    @Implementation fun ensureKey() = Unit
    @Implementation fun ownerIdFor(raw: String): SimOwnerId = when (raw) {
        CallDualBackendFixture.ICCID_A -> CallEntryPlatform.fixture.ownerA
        CallDualBackendFixture.ICCID_B -> CallEntryPlatform.fixture.ownerB
        else -> error("unexpected synthetic owner")
    }
}

@Implements(LocalSocket::class)
class CallEntrySocketShadow {
    private var socket: CallTestSocket? = null
    @Implementation fun connect(endpoint: LocalSocketAddress) {
        val owner = endpoint.name.substringAfterLast('/').removeSuffix(".sock")
        socket = when (owner) {
            CallDualBackendFixture.ICCID_A -> CallEntryPlatform.fixture.first.socket()
            CallDualBackendFixture.ICCID_B -> CallEntryPlatform.fixture.second.socket()
            else -> error("unexpected synthetic socket")
        } as CallTestSocket
    }
    @Implementation fun getInputStream() = requireNotNull(socket).inputStream
    @Implementation fun getOutputStream() = requireNotNull(socket).outputStream
    @Implementation fun setSoTimeout(timeout: Int) = Unit
    @Implementation fun shutdownInput() { socket?.shutdownInput() }
    @Implementation fun shutdownOutput() { socket?.shutdownOutput() }
    @Implementation fun close() { socket?.close() }
}

@Implements(className = "com.vowifi.sw.manager.data.AndroidCallAudioController", isInAndroidSdk = false)
class CallEntryAudioShadow {
    @RealObject private lateinit var controller: Any
    @Implementation suspend fun start(session: CallAudioSession) {
        val lease = ReflectionHelpers.getField<CallMicrophoneLease>(controller, "microphone")
        check(lease.permits(session))
        CallEntryPlatform.fixture.audioStarts += session
    }
}
