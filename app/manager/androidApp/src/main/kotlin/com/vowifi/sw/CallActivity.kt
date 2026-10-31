package com.vowifi.sw

import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import android.view.KeyEvent
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
import com.vowifi.sw.manager.ui.AndroidAppearanceSettingsSource
import com.vowifi.sw.manager.ui.PhoneActionFeedback
import com.vowifi.sw.manager.ui.runtimeBlurSupported

class CallActivity : ComponentActivity() {
    private lateinit var runtime: SoftVoWiFiRuntime
    private lateinit var microphone: CallPermissionActions
    private var launchKey by mutableStateOf<String?>(null)
    private var authorized by mutableStateOf<CallBinding?>(null)
    private var answerConsumed = false
    private var resumed by mutableStateOf(false)
    private var returning = false
    private var displayedTerminal: CallTerminal? = null
    private var readingDeadline = 0L
    private class RetainedCall(val key: String?, val binding: CallBinding, val terminal: CallTerminal?, val deadline: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, R.anim.s6_call_up, R.anim.s6_call_stay_enter)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, R.anim.s6_call_stay_exit, R.anim.s6_call_down)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.s6_call_up, R.anim.s6_call_stay_enter)
        }
        setShowWhenLocked(false); setTurnScreenOn(false)
        launchKey = intent.getStringExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY)
        if (launchKey?.matches(Regex("[A-Za-z0-9-]{1,80}")) != true) { finish(); return }
        runtime = try { (application as SoftVoWiFiApplication).runtime() } catch (_: Exception) { finish(); return }
        @Suppress("DEPRECATION")
        (lastCustomNonConfigurationInstance as? RetainedCall)?.takeIf { it.key == launchKey }?.let {
            authorized = it.binding; displayedTerminal = it.terminal; readingDeadline = it.deadline
        }
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
            val terminal = ready?.terminal?.takeIf { ready?.phase == CallPhase.Idle && ready.sync == CallSyncState.Ready && authorized?.sameCall(it.binding) == true }
            val actionable = current?.takeIf { ready?.phase != CallPhase.Idle && authorized?.sameCall(it) == true }
            LaunchedEffect(terminal, launchKey, ready?.phase, ready?.sync) {
                val ended = terminal ?: return@LaunchedEffect
                val key = launchKey
                if (displayedTerminal !== ended) { displayedTerminal = ended; readingDeadline = 0L }
                if (readingDeadline == 0L) {
                    val base = if (ended.kind == CallTerminalKind.NORMAL) 1200 else 4000
                    val flags = AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_ICONS or AccessibilityManager.FLAG_CONTENT_CONTROLS
                    val recommended = getSystemService(AccessibilityManager::class.java)?.getRecommendedTimeoutMillis(base, flags) ?: base
                    readingDeadline = SystemClock.uptimeMillis() + maxOf(base, recommended)
                }
                delay((readingDeadline - SystemClock.uptimeMillis()).coerceAtLeast(0L))
                val latest = runtime.calls.state.value as? CallUiState.Ready
                if (launchKey == key && latest?.phase == CallPhase.Idle && latest.sync == CallSyncState.Ready &&
                    latest.terminal === ended && authorized?.sameCall(ended.binding) == true) returnToManagement()
            }
            LaunchedEffect(state, launchKey) {
                authorizeIfReady()
                if (isFinishing) return@LaunchedEffect
                if (authorized != null && ready != null) {
                    if (ready.phase == CallPhase.Idle) {
                        updateFlags(); return@LaunchedEffect
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
                PhoneActionFeedback(appearanceSettings, blurSupported = remember { runtimeBlurSupported() }) {
                if (authorized != null && ready != null && (terminal != null || actionable != null)) {
                    CallScreenContent(ready, line, audio, preparing,
                        onAction = { action -> actionable?.let { binding ->
                            if (action == CallAction.Answer) microphone.answer(binding) else runtime.calls.act(binding, action)
                        } }, onMute = { if (actionable != null && (runtime.calls.state.value as? CallUiState.Ready)?.binding === actionable) runtime.calls.mute() },
                        onSpeaker = { if (actionable != null && (runtime.calls.state.value as? CallUiState.Ready)?.binding === actionable) runtime.calls.speaker() },
                        onDigit = { digit -> actionable?.let { runtime.calls.sendDtmf(it, digit) } },
                        onRestoreAudio = { actionable?.let(microphone::restore) }, onMinimize = ::returnToManagement,
                        onKeypad = runtime.calls.lifecycle::setKeypad, ticking = resumed, onRefresh = runtime.calls::refresh,
                        onPermissions = if (micDenied) ({ startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName"))) }) else null, onReturn = ::returnToManagement)
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
        if (key?.matches(Regex("[A-Za-z0-9-]{1,80}")) != true) { finish(); return }
        val resolved = runtime.notifications.resolveKey(key) ?: run { finish(); return }
        if (key != launchKey) { answerConsumed = false; displayedTerminal = null; readingDeadline = 0L }
        authorized = resolved
        launchKey = key
        setShowWhenLocked(false); setTurnScreenOn(false)
        if (key.isNullOrEmpty()) finish() else { runtime.calls.refresh(); authorizeIfReady() }
    }
    // Public Activity/Window.Callback entry; AndroidX annotates its inherited implementation.
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::runtime.isInitialized && runtime.notifications.handleRingingVolumeKey(event)) return true
        return super.dispatchKeyEvent(event)
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
        if (ready.phase == CallPhase.Idle) {
            if (ready.terminal?.binding?.let { authorized?.sameCall(it) } != true) { finish(); return }
        } else if (ready.binding?.let { authorized?.sameCall(it) } != true) {
            val replacingEnded = displayedTerminal?.binding?.let { authorized?.sameCall(it) } == true
            if (authorized != null && !replacingEnded) { finish(); return }
            val key = if (replacingEnded) runtime.notifications.keyForCurrent() else launchKey
            val resolved = runtime.notifications.resolveKey(key) ?: run { finish(); return }
            if (replacingEnded) {
                launchKey = key; setIntent(runtime.notifications.openIntent(requireNotNull(key))); answerConsumed = true
            }
            authorized = resolved; displayedTerminal = null; readingDeadline = 0L
        }
        updateFlags()
    }
    private fun returnToManagement() {
        if (returning || isFinishing) return
        returning = true
        setShowWhenLocked(false); setTurnScreenOn(false)
        val management = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_MINIMIZED, runtime.notifications.keyForCurrent())
        if ((runtime.calls.state.value as? CallUiState.Ready)?.phase == CallPhase.Idle) {
            management.putExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_DESTINATION, "calls")
        }
        // With Main already underneath, only close this top Activity. CLEAR_TOP would
        // otherwise remove it as part of a second launch before its own close transition.
        if (!MainActivity.prepareCallReturn(taskId, management)) {
            management.putExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_DESTINATION, "calls")
            startActivity(management,
                ActivityOptions.makeCustomAnimation(this, R.anim.s6_call_stay_exit, R.anim.s6_call_down).toBundle())
        }
        finish()
    }
    @Suppress("DEPRECATION")
    override fun onRetainCustomNonConfigurationInstance(): Any? = authorized?.let {
        RetainedCall(launchKey, it, displayedTerminal, readingDeadline)
    }
    @Suppress("DEPRECATION")
    override fun finish() {
        setShowWhenLocked(false); setTurnScreenOn(false)
        super.finish()
        if (Build.VERSION.SDK_INT < 34) overridePendingTransition(R.anim.s6_call_stay_exit, R.anim.s6_call_down)
    }
    companion object { const val EXTRA_MINIMIZED = "com.vowifi.sw.minimized_call" }
}
