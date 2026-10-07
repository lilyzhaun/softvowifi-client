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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import androidx.compose.ui.platform.LocalDensity
import top.yukonga.miuix.kmp.blur.layerBackdrop

internal fun acceptPhoneDraft(previous: String, next: String): String =
    if (next.length <= 21 && next.matches(Regex("\\+?[0-9*#]{0,20}"))) next else previous

internal val LocalPhoneBack = staticCompositionLocalOf<() -> Unit> { error("Phone route navigation is not provided") }

@Composable fun PhoneDialerScreen(
    number: String, onNumber: (String) -> Unit, line: String,
    enabled: Boolean, busy: Boolean, message: String?, onDial: () -> Unit,
    onBack: () -> Unit, onHistory: () -> Unit,
    onPermissions: (() -> Unit)? = null,
    lineContent: (@Composable () -> Unit)? = null,
    onRefresh: (() -> Unit)? = null,
) = PhoneStyle {
    val palette = phoneColors()
    val backdrop = rememberCommunicationBackdrop()
    Column(Modifier.fillMaxSize().background(palette.canvas).navigationBarsPadding().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
        CompositionLocalProvider(LocalCommunicationBackdrop provides backdrop) {
        CommunicationNavigation("拨号", leading = { PhoneNavigation("返回", onBack, CallPhoneIcons.Back, false) },
            trailing = {})
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            val contentHeight = maxHeight
            Column(Modifier.fillMaxWidth().then(backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)
                .phoneOverscroll().verticalScroll(rememberScrollState(), overscrollEffect = null).heightIn(min = contentHeight),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                PhoneDialerContent(number, onNumber, line, enabled, busy, message, onDial, onPermissions, lineContent, onRefresh)
            }
        }
    }
}

@Composable internal fun PhoneDialerContent(number: String, onNumber: (String) -> Unit, line: String,
    enabled: Boolean, busy: Boolean, message: String?, onDial: () -> Unit, onPermissions: (() -> Unit)? = null,
    lineContent: (@Composable () -> Unit)? = null, onRefresh: (() -> Unit)? = null) {
    val palette = phoneColors()
    val active = LocalAppRouteActive.current
    val editingEnabled = active && !busy
    var editor by remember { mutableStateOf(TextFieldValue(number, TextRange(number.length))) }
    val feedbackNumber = remember(message) { number }
    LaunchedEffect(number) { if (editor.text != number) editor = TextFieldValue(number, TextRange(number.length)) }
    fun update(value: TextFieldValue) {
        if (editingEnabled && acceptPhoneDraft(number, value.text) == value.text) { editor = value; onNumber(value.text) }
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
    Column(Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (lineContent != null) lineContent() else PhoneText(line, secondary = true, size = 16)
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(48.dp))
            BasicTextField(editor, ::update, Modifier.weight(1f).heightIn(min = 64.dp).clearFocusOnInactiveRouteAndImeHide().semantics { contentDescription = "电话号码" },
                enabled = editingEnabled, singleLine = true, textStyle = TextStyle(color = palette.text, fontSize = 28.sp, lineHeight = 36.sp,
                    fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum", textAlign = TextAlign.Center),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, showKeyboardOnFocus = false),
                visualTransformation = PhoneNumberTransformation,
                decorationBox = { inner -> Box(contentAlignment = Alignment.Center) {
                    PhoneText("输入号码", if (number.isEmpty()) Modifier else Modifier.alpha(0f).clearAndSetSemantics {}, secondary = true, size = 24)
                    inner()
                } })
            PhoneButton("删除号码", ::delete, icon = CallPhoneIcons.Delete, compact = true, showLabel = false,
                enabled = number.isNotEmpty() && editingEnabled, onLongClick = { update(TextFieldValue("")) }, longClickLabel = "清空号码")
        }
        CallKeypad(editingEnabled, { insert(it.toString()) }, {
            val next = if (number.startsWith("+")) number else "+$number"
            update(TextFieldValue(next, TextRange(next.length)))
        })
        PhoneButton(if (busy) "正在准备" else "拨打", onDial, icon = CallPhoneIcons.Phone,
            prominent = true, targetSize = 64.dp, showLabel = false,
            busy = busy, enabled = active && enabled && CallProtocolCodec.isValidDialNumber(number))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.alpha(0f).clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PhoneText("请输入电话号码，暂不支持 USSD", secondary = true, size = 14)
                message?.let { PhoneText(it, secondary = true) }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (number.isNotEmpty() && !CallProtocolCodec.isValidDialNumber(number)) PhoneText("请输入电话号码，暂不支持 USSD", secondary = true, size = 14)
                message?.takeIf { !enabled || feedbackNumber == number }?.let { PhoneText(it, secondary = true) }
            }
        }
        onRefresh?.let { CommunicationTextAction("刷新通话状态", it, enabled = active && !busy) }
        onPermissions?.let { PhoneNavigation("应用权限", it) }
    }
}
