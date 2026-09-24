package com.vowifi.sw.manager.data

class CallMicrophoneLease {
    private val lock = Any()
    private var grant: Grant? = null
    private var key: String? = null
    @Volatile var foreground = false
        private set
    fun serviceReady(key: String) = synchronized(lock) { this.key = key; foreground = true; grant = null }
    fun bind(key: String, owner: String, generation: Long, transport: EngineMessageTransport, token: String?): Boolean = synchronized(lock) {
        if (!foreground || this.key != key) return@synchronized false
        grant = Grant(owner, generation, transport, token)
        true
    }
    fun permits(session: CallAudioSession): Boolean = synchronized(lock) {
        val current = grant ?: return@synchronized false
        if (!foreground || current.owner != session.iccid || current.generation != session.generation ||
            current.transport !== session.transport || current.token != null && current.token != session.token) return@synchronized false
        current.token = session.token
        true
    }
    fun clear(key: String) = synchronized(lock) {
        if (this.key == key) { foreground = false; grant = null; this.key = null }
    }
    private class Grant(val owner: String, val generation: Long, val transport: EngineMessageTransport, var token: String?)
}
