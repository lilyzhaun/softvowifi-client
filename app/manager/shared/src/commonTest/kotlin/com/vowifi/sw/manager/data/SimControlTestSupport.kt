package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.EngineLifecycle
import com.vowifi.sw.manager.model.EngineRuntimeStatus
import com.vowifi.sw.manager.model.SimActiveJoinInput
import com.vowifi.sw.manager.model.SimControlInstance
import com.vowifi.sw.manager.model.SimControlUiState
import com.vowifi.sw.manager.model.SimEngineJoinInput
import kotlin.test.assertFalse
import kotlin.test.assertIs

internal const val SIM_KEY_A = "key-a"
internal const val SIM_KEY_B = "key-b"
internal const val SIM_KEY_C = "key-c"
internal const val SIM_ICCID_19 = "8986000000000000336"
internal const val SIM_DIGITS_15 = "123456789012345"
internal const val SIM_TAIL_FOUR = "0336"
internal const val UNCONFIGURED_MESSAGE = "未配置/未支持"
internal const val REMOVED_MESSAGE = "SIM 已移除/配置保留"
internal const val SAFE_ENGINE_LABEL = "实例 •••• 9999"

internal fun simActive(
    key: String,
    slotIndex: Int,
    carrierName: String? = "alpha",
    displayName: String? = null,
    mcc: String? = "001",
    mnc: String? = "01",
    lastFour: String? = null,
) = SimActiveJoinInput(
    key = key,
    slotIndex = slotIndex,
    carrierName = carrierName,
    displayName = displayName,
    mcc = mcc,
    mnc = mnc,
    lastFour = lastFour,
)

internal fun simEngine(
    key: String,
    lifecycle: EngineLifecycle,
    displayLabel: String = SAFE_ENGINE_LABEL,
    pid: Long? = null,
    runtime: EngineRuntimeStatus? = null,
    message: String? = null,
) = SimEngineJoinInput(
    key = key,
    lifecycle = lifecycle,
    displayLabel = displayLabel,
    pid = pid,
    runtime = runtime,
    message = message,
)

internal fun simJoin(
    active: List<SimActiveJoinInput>,
    engineRows: List<SimEngineJoinInput>,
    handles: OpaqueIccidHandleStore = OpaqueIccidHandleStore(),
): SimControlUiState = SimControlEvaluator.join(active, engineRows, handles)

internal fun simReady(
    active: List<SimActiveJoinInput>,
    engineRows: List<SimEngineJoinInput>,
    handles: OpaqueIccidHandleStore = OpaqueIccidHandleStore(),
): SimControlUiState.Ready = assertIs(simJoin(active, engineRows, handles))

internal fun simRuntime(tunnel: String = "CONNECTED") = EngineRuntimeStatus(
    tunnel = tunnel,
    tunnelDesc = "ready",
    reconnects = 0,
    ims = "REGISTERED",
    call = "IDLE",
    transport = "UDP",
    pcscf = listOf("192.0.2.1"),
)

internal fun publicStrings(instance: SimControlInstance): List<String> = buildList {
    add(instance.handle.value)
    add(instance.displayLabel)
    add(instance.presence.name)
    instance.carrierLabel?.let(::add)
    instance.mcc?.let(::add)
    instance.mnc?.let(::add)
    instance.message?.let(::add)
    instance.lifecycle.name.let(::add)
    instance.runtime?.let { runtime ->
        add(runtime.tunnel)
        add(runtime.tunnelDesc)
        add(runtime.ims)
        add(runtime.call)
        add(runtime.transport)
        addAll(runtime.pcscf)
    }
}

internal fun publicStrings(state: SimControlUiState): List<String> = when (state) {
    SimControlUiState.Loading -> emptyList()
    is SimControlUiState.Ready -> state.instances.flatMap(::publicStrings) + listOfNotNull(state.message)
    is SimControlUiState.PermissionDenied -> listOf(state.message)
    is SimControlUiState.Failure -> listOf(state.message)
}

internal fun assertNoRawKeys(state: SimControlUiState, vararg keys: String) {
    val texts = publicStrings(state)
    val dumps = texts + state.toString()
    keys.filter { it.isNotEmpty() }.forEach { key ->
        dumps.forEach { text ->
            assertFalse(text.contains(key), "leaked raw key $key in $text")
        }
    }
    texts.forEach(::assertPrivate)
}
