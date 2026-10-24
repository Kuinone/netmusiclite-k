package com.netmusiclite.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import kotlin.math.roundToInt
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.netmusiclite.data.AlbumItem
import com.netmusiclite.data.AppearancePrefs
import com.netmusiclite.data.DownloadStore
import com.netmusiclite.data.NcmApi
import com.netmusiclite.data.PageCache
import com.netmusiclite.data.PlaylistItem
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.SessionStore
import com.netmusiclite.data.Song
import com.netmusiclite.data.SongQuery
import com.netmusiclite.ui.components.SongAmbient
import com.netmusiclite.ui.components.songCoverModel
import com.netmusiclite.ui.components.pressStretch
import com.netmusiclite.ui.components.CenterHint
import com.netmusiclite.ui.components.CoverImage
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.SelectionDownloadCircles
import com.netmusiclite.ui.components.StackedCard
import com.netmusiclite.ui.components.StackedCardList
import com.netmusiclite.ui.components.StackedSongCard
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.m3e.M3eCollectionSongs
import com.netmusiclite.ui.m3e.M3eCollectionCard
import com.netmusiclite.ui.m3e.M3eList
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.NavMotionSpec
import com.netmusiclite.ui.nav.navigateWithMotion
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.AccentSoft
import com.netmusiclite.ui.theme.OnAccent
import com.netmusiclite.ui.theme.SurfaceGlass
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import com.netmusiclite.ui.theme.isLightTheme
import com.netmusiclite.ui.components.ListFilterCard
import com.netmusiclite.ui.components.rememberAliasAwareSongs
import kotlinx.coroutines.delay

