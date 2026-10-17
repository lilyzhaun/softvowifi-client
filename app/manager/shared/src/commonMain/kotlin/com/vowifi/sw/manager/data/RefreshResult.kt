package com.vowifi.sw.manager.data

/** Completion of a real read, independent of registration, permission or diagnostic health. */
sealed interface RefreshResult {
    data object Completed : RefreshResult
    /** Only fixed or already-sanitized source messages may cross this boundary. */
    data class Failed(val message: String) : RefreshResult
    data object Busy : RefreshResult
    data object Unsupported : RefreshResult
    data object Cancelled : RefreshResult
}
