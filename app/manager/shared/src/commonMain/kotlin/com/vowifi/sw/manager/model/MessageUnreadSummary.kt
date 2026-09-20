package com.vowifi.sw.manager.model

sealed interface MessageUnreadSummary {
    data object Loading : MessageUnreadSummary
    data class Ready(val total: Long) : MessageUnreadSummary
    data class Unavailable(val lastKnownTotal: Long?) : MessageUnreadSummary
}
