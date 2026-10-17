package com.vowifi.sw.manager.data

import android.content.Context
import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.model.ConfigurationApplyAcceptance
import com.vowifi.sw.manager.model.ConfigurationApplyOutcome
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationOverrides
import com.vowifi.sw.manager.model.ConfigurationProvisionCompletion
import com.vowifi.sw.manager.model.ConfigurationProvisionOutcome
import com.vowifi.sw.manager.model.ConfigurationProvisionState
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.SimHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    private val lock = Any()
    private var refreshInFlight: ConfigurationRefreshFlight? = null
    private var explicitProvision: SimHandle? = null
    private var automaticProvision = emptySet<SimHandle>()
    private var provisionSequence = 0L
    private val mutableProvisioning = MutableStateFlow(ConfigurationProvisionState())
    override val provisioning: StateFlow<ConfigurationProvisionState> = mutableProvisioning.asStateFlow()
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

    override fun refreshIdentities() {
        refreshInternal(AutoProvisionRetry.ForceRetry, refreshIdentities = true)
    }

    override suspend fun refreshAndAwait(): RefreshResult =
        requestRefresh(AutoProvisionRetry.ForceRetry, false).await()

    override suspend fun refreshIdentitiesAndAwait(): RefreshResult =
        requestRefresh(AutoProvisionRetry.ForceRetry, true).await()

    override fun submit(
        instanceId: ConfigurationInstanceId,
        overrides: ConfigurationOverrides,
        onComplete: (ConfigurationApplyOutcome) -> Unit,
    ): ConfigurationApplyAcceptance = AndroidConfigurationSubmitter(
        scope, { closed }, { synchronized(lock) { !closed && operationGate.tryEnter() } }, operationGate::release,
    ).submit(
        available = { mutableState.value is ConfigurationUiState.Ready && catalog.iccidFor(instanceId) != null },
        operation = { applyLocked(instanceId, overrides) },
        onComplete = onComplete,
    )

    override fun provision(handle: SimHandle) {
        synchronized(lock) {
            if (closed || !listingAllowsAuto(mutableState.value) || !operationGate.tryEnter()) return
            val key = handles.keyFor(handle)
            if (key == null || !ConfigurationControlCommand.digitsOnly(key)) {
                operationGate.release()
                failProvision(mutableState.value, ConfigurationEvaluator.failure(CREATE_FAILED))
                return
            }
            explicitProvision = handle
            mutableProvisioning.value = mutableProvisioning.value.copy(pending = mutableProvisioning.value.pending + handle)
        }
        var outcome = ConfigurationProvisionOutcome.Unconfirmed
        val producer = scope.launch(start = CoroutineStart.LAZY) { outcome = provisionLocked(handle) }
        producer.invokeOnCompletion { error ->
            synchronized(lock) {
                operationGate.release()
                if (!closed) {
                    explicitProvision = null
                    val terminal = when (error) {
                        null -> outcome
                        is CancellationException -> ConfigurationProvisionOutcome.Cancelled
                        else -> ConfigurationProvisionOutcome.Unconfirmed
                    }
                    completeProvision(handle, terminal)
                }
            }
        }
        producer.start()
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            refreshInFlight?.completion?.cancel()
            val current = mutableProvisioning.value
            val completions = explicitProvision?.let { handle ->
                current.completions + (handle to ConfigurationProvisionCompletion(++provisionSequence, ConfigurationProvisionOutcome.Cancelled))
            } ?: current.completions
            explicitProvision = null
            automaticProvision = emptySet()
            mutableProvisioning.value = current.copy(pending = emptySet(), completions = completions)
        }
        subscriptionWatch.stop()
        catalog.clear()
        scope.cancel()
    }

    private fun onSubscriptionsChanged() {
        refreshInternal(AutoProvisionRetry.Default)
    }

    private fun refreshInternal(retry: AutoProvisionRetry, refreshIdentities: Boolean = false) {
        requestRefresh(retry, refreshIdentities)
    }

    private fun requestRefresh(retry: AutoProvisionRetry, identities: Boolean): Deferred<RefreshResult> {
        var producer: Job? = null
        val accepted = synchronized(lock) {
            if (closed) return@synchronized CompletableDeferred(RefreshResult.Unsupported)
            refreshInFlight?.let {
                return@synchronized if (it.identities == identities) it.completion else CompletableDeferred(RefreshResult.Busy)
            }
            if (!operationGate.tryEnter()) return@synchronized CompletableDeferred(RefreshResult.Busy)
            val completion = CompletableDeferred<RefreshResult>()
            refreshInFlight = ConfigurationRefreshFlight(identities, completion)
            var result: RefreshResult? = null
            producer = scope.launch(start = CoroutineStart.LAZY) {
                val refreshed = refreshLocked(retry, identities)
                currentCoroutineContext().ensureActive()
                synchronized(lock) { if (!closed) mutableState.value = refreshed.state }
                result = refreshed.result
            }.also { job ->
                job.invokeOnCompletion { error ->
                    synchronized(lock) {
                        refreshInFlight = null
                        operationGate.release()
                        if (!closed && automaticProvision.isNotEmpty()) {
                            val finished = automaticProvision
                            automaticProvision = emptySet()
                            mutableProvisioning.value = mutableProvisioning.value.copy(pending = mutableProvisioning.value.pending - finished)
                        }
                    }
                    when (error) {
                        null -> completion.complete(checkNotNull(result))
                        is CancellationException -> completion.cancel(error)
                        else -> completion.completeExceptionally(error)
                    }
                }
            }
            completion
        }
        producer?.start()
        return accepted
    }

    private suspend fun refreshLocked(retry: AutoProvisionRetry, refreshIdentities: Boolean = false): ConfigurationRefreshRead {
        var listing = catalog.load()
        var identityReadbackResult: RefreshResult? = null
        if (closed || !listingAllowsAuto(listing.state)) return ConfigurationRefreshRead(listing.state)
        val active = when (val result = inventory.listActive()) {
            is SubscriptionInventoryResult.Active -> result.subscriptions
            is SubscriptionInventoryResult.PermissionDenied,
            is SubscriptionInventoryResult.Failure,
            -> return ConfigurationRefreshRead(listing.state, RefreshResult.Failed("无法读取活动订阅"))
        }
        if (refreshIdentities) {
            val activeKeys = active.map { it.key }.toSet()
            val targets = (listing.state as? ConfigurationUiState.Ready)?.instances.orEmpty()
                .filter { it.lifecycle == ConfigurationLifecycle.STOPPED }
                .mapNotNull { catalog.iccidFor(it.instanceId) }
                .filter { it in activeKeys }
            for (key in targets) {
                if (closed) return ConfigurationRefreshRead(listing.state)
                val failure = provisioner.refreshIdentity(key)
                if (failure != null) {
                    return ConfigurationRefreshRead(
                        ConfigurationEvaluator.preserveAfterAutoFailure(catalog.load().state, failure),
                        RefreshResult.Failed(messageOf(failure)),
                    )
                }
            }
            if (targets.isNotEmpty()) {
                listing = catalog.load()
                configurationRefreshResult(listing.state).takeUnless { it == RefreshResult.Completed }
                    ?.let { identityReadbackResult = it }
            }
        }
        val prepared = dns.prepareAuto(active, listing.existingKeys, listing.state)
            ?: return ConfigurationRefreshRead(dns.unavailable(listing.state), RefreshResult.Failed(DNS_UNAVAILABLE))
        when (val existingRepair = prepared.existingRepair) {
            is DnsRepairResult.Failed ->
                return ConfigurationRefreshRead(ConfigurationEvaluator.preserveAfterAutoFailure(listing.state, existingRepair.state),
                    RefreshResult.Failed(messageOf(existingRepair.state)))
            DnsRepairResult.Applied,
            DnsRepairResult.NoWork,
            -> Unit
        }
        val selected = tracker.select(active, listing.existingKeys, retry)
        if (selected.isEmpty()) {
            val refreshed = if (prepared.existingRepair is DnsRepairResult.Applied) {
                catalog.load().state
            } else {
                listing.state
            }
            return ConfigurationRefreshRead(refreshed, identityReadbackResult ?: configurationRefreshResult(refreshed))
        }
        when (val state = listing.state) {
            is ConfigurationUiState.Ready ->
                synchronized(lock) {
                    if (!closed) mutableState.value = state.copy(applyingInstanceId = null, message = AUTO_CREATING)
                }
            ConfigurationUiState.Empty,
            ConfigurationUiState.Loading,
            is ConfigurationUiState.Failure,
            is ConfigurationUiState.PermissionDenied,
            is ConfigurationUiState.Unavailable,
            -> Unit
        }
        synchronized(lock) {
            if (!closed) {
                automaticProvision = selected.map { handles.handleFor(it.key) }.toSet()
                mutableProvisioning.value = mutableProvisioning.value.copy(pending = mutableProvisioning.value.pending + automaticProvision)
            }
        }
        val batch = provisioner.attemptSelected(selected.map { it.key }) { closed }
        if (closed) return ConfigurationRefreshRead(listing.state)
        var failed: ConfigurationUiState? = null
        val refreshed = dns.afterAutoBatch(active, prepared, batch, listing.state, onCreated,
            onFailure = { if (failed == null) failed = it })
        return ConfigurationRefreshRead(refreshed, failed?.let { RefreshResult.Failed(messageOf(it)) }
            ?: identityReadbackResult ?: configurationRefreshResult(refreshed))
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

    private suspend fun provisionLocked(handle: SimHandle): ConfigurationProvisionOutcome {
        currentCoroutineContext().ensureActive()
        val current = mutableState.value
        when (current) {
            is ConfigurationUiState.Ready,
            ConfigurationUiState.Empty,
            -> Unit
            ConfigurationUiState.Loading,
            is ConfigurationUiState.Failure,
            is ConfigurationUiState.PermissionDenied,
            is ConfigurationUiState.Unavailable,
            -> return ConfigurationProvisionOutcome.Unconfirmed
        }
        val iccid = handles.keyFor(handle)
        if (iccid == null || !ConfigurationControlCommand.digitsOnly(iccid)) {
            failProvision(current, ConfigurationEvaluator.failure(CREATE_FAILED))
            return ConfigurationProvisionOutcome.Failed
        }
        val exists = iccid in catalog.existingKeys()
        val existingRow = DnsWorkPlanner.rows(current, catalog::iccidFor).firstOrNull { it.key == iccid }
        val needsDns = !exists || existingRow?.dns?.isBlank() == true
        val endpoint = if (needsDns) {
            dnsLookupOrUnavailable(current) ?: return ConfigurationProvisionOutcome.Failed
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
            var repair: DnsRepairResult = DnsRepairResult.NoWork
            val readback = dns.finishManualExisting(current, iccid, endpoint, onRepair = { repair = it })
            return publishProvisionReadback(handle, readback, ConfigurationProvisionOutcome.Unchanged, repair, endpoint?.render())
        }
        if (current is ConfigurationUiState.Ready) {
            synchronized(lock) {
                if (!closed) mutableState.value = current.copy(
                    applyingInstanceId = ConfigurationInstanceId(handle.value),
                    message = CREATING,
                )
            }
        }
        val attempt = provisioner.attempt(iccid)
        currentCoroutineContext().ensureActive()
        if (closed) return ConfigurationProvisionOutcome.Cancelled
        return when (attempt) {
            ProvisionAttempt.Created -> {
                runCatching { onCreated() }
                var repair: DnsRepairResult = DnsRepairResult.NoWork
                val readback = dns.finishManualCreated(iccid, endpoint, onRepair = { repair = it })
                publishProvisionReadback(handle, readback, ConfigurationProvisionOutcome.Created, repair, endpoint?.render())
            }
            ProvisionAttempt.AlreadyExists -> {
                var repair: DnsRepairResult = DnsRepairResult.NoWork
                val readback = dns.finishManualCreated(iccid, endpoint, created = false, onRepair = { repair = it })
                publishProvisionReadback(handle, readback, ConfigurationProvisionOutcome.Unchanged, repair, endpoint?.render())
            }
            is ProvisionAttempt.Failed -> {
                failProvision(current, attempt.state)
                if (attempt.unconfirmed) ConfigurationProvisionOutcome.Unconfirmed else ConfigurationProvisionOutcome.Failed
            }
        }
    }

    private suspend fun publishProvisionReadback(
        handle: SimHandle,
        readback: ConfigurationUiState,
        outcome: ConfigurationProvisionOutcome,
        repair: DnsRepairResult,
        expectedDns: String?,
    ): ConfigurationProvisionOutcome {
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if (closed) return ConfigurationProvisionOutcome.Cancelled
            mutableState.value = readback
        }
        if (repair is DnsRepairResult.Failed) {
            return if (repair.unconfirmed) ConfigurationProvisionOutcome.Unconfirmed else ConfigurationProvisionOutcome.Failed
        }
        val confirmed = (readback as? ConfigurationUiState.Ready)?.instances
            ?.firstOrNull { it.instanceId.value == handle.value && catalog.iccidFor(it.instanceId) == handles.keyFor(handle) }
            ?: return ConfigurationProvisionOutcome.Unconfirmed
        if (repair is DnsRepairResult.Applied && confirmed.dns != expectedDns) return ConfigurationProvisionOutcome.Unconfirmed
        return if (outcome == ConfigurationProvisionOutcome.Unchanged && repair is DnsRepairResult.Applied)
            ConfigurationProvisionOutcome.Updated else outcome
    }

    private fun completeProvision(handle: SimHandle, outcome: ConfigurationProvisionOutcome) {
        val current = mutableProvisioning.value
        mutableProvisioning.value = current.copy(
            pending = current.pending - handle,
            completions = current.completions + (handle to ConfigurationProvisionCompletion(++provisionSequence, outcome)),
        )
    }

    private fun dnsLookupOrUnavailable(current: ConfigurationUiState): DnsEndpoint? {
        val endpoint = dns.resolve()
        if (endpoint == null) {
            failProvision(current, ConfigurationEvaluator.failure(DNS_UNAVAILABLE))
        }
        return endpoint
    }

    private fun failProvision(current: ConfigurationUiState, failure: ConfigurationUiState) = synchronized(lock) {
        if (closed) return@synchronized
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

private data class ConfigurationRefreshFlight(val identities: Boolean, val completion: CompletableDeferred<RefreshResult>)
private data class ConfigurationRefreshRead(
    val state: ConfigurationUiState,
    val result: RefreshResult = configurationRefreshResult(state),
)

private fun configurationRefreshResult(state: ConfigurationUiState): RefreshResult = when (state) {
    is ConfigurationUiState.Ready, ConfigurationUiState.Empty -> RefreshResult.Completed
    is ConfigurationUiState.PermissionDenied -> RefreshResult.Failed("配置读取权限不足")
    is ConfigurationUiState.Failure -> RefreshResult.Failed(state.message)
    ConfigurationUiState.Loading, is ConfigurationUiState.Unavailable -> RefreshResult.Unsupported
}

private fun listingAllowsAuto(state: ConfigurationUiState): Boolean = when (state) {
    is ConfigurationUiState.Ready, ConfigurationUiState.Empty -> true
    ConfigurationUiState.Loading,
    is ConfigurationUiState.Failure,
    is ConfigurationUiState.PermissionDenied,
    is ConfigurationUiState.Unavailable,
    -> false
}
