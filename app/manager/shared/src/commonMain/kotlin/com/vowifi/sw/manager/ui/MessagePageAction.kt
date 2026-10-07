package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun MessagePageAction(loading: Boolean, failed: Boolean, more: Boolean, label: String, endLabel: String = "已显示全部历史", onLoad: () -> Unit) {
    when {
        loading -> CommunicationListLoading("正在加载短信历史")
        failed -> CommunicationTextAction("加载失败，重试加载", onLoad, modifier = Modifier.fillMaxWidth())
        more -> CommunicationTextAction(label, onLoad, modifier = Modifier.fillMaxWidth())
        else -> CommunicationFootnote(endLabel)
    }
}
