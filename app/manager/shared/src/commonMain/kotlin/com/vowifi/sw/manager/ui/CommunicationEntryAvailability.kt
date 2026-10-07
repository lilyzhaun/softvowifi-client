package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.CallLineState

internal fun hasRegisteredCommunicationEngine(lines: CallLineState): Boolean = lines.choices.any {
    it.active && it.available && it.running && it.registered == true && it.syncReady
}
