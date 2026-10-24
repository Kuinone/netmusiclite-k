package com.netmusiclite.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.netmusiclite.data.DownloadStore
import com.netmusiclite.data.AppearancePrefs
import com.netmusiclite.data.NcmApi
import com.netmusiclite.data.PageCache
import com.netmusiclite.data.PlaylistItem
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.Song
import com.netmusiclite.data.SongQuery
import com.netmusiclite.ui.components.CircleIconButton
import com.netmusiclite.ui.components.CircularSideProgress
import com.netmusiclite.ui.components.CenterHint
import com.netmusiclite.ui.components.ListFilterCard
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.SelectionDownloadCircles
import com.netmusiclite.ui.components.SongRow
import com.netmusiclite.ui.components.rememberAliasAwareSongs
import com.netmusiclite.ui.components.rotaryList
import com.netmusiclite.ui.components.estimateProgress
import com.netmusiclite.ui.components.formatCount
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.m3e.M3eCollectionSongs
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.NavMotionSpec
import com.netmusiclite.ui.nav.navigateWithMotion
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.screenBg
import com.netmusiclite.ui.theme.SurfaceStrong
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 尖锐封面头：专辑/歌单详情 —— 最上方为封面原图，
 * 封面下缘渐变到主题纯色，封面处显示名称，下面是歌曲普通列表。
 */
@Composable
fun SharpCoverHeader(
    coverUrl: String?,
    title: String,
    subtitle: String? = null,
    /** 标题下方的附加内容（收藏胶囊等，2026-10-11 收藏歌单/专辑入口）；null 不占位 */
    footer: (@Composable () -> Unit)? = null,
) {
    val ctx = LocalContext.current
    // 有壁纸、或「封面模糊外观」下（外围页整页底 = 模糊封面）→ 渐隐到透明；
    // 否则「纯色模式」渐变终点必须是不透明页面底色，直接硬切会留边（2026-10-07 用户报黑边同款）。
    val fadeOut = com.netmusiclite.data.BackgroundStore.ready ||
        com.netmusiclite.data.AppearancePrefs.coverBlurOuter
    Box(Modifier.fillMaxWidth().height(176.dp)) {
        // 图像层：启用背景图时用 DstIn 沿高度擦除 alpha = 真渐隐
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (fadeOut) {
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Black,
                                0.45f to Color.Black.copy(alpha = 0.8f),
                                
                                1f to Color.Transparent,
                            ),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                        )
                    }
                }
        ) {
            if (coverUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(coverUrl).size(520).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(Color(0xFF1C1C2A)))
            }
        }
        if (!fadeOut) {
            // ★ 2026-10-03 修「专辑封面与背景没有渐变」：终止色必须是**页面真实底色**。
            //   无背景图时全局底 = 歌曲环境色（NcmBackground 铺的 SongAmbient.color），
            //   旧版渐到主题纯色 Bg，底边与环境色之间留一道可见色界，像封面没有渐变。
            //   （BlurHeader 一直渐到 SongAmbient.color，艺人页因此正常。）
            val fadeEnd = com.netmusiclite.ui.components.SongAmbient.color
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.42f to Color.Black.copy(alpha = 0.30f),
                        0.78f to fadeEnd,
                        1f to fadeEnd,
                    )
                )
            )
        }
        Column(
            Modifier.align(Alignment.BottomCenter).padding(horizontal = 22.dp).padding(bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                textAlign = TextAlign.Center, maxLines = 2)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 9.sp, color = TextSecondary, maxLines = 1, textAlign = TextAlign.Center)
            }
            if (footer != null) footer()
        }
    }
}

/**
 * 详情页头部的收藏胶囊（2026-10-11 收藏歌单/专辑入口）。
 * [subscribed] = null 表示不显示（自己的歌单 / 「我喜欢的音乐」/ 数据未加载完）。
 */
