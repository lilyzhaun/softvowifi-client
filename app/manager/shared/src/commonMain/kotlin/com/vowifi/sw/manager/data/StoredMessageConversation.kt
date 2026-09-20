package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.ui.navigation.ConversationHandle

data class StoredMessageConversation(val owner: SimOwnerId, val key: String) {
    val handle: ConversationHandle get() = messageConversationHandle(owner, key)
    override fun toString(): String = "StoredMessageConversation"
}
