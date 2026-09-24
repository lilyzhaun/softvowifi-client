package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.*

class CallLaunchRegistry(private val nextKey: (CallBinding) -> String) {
    private var current: CallBinding? = null
    private var key: String? = null
    private var pendingAlias: String? = null

    fun update(state: CallUiState): String? {
        val ready = state as? CallUiState.Ready
        val binding = ready?.binding
        if (ready == null || ready.phase == CallPhase.Idle || binding == null) {
            current = null; key = null; pendingAlias = null
            return null
        }
        val old = current
        if (old == null || old.owner != binding.owner || old.generation != binding.generation || old.token != binding.token || old.epoch != binding.epoch) {
            pendingAlias = if (old != null && old.token == null && binding.token != null &&
                old.owner == binding.owner && old.epoch == binding.epoch && old.generation == binding.generation) key else null
            key = nextKey(binding)
        }
        current = binding
        return key
    }

    fun resolve(candidate: String?, state: CallUiState): CallBinding? {
        val latest = update(state)
        return current.takeIf { candidate != null && (candidate == latest || candidate == pendingAlias) }
    }
}
