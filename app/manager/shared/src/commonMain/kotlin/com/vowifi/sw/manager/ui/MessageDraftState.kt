package com.vowifi.sw.manager.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class MessageDraftState private constructor(
    val recipient: String,
    val body: String,
) {
    override fun equals(other: Any?): Boolean =
        other is MessageDraftState && recipient == other.recipient && body == other.body

    override fun hashCode(): Int = 31 * recipient.hashCode() + body.hashCode()

    override fun toString(): String = "MessageDraftState(bodyLength=${body.length})"

    companion object {
        private const val MAX_BODY_LENGTH = 1_000

        fun create(recipient: String, body: String): MessageDraftState? {
            if (!isValidMessageRecipient(recipient)) return null
            if (body.length > MAX_BODY_LENGTH || body.any { it.isISOControl() && it != '\n' }) return null
            return MessageDraftState(recipient, body)
        }
    }
}

internal object MessageDraftRestorePolicy {
    private const val VERSION = 1
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun save(draft: MessageDraftState): String = json.encodeToString(
        MessageDraftSnapshot(
            version = VERSION,
            recipient = draft.recipient,
            body = draft.body,
        ),
    )

    fun restore(saved: String): MessageDraftState? {
        val snapshot = try {
            json.decodeFromString<MessageDraftSnapshot>(saved)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (snapshot.version != VERSION) return null
        return MessageDraftState.create(snapshot.recipient, snapshot.body)
    }

    fun cancel(): MessageDraftState? = null
}

@Serializable
private data class MessageDraftSnapshot(
    val version: Int,
    val recipient: String,
    val body: String,
)
