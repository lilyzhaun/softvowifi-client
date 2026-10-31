package com.vowifi.sw.manager.data

import android.content.Context
import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.model.ConfigurationFormatState
import com.vowifi.sw.manager.model.ConfigurationApplyAcceptance
import com.vowifi.sw.manager.model.ConfigurationApplyOutcome
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationOverrides
import com.vowifi.sw.manager.model.ConfigurationNetworkOutlets
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
    private val readNetworkOutlets: suspend () -> ConfigurationNetworkOutlets = ::readAndroidNetworkOutlets,
    readAutomaticSmsc: suspend (String) -> String? = { null },
    readRuntimeFacts: suspend (String) -> com.vowifi.sw.manager.model.ConfigurationRuntimeFacts? = { null },
    private val onCreated: () -> Unit = {},
) : ConfigurationSource {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationGate = EngineOperationGate()
    private val lock = Any()
    private var refreshInFlight: ConfigurationRefreshFlight? = null
    private var subscriptionGeneration = 0L
    private var subscriptionRefreshPending = false
    private var explicitProvision: SimHandle? = null
    private var automaticProvision = emptySet<SimHandle>()
    private var provisionSequence = 0L
    private val mutableProvisioning = MutableStateFlow(ConfigurationProvisionState())
    override val provisioning: StateFlow<ConfigurationProvisionState> = mutableProvisioning.asStateFlow()
    private val commands = AndroidConfigurationCommands()
    private var networkOutlets = ConfigurationNetworkOutlets()
    private var networkOutletsRead = false
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
        AndroidSubscriptionDiscovery(appContext, handles = handles),
        handles,
        ownerFor = { ownerHmac?.ownerIdFor(it) },
        networkOutlets = { networkOutlets },
        readAutomaticSmsc = readAutomaticSmsc,
        readRuntimeFacts = readRuntimeFacts,
    )
    private val provisioner = AndroidConfigurationProvisioner(
        AndroidSubscriptionIdentityReader(appContext),
        commands,
    )
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
        scope, { closed }, { synchronized(lock) { !closed && operationGate.tryEnter() } }, ::releaseOperation,
    ).submit(
        available = { mutableState.value is ConfigurationUiState.Ready && catalog.iccidFor(instanceId) != null },
        operation = { applyLocked(instanceId, overrides) },
        onComplete = onComplete,
    )

    override val supportsRebuild: Boolean get() = true

    override fun provision(handle: SimHandle) { requestProvision(handle, rebuild = false) }

    override fun rebuild(handle: SimHandle): Boolean = requestProvision(handle, rebuild = true)

    private fun requestProvision(handle: SimHandle, rebuild: Boolean): Boolean {
        synchronized(lock) {
            if (closed || !listingAllowsAuto(mutableState.value)) return false
            if (rebuild) {
                val current = (mutableState.value as? ConfigurationUiState.Ready)?.instances
                    ?.firstOrNull { it.instanceId.value == handle.value }
                if (current?.formatState != ConfigurationFormatState.LEGACY || current.lifecycle != ConfigurationLifecycle.STOPPED) return false
            }
            if (!operationGate.tryEnter()) return false
            val key = handles.keyFor(handle)
            if (key == null || !ConfigurationControlCommand.digitsOnly(key)) {
                operationGate.release()
                failProvision(mutableState.value, ConfigurationEvaluator.failure(CREATE_FAILED))
                return false
            }
            explicitProvision = handle
            mutableProvisioning.value = mutableProvisioning.value.copy(pending = mutableProvisioning.value.pending + handle)
        }
        var outcome = ConfigurationProvisionOutcome.Unconfirmed
        val producer = scope.launch(start = CoroutineStart.LAZY) { outcome = provisionLocked(handle, rebuild) }
        producer.invokeOnCompletion { error ->
            val refresh = synchronized(lock) {
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
                (!closed && subscriptionRefreshPending).also { subscriptionRefreshPending = false }
            }
            if (refresh) refreshInternal(AutoProvisionRetry.Default)
        }
        producer.start()
        return true
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
        synchronized(lock) {
            if (closed) return
            subscriptionGeneration++
            subscriptionRefreshPending = true
            catalog.clear()
            mutableState.value = ConfigurationUiState.Loading
        }
        refreshInternal(AutoProvisionRetry.Default)
    }

    private fun releaseOperation() {
        val refresh = synchronized(lock) {
            operationGate.release()
            (!closed && subscriptionRefreshPending).also { subscriptionRefreshPending = false }
        }
        if (refresh) refreshInternal(AutoProvisionRetry.Default)
    }

    private fun currentGeneration(generation: Long): Boolean = synchronized(lock) {
        !closed && subscriptionGeneration == generation
    }

    private fun refreshInternal(retry: AutoProvisionRetry, refreshIdentities: Boolean = false) {
        requestRefresh(retry, refreshIdentities)
    }

    private fun requestRefresh(retry: AutoProvisionRetry, identities: Boolean): Deferred<RefreshResult> {
        var producer: Job? = null
        val accepted = synchronized(lock) {
            if (closed) return@synchronized CompletableDeferred(RefreshResult.Unsupported)
            val needsOutlets = !networkOutletsRead || retry == AutoProvisionRetry.ForceRetry
            refreshInFlight?.let {
                return@synchronized if (it.identities == identities && (!needsOutlets || it.readsNetworkOutlets)) {
                    it.completion
                } else CompletableDeferred(RefreshResult.Busy)
            }
            if (!operationGate.tryEnter()) return@synchronized CompletableDeferred(RefreshResult.Busy)
            val generation = subscriptionGeneration
            subscriptionRefreshPending = false
            val completion = CompletableDeferred<RefreshResult>()
            refreshInFlight = ConfigurationRefreshFlight(identities, completion, readsNetworkOutlets = needsOutlets)
            var result: RefreshResult? = null
            producer = scope.launch(start = CoroutineStart.LAZY) {
                val refreshed = refreshLocked(retry, identities, generation)
                currentCoroutineContext().ensureActive()
                val current = synchronized(lock) {
                    (!closed && subscriptionGeneration == generation).also { if (it) mutableState.value = refreshed.state }
                }
                result = if (!current) RefreshResult.Cancelled else if (refreshed.result == RefreshResult.Completed && networkOutlets.issue != null) {
                    RefreshResult.Failed(checkNotNull(networkOutlets.issue))
                } else refreshed.result
            }.also { job ->
                job.invokeOnCompletion { error ->
                    synchronized(lock) {
                        refreshInFlight = null
                        if (!closed && automaticProvision.isNotEmpty()) {
                            val finished = automaticProvision
                            automaticProvision = emptySet()
                            mutableProvisioning.value = mutableProvisioning.value.copy(pending = mutableProvisioning.value.pending - finished)
                        }
                    }
                    releaseOperation()
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

    private suspend fun refreshLocked(retry: AutoProvisionRetry, refreshIdentities: Boolean, generation: Long): ConfigurationRefreshRead {
        // Subscription registration can request Default before init's ForceRetry
        // joins that flight. Whichever read wins must initialize the directory.
        if (!networkOutletsRead || retry == AutoProvisionRetry.ForceRetry) {
            val fresh = readNetworkOutlets()
            networkOutlets = if (fresh.issue != null && networkOutlets.supported) networkOutlets.copy(issue = fresh.issue) else fresh
            networkOutletsRead = true
        }
        var listing = catalog.load()
        if (!currentGeneration(generation)) return ConfigurationRefreshRead(listing.state, RefreshResult.Cancelled)
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
                .filter { it.lifecycle == ConfigurationLifecycle.STOPPED && it.formatState == ConfigurationFormatState.CURRENT }
                .mapNotNull { catalog.iccidFor(it.instanceId) }
                .filter { it in activeKeys }
            for (key in targets) {
                if (!currentGeneration(generation)) return ConfigurationRefreshRead(listing.state, RefreshResult.Cancelled)
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
        val selected = tracker.select(active, listing.existingKeys, retry)
        if (!currentGeneration(generation)) return ConfigurationRefreshRead(listing.state, RefreshResult.Cancelled)
        if (selected.isEmpty()) {
            return ConfigurationRefreshRead(listing.state, identityReadbackResult ?: configurationRefreshResult(listing.state))
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
        val refreshed = afterAutoBatch(batch, listing.state, onFailure = { if (failed == null) failed = it })
        return ConfigurationRefreshRead(refreshed, failed?.let { RefreshResult.Failed(messageOf(it)) }
            ?: identityReadbackResult ?: configurationRefreshResult(refreshed))
    }

    // DNS stays as saved: an empty override follows the selected network's live DNS in the engine.
    private suspend fun afterAutoBatch(
        batch: AutoProvisionBatch,
        listing: ConfigurationUiState,
        onFailure: (ConfigurationUiState) -> Unit,
    ): ConfigurationUiState {
        batch.failure?.let(onFailure)
        if (batch.created) {
            runCatching { onCreated() }
            return ConfigurationEvaluator.readyAfterAutoCreate(catalog.load().state)
        }
        val base = if (batch.existed) catalog.load().state else listing
        return batch.failure?.let { ConfigurationEvaluator.preserveAfterAutoFailure(base, it) } ?: base
    }

    private suspend fun applyLocked(
        instanceId: ConfigurationInstanceId,
        overrides: ConfigurationOverrides,
    ): ConfigurationApplyOutcome {
        val generation = synchronized(lock) { subscriptionGeneration }
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
        if (previous.formatState != ConfigurationFormatState.CURRENT || !validConfigRevision(previous.configRevision)) {
            retainReady(current, "旧配置需重建，或请更新 App")
            return ConfigurationApplyOutcome.Failed
        }
        if (overrides.expectedRevision != previous.configRevision) {
            retainReady(current, "配置已变化，请刷新核对并处理原草稿")
            return ConfigurationApplyOutcome.Failed
        }
        mutableState.value = current.copy(applyingInstanceId = instanceId, message = APPLYING)
        val payload = try {
            ConfigurationCodec.encodeRevisionChanges(
                ConfigurationOverrides(previous.transport, previous.smsc, previous.epdgFqdn, previous.imsRealm, previous.dns,
                    previous.imsPolicy.securityOverride, previous.imsPolicy.initialOverride, previous.imsPolicy.countryOverride,
                    previous.imsPolicy.digestRealmOverride, previous.dnsOverrides),
                overrides,
            )
        } catch (error: ConfigurationFormatException) {
            retainReady(current, error.message ?: ConfigurationEvaluator.failure().message)
            return ConfigurationApplyOutcome.Failed
        }
        if (!currentGeneration(generation) || catalog.iccidFor(instanceId) != iccid) return ConfigurationApplyOutcome.Unconfirmed
        val result = commands.applyTracked(iccid, payload)
        if (!currentGeneration(generation)) return ConfigurationApplyOutcome.Unconfirmed
        return when (result) {
            ConfigurationCommandApplyResult.RevisionConflict -> {
                val readback = catalog.load().state
                if (readback is ConfigurationUiState.Ready) retainReady(readback, "配置已变化，请刷新核对并处理原草稿")
                else retainReady(current, "配置已变化，请刷新核对并处理原草稿")
                ConfigurationApplyOutcome.Failed
            }
            ConfigurationCommandApplyResult.Failed -> {
                retainReady(current, ConfigurationEvaluator.failure().message)
                ConfigurationApplyOutcome.Failed
            }
            ConfigurationCommandApplyResult.Unconfirmed -> {
                val readback = catalog.load().state
                if (readback is ConfigurationUiState.Ready) retainReady(readback, "保存结果未确认，请刷新核对；不会自动重试")
                else retainReady(current, "保存结果未确认，请刷新核对；不会自动重试")
                ConfigurationApplyOutcome.Unconfirmed
            }
            ConfigurationCommandApplyResult.Applied, ConfigurationCommandApplyResult.Unchanged -> {
                val readback = catalog.load().state
                mutableState.value = readback
                val instance = (readback as? ConfigurationUiState.Ready)?.instances
                    ?.firstOrNull { catalog.iccidFor(it.instanceId) == iccid }
                if (instance == null || instance.instanceId != instanceId) ConfigurationApplyOutcome.Unconfirmed
                else ConfigurationApplyOutcome.Confirmed(instance, if (result == ConfigurationCommandApplyResult.Unchanged)
                    com.vowifi.sw.manager.model.ConfigurationWriteResult.Unchanged else com.vowifi.sw.manager.model.ConfigurationWriteResult.Applied)
            }
        }
    }

    private suspend fun provisionLocked(handle: SimHandle, rebuild: Boolean = false): ConfigurationProvisionOutcome {
        val generation = synchronized(lock) { subscriptionGeneration }
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
        when (val result = inventory.listActive()) {
            is SubscriptionInventoryResult.Active ->
                tracker.select(result.subscriptions, catalog.existingKeys(), AutoProvisionRetry.Manual(iccid))
            is SubscriptionInventoryResult.PermissionDenied,
            is SubscriptionInventoryResult.Failure,
            -> Unit
        }
        if (rebuild) {
            val instance = (current as? ConfigurationUiState.Ready)?.instances?.firstOrNull { it.instanceId.value == handle.value }
            if (!exists || instance?.formatState != ConfigurationFormatState.LEGACY || instance.lifecycle != ConfigurationLifecycle.STOPPED) {
                return ConfigurationProvisionOutcome.Failed
            }
        } else if (exists) {
            return publishProvisionReadback(handle, catalog.load().state, ConfigurationProvisionOutcome.Unchanged)
        }
        if (current is ConfigurationUiState.Ready) {
            synchronized(lock) {
                if (!closed) mutableState.value = current.copy(
                    applyingInstanceId = ConfigurationInstanceId(handle.value),
                    message = if (rebuild) "正在重建配置" else CREATING,
                )
            }
        }
        val attempt = if (rebuild) provisioner.rebuild(iccid) else provisioner.attempt(iccid)
        currentCoroutineContext().ensureActive()
        if (closed) return ConfigurationProvisionOutcome.Cancelled
        if (!currentGeneration(generation) || handles.keyFor(handle) != iccid) return ConfigurationProvisionOutcome.Unconfirmed
        return when (attempt) {
            ProvisionAttempt.Created -> {
                runCatching { onCreated() }
                val fresh = catalog.load().state
                val readback = if (rebuild) ConfigurationEvaluator.readyAfterRebuild(fresh) else ConfigurationEvaluator.readyAfterCreate(fresh)
                publishProvisionReadback(handle, readback, if (rebuild) ConfigurationProvisionOutcome.Updated else ConfigurationProvisionOutcome.Created)
            }
            ProvisionAttempt.AlreadyExists ->
                publishProvisionReadback(handle, catalog.load().state, ConfigurationProvisionOutcome.Unchanged)
            is ProvisionAttempt.Failed -> {
                val readback = if (rebuild && attempt.unconfirmed) catalog.load().state else current
                failProvision(readback, attempt.state)
                if (attempt.unconfirmed) ConfigurationProvisionOutcome.Unconfirmed else ConfigurationProvisionOutcome.Failed
            }
        }
    }

    private suspend fun publishProvisionReadback(
        handle: SimHandle,
        readback: ConfigurationUiState,
        outcome: ConfigurationProvisionOutcome,
    ): ConfigurationProvisionOutcome {
        currentCoroutineContext().ensureActive()
        synchronized(lock) {
            if (closed) return ConfigurationProvisionOutcome.Cancelled
            mutableState.value = readback
        }
        val confirmed = (readback as? ConfigurationUiState.Ready)?.instances
            ?.any { it.instanceId.value == handle.value && catalog.iccidFor(it.instanceId) == handles.keyFor(handle) &&
                (outcome !in setOf(ConfigurationProvisionOutcome.Created, ConfigurationProvisionOutcome.Updated) ||
                    it.formatState == ConfigurationFormatState.CURRENT && validConfigRevision(it.configRevision)) } == true
        return if (confirmed) outcome else ConfigurationProvisionOutcome.Unconfirmed
    }

    private fun completeProvision(handle: SimHandle, outcome: ConfigurationProvisionOutcome) {
        val current = mutableProvisioning.value
        mutableProvisioning.value = current.copy(
            pending = current.pending - handle,
            completions = current.completions + (handle to ConfigurationProvisionCompletion(++provisionSequence, outcome)),
        )
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
    }
}

private data class ConfigurationRefreshFlight(
    val identities: Boolean,
    val completion: CompletableDeferred<RefreshResult>,
    val readsNetworkOutlets: Boolean,
)
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
