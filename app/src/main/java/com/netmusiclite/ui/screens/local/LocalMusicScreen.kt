package com.netmusiclite.ui.screens.local

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.netmusiclite.data.DownloadStore
import com.netmusiclite.data.ImportStore
import com.netmusiclite.data.LocalMusic
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.Song
import com.netmusiclite.data.SongQuery
import com.netmusiclite.ui.components.CenterHint
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.components.ListFilterCard
import com.netmusiclite.ui.components.MediaPermissions
import com.netmusiclite.ui.components.rememberAliasAwareSongs
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.StackedIconCard
import com.netmusiclite.ui.components.SelectionDeleteCircles
import com.netmusiclite.ui.components.StackedCardList
import com.netmusiclite.ui.components.StackedSongCard
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.NavMotionSpec
import com.netmusiclite.ui.nav.navigateWithMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 本地音乐：设备媒体库 + 应用内已下载 + 手动导入合并（歌曲行卡片堆叠）；下载完成自动刷新。
 * 长按进入多选 → 顶部出现删除按钮：
 * 应用内下载（音频+歌词）与手动导入的歌曲可移除；系统媒体库文件受系统权限限制不可删，会提示。
 *
 * ★ 2026-10-03 导入入口（用户口径）：最上方「导入本地音乐」→ 内置导入页（扫描设备音乐，
 *   单选/多选导入，见 ImportLocalScreen）；导入条目只并入本页列表（见 ImportStore 的 KDoc），
 *   「我喜欢」等歌单不受影响。从导入页返回时本页整体重组合 → 自动重扫，新导入的立即出现。
 */
