package com.vowifi.sw.manager.data

import kotlinx.coroutines.sync.Mutex

internal class EngineOperationGate {
    private val mutex = Mutex()

    fun tryEnter(): Boolean = mutex.tryLock()

    fun release() = mutex.unlock()
}
