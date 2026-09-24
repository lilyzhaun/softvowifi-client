package com.vowifi.sw.manager.data

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
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
        if (appForeground || callForeground) {
            if (state.isActive) {
                state = IncomingCallNotificationState(notificationKey = null, peerName = null)
                cancelCurrentNotification()
            }
        } else {
            onCallStateChanged(callState.value)
        }
    }

    fun start() {
        if (collectJob != null) return
        createChannel()
        collectJob = scope.launch {
            callState.collect { uiState ->
                onCallStateChanged(uiState)
            }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
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
        val key = launches.update(uiState)
        if (uiState is CallUiState.Ready && key != null && (uiState.phase != com.vowifi.sw.manager.model.CallPhase.Ringing || microphoneRunning)) {
            notificationManager.notify(CallOngoingNotification.ID, CallOngoingNotification.build(appContext, key, uiState))
        } else if (!microphoneRunning) notificationManager.cancel(CallOngoingNotification.ID)
        if (uiState !is CallUiState.Ready || uiState.phase != com.vowifi.sw.manager.model.CallPhase.Ringing || key == null) {
            cancelIfActive()
            return
        }
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

        val answerPending = builder.buildAnswerPendingIntent(notificationKey)
        val rejectPending = builder.buildRejectPendingIntent(notificationKey)
        val notification = builder.build(peerName, notificationKey, answerPending, rejectPending, policy)
        notificationManager.notify(NOTIFICATION_ID, notification)
        return true
    }

    private fun cancelCurrentNotification() {
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
