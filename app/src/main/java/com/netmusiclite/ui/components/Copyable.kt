package com.netmusiclite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 长按复制面板的状态（全局单例）。
 *
 * 状态放全局而不是各页面 hoist：私信页、房间内聊天页、两种类主题的气泡都要「长按→复制」，
 * 逐页传 `copyTarget` 状态会把同一个 6 行需求变成 4 个文件的参数膨胀。
 * 宿主 [CopySheetHost] 挂在 App 根，一次注册全站可用。
 */
object CopySheet {
    /** null = 面板关闭 */
    var text by mutableStateOf<String?>(null)
        private set
    /** true = 「选文字」模式（文本进 SelectionContainer，可拖选局部再复制） */
    var selecting by mutableStateOf(false)
        private set
    /** 复制成功的瞬时反馈（800ms 后自动关面板） */
    var copied by mutableStateOf(false)
        private set

    fun show(t: String) {
        if (t.isBlank()) return
        text = t; selecting = false; copied = false
    }

    fun pick() { selecting = true }
    fun back() { selecting = false }
    fun markCopied() { copied = true }
    fun close() { text = null; selecting = false; copied = false }
}

/**
 * 给气泡挂「长按 = 复制」。与点击行为合并成**同一个** `combinedClickable`：
 * 另起一层 `pointerInput(detectTapGestures)` 的话，长按松手时那层 `clickable`
 * 会跟着把 onClick 也触发（歌曲气泡就会「长按复制却把歌播了」）。
 *
 * [onClick] = 这条气泡原有的点击行为（打开歌曲 / 进房），null = 纯文本气泡，点击无动作。
 */
@Composable
fun Modifier.copyOnLongPress(text: String, onClick: (() -> Unit)? = null): Modifier =
    combinedClickable(
        onClick = { onClick?.invoke() },
        onLongClick = { CopySheet.show(text) },
    )

/** 面板里的一枚按钮（自绘，不依赖任一主题色板 —— 遮罩上是固定深底白字） */
@Composable
private fun SheetButton(label: String, accent: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.width(150.dp).height(30.dp)
            .clip(RoundedCornerShape(50))
            .background(if (accent) Color(0xFF1DB954) else Color(0xFF2A2A31))
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 11.sp,
                color = if (accent) Color.Black else Color(0xFFEAEAEA),
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/**
 * 长按复制的全局宿主：`MainActivity.setContent` 里与 `AppRoot()` 并排挂一层。
 *
 * 两段式（用户口径「长按复制消息（可选区域）」）：
 * · **复制全文** —— 一键进剪贴板；
 * · **选文字** —— 正文换成 `SelectionContainer`，长按/拖动光标只选想要那几个字，
 *   再用系统浮窗的「复制」；表盘上选择手柄很小，所以同时保留「复制全文」兜底。
 */
@Composable
fun CopySheetHost() {
    val t = CopySheet.text ?: return
    val clip = LocalClipboardManager.current
    LaunchedEffect(CopySheet.copied) {
        if (CopySheet.copied) {
            delay(800)
            CopySheet.close()
        }
    }
    Box(
        Modifier.fillMaxSize()
            .background(Color(0xBB000000))
            .pointerInput(Unit) { detectTapGestures { CopySheet.close() } },
        contentAlignment = Alignment.Center,
    ) {
        if (CopySheet.copied) {
            BasicText(
                "已复制",
                style = androidx.compose.ui.text.TextStyle(
                    fontSize = 13.sp, color = Color.White, textAlign = TextAlign.Center,
                ),
                modifier = Modifier.padding(horizontal = 30.dp),
            )
            return@Box
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(172.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xF2151519))
                .padding(horizontal = 10.dp, vertical = 9.dp)
                // 面板本体不吃遮罩的「点空白关闭」：卡片内的空白也归关闭
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            if (!CopySheet.selecting) {
                // 原文预览：最多两行，长消息看不全也有下面的「选文字」
                BasicText(
                    t,
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 9.5.sp, color = Color(0xFFB6B6BE), textAlign = TextAlign.Center,
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
                SheetButton("复制全文", accent = true) {
                    clip.setText(AnnotatedString(t))
                    CopySheet.markCopied()
                }
                Spacer(Modifier.height(5.dp))
                SheetButton("选文字", accent = false) { CopySheet.pick() }
            } else {
                SelectionContainer {
                    BasicText(
                        t,
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 11.sp, color = Color.White, textAlign = TextAlign.Center,
                        ),
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicText(
                    "长按正文选中要复制的字",
                    style = androidx.compose.ui.text.TextStyle(
                        fontSize = 8.sp, color = Color(0xFF8C8C96), textAlign = TextAlign.Center,
                    ),
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
                )
                SheetButton("复制全文", accent = true) {
                    clip.setText(AnnotatedString(t))
                    CopySheet.markCopied()
                }
                Spacer(Modifier.height(5.dp))
                SheetButton("返回", accent = false) { CopySheet.back() }
            }
        }
    }
}
