package com.netmusiclite.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.netmusiclite.ui.m3e.AdaptiveLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.netmusiclite.data.AppearancePrefs
import com.netmusiclite.data.HotSearch
import com.netmusiclite.data.NcmApi
import com.netmusiclite.data.PageCache
import com.netmusiclite.data.PlaylistItem
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.SearchHistoryStore
import com.netmusiclite.data.Song
import com.netmusiclite.ui.components.CenterHint
import com.netmusiclite.ui.components.CircularSideProgress
import com.netmusiclite.ui.components.circleSafeInsetPx
import com.netmusiclite.ui.components.CoverImage
import com.netmusiclite.ui.components.DialogScrim
import com.netmusiclite.ui.components.NcmToast
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.SongRow
import com.netmusiclite.ui.components.StackedCard
import com.netmusiclite.ui.components.estimateProgress
import com.netmusiclite.ui.components.ArtistCapsule
import com.netmusiclite.ui.components.rotaryList
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.NavMotionSpec
import com.netmusiclite.ui.nav.navigateWithMotion
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.Bg
import com.netmusiclite.ui.theme.BgElevated
import com.netmusiclite.ui.theme.screenBg
import com.netmusiclite.ui.theme.SurfaceGlass
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.math.abs

// ★ 2026-10-23 第二轮（用户真机反馈「m3e 打一个字就自动搜索」）：原来的 350ms 防抖
//   口径已**整条删除** —— 手表上的手写/九宫格输入每打完一个字就是一次完整 commit
//   （composition 立刻变 null），只要用户停下来看屏幕超过 350ms，那句「只打了一个字」
//   的文本就会被当成输完发出去真搜，列表刷新把正在输入的节奏打断。
//   现在改为**显式提交**，见 SearchScreen 里 searchTick/submitSearch 的注释。

/**
 * 进程内「上一次搜索结果」快照（★ 2026-10-23 用户报「点艺人进主页后右滑返回，搜索页
 * 回到输入框界面、结果没了」）。
 *
 * 为什么需要：搜索页点艺人 / 专辑 / 歌单会把它压进返回栈，右滑 pop 回来时页面组合**重建**，
 * `remember` 的 result/playlists 全部丢失。改成「显式提交」搜索之后不会再自动重搜一次，
 * 于是返回时就直接退回「输入框 + 历史热搜」的空态 —— 用户看到的就是结果列表没了。
 *
 * 这里把最近一次结果留在进程内存：返回时**同步**恢复（不发网络请求、不闪空），
 * 滚动位置交给 LazyListState 自己的 saveable + AdaptiveLazyColumn 的 ListViewportRestorer。
 * 只在搜索成功 / 清空时更新，不随输入变化。
 */
private object SearchSnapshot {
    var kw: String = ""
    var result: NcmApi.SearchResult? = null
    var playlists: List<PlaylistItem> = emptyList()

    fun save(query: String, r: NcmApi.SearchResult, pls: List<PlaylistItem>) {
        kw = query
        result = r
        playlists = pls
    }

    fun clear() {
        kw = ""
        result = null
        playlists = emptyList()
    }
}

/** 搜索歌单：一次请求取多少条（多取几条，过滤脏数据后仍有富余） */
private const val SEARCH_PLAYLIST_LIMIT = 6

/** 搜索结果里「相关歌单」分组最多展示几张卡片（圆屏一屏放不下更多） */
private const val PLAYLIST_CARDS_SHOWN = 3

