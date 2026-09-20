package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallHistoryState
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

internal class CallHistorySelection(val owner: SimOwnerId)

internal data class CallHistorySnapshot(
    val selection: CallHistorySelection?,
    val view: CallHistoryState,
    val owners: List<CallHistoryOwnerChoice>,
)

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class CallHistoryProjection<Value : Any>(
    private val snapshot: StateFlow<CallHistorySnapshot>,
    private val project: (CallHistorySnapshot) -> Value,
) : StateFlow<Value> {
    override val value: Value get() = project(snapshot.value)
    override val replayCache: List<Value> get() = listOf(value)

    @OptIn(InternalCoroutinesApi::class)
    override suspend fun collect(collector: FlowCollector<Value>): Nothing {
        var previous: Value? = null
        return snapshot.collect(object : FlowCollector<CallHistorySnapshot> {
            override suspend fun emit(value: CallHistorySnapshot) {
                val next = project(value)
                if (previous != next) {
                    previous = next
                    collector.emit(next)
                }
            }
        })
    }
}
