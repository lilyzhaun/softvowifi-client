package com.vowifi.sw.manager.data

class CallMicrophoneLease {
    @Volatile private var grant: Pair<String, Long>? = null
    @Volatile var foreground = false
        private set
    fun serviceReady() { foreground = true }
    fun bind(owner: String, generation: Long) { grant = owner to generation }
    fun permits(session: CallAudioSession): Boolean = foreground && grant == (session.iccid to session.generation)
    fun clear() { foreground = false; grant = null }
}
