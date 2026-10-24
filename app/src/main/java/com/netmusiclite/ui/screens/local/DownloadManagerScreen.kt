package com.netmusiclite.ui.screens.local

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.netmusiclite.data.DownloadStore
import com.netmusiclite.data.QualityPrefs
import com.netmusiclite.data.Song
import com.netmusiclite.ui.components.CenterHint
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.SelectionDeleteCircles
import com.netmusiclite.ui.components.SongCover
import com.netmusiclite.ui.components.StackedCard
import com.netmusiclite.ui.components.StackedCardList
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.OnAccent
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import com.netmusiclite.ui.theme.TextTertiary
import kotlinx.coroutines.delay

/**
 * 下载管理：列表最上方「暂停 + 速度/剩余时间」胶囊 + 下载中 / 排队中 / 失败 三段队列。
 * 数据来自 DownloadStore 的可组合观测态（currentDownload/pendingQueue/failed），状态变化自动刷新。
 * 失败条目点击即重新入队（-110 需 VIP / -105 登录失效 等原因直接展示在卡片上）。
 *
 * ★ 2026-10-03 用户要求：
 *  1) 新增胶囊显示下载速度与预计剩余时间（500ms 采样 DownloadStore 字节计数，EMA 平滑）；
 *  2) 暂停后所有任务状态文案改为「已暂停」；
 *  3) 长按任务卡进入多选可批量删除下载任务（下载中→协作取消清理半截文件；
 *     排队中→出队；失败→清除记录。见 DownloadStore.removeTasks）；
 *  4) 多选态下行内第二行（下载中/排队中/原因）统一改为显示音质（标准/较高/无损）；
 *  5) 胶囊并入列表最上方、随列表一起滑走（不再钉死在页面顶部），
 *     且底色随浅色/深色主题自适应（浅色白 72% / 深色黑 34%，同顶部标题胶囊标定）。
 */
