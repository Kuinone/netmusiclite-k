package com.netmusiclite.ui.screens.comments

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.netmusiclite.ui.m3e.M3eListMode
import com.netmusiclite.ui.m3e.m3eSinkFromCenter
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import com.netmusiclite.data.CommentItem
import com.netmusiclite.data.NcmApi
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.ui.components.CenterHint
import com.netmusiclite.ui.components.CoverImage
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.rotaryList
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.Gold
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary

/**
 * 云村评论（2026-10-01 升级）：
 * - 主楼显示「N 个赞 / N 条回复」；点赞走 SVG 心形，乐观更新、失败回滚
 * - 点「N 条回复」就地展开二级回复列表（读他人对这条评论的回复），再点收起；
 *   二级回复同样可点赞
 * - 滚到底自动翻页（offset+20）
 * 数据：NcmApi.commentsByThread / commentReplies / likeComment。
 */
@Composable
fun CommentsScreen(nav: NavHostController, songId: Long) {
    val song = PlayerEngine.current ?: com.netmusiclite.data.DownloadStore.songOf(songId)
    // 歌曲评论 threadId 固定形如 R_SO_4_{songId}（与 NcmApi.comments 同源）
    val threadId = remember(songId) { "R_SO_4_$songId" }
    var hot by remember { mutableStateOf<List<CommentItem>>(emptyList()) }
    var latest by remember { mutableStateOf<List<CommentItem>>(emptyList()) }
    var offset by remember { mutableStateOf(0) }
    var endReached by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var loadingMore by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // ---- 二级回复（2026-10-01）----
    /** 当前展开回复的主楼评论 id（null = 全部收起） */
    var expandedId by remember { mutableStateOf<Long?>(null) }
    val repliesMap = remember { mutableStateMapOf<Long, List<CommentItem>>() }
    var replyLoading by remember { mutableStateOf(false) }
    // ★ 2026-10-18 楼中楼分页（用户口径「回复评论的评论限制十条加载」）：
    //   floor/get 一页只有 10~20 条，旧实现只拉第一页、也不认 hasMore，
    //   于是回复列表永远停在一页。这里按主楼 id 记录三样状态：
    //   · replyHasMore —— 服务端说还有下一页（data.hasMore）
    //   · replyCursor  —— 下一页游标（data.time，回传给 commentReplies 的 time）
    //   · replyMoreLoadingId —— 正在给哪条主楼加载下一页（同一时刻只允许一个，避免连点）
    val replyHasMore = remember { mutableStateMapOf<Long, Boolean>() }
    val replyCursor = remember { mutableStateMapOf<Long, Long>() }
    var replyMoreLoadingId by remember { mutableStateOf<Long?>(null) }
    /** 楼中楼一页的条数：与服务端上限对齐（手机端默认也是 10），后续「加载更多」按同一页大小翻 */
    val replyPageSize = 10

    // ★ 2026-10-01 六轮（用户口径「评论区回复数量要主动显示」）：
    //   主评论列表接口不下发 replyCount（实测三种口径都没有），条数只能靠 floor/get 逐条问。
    //   之前是「展开一次才回填那一条」，其余入口只能一直显示笼统的「回复」。
    //   现在列表一到位就**主动**逐条补齐：
    //   - limit=1，只要 data.totalCount，载荷最小；
    //   - 串行 + 45ms 间隔，避免一次性几十个请求砸到接口；
    //   - 结果进 replyCounts 缓存（0 也缓存，避免重复问）；
    //   - 翻页加载出新评论后这个 Effect 会因 latest.size 变化自动续跑，只补没问过的。
    //   进页面 1~2 秒内各入口就会陆续从「回复」变成「N 条回复」。
    val replyCounts = remember { mutableStateMapOf<Long, Int>() }
    LaunchedEffect(songId, hot.size, latest.size) {
        if (failed) return@LaunchedEffect
        val seen = HashSet<Long>()
        for (c in hot + latest) {
            if (c.id <= 0L || !seen.add(c.id)) continue
            if (replyCounts.containsKey(c.id)) continue
            val t = runCatching {
                NcmApi.commentReplies(threadId, c.id, limit = 1).total
            }.getOrDefault(0)
            replyCounts[c.id] = t
            delay(45)
        }
    }

    LaunchedEffect(songId) {
        val r = NcmApi.comments(songId, 0)
        if (r == null) failed = true
        else {
            hot = r.first
            latest = r.second
            offset = r.second.size
            if (r.second.size < 20) endReached = true
        }
        loading = false
    }

    // 滚到末尾自动翻页：footer（最后一项）可见即触发。
    // 加载在 rememberCoroutineScope 里跑（不受本 Effect 重启取消影响），
    // loadingMore 用 try/finally 保证必复位——否则滚动中 Effect 被取消会永远卡「加载中」。
    fun loadMore() {
        if (loadingMore || endReached || failed || loading) return
        loadingMore = true
        scope.launch {
            try {
                val r = NcmApi.comments(songId, offset)
                if (r == null || r.second.isEmpty()) endReached = true
                else {
                    latest = latest + r.second
                    offset += r.second.size
                    if (r.second.size < 20) endReached = true
                }
            } finally {
                loadingMore = false
            }
        }
    }

    /** 主楼评论点赞 / 取消赞：乐观更新（热评与最新两条列表都改），失败回滚 */
    fun toggleLike(c: CommentItem, isHot: Boolean) {
        if (c.id <= 0L || threadId.isEmpty()) return
        val target = !c.mine
        fun apply(on: Boolean) {
            fun upd(list: List<CommentItem>) = list.map {
                if (it.id == c.id) it.copy(
                    mine = on,
                    liked = (it.liked + if (on) 1 else -1).coerceAtLeast(0),
                ) else it
            }
            if (isHot) hot = upd(hot) else latest = upd(latest)
        }
        apply(target)
        scope.launch {
            val good = runCatching { NcmApi.likeComment(threadId, c.id, target) }.getOrDefault(false)
            android.util.Log.i("LTDiag", "comment like id=${c.id} target=$target ok=$good")
            if (!good) apply(!target)
        }
    }

    /** 二级回复点赞（更新 repliesMap 中对应那一项） */
    fun toggleReplyLike(mainId: Long, r: CommentItem) {
        if (r.id <= 0L || threadId.isEmpty()) return
        val target = !r.mine
        fun apply(on: Boolean) {
            val cur = repliesMap[mainId] ?: return
            repliesMap[mainId] = cur.map {
                if (it.id == r.id) it.copy(
                    mine = on,
                    liked = (it.liked + if (on) 1 else -1).coerceAtLeast(0),
                ) else it
            }
        }
        apply(target)
        scope.launch {
            val good = runCatching { NcmApi.likeComment(threadId, r.id, target) }.getOrDefault(false)
            if (!good) apply(!target)
        }
    }

    /** 展开 / 收起某条评论的回复；首次展开才拉取 */
    fun toggleReplies(c: CommentItem) {
        if (c.id <= 0L) return
        if (expandedId == c.id) {
            expandedId = null
            return
        }
        expandedId = c.id
        if (repliesMap.containsKey(c.id)) return
        replyLoading = true
        scope.launch {
            try {
                // ★ 2026-10-18：按楼中楼一页大小拉第一页，并把 hasMore / 下一页游标记下来，
                //   供下面「加载更多回复」继续翻页（旧实现只拉第一页、状态全丢，
                //   所以回复永远停在一页 —— 用户口径「回复评论的评论限制十条加载」）。
                val r = runCatching {
                    NcmApi.commentReplies(threadId, c.id, limit = replyPageSize)
                }.getOrNull()
                repliesMap[c.id] = r?.items ?: emptyList()
                replyHasMore[c.id] = r?.hasMore == true
                replyCursor[c.id] = r?.nextTime ?: 0L
                // 主评论列表不下发楼层数（实测），拿 floor/get 的真实 totalCount 回填主楼，
                // 之后入口就显示准确条数而不是笼统的「回复」
                val total = r?.total ?: 0
                if (total > 0) {
                    replyCounts[c.id] = total   // 同时进缓存，主动补齐的循环就不会再问这条
                    if (total != c.replyCount) {
                        fun patch(list: List<CommentItem>) = list.map {
                            if (it.id == c.id) it.copy(replyCount = total) else it
                        }
                        hot = patch(hot)
                        latest = patch(latest)
                    }
                }
            } finally {
                replyLoading = false
            }
        }
    }

    /**
     * 楼中楼「加载更多回复」：按第一页带回来的 `data.time` 游标翻下一页，追加到已展开的列表。
     * 失败时**保留 hasMore**（用户还能再点一次重试），不假装「到底了」。
     */
    fun loadMoreReplies(c: CommentItem) {
        if (c.id <= 0L) return
        if (replyMoreLoadingId != null) return          // 同一时刻只翻一个楼，连点会被忽略
        if (replyHasMore[c.id] != true) return
        val cursor = replyCursor[c.id] ?: 0L
        replyMoreLoadingId = c.id
        scope.launch {
            try {
                val r = runCatching {
                    NcmApi.commentReplies(threadId, c.id, limit = replyPageSize, time = cursor)
                }.getOrNull()
                if (r != null) {
                    val merged = (repliesMap[c.id].orEmpty() + r.items).distinctBy { it.id }
                    repliesMap[c.id] = merged
                    replyHasMore[c.id] = r.hasMore
                    replyCursor[c.id] = r.nextTime
                    val total = r.total
                    if (total > 0) {
                        replyCounts[c.id] = total
                        fun patch(list: List<CommentItem>) = list.map {
                            if (it.id == c.id) it.copy(replyCount = total) else it
                        }
                        hot = patch(hot)
                        latest = patch(latest)
                    }
                }
            } finally {
                replyMoreLoadingId = null
            }
        }
    }

    val endVisible by remember {
        derivedStateOf {
            val info = com.netmusiclite.ui.m3e.listLayoutInfo(listState)
            info.totalItemsCount > 0 &&
                (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(endVisible, latest.size) {
        if (endVisible && !loading && !loadingMore && !endReached && latest.isNotEmpty()) loadMore()
    }

    Box(
        Modifier
            .fillMaxSize()
            .rotaryList(listState),
    ) {
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
        ) {
            // 悬浮胶囊标题：浮层覆盖列表顶部，胶囊以外全部是评论列表
            // 2026-10-01：先整体缩小 60%（16sp/22×6dp → 6.4sp/8.8×2.4dp），
            // 同日二轮按用户口径再放大 20%（→ 7.68sp / 10.56×2.88dp）
            Text(
                "评论",
                fontSize = 7.68.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    // 跟随主题的弱色块（深色主题→白 13%，浅色主题→黑 13%）
                    .background(TextPrimary.copy(alpha = 0.13f))
                    .padding(horizontal = 10.56.dp, vertical = 2.88.dp),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 46.dp, bottom = 10.dp),
            // ★ 2026-10-17 用户口径「m3e 主题下评论区禁用堆叠效果，只允许出现下沉效果」：
            //   SINK = 不再用 Wear 的 transformedHeight 压缩评论行占位高度（压缩后长评论溢出、
            //   相邻条目互相叠 = 「堆叠」），也不给 Material 组件形变；改为逐项按距屏幕中线
            //   缩放 + 变暗的下沉效果（参数与经典主题 sinkFromCenter 同源，见 M3eShared）。
            mode = M3eListMode.SINK,
        ) {
            if (loading) {
                item { CenterHint("加载中…", Modifier.fillMaxWidth().height(160.dp)) }
            } else if (failed) {
                item { CenterHint("评论获取失败\n请检查登录态与网络", Modifier.fillMaxWidth().height(160.dp)) }
            } else {
                if (hot.isNotEmpty()) {
                    item(key = "hot_label") { SectionLabel("热门评论") }
                    itemsIndexed(
                        hot,
                        key = { i, _ -> "hot_$i" },
                        contentType = { _, _ -> "hot" },
                    ) { _, c ->
                        CommentRow(
                            c = c, isHot = true,
                            expanded = expandedId == c.id,
                            replies = repliesMap[c.id].orEmpty(),
                            replyLoading = replyLoading && expandedId == c.id,
                            replyCount = maxOf(replyCounts[c.id] ?: 0, c.replyCount),
                            replyHasMore = replyHasMore[c.id] == true,
                            replyMoreLoading = replyMoreLoadingId == c.id,
                            onLoadMoreReplies = { loadMoreReplies(c) },
                            onLike = { toggleLike(c, true) },
                            onToggleReplies = { toggleReplies(c) },
                            onReplyLike = { r -> toggleReplyLike(c.id, r) },
                        )
                    }
                    item(key = "latest_label") { SectionLabel("最新评论 (${latest.size})") }
                }
                itemsIndexed(
                    latest,
                    key = { i, _ -> "latest_$i" },
                    contentType = { _, _ -> "latest" },
                ) { _, c ->
                    CommentRow(
                        c = c, isHot = false,
                        expanded = expandedId == c.id,
                        replies = repliesMap[c.id].orEmpty(),
                        replyLoading = replyLoading && expandedId == c.id,
                        replyCount = maxOf(replyCounts[c.id] ?: 0, c.replyCount),
                        replyHasMore = replyHasMore[c.id] == true,
                        replyMoreLoading = replyMoreLoadingId == c.id,
                        onLoadMoreReplies = { loadMoreReplies(c) },
                        onLike = { toggleLike(c, false) },
                        onToggleReplies = { toggleReplies(c) },
                        onReplyLike = { r -> toggleReplyLike(c.id, r) },
                    )
                }
                if (endReached && latest.isEmpty()) {
                    item { CenterHint("还没有评论", Modifier.fillMaxWidth().height(120.dp)) }
                }
                item(key = "footer") {
                    Text(
                        when {
                            loadingMore -> "加载中…"
                            endReached -> "到底啦"
                            else -> ""
                        },
                        fontSize = 9.sp, color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TextSecondary,
        modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
    )
}

/**
 * 主楼评论：头像 + 昵称 + 时间 + 内容 + 操作行（点赞 / 回复数）+ 可展开的二级回复。
 * 点赞与回复图标全部走 NcmIcons 矢量图（无 emoji）。
 */
@Composable
private fun CommentRow(
    c: CommentItem,
    isHot: Boolean,
    expanded: Boolean,
    replies: List<CommentItem>,
    replyLoading: Boolean,
    /** 主动补齐后的回复数（floor/get 逐条问回来的），接口没下发时由调用方兜底 */
    replyCount: Int,
    /** 该主楼还有下一页回复（floor/get 的 data.hasMore） */
    replyHasMore: Boolean,
    /** 该主楼正在加载下一页回复 */
    replyMoreLoading: Boolean,
    onLoadMoreReplies: () -> Unit,
    onLike: () -> Unit,
    onToggleReplies: () -> Unit,
    onReplyLike: (CommentItem) -> Unit,
) {
    // ★ 2026-10-17 用户口径「m3e 主题下评论区禁用堆叠、只允许下沉」：
    //   堆叠（Wear transforming 列表的 transformedHeight 高度压缩）已在列表层关掉（mode = SINK）；
    //   下沉在这里声明 —— 只作用于评论正文条目（SectionLabel / 加载提示 / footer 保持原样），
    //   按距屏幕竖直中线的距离缩放 + 变暗，参数与经典主题 sinkFromCenter 同源。经典主题下是空操作。
    //
    // ★ 2026-10-23 修「点『N 条回复 / 展开更多回复』后，二级菜单自己跟着下沉」：
    //   m3eSinkFromCenter 的基准是**自身高度的一半**（M3eShared：centerY = item 顶部 + height/2，
    //   再与 STACKED_VIEWPORT/2 = 116.5dp 的屏幕中线比较）。它只为「一屏一行的卡片」设计：
    //   高度 ~60-90dp 时 item 近似贴着中线滑过，缩放/变暗轻微且连续。
    //   而二级回复是**就地展开在本 item 内部**的（不是独立的 LazyColumn item），展开后本行高度
    //   会涨到 300dp 以上、常常超过整个 233dp 视口 ⇒ 自身中心长期远离屏幕中线、t 值一直很大：
    //   整块（主评论 + 二级回复）被持续压在 ≈0.88 缩放 / 0.64 alpha；滑动时中心又连续移动
    //   ⇒ 用户看到的就是「二级菜单自己在做下沉动画」，且回复展开得越多、块越高，下沉越重
    //   （越滑越糊）。这就是「二级菜单被当成独立形变单元」的实际成因。
    //   用户口径：二级回复与主评论属**同一层级**，在该层级内滑动不触发下沉动画。
    //   做法：**展开态不挂下沉** —— 这一条变成静态层级，主评论与二级回复作为同一块平滑滚动、
    //   不缩放不变暗；收起态维持原逻辑（单行高度，按设计下沉）。
    //   二级回复仍留在同一个 LazyColumn item 内（同一层级），不拆 item、不动 key/contentType，
    //   所以回复翻页 / 加载更多 / 点赞 / 回复交互与经典主题观感都不受影响
    //   （经典主题下 m3eSinkFromCenter 本就是空操作）。
    val rowSink = if (expanded) Modifier else Modifier.m3eSinkFromCenter()
    Column(Modifier.fillMaxWidth().then(rowSink).padding(horizontal = 22.dp, vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoverImage(c.avatar, 20.dp, shape = CircleShape)
            Spacer(Modifier.width(6.dp))
            Text(
                c.user, fontSize = 9.sp, color = TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isHot) {
                Text(
                    "热评", fontSize = 9.sp, color = Gold, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Gold.copy(alpha = 0.12f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            Text(c.time, fontSize = 8.sp, color = TextSecondary)
        }
        Spacer(Modifier.height(2.dp))
        Text(c.content, fontSize = 11.sp, color = TextPrimary, lineHeight = 15.sp)

        // 操作行：赞（SVG 心形）+ 回复（SVG 气泡，点开二级）
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onLike,
                    )
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (c.mine) NcmIcons.HeartFill else NcmIcons.Heart,
                    contentDescription = if (c.mine) "取消赞" else "点赞",
                    tint = if (c.mine) Accent else TextSecondary,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("${c.liked}", fontSize = 9.sp, color = if (c.mine) Accent else TextSecondary)
            }
            // ★ 2026-10-01 二轮：入口**恒显**。服务端 `/v1/resource/comments/{threadId}`
            //   在实测的三种口径下都**不下发** replyCount / commentCount / showFloorComment
            //   （node 探针 probe_comment2/floorcount 实证），按 `replyCount > 0` 才给入口
            //   等于永远进不去二级 —— 这正是用户报的「评论的评论无法看到」的根因。
            //   现在一律给入口：条数已知显示「N 条回复」，未知显示「回复」；
            //   展开拉到 floor/get 的真实 totalCount 后会回填，下次即显示准确条数。
            Spacer(Modifier.width(12.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onToggleReplies,
                    )
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    NcmIcons.CommentIc,
                    contentDescription = "回复",
                    tint = if (expanded) Accent else TextSecondary,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    when {
                        expanded -> "收起"
                        // ★ 2026-10-01 六轮：这里改用主动补齐的条数（floor/get 逐条问回来的），
                        //   不再依赖接口从不下发的 c.replyCount
                        replyCount > 0 -> "$replyCount 条回复"
                        else -> "回复"
                    },
                    fontSize = 9.sp, color = if (expanded) Accent else TextSecondary,
                )
            }
        }

        // 二级回复列表（就地展开，缩进 + 浅底表示从属关系）
        if (expanded) {
            when {
                replies.isEmpty() && replyLoading -> Text(
                    "加载回复…", fontSize = 9.sp, color = TextSecondary,
                    modifier = Modifier.padding(start = 26.dp, top = 4.dp),
                )
                replies.isEmpty() -> Text(
                    "暂无回复", fontSize = 9.sp, color = TextSecondary,
                    modifier = Modifier.padding(start = 26.dp, top = 4.dp),
                )
                else -> Column(Modifier.padding(start = 12.dp, top = 1.dp)) {
                    replies.forEach { r -> ReplyRow(r) { onReplyLike(r) } }
                    // ★ 2026-10-18：楼中楼分页入口（用户口径「回复评论的评论限制十条加载」）。
                    //   floor/get 一页只有 10~20 条，旧实现拉完第一页就没有下文；现在只要服务端
                    //   说还有下一页（data.hasMore），就在回复列表末尾给一条明确的「加载更多回复」，
                    //   点一下按 data.time 游标继续翻页（详见 loadMoreReplies）。
                    if (replyHasMore) {
                        Text(
                            if (replyMoreLoading) "加载中…"
                            else "加载更多回复" +
                                (replyCount - replies.size).takeIf { it > 0 }?.let { "（还有 $it 条）" }.orEmpty(),
                            fontSize = 9.sp, color = Accent,
                            modifier = Modifier
                                .padding(start = 14.dp, top = 5.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Accent.copy(alpha = 0.10f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    enabled = !replyMoreLoading,
                                    onClick = onLoadMoreReplies,
                                )
                                .padding(horizontal = 9.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 二级回复行：无头像大图，缩进浅底；点赞可点 */
@Composable
private fun ReplyRow(r: CommentItem, onLike: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            // 回复行弱底：跟随主题（深色主题→白 5%，浅色主题→黑 5%）
            .background(TextPrimary.copy(alpha = 0.05f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CoverImage(r.avatar, 16.dp, shape = CircleShape)
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.user, fontSize = 8.sp, color = TextSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onLike,
                        )
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (r.mine) NcmIcons.HeartFill else NcmIcons.Heart,
                        contentDescription = if (r.mine) "取消赞" else "点赞",
                        tint = if (r.mine) Accent else TextSecondary,
                        modifier = Modifier.size(10.dp),
                    )
                    if (r.liked > 0) {
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "${r.liked}", fontSize = 8.sp,
                            color = if (r.mine) Accent else TextSecondary,
                        )
                    }
                }
            }
            // 楼中楼里「回复某人」：接口 beReplied[0].user.nickname 带出被回复者，内联弱化显示
            val body = if (r.replyToUser.isEmpty()) {
                AnnotatedString(r.content)
            } else {
                buildAnnotatedString {
                    withStyle(SpanStyle(color = Accent.copy(alpha = 0.85f))) {
                        append("回复 @${r.replyToUser}")
                    }
                    append("：")
                    append(r.content)
                }
            }
            Text(
                body, fontSize = 10.sp, color = TextPrimary, lineHeight = 13.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
