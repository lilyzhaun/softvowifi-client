package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.messageRouteDigest
import com.vowifi.sw.manager.model.*
import com.vowifi.sw.manager.ui.navigation.CallHistoryHandle

internal class CallHistoryGroup(val handle: CallHistoryHandle, val peer: CallPeerPhone?, val records: List<CallRecord>) {
    val latest: CallRecord get() = records.first()
    val ids: Set<CallHistoryRecordId> get() = records.mapNotNull { it.recordId }.toSet()
    override fun toString() = "CallHistoryGroup(recordCount=${records.size})"
}

internal fun callHistoryGroups(history: CallHistoryState.Ready): List<CallHistoryGroup> = history.records
    .sortedByDescending { it.startedAtMs }.withIndex().groupBy { (index, record) ->
        record.peer?.digits()?.let { "peer:$it" } ?: "unknown:" + (record.recordId?.opaque ?: "index:$index")
    }.map { (key, indexed) ->
        val records = indexed.map { it.value }
        CallHistoryGroup(CallHistoryHandle.fromOpaque("call-history-" + messageRouteDigest(history.owner?.wire().orEmpty() + "\u0000" + key)),
            records.first().peer, records)
    }

internal fun exactCallTime(ms: Long?): String = ms?.let {
    messageDateSpec(it).label + " " + messageTimestampLabel(it) + ":" + ((it / 1000) % 60).toString().padStart(2, '0')
} ?: "未知"

internal fun exactCallDuration(seconds: Long?): String = seconds?.let {
    val h = it / 3600; val m = it % 3600 / 60; val s = it % 60
    if (h > 0) "$h 小时 $m 分 $s 秒" else if (m > 0) "$m 分 $s 秒" else "$s 秒"
} ?: "时长未知"
