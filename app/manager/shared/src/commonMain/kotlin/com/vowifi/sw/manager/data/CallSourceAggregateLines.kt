package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallPhase
import com.vowifi.sw.manager.model.CallSyncState

internal fun CallSourceAggregate.publishCallLines() {
    val complete = inventoryReady && roster != null && roster!!.all { it != null }
    if (!explicitSelection) {
        selected = roster?.singleOrNull()?.takeIf { owner ->
            complete && children.any { it.owner == owner && it.ready()?.sync == CallSyncState.Ready }
        }
    }
    val known = (roster.orEmpty().filterNotNull() + children.map { it.owner } + listOfNotNull(selected)).distinct()
    val choices = known.mapIndexed { index, owner ->
        val child = children.singleOrNull { it.owner == owner }
        val ready = child?.ready()
        CallLineChoice(owner, labels[owner] ?: "SIM ${index + 1}", owner in roster.orEmpty(),
            inventoryReady && child != null && !child.source.closed,
            when (ready?.sync) { CallSyncState.Ready -> true; CallSyncState.Unregistered -> false; else -> null },
            ready?.sync == CallSyncState.Ready, owner == selected)
    }
    val idle = children.all { it.ready()?.phase?.let { phase -> phase == CallPhase.Idle } == true }
    val allowed = children.singleOrNull { it.owner == selected }?.ready()?.takeIf {
        inventoryReady && idle && preparing == null && selected in roster.orEmpty() && it.sync == CallSyncState.Ready
    }?.binding
    mutableLines.value = CallLineState(choices, complete, selected, allowed)
}
