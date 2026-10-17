package com.vowifi.sw.manager.data

import android.content.Context
import android.util.Log
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Application owns one instance per process; failures may retry only in a later process. */
class AndroidObserverNotificationDefaults internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val command: (String) -> AndroidSuResult,
) {
    constructor(context: Context) : this(
        context,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        AndroidSuCommand(timeoutSeconds = 5, maxBytes = 1024)::run,
    )

    private val attempted = AtomicBoolean(false)

    fun applyDefault() {
        if (!attempted.compareAndSet(false, true)) return
        scope.launch {
            try {
                val preferences = context.getSharedPreferences("observer_notification_defaults", Context.MODE_PRIVATE)
                if (preferences.getBoolean("channel_default_repair_v2", false)) return@launch
                val repairingPreviousDefault = preferences.getBoolean("channel_default_v1", false)
                val info = context.applicationInfo
                val classpath = "'" + info.sourceDir.replace("'", "'\\''") + "'"
                val result = command("CLASSPATH=$classpath /system/bin/app_process /system/bin " +
                    "com.vowifi.sw.ObserverNotificationChannelMain ${info.uid}" +
                    if (repairingPreviousDefault) " repair-default-v2" else "")
                if (!result.timedOut && !result.overflowed && !result.writeFailed && result.exitCode == 0 &&
                    result.output == "OBSERVER_CHANNEL_DEFAULT_READY") {
                    if (!preferences.edit().putBoolean("channel_default_v1", true)
                        .putBoolean("channel_default_repair_v2", true).commit()) unavailable()
                } else unavailable()
            } catch (_: IOException) {
                unavailable()
            } catch (_: SecurityException) {
                unavailable()
            }
        }
    }

    private fun unavailable() { Log.w("SoftVoWiFiObserver", "observer_channel_default_unavailable") }
}
