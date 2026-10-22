package com.vowifi.sw

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.vowifi.sw.manager.SoftVoWiFiApp
import com.vowifi.sw.manager.SoftVoWiFiSources
import com.vowifi.sw.manager.data.AndroidEngineControlSource
import com.vowifi.sw.manager.data.AndroidEnvironmentDiagnosticsSource
import com.vowifi.sw.manager.data.AndroidMessageNotifications
import com.vowifi.sw.manager.data.AndroidObserverLogSource
import com.vowifi.sw.manager.data.AndroidOperationalLogSource
import com.vowifi.sw.manager.data.AndroidSimConfigurationSources
import com.vowifi.sw.manager.data.AndroidIncomingCallNotificationCoordinator
import com.vowifi.sw.manager.data.SoftVoWiFiRuntime
import com.vowifi.sw.manager.data.shouldRequestIncomingCallNotificationPermission
import com.vowifi.sw.manager.ui.navigation.AppRoute
import com.vowifi.sw.manager.ui.AndroidAppearanceSettingsSource
import com.vowifi.sw.manager.ui.androidBlurSupported
import com.vowifi.sw.manager.ui.runtimeBlurSupported
import com.vowifi.sw.manager.ui.navigation.IncomingRoute
import com.vowifi.sw.manager.ui.navigation.destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runInterruptible
import com.vowifi.sw.manager.data.AndroidDiagnosticExport
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.vowifi.sw.manager.model.CallUiState
import com.vowifi.sw.manager.model.CallBinding
import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallSyncState
import com.vowifi.sw.manager.model.LogExportCompletion
import com.vowifi.sw.manager.model.LogExportOutcome
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity() {
    private val incomingRoutes = Channel<AppRoute>(capacity = Channel.CONFLATED)
    private lateinit var processRuntime: SoftVoWiFiRuntime
    private lateinit var callPermissions: CallPermissionActions
    private var lastOpenedCall: String? = null
    private var lastOpenedBinding: CallBinding? = null
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        processRuntime.messageNotifications.permissionsChanged()
        processRuntime.callNotificationSettings.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val configurationDrafts = androidx.lifecycle.ViewModelProvider(this)[ConfigurationDraftViewModel::class.java]
        processRuntime = (application as SoftVoWiFiApplication).runtime()
        callPermissions = CallPermissionActions(this, processRuntime.calls.lifecycle)
        managementHost = WeakReference(this)
        lastOpenedCall = intent.getStringExtra(CallActivity.EXTRA_MINIMIZED)
            ?: intent.getStringExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_NOTIFICATION_KEY)
        lastOpenedBinding = processRuntime.notifications.resolveKey(lastOpenedCall)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                processRuntime.calls.state.collect { state ->
                    if (state is CallUiState.Ready && state.phase != CallPhase.Idle && state.sync == CallSyncState.Ready) {
                        val key = processRuntime.notifications.keyForCurrent()
                        if (key != null) {
                            val sameCall = state.binding?.let { lastOpenedBinding?.sameCall(it) } == true
                            val shouldOpen = key != lastOpenedCall && !sameCall
                            lastOpenedCall = key
                            lastOpenedBinding = state.binding
                            if (shouldOpen) openCall(key)
                        }
                    }
                }
            }
        }
        if (!consumeMessageLaunch(intent)) applyIncomingCallLaunch(intent, processRuntime.notifications)
        configureEdgeToEdgeWindow()
        enforceResizeImeOwner()
        setContent {
            val observerSource = remember { AndroidObserverLogSource(applicationContext) }
            val engineClientPool = processRuntime.pool
            val engineSource = remember { AndroidEngineControlSource(engineClientPool) }
            val simConfigurationSources = remember {
                AndroidSimConfigurationSources(applicationContext, engineSource, engineClientPool)
            }
            val messageSource = processRuntime.messages
            val callSource = processRuntime.calls
            val callLine by callSource.lifecycle.lineLabel.collectAsState()
            val micDenied by callSource.lifecycle.microphoneDenied.collectAsState()
            val diagnosticsSource = remember { AndroidEnvironmentDiagnosticsSource() }
            val operationalLogSource = remember { AndroidOperationalLogSource(this@MainActivity, observerSource) }
            val appearanceSource = remember { AndroidAppearanceSettingsSource(applicationContext) }
            val blurSupported = remember { androidBlurSupported(runtimeBlurSupported()) }
            val appVersion = remember {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "未知"
            }
            val launchRoute = remember {
                IncomingRoute.initial(
                    intent.getStringExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_DESTINATION),
                )
            }
            val incomingRouteFlow = remember { incomingRoutes.receiveAsFlow() }
            var microphonePermissionGranted by remember {
                mutableStateOf(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            }
            val microphonePermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted -> microphonePermissionGranted = granted }
            DisposableEffect(
                observerSource,
                engineSource,
                simConfigurationSources,
                messageSource,
                diagnosticsSource,
                operationalLogSource,
            ) {
                onDispose {
                    observerSource.close()
                    diagnosticsSource.close()
                    operationalLogSource.close()
                    simConfigurationSources.close()
                    engineSource.close()
                }
            }
            var pendingDiagnosticWindow by rememberSaveable { mutableStateOf<Pair<Long,Long>?>(null) }
            var exportBusy by remember { mutableStateOf(pendingDiagnosticWindow!=null) }
            var pendingExport by remember { mutableStateOf<String?>(null) }
            var exportCompletion by remember { mutableStateOf<LogExportCompletion?>(null) }
            val scope = rememberCoroutineScope()
            val exportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("text/plain"),
            ) { uri ->
                val text = pendingExport
                val diagnosticWindow=pendingDiagnosticWindow
                pendingExport = null
                pendingDiagnosticWindow=null
                if (uri == null) {
                    exportBusy = false
                    exportCompletion = LogExportCompletion((exportCompletion?.sequence ?: 0L) + 1L, LogExportOutcome.Cancelled)
                    return@rememberLauncherForActivityResult
                }
                scope.launch {
                    try {
                        val wrote = runInterruptible(Dispatchers.IO) {
                            if(diagnosticWindow!=null) writeDiagnosticExport(uri,diagnosticWindow.first,diagnosticWindow.second)
                            else writeUtf8Export(uri,text)
                        }
                        exportCompletion = LogExportCompletion((exportCompletion?.sequence ?: 0L) + 1L,
                            if (wrote) LogExportOutcome.Written else LogExportOutcome.Failed)
                    } finally { exportBusy=false }
                }
            }
            SoftVoWiFiApp(
                configurationDraftMemory = configurationDrafts.memory,
                sources = SoftVoWiFiSources(
                    observer = observerSource,
                    engine = engineSource,
                    simControl = simConfigurationSources.simControl,
                    configuration = simConfigurationSources.configuration,
                    diagnostics = diagnosticsSource,
                    logs = operationalLogSource,
                    messages = messageSource,
                    calls = callSource,
                    callNotificationSettings = processRuntime.callNotificationSettings,
                ),
                initialDestination = launchRoute.destination,
                incomingRoutes = incomingRouteFlow,
                exportBusy = exportBusy,
                exportCompletion = exportCompletion,
                appVersion = appVersion,
                microphonePermissionGranted = microphonePermissionGranted,
                onRequestMicrophonePermission = {
                    microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onExportLogs = { formatted ->
                    if (exportBusy) return@SoftVoWiFiApp
                    // The caller captures the export mode with the displayed source. A later
                    // source selection must not turn a system-registration export into diagnostics.
                    val diagnosticExport = formatted.isEmpty()
                    if(diagnosticExport) {
                        val end=System.currentTimeMillis()
                        pendingDiagnosticWindow=operationalLogSource.exportWindow().start(end) to end
                    } else pendingExport = formatted
                    exportBusy = true
                    exportLauncher.launch(if(diagnosticExport) "vowifi-diagnostics-redacted.jsonl" else EXPORT_FILE_NAME)
                },
                appearanceSource = appearanceSource,
                blurSupported = blurSupported,
                onCallDial = callPermissions::dial,
                onOpenCall = { processRuntime.notifications.keyForCurrent()?.let(::openCall) },
                callLineLabel = callLine,
                onCallPermissions = if (micDenied) ({ startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }) else null,
            )
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            startForegroundService(Intent(this, ObserverService::class.java))
        } catch (_: SecurityException) {
            android.util.Log.w("SoftVoWiFiObserver", "observer_start_permission_denied")
        } catch (_: IllegalStateException) {
            android.util.Log.w("SoftVoWiFiObserver", "observer_start_not_allowed")
        }
        enforceResizeImeOwner()
        requestNotificationPermissionIfNeeded()
        processRuntime.messages.setResumed(true)
        processRuntime.calls.lifecycle.setVisible(this, true)
        callPermissions.resume()
        processRuntime.callNotificationSettings.refresh()
    }

    @Suppress("DEPRECATION")
    private fun openCall(key: String) {
        startActivity(processRuntime.notifications.openIntent(key))
        // API 34+ is owned by CallActivity's open/close transition overrides.
        if (Build.VERSION.SDK_INT < 34) overridePendingTransition(R.anim.s6_call_up, R.anim.s6_call_stay_enter)
    }

    override fun onPause() {
        processRuntime.messages.setResumed(false)
        processRuntime.calls.lifecycle.setVisible(this, false)
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        processRuntime.notifications.setAppForeground(true)
    }
    override fun onPostResume() { super.onPostResume(); if (::callPermissions.isInitialized) callPermissions.resume() }

    override fun onStop() {
        processRuntime.notifications.setAppForeground(false)
        setShowWhenLocked(false)
        setTurnScreenOn(false)
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveLaunchIntent(intent)
    }

    private fun receiveLaunchIntent(intent: Intent) {
        setIntent(intent)
        intent.getStringExtra(CallActivity.EXTRA_MINIMIZED)?.let {
            lastOpenedCall = it
            lastOpenedBinding = processRuntime.notifications.resolveKey(it)
        }
        if (consumeMessageLaunch(intent)) return
        applyIncomingCallLaunch(intent, processRuntime.notifications)
        val route = IncomingRoute.onNewIntent(
            intent.getStringExtra(AndroidIncomingCallNotificationCoordinator.EXTRA_DESTINATION),
        ) ?: return
        processRuntime.messages.selectConversation(null)
        incomingRoutes.trySend(route)
    }

    override fun onDestroy() {
        if (managementHost?.get() === this) managementHost = null
        super.onDestroy()
    }

    private fun consumeMessageLaunch(intent: Intent): Boolean {
        if (intent.action != AndroidMessageNotifications.ACTION) return false
        setShowWhenLocked(false)
        setTurnScreenOn(false)
        AndroidMessageNotifications.consume(intent)?.let {
            processRuntime.messages.selectConversation(it.value)
            incomingRoutes.trySend(AppRoute.MessageThread(it))
        }
        return true
    }

    private fun requestNotificationPermissionIfNeeded() {
        val preferences = getSharedPreferences(NOTIFICATION_PERMISSION_PREFERENCES, MODE_PRIVATE)
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val requestRecorded = preferences.getBoolean(NOTIFICATION_PERMISSION_REQUESTED, false)
        if (!shouldRequestIncomingCallNotificationPermission(
                runtimePermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                permissionGranted = permissionGranted,
                requestRecorded = requestRecorded,
            )
        ) {
            return
        }
        preferences.edit().putBoolean(NOTIFICATION_PERMISSION_REQUESTED, true).apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun writeUtf8Export(uri: Uri, text: String?): Boolean {
        if (text == null) return false
        return try {
            val stream = contentResolver.openOutputStream(uri) ?: return false
            stream.use { output -> output.write(text.encodeToByteArray()) }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun writeDiagnosticExport(uri: Uri, from: Long, to: Long): Boolean {
        var written=false
        try {
            val output=contentResolver.openOutputStream(uri) ?: return false
            output.use { AndroidDiagnosticExport.write(this,from,to,it) }
            written=true
            return true
        } catch (_:java.io.IOException) { return false }
        catch (_:SecurityException) { return false }
        catch (_:IllegalStateException) { return false }
        finally {
            if(!written) try {android.provider.DocumentsContract.deleteDocument(contentResolver,uri)} catch (_:Exception) { /* Provider may not support removing its newly created partial document. */ }
        }
    }

    private fun enforceResizeImeOwner() {
        val adjustMode = window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
        if (adjustMode != WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private fun configureEdgeToEdgeWindow() {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }

    companion object {
        private var managementHost: WeakReference<MainActivity>? = null

        internal fun prepareCallReturn(taskId: Int, intent: Intent): Boolean {
            val target = managementHost?.get()?.takeIf {
                it.taskId == taskId && !it.isFinishing && !it.isDestroyed
            } ?: return false
            // Consume the existing key/route path before RESUMED can reopen the call.
            target.receiveLaunchIntent(intent)
            return true
        }

        private const val EXPORT_FILE_NAME = "vowifi-logs-redacted.txt"
        private const val NOTIFICATION_PERMISSION_PREFERENCES = "incoming-call-notification-permission"
        private const val NOTIFICATION_PERMISSION_REQUESTED = "requested"
    }
}
