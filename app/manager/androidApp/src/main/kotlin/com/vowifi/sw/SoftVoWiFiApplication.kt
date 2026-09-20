package com.vowifi.sw

import android.app.Application
import com.vowifi.sw.manager.data.SoftVoWiFiRuntime
import com.vowifi.sw.manager.data.SoftVoWiFiRuntimeHolder

class SoftVoWiFiApplication : Application(), ProcessRuntimeStarter {
    private lateinit var holder: SoftVoWiFiRuntimeHolder

    override fun onCreate() {
        super.onCreate()
        holder = SoftVoWiFiRuntimeHolder(this, MainActivity::class.java, R.drawable.ic_stat_message)
        holder.startFromApplication()
    }

    fun runtime(): SoftVoWiFiRuntime = holder.requireRuntime()
    override fun startRuntime() = holder.startFromApplication()
}
