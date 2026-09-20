package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.ConfigurationApplyAcceptance
import com.vowifi.sw.manager.model.ConfigurationApplyOutcome
import kotlinx.coroutines.CoroutineScope
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.launch

class AndroidConfigurationSubmitter(
    private val scope: CoroutineScope,
    private val closed: () -> Boolean,
    private val tryEnter: () -> Boolean,
    private val release: () -> Unit,
) {
    fun submit(
        available: () -> Boolean,
        operation: suspend () -> ConfigurationApplyOutcome,
        onComplete: (ConfigurationApplyOutcome) -> Unit,
    ): ConfigurationApplyAcceptance {
        if (closed()) return ConfigurationApplyAcceptance.Unavailable
        if (!tryEnter()) return ConfigurationApplyAcceptance.Busy
        if (!available()) {
            release()
            return ConfigurationApplyAcceptance.Unavailable
        }
        var outcome: ConfigurationApplyOutcome = ConfigurationApplyOutcome.Unconfirmed
        scope.launch {
            if (!closed()) outcome = operation()
        }.invokeOnCompletion { failure ->
            release()
            Handler(Looper.getMainLooper()).post {
                onComplete(if (closed() || failure != null) ConfigurationApplyOutcome.Unconfirmed else outcome)
            }
        }
        return ConfigurationApplyAcceptance.Accepted
    }
}
