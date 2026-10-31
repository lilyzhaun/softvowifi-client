package com.vowifi.sw.manager.ui

import com.vowifi.sw.manager.model.SIMAttributeReadState
import com.vowifi.sw.manager.model.SIMAttributeReadStatus

internal fun configurationSIMIdentitySummary(status: SIMAttributeReadStatus?): String {
    if (status == null) return "未取得匹配身份读取状态；停止引擎后可用“刷新配置”核对。不会更改手动参数。"
    fun label(state: SIMAttributeReadState) = when (state) {
        SIMAttributeReadState.KNOWN -> "已读取"
        SIMAttributeReadState.ABSENT -> "未提供"
        SIMAttributeReadState.DENIED -> "权限不足"
        SIMAttributeReadState.INVALID -> "数据无效"
        SIMAttributeReadState.UNREADABLE -> "读取失败"
        SIMAttributeReadState.UNKNOWN -> "未知"
    }
    val observations = "SPN：${label(status.spn)}；GID1：${label(status.gid1)}；GID2：${label(status.gid2)}。"
    return observations + "未读取的属性不参与匹配；这不是注册或通话结果。"
}