@Composable
fun DownloadManagerScreen(nav: NavHostController) {
    // 先读版本号建立重组观测，再取锁内快照拷贝遍历——300 首并发批量下载时 IO 线程
    // 高频增删队列/失败表，直接迭代 SnapshotStateList 会 CME 崩溃（2026-09-25）
    val pv = DownloadStore.pendingVersion
    val fv = DownloadStore.failedVersion
    val updating = DownloadStore.currentDownload
    val paused = DownloadStore.paused
    val pending = DownloadStore.pendingCopy()
    val failed = DownloadStore.failedCopy()
    val total = (if (updating != null) 1 else 0) + pending.size + failed.size
    val activeTasks = updating != null || pending.isNotEmpty()

    // 多选态（长按任意任务卡进入）：key = songId
    val selected = remember { mutableStateMapOf<Long, Song>() }
    val selectMode = selected.isNotEmpty()
    fun toggle(s: Song) {
        if (selected.containsKey(s.id)) selected.remove(s.id) else selected[s.id] = s
    }

    // 速度采样（500ms）：瞬时字节差 → EMA 平滑；空闲滑落归零。
    // tick 仅在真的有下载在跑时自增——驱动行内进度百分比与胶囊文案刷新，空闲时零重组
    var speedBps by remember { mutableStateOf(0f) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        var last = DownloadStore.bytesReadTotal
        var at = android.os.SystemClock.elapsedRealtime()
        var ema = 0f
        while (true) {
            delay(500)
            val now = android.os.SystemClock.elapsedRealtime()
            val cur = DownloadStore.bytesReadTotal
            val inst = (cur - last).toFloat() * 1000f / (now - at).coerceAtLeast(1L)
            last = cur
            at = now
            ema = when {
                inst > 512f -> if (ema <= 0f) inst else ema * 0.5f + inst * 0.5f
                ema < 4096f -> 0f
                else -> ema * 0.35f
            }
            speedBps = ema
            if (DownloadStore.currentDownload != null) tick++
        }
    }

    // 下载中行状态文案：暂停优先；否则「下载中… 45%」（tick 驱动重算）
    val dlStatus = remember(tick, paused) {
        if (paused) "已暂停"
        else {
            val t = DownloadStore.currentTotal
            if (t > 0L) "下载中… ${(DownloadStore.currentBytes * 100L / t).coerceIn(0L, 100L)}%" else "下载中…"
        }
    }
    val quality = QualityPrefs.label()
    // 顶部速度胶囊文案：已暂停 / 连接中… / 1.2MB/s · 剩3分（tick 驱动重算）
    val speedText = remember(tick, speedBps, paused, updating, pending.size) {
        when {
            paused -> "已暂停"
            speedBps >= 512f -> {
                val eta = estimateEtaSec(speedBps, pending)
                fmtSpeed(speedBps) + if (eta > 0L) " · 剩${fmtEta(eta)}" else ""
            }
            updating != null -> "连接中…"
            else -> "准备中…"
        }
    }

    Box {
        StackedCardList(title = "下载管理", progressTotal = total, cardHeight = 56.dp) {
            if (total == 0) {
                item(key = "empty") {
                    CenterHint("没有下载任务\n在歌单/艺人页长按多选即可批量下载", Modifier.fillMaxWidth().height(240.dp))
                }
            } else {
                // 胶囊行并入列表最上方（用户口径：随列表一起滑走，不再固定悬浮）；
                // 纯失败列表无可暂停任务，不显示
                if (activeTasks) {
                    item(key = "capsules") {
                        Row(
                            Modifier.fillMaxWidth().height(56.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PausePill(if (paused) "继续下载" else "暂停下载") {
                                if (paused) DownloadStore.resumeAll() else DownloadStore.pauseAll()
                            }
                            Spacer(Modifier.width(8.dp))
                            SpeedPill(speedText)
                        }
                    }
                }
                if (updating != null) {
                    item(key = "sec_dl") { SectionLabel("下载中") }
                    val s = updating
                    item(key = "dl_${s.id}") {
                        TaskCard(
                            song = s,
                            status = if (selectMode) quality else dlStatus,
                            selectMode = selectMode,
                            selected = selected.containsKey(s.id),
                            onClick = { if (selectMode) toggle(s) },
                            onLongClick = { toggle(s) },
                        )
                    }
                }
                if (pending.isNotEmpty()) {
                    item(key = "sec_pd") { SectionLabel("排队中") }
                    pending.forEach { s ->
                        item(key = "pd_${s.id}") {
                            TaskCard(
                                song = s,
                                status = if (selectMode) quality else if (paused) "已暂停" else "排队中",
                                selectMode = selectMode,
                                selected = selected.containsKey(s.id),
                                onClick = { if (selectMode) toggle(s) },
                                onLongClick = { toggle(s) },
                            )
                        }
                    }
                }
                if (failed.isNotEmpty()) {
                    item(key = "sec_fl") { SectionLabel("失败") }
                    failed.forEach { f ->
                        item(key = "fl_${f.song.id}") {
                            TaskCard(
                                song = f.song,
                                status = if (selectMode) quality else f.reason,
                                reasonTint = !selectMode,
                                selectMode = selectMode,
                                selected = selected.containsKey(f.song.id),
                                onClick = {
                                    if (selectMode) toggle(f.song) else DownloadStore.enqueue(listOf(f.song))
                                },
                                onLongClick = { toggle(f.song) },
                            )
                        }
                    }
                }
            }
        }
        if (selectMode) {
            // 多选态：顶部换成 全选/取消/删除（全选 = 全部任务：下载中+排队+失败，均可移除）
            SelectionDeleteCircles(
                count = selected.size,
                onDelete = {
                    DownloadStore.removeTasks(selected.keys.toList())
                    selected.clear()
                },
                onCancel = { selected.clear() },
                selectAll = {
                    updating?.let { selected[it.id] = it }
                    pending.forEach { selected[it.id] = it }
                    failed.forEach { selected[it.song.id] = it.song }
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

/**
 * 胶囊底色：跟随浅色/深色主题（同顶部标题与 Toast 的悬浮件标定）——
 * 浅色主题白 72%（叠 #EFEFF3 底可辨），深色主题黑 34%。
 * ⚠ 旧版硬编码「白 10~14%」在浅色主题下白叠白完全看不见（2026-10-03 用户反馈）。
 */
@Composable
private fun capsuleBg(): Color =
    if (com.netmusiclite.ui.theme.isLightTheme) Color.White.copy(alpha = 0.72f)
    else Color.Black.copy(alpha = 0.34f)

/** 暂停/继续胶囊按钮（并入列表内容，随列表滚动） */
@Composable
private fun PausePill(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(capsuleBg())
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(text, fontSize = 9.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
    }
}

/** 速度/剩余时间信息胶囊（不可点击；强调色文字随主题自适应，与暂停胶囊区分） */
@Composable
private fun SpeedPill(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(capsuleBg())
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(text, fontSize = 9.sp, fontWeight = FontWeight.Medium, color = Accent)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextSecondary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 2.dp),
    )
}

/** 下载任务行：封面 + 歌名/艺人 + 状态/原因；多选态右侧换 19dp 勾选圈（与歌曲卡一致） */
@Composable
private fun TaskCard(
    song: Song,
    status: String,
    reasonTint: Boolean = false,
    selectMode: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    StackedCard(onClick = onClick, onLongClick = onLongClick, height = 56.dp) {
        SongCover(song, 38.dp, shape = RoundedCornerShape(8.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, fontSize = 12.sp, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                status,
                fontSize = 9.sp,
                color = if (reasonTint) Accent else TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (selectMode) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .size(19.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, if (selected) Accent else TextTertiary, CircleShape)
                    .background(if (selected) Accent else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(NcmIcons.Check, null, tint = OnAccent, modifier = Modifier.size(12.dp))
            }
        }
    }
}

// ---------- 速度 / 剩余时间估算 ----------

/** 当前档位码率估算（kbps）：无损按 FLAC 均值 ~1000 近似，极高 320 / 标准 128 */
private fun estKbps(): Int = when (QualityPrefs.level) {
    "lossless" -> 1000
    "exhigh" -> 320
    else -> 128
}

/**
 * 预计剩余时间（秒）：当前歌剩余字节（总长未知时用时长×码率估算）+ 排队歌曲估算字节。
 * 速度过低或无需下载返回 0（胶囊只显示速度）。
 */
private fun estimateEtaSec(speedBps: Float, pending: List<Song>): Long {
    if (speedBps < 512f) return 0L
    var remain = 0L
    DownloadStore.currentDownload?.let {
        val total = DownloadStore.currentTotal
        if (total > 0L) remain += (total - DownloadStore.currentBytes).coerceAtLeast(0L)
        else remain += it.durationMs.toLong() * estKbps() / 8L
    }
    pending.forEach { remain += it.durationMs.toLong() * estKbps() / 8L }
    return if (remain <= 0L) 0L else (remain / speedBps).toLong()
}

private fun fmtSpeed(bps: Float): String = when {
    bps >= 1024f * 1024f -> "%.1fMB/s".format(bps / 1024f / 1024f)
    bps >= 1024f -> "%.0fKB/s".format(bps / 1024f)
    else -> "%.0fB/s".format(bps)
}

private fun fmtEta(sec: Long): String = when {
    sec < 60L -> "${sec}秒"
    sec < 3600L -> "${(sec + 59L) / 60L}分"
    else -> "${sec / 3600L}时${(sec % 3600L) / 60L}分"
}
