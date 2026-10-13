package com.vowifi.sw.manager.data

import android.content.Context
import android.os.UserManager

class SoftVoWiFiRuntime internal constructor(
    val pool: AndroidEngineClientPool,
    val calls: AndroidCallSource,
    val history: CallHistoryRepository,
    val notifications: AndroidIncomingCallNotificationCoordinator,
    val messages: AndroidMessageSource,
    val messageNotifications: AndroidMessageNotifications,
    val callNotificationSettings: AndroidCallNotificationSettingsSource,
    private val networkObserver: AndroidUnderlayObserver,
) {
    fun observeNetworks() = networkObserver.start()
    fun stopObservingNetworks() = networkObserver.stop()
}

class SoftVoWiFiRuntimeHolder(context: Context, private val activityClass: Class<*>, private val messageIconRes: Int) {
    private val appContext = context.applicationContext
    private val owner = ProcessCallRuntimeOwner(::open)

    fun startFromApplication() {
        when (acquire(ProcessRuntimeStart.Application)) {
            is ProcessRuntimeAcquire.Ready -> Unit
            ProcessRuntimeAcquire.CredentialNotReady -> Unit
            is ProcessRuntimeAcquire.Failed -> Unit
        }
    }

    fun requireRuntime(): SoftVoWiFiRuntime {
        return when (val result = acquire(ProcessRuntimeStart.Activity)) {
            is ProcessRuntimeAcquire.Ready -> result.runtime
            ProcessRuntimeAcquire.CredentialNotReady -> throw ProcessRuntimeInitException()
            is ProcessRuntimeAcquire.Failed -> throw result.error
        }
    }

    private fun acquire(start: ProcessRuntimeStart): ProcessRuntimeAcquire<SoftVoWiFiRuntime> {
        val credentialEncrypted = isCredentialEncryptedStorageReady(
            userUnlocked = appContext.getSystemService(UserManager::class.java)?.isUserUnlocked == true,
            deviceProtectedStorage = appContext.isDeviceProtectedStorage,
        )
        return owner.acquire(credentialEncrypted, start)
    }

    private fun open(): SoftVoWiFiRuntime {
        val pool = AndroidEngineClientPool()
        var history: CallHistoryRepository? = null
        var openedCalls: AndroidCallSource? = null
        var callNotifications: AndroidIncomingCallNotificationCoordinator? = null
        try {
            val opened = AndroidCallHistoryRepository.open(appContext)
            history = opened
            val calls = AndroidCallSource(appContext, opened)
            openedCalls = calls
            val notifications = AndroidIncomingCallNotificationCoordinator(
                context = appContext,
                callState = calls.state,
                callbacks = IncomingCallNotificationCallbacks(
                    onAnswer = { calls.answer() },
                    onReject = { calls.reject() },
                ),
                actionReceiverClass = Class.forName("com.vowifi.sw.IncomingCallActionReceiver"),
                callsActivityClass = Class.forName("com.vowifi.sw.CallActivity"),
                onBoundAction = calls::act,
            )
            callNotifications = notifications
            notifications.start()
            val messageNotifications = AndroidMessageNotifications(appContext, activityClass, messageIconRes)
            val messages = AndroidMessageSource(pool, appContext, notifications = messageNotifications)
            return SoftVoWiFiRuntime(
                pool = pool,
                calls = calls,
                history = opened,
                notifications = notifications,
                messages = messages,
                messageNotifications = messageNotifications,
                callNotificationSettings = AndroidCallNotificationSettingsSource(appContext),
                networkObserver = AndroidUnderlayObserver(appContext),
            )
        } catch (error: Exception) {
            callNotifications?.stop()
            openedCalls?.close()
            history?.close()
            pool.close()
            throw error
        }
    }
}
