package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

@Composable
internal fun MessagePageAction(loading: Boolean, failed: Boolean, more: Boolean, label: String, endLabel: String = "已显示全部历史", onLoad: () -> Unit) {
    // R6：loading/按钮/终态共享最小行高，不能让历史头在完成时额外推移搜索结果。
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
    when {
        loading -> CommunicationListLoading("正在加载短信历史")
        failed -> CommunicationTextAction("加载失败，重试加载", onLoad, modifier = Modifier.fillMaxWidth())
        more -> CommunicationTextAction(label, onLoad, modifier = Modifier.fillMaxWidth())
        else -> CommunicationFootnote(endLabel)
    }
    }
}
