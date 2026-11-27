package com.vowifi.sw.manager.data

import android.content.Context
import com.vowifi.sw.manager.model.ConfigurationFormatException
import com.vowifi.sw.manager.model.ConfigurationFormatState
import com.vowifi.sw.manager.model.ConfigurationApplyAcceptance
import com.vowifi.sw.manager.model.ConfigurationApplyOutcome
import com.vowifi.sw.manager.model.ConfigurationApplyEffect
import com.vowifi.sw.manager.model.ConfigurationInstanceId
import com.vowifi.sw.manager.model.ConfigurationInstance
import com.vowifi.sw.manager.model.SubscriptionDiscovery
import com.vowifi.sw.manager.model.ConfigurationLifecycle
import com.vowifi.sw.manager.model.ConfigurationOverrides
import com.vowifi.sw.manager.model.ConfigurationRuntimeFacts
import com.vowifi.sw.manager.model.ConfigurationNetworkOutlets
import com.vowifi.sw.manager.model.ConfigurationProvisionCompletion
import com.vowifi.sw.manager.model.ConfigurationProvisionOutcome
import com.vowifi.sw.manager.model.ConfigurationProvisionState
import com.vowifi.sw.manager.model.ConfigurationUiState
import com.vowifi.sw.manager.model.SimAttachment
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.EntitlementCacheReader
import com.vowifi.sw.manager.model.EntitlementCacheResult
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class AndroidConfigurationSource(
    context: Context,
    private val handles: OpaqueIccidHandleStore,
    private val inventory: SubscriptionInventory,
    private val readNetworkOutlets: suspend () -> ConfigurationNetworkOutlets = ::readAndroidNetworkOutlets,
    readAutomaticSmsc: suspend (String) -> String? = { null },
    private val readRuntimeFacts: suspend (String) -> ConfigurationRuntimeObservation? = { null },
    private val applyNextCallPolicy: suspend (String, String, () -> Boolean) -> ConfigurationNextCallResult =
        { _, _, _ -> ConfigurationNextCallResult(ConfigurationCommandApplyResult.Unconfirmed) },
    private val entitlementRead: AndroidEntitlementCacheRead = AndroidEntitlementCacheRead(),
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
    private val runtimeChanges = mutableMapOf<String, EngineRuntimeFactsChange>()
    private val runtimePublications = mutableMapOf<ConfigurationInstanceId, RuntimePublication>()
    private val pendingRuntime = linkedSetOf<String>()
    private var runtimeReader: Job? = null

    @Volatile
    private var closed = false

    private val subscriptionWatch = inventory.startWatching(::onSubscriptionsChanged)

    init {
        refreshInternal(AutoProvisionRetry.ForceRetry)
    }

    override fun refresh() {
        refreshInternal(AutoProvisionRetry.ForceRetry)
    }

    // Only marks a known running owner dirty; no IO or mutating full refresh.
    internal fun runtimeFactsChanged(key: String, change: EngineRuntimeFactsChange) {
        if (!change.isCurrent()) return
        var producer: Job? = null
        synchronized(lock) {
            if (closed) return
            val ready = mutableState.value as? ConfigurationUiState.Ready ?: return
            val running = ready.instances.filter { it.lifecycle == ConfigurationLifecycle.RUNNING && it.systemSubscription is SubscriptionDiscovery.Matched }
            val keys = running.mapNotNull { catalog.iccidFor(it.instanceId) }.toSet()
            runtimeChanges.keys.retainAll(keys)
            runtimePublications.keys.retainAll(running.map { it.instanceId }.toSet())
            pendingRuntime.retainAll(keys)
            if (key !in keys) return
            val previous = runtimeChanges[key]
            if (previous != null && previous.connection === change.connection && previous.sequence >= change.sequence) return
            runtimeChanges[key] = change
            pendingRuntime += key
            if (runtimeReader == null) {
                val job = scope.launch(start = CoroutineStart.LAZY) { drainRuntimeChanges() }
                runtimeReader = job
                job.invokeOnCompletion { synchronized(lock) { if (runtimeReader === job) runtimeReader = null } }
                producer = job
            }
        }
        producer?.start()
    }

    private class RuntimeRead(val key: String, val change: EngineRuntimeFactsChange, val row: ConfigurationInstance, val generation: Long)
    private class RuntimePublication(val row: ConfigurationInstance, val observation: ConfigurationRuntimeObservation)
    private class ProvisionStart(
        val generation: Long,
        val current: ConfigurationUiState,
        val iccid: String,
        val exists: Boolean,
        val existingKeys: Set<String>,
    ) {
        override fun toString(): String = "ProvisionStart"
    }

    private fun runtimeObservationFor(row: ConfigurationInstance): ConfigurationRuntimeObservation? =
        runtimePublications[row.instanceId]?.takeIf { it.row === row }?.observation

    private suspend fun drainRuntimeChanges() {
        while (true) {
            val read = synchronized(lock) {
                if (closed) return
                val key = pendingRuntime.firstOrNull() ?: run { runtimeReader = null; return }
                pendingRuntime.remove(key)
                val ready = mutableState.value as? ConfigurationUiState.Ready
                val row = ready?.instances?.singleOrNull { catalog.iccidFor(it.instanceId) == key }
                val change = runtimeChanges[key]
                if (row == null || change == null || row.lifecycle != ConfigurationLifecycle.RUNNING) null
                else RuntimeRead(key, change, row, subscriptionGeneration)
            } ?: continue
            if (!read.change.isCurrent()) continue
            val owner = handles.attachmentFor(SimHandle(read.row.instanceId.value)) ?: continue
            if ((read.row.systemSubscription as? SubscriptionDiscovery.Matched)?.slotIndex != owner.slotIndex ||
                activeRuntimeOwner(read.key) != owner) continue
            val observation = readRuntimeFacts(read.key)
            currentCoroutineContext().ensureActive()
            if (activeRuntimeOwner(read.key) != owner || !read.change.isCurrent()) continue
            synchronized(lock) {
                val ready = mutableState.value as? ConfigurationUiState.Ready ?: return@synchronized
                if (closed || subscriptionGeneration != read.generation || runtimeChanges[read.key] !== read.change) return@synchronized
                val row = ready.instances.singleOrNull { it.instanceId == read.row.instanceId } ?: return@synchronized
                if (catalog.iccidFor(row.instanceId) != read.key || row.lifecycle != ConfigurationLifecycle.RUNNING ||
                    row.draftOwnerId != read.row.draftOwnerId || row.systemSubscription != read.row.systemSubscription ||
                    !read.change.isCurrent() || observation?.isCurrent() == false) return@synchronized
                val published = runtimeObservationFor(row)
                if (observation != null && published != null && observation.connection === read.change.connection &&
                    published.connection === observation.connection) {
                    // A save can change the disk revision before a newer status is published.
                    if (!observation.follows(published)) return@synchronized
                } else if (row.configRevision != read.row.configRevision || row.runtimeFacts !== read.row.runtimeFacts) return@synchronized
                val updated = row.withRuntimeFacts(observation?.facts)
                mutableState.value = ready.copy(instances = ready.instances.map {
                    if (it.instanceId == row.instanceId) updated else it
                })
                if (observation == null) runtimePublications.remove(row.instanceId)
                else runtimePublications[row.instanceId] = RuntimePublication(if (updated == row) row else updated, observation)
            }
        }
    }

    private fun activeRuntimeOwner(key: String): SimAttachment? =
        (inventory.listActive() as? SubscriptionInventoryResult.Active)?.subscriptions?.singleOrNull { it.key == key }
            ?.attachment

    override val entitlementCacheReader: EntitlementCacheReader = { id, revision, input ->
        kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
            val handle = SimHandle(id.value)
            val generation = synchronized(lock) { subscriptionGeneration }
            val key = catalog.iccidFor(id) ?: return@runInterruptible EntitlementCacheResult.Unconfirmed
            val owner = handles.attachmentFor(handle) ?: return@runInterruptible EntitlementCacheResult.Unconfirmed
            if (owner.slotIndex !in 0..1 || (owner.portIndex ?: -1) < 0 || (owner.subscriptionId ?: -1) < 0)
                return@runInterruptible EntitlementCacheResult.Unconfirmed
            entitlementRead.read(key, revision, input) {
                val active = activeRuntimeOwner(key)
                synchronized(lock) {
                    val row = (mutableState.value as? ConfigurationUiState.Ready)?.instances?.singleOrNull { it.instanceId == id }
                    !closed && subscriptionGeneration == generation && active == owner && handles.attachmentFor(handle) == owner &&
                        catalog.iccidFor(id) == key && row != null && row.configRevision == revision &&
                        (row.systemSubscription as? SubscriptionDiscovery.Matched)?.slotIndex == owner.slotIndex
                }
            }
        }
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
                failProvision(subscriptionGeneration, ConfigurationEvaluator.failure(CREATE_FAILED))
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
            runtimeChanges.clear()
            runtimePublications.clear()
            pendingRuntime.clear()
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
            runtimeChanges.clear()
            runtimePublications.clear()
            pendingRuntime.clear()
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
                    if (closed || subscriptionGeneration != generation) return@synchronized false
                    publishCatalogReadback(refreshed.state, refreshed.runtimeObservations)
                    true
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
            -> return ConfigurationRefreshRead(
                listing.state, RefreshResult.Failed("无法读取活动订阅"), listing.runtimeObservations,
            )
        }
        if (refreshIdentities) {
            val activeKeys = active.map { it.key }.toSet()
            val targets = (listing.state as? ConfigurationUiState.Ready)?.instances.orEmpty()
                .filter { it.lifecycle == ConfigurationLifecycle.STOPPED && it.formatState == ConfigurationFormatState.CURRENT }
                .mapNotNull { catalog.iccidFor(it.instanceId) }
                .filter { it in activeKeys }
            for (key in targets) {
                if (!currentGeneration(generation)) return ConfigurationRefreshRead(listing.state, RefreshResult.Cancelled)
                val failure = provisioner.refreshIdentity(key) { currentGeneration(generation) }
                if (!currentGeneration(generation)) return ConfigurationRefreshRead(listing.state, RefreshResult.Cancelled)
                if (failure != null) {
                    val readback = catalog.load()
                    return ConfigurationRefreshRead(
                        ConfigurationEvaluator.preserveAfterAutoFailure(readback.state, failure),
                        RefreshResult.Failed(messageOf(failure)),
                        readback.runtimeObservations,
                    )
                }
            }
            if (targets.isNotEmpty()) {
                listing = catalog.load()
                configurationRefreshResult(listing.state).takeUnless { it == RefreshResult.Completed }
                    ?.let { identityReadbackResult = it }
            }
        }
        val selected = synchronized(lock) {
            if (closed || subscriptionGeneration != generation) return ConfigurationRefreshRead(listing.state, RefreshResult.Cancelled)
            val candidates = tracker.select(active, listing.existingKeys, retry)
            if (candidates.isNotEmpty()) {
                if (listing.state is ConfigurationUiState.Ready) {
                    publishCatalogReadback(listing.state, listing.runtimeObservations, AUTO_CREATING)
                }
                automaticProvision = candidates.map { handles.handleFor(it.key) }.toSet()
                mutableProvisioning.value = mutableProvisioning.value.copy(pending = mutableProvisioning.value.pending + automaticProvision)
            }
            candidates
        }
        if (selected.isEmpty()) {
            return ConfigurationRefreshRead(listing.state, identityReadbackResult ?: configurationRefreshResult(listing.state),
                listing.runtimeObservations)
        }
        val batch = provisioner.attemptSelected(selected.map { it.key }) { !currentGeneration(generation) }
        tracker.releaseUnstarted(batch.unstarted)
        if (closed) return ConfigurationRefreshRead(listing.state)
        if (batch.created) runCatching { onCreated() }
        if (!currentGeneration(generation)) return ConfigurationRefreshRead(listing.state, RefreshResult.Cancelled)
        val readback = if (batch.created || batch.existed) catalog.load() else listing
        val refreshed = when {
            batch.created -> ConfigurationEvaluator.readyAfterAutoCreate(readback.state)
            batch.failure != null -> ConfigurationEvaluator.preserveAfterAutoFailure(readback.state, batch.failure)
            else -> readback.state
        }
        return ConfigurationRefreshRead(refreshed, batch.failure?.let { RefreshResult.Failed(messageOf(it)) }
            ?: identityReadbackResult ?: configurationRefreshResult(refreshed), readback.runtimeObservations)
    }

    private suspend fun applyLocked(
        instanceId: ConfigurationInstanceId,
        overrides: ConfigurationOverrides,
    ): ConfigurationApplyOutcome {
        val (generation, iccid, previous) = synchronized(lock) {
            if (closed) return ConfigurationApplyOutcome.Unconfirmed
            val currentGeneration = subscriptionGeneration
            val current = when (val state = mutableState.value) {
                is ConfigurationUiState.Ready -> state
                ConfigurationUiState.Loading,
                ConfigurationUiState.Empty,
                is ConfigurationUiState.Failure,
                is ConfigurationUiState.PermissionDenied,
                is ConfigurationUiState.Unavailable,
                -> return ConfigurationApplyOutcome.Unconfirmed
            }
            val key = catalog.iccidFor(instanceId)
            val row = current.instances.firstOrNull { it.instanceId == instanceId }
            if (key == null || !ConfigurationControlCommand.digitsOnly(key) || row == null) {
                retainReady(current, ConfigurationEvaluator.failure().message)
                return ConfigurationApplyOutcome.Failed
            }
            if (row.formatState != ConfigurationFormatState.CURRENT || !validConfigRevision(row.configRevision)) {
                retainReady(current, "旧配置需重建，或请更新 App")
                return ConfigurationApplyOutcome.Failed
            }
            if (overrides.expectedRevision != row.configRevision) {
                retainReady(current, "配置已变化，请刷新核对并处理原草稿")
                return ConfigurationApplyOutcome.Failed
            }
            mutableState.value = current.copy(applyingInstanceId = instanceId, message = APPLYING)
            Triple(currentGeneration, key, row)
        }
        val payload = try {
            ConfigurationCodec.encodeRevisionChanges(
                ConfigurationOverrides(previous.transport, previous.smsc, previous.epdgFqdn, previous.imsRealm, previous.dns,
                     previous.imsPolicy.securityOverride, previous.imsPolicy.initialOverride, previous.imsPolicy.countryOverride,
                     previous.imsPolicy.digestRealmOverride, previous.dnsOverrides, callSignaling = previous.callSignaling,
                     callMedia = previous.callMedia, tunnelSecurity = previous.tunnelSecurity, tunnelTiming = previous.tunnelTiming,
                       imsProtection = previous.imsProtection, epdgAddressFamily = previous.epdgAddressFamily, epdgSelection = previous.epdgSelection, tunnelIdentity = previous.tunnelIdentity, imsTiming = previous.imsTiming, swuAccess = previous.swuAccess),
                overrides,
            )
        } catch (error: ConfigurationFormatException) {
            synchronized(lock) {
                if (closed || subscriptionGeneration != generation) return ConfigurationApplyOutcome.Unconfirmed
                val latest = mutableState.value as? ConfigurationUiState.Ready ?: return ConfigurationApplyOutcome.Unconfirmed
                retainReady(latest, error.message ?: ConfigurationEvaluator.failure().message)
            }
            return if (error is ConfigurationFormatException.UnknownCallSignaling || error is ConfigurationFormatException.UnknownCallMedia ||
                error is ConfigurationFormatException.UnknownTunnelSecurity || error is ConfigurationFormatException.UnknownTunnelTiming ||
                error is ConfigurationFormatException.UnknownImsProtection || error is ConfigurationFormatException.UnknownAddressFamily ||
                error is ConfigurationFormatException.UnknownEpdgSelection || error is ConfigurationFormatException.UnknownTunnelIdentity ||
                error is ConfigurationFormatException.UnknownImsTiming || error is ConfigurationFormatException.UnknownSwuAccess) ConfigurationApplyOutcome.Unconfirmed
                else ConfigurationApplyOutcome.Failed
        }
        if (!currentGeneration(generation) || catalog.iccidFor(instanceId) != iccid) return ConfigurationApplyOutcome.Unconfirmed
        val changes = (Json.parseToJsonElement(payload) as JsonObject)["overrides"] as JsonObject
        val nextCall = if (previous.lifecycle == ConfigurationLifecycle.RUNNING && changes.isNotEmpty() &&
            changes.keys.all { it in setOf("callSignaling", "callMedia") }) {
            applyNextCallPolicy(iccid, payload) { currentGeneration(generation) && catalog.iccidFor(instanceId) == iccid }
        } else null
        val result = nextCall?.result ?: commands.applyTracked(iccid, payload)
        if (!currentGeneration(generation)) return ConfigurationApplyOutcome.Unconfirmed
        return when (result) {
            ConfigurationCommandApplyResult.RevisionConflict, ConfigurationCommandApplyResult.Unconfirmed -> {
                val snapshot = catalog.load()
                synchronized(lock) {
                    if (closed || subscriptionGeneration != generation) return@synchronized ConfigurationApplyOutcome.Unconfirmed
                    val conflict = result == ConfigurationCommandApplyResult.RevisionConflict
                    val message = if (conflict) "配置已变化，请刷新核对并处理原草稿" else "保存结果未确认，请刷新核对；不会自动重试"
                    if (snapshot.state is ConfigurationUiState.Ready) {
                        publishCatalogReadback(snapshot.state, snapshot.runtimeObservations, message)
                    } else {
                        val latest = mutableState.value as? ConfigurationUiState.Ready ?: return@synchronized ConfigurationApplyOutcome.Unconfirmed
                        retainReady(latest, message)
                    }
                    if (conflict) ConfigurationApplyOutcome.Failed else ConfigurationApplyOutcome.Unconfirmed
                }
            }
            ConfigurationCommandApplyResult.Busy, ConfigurationCommandApplyResult.Failed -> synchronized(lock) {
                if (closed || subscriptionGeneration != generation) return@synchronized ConfigurationApplyOutcome.Unconfirmed
                // Runtime facts can advance while the operation gate holds a pending write.
                val latest = mutableState.value as? ConfigurationUiState.Ready ?: return@synchronized ConfigurationApplyOutcome.Unconfirmed
                if (result == ConfigurationCommandApplyResult.Busy) {
                    retainReady(latest, "设备正忙或无法确认空闲，配置未保存")
                    ConfigurationApplyOutcome.Busy
                } else {
                    retainReady(latest, ConfigurationEvaluator.failure().message)
                    ConfigurationApplyOutcome.Failed
                }
            }
            ConfigurationCommandApplyResult.Applied, ConfigurationCommandApplyResult.Unchanged -> {
                val snapshot = catalog.load()
                val readback = synchronized(lock) {
                    if (closed || subscriptionGeneration != generation) return ConfigurationApplyOutcome.Unconfirmed
                    publishCatalogReadback(snapshot.state, snapshot.runtimeObservations)
                }
                val instance = (readback as? ConfigurationUiState.Ready)?.instances
                    ?.firstOrNull { catalog.iccidFor(it.instanceId) == iccid }
                if (instance == null || instance.instanceId != instanceId ||
                    !scalarReadbackMatches(changes, instance) ||
                    "tunnelSecurity" in changes && instance.tunnelSecurity != overrides.tunnelSecurity ||
                    "tunnelTiming" in changes && instance.tunnelTiming != overrides.tunnelTiming ||
                    "tunnelIdentity" in changes && instance.tunnelIdentity != overrides.tunnelIdentity ||
                    "imsTiming" in changes && instance.imsTiming != overrides.imsTiming ||
                    "swuAccess" in changes && instance.swuAccess != overrides.swuAccess ||
                    "imsProtection" in changes && instance.imsProtection != overrides.imsProtection ||
                    "epdgAddressFamily" in changes && instance.epdgAddressFamily != overrides.epdgAddressFamily ||
                    "epdgSelection" in changes && instance.epdgSelection?.let(ConfigurationEpdgSelectionCodec::encode) != overrides.epdgSelection?.let(ConfigurationEpdgSelectionCodec::encode) ||
                    "callSignaling" in changes && instance.callSignaling != overrides.callSignaling ||
                    "callMedia" in changes && instance.callMedia != overrides.callMedia ||
                    nextCall != null && instance.configRevision != nextCall.configRevision
                ) ConfigurationApplyOutcome.Unconfirmed
                else ConfigurationApplyOutcome.Confirmed(instance, if (result == ConfigurationCommandApplyResult.Unchanged)
                    com.vowifi.sw.manager.model.ConfigurationWriteResult.Unchanged else com.vowifi.sw.manager.model.ConfigurationWriteResult.Applied,
                    if (nextCall != null) ConfigurationApplyEffect.NextCall else ConfigurationApplyEffect.Module)
            }
        }
    }

    // Keep the saved rows authoritative. Only two reads from the same live socket can be ordered here.
    private fun publishCatalogReadback(
        state: ConfigurationUiState,
        observations: Map<ConfigurationInstanceId, ConfigurationRuntimeObservation>,
        message: String? = null,
    ): ConfigurationUiState {
        val ready = (state as? ConfigurationUiState.Ready)?.let {
            if (message == null) it else it.copy(applyingInstanceId = null, message = message)
        }
        val selected = mutableMapOf<ConfigurationInstanceId, ConfigurationRuntimeObservation>()
        val readback = if (ready != null && observations.isNotEmpty()) {
            val published = (mutableState.value as? ConfigurationUiState.Ready)?.instances.orEmpty()
            ready.copy(instances = ready.instances.map { row ->
                val sampled = observations[row.instanceId] ?: return@map row
                if (catalog.iccidFor(row.instanceId) == null) return@map row.copy(runtimeFacts = null, resolvedSmsc = "")
                val prior = published.singleOrNull { it.instanceId == row.instanceId && it.lifecycle == ConfigurationLifecycle.RUNNING &&
                    it.draftOwnerId == row.draftOwnerId && it.systemSubscription == row.systemSubscription }
                val later = prior?.let(::runtimeObservationFor)?.takeIf { it.follows(sampled) && it.isCurrent() }
                val latest = later ?: sampled.takeIf { it.isCurrent() }
                if (latest != null) selected[row.instanceId] = latest
                when {
                    later != null -> row.withRuntimeFacts(later.facts)
                    latest == null -> row.withRuntimeFacts(null)
                    else -> row
                }
            })
        } else ready ?: state
        mutableState.value = readback
        runtimePublications.clear()
        // StateFlow may keep an equal row. Bind receipts to what it actually published.
        (mutableState.value as? ConfigurationUiState.Ready)?.instances?.forEach { row ->
            selected[row.instanceId]?.let { runtimePublications[row.instanceId] = RuntimePublication(row, it) }
        }
        return readback
    }

    private fun ConfigurationInstance.withRuntimeFacts(facts: ConfigurationRuntimeFacts?) = copy(
        runtimeFacts = facts,
        resolvedSmsc = if (smsc.isEmpty()) facts?.simReportedSmsc().orEmpty() else "",
    )

    // Compare only submitted keys against the authoritative saved overrides,
    // not resolved/automatic values or the Root command's success receipt.
    private fun scalarReadbackMatches(changes: JsonObject, row: ConfigurationInstance): Boolean =
        changes.all { (field, requested) ->
            val stored = when (field) {
                "transport" -> JsonPrimitive(row.transport.wireValue)
                "smsc" -> JsonPrimitive(row.smsc)
                "epdgFqdn" -> JsonPrimitive(row.epdgFqdn)
                "imsRealm" -> JsonPrimitive(row.imsRealm)
                "dnsOverrides" -> JsonObject(row.dnsOverrides.mapValues { JsonPrimitive(it.value) })
                "imsSipSecurity" -> row.imsPolicy.securitySource.takeUnless { it == "unknown" }
                    ?.let { JsonPrimitive(row.imsPolicy.securityOverride) }
                "paniInitial" -> row.imsPolicy.initialSource.takeUnless { it == "unknown" }
                    ?.let { JsonPrimitive(row.imsPolicy.initialOverride) }
                "paniCountry" -> row.imsPolicy.countrySource.takeUnless { it == "unknown" }
                    ?.let { JsonPrimitive(row.imsPolicy.countryOverride) }
                "imsDigestRealm" -> row.imsPolicy.digestRealmSource.takeUnless { it == "unknown" }
                    ?.let { JsonPrimitive(row.imsPolicy.digestRealmOverride) }
                else -> requested // The structured groups have their own comparisons above.
            }
            stored == requested
        }

    private suspend fun provisionLocked(handle: SimHandle, rebuild: Boolean = false): ConfigurationProvisionOutcome {
        currentCoroutineContext().ensureActive()
        val start = synchronized(lock) {
            if (closed) return ConfigurationProvisionOutcome.Cancelled
            val currentGeneration = subscriptionGeneration
            val state = mutableState.value
            when (state) {
                is ConfigurationUiState.Ready,
                ConfigurationUiState.Empty,
                -> Unit
                ConfigurationUiState.Loading,
                is ConfigurationUiState.Failure,
                is ConfigurationUiState.PermissionDenied,
                is ConfigurationUiState.Unavailable,
                -> return ConfigurationProvisionOutcome.Unconfirmed
            }
            val key = handles.keyFor(handle)
            if (key == null || !ConfigurationControlCommand.digitsOnly(key)) {
                return if (failProvision(currentGeneration, ConfigurationEvaluator.failure(CREATE_FAILED))) ConfigurationProvisionOutcome.Failed
                else ConfigurationProvisionOutcome.Unconfirmed
            }
            val keys = catalog.existingKeys()
            ProvisionStart(currentGeneration, state, key, key in keys, keys)
        }
        val generation = start.generation
        val current = start.current
        val iccid = start.iccid
        val exists = start.exists
        val activeInventory = inventory.listActive()
        synchronized(lock) {
            if (closed) return ConfigurationProvisionOutcome.Cancelled
            if (subscriptionGeneration != generation) return ConfigurationProvisionOutcome.Unconfirmed
            if (activeInventory is SubscriptionInventoryResult.Active) {
                val selected = tracker.select(activeInventory.subscriptions, start.existingKeys, AutoProvisionRetry.Manual(iccid))
                tracker.releaseUnstarted(selected.map { it.key }.filter { it != iccid })
            }
        }
        if (rebuild) {
            val instance = (current as? ConfigurationUiState.Ready)?.instances?.firstOrNull { it.instanceId.value == handle.value }
            if (!exists || instance?.formatState != ConfigurationFormatState.LEGACY || instance.lifecycle != ConfigurationLifecycle.STOPPED) {
                return ConfigurationProvisionOutcome.Failed
            }
        } else if (exists) {
            return publishProvisionReadback(handle, generation, ConfigurationProvisionOutcome.Unchanged)
        }
        synchronized(lock) {
            if (closed) return ConfigurationProvisionOutcome.Cancelled
            if (subscriptionGeneration != generation) return ConfigurationProvisionOutcome.Unconfirmed
            // Candidate discovery can block while the other line publishes newer runtime facts.
            (mutableState.value as? ConfigurationUiState.Ready)?.let { latest ->
                mutableState.value = latest.copy(
                    applyingInstanceId = ConfigurationInstanceId(handle.value),
                    message = if (rebuild) "正在重建配置" else CREATING,
                )
            }
        }
        val canSubmit = {
            synchronized(lock) { !closed && subscriptionGeneration == generation && handles.keyFor(handle) == iccid }
        }
        val attempt = if (rebuild) provisioner.rebuild(iccid, canSubmit) else provisioner.attempt(iccid, canSubmit)
        currentCoroutineContext().ensureActive()
        if (closed) return ConfigurationProvisionOutcome.Cancelled
        if (!canSubmit()) return ConfigurationProvisionOutcome.Unconfirmed
        return when (attempt) {
            ProvisionAttempt.NotStarted -> ConfigurationProvisionOutcome.Unconfirmed
            ProvisionAttempt.Created -> {
                runCatching { onCreated() }
                publishProvisionReadback(handle, generation,
                    if (rebuild) ConfigurationProvisionOutcome.Updated else ConfigurationProvisionOutcome.Created)
            }
            ProvisionAttempt.AlreadyExists ->
                publishProvisionReadback(handle, generation, ConfigurationProvisionOutcome.Unchanged)
            is ProvisionAttempt.Failed -> {
                if (rebuild && attempt.unconfirmed) {
                    publishProvisionReadback(handle, generation, ConfigurationProvisionOutcome.Unconfirmed, attempt.state)
                } else {
                    val published = failProvision(generation, attempt.state)
                    if (published && !attempt.unconfirmed) ConfigurationProvisionOutcome.Failed else ConfigurationProvisionOutcome.Unconfirmed
                }
            }
        }
    }

    private suspend fun publishProvisionReadback(
        handle: SimHandle,
        generation: Long,
        outcome: ConfigurationProvisionOutcome,
        failure: ConfigurationUiState? = null,
    ): ConfigurationProvisionOutcome {
        val snapshot = catalog.load()
        currentCoroutineContext().ensureActive()
        return synchronized(lock) {
            if (closed) return@synchronized ConfigurationProvisionOutcome.Cancelled
            if (subscriptionGeneration != generation) return@synchronized ConfigurationProvisionOutcome.Unconfirmed
            val state = when (outcome) {
                ConfigurationProvisionOutcome.Created -> ConfigurationEvaluator.readyAfterCreate(snapshot.state)
                ConfigurationProvisionOutcome.Updated -> ConfigurationEvaluator.readyAfterRebuild(snapshot.state)
                else -> snapshot.state
            }
            val readback = publishCatalogReadback(
                if (failure != null && state !is ConfigurationUiState.Ready) failure else state,
                snapshot.runtimeObservations, failure?.let(::messageOf),
            )
            // A readable saved row cannot confirm a rebuild whose acknowledgement was lost.
            if (outcome == ConfigurationProvisionOutcome.Unconfirmed) return@synchronized outcome
            val confirmed = (readback as? ConfigurationUiState.Ready)?.instances
                ?.any { it.instanceId.value == handle.value && catalog.iccidFor(it.instanceId) == handles.keyFor(handle) &&
                    (outcome !in setOf(ConfigurationProvisionOutcome.Created, ConfigurationProvisionOutcome.Updated) ||
                        it.formatState == ConfigurationFormatState.CURRENT && validConfigRevision(it.configRevision)) } == true
            if (confirmed) outcome else ConfigurationProvisionOutcome.Unconfirmed
        }
    }

    private fun completeProvision(handle: SimHandle, outcome: ConfigurationProvisionOutcome) {
        val current = mutableProvisioning.value
        mutableProvisioning.value = current.copy(
            pending = current.pending - handle,
            completions = current.completions + (handle to ConfigurationProvisionCompletion(++provisionSequence, outcome)),
        )
    }

    private fun failProvision(generation: Long, failure: ConfigurationUiState): Boolean = synchronized(lock) {
        if (closed || subscriptionGeneration != generation) return@synchronized false
        // This result may change the feedback, not replace facts already published by the runtime reader.
        val current = mutableState.value
        if (current is ConfigurationUiState.Ready) {
            retainReady(current, messageOf(failure))
        } else {
            mutableState.value = failure
        }
        true
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
    val runtimeObservations: Map<ConfigurationInstanceId, ConfigurationRuntimeObservation> = emptyMap(),
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