@Composable
fun LocalMusicScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var toast by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateMapOf<Long, Song>() }
    val selectMode = selected.isNotEmpty()

    // 下载状态变化（0→1→2）即重扫，让刚下载完的歌立刻出现
    val dlTick by remember {
        derivedStateOf { DownloadStore.states.values.sumOf { it } + DownloadStore.states.size }
    }

    suspend fun rescan() {
        songs = LocalMusic.scan(ctx)
        loading = false
        // ★ 2026-10-06 用户口径「扫描本地音乐后自动查找是否有同名 lrc 读取」：
        //   扫描完成后在**后台**把每首本地歌的歌词查找跑一遍（旁挂 .lrc / 内嵌帧），
        //   结果记进 LocalLyricStore 的 memo —— 之后进歌词页秒出，查找成本不再落在
        //   打开歌词页那一刻。read 自带 memo 检查：查过的歌这里零成本跳过，只有新增 /
        //   未查过的才真正读文件；全程 IO 线程，不与本页任何 UI 状态交互。
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.netmusiclite.data.LocalLyricStore.warmup(ctx, songs)
        }
    }
    LaunchedEffect(Unit) { rescan() }
    LaunchedEffect(dlTick) { if (dlTick > 0) rescan() }
    LaunchedEffect(toast) { if (toast != null) { delay(3500); toast = null } }

    // ★ 2026-10-04「更新后本地音乐失效」修复之一：音频读取权限一旦被系统/ROM 撤销，
    //   媒体库查询会**静默返回空**（queryMediaStore 里的 runCatching 吞掉了异常）——
    //   列表看起来就像「之前导入的歌全没了」。这里显式检测权限，缺失时在列表顶部给出
    //   可点的授权卡片，授权成功后立刻重扫，不必退出重进。
    val audioPerm = remember { MediaPermissions.audio() }
    var permOk by remember { mutableStateOf(MediaPermissions.granted(ctx, audioPerm)) }
    val permScope = rememberCoroutineScope()
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        permOk = ok
        if (ok) permScope.launch { rescan() }
        else toast = "没有音频读取权限，只能看到应用内下载与导入的歌曲"
    }

    // 系统文件管理（SAF）多选导入入口已迁入内置导入页（2026-10-03，见 ImportLocalScreen）；
    // 本页「导入本地音乐」卡片改为导航进入该页。
    fun deleteSelected() {
        val downloaded = selected.values.filter { DownloadStore.songOf(it.id) != null }
        val importedIds = selected.keys.filter { ImportStore.isImported(ctx, it) }
        // 媒体库扫描来的系统文件不属于应用资产：既删不了文件，也无「移出列表」的意义
        val skipped = selected.size - downloaded.size - importedIds.size
        downloaded.forEach { DownloadStore.delete(it.id) }
        ImportStore.remove(ctx, importedIds)
        val removedIds = downloaded.map { it.id }.toSet() + importedIds
        songs = songs.filterNot { it.id in removedIds }
        selected.clear()
        toast = when {
            removedIds.isEmpty() -> "仅支持删除应用内下载或导入的歌曲"
            skipped > 0 -> "已移除 ${removedIds.size} 首；$skipped 首为系统媒体文件不可移除"
            else -> "已移除 ${removedIds.size} 首"
        }
    }

    Box(Modifier.fillMaxSize()) {
        val n = songs.size
        var filter by remember { mutableStateOf("") }
        // 跨语言搜索：本地文件没有网易译名，按需"查询扩展 + 别名反查"（详 rememberAliasAwareSongs）
        val visible = rememberAliasAwareSongs(songs, filter)
        StackedCardList(
            title = "本地音乐",
            // 权限缺失时列表里多一张授权卡（见上方 permLauncher 说明）
            progressTotal = n + if (permOk) 3 else 4,
            cardHeight = 56.dp,
            horizontalInsetPx = 10,
        ) {
            // ★ 2026-10-04 权限缺失时的授权卡片（排在最前）：权限被撤销后媒体库查询静默返回空，
            //   用户看到的就是「歌全没了」——给一个当场就能恢复的入口，而不是让人以为数据丢了。
            if (!permOk) {
                item(key = "perm") {
                    StackedIconCard(
                        title = "需要音频读取权限",
                        subtitle = "点此授权后重新扫描设备音乐",
                        icon = NcmIcons.Refresh,
                        iconTint = Accent,
                        navMotionKind = NavMotionKind.Pill,
                        onClick = { permLauncher.launch(audioPerm) },
                    )
                }
            }
            // ★ 2026-10-03 用户口径「本地音乐最上方增添导入本地音乐选项」：
            //   排在下载管理之上，是列表第一条；点击进入内置导入页（扫描/多选/单选，见 ImportLocalScreen），
            //   权限在点「扫描本地音乐」那一刻申请，不在进页时弹窗。
            item(key = "import") {
                StackedIconCard(
                    title = "导入本地音乐",
                    subtitle = "扫描设备歌曲，选择后仅在本页显示",
                    icon = NcmIcons.Plus,
                    iconTint = Accent,
                    navMotionKind = NavMotionKind.Pill,
                    onClick = { nav.navigateWithMotion(Routes.LOCAL_IMPORT) },
                )
            }
            item(key = "dl_mgr") {
                StackedIconCard(
                    title = "下载管理",
                    subtitle = dlSummaryLine(),
                    icon = NcmIcons.Download,
                    iconTint = Accent,
                    navMotionKind = NavMotionKind.Pill,
                    onClick = { nav.navigateWithMotion(Routes.DOWNLOAD_MGR) },
                )
            }
            item(key = "filter") { ListFilterCard(query = filter, onQuery = { filter = it }) }
            if (loading) {
                item { CenterHint("扫描中…", Modifier.fillMaxWidth().height(240.dp)) }
            } else if (songs.isEmpty()) {
                item { CenterHint("设备上没有本地音乐\n下载的歌曲会出现在这里", Modifier.fillMaxWidth().height(240.dp)) }
            } else {
                itemsIndexed(visible, key = { _, s -> s.id }, contentType = { _, _ -> "song" }) { i, song ->
                    StackedSongCard(
                        song = song,
                        isCurrent = PlayerEngine.current?.id == song.id,
                        aliasHint = SongQuery.matchedAlias(song, filter),
                        selectMode = selectMode,
                        selected = selected.containsKey(song.id),
                        onClick = {
                            if (selectMode) {
                                if (selected.containsKey(song.id)) selected.remove(song.id) else selected[song.id] = song
                            } else {
                                PlayerEngine.playQueue(visible, i)
                                nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Pill))
                            }
                        },
                        onLongClick = {
                            if (selected.containsKey(song.id)) selected.remove(song.id) else selected[song.id] = song
                        },
                    )
                }
            }
        }
        if (selectMode) {
            SelectionDeleteCircles(
                count = selected.size,
                onDelete = { deleteSelected() },
                onCancel = { selected.clear() },
                selectAll = {
                    songs.forEach { selected[it.id] = it }
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        if (toast != null) {
            Box(Modifier.align(Alignment.Center)) {
                Text(
                    toast!!, fontSize = 11.sp, color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xCC2B2B33))
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/** 下载管理卡片副标题：下载中/排队/失败 计数（观察 DownloadStore 可组合态，自动刷新） */
@Composable
private fun dlSummaryLine(): String {
    val dl = DownloadStore.currentDownload != null
    val pd = DownloadStore.pendingQueue.size
    val fl = DownloadStore.failed.size
    if (!dl && pd == 0 && fl == 0) return "没有进行中的任务"
    return listOfNotNull(
        if (dl) "下载中 1" else null,
        if (pd > 0) "排队 $pd" else null,
        if (fl > 0) "失败 $fl" else null,
    ).joinToString(" · ")
}
