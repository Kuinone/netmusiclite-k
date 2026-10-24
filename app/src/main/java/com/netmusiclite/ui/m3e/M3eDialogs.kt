package com.netmusiclite.ui.m3e

import androidx.compose.ui.text.style.TextAlign

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.*
import com.netmusiclite.ui.components.pressStretch
import com.netmusiclite.ui.components.NcmIcons

@Composable
fun M3eTextInputDialog(title: String, placeholder: String, confirmText: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    AlertDialog(visible = true, onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = {
            FilledIconButton(onClick = { if (value.text.isNotBlank()) onConfirm(value.text.trim()) }, enabled = value.text.isNotBlank(), modifier = Modifier.pressStretch(enabled = value.text.isNotBlank())) {
                Icon(NcmIcons.Check, confirmText)
            }
        },
        dismissButton = { IconButton(onClick = onDismiss, modifier = Modifier.pressStretch()) { Icon(NcmIcons.Close, "取消") } },
        text = {
            // NO-NATIVE: Wear Material3 has no text-field component; Foundation provides Android IME editing.
            BasicTextField(value, { value = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
                // ★ 2026-10-23 需求③：m3e 全站 TextInputDialog 不再画框内占位文字
                //   （与经典主题 Common.TextInputDialog 同一口径，见那里的注释）。
                //   [placeholder] 参数保留仅为兼容调用点签名；框高由 12dp 内边距 + 一行行高撑住。
                decorationBox = { inner -> Box { inner() } })
        })
}

/**
 * m3e 通用对话框容器。
 *
 * @param showClose 是否渲染最下方的「关闭」按钮。
 *   ★ 2026-10-24 用户需求：m3e「结束一起听」确认框要删掉这个最下方关闭按钮
 *   （该框自己有取消/确定两颗圆钮，点遮罩也能关，底部再来一颗关闭既重复又压屏）。
 *   其它对话框保持默认 true。
 */
@Composable
fun M3eDialog(onDismiss: () -> Unit, showClose: Boolean = true, content: @Composable () -> Unit) {
    Dialog(visible = true, onDismissRequest = onDismiss) {
        M3eList {
            item { content() }
            if (showClose) {
                item {
                    Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().pressStretch()) {
                        Text("关闭", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}
