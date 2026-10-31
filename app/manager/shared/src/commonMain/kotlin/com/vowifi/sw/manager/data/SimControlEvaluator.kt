package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineRuntimeStatus
import com.vowifi.sw.manager.model.SimActiveJoinInput
import com.vowifi.sw.manager.model.SimControlInstance
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimEngineJoinInput
import com.vowifi.sw.manager.model.SimHandle
import com.vowifi.sw.manager.model.SimPresence
import com.vowifi.sw.manager.model.StandardProvision
import com.vowifi.sw.manager.model.SubscriptionDiscovery

object SimControlEvaluator {
    fun permissionDenied(
        message: String = PERMISSION,
    ): SimControlUiState.PermissionDenied =
        SimControlUiState.PermissionDenied(bounded(message, PERMISSION))

    fun failure(
        message: String = UNREADABLE,
    ): SimControlUiState.Failure =
        SimControlUiState.Failure(bounded(message, UNREADABLE))

    internal fun join(
        active: List<SimActiveJoinInput>,
        engineRows: List<SimEngineJoinInput>,
        handles: OpaqueIccidHandleStore,
    ): SimControlUiState {
        val engineByKey = LinkedHashMap<String, SimEngineJoinInput>()
        for (row in engineRows) {
            if (row.key.isBlank() || row.key in engineByKey) continue
            engineByKey[row.key] = row
        }
        val sortedActive = active
            .filter { it.key.isNotBlank() }
            .sortedWith(
                compareBy<SimActiveJoinInput> { validatedSlotIndex(it.slotIndex) ?: Int.MAX_VALUE }
                    .thenBy { it.key },
            )
            .distinctBy { it.key }
        val instances = ArrayList<SimControlInstance>()
        val keys = engineByKey.keys + sortedActive.map { it.key }
        for (row in sortedActive) {
            val engine = engineByKey[row.key]
            val configured = engine != null && engine.lifecycle != EngineLifecycle.CONFIG_MISSING
            if (configured && engine.message?.let(::isSafeBounded) == false) {
                return failure(IDENTITY)
            }
            val homeLabel = row.homeCarrierLabel?.let(::homeCarrierLabel)
                ?.takeUnless { unsafeForUi(it, keys) }
            instances += activeInstance(row.copy(homeCarrierLabel = homeLabel), engine.takeIf { configured }, handles.handleFor(row.key))
                .copy(operationPending = engine?.operationPending ?: false, controlCompletion = engine?.controlCompletion)
        }
        if (instances.any { unsafeInstance(it, keys) }) return failure(IDENTITY)
        return SimControlUiState.Ready(instances)
    }
}

private data class SimActions(val canStart: Boolean, val canStop: Boolean, val canApply: Boolean)

private fun activeInstance(
    row: SimActiveJoinInput,
    engine: SimEngineJoinInput?,
    handle: SimHandle,
): SimControlInstance {
    val discovery = matchedSubscription(row.carrierName, row.displayName, row.slotIndex, row.mcc, row.mnc)
    val presence = if (engine == null) SimPresence.ACTIVE_UNCONFIGURED else SimPresence.ACTIVE_MATCHED
    val lifecycle = engine?.lifecycle ?: EngineLifecycle.CONFIG_MISSING
    return instance(
        handle = handle,
        displayLabel = activeDisplayLabel(discovery, row.lastFour),
        presence = presence,
        slotIndex = discovery.slotIndex,
        carrierLabel = discovery.carrierLabel,
        homeCarrierLabel = row.homeCarrierLabel,
        mcc = discovery.mcc,
        mnc = discovery.mnc,
        lifecycle = lifecycle,
        pid = engine?.pid,
        runtime = engine?.runtime,
        message = if (engine == null) UNCONFIGURED_MESSAGE else engine.message,
    )
}

private fun instance(
    handle: SimHandle,
    displayLabel: String,
    presence: SimPresence,
    slotIndex: Int?,
    carrierLabel: String?,
    homeCarrierLabel: String?,
    mcc: String?,
    mnc: String?,
    lifecycle: EngineLifecycle,
    pid: Long?,
    runtime: EngineRuntimeStatus?,
    message: String?,
): SimControlInstance {
    val actions = actions(presence, lifecycle)
    return SimControlInstance(
        handle = handle,
        displayLabel = displayLabel,
        presence = presence,
        slotIndex = slotIndex,
        carrierLabel = carrierLabel,
        homeCarrierLabel = homeCarrierLabel,
        mcc = mcc,
        mnc = mnc,
        lifecycle = lifecycle,
        pid = pid,
        runtime = runtime,
        message = message,
        canStart = actions.canStart,
        canStop = actions.canStop,
        canApply = actions.canApply,
        canProvision = canProvision(presence, mcc, mnc),
    )
}