/** 每日推荐：真实接口，点歌即播（歌曲行卡片堆叠）；长按进入多选下载 */
@Composable
fun DailyRecommendScreen(nav: NavHostController) {
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateMapOf<Long, Song>() }
    val selectMode = selected.isNotEmpty()

    LaunchedEffect(Unit) {
        songs = PageCache.dailyRecommend()
        if (songs.isEmpty()) failed = true
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

    if (AppearancePrefs.isM3e) {
        M3eCollectionSongs(
            title = "每日推荐", cover = songCoverModel(songs.firstOrNull()), songs = songs,
            fallback = NcmIcons.DailyRec, loading = songs.isEmpty() && !failed,
            emptyMessage = if (failed) "今日推荐获取失败，请检查登录态与网络" else "暂无推荐歌曲",
            status = toast, selected = selected.keys.toSet(),
            onToggleSelection = { song -> if (selected.containsKey(song.id)) selected.remove(song.id) else selected[song.id] = song },
            onClearSelection = { selected.clear() }, onDownload = { downloadSelected() },
            onPlay = { queue, index ->
                PlayerEngine.playQueue(queue, index)
                nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Pill))
            },
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        val n = songs.size
        var filter by remember { mutableStateOf("") }
        // 跨语言搜索：每日推荐走接口，歌自带译名；缺的按需"查询扩展 + 别名反查"
        val visible = rememberAliasAwareSongs(songs, filter)
        StackedCardList(title = "每日推荐", progressTotal = n + 2, cardHeight = 56.dp, horizontalInsetPx = 10) {
            item(key = "filter") { ListFilterCard(query = filter, onQuery = { filter = it }) }
            if (failed && songs.isEmpty()) {
                item { CenterHint("今日推荐获取失败\n请检查登录态与网络", Modifier.fillMaxWidth().height(240.dp)) }
            } else if (songs.isEmpty()) {
                item { CenterHint("加载中…", Modifier.fillMaxWidth().height(240.dp)) }
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
                modifier = Modifier.align(Alignment.TopCenter),
            )
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

/** 我喜欢：顶部 = 最新收藏封面渐变头图（列表首项，进入页面从列表最顶部开始），歌曲按收藏时间排列（最新在前）；长按多选下载 */
@Composable
fun LikedSongsScreen(nav: NavHostController) {
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var toast by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateMapOf<Long, Song>() }
    val selectMode = selected.isNotEmpty()
    // 列表进度恢复（免闪烁，2026-09-25）：进播放页前存的视口进度直接作为列表初始位置，
    // 歌曲未加载完前不渲染列表，首次布局即落在原进度，无顶部→跳转的闪现
    val savedHandle = nav.currentBackStackEntry?.savedStateHandle
    val savedIdx = remember { savedHandle?.get<Int>("list_restore_index") }
    val restoring = savedIdx != null
    // ★ 必须 remember：LazyListState(...) 是类构造器（优先于同名 Composable 工厂），
    //   裸调用会每次重组新建实例——选中歌曲/toast/切歌等任何重组都会让列表弹回顶部
    val listState = remember {
        androidx.compose.foundation.lazy.LazyListState(
            savedIdx ?: 0,
            savedHandle?.get<Int>("list_restore_offset") ?: 0,
        )
    }

    LaunchedEffect(Unit) {
        songs = PageCache.likedSongs(SessionStore.uid)
        loading = false
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

    fun addSelectedToNext() {
        // 顺序按列表顺序取（不是点选顺序），符合「列表怎么排就怎么播」的直觉
        val picked = songs.filter { selected.containsKey(it.id) }
        if (picked.isEmpty()) return
        PlayerEngine.playNextInQueue(picked)
        toast = "已添加 ${picked.size} 首到下一首播放"
        selected.clear()
    }

    if (AppearancePrefs.isM3e) {
        M3eCollectionSongs(
            title = "我喜欢的音乐", cover = songCoverModel(songs.firstOrNull()), songs = songs,
            fallback = NcmIcons.HeartFill, loading = loading, state = listState,
            emptyMessage = "还没有红心歌曲，播放时收藏即可加入", status = toast,
            selected = selected.keys.toSet(),
            onToggleSelection = { song -> if (selected.containsKey(song.id)) selected.remove(song.id) else selected[song.id] = song },
            onClearSelection = { selected.clear() }, onDownload = { downloadSelected() },
            onSelectAll = { songs.forEach { selected[it.id] = it } }, onAddNext = { addSelectedToNext() },
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

    Box(Modifier.fillMaxSize()) {
        val n = songs.size
        var filter by remember { mutableStateOf("") }
        // 跨语言搜索：我喜欢的音乐歌自带译名（/v3/song/detail 的 tns/alia），缺的按需补齐
        val visible = rememberAliasAwareSongs(songs, filter)
        // 恢复进度等待期不渲染列表：避免先按顶部布局再跳转的闪现
        if (!restoring || songs.isNotEmpty()) {
        StackedCardList(state = listState, title = "我喜欢的音乐", progressTotal = n + 2, cardHeight = 56.dp, horizontalInsetPx = 10) {
            if (loading) {
                item { CenterHint("加载中…", Modifier.fillMaxWidth().height(240.dp)) }
            } else if (songs.isEmpty()) {
                item { CenterHint("还没有红心歌曲\n播放时点亮 ♥ 即可收藏", Modifier.fillMaxWidth().height(240.dp)) }
            } else {
                item(key = "liked_cover_header") { LikedCoverHeader(songs.first(), songs.size) }
                item(key = "filter") { ListFilterCard(query = filter, onQuery = { filter = it }) }
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
        }
        if (selectMode) {
            SelectionDownloadCircles(
                count = selected.size,
                onDownload = { downloadSelected() },
                onCancel = { selected.clear() },
                selectAll = { songs.forEach { selected[it.id] = it } }, // 全选
                onAddNext = { addSelectedToNext() },                    // ＋：添加到下一首播放
                modifier = Modifier.align(Alignment.TopCenter),
            )
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

/** 封面头图：最新收藏歌曲封面铺满整卡（小尺寸解码，防大图卡顿），向下渐变到背景色，融入下方歌曲列表 */
@Composable
private fun LikedCoverHeader(song: Song, count: Int) {
    val ctx = LocalContext.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(118.dp)
            .clip(RoundedCornerShape(30.dp)),
    ) {
        val fadeOut = com.netmusiclite.data.BackgroundStore.ready ||
            com.netmusiclite.data.AppearancePrefs.coverBlurOuter // 封面模糊外观：底 = 模糊封面，须渐隐（同 BlurHeader）
        // 图像层：启用背景图时用 DstIn 沿高度擦除 alpha = 真渐隐
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (fadeOut) {
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Black,
                                0.45f to Color.Black.copy(alpha = 0.7f),
                                
                                1f to Color.Transparent,
                            ),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                        )
                    }
                }
        ) {
            // 头图与封面同源：已下载歌曲用落盘封面文件（断网可显示），否则网络 URL
            val model = songCoverModel(song)
            if (model != null) {
                AsyncImage(
                    model = if (model is String) ImageRequest.Builder(ctx).data(model).size(466).build() else model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        if (!fadeOut) {
            // 纯色模式：顶部轻压暗 → 底部融入页面底色。
            // ★ 2026-10-03 同类修复（对齐 SharpCoverHeader/BlurHeader）：无背景图时页面
            //   全局底 = 歌曲环境色，终止色用主题纯色 Bg 会留一道色界，必须用 SongAmbient。
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0x400A0A0C),
                            0.45f to Color(0x0F0A0A0C),
                            1f to SongAmbient.color,
                        )
                    )
            )
        }
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(NcmIcons.HeartFill, contentDescription = "最新收藏", tint = Accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("$count 首", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

/**
 * 我的歌单库（2026-09-30 新增分栏；2026-10-23 加「收藏的歌单」第三栏）：
 * 顶部胶囊「创建的歌单 / 收藏的专辑 / 收藏的歌单」点击切换 ——
 * 歌单卡与专辑卡同一套堆叠玻璃样式（封面 + 名称 + 曲目数），点击分别进歌单/专辑详情。
 * 收藏专辑列表走 PageCache 会话级缓存。
 *
 * ★ 2026-10-23（需求2 闭环）：补「收藏的歌单」栏。此前歌单详情页已能收藏，
 *   但库页只显示自己创建的（isMine）歌单，收藏的别人歌单一处都看不到 —— 用户以为「收藏没生效」。
 *   数据走 `/playlist/sublist`（只含别人歌单，不含自己创建的），每次进入页面重拉：
 *   remember 随页面重建丢失 ⇒ 从歌单详情收藏/取关后返回，本栏自动是最新的，无需额外失效回调。
 */
@Composable
fun MyPlaylistsScreen(nav: NavHostController) {
    // ★ 2026-10-07 与艺人页同款（用户口径「点专辑进详情后返回应回到专辑页」）：
    //   tab 改 rememberSaveable —— 从专辑详情返回时「收藏的专辑」栏原地还原，
    //   专辑列表由 PageCache 恢复，无重拉、无闪「暂无」。
    var tab by rememberSaveable { mutableStateOf(0) }   // 0 = 创建的歌单；1 = 收藏的专辑；2 = 收藏的歌单
    var playlists by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
    var albums by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var albumsLoaded by remember { mutableStateOf(false) }
    var subPlaylists by remember { mutableStateOf<List<PlaylistItem>>(emptyList()) }
    var subPlaylistsLoaded by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val all = PageCache.userPlaylists(SessionStore.uid)
        playlists = all.filter { it.isMine }
        if (playlists.isEmpty()) playlists = all // 兜底：接口 creator 字段缺失时全量展示
        loading = false
    }
    // 收藏专辑 / 收藏歌单按需加载：切到该栏且没拉过才发请求（首屏/歌单一栏零额外开销）
    LaunchedEffect(tab) {
        if (tab == 1 && !albumsLoaded) {
            albums = PageCache.albumSublist(SessionStore.uid)
            albumsLoaded = true
        }
        if (tab == 2 && !subPlaylistsLoaded) {
            subPlaylists = NcmApi.playlistSublist()
            subPlaylistsLoaded = true
        }
    }

    val total = when (tab) {
        0 -> playlists.size
        1 -> albums.size
        else -> subPlaylists.size
    }
    // ★ 2026-10-17 用户口径「我的歌单界面收藏的歌单与下方歌单卡片间距加到 25 像素」：
    //   列表自身垂直间距 5dp / 8dp，这里补足到 25 物理像素（按 density 折算，密度无关）。
    val m3eGapToCards = with(LocalDensity.current) {
        (25f - 5.dp.toPx()).coerceAtLeast(0f).toDp()
    }
    val stackGapToCards = with(LocalDensity.current) {
        (25f - 8.dp.toPx()).coerceAtLeast(0f).toDp()
    }
    if (AppearancePrefs.isM3e) {
        // ★ 2026-10-23 用户报「m3e 这三个按钮上面的字显示不全」：
        //   m3e 列表是 Wear 的 TransformingLazyColumn —— 默认会把每个 item 的**布局高度压缩**，
        //   再用 graphicsLayer 放大画到视觉高度。tab 行本身又被 playlistSwitchScale 二次缩小，
        //   两层压缩叠加后 Button 的布局高度不够放下一行 labelSmall，Button 的圆角 shape 会把
        //   文字上下裁掉（经典主题没有形变，所以只有 m3e 出问题）。
        //   修法：把这个 item 的 key 放进 safeKeys ⇒ tab 行不参与形变、占真实高度；
        //   同时给文字显式字号/行高并允许溢出绘制，双保险。
        M3eList(safeKeys = setOf("switch")) {
            item(key = "switch") {
                Box(Modifier.fillMaxWidth().padding(bottom = m3eGapToCards), contentAlignment = Alignment.Center) {
                androidx.wear.compose.material3.ButtonGroup(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    modifier = Modifier.playlistSwitchScale(.75f).fillMaxWidth(),
                ) {
                    listOf(0 to "创建的歌单", 1 to "收藏的专辑", 2 to "收藏的歌单").forEach { (index, label) ->
                        val interaction = remember { MutableInteractionSource() }
                        androidx.wear.compose.material3.Button(
                            onClick = { tab = index }, interactionSource = interaction,
                            modifier = Modifier.weight(1f).animateWidth(interaction),
                            // 4dp → 2dp：三栏均分时把空间让给文字（圆屏宽度本来就紧）
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 3.dp),
                            colors = androidx.wear.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = if (tab == index) androidx.wear.compose.material3.MaterialTheme.colorScheme.primaryContainer
                                else androidx.wear.compose.material3.MaterialTheme.colorScheme.surfaceContainer,
                                contentColor = if (tab == index) androidx.wear.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer
                                else androidx.wear.compose.material3.MaterialTheme.colorScheme.onSurface,
                            ),
                        ) {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                androidx.wear.compose.material3.Text(
                                    label,
                                    // ★ 不用 labelSmall（字号随版本漂移）：显式 10sp + 13sp 行高，
                                    //   保证在 Button 的最小高度里垂直完整；overflow 允许溢出而不是裁切。
                                    style = androidx.wear.compose.material3.MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp, lineHeight = 13.sp,
                                    ),
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Visible,
                                )
                            }
                        }
                    }
                }
                }
            }
            if ((tab == 0 && loading) || (tab == 1 && !albumsLoaded) || (tab == 2 && !subPlaylistsLoaded)) {
                item { androidx.wear.compose.material3.Text("加载中…") }
            } else if (total == 0) {
                item {
                    androidx.wear.compose.material3.Text(
                        when (tab) {
                            0 -> "还没有创建歌单"
                            1 -> "还没有收藏专辑"
                            else -> "还没有收藏的歌单"
                        },
                    )
                }
            } else if (tab == 0) {
                itemsIndexed(playlists, key = { _, playlist -> playlist.id }, contentType = { _, _ -> "playlist" }) { _, playlist ->
                    M3eCollectionCard(playlist.name, "${playlist.trackCount} 首歌曲", playlist.coverUrl, compact = true) {
                        nav.navigateWithMotion(Routes.playlistDetail(playlist.id), NavMotionSpec(NavMotionKind.Pill))
                    }
                }
            } else if (tab == 1) {
                itemsIndexed(albums, key = { _, album -> "album_${album.id}" }, contentType = { _, _ -> "album" }) { _, album ->
                    M3eCollectionCard(album.name,
                        listOf(album.artist, if (album.songCount > 0) "${album.songCount} 首歌曲" else "").filter { it.isNotBlank() }.joinToString(" · "),
                        album.coverUrl, compact = true,
                    ) {
                        nav.navigateWithMotion(Routes.albumDetail(album.id), NavMotionSpec(NavMotionKind.Pill))
                    }
                }
            } else {
                // 收藏的歌单（2026-10-23）：点击进歌单详情，详情页可取消收藏
                itemsIndexed(subPlaylists, key = { _, playlist -> "sub_${playlist.id}" }, contentType = { _, _ -> "playlist" }) { _, playlist ->
                    M3eCollectionCard(playlist.name, "${playlist.trackCount} 首歌曲", playlist.coverUrl, compact = true) {
                        nav.navigateWithMotion(Routes.playlistDetail(playlist.id), NavMotionSpec(NavMotionKind.Pill))
                    }
                }
            }
        }
        return
    }
    StackedCardList(
        title = when (tab) {
            0 -> "我创建的歌单"
            1 -> "收藏的专辑"
            else -> "我收藏的歌单"
        },
        progressTotal = total + 2,
        // 2026-10-01：补上 horizontalInsetPx = 10 —— 此前缺省为 0，卡片比主页宽 20px，
        // 两页并排看宽度对不齐（主页/我喜欢/每日推荐等均为 10）。
        horizontalInsetPx = 10,
    ) {
        item(key = "switch") {
            Box(Modifier.fillMaxWidth().padding(bottom = stackGapToCards), contentAlignment = Alignment.Center) {
            Row(
                // ★ 2026-10-23 用户口径「经典主题这三个按钮大小增大 30%」：
                //   这个开关整体由 playlistSwitchScale 统一缩放（默认 0.6 = 原始尺寸的 60%，
                //   当初是为了把三栏塞进圆屏才压这么小）。这里 0.6 → 0.78 = 正好放大 30%，
                //   字号/内距/高度一起等比变大；三栏总宽约 126dp，仍在圆屏可用宽度内。
                Modifier.playlistSwitchScale(.78f).fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                // ★ 2026-10-23 三栏后宽度预算：三枚「创建的歌单/收藏的专辑/收藏的歌单」若沿用
                //   11sp + 13dp 内距，原始测量宽约 3×81dp=243dp > 圆屏可用宽（约 200dp），会被裁掉；
                //   故字号降到 10sp、内距 5dp（3×约 64dp=192dp），整体再经 playlistSwitchScale 缩小。
                listOf(0 to "创建的歌单", 1 to "收藏的专辑", 2 to "收藏的歌单").forEach { (t, label) ->
                    val selected = tab == t
                    // 浅色主题统一「选中实底 + 反色文字 / 未选中纯白胶囊」（2026-10-02 排行页同款）：
                    // 修复未选中项因半透明白玻璃反而比选中项更亮的层级倒挂。深色主题维持原样。
                    val light = isLightTheme
                    Text(
                        label,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        fontSize = 10.sp,
                        color = if (selected) {
                            if (light) OnAccent else Accent
                        } else TextPrimary,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .pressStretch()
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (selected) {
                                    if (light) Accent else AccentSoft
                                } else {
                                    if (light) Color.White else SurfaceGlass
                                }
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { tab = t }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }
            }
        }
        if (tab == 0) {
            if (loading) {
                item { CenterHint("加载中…", Modifier.fillMaxWidth().height(240.dp)) }
            } else if (playlists.isEmpty()) {
                item { CenterHint("还没有创建歌单\n播放页 · 更多 · 收藏 可新建", Modifier.fillMaxWidth().height(240.dp)) }
            } else {
                itemsIndexed(playlists, key = { _, p -> p.id }, contentType = { _, _ -> "playlist" }) { _, pl ->
                    StackedCard(
                        onClick = { nav.navigateWithMotion(Routes.playlistDetail(pl.id)) },
                        navMotionKind = NavMotionKind.Pill,
                    ) {
                        CoverImage(pl.coverUrl, 44.dp, shape = RoundedCornerShape(11.dp))
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(pl.name, fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${pl.trackCount} 首", fontSize = 9.sp, color = TextSecondary)
                        }
                        Icon(
                            NcmIcons.ChevronRight, null,
                            tint = Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        } else if (tab == 1) {
            if (!albumsLoaded) {
                item { CenterHint("加载中…", Modifier.fillMaxWidth().height(240.dp)) }
            } else if (albums.isEmpty()) {
                item { CenterHint("还没有收藏专辑\n专辑页点收藏即可加入这里", Modifier.fillMaxWidth().height(240.dp)) }
            } else {
                itemsIndexed(albums, key = { _, a -> "al_${a.id}" }, contentType = { _, _ -> "album" }) { _, al ->
                    StackedCard(
                        onClick = {
                            nav.navigateWithMotion(Routes.albumDetail(al.id), NavMotionSpec(NavMotionKind.Pill))
                        },
                        navMotionKind = NavMotionKind.Pill,
                    ) {
                        CoverImage(al.coverUrl, 44.dp, shape = RoundedCornerShape(11.dp))
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(al.name, fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOf(al.artist, if (al.songCount > 0) "${al.songCount} 首" else "")
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                fontSize = 9.sp, color = TextSecondary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Icon(
                            NcmIcons.ChevronRight, null,
                            tint = Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        } else {
            // 收藏的歌单（2026-10-23 需求2 闭环）：点击进歌单详情，可在详情页取消收藏
            if (!subPlaylistsLoaded) {
                item { CenterHint("加载中…", Modifier.fillMaxWidth().height(240.dp)) }
            } else if (subPlaylists.isEmpty()) {
                item { CenterHint("还没有收藏的歌单\n歌单详情页点「收藏歌单」即可加入这里", Modifier.fillMaxWidth().height(240.dp)) }
            } else {
                itemsIndexed(subPlaylists, key = { _, p -> "sub_${p.id}" }, contentType = { _, _ -> "playlist" }) { _, pl ->
                    StackedCard(
                        onClick = { nav.navigateWithMotion(Routes.playlistDetail(pl.id)) },
                        navMotionKind = NavMotionKind.Pill,
                    ) {
                        CoverImage(pl.coverUrl, 44.dp, shape = RoundedCornerShape(11.dp))
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(pl.name, fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${pl.trackCount} 首", fontSize = 9.sp, color = TextSecondary)
                        }
                        Icon(
                            NcmIcons.ChevronRight, null,
                            tint = Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Scale the whole switch, including native width animations, and reclaim its layout space. */
private fun Modifier.playlistSwitchScale(scale: Float = .6f): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout((placeable.width * scale).roundToInt(), (placeable.height * scale).roundToInt()) {
        placeable.placeRelativeWithLayer(0, 0) {
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}
