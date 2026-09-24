package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.data.MessageOwnerLabel
import com.vowifi.sw.manager.data.SimOwnerId
import com.vowifi.sw.manager.model.MessageHistoryLine
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmsDualSimLinePresentationTest {
    private val line = MessageHistoryLine(
        requireNotNull(SimOwnerId.parse("sim-owner-v1:" + "a".repeat(43))), 0, true,
        MessageOwnerLabel(1, "Synthetic A"), active = null, sendable = false,
    )

    @Test fun unknownActivityIsNotAbsenceAndConnectionDoesNotClaimSendability() {
        val summary = line.lineSummary()
        assertTrue(summary.contains("SIM 状态未知"))
        assertTrue(summary.contains("已连接"))
        assertTrue(summary.contains("暂不可发送"))
        assertFalse(summary.contains("SIM 未启用"))
    }

    @Test fun knownAbsentAndSendableHaveDistinctLabels() {
        assertTrue(line.copy(active = false, connected = false).lineSummary().contains("SIM 未启用"))
        val summary = line.copy(active = true, sendable = true).lineSummary()
        assertTrue(summary.contains("SIM 已启用"))
        assertTrue(summary.contains("可发送"))
        assertTrue(summary.contains("本地线路 1"))
    }
}
