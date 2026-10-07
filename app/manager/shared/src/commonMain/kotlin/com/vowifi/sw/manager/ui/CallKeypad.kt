package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics

@Composable internal fun CallKeypad(enabled: Boolean, onDigit: (Char) -> Unit, onPlus: (() -> Unit)? = null) {
    val keys = "123456789*0#"
    val letters = listOf("", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ", "", "+", "")
    val diameter = maxOf(80.dp, with(LocalDensity.current) { 32.sp.toDp() } + 12.dp)
    val large = LocalDensity.current.fontScale > 1.3f
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        keys.toList().chunked(3).forEachIndexed { row, digits ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                digits.forEachIndexed { column, digit ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PhoneButton(digit.toString(), { onDigit(digit) }, enabled = enabled, material = false, targetSize = diameter,
                        onLongClick = if (digit == '0') onPlus else null,
                        longClickLabel = if (digit == '0' && onPlus != null) "输入加号" else null,
                         content = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                PhoneText(digit.toString(), size = 32)
                                if (!large) PhoneText(letters[row * 3 + column].ifEmpty { " " },
                                    Modifier.clearAndSetSemantics {}, size = 12, secondary = true)
                            }
                         })
                     if (large) PhoneText(letters[row * 3 + column].ifEmpty { " " }, Modifier.clearAndSetSemantics {}, size = 12, secondary = true)
                    }
                }
            }
        }
    }
}