/**
 * 搜索：350ms 防抖真实搜索。
 * 结果最上方为艺人胶囊卡（圆头像+艺人名），其下为「相关歌单」分组（2026-10-23 新增），
 * 再往下是相关歌曲普通列表。
 *
 * ★ 2026-10-23 项3：防抖改为**组词感知**——输入法 composition 期间不搜，
 *   上屏后再重新计 350ms（详见下方 LaunchedEffect 注释）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    nav: NavHostController,
    initialQuery: String? = null,
    /**
     * ★ 2026-10-23 项3：输入框焦点变化回调。
     *
     * 搜索页据此告诉调用方「用户正在输入」。AppNav 里搜索页挂了左滑手势（去播放页），
     * m3e 的 `m3eSwipeLeft` 在页面根、Main pass 主动 consume 横向事件 —— 它会和输入框内
     * 横向拖动（选光标 / 滑动输入）抢事件，甚至把正在打字的人直接带去播放页（用户反馈的
     * 「自动跳转」）。AppNav 用这个回调在**输入期间禁用左滑**，退出页面时这里会补发 false。
     */
    onInputFocusChange: ((Boolean) -> Unit)? = null,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // ★ 2026-10-23：kw 改 rememberSaveable —— 被压栈再 pop 回来时输入框里的词要还在
    var kw by rememberSaveable { mutableStateOf(initialQuery ?: "") }
    // ★ 2026-10-23：结果从进程内快照恢复（返回搜索页不再退回空态），详见 SearchSnapshot 注释
    var result by remember { mutableStateOf(if (kw.isBlank()) null else SearchSnapshot.result) }
    /** 搜索歌单结果（2026-10-23 项7 新增；与单曲/艺人/专辑并行拉取） */
    var playlists by remember {
        mutableStateOf(if (kw.isBlank()) emptyList() else SearchSnapshot.playlists)
    }
    var loading by remember { mutableStateOf(false) }
    /**
     * ★ 2026-10-23 项3：输入法是否**正在组词**（`TextFieldValue.composition != null`）。
     * 拼音/手写/语音输入法每敲一下都会把尚未上屏的中间态写进 text（"zhouj"、"周杰" 这类），
     * 只有组词结束（上屏）后的文本才代表用户真正想搜的词。防抖必须看这个状态，
     * 否则中间态一旦停顿 350ms 就会被当成「输完了」发出去真搜。
     */
    var composing by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(SearchHistoryStore.load(ctx)) }
    var hotList by remember { mutableStateOf<List<HotSearch>>(emptyList()) }
    val listState = rememberLazyListState()
    // 长按歌曲弹出的操作目标（null = 不显示操作浮层）
    var actionSong by remember { mutableStateOf<Song?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(toast) { if (toast != null) { delay(1800); toast = null } }

    LaunchedEffect(Unit) { hotList = PageCache.searchHot() }

    // ★★ 2026-10-23 第二轮（项3 返工）：搜索触发从「350ms 防抖」改为**显式提交**。
    //   用户真机反馈「m3e 打一个字就自动搜索」—— 上一轮加了「输入法组词感知」，
    //   但手表上的手写/九宫格输入每打一个字就是一次完整 commit（composition 立刻归 null），
    //   只要打字停顿超过 350ms，只写了一个字的文本就会被当成"输完了"发出去真搜。
    //   任何「停顿多久算输完」的阈值在手表输入节奏下都不成立 ⇒ 去掉自动触发，只留三条显式路径：
    //     ① 输入法软键盘的「搜索/确认」键（ImeAction.Search）；
    //     ② 点搜索框左侧的放大镜图标（手表输入法没有搜索键时的兜底入口）；
    //     ③ 点搜索历史 / 热搜词（本来就是"选词即搜"的语义）。
    //   输入过程中的任何文本变化只更新输入框，不再发任何网络请求。
    var searchTick by remember { mutableIntStateOf(0) }
    var searchKw by remember { mutableStateOf("") }

    /** 显式提交一次搜索：raw 为空则回到空态（历史/热搜）。 */
    fun submitSearch(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) {
            result = null
            playlists = emptyList()
            loading = false
            searchKw = ""
            return
        }
        composing = false
        searchKw = q
        searchTick++
    }

    // 输入框被清空 → 立即回到空态（不等任何提交）
    LaunchedEffect(kw) {
        if (kw.isBlank()) {
            result = null
            playlists = emptyList()
            loading = false
            SearchSnapshot.clear()
        }
    }

    // 带搜索词跳进来（听歌识曲 → SEARCH_Q）时自动提交一次
    LaunchedEffect(Unit) {
        val q0 = initialQuery?.trim().orEmpty()
        if (q0.isNotEmpty() && searchTick == 0) {
            searchKw = q0
            searchTick++
        }
    }

    // ★ 真正的网络搜索：只由 searchTick 变化触发（显式提交），不再随输入变化触发。
    LaunchedEffect(searchTick) {
        if (searchTick == 0) return@LaunchedEffect
        val q = searchKw
        if (q.isEmpty()) return@LaunchedEffect
        loading = true
        try {
            // 单曲/艺人/专辑（NcmApi.search 内部串行 3 次请求）与歌单（1 次）**并行**：
            // 总耗时取较慢的一路，而不是两路相加。歌单失败不影响歌曲结果（runCatching）。
            val (r, pls) = coroutineScope {
                val songsJob = async { NcmApi.search(q) }
                val playlistJob = async {
                    runCatching { NcmApi.searchPlaylists(q, SEARCH_PLAYLIST_LIMIT) }
                        .getOrDefault(emptyList())
                }
                songsJob.await() to playlistJob.await()
            }
            val validPls = pls.filter { it.id > 0L }
            result = r
            // 只留有效歌单：官方偶发 id=0 的占位条目，进详情会直接空页
            playlists = validPls
            // ★ 2026-10-23：留一份进程内快照 —— 点艺人/专辑/歌单回来时同步恢复结果
            SearchSnapshot.save(q, r, validPls)
            // 搜索成功（有结果）才写历史：最新在前、去重、上限 10 条
            if (r.songs.isNotEmpty() || playlists.isNotEmpty()) {
                SearchHistoryStore.add(ctx, q)
                history = SearchHistoryStore.load(ctx)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 被新的提交打断属于正常取消，不能吞（否则协程作用域状态会错）
            throw e
        } catch (e: Exception) {
            // 网络异常静默降级：**不清空上一次结果**，界面不闪空、不弹错窗。
            // （NcmApi 内部已把网络异常吞成空 JSON，正常路径走不到这里，属兜底。）
        } finally {
            loading = false
        }
    }

    val songs = result?.songs ?: emptyList()
    val artists = result?.artists ?: emptyList()
    // 「相关歌单」分组实际展示的卡片（最多 PLAYLIST_CARDS_SHOWN 张）
    val shownPlaylists = playlists.take(PLAYLIST_CARDS_SHOWN)

    // 空结果 → 「历史 + 热搜」组合页；有结果 → 结果列表。
    // ★ 2026-10-23 第二轮：显式提交口径下，「还没搜出东西」= 历史/热搜页。
    //   输入文字但尚未提交、或正在搜索时都保持空态（搜索中另有「搜索中…」分支），
    //   列表不会在打字过程中闪空，输入框焦点也不受影响。
    val emptyState = result == null

    // 侧缘进度环：按当前列表实际条目数动态估算，不写死
    // （2026-10-03 搜索结果列表：搜索框为列表第 1 项随列表滚动，计数 +1）
    // （2026-10-04 搜索框改为**两态恒在**列表首条，故空态也要 +1）
    // （2026-10-23 项7：结果态多了「相关歌单」分组 —— 分组标题 1 条 + 卡片 N 张 + 底部间隙 1 条）
    val listItemCount = when {
        emptyState -> 1 + (if (history.isNotEmpty()) 3 else 0) + 1 + hotList.size
        result != null -> 1 + songs.size + (if (artists.isNotEmpty()) 1 else 0) +
            (if (shownPlaylists.isNotEmpty()) shownPlaylists.size + 2 else 0)
        else -> 1
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(screenBg())
            .rotaryList(listState),
    ) {
        // 单图层纵向流：搜索框 → 历史 → 热搜/结果，互不重叠
        Column(Modifier.fillMaxSize()) {
            // ★ 2026-10-03 用户口径「搜索界面最上方搜索框不被屏幕裁切」：
            //   旧版顶距 10dp + 通宽输入框 —— 466px 方屏的圆表圈在 y≈20px 处的弦宽只剩
            //   ≈189px，输入框左右两端（中高处最宽）被弧边吃掉近 18dp/侧（真机表现就是
            //   「两头被裁」）。修法两步：
            //   ① 头部下移到 28dp：该高度的圆内可用宽度足够放下 42dp 高的输入框；
            //   ② 按输入框纵向中心算圆内安全内缩（circleSafeInsetPx，margin 加大到 14px
            //      让圆角肩部也不越界），只收窄横向、不改纵向 —— 不产生测量回环。
            //   内缩在首帧（centerY 未采样到）先给 0，避免 circleSafeInsetPx(0)=239px
            //   把输入框压成 0 宽；采样后一帧即就位。
            var fieldCenterY by remember { mutableStateOf(0f) }
            val fieldInsetDp = with(androidx.compose.ui.platform.LocalDensity.current) {
                if (fieldCenterY > 0f) circleSafeInsetPx(fieldCenterY, marginPx = 14f).toDp() else 0.dp
            }
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp),
                // ★ 2026-10-23 项3：m3e 主题下搜索框这一条**不参与 Wear 列表形变**。
                //   搜索页在 m3e 走 AdaptiveLazyColumn → TransformingLazyColumn：默认把每个 item
                //   的**布局高度压缩**、再用 graphicsLayer 放大画到视觉高度。首条搜索框被压缩后，
                //   「看得见的框」比「布局占位」高，圆屏上点框的下半截实际命中的是下方历史/热搜
                //   条目 —— 手指点在搜索框上却变成「点了别的词」并立刻按 350ms 防抖发起搜索，
                //   用户感受正是「还没输完就自己搜了 / 自己跳走了」。占真实高度后命中区与视觉
                //   完全一致；输入过程中列表锚点也不会再随形变抖动。
                //   经典主题忽略该参数（原生 LazyColumn 无形变）⇒ 两主题交互一致。
                safeKeys = setOf("search_field"),
            ) {
                // ★ 2026-10-04 修复「点搜索框后按一下键盘就跳出输入」：
                //   旧版空态把搜索框挂在外层固定头部，一旦输入首字符（kw 由空转非空），
                //   emptyState 立刻翻转 ⇒ 输入框从外层 Column 搬到 LazyColumn 首条。
                //   跨父节点搬家时 Compose 会销毁旧节点、新建节点不带焦点 ⇒ 键盘收起、
                //   列表转空，用户看到的就是「按一下键就进了搜索、回不去继续输入」。
                //   现在搜索框**无条件**作为列表首条（key 恒定），空态/结果态共用同一个
                //   组合节点，输入首字符不再触发搬家，焦点与键盘全程保持。
                //   顶距 26dp + 列表 contentPadding 2dp = 28dp 静置位（与原固定头部一致）；
                //   圆屏内缩采样加门（仅列表停顶时记录），滚动途中位置变化不改内缩 ——
                //   否则输入框在滚动中忽宽忽窄（测量回环）。
                item(key = "search_field") {
                    Column(
                        Modifier
                            .padding(top = 26.dp, bottom = 4.dp)
                            .onGloballyPositioned { c ->
                                if (com.netmusiclite.ui.m3e.listFirstIndex(listState) == 0 &&
                                    com.netmusiclite.ui.m3e.listFirstOffset(listState) < 8
                                ) {
                                    val cy = c.positionInRoot().y + c.size.height / 2f
                                    if (abs(cy - fieldCenterY) > 0.5f) fieldCenterY = cy
                                }
                            },
                    ) {
                        SearchField(
                            kw = kw,
                            onQuery = { kw = it },
                            // ★ 项3：把输入法组词状态上报（提交时复位，避免残留）
                            onComposingChange = { composing = it },
                            // ★ 2026-10-23 第二轮：显式提交入口（软键盘搜索键 / 点放大镜）
                            onSearch = { submitSearch(kw) },
                            // 输入焦点上报（AppNav 据此在输入期间禁用左滑手势）
                            onFocusChange = onInputFocusChange,
                            horizontalInset = fieldInsetDp,
                        )
                    }
                }
                if (loading && result == null) {
                    item { Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                        Text("搜索中…", fontSize = 12.sp, color = TextSecondary)
                    } }
                } else if (emptyState) {
                if (history.isNotEmpty()) {
                    item(key = "history_label") {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("搜索历史", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "清空",
                                fontSize = 9.sp, color = TextSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .clickable(
                                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                        indication = null,
                                    ) {
                                        SearchHistoryStore.clear(ctx)
                                        history = emptyList()
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    item(key = "history_chips") {
                        FlowRow(
                            modifier = Modifier.padding(horizontal = 18.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            history.forEach { h ->
                                Text(
                                    h,
                                    fontSize = 10.sp, color = TextPrimary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(SurfaceGlass)
                                        .clickable(
                                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            // 用户显式选词：写回输入框并**立刻提交搜索**
                                            kw = h
                                            submitSearch(h)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                    item(key = "hot_gap") { Spacer(Modifier.height(14.dp)) }
                }
                item(key = "hot_label") {
                    Text(
                        "网易云热搜榜",
                        fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
                    )
                }
                if (hotList.isEmpty()) {
                    item(key = "hot_empty") {
                        Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                            Text("热搜加载中…", fontSize = 10.sp, color = TextSecondary)
                        }
                    }
                } else {
                    itemsIndexed(hotList, key = { i, _ -> "hot_$i" }, contentType = { _, _ -> "hot" }) { i, h ->
                        HotRow(i, h, maxScore = hotList.maxOf { it.score }) {
                            // 同上：显式点热词 = 写回输入框并立刻提交搜索
                            kw = h.word
                            submitSearch(h.word)
                        }
                    }
                }
            } else if (result == null) {
                item {}
            } else {
                if (artists.isNotEmpty()) {
                    item(key = "artist_card") {
                        ArtistCapsule(artists.first()) {
                            nav.navigateWithMotion(
                                Routes.artistDetail(artists.first().id),
                                NavMotionSpec(NavMotionKind.Pill),
                            )
                        }
                    }
                    item(key = "artist_gap") { Spacer(Modifier.height(4.dp)) }
                }
                // ★ 2026-10-23 项7 新增「相关歌单」分组：
                //   网易云 cloudsearch 按 type 分桶（1 单曲 / 1000 歌单 / 10 专辑），
                //   歌单结果走 NcmApi.searchPlaylists（type=1000），与单曲结果**并行**拉取。
                //   放在艺人卡下方、歌曲列表上方 —— 圆屏首屏就能看到「相关歌单」入口，
                //   不必滚到底；点卡片进歌单详情（Routes.PLAYLIST_DETAIL = playlist/{id}，
                //   AppNav 已注册，无需新增路由）。
                if (shownPlaylists.isNotEmpty()) {
                    item(key = "playlist_label") {
                        Text(
                            "相关歌单",
                            fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TextSecondary,
                            modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
                        )
                    }
                    itemsIndexed(
                        shownPlaylists,
                        key = { _, p -> "pl_${p.id}" },
                        contentType = { _, _ -> "playlist" },
                    ) { _, pl ->
                        SearchPlaylistCard(pl) {
                            nav.navigateWithMotion(
                                Routes.playlistDetail(pl.id),
                                NavMotionSpec(NavMotionKind.Pill),
                            )
                        }
                    }
                    item(key = "playlist_gap") { Spacer(Modifier.height(6.dp)) }
                }
                if (songs.isEmpty() && shownPlaylists.isEmpty()) {
                    // 单曲与歌单都空才算「没有找到」（有歌单时不该弹「没有找到相关歌曲」）
                    item { Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text("没有找到相关内容", fontSize = 12.sp, color = TextSecondary)
                    } }
                } else if (songs.isNotEmpty()) {
                    itemsIndexed(songs, key = { _, s -> s.id }, contentType = { _, _ -> "song" }) { i, song ->
                        SongRow(
                            song = song,
                            isCurrent = PlayerEngine.current?.id == song.id,
                            // 跨语言搜索：中文词命中日文歌时，把命中的译名标出来
                            aliasHint = com.netmusiclite.data.SongQuery.matchedAlias(song, kw.trim()),
                            cardEdge = true,
                            onClick = {
                                PlayerEngine.playQueue(songs, i)
                                nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Pill))
                            },
                            // 长按出操作浮层：添加到列表 / 下一首播放（2026-10-01）
                            onLongClick = { actionSong = song },
                        )
                    }
                }
            }
        }
        }

        val scrollProgress by remember(listItemCount) {
            derivedStateOf { estimateProgress(listState, listItemCount.coerceAtLeast(2)) }
        }
        CircularSideProgress(
            progress = { scrollProgress },
            modifier = Modifier.fillMaxSize(),
        )

        // 长按歌曲的操作浮层：下一首播放（2026-10-01）
        actionSong?.let { s ->
            DialogScrim(onDismiss = { actionSong = null }) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(BgElevated)
                        // ★ 2026-10-01：整块压扁（内边距 18→13dp、字号各收一档）。
                        //   旧版纵向排两行时整块比圆屏可用高度还高，Column 把最后一个按钮的
                        //   高度分到 0dp —— 按钮文字整段消失（用户反馈「看不到按钮上的字」）。
                        //   现按用户要求只留「下一首播放」一项，整块 ≈80dp，稳稳落在圆内。
                        .padding(horizontal = 13.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        s.title, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        s.artist, fontSize = 8.5.sp, color = TextSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(10.dp))
                    // 只保留「下一首播放」——「添加到列表」按用户要求移除
                    SongActionRow("下一首播放", Accent) {
                        PlayerEngine.playNextInQueue(s)
                        actionSong = null
                        toast = "已设为下一首 · ${s.title}"
                    }
                }
            }
        }

        NcmToast(toast, Modifier.align(Alignment.Center))
    }
}