@Composable
private fun SubPill(subscribed: Boolean?, label: String, onClick: () -> Unit) {
    if (subscribed == null) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 5.dp)
            .clip(RoundedCornerShape(50))
            .background(SurfaceStrong)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Icon(
            if (subscribed) NcmIcons.HeartFill else NcmIcons.Heart, null,
            tint = Accent, modifier = Modifier.size(11.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(if (subscribed) "已收藏" else label, fontSize = 9.sp, color = TextPrimary)
    }
}

/** 专辑详情：歌曲长按多选下载 */
@Composable
fun AlbumScreen(nav: NavHostController, albumId: Long) {
    var name by remember { mutableStateOf("") }
    var cover by remember { mutableStateOf<String?>(null) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var subscribed by remember { mutableStateOf(false) }
    var subscribeBusy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    // ★ 2026-10-24 新增「列表内搜索」（跨语言）：输中文译名就能命中库里存日文/英文原名的歌。
    //   匹配走现成的三层管线 [rememberAliasAwareSongs]：① 归一化后比歌名/艺人/专辑/译名
    //   （接口返回的歌本来就带 tns/alia 译名）→ ② 一首都没中就拿关键词去服务端搜一次，
    //   把中文词换成库里存着的原名写法 → ③ 仍没中才逐首反查译名并落盘缓存。
    //   与每日推荐 / 我喜欢的音乐 / 本地音乐 / m3e 集合页同一口径（那几处早就接上了）。
    var filter by remember { mutableStateOf("") }
    val visible = rememberAliasAwareSongs(songs, filter)
    val selected = remember { mutableStateMapOf<Long, Song>() }
    val selectMode = selected.isNotEmpty()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(albumId) {
        val r = PageCache.albumDetail(albumId)
        if (r == null) failed = true
        else {
            name = r.name; cover = r.coverUrl; songs = r.songs
            // ★ 2026-10-23 修「有的专辑收藏后进专辑页仍显示未收藏」（需求13）：
            //   ① 本地写穿优先：刚在本机收藏/取消成功的专辑，用会话内真实结果覆盖详情接口值
            //      （/v1/album 的 subscribed 带服务端/CDN 缓存，收藏后立刻重进可能仍是旧值 false）；
            //   ② 服务端仍说「未收藏」时，再用「收藏的专辑」列表精确交叉校验一次
            //      （列表接口无详情缓存且已分页拉全；返回 null = 判断不了，保留原值，绝不误刷）。
            subscribed = NcmApi.albumSubOverride(albumId) ?: r.subscribed
            if (!subscribed) {
                val exact = NcmApi.isAlbumSubscribed(albumId)
                // ★ 异步校验期间用户可能刚好点了收藏/取消：已有写穿态时以写穿态为准，
                //   否则旧读回会把刚写的状态覆盖回去（竞态）。
                if (exact != null && NcmApi.albumSubOverride(albumId) == null) subscribed = exact
            }
        }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(3500); toast = null } }

    fun toggleSubscribe() {
        // ★ 2026-10-17 风控加固：在途时忽略重复点击，避免并发 sub/unsub 交错写入
        //   （服务端按「高频收藏/取消」判异常，且网络乱序会与 UI 状态相反）。
        if (subscribeBusy) return
        subscribeBusy = true
        scope.launch {
            try {
                val target = !subscribed
                toast = if (target) "收藏中…" else "取消中…"
                val okv = NcmApi.subAlbum(albumId, target)
                if (okv) {
                    subscribed = target
                    // ★ 2026-10-23：写穿 + 清缓存，保证重新进入/再校验读到新状态，而不是服务端缓存旧值
                    NcmApi.noteAlbumSub(albumId, target)
                    PageCache.invalidateAlbumSublist()
                    PageCache.invalidateAlbumDetail(albumId)
                }
                toast = when {
                    okv && target -> "已收藏专辑，到「收藏的专辑」查看"
                    okv -> "已取消收藏"
                    target -> "收藏失败"
                    else -> "取消失败"
                }
            } finally {
                subscribeBusy = false
            }
        }
    }

    fun downloadSelected() {
        val list = selected.values.filter { it.id > 0 && DownloadStore.states[it.id] != 2 }
        if (list.isEmpty()) { toast = "所选歌曲均已下载"; selected.clear(); return }
        toast = "开始下载 ${list.size} 首…"
        DownloadStore.enqueue(list) { okN, failN, reasons ->
            toast = when {
                failN == 0 -> "已下载 $okN 首，到本地音乐查看"
                okN == 0 -> "下载失败：$reasons"
                else -> "成功 $okN 首；失败 $failN 首（$reasons）"
            }
            selected.clear()
        }
    }

    if (AppearancePrefs.isM3e) {
        M3eCollectionSongs(
            title = name.ifEmpty { "专辑" }, cover = cover, songs = songs, state = listState,
            loading = name.isEmpty() && !failed,
            emptyMessage = if (failed) "专辑加载失败" else "暂无歌曲",
            status = toast, subscribed = if (name.isEmpty()) null else subscribed,
            onSubscribe = { toggleSubscribe() }, selected = selected.keys.toSet(),
            onToggleSelection = { song -> if (selected.containsKey(song.id)) selected.remove(song.id) else selected[song.id] = song },
            onClearSelection = { selected.clear() }, onDownload = { downloadSelected() },
            // ★ 2026-10-23 补多选「全选」（需求1）：与「我喜欢」页同一交互
            onSelectAll = { songs.forEach { selected[it.id] = it } },
            onPlay = { queue, index ->
                PlayerEngine.playQueue(queue, index)
                nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Pill))
            },
        )
        return
    }

    // ★ 2026-10-23 修复项 10（Lead 授权补收尾）：经典主题下的列表位置记忆 / 延迟恢复。
    //   本页 songs 是 remember + LaunchedEffect 异步重载的：返回时 rememberLazyListState 恢复出来的
    //   位置会被**首帧条目数**（此刻只有头图一个 item）clamp 到 0，数据到位后也不回去
    //   ⇒ 点歌进播放页再回来会直接回到专辑顶部。
    //   m3e 分支（上面的 M3eCollectionSongs → M3eList → NativeList）已在 M3eShared.kt 的
    //   M3eListPositionKeeper 里统一处理，这里只补经典分支；页面其它逻辑一行不动。
    com.netmusiclite.ui.components.ListViewportRestorer(listState)

    Box(Modifier.fillMaxSize().background(screenBg()).rotaryList(listState)) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 90.dp)) {
            item(key = "header") {
                SharpCoverHeader(cover, name.ifEmpty { "专辑" }, detailCountLabel(songs.size, visible.size, filter),
                    footer = if (name.isEmpty()) null else ({ SubPill(subscribed, "收藏专辑") { toggleSubscribe() } }),
                )
            }
            item(key = "filter") {
                ListFilterCard(query = filter, onQuery = { filter = it }, modifier = Modifier.padding(horizontal = 10.dp))
            }
            if (filter.isNotEmpty() && visible.isEmpty()) {
                item(key = "no_match") {
                    CenterHint(
                        "没有匹配「${filter.take(8)}」的歌\n外文歌可直接输中文译名（如「向夜晚奔去」）",
                        Modifier.fillMaxWidth().height(160.dp),
                    )
                }
            }
            itemsIndexed(visible, key = { _, s -> s.id }, contentType = { _, _ -> "song" }) { i, song ->
                SongRow(
                    song = song,
                    isCurrent = PlayerEngine.current?.id == song.id,
                    aliasHint = SongQuery.matchedAlias(song, filter),
                    selected = selected.containsKey(song.id),
                    selectMode = selectMode,
                    onClick = {
                        if (selectMode) {
                            if (selected.containsKey(song.id)) selected.remove(song.id) else selected[song.id] = song
                        } else {
                        // 筛选态下播放**筛出来的这一段**（与每日推荐/我喜欢的页一致：visible, i）
                        PlayerEngine.playQueue(visible, i)
                        nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Pill)) // 点歌即播并进入播放页
                    }
                    },
                    onLongClick = {
                        if (selected.containsKey(song.id)) selected.remove(song.id) else selected[song.id] = song
                    },
                )
            }
        }
        if (selectMode) {
            SelectionDownloadCircles(
                count = selected.size,
                onDownload = { downloadSelected() },
                onCancel = { selected.clear() },
                // ★ 2026-10-23 补多选「全选」（需求1）：与「我喜欢」页同一交互与图标（NcmIcons.CheckAll 双勾）
                selectAll = { visible.forEach { selected[it.id] = it } },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        // 返回 = 全局左缘滑动
        val pageProgress by remember(songs.size, visible.size) {
            derivedStateOf { estimateProgress(listState, (visible.size + 2).coerceAtLeast(2)).coerceIn(0.15f, 1f) }
        }
        CircularSideProgress(progress = { pageProgress }, modifier = Modifier.fillMaxSize())
        if (failed) {
            Box(Modifier.align(Alignment.Center)) {
                Text("专辑加载失败", fontSize = 12.sp, color = TextSecondary)
            }
        }
        if (toast != null) {
            Box(Modifier.align(Alignment.Center)) {
                Text(toast!!, fontSize = 11.sp, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC2B2B33))
                        .padding(horizontal = 16.dp, vertical = 7.dp))
            }
        }
    }
}

