package com.vowifi.sw.manager.model

/** Transient input for an explicit local read. Never a saved per-card setting. */
class EntitlementReadContext(val server: String, val vendor: String, val model: String, val software: String) {
    override fun toString(): String = "EntitlementReadContext"
}

enum class EntitlementServiceState { Entitled, NotEntitled, InProgress, ActionRequired }

sealed interface EntitlementCacheResult {
    data object Missing : EntitlementCacheResult
    data object InvalidInput : EntitlementCacheResult
    data object Unconfirmed : EntitlementCacheResult
    data class Observed(val state: EntitlementServiceState, val validUntil: String,
        val needsTerms: Boolean, val needsAddress: Boolean) : EntitlementCacheResult
}

typealias EntitlementCacheReader = suspend (ConfigurationInstanceId, String, EntitlementReadContext) -> EntitlementCacheResult
