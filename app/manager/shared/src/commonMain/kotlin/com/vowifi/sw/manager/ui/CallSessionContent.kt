package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Phone
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.model.CallAudioControls
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallUiState
import com.vowifi.sw.manager.model.CallOrigin
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text

internal const val MICROPHONE_PERMISSION_ACTION = "授予麦克风权限"

@Composable
internal fun CallSessionCard(
    state: CallUiState.Ready,
    peerDisplay: String,
    microphonePermissionGranted: Boolean,
    audioControls: CallAudioControls,
    actions: CallActions,
) {
    val colors = LocalSoftVoWiFiColors.current
    val actionsEnabled = state.origin == CallOrigin.ENGINE && !state.busy && state.sync == com.vowifi.sw.manager.model.CallSyncState.Ready
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val ticking = LocalAppRouteActive.current && lifecycle == Lifecycle.State.RESUMED
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Surface(shape = CircleShape, color = colors.surfaceRaised) {
                Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) { Icon(MiuixIcons.Phone, null, Modifier.size(28.dp), tint = colors.interactive) }
            }
            Text(peerDisplay, fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, softWrap = true)
            Text(
                callPhaseTitle(state.phase),
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.phase == CallPhase.InCall) {
                val clock = state.clock
                if (clock != null) {
                    val elapsed by produceState(clock.formatted(), clock, ticking) {
                        while (ticking) {
                            value = clock.formatted()
                            delay(1000)
                        }
                    }
                    Text(elapsed, fontSize = 28.sp, lineHeight = 36.sp, style = TextStyle(fontFeatureSettings = "tnum"))
                }
                Text(callAudioText(state.audio), fontSize = 14.sp, lineHeight = 20.sp, softWrap = true)
            }
            state.operationMessage?.let { message ->
                Text(message, color = colors.textSecondary, fontSize = 14.sp, lineHeight = 20.sp, softWrap = true)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state.phase) {
                CallPhase.Ringing -> {
                    if (!microphonePermissionGranted) {
                        CallsActionButton(
                            MICROPHONE_PERMISSION_ACTION,
                            actionsEnabled,
                            actions.requestMicrophonePermission,
                        )
                    }
                    CallsActionButton(
                        callAnswerLabel(state.busy),
                        actionsEnabled && canAnswerCall(state, microphonePermissionGranted),
                        actions.answer,
                    )
                    CallsActionButton(callRejectLabel(state.busy), actionsEnabled, actions.reject, CallActionTone.DESTRUCTIVE)
                }
                CallPhase.InCall -> {
                    if (showsInCallAudioControls(state.phase)) {
                        CallsActionButton(
                            callMuteLabel(audioControls.muted),
                            actionsEnabled,
                            actions.mute,
                            if (audioControls.muted) CallActionTone.PRIMARY else CallActionTone.SECONDARY,
                        )
                        CallsActionButton(
                            callSpeakerLabel(audioControls.speakerOn),
                            actionsEnabled,
                            actions.speaker,
                            if (audioControls.speakerOn) CallActionTone.PRIMARY else CallActionTone.SECONDARY,
                        )
                    }
                    CallsActionButton(callHangupLabel(state.busy), actionsEnabled, actions.hangup, CallActionTone.DESTRUCTIVE)
                }
                CallPhase.Idle,
                CallPhase.Connecting,
                CallPhase.Dialing,
                CallPhase.Canceling,
                CallPhase.Ending,
                -> Unit
            }
            }
    }
}

@Composable
internal fun CallsFixtureDisclosureBand() {
    CommunicationNotice(FIXTURE_DISCLOSURE_TEXT)
}
