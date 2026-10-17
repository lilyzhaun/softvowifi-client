package com.vowifi.sw.manager.data

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import com.vowifi.sw.manager.model.CallSyncState
import com.vowifi.sw.manager.model.CallUiState
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class IncomingCallRecoveryState {
    NONE,
    NOTIFICATIONS_DENIED,
    FULL_SCREEN_DENIED,
    FOREGROUND_SERVICE_REQUIRED,
}

class AndroidIncomingCallNotificationCoordinator(
    private val context: Context,
    private val callState: StateFlow<CallUiState>,
    private val callbacks: IncomingCallNotificationCallbacks,
    private val actionReceiverClass: Class<*>,
    private val callsActivityClass: Class<*>,
    private val onBoundAction: ((com.vowifi.sw.manager.model.CallBinding, com.vowifi.sw.manager.model.CallAction) -> Unit)? = null,
) {
    private val appContext = context.applicationContext
    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val builder = IncomingCallNotificationBuilder(appContext, actionReceiverClass, callsActivityClass)
    private val vibration = IncomingCallVibration(appContext)
    private val launches = CallLaunchRegistry { binding ->
        if (binding.token == null) UUID.randomUUID().toString() else java.security.MessageDigest.getInstance("SHA-256")
            .digest(listOf("call-v1", binding.owner, binding.epoch.orEmpty(), binding.token).joinToString("\u0000").toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun keyForCurrent(): String? = launches.update(callState.value)
    fun resolveKey(key: String?): com.vowifi.sw.manager.model.CallBinding? = launches.resolve(key, callState.value)
    fun openIntent(key: String, answer: Boolean = false): android.content.Intent = builder.buildOpenCallsIntent(key).apply {
        if (answer) action = IncomingCallAction.ANSWER.actionName
    }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var collectJob: Job? = null
    @Volatile private var appForeground = false
    private var callForeground = false
    private var microphoneRunning = false
    private var foregroundHost: Any? = null
    private var foregroundPublish: ((Notification) -> Unit)? = null
    private var foregroundRestore: (() -> Unit)? = null
    private var foregroundOwnsIncoming = false

    fun attachForegroundHost(owner: Any, publish: (Notification) -> Unit, restore: () -> Unit) {
        if (foregroundHost === owner) return
        if (foregroundOwnsIncoming) {
            cancelCurrentNotification()
            state = IncomingCallNotificationState(notificationKey = null, peerName = null)
        }
        foregroundHost = owner
        foregroundPublish = publish
        foregroundRestore = restore
        if (collectJob != null) onCallStateChanged(callState.value)
    }

    fun detachForegroundHost(owner: Any) {
        if (foregroundHost !== owner) return
        foregroundHost = null
        foregroundPublish = null
        foregroundRestore = null
        if (foregroundOwnsIncoming) {
            foregroundOwnsIncoming = false
            state = IncomingCallNotificationState(notificationKey = null, peerName = null)
            notificationManager.cancel(NOTIFICATION_ID)
        }
    }
    fun setCallForeground(value: Boolean) { callForeground = value; refreshForeground() }
    fun setMicrophoneRunning(value: Boolean) { microphoneRunning = value; onCallStateChanged(callState.value) }
    private var state = IncomingCallNotificationState(
        notificationKey = null,
        peerName = null,
    )
    private val mutableRecovery = MutableStateFlow(IncomingCallRecoveryState.NONE)
    val recoveryState: StateFlow<IncomingCallRecoveryState> = mutableRecovery.asStateFlow()

    fun setAppForeground(foreground: Boolean) {
        if (appForeground == foreground) return
        appForeground = foreground
        refreshForeground()
    }
    private fun refreshForeground() {
        onCallStateChanged(callState.value)
    }

    fun start() {
        if (collectJob != null) return
        createChannel()
        // 进程重建时，先撤下上一进程可能遗留的循环提醒，再按真实快照恢复。
        cancelCurrentNotification()
        collectJob = scope.launch {
            callState.collect { uiState ->
                onCallStateChanged(uiState)
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
        vibration.stop()
        cancelCurrentNotification()
        notificationManager.cancel(CallOngoingNotification.ID)
        scope.cancel()
    }

    fun handleAction(action: IncomingCallAction, notificationKey: String) {
        val binding = resolveKey(notificationKey) ?: return
        val ready = callState.value as? CallUiState.Ready ?: return
        if (ready.phase != com.vowifi.sw.manager.model.CallPhase.Ringing || ready.sync != com.vowifi.sw.manager.model.CallSyncState.Ready) return
        if (action == IncomingCallAction.ANSWER) appContext.startActivity(openIntent(notificationKey, true))
        else if (onBoundAction != null) onBoundAction.invoke(binding, com.vowifi.sw.manager.model.CallAction.Reject)
        else callbacks.onReject()
    }

    private fun onCallStateChanged(uiState: CallUiState) {
        if (collectJob == null) return
        val key = launches.update(uiState)
        if (uiState is CallUiState.Ready && key != null && (uiState.phase != com.vowifi.sw.manager.model.CallPhase.Ringing || microphoneRunning)) {
            notificationManager.notify(CallOngoingNotification.ID, CallOngoingNotification.build(appContext, key, uiState, microphoneRunning))
        } else if (!microphoneRunning) notificationManager.cancel(CallOngoingNotification.ID)
        if (uiState !is CallUiState.Ready || uiState.phase != com.vowifi.sw.manager.model.CallPhase.Ringing ||
            uiState.sync != CallSyncState.Ready || key == null || microphoneRunning || uiState.pendingAction != null) {
            vibration.stop()
            cancelIfActive()
            return
        }
        if (appForeground || callForeground) {
            cancelIfActive()
            vibration.start(key)
            mutableRecovery.value = IncomingCallRecoveryState.NONE
            return
        }
        vibration.stop()
        val peerName = uiState.peer?.text() ?: "未知号码"
        if (state.notificationKey == key && state.peerName == peerName) return
        if (postNotification(peerName, key)) {
            state = IncomingCallNotificationState(key, peerName)
        }
    }

    private fun cancelIfActive() {
        if (state.isActive) {
            state = IncomingCallNotificationState(notificationKey = null, peerName = null)
            cancelCurrentNotification()
        }
    }

    private fun postNotification(peerName: String, notificationKey: String): Boolean {
        val policy = evaluateNotificationPolicy(
            NotificationPolicyInput(
                appForeground = appForeground || callForeground,
                screenLocked = isScreenLocked(),
                notificationsGranted = notificationManager.areNotificationsEnabled() &&
                    notificationManager.getNotificationChannel(CHANNEL_ID)?.let {
                        it.importance != NotificationManager.IMPORTANCE_NONE
                    } == true,
                canUseFullScreen = canUseFullScreenIntent(),
            ),
        )
        if (!shouldPostIncomingCallNotification(policy)) {
            mutableRecovery.value = if (policy == NotificationPolicyDecision.PERMISSION_DENIED) {
                IncomingCallRecoveryState.NOTIFICATIONS_DENIED
            } else {
                IncomingCallRecoveryState.NONE
            }
            return false
        }
        mutableRecovery.value = if (policy == NotificationPolicyDecision.HEADS_UP_FSI_DENIED) {
            IncomingCallRecoveryState.FULL_SCREEN_DENIED
        } else {
            IncomingCallRecoveryState.NONE
        }

        val published = builder.post(peerName, notificationKey, policy,
            notify = { notificationManager.notify(NOTIFICATION_ID, it) },
            foregroundNotify = foregroundPublish?.let { publish -> { notification ->
                publish(notification)
                foregroundOwnsIncoming = true
            } },
        )
        if (!published) mutableRecovery.value = IncomingCallRecoveryState.FOREGROUND_SERVICE_REQUIRED
        return published
    }

    private fun cancelCurrentNotification() {
        if (foregroundOwnsIncoming) {
            foregroundRestore?.invoke()
            foregroundOwnsIncoming = false
        }
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun createChannel() {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "来电通知",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "VoWiFi 来电通知"
            enableVibration(true)
            vibrationPattern = IncomingCallVibration.pattern()
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun isScreenLocked(): Boolean {
        val keyguard = appContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return keyguard?.isKeyguardLocked == true
    }

    private fun canUseFullScreenIntent(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return true
        }
        return notificationManager.canUseFullScreenIntent()
    }

    companion object {
        const val CHANNEL_ID = "incoming_calls"
        const val NOTIFICATION_ID = 100
        const val ANSWER_REQUEST_CODE = 200
        const val REJECT_REQUEST_CODE = 201
        const val FULL_SCREEN_REQUEST_CODE = 202
        const val FSI_SETTINGS_REQUEST_CODE = 203
        const val OPEN_CALLS_REQUEST_CODE = 204
        const val EXTRA_NOTIFICATION_KEY = "com.vowifi.sw.notification_key"
        const val EXTRA_DESTINATION = "destination"
        const val DESTINATION_CALLS = "calls"
    }
}
