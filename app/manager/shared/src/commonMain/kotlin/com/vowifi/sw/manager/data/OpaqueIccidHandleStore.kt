package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.SimHandle

internal class OpaqueIccidHandleStore {
    private val handlesByKey = LinkedHashMap<String, SimHandle>()
    private val keysByHandle = LinkedHashMap<SimHandle, String>()
    private var nextOrdinal = 1

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
    fun keyFor(handle: SimHandle): String? = keysByHandle[handle]

    @Synchronized
    fun clear() {
        handlesByKey.clear()
        keysByHandle.clear()
        nextOrdinal = 1
    }
}
