package com.netmusiclite.ui.screens.friends

import com.netmusiclite.ui.m3e.adaptiveAnimateScrollToItem
import com.netmusiclite.ui.m3e.adaptiveScrollToItem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.basicMarquee
import com.netmusiclite.ui.m3e.AdaptiveLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.netmusiclite.data.ChatMessage
import com.netmusiclite.data.AppearancePrefs
import com.netmusiclite.data.ChatInvite
import com.netmusiclite.data.ChatSession
import com.netmusiclite.data.FriendInfo
import com.netmusiclite.data.ListenSession
import com.netmusiclite.data.MsgOrderStore
import com.netmusiclite.data.NcmApi
import com.netmusiclite.data.PageCache
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.SessionStore
import com.netmusiclite.data.Song
import com.netmusiclite.ui.components.CircleIconButton
import com.netmusiclite.ui.components.CoverImage
import com.netmusiclite.ui.components.InviteCaption
import com.netmusiclite.ui.components.ListenInviteCard
import com.netmusiclite.ui.components.copyOnLongPress
import com.netmusiclite.ui.components.inviteCaption
import com.netmusiclite.ui.components.listenInviteOf
import com.netmusiclite.ui.components.songCoverModel
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.StackedCard
import com.netmusiclite.ui.components.StackedCardList
import com.netmusiclite.ui.components.StackedIconCard
import com.netmusiclite.ui.components.rotaryList
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.NavMotionSpec
import com.netmusiclite.ui.nav.navMotionSource
import com.netmusiclite.ui.nav.navigateWithMotion
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.screenBg
import com.netmusiclite.ui.theme.OnAccent
import com.netmusiclite.ui.theme.SurfaceGlass
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import com.netmusiclite.ui.m3e.M3eContactRow
import com.netmusiclite.ui.m3e.M3eChatMessage
import com.netmusiclite.ui.m3e.M3eChatComposer
import androidx.compose.foundation.interaction.MutableInteractionSource
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 好友与私信。
 * ★ 2026-09-11 起私信接**网易 web 端真实接口**（msg 私信四端点，见 NcmApi.msgSessions/msgHistory/msgSend）：
 * 会话列表 = 最近联系人（含未读数），聊天页 = 真实历史 + 真实投递。
 * 旧版 ChatStore 是纯内存回显（消息发不出去、也收不到），已整体移除 —— 假成功比失败更糟。
 */
