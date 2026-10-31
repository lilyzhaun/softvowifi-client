package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SimActiveJoinInput

internal class OpaqueIccidHandleStore {
    private val handlesByKey = LinkedHashMap<String, SimHandle>()
    private val keysByHandle = LinkedHashMap<SimHandle, String>()
    private var nextOrdinal = 1
    private var attachments: Map<String, Pair<Int, Int?>>? = null

    /** Retire process-local action handles, never persistent history owners. */
    @Synchronized
    fun reconcileActive(rows: List<SimActiveJoinInput>) {
        val next = rows.associate { it.key to (it.slotIndex to it.subscriptionId) }
        attachments?.forEach { (key, previous) ->
            if (next[key] != previous) handlesByKey.remove(key)?.let(keysByHandle::remove)
        }
        attachments = next
    }

    @Synchronized
    fun handleFor(key: String): SimHandle {
        handlesByKey[key]?.let { return it }
        val handle = SimHandle("sim-$nextOrdinal")
        nextOrdinal += 1
        handlesByKey[key] = handle
        keysByHandle[handle] = key
        return handle
    }

    @Synchronized
    fun keyFor(handle: SimHandle): String? = keysByHandle[handle]?.takeIf { key -> attachments?.containsKey(key) != false }

    @Synchronized
    fun clear() {
        handlesByKey.clear()
        keysByHandle.clear()
        attachments = null
        nextOrdinal = 1
    }
}
