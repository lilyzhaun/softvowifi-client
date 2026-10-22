package com.vowifi.sw.manager.data

enum class DiagnosticExportWindow(val durationMillis: Long, val label: String) {
    QUARTER_HOUR(15*60*1000L,"最近15分钟"), HOUR(60*60*1000L,"最近1小时"), DAY(24*60*60*1000L,"最近24小时"), ALL(0,"全部保留事件");
    fun start(end: Long): Long = if (this == ALL) 0 else (end-durationMillis).coerceAtLeast(0)
}
