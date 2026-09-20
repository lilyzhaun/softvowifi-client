package com.vowifi.sw.manager.data

class CallHistoryOwnerChoice internal constructor(
    internal val owner: SimOwnerId,
    val label: String,
    val selected: Boolean,
) {
    override fun toString(): String = "CallHistoryOwnerChoice(selected=$selected)"
}