/**
 * 列表内搜索时的表头计数：没在筛选就只显示总数；筛选中显示「匹配 m / n 首」，
 * 让用户看得见「是筛没了，还是歌单本来就这几首」。
 */
private fun detailCountLabel(total: Int, shown: Int, filter: String): String =
    if (filter.isEmpty()) "$total 首" else "匹配 $shown / $total 首"

/** 歌单详情：歌曲长按多选下载 */
@Composable
fun PlaylistDetailScreen(nav: NavHostController, playlistId: Long) {
    var info by remember { mutableStateOf<PlaylistItem?>(null) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var subscribeBusy by remember { mutableStateOf(false) }
    // ★ 2026-10-24 列表内搜索（跨语言），与专辑页同口径，实现说明见 AlbumScreen
    var filter by remember { mutableStateOf("") }
    val visible = rememberAliasAwareSongs(songs, filter)
    val selected = remember { mutableStateMapOf<Long, Song>() }
    val selectMode = selected.isNotEmpty()
    // 列表进度恢复（免闪烁，2026-09-25）：进播放页前存的视口进度直接作为列表初始位置，
    // 歌曲未加载完前不渲染列表，首次布局即落在原进度，无顶部→跳转的闪现
    val savedHandle = nav.currentBackStackEntry?.savedStateHandle
    val savedIdx = remember { savedHandle?.get<Int>("list_restore_index") }
    val restoring = savedIdx != null
    // ★ 必须 remember：LazyListState(...) 是类构造器（优先于同名 Composable 工厂），
    //   裸调用会每次重组新建实例——多选歌曲/toast/切歌等任何重组都会让列表弹回顶部
    val listState = remember {
        androidx.compose.foundation.lazy.LazyListState(
            savedIdx ?: 0,
            savedHandle?.get<Int>("list_restore_offset") ?: 0,
        )
    }
    val scope = rememberCoroutineScope()

    LaunchedEffect(playlistId) {
        val r = PageCache.playlistDetail(playlistId)
        if (r == null) failed = true
        else {
            // ★ 2026-10-23（需求2）：收藏/取消成功后，详情接口 v6/playlist/detail 的 subscribed
            //   可能仍回服务端缓存旧值；本会话写穿的收藏态优先，避免「已收藏」被刷回「未收藏」。
            info = (NcmApi.playlistSubOverride(playlistId)?.let { r.first.copy(subscribed = it) }) ?: r.first
            songs = r.second
        }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(3500); toast = null } }

    fun downloadSelected() {
        val list = selected.values.filter { it.id > 0 && DownloadStore.states[it.id] != 2 }
        if (list.isEmpty()) { toast = "所选歌曲均已下载"; selected.clear(); return }
        toast = "开始下载 ${list.size} 首…"
        DownloadStore.enqueue(list) { okN, failN, reasons ->
            toast = when {
                failN == 0 -> "已下载 $okN 首，到本地音乐查看"
                okN == 0 -> "下载失败：$reasons"
                else -> "成功 $okN 首；失败 $failN 首（$reasons）"
            }
            selected.clear()
        }
    }

    /** 移出歌单（op=del，仅自己创建的歌单；「我喜欢的音乐」红心走播放页，不在这里删） */
    fun removeSelected() {
        val pid = info?.id ?: return
        val ids = selected.keys.filter { it > 0 }
        if (ids.isEmpty()) { selected.clear(); return }
        scope.launch {
            toast = "移除中…"
            // ★ 2026-10-18：removeFromPlaylist 改返回 PlaylistEditOutcome ——
            //   「歌本来就不在歌单里」服务端同样回 502/521，按幂等语义算已达成（ALREADY），
            //   不应报「移除失败」；只有 FAILED 才是真失败。
            val outcome = NcmApi.removeFromPlaylist(pid, ids)
            if (outcome != NcmApi.PlaylistEditOutcome.FAILED) {
                songs = songs.filterNot { it.id in ids }
                PageCache.invalidatePlaylistDetail(pid)
                toast = "已移出歌单 ${ids.size} 首"
            } else toast = "移除失败（只能移除自己创建的歌单）"
            selected.clear()
        }
    }

    /** 收藏/取消收藏歌单；自己的歌单与「我喜欢的音乐」不能收藏（入口都不显示） */
    fun toggleSubscribe() {
        val cur = info ?: return
        // ★ 2026-10-17 风控加固：在途时忽略重复点击，避免并发订阅/退订交错写入
        if (subscribeBusy) return
        subscribeBusy = true
        scope.launch {
            try {
                val target = !cur.subscribed
                toast = if (target) "收藏中…" else "取消中…"
                val okv = NcmApi.subPlaylist(cur.id, target)
                if (okv) {
                    info = cur.copy(subscribed = target)
                    // ★ 2026-10-23：写穿 + 清缓存，防详情接口缓存旧值把刚收藏的歌单刷回未收藏
                    NcmApi.notePlaylistSub(cur.id, target)
                    PageCache.invalidatePlaylists()
                    PageCache.invalidatePlaylistDetail(cur.id)
                }
                toast = when {
                    okv && target -> "已收藏歌单"
                    okv -> "已取消收藏"
                    target -> "收藏失败"
                    else -> "取消失败"
                }
            } finally {
                subscribeBusy = false
            }
        }
    }

    if (AppearancePrefs.isM3e) {
        val playlist = info
        M3eCollectionSongs(
            title = playlist?.name ?: "歌单", cover = playlist?.coverUrl, songs = songs, state = listState,
            loading = playlist == null && !failed,
            emptyMessage = if (failed) "歌单加载失败" else "暂无歌曲",
            status = toast,
            subscribed = if (playlist == null || playlist.isMine || playlist.specialType == 5) null else playlist.subscribed,
            onSubscribe = { toggleSubscribe() }, selected = selected.keys.toSet(),
            onToggleSelection = { song -> if (selected.containsKey(song.id)) selected.remove(song.id) else selected[song.id] = song },
            onClearSelection = { selected.clear() }, onDownload = { downloadSelected() },
            // ★ 2026-10-23 补多选「全选」（需求1）：与「我喜欢」页同一交互
            onSelectAll = { songs.forEach { selected[it.id] = it } },
            onRemove = if (playlist?.isMine == true && playlist.specialType != 5) ({ removeSelected() }) else null,
            onPlay = { queue, index ->
                PlayerEngine.playQueue(queue, index)
                nav.currentBackStackEntry?.savedStateHandle?.apply {
                    set("list_restore_index", com.netmusiclite.ui.m3e.listFirstIndex(listState))
                    set("list_restore_offset", com.netmusiclite.ui.m3e.listFirstOffset(listState))
                }
                nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Pill))
            },
        )
        return
    }

    Box(Modifier.fillMaxSize().background(screenBg()).rotaryList(listState)) {
        // 恢复进度等待期不渲染列表：避免先按顶部布局再跳转的闪现
        if (!restoring || songs.isNotEmpty()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 90.dp)) {
            item(key = "header") {
                // 局部 val：委托属性不能 smart-cast，isMine/subscribed 的判空只能借不可变局部量
                val pl = info
                SharpCoverHeader(
                    pl?.coverUrl, pl?.name ?: "歌单",
                    detailCountLabel(songs.size, visible.size, filter) + " · 播放 ${formatCount(pl?.playCount ?: 0)}",
                    // 自己创建的歌单（含「我喜欢的音乐」specialType==5）不能被收藏 → 不显示入口
                    footer = when {
                        pl == null || pl.isMine || pl.specialType == 5 -> null
                        else -> ({ SubPill(pl.subscribed, "收藏歌单") { toggleSubscribe() } })
                    },
                )
            }
            item(key = "filter") {
                ListFilterCard(query = filter, onQuery = { filter = it }, modifier = Modifier.padding(horizontal = 10.dp))
            }
            if (filter.isNotEmpty() && visible.isEmpty()) {
                item(key = "no_match") {
                    CenterHint(
                        "没有匹配「${filter.take(8)}」的歌\n外文歌可直接输中文译名（如「向夜晚奔去」）",
                        Modifier.fillMaxWidth().height(160.dp),
                    )
                }
            }
            itemsIndexed(visible, key = { _, s -> s.id }, contentType = { _, _ -> "song" }) { i, song ->
                SongRow(
                    song = song,
                    isCurrent = PlayerEngine.current?.id == song.id,
                    aliasHint = SongQuery.matchedAlias(song, filter),
                    cardEdge = true,
                    selected = selected.containsKey(song.id),
                    selectMode = selectMode,
                    onClick = {
                        if (selectMode) {
                            if (selected.containsKey(song.id)) selected.remove(song.id) else selected[song.id] = song
                        } else {
                        // 筛选态下播放筛出来的这一段（与每日推荐/我喜欢的页一致）
                        PlayerEngine.playQueue(visible, i)
                        // 进播放页前保存列表视口进度，返回时复用（savedStateHandle）
                        nav.currentBackStackEntry?.savedStateHandle?.apply {
                            set("list_restore_index", listState.firstVisibleItemIndex)
                            set("list_restore_offset", listState.firstVisibleItemScrollOffset)
                        }
                        nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Pill)) // 点歌即播并进入播放页
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
            SelectionDownloadCircles(
                count = selected.size,
                onDownload = { downloadSelected() },
                onCancel = { selected.clear() },
                // ★ 2026-10-23 补多选「全选」（需求1）：与「我喜欢」页同一交互与图标（NcmIcons.CheckAll 双勾）
                selectAll = { visible.forEach { selected[it.id] = it } },
                // 移出歌单只对自己创建的歌单开放（「我喜欢的音乐」走播放页红心，不在这删）
                onRemove = if (info?.isMine == true && info?.specialType != 5) ({ removeSelected() }) else null,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        // 返回 = 全局左缘滑动
        val pageProgress by remember(songs.size, visible.size) {
            derivedStateOf { estimateProgress(listState, (visible.size + 2).coerceAtLeast(2)).coerceIn(0.15f, 1f) }
        }
        CircularSideProgress(progress = { pageProgress }, modifier = Modifier.fillMaxSize())
        if (failed) {
            Box(Modifier.align(Alignment.Center)) {
                Text("歌单加载失败", fontSize = 12.sp, color = TextSecondary)
            }
        }
        if (toast != null) {
            Box(Modifier.align(Alignment.Center)) {
                Text(toast!!, fontSize = 11.sp, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC2B2B33))
                        .padding(horizontal = 16.dp, vertical = 7.dp))
            }
        }
    }
}

/** 左上返回钮已废弃：统一用全局左缘滑动返回 */
