package com.vowifi.sw

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.CallScreenContent
import com.vowifi.sw.manager.ui.SoftVoWiFiTheme
import kotlinx.coroutines.delay
import com.vowifi.sw.manager.ui.CallLoadingContent
import com.vowifi.sw.manager.ui.CallEndContent
import com.vowifi.sw.manager.ui.AndroidAppearanceSettingsSource
import com.vowifi.sw.manager.ui.PhoneActionFeedback

class CallActivity : ComponentActivity() {
    private lateinit var runtime: SoftVoWiFiRuntime
    private lateinit var microphone: CallPermissionActions
    private var launchKey by mutableStateOf<String?>(null)
    private var authorized by mutableStateOf<CallBinding?>(null)
    private var answerConsumed = false
    private var resumed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(false); setTurnScreenOn(false)
        launchKey = intent.getStringExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY)
        if (launchKey?.matches(Regex("[A-Za-z0-9-]{1,80}")) != true) { finish(); return }
        runtime = try { (application as SoftVoWiFiApplication).runtime() } catch (_: Exception) { finish(); return }
        microphone = CallPermissionActions(this, runtime.calls.lifecycle)
        answerConsumed = savedInstanceState?.getBoolean("answer_consumed") == true
        if (launchKey.isNullOrEmpty()) { finish(); return }
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(this) { returnToManagement() }
        runtime.calls.refresh()
        setContent {
            val appearance = remember(resumed) { AndroidAppearanceSettingsSource(applicationContext) }
            val appearanceSettings by appearance.state.collectAsState()
            val state by runtime.calls.state.collectAsState()
            val audio by runtime.calls.audioControls.collectAsState()
            val line by runtime.calls.lifecycle.lineLabel.collectAsState()
            val preparing by runtime.calls.lifecycle.busy.collectAsState()
            val micDenied by runtime.calls.lifecycle.microphoneDenied.collectAsState()
            val ready = state as? CallUiState.Ready
            val current = ready?.binding
            LaunchedEffect(state, launchKey) {
                if (authorized == null && ready?.sync == CallSyncState.Ready) {
                    val resolved = runtime.notifications.resolveKey(launchKey)
                    if (resolved == null) { finish(); return@LaunchedEffect }
                    authorized = resolved
                }
                if (authorized != null && ready != null) {
                    if (ready.phase == CallPhase.Idle) {
                        updateFlags(); delay(1200); returnToManagement(); return@LaunchedEffect
                    }
                    if (current == null || authorized?.sameCall(current) != true) { finish(); return@LaunchedEffect }
                    updateFlags()
                    if (!answerConsumed && intent.action == IncomingCallAction.ANSWER.actionName && ready.phase == CallPhase.Ringing && ready.sync == CallSyncState.Ready) {
                        answerConsumed = true
                        microphone.answer(current)
                    }
                }
            }
            SoftVoWiFiTheme {
                PhoneActionFeedback(appearanceSettings) {
                if (authorized != null && ready != null && (ready.phase == CallPhase.Idle || current?.let { authorized?.sameCall(it) } == true)) {
                    if (ready.phase == CallPhase.Idle) CallEndContent(ready.operationMessage, ::returnToManagement)
                    else {
                    CallScreenContent(ready, line, audio, preparing,
                        onAction = { action -> current?.let { binding ->
                            if (action == CallAction.Answer) microphone.answer(binding) else runtime.calls.act(binding, action)
                        } }, onMute = { if ((runtime.calls.state.value as? CallUiState.Ready)?.binding === current) runtime.calls.mute() },
                        onSpeaker = { if ((runtime.calls.state.value as? CallUiState.Ready)?.binding === current) runtime.calls.speaker() },
                        onDigit = { digit -> current?.let { runtime.calls.sendDtmf(it, digit) } },
                        onRestoreAudio = { current?.let(microphone::restore) }, onMinimize = ::returnToManagement,
                        onKeypad = runtime.calls.lifecycle::setKeypad, ticking = resumed, onRefresh = runtime.calls::refresh,
                        onPermissions = if (micDenied) ({ startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName"))) }) else null)
                    }
                } else CallLoadingContent(runtime.calls::refresh, { finish() },
                    if (state == CallUiState.Loading || ready?.sync == CallSyncState.Recovering) "正在确认通话" else "暂时无法确认通话，请刷新或返回")
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!::runtime.isInitialized) { finish(); return }
        setIntent(intent)
        val key = intent.getStringExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY)
        if (key != launchKey) { authorized = null; answerConsumed = false }
        launchKey = key
        setShowWhenLocked(false); setTurnScreenOn(false)
        if (key.isNullOrEmpty()) finish() else { runtime.calls.refresh(); authorizeIfReady() }
    }
    override fun onResume() {
        super.onResume(); resumed = true
        if (::runtime.isInitialized && !isFinishing) { runtime.calls.lifecycle.setVisible(this, true, true); authorizeIfReady(); microphone.resume() }
    }
    override fun onPause() {
        resumed = false
        if (::runtime.isInitialized) runtime.calls.lifecycle.setVisible(this, false, true)
        super.onPause()
    }
    override fun onPostResume() { super.onPostResume(); if (::microphone.isInitialized) microphone.resume() }
    override fun onStart() { super.onStart(); if (::runtime.isInitialized) runtime.notifications.setCallForeground(true) }
    override fun onStop() {
        if (::runtime.isInitialized) runtime.notifications.setCallForeground(false)
        setShowWhenLocked(false); setTurnScreenOn(false); super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("answer_consumed", answerConsumed); super.onSaveInstanceState(outState) }
    private fun updateFlags() {
        val ready = runtime.calls.state.value as? CallUiState.Ready
        val valid = ready?.binding?.let { authorized?.sameCall(it) } == true && ready.sync == CallSyncState.Ready && ready.phase != CallPhase.Idle
        setShowWhenLocked(valid)
        setTurnScreenOn(valid && ready.phase == CallPhase.Ringing)
    }
    private fun authorizeIfReady() {
        val ready = runtime.calls.state.value as? CallUiState.Ready ?: return
        if (ready.sync != CallSyncState.Ready) { updateFlags(); return }
        if (authorized == null) {
            authorized = runtime.notifications.resolveKey(launchKey)
            if (authorized == null) { finish(); return }
        }
        updateFlags()
    }
    private fun returnToManagement() {
        setShowWhenLocked(false); setTurnScreenOn(false)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_DESTINATION, "calls")
            .putExtra(EXTRA_MINIMIZED, runtime.notifications.keyForCurrent()))
        finish()
    }
    companion object { const val EXTRA_MINIMIZED = "com.vowifi.sw.minimized_call" }
}