/**
 * 长按操作浮层里的单个动作按钮（胶囊）。
 * ★ 2026-10-01：底从「动作色 14% 透明」改为玻璃材质 + 动作色描边 ——
 *   旧版浅色主题色（含白色）下会出现「浅字压浅底」而整条看不见的情况；
 *   玻璃底 + 描边让按钮边界与文字在深/浅两套主题下都稳定可读。
 *   两个动作改为并排后按钮变窄，字号收到 10sp 且不换行。
 */
@Composable
private fun SongActionRow(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Text(
        label,
        fontSize = 10.sp, color = color, fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(SurfaceGlass)
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(vertical = 8.dp),
    )
}

/**
 * 搜索结果里的歌单卡（2026-10-23 项7 新增）。
 *
 * 直接复用 [StackedCard]：**m3e 主题自动走 M3eRowCard（Wear 卡片）、经典主题走堆叠玻璃卡**，
 * 与「我的歌单」「用户主页 Ta 的歌单」两处用的是同一套视觉，不新造样式、不引入主题分支。
 * 点击进歌单详情：路由 Routes.PLAYLIST_DETAIL = `playlist/{playlistId}`（AppNav 已注册）。
 *
 * 副标题按官方字段可用性拼接「歌单 · N 首 · 播放量」：
 *  · trackCount 来自 cloudsearch 的 `trackCount`；
 *  · playCount 是官方累计播放量，卡片窄所以折成「万 / 亿」一位小数；
 *  · 两个字段缺失（0）时不显示对应段，不出现「0 首」这种脏文案。
 */
