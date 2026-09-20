package com.vowifi.sw.manager.data

internal class CallHistoryBinding(
    val repository: CallHistoryRepository,
    val ownerIdFor: (String) -> SimOwnerId?,
    val nextCorrelation: () -> String,
) {
    override fun toString(): String = "CallHistoryBinding"
}