@Composable
fun FriendsScreen(nav: NavHostController) {
    // 秒显缓存 + 后台刷新（2026-09-25）：从聊天页右滑返回时不再白屏等两个串行网络请求
    var friends by remember { mutableStateOf(PageCache.cachedFriends(SessionStore.uid).orEmpty()) }
    var sessions by remember { mutableStateOf(PageCache.cachedSessions().orEmpty()) }
    var loading by remember { mutableStateOf(PageCache.cachedFriends(SessionStore.uid) == null) }

    LaunchedEffect(Unit) {
        friends = PageCache.friends(SessionStore.uid)
        sessions = PageCache.msgSessions().orEmpty() // 私信接口不可用时为空，不影响好友列表
        loading = false
    }

    val unread = sessions.sumOf { it.unread }

    StackedCardList(title = "我的好友", progressTotal = friends.size + 1, horizontalInsetPx = 10) {
        if (loading) {
            item(key = "loading") {
                Text("加载中…", fontSize = 11.sp, color = TextSecondary,
                    modifier = Modifier.fillMaxWidth().padding(top = 140.dp), textAlign = TextAlign.Center)
            }
        } else {
            item(key = "messages") {
                StackedIconCard(
                    title = "私信",
                    subtitle = when {
                        unread > 0 -> "$unread 条未读"
                        sessions.isNotEmpty() -> "${sessions.size} 个会话"
                        else -> "暂无会话"
                    },
                    icon = NcmIcons.CommentIc,
                    navMotionKind = NavMotionKind.Pill,
                    onClick = { nav.navigateWithMotion(Routes.MESSAGES) },
                )
            }
            if (friends.isEmpty()) {
                item(key = "empty") {
                    Text("暂无好友\n网易云关注后即出现在这里", fontSize = 11.sp, color = TextSecondary,
                        modifier = Modifier.fillMaxWidth().padding(top = 60.dp), textAlign = TextAlign.Center)
                }
            } else {
                items(friends, key = { it.id }) { f ->
                    val s = sessions.firstOrNull { it.userId == f.id }
                    if (AppearancePrefs.isM3e) {
                        M3eContactRow(f.name, f.avatarUrl, s?.lastText,
                            if (s != null && s.unread > 0) "${s.unread} 未读" else "聊天",
                            onClick = { nav.navigateWithMotion(Routes.friendChat(f.id)) },
                            onProfile = { nav.navigateWithMotion(Routes.userPage(f.id)) })
                        return@items
                    }
                    StackedCard(
                        onClick = { nav.navigateWithMotion(Routes.friendChat(f.id)) },
                        navMotionKind = NavMotionKind.Pill,
                    ) {
                        Box(Modifier.size(36.dp).navMotionSource(
                            NavMotionKind.Circle,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { nav.navigateWithMotion(Routes.userPage(f.id)) }) {
                            CoverImage(f.avatarUrl, 36.dp, shape = CircleShape)
                        }
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, fontSize = 12.sp, color = TextPrimary, maxLines = 1)
                            if (!s?.lastText.isNullOrEmpty()) {
                                Text(s.lastText, fontSize = 9.sp, color = TextSecondary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (s != null && s.unread > 0) {
                            // ★ 2026-10-01：未读角标底色是主题色实底，白字在白色主题色下不可读 —— 走 OnAccent 自适应
                            Text("${s.unread}", fontSize = 9.sp, color = OnAccent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Accent)
                                    .padding(horizontal = 6.dp, vertical = 2.dp))
                        } else {
                            Text("聊天", fontSize = 9.sp, color = TextSecondary)
                        }
                    }
                }
            }
        }
    }
}

/** 私信会话列表（最近联系人）。接口不可用时明确说明，不假装成空列表 */
@Composable
fun MessagesScreen(nav: NavHostController) {
    // 有缓存快照时直接显示（不再闪「加载中」），Raw 保留失败信号用于首次进页提示
    var sessions by remember { mutableStateOf<List<ChatSession>?>(PageCache.cachedSessions()) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val r = PageCache.msgSessionsRaw()
        failed = r == null && sessions == null
        if (r != null) sessions = r
    }

    StackedCardList(title = "私信", titleSubtitle = "网易云站内信", progressTotal = sessions?.size ?: -1) {
        val list = sessions
        when {
            AppearancePrefs.isM3e && failed -> item(key = "m3e-fail") {
                androidx.wear.compose.material3.Text("私信接口暂不可用\n（网易云可能已下线该功能）",
                    style = androidx.wear.compose.material3.MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
            }
            list == null -> item(key = "loading") {
                Text("加载中…", fontSize = 11.sp, color = TextSecondary,
                    modifier = Modifier.fillMaxWidth().padding(top = 140.dp), textAlign = TextAlign.Center)
            }
            failed -> item(key = "fail") {
                Text("私信接口暂不可用\n（网易云可能已下线该功能）", fontSize = 11.sp, color = TextSecondary,
                    modifier = Modifier.fillMaxWidth().padding(top = 120.dp), textAlign = TextAlign.Center)
            }
            list.isEmpty() -> item(key = "empty") {
                Text("暂无会话\n到好友页选一位点「聊天」", fontSize = 11.sp, color = TextSecondary,
                    modifier = Modifier.fillMaxWidth().padding(top = 120.dp), textAlign = TextAlign.Center)
            }
            else -> items(list, key = { it.userId }) { s ->
                if (AppearancePrefs.isM3e) {
                    // ★ 2026-10-22 用户口径⑤：私信会话列表同样支持点头像进主页（与好友列表一致）
                    M3eContactRow(s.name, s.avatarUrl, s.lastText.ifEmpty { "—" },
                        if (s.unread > 0) "${s.unread} 未读" else shortTime(s.timeMs),
                        onClick = { nav.navigateWithMotion(Routes.friendChat(s.userId)) },
                        onProfile = { nav.navigateWithMotion(Routes.userPage(s.userId)) })
                    return@items
                }
                StackedCard(
                    onClick = { nav.navigateWithMotion(Routes.friendChat(s.userId)) },
                    navMotionKind = NavMotionKind.Pill,
                ) {
                    CoverImage(s.avatarUrl, 36.dp, shape = CircleShape)
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontSize = 12.sp, color = TextPrimary, maxLines = 1)
                        Text(s.lastText.ifEmpty { "—" }, fontSize = 9.sp, color = TextSecondary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (s.unread > 0) {
                        // ★ 2026-10-01：同上 —— 私信会话列表未读角标改用 OnAccent
                        Text("${s.unread}", fontSize = 9.sp, color = OnAccent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Accent)
                                .padding(horizontal = 6.dp, vertical = 2.dp))
                    } else {
                        Text(shortTime(s.timeMs), fontSize = 9.sp, color = TextSecondary)
                    }
                }
            }
        }
    }
}

/**
 * 聊天页：真实历史 + 真实投递。
 * 输入区为贴合屏幕弧度的弯曲胶囊，「+」在最左（一起听 / 分享当前歌），发送键在右端。
 * 滚到顶部自动加载更早的消息；发送成功后本地乐观追加，随下次刷新与服务端对齐。
 */
@Composable
fun ChatScreen(nav: NavHostController, friendId: Long) {
    var friend by remember { mutableStateOf<FriendInfo?>(null) }
    var msgs by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var noMore by remember { mutableStateOf(false) }
    // 已上报过已读的最后一条消息 key（null = 本次进入还没报过）。失败不写入 → 下轮重试
    var lastReadKey by remember(friendId) { mutableStateOf<String?>(null) }
    // 已发出但服务端还没回传的消息（乐观显示），刷新后自动清理
    val pending = remember(friendId) { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf(TextFieldValue("")) }
    var menuOpen by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    suspend fun refresh(limit: Int = 40) {
        val h = NcmApi.msgHistory(friendId, limit = limit) ?: run { failed = true; return }
        failed = false
        if (h != msgs) msgs = h // 内容没变不替换，轮询时不做无谓重组
        // 服务端已回传同文案的近期消息 → 本地乐观那条不再重复显示。
        // 刻意不按方向过滤：方向判定若失效，己方回传消息会被误判成对方，双条比误清更伤；
        // 时间窗（近 10 分钟 + 1 分钟时钟容差）保证不会清掉更早的历史同名消息
        val now = System.currentTimeMillis()
        val serverTexts = h.filter { it.timeMs in (now - 10 * 60_000L)..(now + 60_000L) }
            .map { it.text.trim() }
        pending.removeAll { it.text.trim() in serverTexts }
        // 先把最新一条的真实消息时间落盘：lastmsg/update 会把服务端 lastMsgTime 改成当前时间，
        // 列表排序靠这条记录抵消（见 MsgOrderStore）。
        h.lastOrNull()?.let { MsgOrderStore.record(friendId, it.id, it.timeMs) }
        // 标记已读：首次进入 + 每出现新消息时上报一次（官方 PrivateMsgDetailActivity 同款
        // lastmsg/update）。成功后让会话缓存失效 —— 否则返回好友/私信列表时吃的还是旧快照，
        // 服务端 newMsgCount 已清零、角标却仍挂着。
        val readKey = h.lastOrNull()?.let { "${it.id}_${it.timeMs}" }.orEmpty()
        if (lastReadKey != readKey && NcmApi.msgMarkRead(friendId)) {
            lastReadKey = readKey
            PageCache.markSessionRead(friendId)
        }
    }

    LaunchedEffect(friendId) {
        var f = PageCache.friends(SessionStore.uid).firstOrNull { it.id == friendId }
        if (f == null) {
            NcmApi.msgSessions()?.firstOrNull { it.userId == friendId }
                ?.let { f = FriendInfo(it.userId, it.name, it.avatarUrl) }
        }
        friend = f
        refresh()
        loading = false
        // 轻量轮询：私信没有推送，对方回复 10s 内自动出现（离开页面协程自动取消）
        while (isActive) {
            kotlinx.coroutines.delay(10_000)
            refresh()
        }
    }
    LaunchedEffect(toast) { if (toast != null) { kotlinx.coroutines.delay(1600); toast = null } }

    val display = (msgs + pending).sortedBy { it.timeMs }
    // ★ 2026-10-23 需求①：「发起一起听」改成发**完整分享链接**，链接同时进剪贴板兜底
    //   （私信接口失败时用户可手动粘贴；见下方 CircleIconButton 的注释）。
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // 按最后一条消息的身份决定是否跟随滚底：新消息（自己发/对方来）滚底，
    // 加载更早（头部插入，末条不变）不触发 —— 旧版按 size 触发，翻历史会被拉回底部
    val lastMsgKey = display.lastOrNull()?.let { "${it.id}_${it.timeMs}" }
    LaunchedEffect(lastMsgKey) {
        if (display.isNotEmpty()) listState.adaptiveAnimateScrollToItem(display.size) // 末尾有 pad 项
    }

    // 滚到顶部 → 加载更早（首项为状态占位，消息从索引 1 开始）
    LaunchedEffect(listState, friendId) {
        snapshotFlow { com.netmusiclite.ui.m3e.listFirstIndex(listState) }
            .collect { idx ->
                if (idx <= 1 && !loadingMore && !noMore && !loading && msgs.isNotEmpty()) {
                    loadingMore = true
                    val page = NcmApi.msgHistory(friendId, before = msgs.first().timeMs)
                    if (page.isNullOrEmpty()) noMore = true
                    else msgs = (page + msgs).distinctBy { it.id }.sortedBy { it.timeMs }
                    loadingMore = false
                }
            }
    }

    fun sendMessage() {
        val text = input.text.trim()
        if (text.isNotEmpty()) {
            input = TextFieldValue("")
            scope.launch {
                if (NcmApi.msgSend(friendId, text)) {
                    PageCache.invalidateMsgSessions()
                    pending.add(ChatMessage(0L, text, System.currentTimeMillis(), true))
                } else {
                    toast = "发送失败（私信接口不可用）"
                    input = TextFieldValue(text) // 回填，别让用户白打一遍
                }
            }
        }
    }

    // ★ 2026-10-24 需求④：私信里认出的「一起听邀请」→ 点卡片直接进房。
    //   joiningMsgId 记住是哪一条在进房：卡片半透明 + 文案变「进房中…」，
    //   并且期间忽略重复点击 —— join 是串行请求，连点三下会撞在第一次的半路上。
    var joiningMsgId by remember { mutableStateOf(-1L) }
    fun joinRoom(inv: ChatInvite, msg: ChatMessage) {
        if (joiningMsgId >= 0L) return
        if (ListenSession.active) {
            toast = "已经在一个一起听房间里了，先退出再进这个"
            return
        }
        scope.launch {
            joiningMsgId = msg.id
            val ok = runCatching { ListenSession.join(inv.code) }.getOrDefault(false)
            joiningMsgId = -1L
            if (ok) nav.navigateWithMotion(Routes.LISTEN_ROOM, NavMotionSpec(NavMotionKind.Pill))
            else toast = if (ListenSession.statusText.isBlank()) "进房失败" else ListenSession.statusText
        }
    }

    Box(Modifier.fillMaxSize().background(screenBg()).rotaryList(listState)) {
        // 中间输入框的弧形胶囊底（仅中段：90°±26°）；两端按钮各自独立圆形
        // ★ 2026-10-18 用户口径「经典主题方表时私信输入框依旧是弯的」：方表不再画弧带，
        //   改由下方直胶囊 Row 自带胶囊底（见 classic 方表分支）。
        if (!AppearancePrefs.isM3e && com.netmusiclite.ui.components.isRoundScreen()) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val r = 96.dp.toPx()
            drawArc(
                color = Color.White.copy(alpha = 0.10f),
                startAngle = 64f, sweepAngle = 52f, useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset((size.width - r * 2) / 2f, (size.height - r * 2) / 2f),
                size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    34.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        }

        Column(Modifier.fillMaxSize()) {
            // 对方名字改为悬浮字（不占列表空间）：在根 Box 顶层渲染，列表直接从顶部起
            // 消息流：m3e 下必须用 SINK 模式（★ 2026-10-24 需求②「消息不允许互相堆叠」）。
            //   默认 WEAR 会用 transformedHeight **压缩每条的占位高度**，气泡实际内容比槽位高
            //   就溢出、压到邻居 = 用户看到的「堆叠」；和评论区 2026-10-17 那次是同一个根因
            //   （见 CommentsScreen 的 mode = M3eListMode.SINK）。SINK = 占真实高度 +
            //   下沉由气泡整行挂 m3eSinkFromCenter()。经典主题忽略此参数。
            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
                mode = com.netmusiclite.ui.m3e.M3eListMode.SINK,
            ) {
                item(key = "head") {
                    if (AppearancePrefs.isM3e) {
                        androidx.wear.compose.material3.ListHeader(Modifier.fillMaxWidth(),
                            transformation = com.netmusiclite.ui.m3e.LocalItemTransformation.current) {
                            androidx.wear.compose.material3.Text(friend?.name ?: "私信")
                        }
                    }
                    Text(
                        when {
                            loading -> "加载中…"
                            failed -> "私信接口暂不可用"
                            loadingMore -> "加载更早…"
                            noMore && display.isNotEmpty() -> "没有更早的消息了"
                            display.isEmpty() -> "还没有消息，说点什么"
                            else -> ""
                        },
                        fontSize = 9.sp, color = TextSecondary,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                itemsIndexedMessages(
                    display, friend?.avatarUrl,
                    // ★ 2026-10-22 用户口径⑤：m3e 私信点对方头像进好友主页 ——
                    //   与好友列表 M3eContactRow 的 onProfile 走同一条路由（Routes.userPage）。
                    //   经典主题不使用（该分支的头像保持原样，不在本次需求范围）。
                    onProfile = { nav.navigateWithMotion(Routes.userPage(friendId)) },
                    // ★ 2026-10-24 需求④：邀请卡的副标题要知道「谁发的」
                    peerName = friend?.name ?: "对方",
                    joiningId = joiningMsgId,
                    onJoin = { inv, m -> joinRoom(inv, m) },
                ) { s ->
                    if (s.title.isNotEmpty()) {
                        PlayerEngine.playOne(s)
                        nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Pill))
                    } else {
                        // 兜底的链接消息只带歌曲 id，先补全歌曲信息再播
                        scope.launch {
                            val d = runCatching { NcmApi.songDetail(listOf(s.id)) }
                                .getOrDefault(emptyList()).firstOrNull()
                            if (d != null) {
                                PlayerEngine.playOne(d)
                                nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Pill))
                            } else {
                                toast = "打不开这首歌"
                            }
                        }
                    }
                }
                item(key = "pad") { Spacer(Modifier.height(76.dp)) }
            }
        }

        // 对方名字：悬浮字（叠加在消息列表上方，不占布局空间），较原位置上移 30px（=15dp@2.0）
        if (!AppearancePrefs.isM3e) Text(
            friend?.name ?: "私信",
            fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 15.dp)
                .basicMarquee(),
        )

        // 二级菜单（发起一起听 / 分享当前歌），悬在输入胶囊上方
        if (menuOpen) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 66.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(NcmIcons.Together, "发起一起听", {
                    menuOpen = false
                    scope.launch {
                        if (ListenSession.create()) {
                            // ★ 2026-10-23 需求①（用户口径「私信里一起听分享发出去的是房间号，不是完整链接」）：
                            //   改成发 **完整分享链接**（shareLink() = https://st.music.163.com/listen-together/
                            //   share/?songId=..&roomId=..&inviterId=..）：对方在私信里点一下就能进房/在官方
                            //   App 打开，一串裸房号是没法点的。
                            //   ⚠ shareLink() 需要**服务端确认的房号**，未就绪时返回 null —— 这时只提示「未就绪」，
                            //   **绝不退回发房间号**（旧实现就是这里把裸房号发了出去，还会出现「房间号 -」的假房号）。
                            //   链接同时进剪贴板：私信接口抽风时用户还能手动粘贴，不至于白建一个房。
                            val link = ListenSession.shareLink()
                            if (link == null) {
                                toast = "房间信息未就绪，请稍后重试"
                            } else {
                                clipboard.setText(AnnotatedString(link))
                                val ok = NcmApi.msgSend(
                                    friendId,
                                    "我在 NetMusicLite 开了个一起听，点链接一起听：$link",
                                )
                                if (ok) {
                                    PageCache.invalidateMsgSessions()
                                    toast = "邀请已发送（链接已复制）"
                                } else {
                                    toast = "私信发送失败 · 邀请链接已复制"
                                }
                            }
                        } else toast = ListenSession.statusText
                    }
                }, size = 40.dp, iconSize = 18.dp, container = Accent)
                Spacer(Modifier.width(12.dp))
                Text("一起听", fontSize = 9.sp, color = TextSecondary)
                Spacer(Modifier.width(12.dp))
                CircleIconButton(NcmIcons.Send, "分享当前歌", {
                    menuOpen = false
                    val s = PlayerEngine.current
                    if (s == null) {
                        toast = "当前没有正在播放的歌"
                    } else {
                        scope.launch {
                            // 优先发官方歌曲卡片（对方在官方 App 里也能点开），失败退文本+链接
                            val ok = NcmApi.shareSongTo(friendId, s)
                            if (ok) {
                                PageCache.invalidateMsgSessions()
                                toast = "已分享《${s.title}》"
                                refresh()
                            } else {
                                toast = "分享失败（私信接口不可用）"
                            }
                        }
                    }
                }, size = 40.dp, iconSize = 18.dp)
                Spacer(Modifier.width(12.dp))
                Text("分享歌", fontSize = 9.sp, color = TextSecondary)
            }
        }

        // 弧形胶囊上的三个元素：+(最左) / 输入框(中) / 发送(右)
        if (AppearancePrefs.isM3e) {
            // ★ 2026-10-18 用户口径「m3e 私信在圆表下聊天框要呈圆弧胶囊状」：
            //   圆表走 M3eChatComposerArc（贴着表盘弧线的一整条），方表仍用直胶囊。
            if (com.netmusiclite.ui.components.isRoundScreen()) {
                com.netmusiclite.ui.m3e.M3eChatComposerArc(
                    input, { input = it }, menuOpen, { menuOpen = !menuOpen }, { sendMessage() },
                    Modifier.fillMaxSize(),
                )
            } else {
                com.netmusiclite.ui.m3e.M3eChatComposer(
                    input, { input = it }, menuOpen, { menuOpen = !menuOpen }, { sendMessage() },
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
                )
            }
        } else if (!com.netmusiclite.ui.components.isRoundScreen()) {
            // ★ 2026-10-18 用户口径「经典主题私信界面选择方表的时候聊天输入框依旧是弯的
            //   而不是直的」：方表没有圆弧边缘可贴，弧形布局在这里只会显得歪 ——
            //   改成一条水平直胶囊（+ / 输入框 / 发送 三等分），底边距屏底 8dp。
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
                    .width(186.dp)
                    .height(38.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.10f))
                    .padding(horizontal = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIconButton(
                    if (menuOpen) NcmIcons.Close else NcmIcons.Plus, "更多",
                    { menuOpen = !menuOpen },
                    size = 32.dp, iconSize = 14.dp,
                )
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 11.sp, color = TextPrimary),
                    cursorBrush = SolidColor(Accent),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp, vertical = 7.dp),
                    // ★ 2026-10-23 需求③（用户口径「所有文字输入框里的占位文字全部删除，只留空白框」）：
                    //   删掉框内「说点什么…」占位，只保留 inner()。空文本仍按 textStyle 占一行行高，
                    //   外层胶囊 Row 有 height(38.dp) 兜底 ⇒ 不会塌陷。
                    decorationBox = { inner -> Box(Modifier.fillMaxWidth()) { inner() } },
                )
                CircleIconButton(
                    NcmIcons.Send, "发送",
                    { sendMessage() },
                    size = 32.dp, iconSize = 14.dp,
                    tint = Accent,
                )
            }
        } else
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cx = maxWidth / 2
            val cyc = maxHeight / 2
            val rB = 96.dp
            val radL = Math.toRadians((-52).toDouble())
            val lx = cx + rB * kotlin.math.sin(radL).toFloat()
            val ly = cyc + rB * kotlin.math.cos(radL).toFloat()
            CircleIconButton(
                if (menuOpen) NcmIcons.Close else NcmIcons.Plus, "更多",
                { menuOpen = !menuOpen },
                modifier = Modifier.offset {
                    androidx.compose.ui.unit.IntOffset(
                        (lx - 16.dp).roundToPx(), (ly - 16.dp).roundToPx())
                },
                size = 32.dp, iconSize = 14.dp,
            )
            val radR = Math.toRadians(52.0)
            val rx = cx + rB * kotlin.math.sin(radR).toFloat()
            val ry = cyc + rB * kotlin.math.cos(radR).toFloat()
            CircleIconButton(
                NcmIcons.Send, "发送",
                { sendMessage() },
                modifier = Modifier.offset {
                    androidx.compose.ui.unit.IntOffset(
                        (rx - 16.dp).roundToPx(), (ry - 16.dp).roundToPx())
                },
                size = 32.dp, iconSize = 14.dp,
                tint = Accent,
            )
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 11.sp, color = TextPrimary),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 7.dp)
                    .width(112.dp)
                    .clip(RoundedCornerShape(50))
                    .clickable(enabled = false) {}
                    .padding(horizontal = 4.dp, vertical = 7.dp),
                // ★ 2026-10-23 需求③：删除框内「说点什么…」占位（只留空白框）。
                //   圆表这条输入框靠 bottom padding + 弧带定位，高度由一行行高撑住，不会塌陷。
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { inner() }
                },
            )
        }

        toast?.let {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(it, fontSize = 11.sp, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC2B2B33))
                        .padding(horizontal = 16.dp, vertical = 7.dp))
            }
        }
    }
}

