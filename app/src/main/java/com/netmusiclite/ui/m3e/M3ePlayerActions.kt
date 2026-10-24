package com.netmusiclite.ui.m3e

import com.netmusiclite.ui.components.pressStretch

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.*
import com.netmusiclite.data.*
import com.netmusiclite.ui.screens.player.LyricsPronunciationState
import com.netmusiclite.ui.components.TextInputDialog
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.navigateWithMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun M3eMore(nav: NavHostController) {
    val song = PlayerEngine.current
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(song?.id) {
        song?.let { LyricsPronunciationState.ensureAvailable(context, it) }
    }
    val mode = PlayerEngine.playMode
    val epoch = SessionStore.epoch
    val loggedIn = epoch >= 0 && SessionStore.loggedIn
    var message by remember { mutableStateOf<String?>(null) }
    val downloading = song != null && DownloadStore.states[song.id] == 1
    val downloaded = song != null && DownloadStore.states[song.id] == 2
    M3eList(title = "更多") {
        item(key = "mode") {
            PlayerActionButton(mode.label, when (mode) {
                PlayMode.SHUFFLE -> Icons.Rounded.Shuffle
                PlayMode.SINGLE -> Icons.Rounded.RepeatOne
                PlayMode.HEART -> Icons.Rounded.Favorite
                else -> Icons.Rounded.Repeat
            }) {
                val next = mode.next()
                // ★ 2026-10-18 用户 bug「m3e 播放界面更多里的播放顺序会点到底而不是循环」：
                //   旧实现在 next == HEART 且已登录时**直接调 enterHeartMode()**，绕过了
                //   PlayerEngine.setMode —— 而 enterHeartMode 只负责异步拉流，
                //   它不改 playMode（末尾还靠 `playMode == HEART` 判断才落地队列）。
                //   于是 playMode 仍停在 SINGLE：图标/文案不变、队列不换，再点一次 next 还是
                //   HEART ⇒ 永远卡在单曲循环，用户口径的「点到底」。
                //   现在统一走 setMode：它先落 playMode 再触发 enterHeartMode（见其实现）。
                //   未登录仍跳过心动模式直接回循环（原口径不变）。
                if (next == PlayMode.HEART && !loggedIn) PlayerEngine.setMode(PlayMode.LOOP)
                else PlayerEngine.setMode(next)
            }
        }
        item(key = "download") {
            PlayerActionButton(when { downloaded -> "已下载"; downloading -> "下载中…"; else -> "下载" },
                Icons.Rounded.Download, enabled = song != null && song.id > 0 && !downloaded) {
                song?.let {
                    when {
                        DownloadStore.states[it.id] == 1 -> message = "正在下载中"
                        DownloadStore.pendingCopy().any { pending -> pending.id == it.id } -> message = "已在下载队列"
                        else -> { DownloadStore.enqueue(listOf(it)); message = "开始下载" }
                    }
                }
            }
        }
        if (song != null && LyricsPronunciationState.hasData(song.id)) {
            item(key = "pronunciation") {
                SwitchButton(
                    checked = LyricsPronunciationState.enabled,
                    onCheckedChange = { LyricsPronunciationState.enabled = it },
                    modifier = Modifier.fillMaxWidth().pressStretch(),
                    transformation = LocalItemTransformation.current,
                    label = { Text("开启发音", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                )
            }
        }
        item(key = "queue") { PlayerActionButton("播放列表", Icons.Rounded.QueueMusic) { nav.navigateWithMotion(Routes.QUEUE) } }
        item(key = "sleep") { PlayerActionButton("睡眠定时", Icons.Rounded.Bedtime) { nav.navigateWithMotion(Routes.SLEEP_TIMER) } }
        if (loggedIn) {
            item(key = "collect") { PlayerActionButton("收藏到歌单", Icons.Rounded.LibraryAdd, enabled = song != null && song.id > 0) { nav.navigateWithMotion(Routes.COLLECT) } }
            item(key = "share") { PlayerActionButton("分享", Icons.Rounded.Share, enabled = song != null && song.id > 0) { nav.navigateWithMotion(Routes.SHARE_PICK) } }
        }
    }
    PlayerMessage(message) { message = null }
}

@Composable
private fun PlayerActionButton(title: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().pressStretch(enabled = enabled), enabled = enabled,
        transformation = LocalItemTransformation.current, icon = { Icon(icon, null) },
        label = { Text(title, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) })
}

@Composable
fun M3eSleepTimer() {
    M3eList(title = "睡眠定时") {
        itemsIndexed(SleepMode.entries, key = { _, mode -> mode.name }) { _, mode ->
            val selected = SleepTimer.mode == mode
            RadioButton(selected = selected, onSelect = { SleepTimer.start(mode) },
                modifier = Modifier.fillMaxWidth().pressStretch(), transformation = LocalItemTransformation.current,
                secondaryLabel = if (selected && mode != SleepMode.OFF) ({
                    Text(if (mode == SleepMode.END_OF_SONG) "播完暂停" else {
                        val sec = SleepTimer.remainingSec
                        "${sec / 60}:${(sec % 60).toString().padStart(2, '0')}"
                    }, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }) else null, label = { Text(mode.label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) })
        }
    }
}

@Composable
fun M3eQueue(nav: NavHostController) {
    val queue = PlayerEngine.queue
    M3eList(title = "播放列表") {
        if (queue.isEmpty()) item(key = "empty") {
            Text("队列为空", modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = TextAlign.Center)
        }
        itemsIndexed(queue, key = { index, song -> "q$index-${song.id}" }) { index, song ->
            M3eSongCard(song, onClick = { playFromQueue(nav, index) },
                isCurrent = index == PlayerEngine.index)
        }
    }
}

/**
 * 播放列表里点歌后的导航。
 *
 * ★ 2026-10-18 用户 bug「m3e 首页第一个『播放列表』卡片点歌后不进播放页、退回主页才开始播」：
 *   旧实现无条件 `nav.popBackStack()`。经典主题下播放列表**只**从播放页进入（播放页 → 更多 →
 *   播放列表），pop 一次正好回到播放页，语义成立；但 m3e 首页把「播放列表」做成了第一个
 *   入口大卡（栈是 HOME → QUEUE），此时 popBackStack 退的是 QUEUE 本身 —— 落回**主页**，
 *   于是用户看到的正是「点了歌却退回主页，然后才开始播」。
 *
 * 现在按栈内容判定，两种情况各走各的：
 *  · 栈里已有 PLAYER（从播放页 → 更多 → 播放列表，或其它从播放页深入的路径）→ pop 回它，
 *    播放页仍是唯一实例，转场退回到正在播放的页面；
 *  · 栈里没有 PLAYER（首页直接进播放列表）→ 前进导航进播放页，与其它点歌入口同一套转场。
 *
 * popBackStack(route, inclusive = false) 只在目标确实在栈中时才回退并返回 true，
 * 否则原地不动返回 false —— 正是这里要的判据。
 */
private fun playFromQueue(nav: NavHostController, index: Int) {
    PlayerEngine.playAt(index)
    if (!nav.popBackStack(Routes.PLAYER, false)) {
        nav.navigateWithMotion(Routes.PLAYER)
    }
}

@Composable
fun M3eCollect() {
    val epoch = SessionStore.epoch
    val loggedIn = epoch >= 0 && SessionStore.loggedIn
    var playlists by remember { mutableStateOf(emptyList<PlaylistItem>()) }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    suspend fun load() {
        failed = false
        val result = runCatching { PageCache.userPlaylists(SessionStore.uid).filter { it.isMine && it.specialType != 5 } }
        result.onSuccess { playlists = it }.onFailure { failed = true }
        loaded = true
    }
    LaunchedEffect(loggedIn) { if (loggedIn) load() }
    M3eList(title = "收藏到歌单") {
        if (!loggedIn) item { Text("请先登录账号", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
        else {
            item(key = "create") { PlayerActionButton("新建歌单", Icons.Rounded.Add) { creating = true } }
            if (!loaded) item { Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
            else if (failed) item { PlayerActionButton("重试加载歌单", Icons.Rounded.Refresh) { scope.launch { load() } } }
            else if (playlists.isEmpty()) item { Text("暂无可收藏的歌单", modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp), textAlign = TextAlign.Center) }
            itemsIndexed(playlists, key = { _, playlist -> playlist.id }) { _, playlist ->
                PlayerActionButton(playlist.name, Icons.Rounded.PlaylistAdd) {
                    val songId = PlayerEngine.current?.id
                    if (songId == null || songId <= 0) message = "没有可收藏的歌曲"
                    else scope.launch {
                        // ★ 2026-10-18：「部分歌曲收藏失败」的真根因是服务端对「歌已经在
                        //   目标歌单里」回 502（歌单内歌曲重复），旧实现把它当失败。现在
                        //   NcmApi 用 PlaylistEditOutcome 区分「本次写入成功 / 本来就在里面 /
                        //   真失败」，提示语随之分三档，不再把重复收藏报成「收藏失败」。
                        val outcome = runCatching { NcmApi.addToPlaylist(playlist.id, songId) }
                            .getOrDefault(NcmApi.PlaylistEditOutcome.FAILED)
                        if (outcome != NcmApi.PlaylistEditOutcome.FAILED) {
                            PageCache.invalidatePlaylistDetail(playlist.id)
                            PageCache.invalidatePlaylists()
                        }
                        message = when (outcome) {
                            NcmApi.PlaylistEditOutcome.APPLIED -> "已收藏到「${playlist.name}」"
                            NcmApi.PlaylistEditOutcome.ALREADY -> "「${playlist.name}」里已经有这首歌了"
                            NcmApi.PlaylistEditOutcome.FAILED -> "收藏失败"
                        }
                    }
                }
            }
        }
    }
    // ★ 2026-10-23 需求③：不再传框内占位文案（全站输入框占位统一删除）。
    if (creating) TextInputDialog(title = "新建歌单", onConfirm = { name ->
        creating = false
        scope.launch {
            val id = runCatching { NcmApi.createPlaylist(name) }.getOrNull()
            if (id == null) message = "创建失败"
            else { PageCache.invalidatePlaylists(); load(); message = "已创建「$name」" }
        }
    }, onDismiss = { creating = false })
    PlayerMessage(message) { message = null }
}

/**
 * m3e 的收藏 / 下载提示弹窗（「开始下载」「正在下载中」「已收藏到「xx」」「收藏失败」等）。
 *
 * ★ 2026-10-18 用户口径「m3e 主题下收藏和下载弹窗加上自动消失」：
 *   旧实现用 Wear Material3 的 [ConfirmationDialog]，它按对话框语义**必须点一下确认键**
 *   才会关闭 —— 收藏/下载这类一次性提示没有需要用户拍板的内容，多一步点击纯属负担。
 *   现在保持同一外观（图标 + 居中文字），但 [LaunchedEffect] 在 1.8s 后自动回调
 *   onDismiss；时长与经典主题 CollectScreen 的 toast 自动消失口径一致（1800ms），
 *   两种主题下同一条提示的停留时间相同。
 *   用户主动点击弹窗仍可立即关闭（onDismissRequest 原样透传）。
 */
@Composable
private fun PlayerMessage(message: String?, onDismiss: () -> Unit) {
    LaunchedEffect(message) {
        if (message != null) {
            delay(1800)
            onDismiss()
        }
    }
    ConfirmationDialog(visible = message != null, onDismissRequest = onDismiss,
        text = { Text(message.orEmpty(), maxLines = 3, textAlign = TextAlign.Center) }) {
        Icon(Icons.Rounded.Info, null, modifier = Modifier.size(ConfirmationDialogDefaults.SmallIconSize))
    }
}
