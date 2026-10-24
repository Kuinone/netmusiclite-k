package com.netmusiclite.ui.m3e

import com.netmusiclite.ui.components.pressStretch

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.PersonOutline
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.*
import com.netmusiclite.ui.components.QrCodeImage
import com.netmusiclite.update.UpdateFlow
import com.netmusiclite.update.UpdateInfo

/** Presentation only: the source login screen remains the single owner of QR polling. */
@Composable
fun M3eLoginQr(heading: String, qrContent: String?, status: String, scanned: Boolean,
    onGuestEnter: (() -> Unit)?) {
    M3eList(title = heading) {
        item(key = "login_qr") {
            Card(onClick = {}, modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 10.dp, horizontal = 6.dp),
                transformation = LocalItemTransformation.current) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (qrContent.isNullOrEmpty()) CircularProgressIndicator(Modifier.size(48.dp).padding(8.dp))
                    else QrCodeImage(qrContent, 153.6.dp)
                }
            }
        }
        item(key = "login_status") {
            Card(onClick = {}, modifier = Modifier.fillMaxWidth(), transformation = LocalItemTransformation.current) {
                if (scanned) Icon(Icons.Rounded.CheckCircle, "已扫码", modifier = Modifier.align(Alignment.CenterHorizontally))
                Text(status, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
            }
        }
        if (onGuestEnter != null) item(key = "guest_entry") {
            Button(onClick = onGuestEnter, modifier = Modifier.fillMaxWidth().pressStretch(),
                colors = ButtonDefaults.filledTonalButtonColors(), transformation = LocalItemTransformation.current,
                icon = { Icon(Icons.Rounded.PersonOutline, null) }, label = { Text(modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, text = "不登录进入音乐") })
        }
    }
}

/** Presentation only: startRoam and its navigation callback continue to live in the source screen. */
@Composable
fun M3eRoamStatus(status: String, onBack: () -> Unit) {
    val failed = status == "没有拿到推荐歌曲"
    M3eList(title = "私人漫游") {
        item(key = "roam_status") {
            Card(onClick = {}, modifier = Modifier.fillMaxWidth(), transformation = LocalItemTransformation.current) {
                Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    if (failed) Icon(Icons.Rounded.Explore, null, Modifier.size(48.dp))
                    else CircularProgressIndicator(Modifier.size(58.dp))
                }
                Text(status, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
            }
        }
        if (failed) item(key = "roam_return") {
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth().pressStretch(), transformation = LocalItemTransformation.current,
                label = { Text(modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, text = "返回") })
        }
    }
}

@Composable
fun M3eUpdateHint(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier.pressStretch(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        icon = { Icon(Icons.Rounded.SystemUpdate, null, Modifier.size(16.dp)) },
        label = { Text(modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, text = label, style = MaterialTheme.typography.labelSmall, maxLines = 1) })
}

/** Uses the existing state and callbacks; rendering never starts a download or installation. */
@Composable
fun M3eUpdateDialog(info: UpdateInfo, installedVersionName: String, state: UpdateFlow.State,
    localReady: Boolean, status: String, onStart: () -> Unit, onInstallNow: () -> Unit,
    onOpenPermission: () -> Unit, onDismiss: () -> Unit) {
    val actionLabel = when (state) {
        is UpdateFlow.State.Downloading -> "下载中 ${state.percent}%"
        is UpdateFlow.State.Installing -> "正在安装…"
        is UpdateFlow.State.NeedPermission -> "去开启权限"
        is UpdateFlow.State.Failed -> "重试"
        UpdateFlow.State.Idle -> if (localReady) "立即安装" else "下载更新"
    }
    val action = when (state) {
        is UpdateFlow.State.NeedPermission -> onOpenPermission
        is UpdateFlow.State.Failed -> onStart
        UpdateFlow.State.Idle -> if (localReady) onInstallNow else onStart
        else -> ({})
    }
    AlertDialog(visible = true, onDismissRequest = onDismiss,
        title = { Text("发现新版本 v${info.versionName}") },
        icon = { Icon(Icons.Rounded.SystemUpdate, null) },
        text = {
            Text(buildString {
                if (info.changelog.isNotEmpty()) append(info.changelog).append("\n\n")
                append("当前 v$installedVersionName → 最新 v${info.versionName}\n")
                append(status)
            }, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        },
        edgeButton = {
            EdgeButton(onClick = action, enabled = state !is UpdateFlow.State.Downloading && state !is UpdateFlow.State.Installing) {
                Text(actionLabel, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 1)
            }
        },
        content = {
            if (state is UpdateFlow.State.Downloading) item {
                LinearProgressIndicator(progress = { state.percent.coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
            }
            if (state is UpdateFlow.State.Installing) item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
            }
            item {
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().pressStretch(), colors = ButtonDefaults.filledTonalButtonColors(),
                    label = { Text(modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, text = when (state) {
                        is UpdateFlow.State.Downloading -> "后台下载"
                        is UpdateFlow.State.Installing -> "关闭"
                        else -> "暂不更新"
                    }) })
            }
        })
}
