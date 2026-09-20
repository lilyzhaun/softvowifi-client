package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.MessageUnreadSummary

internal data class MessageUnreadBadge(val label: String, val total: Long?, val current: Boolean)

internal fun messageUnreadBadge(summary: MessageUnreadSummary): MessageUnreadBadge? = when (summary) {
    MessageUnreadSummary.Loading -> MessageUnreadBadge("...", null, false)
    is MessageUnreadSummary.Ready -> if (summary.total == 0L) null else MessageUnreadBadge(
        globalUnreadLabel(summary.total), summary.total, true,
    )
    is MessageUnreadSummary.Unavailable -> MessageUnreadBadge(
        summary.lastKnownTotal?.takeIf { it > 0 }?.let { "${globalUnreadLabel(it)}?" } ?: "?",
        summary.lastKnownTotal,
        false,
    )
}

private fun globalUnreadLabel(count: Long): String = if (count > 99) "99+" else count.toString()
