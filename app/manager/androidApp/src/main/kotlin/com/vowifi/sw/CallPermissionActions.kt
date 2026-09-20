package com.vowifi.sw

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.vowifi.sw.manager.data.*
import com.vowifi.sw.manager.model.CallBinding
import kotlinx.coroutines.launch

internal class CallPermissionActions(private val activity: ComponentActivity, private val lifecycle: AndroidCallLifecycle) {
    private class Request(val binding: CallBinding, val action: MicrophoneCallAction, val number: String?)
    private var pending: Request? = null
    private var running = false
    private val permission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) resume() else { pending = null; lifecycle.permissionDenied() }
    }
    fun dial(binding: CallBinding, number: String) = submit(binding, MicrophoneCallAction.Dial, number)
    fun answer(binding: CallBinding) = submit(binding, MicrophoneCallAction.Answer, null)
    fun restore(binding: CallBinding) = submit(binding, MicrophoneCallAction.Restore, null)
    private fun submit(binding: CallBinding, action: MicrophoneCallAction, number: String?) {
        if (running || pending != null) return
        pending = Request(binding, action, number)
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.RECORD_AUDIO)
        else resume()
    }
    fun resume() {
        if (!activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        if (running || activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val request = pending ?: return
        pending = null
        running = true
        activity.lifecycleScope.launch {
            try { lifecycle.perform(activity, request.binding, request.action, request.number) }
            finally { running = false }
        }
    }
}
