package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.SmsEngineStatus

internal object SmsStatusMachine {
    fun reduce(current: SmsEngineStatus?, incoming: SmsEngineStatus): SmsEngineStatus = when {
        current == incoming -> incoming
        current != null && current.isTerminal() -> current
        incoming.isTerminal() -> incoming
        current == SmsEngineStatus.PENDING -> current
        else -> incoming
    }
}

private fun SmsEngineStatus.isTerminal(): Boolean = when (this) {
    SmsEngineStatus.DELIVERED,
    SmsEngineStatus.FORWARDED,
    SmsEngineStatus.REPLACED,
    SmsEngineStatus.FAILED,
    -> true
    SmsEngineStatus.ACCEPTED,
    SmsEngineStatus.PENDING,
    -> false
}