private fun canProvision(presence: SimPresence, mcc: String?, mnc: String?): Boolean =
    presence == SimPresence.ACTIVE_UNCONFIGURED &&
        StandardProvision.profileFor(mcc, mnc) != null

private fun actions(presence: SimPresence, lifecycle: EngineLifecycle): SimActions = when (presence) {
    SimPresence.ACTIVE_UNCONFIGURED -> SimActions(false, false, false)
    SimPresence.ACTIVE_MATCHED -> when (lifecycle) {
        EngineLifecycle.STOPPED -> SimActions(true, false, true)
        EngineLifecycle.RUNNING -> SimActions(false, true, true)
        EngineLifecycle.STARTING,
        EngineLifecycle.STOPPING,
        EngineLifecycle.CONFIG_MISSING,
        -> SimActions(false, false, false)
    }
    SimPresence.REMOVED_CONFIG_RETAINED -> when (lifecycle) {
        EngineLifecycle.RUNNING -> SimActions(false, true, true)
        EngineLifecycle.STOPPED -> SimActions(false, false, true)
        EngineLifecycle.STARTING,
        EngineLifecycle.STOPPING,
        EngineLifecycle.CONFIG_MISSING,
        -> SimActions(false, false, false)
    }
}

private fun activeDisplayLabel(discovery: SubscriptionDiscovery.Matched, lastFour: String?): String {
    val summary = checkNotNull(systemSubscriptionSummary(discovery))
    val tail = lastFour?.takeIf(FOUR_DIGIT::matches) ?: return summary
    return "$summary · •••• $tail"
}

private fun unsafeInstance(instance: SimControlInstance, rawKeys: Collection<String>): Boolean {
    val texts = buildList {
        add(instance.handle.value)
        add(instance.displayLabel)
        instance.carrierLabel?.let(::add)
        instance.homeCarrierLabel?.let(::add)
        instance.mcc?.let(::add)
        instance.mnc?.let(::add)
        instance.message?.let(::add)
        instance.runtime?.let { runtime ->
            add(runtime.tunnel)
            add(runtime.tunnelDesc)
            add(runtime.tunnelProgress)
            add(runtime.imsProgress)
            add(runtime.ims)
            add(runtime.call)
            add(runtime.transport)
            addAll(runtime.pcscf)
        }
    }
    if (texts.any { unsafeForUi(it, rawKeys) }) return true
    val dumped = instance.toString()
    return containsIdentityDigits(dumped) || "/data/" in dumped || "/proc/" in dumped ||
        rawKeys.any { key -> key.isNotEmpty() && dumped.contains(key) }
}

private fun unsafeForUi(text: String, rawKeys: Collection<String>): Boolean {
    if (containsIdentityDigits(text) || "/data/" in text || "/proc/" in text) return true
    if (rawKeys.any { key -> key.isNotEmpty() && text.contains(key) }) return true
    return !OperationalLogRedactor.isPrivateUiText(text)
}

private fun isSafeBounded(message: String): Boolean =
    message.length <= MAX_MESSAGE_CHARS && OperationalLogRedactor.isPrivateUiText(message) &&
        !containsIdentityDigits(message) && "/data/" !in message && "/proc/" !in message

private fun bounded(message: String, fallback: String): String =
    message.takeIf { it in ALLOWED_MESSAGES && !containsIdentityDigits(it) } ?: fallback

private const val PERMISSION = "请在 KernelSU 中授予 SoftVoWiFi root 权限"
private const val UNREADABLE = "无法读取 SIM 控制状态"
private const val IDENTITY = "SIM 控制包含敏感标识"
private const val UNCONFIGURED_MESSAGE = "未配置/未支持"
private const val MAX_MESSAGE_CHARS = 80
private val FOUR_DIGIT = Regex("""^\d{4}$""")
private val ALLOWED_MESSAGES = setOf(PERMISSION, UNREADABLE, IDENTITY)
