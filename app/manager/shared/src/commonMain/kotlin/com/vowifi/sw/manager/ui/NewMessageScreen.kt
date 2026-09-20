package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vowifi.sw.manager.model.MessageUiState
import com.vowifi.sw.manager.ui.navigation.AppRoute
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

@Composable
internal fun NewMessageScreen(
    state: MessageUiState,
    recipient: String,
    body: String,
    onRecipientChange: (String) -> Unit,
    onBodyChange: (String) -> Unit,
    onClearDraft: () -> Unit,
    onSend: (String, String) -> Unit,
) {
    val compose = projectMessageConversations(messageProjectionOwner(), state).compose
    val submission = rememberMessageDraftSubmission(state, recipient, body, onClearDraft, onSend)
    val requestImeOwner = rememberMessageImeOwnerRequest()
    var recipientTouched by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val topInset = LocalSubpageTopContentInset.current
            val spacing = messageSubpageSpacing()
            val inset = if (maxWidth >= 600.dp) 24.dp else 16.dp
            LazyColumn(
                modifier = Modifier.fillMaxSize().widthIn(max = 960.dp).align(Alignment.TopCenter),
                contentPadding = PaddingValues(
                    start = inset,
                    top = topInset + spacing.contentTopGapDp.dp,
                    end = inset,
                    bottom = 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                messageFixtureDisclosure(AppRoute.NewMessage, state)?.let { disclosure ->
                    item(key = "fixture-disclosure") { MessageFixtureDisclosureBand(disclosure) }
                }
                item(key = "recipient-field") {
                    MessageRecipientField(
                        recipient = recipient,
                        enabled = compose.editingEnabled && !submission.pending,
                        onRecipientChange = {
                            submission.draftEdited()
                            recipientTouched = true
                            onRecipientChange(boundedRecipientDraft(it))
                        },
                    )
                }
                if (recipientTouched || recipient.isNotEmpty()) item(key = "recipient-validation") {
                    Text(if (isValidMessageRecipient(recipient)) "号码格式有效" else "请输入有效号码，可使用开头的 + 和数字。",
                        color = if (isValidMessageRecipient(recipient)) LocalSoftVoWiFiColors.current.interactive else LocalSoftVoWiFiColors.current.error,
                        fontSize = 14.sp, lineHeight = 20.sp)
                }
                if (state is MessageUiState.Available) {
                    item(key = "message-line") {
                        CommunicationNotice(if (state.composerEnabled) "发送线路：当前连接线路" else "只读：可编辑草稿，连接后再发送。不会转交其他线路。")
                    }
                    state.attachmentNotice?.let { notice -> item(key = "messages-notice") { CommunicationNotice(notice) } }
                } else messageStateSpec(state)?.let { panel ->
                    item(key = "messages-state") { CommunicationNotice(panel.title, panel.description) }
                }
                submission.outcome?.let { outcome -> item(key = "submission-result") {
                    CommunicationNotice(when (outcome) {
                        com.vowifi.sw.manager.model.OutgoingMessageStatus.ACCEPTED -> "已受理，不代表接收设备已收到。"
                        com.vowifi.sw.manager.model.OutgoingMessageStatus.DELIVERY_REPORTED -> "已收到状态报告，不代表本机收到 MT 短信。"
                        com.vowifi.sw.manager.model.OutgoingMessageStatus.FAILED -> "发送未完成，草稿已保留。"
                        com.vowifi.sw.manager.model.OutgoingMessageStatus.UNKNOWN -> "发送结果未知，请先核对；不会自动重发。"
                        com.vowifi.sw.manager.model.OutgoingMessageStatus.QUEUED -> "排队中"
                    })
                } }
            }
        }
        MessageComposer(
            body = body,
            compose = newMessageComposerPresentation(compose, (state as? MessageUiState.Available)?.attachmentNotice),
            imeOwner = messageRouteLayout(AppRoute.NewMessage).imeOwner,
            requestImeOwner = requestImeOwner,
            pending = submission.pending,
            onBodyChange = { submission.draftEdited(); onBodyChange(boundedDraftBody(it)) },
            onSend = submission.submit,
            canSend = canSendMessageInput(recipient, body),
        )
    }
}

internal fun newMessageComposerPresentation(compose: MessageComposeSpec, displayedNotice: String?): MessageComposeSpec =
    if (displayedNotice != null && compose.notice == displayedNotice) compose.copy(notice = null) else compose

@Composable
private fun MessageRecipientField(
    recipient: String,
    enabled: Boolean,
    onRecipientChange: (String) -> Unit,
) {
    TextField(
        value = recipient,
        onValueChange = onRecipientChange,
        modifier = Modifier
            .clearFocusOnInactiveRouteAndImeHide()
            .heightIn(min = 48.dp)
            .fillMaxWidth(),
        insideMargin = androidx.compose.ui.unit.DpSize(16.dp, 10.dp),
        cornerRadius = 24.dp,
        colors = TextFieldDefaults.textFieldColors(backgroundColor = LocalSoftVoWiFiColors.current.surface),
        label = "收件号码",
        useLabelAsPlaceholder = true,
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
    )
}

internal fun boundedRecipientDraft(value: String): String = value
    .filter { it == '+' || it in '0'..'9' }
    .take(21)

private fun canSendMessageInput(recipient: String, body: String): Boolean =
    isValidMessageRecipient(recipient) && body.isNotBlank()
