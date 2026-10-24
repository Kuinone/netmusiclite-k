package com.netmusiclite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netmusiclite.update.UpdateFlow
import com.netmusiclite.update.UpdateInfo
import com.netmusiclite.ui.theme.PlayAccent
import com.netmusiclite.ui.theme.TextSecondary
import com.netmusiclite.ui.theme.onColorFor

/**
 * 更新 UI 双件套（2026-10-02 用户口径）：
 *  · [UpdateHintPill]  —— 播放页顶部「发现新版本」胶囊；点它直接弹更新弹窗。
 *    2026-10-03 收紧：同一版本只弹一次、点一次即消失（记录见 UpdatePrompt），
 *    下载中/授权/失败等过程态不再占用胶囊 —— 进度看弹窗与系统下载通知。
 *  · [UpdateAvailableDialog] —— 下载 / 校验 / 拉起安装器 / 授权引导的唯一弹窗，
 *    播放页胶囊与设置页「检查更新」共用同一份状态机（[UpdateFlow]），两处永远显示同一进度。
 */

/** 胶囊文案（null = 隐藏胶囊）；播放页顶部胶囊专用，只承载「发现新版本」提示。 */
fun updatePillLabel(versionName: String?): String? = versionName?.let { "发现新版本 v$it" }

/**
 * 播放页顶部提示胶囊：Accent 实底 + 深/浅自动反差字，点击直接开更新弹窗。
 * 位置由调用方决定（播放页放在标题上方，展开时把标题自然下推）。
 */
@Composable
fun UpdateHintPill(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    if (com.netmusiclite.data.AppearancePrefs.isM3e) {
        com.netmusiclite.ui.m3e.M3eUpdateHint(label, modifier, onClick)
        return
    }
    // 播放组明暗对调时走 Play* 作用域色（作用域外 = 原 Accent/OnAccent）
    val accent = PlayAccent
    val onAccent = onColorFor(accent)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(accent)
            .border(1.dp, onAccent.copy(alpha = 0.22f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Icon(
            NcmIcons.Download,
            contentDescription = null,
            tint = onAccent,
            modifier = Modifier.size(10.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            fontSize = 9.sp, fontWeight = FontWeight.Medium,
            color = onAccent, maxLines = 1,
        )
    }
}

/**
 * 更新弹窗（发现新版本 → 下载 → 校验 → 拉起安装器；缺权限则引导去系统设置）。
 *
 * 按钮随 [UpdateFlow.state] 变形：
 *  Idle（本地无包）→「下载更新」；Idle（本地已校验）→「立即安装」；
 *  Downloading →「下载中 x%」（禁用，走「后台下载」关弹窗）；
 *  Installing →「正在安装…」（禁用）；Failed →「重试」；NeedPermission →「去开启权限」。
 */
@Composable
fun UpdateAvailableDialog(
    info: UpdateInfo,
    installedVersionName: String,
    onStart: () -> Unit,
    onInstallNow: () -> Unit,
    onOpenPermission: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state by UpdateFlow.state.collectAsState()
    val localReady by UpdateFlow.localPackageReady.collectAsState()

    val status: String = when (val st = state) {
        UpdateFlow.State.Idle ->
            if (localReady) "更新包已下载并通过校验，点「立即安装」拉起安装器。"
            else "下载完成后自动拉起安装器；若提示未知来源，请允许「安装未知应用」。"
        is UpdateFlow.State.Downloading -> "下载中 ${st.percent}%（失败自动换源重试，可「后台下载」离开本页）"
        is UpdateFlow.State.Installing -> "已拉起系统安装器，请按提示完成安装。"
        is UpdateFlow.State.NeedPermission -> "需要「允许来自此来源」权限：开启后返回即自动安装。"
        is UpdateFlow.State.Failed -> st.reason
    }

    if (com.netmusiclite.data.AppearancePrefs.isM3e) {
        com.netmusiclite.ui.m3e.M3eUpdateDialog(info, installedVersionName, state, localReady, status,
            onStart, onInstallNow, onOpenPermission, onDismiss)
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 v${info.versionName}", fontSize = 14.sp) },
        text = {
            Text(
                buildString {
                    if (info.changelog.isNotEmpty()) {
                        append(info.changelog)
                        append("\n\n")
                    }
                    append("当前 v$installedVersionName → 最新 v${info.versionName}\n")
                    append(status)
                },
                fontSize = 11.sp, color = TextSecondary, lineHeight = 15.sp,
            )
        },
        confirmButton = {
            when (val st = state) {
                is UpdateFlow.State.Downloading -> TextButton(onClick = {}, enabled = false) {
                    Text("下载中 ${st.percent}%", fontSize = 12.sp)
                }
                is UpdateFlow.State.Installing -> TextButton(onClick = {}, enabled = false) {
                    Text("正在安装…", fontSize = 12.sp)
                }
                is UpdateFlow.State.NeedPermission -> TextButton(onClick = onOpenPermission) {
                    Text("去开启权限", fontSize = 12.sp)
                }
                is UpdateFlow.State.Failed -> TextButton(onClick = onStart) {
                    Text("重试", fontSize = 12.sp)
                }
                UpdateFlow.State.Idle -> TextButton(onClick = if (localReady) onInstallNow else onStart) {
                    Text(if (localReady) "立即安装" else "下载更新", fontSize = 12.sp)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    when (state) {
                        is UpdateFlow.State.Downloading -> "后台下载"
                        is UpdateFlow.State.Installing -> "关闭"
                        else -> "暂不更新"
                    },
                    fontSize = 12.sp,
                )
            }
        },
    )
}

/** 读已装版本名（弹窗正文用）。 */
fun installedVersionNameOf(ctx: android.content.Context): String = runCatching {
    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
}.getOrNull() ?: "未知"
