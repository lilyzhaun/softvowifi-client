package com.vowifi.sw.manager.model

import com.vowifi.sw.manager.data.SimOwnerId

sealed interface MessageUnreadSummary {
    data object Loading : MessageUnreadSummary
    data class Ready(val total: Long, val byOwner: Map<SimOwnerId, Long> = emptyMap()) : MessageUnreadSummary
    data class Unavailable(val lastKnownTotal: Long?) : MessageUnreadSummary
}
