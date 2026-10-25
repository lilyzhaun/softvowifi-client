package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.CallLineState
import com.vowifi.sw.manager.model.CallUiState
import com.vowifi.sw.manager.model.CallPhase

internal fun hasRegisteredCommunicationEngine(lines: CallLineState): Boolean = lines.choices.any {
    it.active && it.available && it.running && it.registered == true && it.syncReady
}

internal fun confirmedNoRegisteredCommunicationLine(lines: CallLineState, call: CallUiState): Boolean =
    lines.rosterComplete && (call as? CallUiState.Ready)?.phase == CallPhase.Idle &&
        lines.choices.all { it.registered == false || !it.running }
