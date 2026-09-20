package com.vowifi.sw.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.vowifi.sw.manager.data.SimOwnerId

internal class MessageOwnerDraft(
    val recipient: String,
    val body: String,
    val updateRecipient: (String) -> Unit,
    val updateBody: (String) -> Unit,
    val clear: () -> Unit,
)

@Composable
internal fun rememberMessageOwnerDraft(owner: SimOwnerId?): MessageOwnerDraft {
    var entries by rememberSaveable { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    val key = owner?.wire().orEmpty()
    val entry = entries[key] ?: listOf("", "")
    return MessageOwnerDraft(
        recipient = entry[0],
        body = entry[1],
        updateRecipient = { value -> entries = entries + (key to listOf(value, entries[key]?.get(1).orEmpty())) },
        updateBody = { value -> entries = entries + (key to listOf(entries[key]?.get(0).orEmpty(), value)) },
        clear = { entries = entries - key },
    )
}