/** 文本消息里若含网易云单曲链接，取出歌曲 id（用于把兜底的链接消息也做成可点开） */
private fun songIdFromText(t: String): Long =
    Regex("song\\?id=(\\d+)").find(t)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

/**
 * 消息流：己方右对齐主色气泡，对方左对齐玻璃气泡（左侧带对方小头像，两边分布一眼可辨）；
 * 间隔超过 10 分钟插一条时间行。歌曲消息渲染成**可点卡片**（封面 + 歌名艺人，点击即播），
 * 文本消息里若含单曲链接也可点。乐观追加的未确认消息（id=0）半透明显示 = 发送中。
 *
 * ★ 2026-10-24 两条新口径：
 * ③ **长按任意气泡 = 复制**（[copyOnLongPress]，两主题都做）：弹「复制全文 / 选文字」面板；
 * ④ **一起听邀请自动成卡**（[listenInviteOf]）：正文里认出分享链接/房号时，
 *    不再显示那串几百字符的 URL，改画 [com.netmusiclite.ui.components.ListenInviteCard]，
 *    点卡片直接 `ListenSession.join()` 进房，卡下面留一行截断短链。
 */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedMessages(
    display: List<ChatMessage>,
    friendAvatar: String?,
    /**
     * 点对方头像 → 好友主页。null = 头像不可点（经典主题 / 其它调用方）。
     * ★ 2026-10-22 放第三个参数（onOpenSong 之前）以便调用方继续用尾随 lambda 传 onOpenSong。
     */
    onProfile: (() -> Unit)? = null,
    /** 邀请卡副标题里的发言人昵称（★ 2026-10-24 需求④） */
    peerName: String = "对方",
    /** 正在进房的那条消息 id（-1 = 没有）；同一条卡片显示「进房中…」并忽略重复点击 */
    joiningId: Long = -1L,
    onJoin: (ChatInvite, ChatMessage) -> Unit = { _, _ -> },
    onOpenSong: (Song) -> Unit,
) {
    items(display.size, key = { i -> "${display[i].id}_$i" }) { i ->
        val msg = display[i]
        val prev = display.getOrNull(i - 1)
        if (prev == null || msg.timeMs - prev.timeMs > 10 * 60_000L) {
            Text(
                shortTime(msg.timeMs), fontSize = 9.sp, color = TextSecondary,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                textAlign = TextAlign.Center,
            )
        }
        val sending = msg.id == 0L && msg.fromMe // 乐观追加、服务端未确认
        val alpha = if (sending) 0.6f else 1f
        val align = if (msg.fromMe) Alignment.CenterEnd else Alignment.CenterStart
        val bubble = if (msg.fromMe) Accent.copy(alpha = 0.8f) else SurfaceGlass
        // ★ 2026-10-01 可读性修复（用户报「白色主题色下私信界面依旧不可读」）：
        //   己方气泡底色是主题色实底 —— 主题色选白色/浅色时必须把前景切成深色，
        //   否则「白字 + 白气泡」整体糊掉。OnAccent 已按 Accent 亮度自适应（亮→深字）。
        val onBubble = if (msg.fromMe) OnAccent else TextPrimary
        val onBubbleSub = if (msg.fromMe) OnAccent.copy(alpha = 0.72f) else TextSecondary
        val song = msg.song
        // ★ 2026-10-18 m3e 私信「同一人连续多条消息合并」：
        //   组首 = 上一条不存在 / 换了发言人 / 间隔超过 5 分钟（与上面的 10 分钟时间行分开）。
        //   头像只画在组首那条上，30dp、与气泡顶部对齐（口径见 M3eChatMessage 的 KDoc）。
        //   间距本身不再区分组内/组间（2026-10-24 需求②：每条上下各 5dp、左右 10dp），
        //   合并只体现在「共用头像」这一点上。
        val groupHead = prev == null || prev.fromMe != msg.fromMe ||
            msg.timeMs - prev.timeMs > 5 * 60_000L
        // 歌曲卡片优先：同一条消息里既有歌又有链接时，卡片本来就是可点的，不再叠邀请卡。
        // 官方 App 的邀请是 bodyType=23 的卡片（非链接）：房号由 [NcmApi.msgHistory] 预先
        // 解析进 msg.invite，正文里没有 URL，所以先看结构化字段，再退回正文识别。
        val invite = msg.invite ?: if (song == null) listenInviteOf(msg.text) else null
        val inviteSub = if (msg.fromMe) "我发起的一起听 · 点卡片进房"
            else "$peerName 邀请你 · 点卡片进房"
        if (AppearancePrefs.isM3e) {
            M3eChatMessage(
                msg, friendAvatar, songIdFromText(msg.text), onOpenSong, groupHead, onProfile,
                invite, joiningId == msg.id, { invite?.let { onJoin(it, msg) } }, inviteSub,
            )
            return@items
        }
        // 头像只挂在对方一侧；自己侧留等宽空白，两侧气泡不对齐到屏幕边缘
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!msg.fromMe) {
                CoverImage(friendAvatar, 20.dp, shape = CircleShape)
                Spacer(Modifier.width(6.dp))
            }
            Box(Modifier.weight(1f).graphicsLayer { this.alpha = alpha }, contentAlignment = align) {
                if (invite != null) {
                    // ★ 2026-10-24 需求④：一起听邀请 → 可点卡片 + 一行短链（长按复制原文）
                    Column(horizontalAlignment = if (msg.fromMe) Alignment.End else Alignment.Start) {
                        ListenInviteCard(
                            subtitle = inviteSub,
                            busy = joiningId == msg.id,
                            onJoin = { onJoin(invite, msg) },
                            onCopyText = msg.text,
                        )
                        InviteCaption(inviteCaption(invite.code), msg.text)
                    }
                } else if (song != null) {
                    // 歌曲卡片：点击直接播放，长按复制（★ 需求③）
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(bubble)
                            .copyOnLongPress(
                                msg.text.ifEmpty { "${song.title} - ${song.artist}" },
                            ) { onOpenSong(song) }
                            .padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverImage(songCoverModel(song), 30.dp, shape = RoundedCornerShape(6.dp))
                        Spacer(Modifier.width(7.dp))
                        Column(Modifier.width(112.dp)) {
                            Text(song.title, fontSize = 10.sp, color = onBubble,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artist.ifEmpty { "单曲" }, fontSize = 9.sp,
                                color = onBubbleSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(NcmIcons.Play, contentDescription = "播放",
                            tint = onBubble, modifier = Modifier.size(13.dp))
                    }
                } else {
                    val linkId = songIdFromText(msg.text)
                    Text(
                        msg.text.ifEmpty { "[消息]" },
                        fontSize = 10.sp,
                        color = onBubble,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(bubble)
                            // 单击=打开歌链接（有才生效），长按=复制面板（★ 需求③）。
                            // 合成同一个 combinedClickable，不另起 pointerInput 层。
                            .copyOnLongPress(msg.text) {
                                if (linkId > 0L) onOpenSong(Song(linkId, "", "", 0L, "", 0L, 0, null))
                            }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            if (msg.fromMe) Spacer(Modifier.width(26.dp)) // 与对方头像列等宽，保持视觉对称
        }
    }
}

/** 会话/消息时间：今天显示时刻，昨天显示「昨天」，更早显示月-日 */
private fun shortTime(ms: Long): String {
    if (ms <= 0L) return ""
    val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    if (sameDay(c, java.util.Calendar.getInstance())) {
        return String.format("%02d:%02d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }
    val yesterday = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
    if (sameDay(c, yesterday)) return "昨天"
    return String.format("%02d-%02d",
        c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
}

private fun sameDay(a: java.util.Calendar, b: java.util.Calendar): Boolean =
    a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
        a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)

/**
 * 分享当前歌曲：好友选择页。
 * 播放页「更多」只有 6 个圆钮的位置，塞不下好友列表 —— 所以分享按钮跳到这一页来选人，
 * 选中即发官方歌曲卡片私信。标题副标题显示要分享的歌，避免选错人。
 */
@Composable
fun SharePickScreen(nav: NavHostController) {
    val song = PlayerEngine.current
    var friends by remember { mutableStateOf<List<FriendInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var sending by remember { mutableStateOf<Long?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        friends = NcmApi.friends(SessionStore.uid)
        loading = false
    }
    LaunchedEffect(toast) { if (toast != null) { kotlinx.coroutines.delay(1600); toast = null } }

    Box(Modifier.fillMaxSize().background(screenBg())) {
        StackedCardList(
            title = "分享给",
            titleSubtitle = song?.title ?: "无正在播放的歌",
            progressTotal = friends.size,
        ) {
            if (loading) {
                item(key = "loading") {
                    Text("加载中…", fontSize = 11.sp, color = TextSecondary,
                        modifier = Modifier.fillMaxWidth().padding(top = 140.dp), textAlign = TextAlign.Center)
                }
            } else if (song == null) {
                item(key = "nosong") {
                    Text("当前没有正在播放的歌", fontSize = 11.sp, color = TextSecondary,
                        modifier = Modifier.fillMaxWidth().padding(top = 140.dp), textAlign = TextAlign.Center)
                }
            } else if (friends.isEmpty()) {
                item(key = "empty") {
                    Text("暂无好友\n网易云关注后即出现在这里", fontSize = 11.sp, color = TextSecondary,
                        modifier = Modifier.fillMaxWidth().padding(top = 140.dp), textAlign = TextAlign.Center)
                }
            } else {
                val track = song // 上一分支已判定非空
                items(friends, key = { it.id }) { f ->
                    StackedCard(onClick = {
                        if (sending != null) return@StackedCard
                        sending = f.id
                        scope.launch {
                            val ok = NcmApi.shareSongTo(f.id, track)
                            sending = null
                            toast = if (ok) "已分享《${track.title}》给 ${f.name}"
                            else "分享失败（私信接口不可用）"
                        }
                    }) {
                        CoverImage(f.avatarUrl, 36.dp, shape = CircleShape)
                        Spacer(Modifier.width(11.dp))
                        Text(f.name, fontSize = 12.sp, color = TextPrimary, maxLines = 1,
                            modifier = Modifier.weight(1f))
                        Text(if (sending == f.id) "发送中" else "发送", fontSize = 9.sp, color = TextSecondary)
                    }
                }
            }
        }
        toast?.let {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(it, fontSize = 11.sp, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC2B2B33))
                        .padding(horizontal = 16.dp, vertical = 7.dp))
            }
        }
    }
}
