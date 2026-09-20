package com.vowifi.sw.manager.data

internal data class MessageConversationIdentity(val owner: String, val conversationKey: String) {
    fun parsed(): StoredMessageConversation = StoredMessageConversation(
        SimOwnerId.parse(owner) ?: throw MessageStorageException(), conversationKey,
    )
    override fun toString(): String = "MessageConversationIdentity"
}
