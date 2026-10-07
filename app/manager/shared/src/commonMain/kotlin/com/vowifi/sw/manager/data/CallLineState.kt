package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.CallBinding
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class CallLineChoice(
    val owner: SimOwnerId,
    val label: String,
    val active: Boolean,
    val available: Boolean,
    val registered: Boolean?,
    val syncReady: Boolean,
    val selected: Boolean,
    val running: Boolean = false,
)

data class CallLineState(
    val choices: List<CallLineChoice> = emptyList(),
    val rosterComplete: Boolean = false,
    val selectedOwner: SimOwnerId? = null,
    val outgoingBinding: CallBinding? = null,
)

internal val EmptyCallLines: StateFlow<CallLineState> = MutableStateFlow(CallLineState())
