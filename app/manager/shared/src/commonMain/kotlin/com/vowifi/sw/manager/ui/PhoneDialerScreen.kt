package com.vowifi.sw.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vowifi.sw.manager.data.CallProtocolCodec

internal fun acceptPhoneDraft(previous: String, next: String): String =
    if (next.length <= 21 && next.matches(Regex("\\+?[0-9*#]{0,20}"))) next else previous

internal val LocalPhoneBack = staticCompositionLocalOf<() -> Unit> { error("Phone route navigation is not provided") }

@Composable fun PhoneDialerScreen(
    number: String, onNumber: (String) -> Unit, line: String,
    enabled: Boolean, busy: Boolean, message: String?, onDial: () -> Unit,
    onBack: () -> Unit, onHistory: () -> Unit,
    onPermissions: (() -> Unit)? = null,
) {
    val palette = phoneColors()
    Column(Modifier.fillMaxSize().background(palette.canvas).safeDrawingPadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.weight(1f)) { PhoneNavigation("返回", onBack, CallPhoneIcons.Back, false) }
            PhoneText("拨号", size = 24)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { PhoneNavigation("历史", onHistory) }
        }
        PhoneDialerContent(number, onNumber, line, enabled, busy, message, onDial, onPermissions)
    }
}

@Composable internal fun PhoneDialerContent(number: String, onNumber: (String) -> Unit, line: String,
    enabled: Boolean, busy: Boolean, message: String?, onDial: () -> Unit, onPermissions: (() -> Unit)? = null) {
    val palette = phoneColors()
    var editor by remember { mutableStateOf(TextFieldValue(number, TextRange(number.length))) }
    val feedbackNumber = remember(message) { number }
    LaunchedEffect(number) { if (editor.text != number) editor = TextFieldValue(number, TextRange(number.length)) }
    fun update(value: TextFieldValue) {
        if (acceptPhoneDraft(number, value.text) == value.text) { editor = value; onNumber(value.text) }
    }
    fun insert(value: String) {
        val start = editor.selection.min
        val next = editor.text.replaceRange(start, editor.selection.max, value)
        update(TextFieldValue(next, TextRange(start + value.length)))
    }
    fun delete() {
        val start = if (editor.selection.collapsed) (editor.selection.min - 1).coerceAtLeast(0) else editor.selection.min
        update(TextFieldValue(editor.text.removeRange(start, editor.selection.max), TextRange(start)))
    }
    Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        PhoneText(line, secondary = true)
        Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(editor, ::update, Modifier.weight(1f).heightIn(min = 64.dp).semantics { contentDescription = "电话号码" },
                enabled = !busy, textStyle = TextStyle(color = palette.text, fontSize = 28.sp, lineHeight = 36.sp,
                    fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, showKeyboardOnFocus = false),
                decorationBox = { inner -> Box(contentAlignment = Alignment.Center) {
                    if (number.isEmpty()) PhoneText("输入号码", secondary = true, size = 24)
                    inner()
                } })
            PhoneButton("删除号码", ::delete, icon = CallPhoneIcons.Delete, compact = true, showLabel = false,
                enabled = number.isNotEmpty() && !busy, onLongClick = { update(TextFieldValue("")) }, longClickLabel = "清空号码")
        }
        CallKeypad(!busy, { insert(it.toString()) }, {
            val next = if (number.startsWith("+")) number else "+$number"
            update(TextFieldValue(next, TextRange(next.length)))
        })
        PhoneButton(if (busy) "正在准备" else "拨打", onDial, Modifier.fillMaxWidth(), icon = CallPhoneIcons.Phone,
            selected = true, wide = true, busy = busy, enabled = enabled && CallProtocolCodec.isValidDialNumber(number))
        if (number.isNotEmpty() && !CallProtocolCodec.isValidDialNumber(number)) PhoneText("请输入电话号码，暂不支持 USSD", secondary = true)
        message?.takeIf { !enabled || feedbackNumber == number }?.let { PhoneText(it, secondary = true) }
        onPermissions?.let { PhoneNavigation("应用权限", it) }
    }
}
