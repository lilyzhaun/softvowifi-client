package com.vowifi.sw.manager.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity

@Composable internal fun CallKeypad(enabled: Boolean, onDigit: (Char) -> Unit, onPlus: (() -> Unit)? = null) {
    val keys = "123456789*0#"
    val letters = listOf("", "ABC", "DEF", "GHI", "JKL", "MNO", "PQRS", "TUV", "WXYZ", "", "+", "")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        keys.toList().chunked(3).forEachIndexed { row, digits ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                digits.forEachIndexed { column, digit ->
                    PhoneButton(digit.toString(), { onDigit(digit) }, enabled = enabled,
                        onLongClick = if (digit == '0') onPlus else null,
                        longClickLabel = if (digit == '0' && onPlus != null) "输入加号" else null,
                        content = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                PhoneText(digit.toString(), size = 28)
                                val label = letters[row * 3 + column]
                                if (label.isNotEmpty()) PhoneText(label, size = 14, secondary = true)
                                else Spacer(Modifier.height(with(LocalDensity.current) { 20.sp.toDp() }))
                            }
                        })
                }
            }
        }
    }
}
