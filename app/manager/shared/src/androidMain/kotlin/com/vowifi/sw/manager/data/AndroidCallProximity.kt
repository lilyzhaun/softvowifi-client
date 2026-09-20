package com.vowifi.sw.manager.data

import android.content.Context
import android.os.PowerManager

class AndroidCallProximity(context: Context) : AutoCloseable {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var active = false
    private val renew = object : Runnable {
        override fun run() {
            if (!active) return
            lock?.acquire(60_000)
            handler.postDelayed(this, 30_000)
        }
    }
    private val manager = context.getSystemService(PowerManager::class.java)
    private val lock = if (manager?.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK) == true)
        manager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "SoftVoWiFi:call-proximity").apply { setReferenceCounted(false) }
    else null
    val held: Boolean get() = lock?.isHeld == true
    fun update(earpiece: Boolean, confirmed: Boolean, visible: Boolean, keypad: Boolean) {
        val target = lock ?: return
        if (earpiece && confirmed && visible && !keypad) {
            if (!active) { active = true; renew.run() }
        } else close()
    }
    override fun close() {
        active = false
        handler.removeCallbacks(renew)
        lock?.let { if (it.isHeld) it.release() }
    }
}