@Composable
private fun SearchPlaylistCard(pl: PlaylistItem, onClick: () -> Unit) {
    // 横向内距：经典主题里本页列表项都自带 18dp 左右内距（艺人胶囊 / 歌曲行），卡片取同值
    // 才与上下行对齐；m3e 的 AdaptiveLazyColumn（NativeList/ScreenScaffold）已经统一给
    // 16dp 横向安全内距，这里再加会双重收窄 ⇒ m3e 传 0。
    val horizontalPad = if (AppearancePrefs.isM3e) 0.dp else 18.dp
    StackedCard(
        modifier = Modifier.padding(horizontal = horizontalPad),
        onClick = onClick,
        navMotionKind = NavMotionKind.Pill,
        height = 52.dp,
        contentPadding = PaddingValues(horizontal = 13.dp),
    ) {
        CoverImage(pl.coverUrl, 38.dp, shape = RoundedCornerShape(10.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                pl.name, fontSize = 11.sp, color = TextPrimary, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                playlistSubtitle(pl), fontSize = 8.5.sp, color = TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(NcmIcons.ChevronRight, null, tint = TextSecondary, modifier = Modifier.size(13.dp))
    }
}

/** 歌单卡副标题：「歌单 · N 首 · X 万播放」（只拼有值的段） */
private fun playlistSubtitle(pl: PlaylistItem): String = buildList {
    add("歌单")
    if (pl.trackCount > 0) add("${pl.trackCount} 首")
    if (pl.playCount > 0L) add("${playCountText(pl.playCount)}播放")
}.joinToString(" · ")

/** 播放量缩写：与网易云口径一致（1.2 万 / 3.4 亿），搜索卡宽度窄只用一位小数 */
private fun playCountText(n: Long): String = when {
    n >= 100_000_000L -> "%.1f 亿".format(n / 100_000_000.0)
    n >= 10_000L -> "%.1f 万".format(n / 10_000.0)
    else -> "$n"
}

@Composable
private fun SearchField(
    kw: String,
    onQuery: (String) -> Unit,
    /**
     * ★ 2026-10-23 项3：输入法组词状态回调。
     * true = `TextFieldValue.composition != null`，即输入法正在组词（拼音串/手写/语音中间态），
     * 此时 text 里混着**尚未上屏**的内容，上层必须暂停 350ms 防抖搜索。
     * 清空按钮属于「非组词的程序化改写」，同样回调 false，保证清空后能立即回到空态。
     */
    onComposingChange: (Boolean) -> Unit,
    /**
     * ★ 2026-10-23 第二轮：显式提交搜索的回调（软键盘「搜索」键 / 点放大镜按钮）。
     * 搜索已不再随输入自动触发，真实网络请求只由这个入口（以及点历史/热搜词）发起。
     */
    onSearch: () -> Unit,
    /**
     * 输入框焦点变化回调（可空）：AppNav 据此在「输入期间」禁用搜索页的左滑手势，
     * 避免 m3e 的页面级横滑与输入框内横向拖动（选光标）抢事件、把打字的人带去播放页。
     */
    onFocusChange: ((Boolean) -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** 圆屏安全内缩（见调用点注释）；与基础 18dp 取较大者，方屏上传 0dp 即恢复原宽度 */
    horizontalInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    // 软键盘控制器：提交搜索后收起键盘，让结果列表露出来（手表屏幕小，键盘占掉大半）
    val keyboard = LocalSoftwareKeyboardController.current
    // TextFieldValue 状态重载：光标/选区由调用方持有，
    // 避免按删除键时光标状态在重组中丢失（表现为跳到上一个字而非删字）
    var textState by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(kw))
    }
    // 外部改动（点 × 清空 / 点历史词或热搜词）时同步回输入框
    LaunchedEffect(kw) {
        if (kw != textState.text) {
            textState = TextFieldValue(kw, TextRange(kw.length))
        }
    }
    // 离开页面/搜索框被回收时补发一次「失焦」：否则 AppNav 里的 inputFocused 会残留 true，
    // 下次进搜索页时左滑手势莫名失效。
    DisposableEffect(Unit) {
        onDispose { onFocusChange?.invoke(false) }
    }
    Row(
        modifier = modifier
            .padding(horizontal = maxOf(horizontalInset, 18.dp))
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(50))
            .background(SurfaceGlass)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ★ 2026-10-23 第二轮：放大镜从装饰图标改成**提交搜索按钮**。
        //   手表输入法（手写/九宫格）不一定带「搜索」键，没有这个显式入口，用户会觉得
        //   「打完字没反应、搜索坏了」。点它 = 提交当前输入并收起键盘；空输入时无副作用。
        val searchSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        Box(
            Modifier
                .size(20.dp)
                .androidxClickable(searchSrc) {
                    keyboard?.hide()
                    onSearch()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                NcmIcons.Search, "搜索",
                tint = if (kw.isNotEmpty()) Accent else TextSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        BasicTextField(
            value = textState,
            onValueChange = {
                textState = it
                // ★ 项3：先上报组词状态，再上报文本 —— 组词期间任何变化都不会触发搜索
                onComposingChange(it.composition != null)
                onQuery(it.text)
            },
            singleLine = true,
            // ★ 2026-10-23 第二轮：软键盘上的「搜索」动作键 = 显式提交入口。
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                onSearch()
            }),
            textStyle = TextStyle(fontSize = 13.sp, color = TextPrimary),
            cursorBrush = SolidColor(Accent),
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { onFocusChange?.invoke(it.isFocused) },
            // ★ 2026-10-24 用户需求：搜索框恢复「搜点什么」占位（单行，不改变框高）。
            decorationBox = { inner ->
                Box {
                    if (textState.text.isEmpty()) {
                        Text("搜点什么", fontSize = 13.sp, color = TextSecondary)
                    }
                    inner()
                }
            },
        )
        if (kw.isNotEmpty()) {
            val clearSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Icon(
                NcmIcons.Close, "清空", tint = TextSecondary,
                modifier = Modifier
                    .size(14.dp)
                    .androidxClickable(clearSrc) {
                        // 清空是程序化改写：显式复位组词标记，否则可能残留 true 挡住后续搜索
                        onComposingChange(false)
                        onQuery("")
                    },
            )
        }
    }
}

private fun Modifier.androidxClickable(
    src: androidx.compose.foundation.interaction.MutableInteractionSource,
    onClick: () -> Unit,
): Modifier = this.then(
    Modifier.clickable(interactionSource = src, indication = null, onClick = onClick)
)

/** 热搜榜单行：序号（前三名强调色）+ 词条 + 右侧热度细条（按 score 归一化） */
@Composable
private fun HotRow(index: Int, h: HotSearch, maxScore: Int, onClick: () -> Unit) {
    val top3 = index < 3
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null, onClick = onClick,
            )
            .padding(horizontal = 22.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${index + 1}",
            fontSize = 12.sp, fontWeight = FontWeight.Bold,
            color = if (top3) Accent else TextSecondary,
            modifier = Modifier.width(22.dp),
        )
        Text(
            h.word,
            fontSize = 12.sp, color = TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        if (maxScore > 0) {
            val frac = (h.score.toFloat() / maxScore).coerceIn(0.06f, 1f)
            Box(
                Modifier
                    .width((12 + 46 * frac).dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Accent.copy(alpha = if (top3) 0.85f else 0.35f)),
            )
        }
    }
}
