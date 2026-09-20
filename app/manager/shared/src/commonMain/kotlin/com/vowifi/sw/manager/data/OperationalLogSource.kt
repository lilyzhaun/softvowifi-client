package com.vowifi.sw.manager.data

import com.vowifi.sw.manager.model.OperationalLogKind
import com.vowifi.sw.manager.model.OperationalLogLevel
import com.vowifi.sw.manager.model.OperationalLogUiState
import kotlinx.coroutines.flow.StateFlow

interface OperationalLogSource {
    val state: StateFlow<OperationalLogUiState>
    fun refresh()
    fun selectFile(fileId: String)
    fun setKindFilter(kind: OperationalLogKind?)
    fun setLevelFilter(level: OperationalLogLevel?)
    fun setInstanceFilter(displayLabel: String?)
    fun close() = Unit
}
