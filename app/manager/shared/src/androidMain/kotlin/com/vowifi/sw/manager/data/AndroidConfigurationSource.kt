package com.vowifi.sw.manager.data

import android.content.Context
import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.model.ConfigurationApplyAcceptance
import com.vowifi.sw.manager.model.ConfigurationApplyOutcome
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationOverrides
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.SimHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class AndroidConfigurationSource(
    context: Context,
    private val handles: OpaqueIccidHandleStore,
    private val inventory: SubscriptionInventory,
    dnsLookup: NetworkDnsLookup = AndroidNetworkDnsSource(context),
    private val onCreated: () -> Unit = {},
) : ConfigurationSource {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationGate = EngineOperationGate()
    private val commands = AndroidConfigurationCommands()
    private val ownerHmac = try {
        AndroidSimOwnerHmac()
    } catch (_: java.security.GeneralSecurityException) {
        null
    } catch (_: java.io.IOException) {
        null
    } catch (_: java.security.ProviderException) {
        null
    } catch (_: SecurityException) {
        null
    }
    private val catalog = AndroidConfigurationCatalog(
        commands,
        AndroidSubscriptionDiscovery(appContext),
        handles,
        ownerFor = { ownerHmac?.ownerIdFor(it) },
    )
    private val provisioner = AndroidConfigurationProvisioner(
        AndroidSubscriptionIdentityReader(appContext),
        commands,
    )
    private val dns = AndroidConfigurationDns(dnsLookup, catalog, commands)
    private val tracker = AutoProvisionTracker()
    private val mutableState = MutableStateFlow<ConfigurationUiState>(ConfigurationUiState.Loading)
    override val state: StateFlow<ConfigurationUiState> = mutableState.asStateFlow()

    @Volatile
    private var closed = false

    private val subscriptionWatch = inventory.startWatching(::onSubscriptionsChanged)

    init {
        refreshInternal(AutoProvisionRetry.ForceRetry)
    }

    override fun refresh() {
        refreshInternal(AutoProvisionRetry.ForceRetry)
    }

    override fun submit(
        instanceId: ConfigurationInstanceId,
        overrides: ConfigurationOverrides,
        onComplete: (ConfigurationApplyOutcome) -> Unit,
    ): ConfigurationApplyAcceptance = AndroidConfigurationSubmitter(
        scope, { closed }, operationGate::tryEnter, operationGate::release,
    ).submit(
        available = { mutableState.value is ConfigurationUiState.Ready && catalog.iccidFor(instanceId) != null },
        operation = { applyLocked(instanceId, overrides) },
        onComplete = onComplete,
    )

    override fun provision(handle: SimHandle) {
        launchOperation { provisionLocked(handle) }
    }

    override fun close() {
        closed = true
        subscriptionWatch.stop()
        catalog.clear()
        scope.cancel()
    }

    private fun onSubscriptionsChanged() {
        refreshInternal(AutoProvisionRetry.Default)
    }

    private fun refreshInternal(retry: AutoProvisionRetry) {
        launchOperation { mutableState.value = refreshLocked(retry) }
    }

    private fun launchOperation(operation: suspend () -> Unit) {
        if (closed || !operationGate.tryEnter()) return
        scope.launch { if (!closed) operation() }
            .invokeOnCompletion { operationGate.release() }
    }

    private suspend fun refreshLocked(retry: AutoProvisionRetry): ConfigurationUiState {
        val listing = catalog.load()
        if (closed || !listingAllowsAuto(listing.state)) return listing.state
        val active = when (val result = inventory.listActive()) {
            is SubscriptionInventoryResult.Active -> result.subscriptions
            is SubscriptionInventoryResult.PermissionDenied,
            is SubscriptionInventoryResult.Failure,
            -> return listing.state
        }
        val prepared = dns.prepareAuto(active, listing.existingKeys, listing.state)
            ?: return dns.unavailable(listing.state)
        when (val existingRepair = prepared.existingRepair) {
            is DnsRepairResult.Failed ->
                return ConfigurationEvaluator.preserveAfterAutoFailure(listing.state, existingRepair.state)
            DnsRepairResult.Applied,
            DnsRepairResult.NoWork,
            -> Unit
        }
        val selected = tracker.select(active, listing.existingKeys, retry)
        if (selected.isEmpty()) {
            return if (prepared.existingRepair is DnsRepairResult.Applied) {
                catalog.load().state
            } else {
                listing.state
            }
        }
        when (val state = listing.state) {
            is ConfigurationUiState.Ready ->
                mutableState.value = state.copy(applyingInstanceId = null, message = AUTO_CREATING)
            ConfigurationUiState.Empty,
            ConfigurationUiState.Loading,
            is ConfigurationUiState.Failure,
            is ConfigurationUiState.PermissionDenied,
            is ConfigurationUiState.Unavailable,
            -> Unit
        }
        val batch = provisioner.attemptSelected(selected.map { it.key }) { closed }
        if (closed) return listing.state
        return dns.afterAutoBatch(active, prepared, batch, listing.state, onCreated)
    }

    private suspend fun applyLocked(
        instanceId: ConfigurationInstanceId,
        overrides: ConfigurationOverrides,
    ): ConfigurationApplyOutcome {
        val current = when (val state = mutableState.value) {
            is ConfigurationUiState.Ready -> state
            ConfigurationUiState.Loading,
            ConfigurationUiState.Empty,
            is ConfigurationUiState.Failure,
            is ConfigurationUiState.PermissionDenied,
            is ConfigurationUiState.Unavailable,
            -> return ConfigurationApplyOutcome.Unconfirmed
        }
        val iccid = catalog.iccidFor(instanceId)
        val previous = current.instances.firstOrNull { it.instanceId == instanceId }
        if (iccid == null || !ConfigurationControlCommand.digitsOnly(iccid) || previous == null) {
            retainReady(current, ConfigurationEvaluator.failure().message)
            return ConfigurationApplyOutcome.Failed
        }
        mutableState.value = current.copy(applyingInstanceId = instanceId, message = APPLYING)
        val payload = try {
            ConfigurationCodec.encodeChanges(
                ConfigurationOverrides(previous.transport, previous.smsc, previous.epdgFqdn, previous.imsRealm, previous.dns,
                    previous.imsPolicy.securityOverride, previous.imsPolicy.initialOverride, previous.imsPolicy.countryOverride,
                    previous.imsPolicy.digestRealmOverride),
                overrides,
            )
        } catch (error: ConfigurationFormatException) {
            retainReady(current, error.message ?: ConfigurationEvaluator.failure().message)
            return ConfigurationApplyOutcome.Failed
        }
        return when (val result = commands.applyTracked(iccid, payload)) {
            ConfigurationCommandApplyResult.Failed -> {
                retainReady(current, ConfigurationEvaluator.failure().message)
                ConfigurationApplyOutcome.Failed
            }
            ConfigurationCommandApplyResult.Unconfirmed -> {
                retainReady(current, ConfigurationEvaluator.failure().message)
                ConfigurationApplyOutcome.Unconfirmed
            }
            ConfigurationCommandApplyResult.Applied, ConfigurationCommandApplyResult.Unchanged -> {
                val readback = catalog.load().state
                mutableState.value = readback
                val instance = (readback as? ConfigurationUiState.Ready)?.instances
                    ?.firstOrNull { catalog.iccidFor(it.instanceId) == iccid }
                if (instance == null) ConfigurationApplyOutcome.Unconfirmed
                else ConfigurationApplyOutcome.Confirmed(instance, if (result == ConfigurationCommandApplyResult.Unchanged)
                    com.vowifi.sw.manager.model.ConfigurationWriteResult.Unchanged else com.vowifi.sw.manager.model.ConfigurationWriteResult.Applied)
            }
        }
    }

    private suspend fun provisionLocked(handle: SimHandle) {
        val current = mutableState.value
        when (current) {
            is ConfigurationUiState.Ready,
            ConfigurationUiState.Empty,
            -> Unit
            ConfigurationUiState.Loading,
            is ConfigurationUiState.Failure,
            is ConfigurationUiState.PermissionDenied,
            is ConfigurationUiState.Unavailable,
            -> return
        }
        val iccid = handles.keyFor(handle)
        if (iccid == null || !ConfigurationControlCommand.digitsOnly(iccid)) {
            failProvision(current, ConfigurationEvaluator.failure(CREATE_FAILED))
            return
        }
        val exists = iccid in catalog.existingKeys()
        val existingRow = DnsWorkPlanner.rows(current, catalog::iccidFor).firstOrNull { it.key == iccid }
        val needsDns = !exists || existingRow?.dns?.isBlank() == true
        val endpoint = if (needsDns) {
            dnsLookupOrUnavailable(current) ?: return
        } else {
            null
        }
        when (val result = inventory.listActive()) {
            is SubscriptionInventoryResult.Active ->
                tracker.select(result.subscriptions, catalog.existingKeys(), AutoProvisionRetry.Manual(iccid))
            is SubscriptionInventoryResult.PermissionDenied,
            is SubscriptionInventoryResult.Failure,
            -> Unit
        }
        if (exists) {
            mutableState.value = dns.finishManualExisting(current, iccid, endpoint)
            return
        }
        if (current is ConfigurationUiState.Ready) {
            mutableState.value = current.copy(
                applyingInstanceId = ConfigurationInstanceId(handle.value),
                message = CREATING,
            )
        }
        when (val attempt = provisioner.attempt(iccid)) {
            ProvisionAttempt.Created -> {
                runCatching { onCreated() }
                mutableState.value = dns.finishManualCreated(iccid, endpoint)
            }
            ProvisionAttempt.AlreadyExists ->
                mutableState.value = dns.finishManualCreated(iccid, endpoint, created = false)
            is ProvisionAttempt.Failed -> failProvision(current, attempt.state)
        }
    }

    private fun dnsLookupOrUnavailable(current: ConfigurationUiState): DnsEndpoint? {
        val endpoint = dns.resolve()
        if (endpoint == null) {
            failProvision(current, ConfigurationEvaluator.failure(DNS_UNAVAILABLE))
        }
        return endpoint
    }

    private fun failProvision(current: ConfigurationUiState, failure: ConfigurationUiState) {
        if (current is ConfigurationUiState.Ready) {
            retainReady(current, messageOf(failure))
        } else {
            mutableState.value = failure
        }
    }

    private fun retainReady(current: ConfigurationUiState.Ready, message: String) {
        mutableState.value = current.copy(applyingInstanceId = null, message = message)
    }

    private fun messageOf(state: ConfigurationUiState): String = when (state) {
        is ConfigurationUiState.Failure -> state.message
        is ConfigurationUiState.PermissionDenied -> state.message
        is ConfigurationUiState.Unavailable -> state.message
        ConfigurationUiState.Loading,
        ConfigurationUiState.Empty,
        is ConfigurationUiState.Ready,
        -> ConfigurationEvaluator.failure().message
    }

    private companion object {
        const val APPLYING = "正在应用配置"
        const val CREATING = "正在创建配置"
        const val AUTO_CREATING = "正在自动创建配置"
        const val CREATE_FAILED = "无法创建配置"
        const val DNS_UNAVAILABLE = "底层网络 DNS 不可用"
    }
}

private fun listingAllowsAuto(state: ConfigurationUiState): Boolean = when (state) {
    is ConfigurationUiState.Ready, ConfigurationUiState.Empty -> true
    ConfigurationUiState.Loading,
    is ConfigurationUiState.Failure,
    is ConfigurationUiState.PermissionDenied,
    is ConfigurationUiState.Unavailable,
    -> false
}
