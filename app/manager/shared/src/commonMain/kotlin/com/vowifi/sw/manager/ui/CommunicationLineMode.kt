package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.MessageHistoryChoices

internal val MessageHistoryChoices.hasRunningEngines: Boolean get() = lines.any { it.running }
internal fun MessageHistoryChoices.selectableLines() = if (hasRunningEngines) lines.filter { it.running } else lines
internal fun MessageHistoryChoices.pickerTitle() = if (hasRunningEngines) "选择短信线路" else "选择短信记录线路"
