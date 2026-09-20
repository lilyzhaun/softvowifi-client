package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.MessageRecord

class MessageNotificationPresentation private constructor(
    val peer: String,
    val messages: List<MessageRecord.Incoming>,
) {
    override fun toString(): String = "MessageNotificationPresentation"

    companion object {
        fun from(conversation: StoredMessageConversation, records: List<MessageRecord>): MessageNotificationPresentation? {
            val messages = records.filterIsInstance<MessageRecord.Incoming>()
                .filter { !it.isRead && it.timestampMs >= 0 &&
                    PeerNumberNormalizer.normalize(it.peerLabel) == conversation.key }
                .sortedWith(compareBy<MessageRecord.Incoming> { it.timestampMs }.thenBy { it.id })
                .takeLast(6).map { it.copy(preview = boundedNotificationBody(it.preview)) }
            return if (messages.isEmpty()) null else MessageNotificationPresentation(notificationSender(messages.last().peerLabel), messages)
        }
    }
}

private fun notificationSender(label: String): String {
    val clean = label.filterNot { it.isISOControl() || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' }.trim()
    if (clean.isEmpty() || listOf("sip:", "sips:", "raw:", "tel:").any { clean.startsWith(it, ignoreCase = true) } ||
        clean.contains('@') || clean.contains("://")) return "短信"
    var end = 0
    repeat(64) {
        if (end < clean.length) {
            end += if (clean[end].isHighSurrogate() && end + 1 < clean.length && clean[end + 1].isLowSurrogate()) 2 else 1
        }
    }
    return clean.substring(0, end)
}

private fun boundedNotificationBody(body: String): String {
    val clean = body.filter { it == '\n' || it == '\t' || !it.isISOControl() }
    if (clean.length <= 768) return clean
    val end = if (clean[767].isHighSurrogate() && clean[768].isLowSurrogate()) 767 else 768
    return clean.substring(0, end) + "…"
}
