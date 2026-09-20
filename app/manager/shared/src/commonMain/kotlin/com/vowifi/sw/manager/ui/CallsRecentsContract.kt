package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.CallRecord
import com.vowifi.sw.manager.model.CallUiState

internal class CallHistoryDay(val label: String, val records: List<CallRecord>) {
    override fun toString(): String = "CallHistoryDay(count=${records.size})"
}

internal fun callHistoryDays(
    records: List<CallRecord>,
    localDay: (Long) -> String,
): List<CallHistoryDay> = records.sortedByDescending { it.startedAtMs }
    .groupBy { localDay(it.startedAtMs) }
    .map { (label, rows) -> CallHistoryDay(label, rows) }

internal fun acceptCallDraft(previous: String, next: String): String =
    if (next.matches(Regex("\\+?[0-9]{0,20}"))) next else previous

internal fun editCallDraft(current: String, key: DialKey): String = when (key) {
    DialKey.BACKSPACE -> current.dropLast(1)
    else -> acceptCallDraft(current, current + key.emittedText.orEmpty())
}

internal fun activeCallPeerDisplay(state: CallUiState.Ready): String =
    state.peer?.let { privateUiPhoneDisplay(it.text()) } ?: "未知号码"
