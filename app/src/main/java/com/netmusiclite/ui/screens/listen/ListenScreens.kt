package com.netmusiclite.ui.screens.listen

import com.netmusiclite.ui.m3e.m3eSwipeLeft
import com.netmusiclite.ui.m3e.adaptiveAnimateScrollToItem
import com.netmusiclite.ui.m3e.adaptiveScrollToItem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import com.netmusiclite.ui.m3e.AdaptiveLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.netmusiclite.data.ArtistItem
import com.netmusiclite.data.FriendInfo
import com.netmusiclite.data.ListenSession
import com.netmusiclite.data.NcmApi
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.SessionStore
import com.netmusiclite.data.Song
import com.netmusiclite.ui.components.CircleIconButton
import com.netmusiclite.ui.components.CoverImage
import com.netmusiclite.ui.components.DialogScrim
import com.netmusiclite.ui.components.EdgeProgressRing
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.NcmToast
import com.netmusiclite.ui.components.Pressable
import com.netmusiclite.ui.components.pressStretch
import com.netmusiclite.ui.components.RotatingCover
import com.netmusiclite.ui.components.StackedCard
import com.netmusiclite.ui.components.StackedCardList
import com.netmusiclite.ui.components.StackedIconCard
import com.netmusiclite.ui.components.StackedSongCard
import com.netmusiclite.ui.components.SwipeBackFader
import com.netmusiclite.ui.components.TextInputDialog
import com.netmusiclite.ui.components.circleSafeInsetPx
import com.netmusiclite.ui.components.formatMs
import com.netmusiclite.ui.components.rotaryList
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.NavMotionSpec
import com.netmusiclite.ui.nav.OverlayBackHandler
import com.netmusiclite.ui.nav.TransitionCoordinator
import com.netmusiclite.ui.nav.navigateWithMotion
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.Bg
import com.netmusiclite.ui.theme.BgElevated
import com.netmusiclite.ui.theme.OnAccent
import com.netmusiclite.ui.theme.SurfaceGlass
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 一起听界面（2026-09-11 重写批次 + 反馈修订）
 *
 * 重写要点：入口页改堆叠卡；房间页与播放页同构；右滑返回/左划歌词/上滑聊天。
 *
 * 首轮真机反馈修订（5 条）：
 * 1. 恢复聊天页——上滑进入（此前被误删）。消息走私信桥接真实收发，见 ListenChatStore。
 * 2. 底部左钮（多人图标）改为进入「邀请好友」列表，点好友即发邀请；
 *    原先只复制链接，无法落到具体的人。
 * 3. 修布局重叠——三键上移并收窄（82→76dp），成员行上提到 156dp，
 *    与弧形底栏上缘（184.5dp）留出明确间距（原为 2.5dp，视觉上贴死）。
 * 4. 歌单浮层新增「添加歌曲」——从我喜欢的音乐选曲追加进房间队列。
 * 5. 歌单浮层新增「房间推荐」——来自 sync 响应的 displayList.rcmdSongIds，
 *    官方同款字段，无需额外端点。
 *
 * 2026-09-12 六项反馈批次：
 * 1. 人数走服务端 totalUserNums 口径（roomUsers 可能只回部分成员）；
 * 2. 聊天改私信桥接（真实收发，官方 App 可互见）；
 * 3. 弧形栏加音量键（四钮：邀请/歌单/音量/退出，唤出系统音量面板）；
 * 4. 成员名单显示服务端返回的全部成员 + 总人数说明；
 * 5. 成员名单显示房内累计时长（本地跟踪：他人=在房时长，自己=实际播放时长）；
 * 6. 非房主屏蔽上一首/下一首（歌单点歌同限，防切到空白）。
 *
 * 2026-09-12 一起听同步专项（真机反馈 5 项，详见 项目结构说明.md 四点十九）：
 * 1. 进度同步：人数/成员口径换 roomExt.curUserNums 实时值；房间聊天依赖成员表，同步修复；
 * 2. 封面：房间歌单详情批量拉取失败时退「只拉当前歌」，不再永远空白；
 * 3. 聊天：成员未就绪/私信全失败分文案上屏，失败撤回待确认气泡；
 * 4. 人数：curUserNums 实时口径优先（totalUserNums 偏累计）；
 * 5. 时长：按房间持久化累计（重进接着计）+ 服务端 effectiveDurationMs 房间累计展示。
 */

// ==================== 聊天（房内公共聊天优先，私信仅普通房兜底） ====================

/**
 * 一起听房间消息。
 *
 * ★ 2026-09-13 通道定性（真机 + 匿名参数矩阵双重复现）：
 * - **读**：`listen/together/multi/special/msg/history`（官方 `Lad0/v;->e`）是**唯一**对
 *   乐迷团房（`imType=SELFHOST`）可用的 HTTP 通道，返回房内公共聊天（含进房前历史、
 *   系统提示、其他用户发言）。歌房那套 `middle/im/chatroom/msg/history/query` 对该房型
 *   是网关级 400 空 body —— 旧版日志里的 `End of input at character 0 of` 就是它。
 * - **发**：官方房内发言走云信（NIM）长连接（`MainIMManager.bindIMService` +
 *   `LTChatSendPanel`），`Lad0/v` 里**只有读方法没有写方法**，HTTP 无发送端点
 *   （`multi/special/msg/send` 实测 `404 接口未找到`；`middle/im/chatroom/send` 虽为活路由
 *   但对匿名恒 `301 系统错误`）。故乐迷团房本端只做「实时收」，发言请用官方客户端。
 * - 普通房：房内通道失败后仍保留「逐个成员发私信」的桥接兜底（成员是邀请来的熟人）。
 */
object ListenChatStore {
    data class Msg(
        val text: String,
        val isMe: Boolean,
        val isSystem: Boolean = false,
        /** 服务端消息 id（0 = 本地待确认/系统提示）；房内通道无 id 时用时间戳兜底 */
        val id: Long = 0L,
        val timeMs: Long = System.currentTimeMillis(),
        /** 非己方消息的发送者昵称 */
        val senderName: String = "",
        /** 非己方消息的发送者 uid（引用回复的结构化字段用；0 = 未知） */
        val senderUid: Long = 0L,
        /** 服务端真正的 msgId（房内 `serverExt.msgId`；0 = 未下发，引用只走正文前缀） */
        val serverMsgId: Long = 0L,
        /** 引用回复：被回复者昵称（本地回显；服务端不做结构化回传，刷新后靠正文前缀体现） */
        val replyToName: String = "",
        /** 引用回复：被回复消息正文摘要 */
        val replyToText: String = "",
    )

    private val list = mutableStateListOf<Msg>()
    /** 历史去重键（`uid_sendTime`，两个读通道同构 → 跨通道天然去重） */
    private val seenRoomKeys = HashSet<String>()
    /** 房内公共聊天通道连续未命中的轮数：连续 3 轮空则本轮会话不再探测（省请求） */
    private var roomHistMiss = 0
    private var roomChatDead = false

    fun messages(): List<Msg> = list
    fun clear() {
        list.clear()
        seenRoomKeys.clear()
        roomHistMiss = 0; roomChatDead = false
        runCatching { NcmApi.resetRoomChatProbe() }
    }

    /** 系统提示：加入/退出/远端切歌等，居中灰字 */
    fun system(text: String) = list.add(Msg(text, false, true))

    /**
     * 面板空态文案 —— 区分「真没人说话」和「通道没打通」。
     * 2026-10-24：房内通道究竟哪一条对当前房型有效还在探测期，只写「还没有消息」
     * 会让用户以为是自己没发出去；把判死状态摊到界面上，一眼知道下一步该看哪里。
     */
    fun emptyHint(): String = when {
        roomChatDead && NcmApi.chatRoomHistoryDead ->
            "两条房内通道都被服务端拒了\n（发言不会上屏 · logcat tag=LTDiag 有 crMsgSend/roomRecv）"
        roomChatDead && ListenSession.chatRoomId == null ->
            "拿不到云信聊天室 id\n房内消息通道没建立 · 请退出重开一次房间"
        ListenSession.isFltRoom ->
            "还没有消息\n这里显示乐迷团房的公共聊天\n（含官方客户端用户的发言）"
        else -> "还没有消息\n公共聊天与云信聊天室各 8 秒拉一轮\n（含官方客户端用户的发言）"
    }

    /** 消息上限：只留最近 200 条，避免长时间挂机把列表撑爆（去重集合不裁，裁了会重复上屏） */
    private fun trim() {
        while (list.size > 200) list.removeAt(0)
    }

    /** 引用回复的正文前缀（跨端可见的降级形式，见 [NcmApi.RoomMsgRef] 的为何需要它） */
    private fun quotePrefix(replyTo: Msg): String {
        val snippet = replyTo.text.replace('\n', ' ').trim()
            .let { if (it.length > 24) it.take(24) + "…" else it }
        val name = replyTo.senderName.ifEmpty { "某人" }
        return if (snippet.isEmpty()) "回复 @$name" else "回复 @$name：$snippet"
    }

    /**
     * 房内**信令**的展示口径：`点歌：歌名 - 歌手 [WM#123]` 尾巴上的 `[WM#id]` 是本端
     * 房主自动接受用的机器码（[com.netmusiclite.data.ListenSession.confirmPendingAdds] 的
     * 接力兜底）。房内通道打通后它会明文出现在聊天里 —— 这里只从显示文本去掉，
     * 房主端 watcher 读的是接口原文，不受影响。
     */
    private fun stripSignal(text: String): String =
        text.replace(Regex("""\s*\[WM#\d+]"""), "").trim()

    /**
     * [quotePrefix] 的逆运算：把服务端回读的正文前缀还原成结构化引用。
     * 返回 (被回复者昵称, 引用摘要, 正文)；无前缀时昵称为空、正文原样返回。
     *
     * 为什么必须做：服务端不存引用字段（见上方 DTO 说明），本端发出的引用消息
     * 下一轮回读只剩纯文本 —— 不还原就会显示成「回复 @某人：xxx」一整行糊在气泡里，
     * 而不是引用块 + 正文的两段式。别人用本端 App 发的引用消息同样靠这里还原。
     */
    private fun normalizeQuote(text: String): Triple<String, String, String> {
        val nl = text.indexOf('\n')
        if (nl <= 0) return Triple("", "", text)
        val head = text.substring(0, nl)
        if (!head.startsWith("回复 @")) return Triple("", "", text)
        val rest = head.removePrefix("回复 @")
        val ci = rest.indexOf('：')
        val name = if (ci >= 0) rest.substring(0, ci) else rest
        if (name.isEmpty() || name.length > 24) return Triple("", "", text)
        val snippet = if (ci >= 0) rest.substring(ci + 1) else ""
        return Triple(name, snippet, text.substring(nl + 1))
    }

    /**
     * 发送。**只走房内公共消息通道，不再有任何私信兜底**（2026-10-01 修订；
     * 2026-10-24 补上云信聊天室直发）：
     * ① 云信聊天室直发 `eapi/room/chat/sendMsg`（带 chatRoomId + 云信 token/accid）——
     *    真机确证双人房的发言就在云信聊天室里（`multi/special/msg/history` 对双人房恒空），
     *    旧版只打官方那两条 `middle/im/chatroom` 系路由（恒 301），所以一句也发不出去；
     * ② 官方 dex 实证的 `middle/im/chatroom/send` / `chatroom/special/send` 探测
     *    （乐迷团/直播系房间走这套）；
     * ③ 两条都不通才把失败原因如实上屏。旧版普通房这里会「逐个成员发私信」兜底，
     *    导致房间内发言变成私聊刷屏（用户反馈「房间内发消息发到私信去了」），已整体移除。
     *
     * [replyTo] 非空 = 引用回复房内公共聊天里的某条消息：正文前置一行
     * `回复 @昵称：摘要` —— 服务端不做结构化回传，只有把引用写进正文，本端下一轮
     * 刷新回读才看得见「在回复谁」；结构化引用字段同步进请求体（双保险）。
     */
    suspend fun send(text: String, replyTo: Msg? = null): Boolean {
        val t = text.trim()
        if (t.isBlank()) return false
        val quoted = replyTo?.takeIf { !it.isMe && !it.isSystem }
        val prefix = quoted?.let { quotePrefix(it) }
        val sendText = if (prefix != null) "$prefix\n$t" else t
        val echo = Msg(
            text = sendText, isMe = true,
            replyToName = quoted?.senderName.orEmpty(),
            replyToText = quoted?.text.orEmpty(),
        )
        list.add(echo)
        val rid = ListenSession.roomId
        val ref = quoted?.let {
            NcmApi.RoomMsgRef(
                // 房内通道没下发 serverMsgId 时 id 存的是时间戳（>1e12），不能当 msgId 上报
                msgId = when {
                    it.serverMsgId > 0L -> it.serverMsgId
                    it.id in 1..999_999_999_999L -> it.id
                    else -> 0L
                },
                uid = it.senderUid,
                nickname = it.senderName,
                text = it.text,
            )
        }
        if (rid != null) {
            // 引用时 chatRoomId 可能还没下发（status 恢复会话那一拍）：向中间层要一次
            val cr = ListenSession.chatRoomId
                ?: runCatching { NcmApi.resolveChatRoomId(rid) }.getOrNull()
            val code = runCatching { NcmApi.ltChatroomMsgSend(cr.orEmpty(), rid, sendText, ref) }.getOrNull()
                ?: runCatching {
                    NcmApi.ltRoomMsgSend(rid, cr, sendText, ListenSession.roomType, ref)
                }.getOrNull()
            if (code == 200) {
                trim()
                runCatching { refreshRoomHistory() } // 服务端回执后转正，去掉待确认半透明态
                return true
            }
        }

        // 房内通道没命中：**不再退私信**（2026-10-01 用户明确要求「非私聊」）。
        // 这里只做一件事：把失败原因如实上屏，方便定位（407 = 消息类型/内容被拦，
        // -1 = 空 body 网关拒绝，null = 全部候选组合未命中）。
        list.remove(echo)
        system(
            when {
                rid == null -> "房内消息未发出：当前不在房间里"
                else -> "房内消息未发出：两条房内通道都没接受这条消息（详见 logcat tag=LTDiag 的 crMsgSend / roomMsgSend）"
            }
        )
        return false
    }

    /**
     * 收：**房内公共聊天**（唯一权威通道，含进房前历史、系统提示、他人发言）。
     *
     * 两个通道**每拍都读**（2026-10-24 修订，直接决定「能不能收到对方消息」）：
     * ① `listen/together/multi/special/msg/history`（官方 `Lad0/v;->e`）——
     *    参数硬约束 size=50 / direction=0。真机确证：**双人房这一路恒空**
     *    （1.35 日志 `roomChatHist#1 n=0`），它是乐迷团/SELFHOST 房才有的通道。
     * ② `middle/im/chatroom/msg/history/query`（云信聊天室）——
     *    双人房的房内发言真正落在 `roomInfo.chatRoomId` 那个聊天室里。
     *
     * 旧版把②写成 `if (!hit)` 的兜底：只要①回了**任何**一条（哪怕只是「XX 加入了房间」
     * 这种系统提示），②就永远不查 —— 双人房于是「收不到对方发的消息」。
     * 现在两路并行，跨通道按 `uid_sendTime` 去重（两路同构，天然不会重影），
     * 各自的判死逻辑（multi 连续 3 轮空 / middle/im 连续 2 轮 3 通道全空）仍然省请求。
     *
     * ★ 2026-10-24 同时**删除私信桥接接收**（旧的 `refresh()`：逐成员拉私信历史灌进房间列表）。
     *   那是「歌房消息通道和个人私信混用」的根因 —— 房间面板里会冒出这对好友几个月前
     *   的私聊记录，而真正的房内发言一条也没有。发送侧早在 2026-10-01 就不退私信了，
     *   接收侧当时没跟着删。私信只属于私信页，房间聊天只属于房间通道。
     */
    suspend fun refreshRoomHistory() {
        val rid = ListenSession.roomId ?: return
        val me = SessionStore.uid
        var added = 0

        // ① 房内公共聊天（官方 LT multi 通道）
        if (!roomChatDead) {
            val recs = runCatching { NcmApi.ltRoomMsgHistory(rid) }.getOrDefault(emptyList())
            if (recs.isEmpty()) {
                if (++roomHistMiss >= 3) roomChatDead = true
            } else roomHistMiss = 0
            recs.forEach { m ->
                if (m.key in seenRoomKeys) return@forEach
                seenRoomKeys.add(m.key)
                val q = normalizeQuote(m.text)
                list.add(Msg(
                    text = stripSignal(q.third),
                    isMe = !m.isSystem && m.uid == me,
                    isSystem = m.isSystem,
                    // id 非零 = 已确认的房内消息（不参与本地待确认气泡逻辑）
                    id = if (m.id > 0L) m.id else m.timeMs,
                    timeMs = m.timeMs,
                    senderName = if (m.isSystem || m.uid == me) "" else m.nickname,
                    senderUid = if (m.isSystem) 0L else m.uid,
                    serverMsgId = m.id,
                    replyToName = q.first,
                    replyToText = q.second,
                ))
                added++
            }
        }

        // ② 云信聊天室通道 —— 双人房的发言只有这里会有。
        //    chatRoomId 未下发时（status 恢复会话那一拍）向中间层要一次，别整条通道空转。
        //    请求参数按官方 dex 口径（2026-10-24）：timeStamp 传**当前时间**、limit=100
        //    拉最近一页（传 0 会被当成「epoch 之前」，服务端回 data {}，什么都收不到）；
        //    增量靠 uid_sendTime 去重。
        val chatRoomId = ListenSession.chatRoomId
            ?: runCatching { NcmApi.resolveChatRoomId(rid) }.getOrNull()
        if (chatRoomId != null) {
            val recs = runCatching {
                NcmApi.chatRoomHistory(chatRoomId, rid)
            }.getOrDefault(emptyList())
            recs.forEach { r ->
                val ext = r.optJSONObject("serverExt")
                val uid = r.optLong("fromUserId", ext?.optLong("userId", 0L) ?: 0L)
                val t = r.optLong("sendTime", 0L)
                val text = r.optJSONObject("body")?.optString("msg").orEmpty()
                    .let { if (it == "null") "" else it.trim() }
                if (text.isEmpty() || t <= 0L) return@forEach
                val key = "${uid}_$t"
                if (key in seenRoomKeys) return@forEach
                seenRoomKeys.add(key)
                val q = normalizeQuote(text)
                list.add(Msg(
                    text = stripSignal(q.third),
                    isMe = uid == me,
                    id = t,
                    timeMs = t,
                    senderName = ext?.optString("nickname")?.let { if (it == "null") "" else it } ?: "",
                    senderUid = uid,
                    replyToName = q.first,
                    replyToText = q.second,
                ))
                added++
            }
        } else {
            android.util.Log.i("LTDiag", "roomRecv noChatRoom roomId=$rid")
        }
        if (added > 0) {
            // 「收不到消息」先看这行：n=本轮新增、cr=聊天室 id、multiDead/imDead=两路判死状态
            android.util.Log.i(
                "LTDiag",
                "roomRecv n=$added total=${list.size} cr=$chatRoomId multiDead=$roomChatDead",
            )
        }
        // 一条都没新增时不必重排/清理（列表已经是有序的），省掉每拍两次全表遍历
        if (added == 0 && list.isNotEmpty()) return
        list.sortBy { it.timeMs }
        // 走房内通道发出的消息：服务端回执后撤掉本地待确认气泡（否则同一句话会重影两条：
        // 一条半透明的 id=0 回显 + 一条服务端回读的正式消息）。
        // ⚠ 引用回复时 echo.text = "回复 @某人：摘要\n正文"，而回读已被 normalizeQuote 剥成「正文」，
        //   所以不能只比全等，还要比「正文是回声的后缀」这两种形式。
        val delivered = list.filter { !it.isSystem && it.id > 0L && it.isMe }.map { it.text }
        if (delivered.isNotEmpty()) {
            list.removeAll { m ->
                !m.isSystem && m.id == 0L && m.isMe &&
                    delivered.any { d -> d == m.text || m.text.endsWith("\n$d") || d.endsWith("\n${m.text}") }
            }
        }
        trim()
    }
}

// ==================== 入口页：堆叠卡 ====================

@Composable
fun ListenTogetherScreen(nav: NavHostController) {
    var showJoin by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // 乐迷团验证：搜艺人 → 点艺人 → 查该艺人乐迷团是否正在一起听 → 有则加入
    var fltQuery by remember { mutableStateOf(TextFieldValue("")) }
    var fltArtists by remember { mutableStateOf<List<ArtistItem>>(emptyList()) }
    var fltSearching by remember { mutableStateOf(false) }
    // 每个艺人的验证结果：uid -> (state, info)。state: 1=验证中 2=正在一起听 3=没在听 4=接口不可达/无团
    var fanProbe by remember { mutableStateOf<Map<Long, Pair<Int, NcmApi.FanGroupListen?>>>(emptyMap()) }
    var joining by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { runCatching { ListenSession.tryRestore() } }
    LaunchedEffect(toast) { if (toast != null) { delay(2200); toast = null } }
    // 搜索防抖：只取艺人（乐迷团房按艺人归组）
    LaunchedEffect(fltQuery.text) {
        val kw = fltQuery.text.trim()
        if (kw.isEmpty()) { fltArtists = emptyList(); fltSearching = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(350)
        fltSearching = true
        fltArtists = runCatching { NcmApi.search(kw, limit = 10).artists }.getOrDefault(emptyList())
        fltSearching = false
    }

    /**
     * 验证该艺人是否有乐迷团房间正在一起听（只读探测，无副作用）。
     * 探测链（2026-09-13 官方包+匿名探针双实证）：artistId → fansGroupId →
     * bff/detail/get 的 fansGroupInfo.listenTogether {status, roomId, roomUserNums, joinOrpheus…}。
     * 旧方案（follow/listen/room/rcmd/list 翻页）因服务端风控 500 且 multi/room/create 有建房副作用，已弃用。
     */
    fun probeFanGroupListen(artist: ArtistItem) {
        if (fanProbe[artist.id]?.first == 1) return
        fanProbe = fanProbe + (artist.id to Pair(1, null))
        scope.launch {
            val info = runCatching { NcmApi.fanGroupListenProbe(artist.id) }.getOrNull()
            val state = when {
                info == null -> 4       // 接口不可达 / 艺人没有乐迷团
                info.listening -> 2     // 正在进行乐迷团一起听
                else -> 3               // 有乐迷团，当前没在听
            }
            fanProbe = fanProbe + (artist.id to Pair(state, info))
        }
    }

    /** 加入正在进行的乐迷团一起听：fl_ 房号走 ListenSession.join 的 FLT 分支（安卓口径 join/room） */
    fun joinFanGroupListen(info: NcmApi.FanGroupListen) {
        val rid = info.roomId ?: return
        if (joining) return
        joining = true
        scope.launch {
            ListenChatStore.clear() // 新房间从干净的消息列表开始
            val okJoin = runCatching { ListenSession.join(rid) }.getOrDefault(false)
            joining = false
            if (okJoin) nav.navigateWithMotion(Routes.LISTEN_ROOM, NavMotionSpec(NavMotionKind.Pill))
            else toast = ListenSession.statusText.ifEmpty { "加入失败（房间可能刚结束）" }
        }
    }

    /**
     * 开一个乐迷团一起听房（2026-10-01 新增）：官方 `listen/together/multi/room/create`，
     * 带 artistId 建房 + autoJoinUids 直接把选中的好友拉进房。
     * 与「加入别人的团房」互补 —— 团里当前没人在听时，用户可以自己开一个。
     */
    fun createFanGroupRoom(artist: ArtistItem) {
        if (joining) return
        joining = true
        scope.launch {
            ListenChatStore.clear()
            // 开房前先垫上可连续播放的队列，否则房间刚建就断流（与普通房 create 同策略）
            if (PlayerEngine.current == null) {
                // 房间初始歌单固定 5 首（随机挑选），之后每播完一首自动补一首
                val songs = runCatching { NcmApi.roomSeedSongs(ListenSession.ROOM_SEED_SIZE) }
                    .getOrDefault(emptyList())
                if (songs.isNotEmpty()) PlayerEngine.playQueue(songs, 0, PlayerEngine.StreamSource.FM)
            }
            val okCreate = runCatching { ListenSession.createFlt(artist.id) }.getOrDefault(false)
            joining = false
            if (okCreate) nav.navigateWithMotion(Routes.LISTEN_ROOM, NavMotionSpec(NavMotionKind.Pill))
            else toast = ListenSession.statusText.ifEmpty { "创建乐迷团房失败" }
        }
    }

    val inRoom = ListenSession.active
    val kw = fltQuery.text.trim()
    val searchingMode = kw.isNotEmpty()
    val cardCount = (if (inRoom) 1 else 0) + 2

    Box(Modifier.fillMaxSize()) {
        StackedCardList(
            title = "一起听",
            titleSubtitle = when {
                searchingMode -> "搜索乐迷团一起听"
                ListenSession.fltMode -> "乐迷团房 · ${ListenSession.fltTitle.ifEmpty { "已加入" }}"
                // ★ 2026-10-23 用户口径「把开房间后带有尾号相关字样显示删掉，容易被误会是
                //   房间号」：标题副标题不再显示任何房号/尾号，只说"已在房间中"和在线人数。
                inRoom -> "已在房间中 · ${peopleText()}"
                else -> "和好友同步听歌"
            },
            progressTotal = if (searchingMode) (if (fltArtists.isEmpty()) 1 else fltArtists.size) else cardCount,
            horizontalInsetPx = 10,
        ) {
            // 乐迷团搜索胶囊：置顶，与功能卡同列滚动
            item(key = "flt_search") {
                BasicTextField(
                    value = fltQuery,
                    onValueChange = { fltQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 11.sp, color = TextPrimary),
                    cursorBrush = SolidColor(Accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(SurfaceGlass)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    // ★ 2026-10-24 用户需求：搜索框恢复「搜点什么」占位（单行，不改变框高）。
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth()) {
                            if (fltQuery.text.isEmpty()) {
                                Text("搜点什么", fontSize = 11.sp, color = TextSecondary)
                            }
                            inner()
                        }
                    },
                )
            }

            if (searchingMode) {
                when {
                    fltSearching -> item(key = "flt_wait") {
                        Text("搜索中…", fontSize = 10.sp, color = TextSecondary,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp),
                            textAlign = TextAlign.Center)
                    }
                    fltArtists.isEmpty() -> item(key = "flt_none") {
                        Text("没有找到相关艺人", fontSize = 10.sp, color = TextSecondary,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp),
                            textAlign = TextAlign.Center)
                    }
                    else -> items(fltArtists, key = { it.id }) { a ->
                        val probe = fanProbe[a.id]
                        val state = probe?.first ?: 0
                        val info = probe?.second
                        StackedCard(onClick = {
                            when (state) {
                                2 -> info?.let { joinFanGroupListen(it) }  // 正在听 → 点击加入
                                3 -> createFanGroupRoom(a)                 // 有团没在听 → 自己开房
                                else -> probeFanGroupListen(a)             // 其余 → 验证/重试
                            }
                        }) {
                            CoverImage(a.avatarUrl, 36.dp, shape = CircleShape)
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(a.name, fontSize = 12.sp, color = TextPrimary, maxLines = 1)
                                Text(
                                    when (state) {
                                        1 -> "验证乐迷团中…"
                                        2 -> "正在乐迷团一起听" +
                                            (info?.roomUserNums?.takeIf { it > 0 }?.let { " · ${it}人" } ?: "")
                                        3 -> "有乐迷团 · 当前没有一起听"
                                        4 -> "暂不可达，点按重试"
                                        else -> "点查是否正在一起听"
                                    },
                                    fontSize = 9.sp,
                                    color = if (state == 2) Accent else TextSecondary,
                                    fontWeight = if (state == 2) FontWeight.SemiBold else null,
                                    maxLines = 1,
                                )
                            }
                            Text(
                                when (state) {
                                    1 -> "…"
                                    2 -> "加入"
                                    3 -> "开房"
                                    else -> "查询"
                                },
                                fontSize = 9.sp, color = Accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                if (inRoom) {
                    item(key = "room", contentType = "listen") {
                        StackedIconCard(
                            title = "回到房间",
                            // ★ 2026-10-23 用户口径「把开房间后带有尾号相关字样显示删掉，
                            //   容易被误会是房间号」：副标题只显示在线人数，不再出现「尾号 xxxx」。
                            subtitle = peopleText(),
                            icon = NcmIcons.Together,
                            iconTint = Accent,
                            iconBg = Accent.copy(alpha = 0.20f),
                            onClick = {
                                nav.navigateWithMotion(Routes.LISTEN_ROOM, NavMotionSpec(NavMotionKind.Pill))
                            },
                        )
                    }
                }

                item(key = "create", contentType = "listen") {
                    StackedIconCard(
                        title = "创建房间",
                        subtitle = if (busy) "正在处理…" else "从「我喜欢」随机 5 首开房",
                        icon = NcmIcons.Plus,
                        onClick = {
                            if (!busy) {
                                busy = true
                                scope.launch {
                                    ListenChatStore.clear() // 新房间从干净的消息列表开始
                                    // 初始歌单播种已收进 ListenSession.create()（我喜欢随机 5 首，
                                    // 收藏为空时退口味兜底）—— 这里不再预置队列，避免出现
                                    // 「UI 播一批、create 又上报另一批」的两套口径。
                                    if (ListenSession.create()) {
                                        nav.navigateWithMotion(Routes.LISTEN_ROOM, NavMotionSpec(NavMotionKind.Pill))
                                    } else {
                                        toast = ListenSession.statusText.ifEmpty { "创建房间失败" }
                                    }
                                    busy = false
                                }
                            }
                        },
                    )
                }

                item(key = "join", contentType = "listen") {
                    StackedIconCard(
                        title = "加入房间",
                        subtitle = "粘贴邀请链接或房号",
                        icon = NcmIcons.Qr,
                        onClick = { showJoin = true },
                    )
                }
            }
        }

        if (showJoin) {
            TextInputDialog(
                title = "加入一起听",
                // ★ 2026-10-23 需求③：不再传框内占位文案（用户口径「所有输入框占位全部删除」）。
                onConfirm = { code ->
                    showJoin = false
                    if (!busy) {
                        busy = true
                        scope.launch {
                            ListenChatStore.clear() // 新房间从干净的消息列表开始
                            if (ListenSession.join(code)) {
                                nav.navigateWithMotion(Routes.LISTEN_ROOM, NavMotionSpec(NavMotionKind.Pill))
                            }
                            else toast = ListenSession.statusText.ifEmpty { "加入失败" }
                            busy = false
                        }
                    }
                },
                onDismiss = { showJoin = false },
            )
        }

        NcmToast(toast, Modifier.align(Alignment.Center))
    }
}

// ★ 2026-10-23：这里原先有个 roomTail()（取 roomId 尾 4 位，在入口页标题与「回到房间」卡上
//   显示「房间 xxxx / 尾号 xxxx」）。用户口径「带有尾号相关字样容易被误会是房间号」——
//   现在房号/尾号**完全不上屏**，该函数已删除：
//   · 「回到房间」卡副标题只显示在线人数；
//   · 入口页标题只显示「已在房间中 · N 人」。
//   房号校验本身（ListenSession.validRoomId / establish 失败不进房间态）保留不动 ——
//   它负责"没拿到服务端真房号就不进入房间态"，与显示无关。

/**
 * 一起听各浮层（房间歌单 / 添加歌曲 / 邀请好友 / 成员名单）的底色。
 *
 * ★ 2026-10-01 主题适配：旧版写死 `Color(0xF70A0A0C)`（近黑 97%）。深色主题下没问题，
 *   但浅色主题下整页变成一块黑蒙版 —— 白色胶囊卡浮在黑底上，**搜索框上方那条固定
 *   不滚动的区域**尤其像一条突兀的黑带（用户反馈「搜索框附近的黑色固定区域去不掉」
 *   「背景黑色蒙版效果要适配浅色主题」）。
 *
 *   现在底色跟随主题色板 [Bg]（深色 #0A0A0C / 浅色 #EFEFF3），并保持 97% 不透明 ——
 *   不透明度不能降：底下的房间页（进度环 / 旋转封面 / 弧形底栏）一旦透出来，
 *   观感就是「黑边 + 裁切」（旧版 82% 的老问题）。
 */
@Composable
private fun listenOverlayBackdrop(): Color = Bg.copy(alpha = 0.97f)

@Composable
private fun peopleText(): String {
    // 口径：roomExt.curUserNums（当前实时）> totalUserNums（偏累计）> 成员表大小
    val n = ListenSession.liveUserCount()
    return if (n > 1) "$n 人" else "等待加入"
}

// ==================== 房间页 ====================

/**
 * 手势：右滑返回入口页；左划歌词；上滑聊天（聊天内下滑回房间）。
 * 房间被服务端结束 / 账号在别处进房 → 提示后自动弹回。
 */
@Composable
fun ListenRoomScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var mode by remember { mutableStateOf("room") }        // room / chat
    var showQueue by remember { mutableStateOf(false) }    // 歌单浮层
    var showInvite by remember { mutableStateOf(false) }   // 邀请好友浮层
    var showAdd by remember { mutableStateOf(false) }      // 添加歌曲浮层
    var showMembers by remember { mutableStateOf(false) }  // 房间成员名单浮层
    var askExit by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    // 右滑退出房间淡出引擎（跟手右移+非线性淡出+速度加成，无震动；仅房间模式生效，聊天右滑是页内切换）
    // 返回行程 34dp（2026-09-13 二次下调 40%：70dp → 56dp → 34dp）
    val swipePx = with(androidx.compose.ui.platform.LocalDensity.current) { 34.dp.toPx() }
    val backFader = remember(swipePx) { SwipeBackFader(swipePx, scope) }
    // 滑出目标 = 屏宽（松手速度接续滑出用）
    // ⚠ screenWidthUiDp = UI 坐标系屏宽（密度等比缩放后 LocalConfiguration.screenWidthDp 不再等于屏宽）
    val screenPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        com.netmusiclite.ui.components.screenWidthUiDp().toPx()
    }
    var swipeLifted by remember { mutableStateOf(false) } // 右滑期间置顶，退出时下层接场无空白帧
    val clipboard = LocalClipboardManager.current
    val song = PlayerEngine.current
    val playing = PlayerEngine.isPlaying

    LaunchedEffect(Unit) {
        // ★ 2026-10-18 耗电：改成状态订阅，不再每 500ms 轮询。
        //   ListenSession.active 本身就是 Compose 状态，snapshotFlow 只在它真正翻转时发一次
        //   事件 —— 旧版在房间页停留期间每 0.5s 唤醒一次 CPU，只为读一个 boolean。
        //   语义不变：仍然要求「先 true 后 false」才触发，进入页面时已结束的房间不会误弹。
        var wasActive = ListenSession.active
        snapshotFlow { ListenSession.active }.collect { now ->
            if (wasActive && !now) {
                toast = ListenSession.statusText.ifEmpty { "房间已结束" }
                delay(1400)
                nav.popBackStack()
            }
            wasActive = now
        }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2200); toast = null } }

    // ---------- 一起听内收藏（2026-10-23 新增，修「一起听收藏不可用」） ----------
    // 本端在途的收藏 id：写请求期间挡掉重复点击。引擎里也有 pendingLike 互斥，
    // 但被它忽略的那次点击本页的 toast 已经说出去了，会出现「提示成功其实没写」的假象。
    var likingIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    // 每首歌最后一次收藏操作的序号：异步校对结果时只认最后一次，避免连点误报失败
    var likeSeq by remember { mutableStateOf(0) }
    val likeSeqBySong = remember { mutableMapOf<Long, Int>() }

    /**
     * 房间歌单 → 收藏 / 取消收藏（**写接口**：`NcmApi.likeSong`，经 [PlayerEngine.toggleLike]）。
     *
     * 根因背景（「一起听收藏不可用」）：本文件一起听域旧版**没有任何收藏调用**
     * （grep 只有三处注释提到「收藏」），房间歌单里长按的手势实际是「从房间歌单删除」——
     * 用户在房间里想收藏，唯一能按到的长按反而把歌删了，反馈就成了「收藏点了没反应/收藏不了」。
     * 现补上真正落到网易云账号的收藏入口（长按 → 操作浮层 → 收藏/取消收藏）。
     *
     * 防静默三件事：
     *  ① 未登录明确提示（[NcmApi.likeSong] 在 uid=0 时会拒绝写匿名收藏，旧链路没人告诉用户）；
     *  ② 本地歌曲 / 坏 id 明确提示；
     *  ③ 乐观更新后等写请求落地再校对一次红心，失败回滚时把「静默回滚」变成可见提示。
     */
    fun toggleRoomLike(s: Song) {
        if (!SessionStore.loggedIn) {
            toast = "未登录，无法收藏"
            return
        }
        if (s.id <= 0L) {
            toast = "该歌曲不支持收藏"
            return
        }
        // ★ 2026-10-24（用户反馈「点第一下显示收藏处理中，要点第二下才真正收藏」）：
        //   在途时**静默忽略**，不再覆盖 toast。旧版在这里写「收藏处理中，请稍候」——
        //   当同一点击被两个手势层/两个入口各触发一次时（第二次命中的是这里的在途拦截），
        //   用户看到的就是「第一下说处理中、第二下才生效」，误以为第一次没执行。
        //   实际第一次已乐观更新并发起写入，第二次本就应该被丢弃（防 like/unlike 交错）。
        if (s.id in likingIds) return
        val want = !PlayerEngine.isLiked(s.id)
        val seq = ++likeSeq
        likeSeqBySong[s.id] = seq
        likingIds = likingIds + s.id
        // 乐观更新 + 风控互斥 + 失败回滚都在引擎内（PlayerEngine.toggleLike）
        PlayerEngine.toggleLike(s)
        toast = if (want) "已收藏 · ${s.title}" else "已取消收藏 · ${s.title}"
        scope.launch {
            // ★ 写请求失败在引擎里是**静默回滚**（只回滚红心、不通知 UI）。
            //   一起听域刚补上收藏入口，必须把失败摆到界面上：
            //   等写请求落地后校对一次红心，与用户意图不一致就明确提示。
            try {
                delay(2000L)
            } finally {
                likingIds = likingIds - s.id
            }
            if (likeSeqBySong[s.id] != seq) return@launch // 期间又点过这首歌：以最新一次为准
            if (PlayerEngine.isLiked(s.id) != want) {
                toast = "收藏失败 · 请检查网络或重新登录后再试"
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .zIndex(if (swipeLifted) 2f else 0f)
            .graphicsLayer {
                alpha = backFader.alpha
                translationX = backFader.slidePx
                scaleX = backFader.scale
                scaleY = backFader.scale
                // 2026-10-02：缩放圆心 = 本页入场时的点击位置（原路返回）
                transformOrigin = backFader.origin
            }
            .m3eSwipeLeft(if (com.netmusiclite.data.AppearancePrefs.isM3e && mode == "room") ({
                nav.navigateWithMotion(Routes.LYRICS, NavMotionSpec(NavMotionKind.Slide))
            }) else null)
            .pointerInput(com.netmusiclite.data.AppearancePrefs.isM3e, mode) {
                if (com.netmusiclite.data.AppearancePrefs.isM3e && mode == "room") return@pointerInput
                val px = 70.dp.toPx()
                var totalX = 0f
                var leftX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalX = 0f; leftX = 0f; backFader.onDragStart(); swipeLifted = true },
                    onDragEnd = {
                        val back = mode != "chat" && backFader.passedThreshold
                        backFader.onDragEnd(back)
                        if (back) {
                            // 松手速度接续：fader 线性滑出全屏，转场时长同步武装给 NavHost
                            val ms = backFader.flingOutDuration(screenPx)
                            TransitionCoordinator.withGesturePop(ms) { nav.popBackStack() }
                            backFader.flingOut(screenPx, ms)
                        }
                        else swipeLifted = false
                        if (totalX > px) {
                            if (mode == "chat") mode = "room"
                        } else if (leftX > px && mode == "room") {
                            // 左划进歌词：滑动来源 → 保留滑动转场
                            nav.navigateWithMotion(
                                Routes.LYRICS, NavMotionSpec(NavMotionKind.Slide),
                            )
                        }
                    },
                ) { change, amount ->
                    change.consume()
                    totalX += amount
                    if (amount < 0) leftX += -amount else leftX = 0f
                    // 淡出只在房间模式（退出房间）生效；聊天模式右滑是页内切回房间视图
                    if (mode == "room") backFader.onDrag(change, amount)
                }
            }
            .pointerInput(Unit) {
                var totalY = 0f
                detectVerticalDragGestures(
                    onDragStart = { totalY = 0f },
                    onDragEnd = {
                        val px = 70.dp.toPx()
                        if (mode == "room" && totalY < -px) mode = "chat"
                        else if (mode == "chat" && totalY > px) mode = "room"
                    },
                ) { change, amount -> change.consume(); totalY += amount }
            },
    ) {
        // 默认主题背景由 AppRoot 全局层负责；m3e 仍由页面自带。
        if (com.netmusiclite.data.AppearancePrefs.isM3e) {
            com.netmusiclite.ui.components.NcmBackground(route = Routes.LISTEN_ROOM, prepareResources = false)
        }
        AnimatedContent(
            targetState = mode,
            transitionSpec = {
                if (targetState == "chat") {
                    slideInVertically { it } + fadeIn() togetherWith slideOutVertically { it } + fadeOut()
                } else {
                    slideInVertically { -it } + fadeIn() togetherWith slideOutVertically { -it } + fadeOut()
                }
            },
            label = "listenMode",
        ) { m ->
            if (m == "chat") {
                ListenChatPanel(onClose = { mode = "room" })
            } else {
                RoomPanel(
                    song = song,
                    playing = playing,
                    // ★ 2026-10-23：收藏态从引擎红心集合反查（PlayerEngine.likedIds 是
                    //   mutableStateOf，读它即建立快照订阅，收藏落地/失败回滚会自动重组）。
                    liked = song != null && PlayerEngine.isLiked(song.id),
                    onToggleLike = { song?.let { toggleRoomLike(it) } },
                    onOpenInvite = { showInvite = true },
                    onOpenQueue = { showQueue = true },
                    onOpenMembers = { showMembers = true },
                    onOpenChat = { mode = "chat" },
                    onVolume = {
                        if (com.netmusiclite.data.DeviceCompat.useInAppVolume) {
                            // 非 ColorOS：自绘音量条（与播放页同规格，2026-10-03 扩到非 ColorOS 全机型）
                            com.netmusiclite.ui.components.InAppVolume.show()
                        } else {
                            // 唤出系统音量调节面板（STREAM_MUSIC，与播放页音量钮同规格）
                            val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE)
                                as android.media.AudioManager
                            am.adjustStreamVolume(
                                android.media.AudioManager.STREAM_MUSIC,
                                android.media.AudioManager.ADJUST_SAME,
                                android.media.AudioManager.FLAG_SHOW_UI,
                            )
                        }
                    },
                    onExit = { askExit = true },
                )
            }
        }

        if (showQueue) {
            RoomQueueOverlay(
                onDismiss = { showQueue = false },
                // 双人房全员可在歌单点歌（2026-10-24 用户要求）；乐迷团跟听房仍只房主
                onPick = { i ->
                    if (ListenSession.canControlRoom) {
                        PlayerEngine.playAt(i)
                        showQueue = false
                    } else {
                        toast = "乐迷团房仅房主可切歌"
                    }
                },
                onAddSong = { showQueue = false; showAdd = true },
                onPickRecommend = { s ->
                    // ★ 2026-10-23 二轮：三态（已加入 / 已在歌单 / 加入失败），
                    //   旧版 Boolean 把「失败」和「重复」都报成「已在歌单」，用户以为加成功了。
                    when (ListenSession.addToRoomQueueResult(s)) {
                        ListenSession.AddResult.ADDED -> toast = "已加入房间歌单 · ${s.title}"
                        ListenSession.AddResult.DUPLICATE -> toast = "这首歌已在房间歌单里"
                        ListenSession.AddResult.FAILED -> toast = "加入失败（不在房间或房间已结束）"
                    }
                },
                // 长按房间歌单里的某一首 → 从房间歌单删除（不是取消收藏，不动收藏数据）
                onRemove = { i ->
                    val s = PlayerEngine.queue.getOrNull(i)
                    toast = if (ListenSession.removeFromRoomQueue(i)) {
                        if (s != null) "已从房间歌单删除 · ${s.title}" else "已从房间歌单删除"
                    } else {
                        "删除失败（歌单可能已变化）"
                    }
                },
                // ★ 2026-10-23 新增：长按菜单里的「收藏 / 取消收藏」真正落到网易云账号
                onToggleLike = { s -> toggleRoomLike(s) },
            )
        }

        if (showAdd) {
            AddSongOverlay(
                // 选完/关掉都回歌单浮层（选歌是歌单的子流程，不再两个浮层叠着渲染）
                onDismiss = { showAdd = false; showQueue = true },
                onPick = { s ->
                    // ★ 2026-10-23 二轮：三态 toast（同 onPickRecommend）
                    when (ListenSession.addToRoomQueueResult(s)) {
                        ListenSession.AddResult.ADDED -> toast = "已加入房间歌单 · ${s.title}"
                        ListenSession.AddResult.DUPLICATE -> toast = "这首歌已在房间歌单里"
                        ListenSession.AddResult.FAILED -> toast = "加入失败（不在房间或房间已结束）"
                    }
                    showAdd = false
                    showQueue = true
                },
                // 多选批量：一次快照上报；全重复时明确提示
                onPickMany = { list ->
                    val (added, dup) = ListenSession.addMultiToRoomQueue(list)
                    toast = when {
                        added == 0 -> "所选歌曲都已在房间歌单里"
                        dup > 0 -> "已添加 $added 首（$dup 首已在歌单）"
                        else -> "已添加 $added 首到房间"
                    }
                    showAdd = false
                    showQueue = true
                },
            )
        }

        if (showMembers) {
            MemberOverlay(onDismiss = { showMembers = false })
        }

        if (showInvite) {
            InviteFriendsOverlay(
                onDismiss = { showInvite = false },
                // ★ 2026-10-23 修「点分享/邀请后没有任何提示」：
                //   复制邀请链接后**立刻**弹「邀请链接已复制」（复用本页 toast），
                //   随后邀请结果再覆盖这条提示 —— 用户至少知道链接已经进了剪贴板。
                onShareLink = {
                    val link = ListenSession.shareLink()
                    if (link == null) {
                        // 没有服务端确认的房号时 shareLink() 返回 null：不复制空链接
                        toast = "房间信息未就绪，暂时无法分享"
                    } else {
                        clipboard.setText(AnnotatedString(link))
                        // ★ 2026-10-23：提示里不再带「尾号 xxxx」（用户口径：容易被误会是房间号）
                        toast = "邀请链接已复制"
                    }
                },
                onInvite = { f ->
                    val link = ListenSession.shareLink()
                    if (link == null) {
                        toast = "房间信息未就绪"
                        showInvite = false
                    } else {
                        // 链接始终先复制：官方邀请接口失败时它是唯一可用路径
                        clipboard.setText(AnnotatedString(link))
                        showInvite = false
                        // ★ 复制动作必须有可见反馈（旧版只在部分分支的 toast 里提一句「链接已复制」）
                        toast = "邀请链接已复制"
                        // 房号只取真实值：旧版 ?: "-" 会往私信/系统消息里写「房号 -」这种假房号
                        val rid = ListenSession.roomId.orEmpty()
                        val ridSuffix = if (rid.isNotEmpty()) "，房间号 $rid" else ""
                        scope.launch {
                            // ① 官方一起听邀请接口（非私信）：邀请落进对方「一起听」消息箱，点一下即进房
                            when (ListenSession.invite(f.id)) {
                                ListenSession.InviteResult.DELIVERED -> {
                                    ListenChatStore.system("已邀请 ${f.name} 一起听（对方在「一起听」页接受即可）")
                                    toast = "已邀请 ${f.name} · 邀请链接已复制"
                                }
                                // ② 邀请接口没通：退一步发私信（带房号 + 链接），并明确告知链接已复制
                                else -> {
                                    val ok = runCatching {
                                        NcmApi.msgSend(f.id, "我在 NetMusicLite 开了个一起听$ridSuffix，来一起听\n$link")
                                    }.getOrDefault(false)
                                    ListenChatStore.system(
                                        if (ok) "已私信邀请 ${f.name}${if (rid.isNotEmpty()) "（房号 $rid）" else ""}"
                                        else "邀请未送达：邀请链接已复制，可手动发给对方")
                                    toast = if (ok) "已邀请 ${f.name} · 邀请链接已复制"
                                    else "邀请未送达 · 邀请链接已复制"
                                }
                            }
                        }
                    }
                },
            )
        }

        if (askExit) {
            // ★ 2026-10-24 用户需求：m3e 主题下删除本确认框最下方的「关闭」按钮
            //   （框内已有取消/确定两颗圆钮 + 点遮罩关闭，底部关闭键重复且压屏）。
            DialogScrim(onDismiss = { askExit = false }, m3eShowClose = false) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(BgElevated)
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        if (ListenSession.isHost) "结束一起听？" else "退出房间？",
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (ListenSession.isHost) "房间将对所有成员结束" else "退出后不再与房间同步播放",
                        fontSize = 9.sp, color = TextSecondary, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircleIconButton(NcmIcons.Close, "取消", { askExit = false }, size = 40.dp, iconSize = 17.dp)
                        CircleIconButton(
                            NcmIcons.Check, "确定",
                            {
                                askExit = false
                                ListenSession.end()
                                ListenChatStore.clear()
                                nav.popBackStack()
                            },
                            size = 40.dp, iconSize = 17.dp, container = Accent,
                        )
                    }
                }
            }
        }

        NcmToast(toast, Modifier.align(Alignment.Center))
    }
}

/**
 * 房间主面板。垂直分配按**真机实测**重排（2026-09-11 第二轮，466px@density2.0）：
 * 实测发现 CJK 字体下 9sp 文字块实占 24dp（非理论的 13dp），
 * 底部弧形三钮顶端在 187.5dp，塞不下成员行 —— 因此成员行移到顶部。
 *
 * 2026-10-01：顶部「已连接 / 房主」状态胶囊（原占 4→36dp）删除，其余组件整体上移 20px（本机 10dp）：
 *   成员行 40→30 ｜ 歌名 64→54 ｜ 艺人行 54→78 ｜ 三键 112→102 ｜ 弧形钮顶 187.5（不动）
 * 侧边进度环与底部弧形状态栏**不参与**位移。
 *
 * 2026-10-23：新增「收藏 / 取消收藏当前歌」（[liked] / [onToggleLike]）。
 *   经典主题落点 = 底部 [ListenArcBar] 中间那颗（弧栏 4 → 5 颗：36dp/半径 92dp/±47°，
 *   外沿仍为 110dp，与改造前一致）；m3e 由 [M3eListenRoomPanel] 画在 song 卡片的 trailing
 *   红心。三键区右侧放不下的几何原因见 [ListenArcBar] 的注释与 docs/LT_UI_COLLECT.md。
 */
@Composable
private fun RoomPanel(
    song: Song?,
    playing: Boolean,
    /** 当前房间歌曲是否已在「我喜欢」里（红心填充态） */
    liked: Boolean,
    /** 收藏 / 取消收藏当前歌（复用 ListenRoomScreen.toggleRoomLike：未登录/本地歌/toast/校对都在那里） */
    onToggleLike: () -> Unit,
    onOpenInvite: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenMembers: () -> Unit,
    onOpenChat: () -> Unit,
    onVolume: () -> Unit,
    onExit: () -> Unit,
) {
    if (com.netmusiclite.data.AppearancePrefs.isM3e) {
        com.netmusiclite.ui.m3e.M3eListenRoomPanel(
            song = song,
            playing = playing,
            liked = liked,
            onToggleLike = onToggleLike,
            isHost = ListenSession.isHost,
            canControl = ListenSession.canControlRoom,
            members = peopleText(),
            progress = {
                (PlayerEngine.positionMs.toFloat() / PlayerEngine.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
            },
            playbackTime = "${formatMs(PlayerEngine.positionMs)} / ${formatMs(PlayerEngine.durationMs)}",
            onPrevious = { PlayerEngine.prev() },
            onToggle = { PlayerEngine.toggle() },
            onNext = { PlayerEngine.next() },
            onMembers = onOpenMembers,
            onChat = onOpenChat,
            onInvite = onOpenInvite,
            onQueue = onOpenQueue,
            onVolume = onVolume,
            onExit = onExit,
        )
        return
    }
  Box(Modifier.fillMaxSize()) {
    // ★ 房间页进度环只作展示、不接 seek：① 播放进度由房间同步接管，本地拖动必然被
    //   远端状态拉回，徒劳；② 该手势挂 Initial 通道且「纵向趋势接管」，会把页面级
    //   「上滑聊天」手势整个吃掉（真机反馈：进度条没屏蔽 + 聊天打不开，同根因）。
    //   onSeek = null 时环的 pointerInput 直接跳过，纵向滑动归还根布局。
    //   ★ 侧边进度环与底部弧形状态栏**不参与**下面的整体上移。
    EdgeProgressRing(
        progress = {
            val d = PlayerEngine.durationMs.coerceAtLeast(1L)
            (PlayerEngine.positionMs.toFloat() / d).coerceIn(0f, 1f)
        },
        onSeek = null,
        modifier = Modifier.fillMaxSize(),
    )

    // 2026-10-01：顶部「已连接 / 房主」状态胶囊（原 StatusPill）整体删除。
    // 其余组件（成员行 / 歌名 / 艺人行 / 三键区）统一上移 20 **物理像素**：
    // 用户口径是像素，故按 LocalDensity 换算（本机 density 2.0 → 10dp），
    // 换到别的密度设备上仍然是 20px 的视觉位移，不会随 density 缩放。
    val shift = with(androidx.compose.ui.platform.LocalDensity.current) { 20.toDp() }

    MemberRow(
        Modifier.align(Alignment.TopCenter).padding(top = 40.dp - shift),
        onClick = onOpenMembers,
    )

    Column(
        Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(top = 64.dp - shift),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.width(104.dp), contentAlignment = Alignment.Center) {
            Text(
                song?.title ?: "等待同步…",
                fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                maxLines = 1, modifier = Modifier.basicMarquee(),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            buildString {
                append(song?.artist ?: "房间暂无歌曲")
                if (PlayerEngine.durationMs > 0L) {
                    append(" · ")
                    append(formatMs(PlayerEngine.positionMs))
                    append("/")
                    append(formatMs(PlayerEngine.durationMs))
                }
            },
            fontSize = 9.sp, color = TextSecondary, maxLines = 1,
            modifier = Modifier.padding(horizontal = 40.dp),
        )
    }

    // 三键区：双人房（非乐迷团房）**全员可切歌**（2026-10-24 用户要求，与官方一致）。
    //   旧版按 isHost 屏蔽成员的上一首/下一首，理由「成员点了会切到空白/被同步拉回」其实
    //   是三个同步缺陷的表象（回声误判 + 命令被门禁吞 + 心跳互相拽回），本轮已在 ListenSession 修掉。
    //   乐迷团跟听房仍只房主可控（单向跟听，成员控歌无上报通道）。
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 112.dp - shift),
    ) {
        if (ListenSession.canControlRoom) {
            CircleIconButton(NcmIcons.Prev, "上一首", { PlayerEngine.prev() }, size = 38.dp, iconSize = 18.dp)
            Spacer(Modifier.width(6.dp))
        }
        RoomPlayButton(playing = playing, song = song) { PlayerEngine.toggle() }
        if (ListenSession.canControlRoom) {
            Spacer(Modifier.width(6.dp))
            CircleIconButton(NcmIcons.Next, "下一首", { PlayerEngine.next() }, size = 38.dp, iconSize = 18.dp)
        }
    }

    ListenArcBar(
        liked = liked,
        onToggleLike = onToggleLike,
        onInvite = onOpenInvite,
        onQueue = onOpenQueue,
        onVolume = onVolume,
        onExit = onExit,
    )
  }
}

/** 成员头像行：点击进房间成员名单（含每人房内累计时长） */
@Composable
private fun MemberRow(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val members = ListenSession.members
    Row(
        modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (members.isEmpty()) {
            Text("等待加入 · 点左下邀请", fontSize = 9.sp, color = TextSecondary)
        } else {
            members.take(4).forEach { m -> CoverImage(m.avatarUrl, 20.dp, shape = CircleShape) }
            Spacer(Modifier.width(1.dp))
            // 人数口径：curUserNums（正在房间听）> totalUserNums（累计，仅回退）> members.size
            val n = ListenSession.liveUserCount()
            Text("$n 人正在房间听 ›", fontSize = 9.sp, color = TextSecondary)
        }
    }
}

/**
 * 房间成员名单：头像 + 昵称 + 房主标记 + 房内累计时长。
 * 人数/时长口径（2026-09-12 修正）：
 * - 实时人数 = roomExt.curUserNums（官方当前在线口径，修复 totalUserNums 偏累计的问题）；
 * - 房间累计一起听 = effectiveDurationMs（服务端统计，全员一致）；
 * - 个人时长按房间持久化累计（listen_room_secs.json），重进同房间接着计；
 *   他人只能统计本端在房期间（对方更早的听时长官方无接口提供）。
 */
@Composable
private fun MemberOverlay(onDismiss: () -> Unit) {
    // 系统 back（含华为式被系统吃掉的右滑）：先关本浮层，不动导航（2026-10-07 同批修复）
    OverlayBackHandler { onDismiss() }
    val listState = rememberLazyListState()
    val members = ListenSession.members
    val creator = ListenSession.creatorId
    val roomSecs = ListenSession.memberRoomSecs
    val me = SessionStore.uid
    // 服务端统计的房间累计一起听时长（effectiveDurationMs，聊天/互动解锁同一口径）
    val roomMin = ListenSession.roomListenMs / 60_000L

    fun inRoomText(uid: Long): String {
        val secs = roomSecs[uid] ?: return "刚进房"
        val min = secs / 60
        // 时长来自服务端下发（seedServerRoomTime 命中）时，标注包含进房前部分
        val serverNote = if (uid in ListenSession.serverTimeUids) "（含进房前）" else ""
        return when {
            uid == me -> if (min >= 1) "已听 $min 分钟" else "已听不足 1 分钟"
            min >= 1 -> "在房 $min 分钟$serverNote"
            else -> "在房不足 1 分钟"
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(listenOverlayBackdrop())
            .pointerInput(Unit) {
                var totalX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalX = 0f },
                    onDragEnd = { if (abs(totalX) > 60.dp.toPx()) onDismiss() },
                ) { change, amount -> change.consume(); totalX += amount }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp)
                .rotaryList(listState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(top = 14.dp, bottom = 78.dp),
            ) {
                // 总人数口径：服务端只回部分成员时注明（实时人数来自 curUserNums/totalUserNums）
                val total = ListenSession.liveUserCount()
                if (total > members.size) {
                    item(key = "total") {
                        Text(
                            "房间实时 $total 人 · 列表为服务端返回的 ${members.size} 位",
                            fontSize = 9.sp, color = TextSecondary,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                // 房间累计一起听（服务端口径，全员同步；旧版本地口径只算自己进房后的）
                if (roomMin >= 1) {
                    item(key = "room_listen") {
                        Text(
                            "本房间已一起听 $roomMin 分钟（服务端统计）",
                            fontSize = 9.sp, color = Accent,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                item(key = "hint") {
                    Text(
                        "个人时长按房间累计，重进接着计\n他人时长=本端在房期间的观测累计\n（对方更早的听时长官方服务端不提供）",
                        fontSize = 9.sp, color = TextSecondary,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        textAlign = TextAlign.Center,
                    )
                }
                if (members.isEmpty()) {
                    item(key = "empty") {
                        Text("成员读取中…", fontSize = 11.sp, color = TextSecondary,
                            modifier = Modifier.fillMaxWidth().padding(top = 120.dp),
                            textAlign = TextAlign.Center)
                    }
                }
                items(members, key = { it.id }) { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(30.dp))
                            .background(SurfaceGlass)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverImage(m.avatarUrl, 30.dp, shape = CircleShape)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name, fontSize = 12.sp, color = TextPrimary, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(inRoomText(m.id), fontSize = 9.sp, color = TextSecondary)
                        }
                        if (m.id.toString() == creator)
                            Text("房主", fontSize = 9.sp, color = Accent, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        CircleIconButton(
            NcmIcons.Close, "关闭", onDismiss,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            size = 36.dp, iconSize = 15.dp,
        )
    }
}

/** 大播放键：镂空圆环 + 内嵌旋转封面（68dp；房间页顶部多了状态+成员两行，比播放页 82dp 明显收小） */
@Composable
private fun RoomPlayButton(playing: Boolean, song: Song?, onClick: () -> Unit) {
    if (com.netmusiclite.data.AppearancePrefs.isM3e) {
        androidx.wear.compose.material3.FilledIconButton(onClick = onClick, modifier = Modifier.pressStretch().size(68.dp)) {
            androidx.wear.compose.material3.Icon(
                if (playing) NcmIcons.Pause else NcmIcons.Play,
                if (playing) "暂停" else "播放",
            )
        }
        return
    }
    Pressable(onClick = onClick) {
        Box(
            Modifier
                .size(68.dp)
                .clip(CircleShape)
                .border(3.dp, Color.White.copy(alpha = 0.9f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            RotatingCover(song, 52.dp, playing)
            if (!playing) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(NcmIcons.Play, contentDescription = "播放", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/**
 * 底部弧形操作区（带高 40dp / 中心半径 92dp / 弧段 94°±47°）。
 * 五钮（23.5° 间距、36dp）：左→右 邀请好友｜房间歌单｜收藏当前歌｜音量｜退出。
 * 音量 = 唤出系统音量面板（STREAM_MUSIC，与播放页音量钮同规格）。
 *
 * ★ 2026-10-23 新增「收藏 / 取消收藏当前歌」。
 *
 * 【为什么放在底部弧而不是三键区右侧】
 *   466×466 @density2.0（1dp=2px，屏 233dp，圆心 (116.5,116.5)，圆半径 116.5dp）实测：
 *   ① 房主态三键区 = prev 38 + 6 + play 68 + 6 + next 38 = 156dp，居中后占 x 38.5..194.5；
 *      右侧 3 点钟方向还有 [EdgeProgressRing] 的进度弧带（中线半径 0.914R≈106.5dp、
 *      线宽 6dp ⇒ 半径覆盖 103.5..109.5dp，角度 ±26.5°）。任何 36~40dp 圆钮想放在
 *      三键右侧，中心距圆心必须 ≤ 85.5dp（=103.5−18，否则压进度弧），换算到 x 只能到
 *      ~199.7dp，而三键右沿已到 194.5dp —— 物理上塞不进去（4dp 里放不下 36dp 钮）。
 *   ② 5 颗按钮若保持 44dp，两端要跨 ±58°，-60° 位置（y≈163dp）会撞上三键区 prev/next
 *      （底 155dp），同样无解。
 *   ③ 底部弧是房间页唯一还有余量的操作区：缩到 36dp / 半径 92dp / 间距 23.5° 后，
 *      相邻弦长 37.47dp 留 1.47dp 缝（改造前 4 颗 44dp/30° 是 1.55dp，观感一致），
 *      每颗外沿 92+18 = **110dp，与改造前完全一致** —— 不压进度弧、不出圆、不碰三键区。
 *   （几何推导与逐点校验见 docs/LT_UI_COLLECT.md。）
 *
 * 【红心态】
 *   [liked] 由调用方从 `PlayerEngine.isLiked(current.id)` 反查（likedIds 是
 *   mutableStateOf，读它即订阅，收藏成功/失败回滚都会自动重组）；点击走
 *   ListenRoomScreen.toggleRoomLike（未登录提示 / 去重 / 乐观更新 / 失败校对 toast 全在那里）。
 */
@Composable
private fun ListenArcBar(
    liked: Boolean,
    onToggleLike: () -> Unit,
    onInvite: () -> Unit,
    onQueue: () -> Unit,
    onVolume: () -> Unit,
    onExit: () -> Unit,
) {
    if (com.netmusiclite.data.AppearancePrefs.isM3e) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 18.dp)) {
                com.netmusiclite.ui.m3e.M3eListenActions(onInvite, onQueue, onVolume, onExit)
            }
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cx = maxWidth / 2
        val cyc = maxHeight / 2
        // 88dp → 92dp：5 颗 36dp 才能在 ±47° 内排开且外沿仍为 110dp（与 4 颗 44dp 时一致）
        val rBand = 92.dp

        Canvas(Modifier.fillMaxSize()) {
            val stroke = 40.dp.toPx()
            val r = rBand.toPx()
            // 弧段 -47°..+47°（以 6 点钟为 0°）＝ drawArc 的 43°..137°（0° 在 3 点钟、顺时针为正）
            drawArc(
                color = Color.White.copy(alpha = 0.10f),
                startAngle = 43f, sweepAngle = 94f, useCenter = false,
                topLeft = Offset((size.width - r * 2) / 2f, (size.height - r * 2) / 2f),
                size = Size(r * 2, r * 2),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            val rInner = r - stroke / 2 - 0.75.dp.toPx()
            drawArc(
                color = Color.White.copy(alpha = 0.10f),
                startAngle = 43f, sweepAngle = 94f, useCenter = false,
                topLeft = Offset((size.width - rInner * 2) / 2f, (size.height - rInner * 2) / 2f),
                size = Size(rInner * 2, rInner * 2),
                style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        // 第 3 颗（角度 0°，屏幕正下方）＝ 收藏当前歌：与「邀请/歌单」同属内容操作，
        // 两侧分别是音量/退出，危险操作（退出）仍留在最右端不易误触。
        val angles = listOf(-47f, -23.5f, 0f, 23.5f, 47f)
        val icons = listOf(
            NcmIcons.Together, NcmIcons.Playlist,
            if (liked) NcmIcons.HeartFill else NcmIcons.Heart,
            NcmIcons.Volume, NcmIcons.Close,
        )
        val descs = listOf(
            "邀请好友", "房间歌单",
            if (liked) "取消收藏" else "收藏",
            "音量", "退出房间",
        )
        val actions = listOf(onInvite, onQueue, onToggleLike, onVolume, onExit)
        angles.forEachIndexed { i, deg ->
            val rad = Math.toRadians(deg.toDouble())
            val x = cx + rBand * sin(rad).toFloat()
            val y = cyc + rBand * cos(rad).toFloat()
            CircleIconButton(
                icons[i], descs[i], actions[i],
                modifier = Modifier.offset {
                    // 36dp 钮 ⇒ 左上角偏移 = 圆心 − 18dp
                    IntOffset((x - 18.dp).roundToPx(), (y - 18.dp).roundToPx())
                },
                size = 36.dp, iconSize = 17.dp,
                container = Color.Transparent,
                // 已收藏点亮成主题强调色；其余（含未收藏的红心）保持底栏原墨色
                tint = if (i == 2 && liked) Accent else com.netmusiclite.ui.theme.PlayTextPrimary,
            )
        }
    }
}

// ==================== 聊天面板 ====================

/**
 * 房间聊天（上滑进入，下滑或右滑返回）。
 *
 * 收：**只用房内通道**，两路每拍并读 ——
 *     `listen/together/multi/special/msg/history`（乐迷团房）+
 *     `middle/im/chatroom/msg/history/query`（双人房的云信聊天室）。
 *     每 8s 一轮，按 `uid_sendTime` 跨通道去重浮出，含进房前的历史与官方客户端用户的发言。
 * 发：云信聊天室直发 `eapi/room/chat/sendMsg` → 官方 `middle/im/chatroom/send` 探测；
 *     **绝不退私信**（2026-10-01 起，见 [ListenChatStore.send] 的 KDoc）。
 *
 * ★ 2026-10-24：删掉「普通房每 16s 拉一轮成员私信」的桥接 —— 用户报
 *   「歌房消息通道和个人私信混用」，正是这里把两人的私聊历史灌进了房间面板。
 */
/** 聊天时间分隔用的 HH:mm */
private fun hhmm(ms: Long): String {
    val c = java.util.Calendar.getInstance()
    c.timeInMillis = ms
    return "%02d:%02d".format(
        c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE),
    )
}

@Composable
private fun ListenChatPanel(onClose: () -> Unit) {
    var input by remember { mutableStateOf(TextFieldValue("")) }
    // 引用回复目标（2026-10-01）：长按他人消息选中，输入区上方出引用条，发送时随消息一起上报
    var replyTarget by remember { mutableStateOf<ListenChatStore.Msg?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val messages = ListenChatStore.messages()
    val startMs = ListenSession.sessionStartWallMs
    // 进房前的历史与本次会话之间画一条分隔线（按时间戳找第一条「进房后」消息）
    val dividerIdx = messages.indexOfFirst { it.timeMs >= startMs }

    // 面板挂载期轮询（准实时）：两路房内通道每 8s 各拉一页（新消息按 uid_sendTime
    // 去重浮出，含对方在官方客户端房内发的）。离开面板协程自动取消，不耗后台。
    LaunchedEffect(ListenSession.roomId) {
        if (!ListenSession.active) return@LaunchedEffect
        runCatching { ListenChatStore.refreshRoomHistory() }
        while (ListenSession.active) {
            delay(8_000)
            runCatching { ListenChatStore.refreshRoomHistory() }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.adaptiveAnimateScrollToItem(messages.size - 1)
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val r = 96.dp.toPx()
            drawArc(
                color = Color.White.copy(alpha = 0.10f),
                startAngle = 64f, sweepAngle = 52f, useCenter = false,
                topLeft = Offset((size.width - r * 2) / 2f, (size.height - r * 2) / 2f),
                size = Size(r * 2, r * 2),
                style = Stroke(34.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        Column(Modifier.fillMaxSize()) {
            // 2026-10-01：聊天面板顶部标题整体删除（先删两行说明文案，再删「聊天显示区域」），
            // 列表直接吃满整屏，不再占版面。
            Spacer(Modifier.height(26.dp))

            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (messages.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            ListenChatStore.emptyHint(),
                            fontSize = 9.sp, color = TextSecondary, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 34.dp, vertical = 16.dp),
                        )
                    }
                }
                items(messages.size, key = { it }) { i ->
                    val msg = messages[i]
                    // 时间分隔：与上一条间隔超过 5 分钟插一个时刻，长时间挂机后不至于分不清先后
                    val prevMsg = messages.getOrNull(i - 1)
                    if (prevMsg == null || msg.timeMs - prevMsg.timeMs > 5 * 60_000L) {
                        Text(
                            hhmm(msg.timeMs), fontSize = 8.sp, color = TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 1.dp),
                        )
                    }
                    if (i == dividerIdx && dividerIdx > 0) {
                        Text(
                            "── 以上为进房前的房内消息 ──",
                            fontSize = 8.sp, color = TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        )
                    }
                    if (msg.isSystem) {
                        Text(
                            msg.text, fontSize = 9.sp, color = TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 3.dp),
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp, horizontal = 14.dp)
                                // 长按他人消息 → 引用回复（自己/系统提示不给回复入口）
                                .pointerInput(msg.id, msg.timeMs) {
                                    if (msg.isMe || msg.isSystem) return@pointerInput
                                    detectTapGestures(onLongPress = { replyTarget = msg })
                                },
                            contentAlignment = if (msg.isMe) Alignment.CenterEnd else Alignment.CenterStart,
                        ) {
                            Column(
                                horizontalAlignment = if (msg.isMe) Alignment.End else Alignment.Start,
                            ) {
                                // 多人房发送者名字：仅换人时显示 + 6sp 紧凑行高
                                // （此前每条都带 9sp 名字行，实测 CJK 9sp 块占 24dp，太吃空间）
                                val prev = messages.getOrNull(i - 1)
                                if (!msg.isMe && msg.senderName.isNotEmpty() && prev?.senderName != msg.senderName) {
                                    Text(
                                        msg.senderName, fontSize = 6.sp, color = TextSecondary,
                                        lineHeight = 7.sp,
                                        modifier = Modifier.padding(bottom = 1.dp),
                                    )
                                }
                                // 气泡 = 可选引用块 + 正文。id=0 = 本地待确认（私信在途/失败），半透明区分
                                // ★ 2026-10-01：己方气泡底色是主题色实底，前景必须走 OnAccent 自适应 ——
                                //   主题色选白色/浅色时「白字 + 白气泡」会整体糊掉（同 FriendScreens 的修复）
                                val onBubble = if (msg.isMe) OnAccent else TextPrimary
                                Column(
                                    Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (msg.isMe) Accent.copy(alpha = 0.8f) else SurfaceGlass
                                        )
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                        .then(if (msg.id == 0L) Modifier.alpha(0.55f) else Modifier),
                                ) {
                                    if (msg.replyToName.isNotEmpty() || msg.replyToText.isNotEmpty()) {
                                        Row(
                                            Modifier.padding(bottom = 3.dp).widthIn(max = 132.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            // 引用竖线（纯绘制，不用 emoji）
                                            Box(
                                                Modifier
                                                    .width(2.dp)
                                                    .height(16.dp)
                                                    .background(
                                                        onBubble.copy(alpha = 0.5f),
                                                        RoundedCornerShape(1.dp),
                                                    ),
                                            )
                                            Text(
                                                "回复 @${msg.replyToName.ifEmpty { "某人" }}" +
                                                    if (msg.replyToText.isBlank()) ""
                                                    else "：${msg.replyToText.replace('\n', ' ')}",
                                                fontSize = 6.sp, lineHeight = 8.sp,
                                                color = onBubble.copy(alpha = 0.72f),
                                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.padding(start = 4.dp),
                                            )
                                        }
                                    }
                                    Text(msg.text, fontSize = 10.sp, color = onBubble, lineHeight = 13.sp)
                                }
                            }
                        }
                    }
                }
                item(key = "pad") { Spacer(Modifier.height(74.dp)) }
            }
        }

        // 底部输入区：左「+」/ 中输入框 / 右发送（与好友聊天页同规格）
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cx = maxWidth / 2
            val cyc = maxHeight / 2
            val rB = 96.dp
            val radL = Math.toRadians((-52).toDouble())
            CircleIconButton(
                NcmIcons.Close, "返回房间", onClose,
                modifier = Modifier.offset {
                    IntOffset(
                        (cx + rB * sin(radL).toFloat() - 16.dp).roundToPx(),
                        (cyc + rB * cos(radL).toFloat() - 16.dp).roundToPx(),
                    )
                },
                size = 32.dp, iconSize = 14.dp,
            )
            val radR = Math.toRadians(52.0)
            CircleIconButton(
                NcmIcons.Send, "发送",
                {
                    val t = input.text.trim()
                    if (t.isNotEmpty()) {
                        val rt = replyTarget
                        input = TextFieldValue("")
                        replyTarget = null
                        // 真实投递：优先房内消息通道（官方 App 的房间聊天页可见），
                        // 失败再按房型回退（普通房私信桥接 / 乐迷团房明确提示，不向陌生人发私信）
                        scope.launch { ListenChatStore.send(t, rt) }
                    }
                },
                modifier = Modifier.offset {
                    IntOffset(
                        (cx + rB * sin(radR).toFloat() - 16.dp).roundToPx(),
                        (cyc + rB * cos(radR).toFloat() - 16.dp).roundToPx(),
                    )
                },
                size = 32.dp, iconSize = 14.dp, tint = Accent,
            )
            // 引用条：长按消息后浮在输入框上方，发送时随消息一起上报（× 取消引用）
            // 尺寸约束：底部左右两个圆形按钮中心在 r=96dp 圆上、距底约 58dp、水平 ±76dp，
            // 故引用条收窄到 112dp（±56dp）并抬到距底 40dp —— 两轴都避开，不与按钮压字。
            replyTarget?.let { rt ->
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 40.dp)
                        .width(112.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceGlass)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.width(2.dp).height(12.dp)
                            .background(Accent, RoundedCornerShape(1.dp)),
                    )
                    Text(
                        "回复 @${rt.senderName.ifEmpty { "某人" }}：" +
                            rt.text.replace('\n', ' ').take(14),
                        fontSize = 6.sp, lineHeight = 8.sp, color = TextPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    Text(
                        "×", fontSize = 10.sp, lineHeight = 10.sp, color = TextSecondary,
                        modifier = Modifier.padding(start = 4.dp, end = 1.dp)
                            .clickable { replyTarget = null },
                    )
                }
            }
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
                    .padding(horizontal = 4.dp, vertical = 7.dp),
                // ★ 2026-10-23 需求③：删除框内「说点什么…」占位（只留空白框）。
                //   该输入框 width(112dp) + 纵向 7dp 内边距，高度由一行行高撑住，不会塌陷。
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { inner() }
                },
            )
        }
    }
}

// ==================== 歌单浮层（含推荐 + 添加歌曲） ====================

@Composable
private fun RoomQueueOverlay(
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    onAddSong: () -> Unit,
    onPickRecommend: (Song) -> Unit,
    onRemove: (Int) -> Unit,
    /** 收藏 / 取消收藏（写接口落到网易云账号；由 ListenRoomScreen 注入 toast 与失败校对） */
    onToggleLike: (Song) -> Unit,
) {
    // 系统 back（含华为式被系统吃掉的右滑）：先关本浮层，不动导航（2026-10-07 同批修复）
    OverlayBackHandler { onDismiss() }
    val listState = rememberLazyListState()
    // 响应式读取：PlayerEngine.queue 已是 Compose 状态（2026-09-12），远端同步/别人加歌即刻反映
    val queue = PlayerEngine.queue
    // 长按条目的操作浮层目标（2026-10-23 新增）：
    // 「一起听收藏不可用」的根因是**本域没有收藏入口** —— 旧版长按只有一个动作
    // 「从房间歌单删除」，用户在房间里想收藏时唯一能按的长按反而把歌删了。
    // 现在长按先出操作浮层：收藏/取消收藏（真写接口）+ 从房间歌单删除，两个动作都写清楚。
    var actionItem by remember { mutableStateOf<Pair<Int, Song>?>(null) }
    var recommend by remember { mutableStateOf<List<Song>>(emptyList()) }
    val flt = ListenSession.fltMode
    var pulling by remember { mutableStateOf(false) }
    var pullTick by remember { mutableStateOf(0) }
    // 「换一批」：官方推荐没有独立端点，换一批 = 重新拉一次 sync/playlist/get
    var rcmdTick by remember { mutableStateOf(0) }
    var rcmdBusy by remember { mutableStateOf(false) }
    // ★ 2026-10-23 修「换一批官方推荐不可用」：旧版点完只有列表静默不动——
    //   「网络失败 / 官方这批没变 / 详情拉取失败 / 真的换了」四种结局在 UI 上一模一样，
    //   用户只能判断成「按钮没反应」。这里把结果文案显式摆出来，成功也回一句，
    //   让每次点击都有可见反馈（失败可重试，不会让人以为功能坏了）。
    var rcmdNote by remember { mutableStateOf("") }
    // 顶部「搜索歌曲添加」：非空时列表整体切成搜索结果，清空即回房间歌单
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    // ★ 2026-10-23：「换一批」公共逻辑抽成局部函数（打开浮层 / 点按钮 / 轮询更新三处复用）。
    //   旧版三个问题都在这里收口：
    //   ① 结果不落地：refreshRecommendation() 的返回值被直接丢掉，失败/没变化都是
    //      「列表纹丝不动」，用户判定为按钮坏了 → 现在一律写 rcmdNote 文案；
    //   ② 失败被吞：runCatching{}.getOrDefault(emptyList()) 把详情接口失败吞成空列表，
    //      和「官方没给推荐」混为一谈 → 改用 recommendSongsOrNull() 区分；
    //   ③ busy 卡死：调用方统一 try/finally + withTimeout，异常路径也一定复位。
    suspend fun fetchRecommendOnce() {
        val r = ListenSession.refreshRecommendationDetailed()
        val fresh = ListenSession.recommendSongsOrNull()
        if (fresh != null) recommend = fresh
        rcmdNote = when {
            r == ListenSession.RcmdResult.FAILED -> ListenSession.recommendNote.ifEmpty { "取推荐失败 · 请重试" }
            fresh == null -> "推荐详情加载失败 · 请重试"
            else -> ListenSession.recommendNote
        }
    }

    /**
     * 打开浮层/首次进页时的推荐加载（2026-10-24 新增）：**只展示，不换批**。
     *
     * 用户反馈「每次进房间歌单页没点换一批，推荐也自动换了一批」——
     * 旧版 LaunchedEffect(pullTick) 直接调 [fetchRecommendOnce] → [ListenSession.refreshRecommendationDetailed]，
     * 官方本房不下发推荐时该函数必然走本地兜底链（心动/FM），于是每打开一次就换一批。
     * 现在：已有批次直接展示；没有批次只向官方补拉一次（[ListenSession.syncRcmdOnce]），
     * 不产生本地批次；换批只由「换一批」按钮（rcmdTick）触发。
     */
    suspend fun loadRecommendOnce() {
        ListenSession.syncRcmdOnce()
        val fresh = ListenSession.recommendSongsOrNull()
        if (fresh != null) recommend = fresh
        rcmdNote = when {
            fresh == null -> "推荐详情加载失败 · 请重试"
            else -> "" // 没有推荐时交给空态文案；不要把"打开页面"伪装成"已换一批"
        }
    }

    // 打开即主动拉一次房间歌单（旧版只被动等轮询：轮询退避/失败时歌单永远空着，
    // 用户看到的就是「加载不出来」）。重试按钮把 pullTick +1 再跑一遍。
    // ★ 2026-10-01：这里**同时**刷一次官方推荐（sync/playlist/get 的
    //   displayList.rcmdSongIds）—— 旧版只等后台轮询写 rcmdSongIds，浮层打开时
    //   推荐往往还是上一批甚至空的，用户看到的就是「推荐和官方对不上」。
    LaunchedEffect(pullTick) {
        pulling = true
        runCatching { ListenSession.pullRoomQueueNow() }
        pulling = false
        rcmdBusy = true
        try {
            // ★ 2026-10-24：打开浮层只**展示**已有推荐（无批次才向官方补拉一次），
            //   不再调 fetchRecommendOnce（那会走本地兜底 → 每次进页面自动换一批）。
            kotlinx.coroutines.withTimeoutOrNull(15_000L) { loadRecommendOnce() }
                ?: run { rcmdNote = "加载推荐超时 · 可点换一批重试" }
        } finally {
            rcmdBusy = false
        }
    }

    // 「换一批」：重拉官方播放列表响应，再解析其中的 rcmdSongIds。
    // ★ 2026-10-23：busy 用 try/finally + 15s 超时兜底 —— 旧版若请求悬挂/effect 被取消，
    //   「正在取官方推荐…」会一直挂着、按钮再也点不动，这正是「换一批不可用」的直接表现。
    LaunchedEffect(rcmdTick) {
        if (rcmdTick == 0) return@LaunchedEffect
        rcmdBusy = true
        try {
            kotlinx.coroutines.withTimeoutOrNull(15_000L) { fetchRecommendOnce() }
                ?: run { rcmdNote = "取推荐超时 · 请重试" }
        } finally {
            rcmdBusy = false
        }
    }

    // ★ 2026-10-23：后台轮询（syncFromServer）拿到**新推荐批次**时自动刷新列表。
    //   旧版 recommend 只在「打开浮层 / 点换一批」时拉一次：打开时官方还没下发推荐
    //   （rcmdSongIds 为空）就永远停在一句「官方这批还没给推荐」，之后官方补了也不显示。
    //   这也是「换一批没反应」的一种——按钮拉到的那批没变，而真实推荐其实已经到了。
    LaunchedEffect(ListenSession.rcmdUpdatedAt) {
        if (ListenSession.rcmdSongIds.isEmpty()) return@LaunchedEffect
        ListenSession.recommendSongsOrNull()?.let { recommend = it }
    }

    // 搜索防抖 350ms（与「添加歌曲」浮层同规格）；清空即回房间歌单
    LaunchedEffect(query.text) {
        val kw = query.text.trim()
        if (kw.isEmpty()) { results = emptyList(); searching = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(350)
        searching = true
        results = runCatching { NcmApi.search(kw).songs }.getOrDefault(emptyList())
        searching = false
    }
    val kw = query.text.trim()

    Box(
        Modifier
            .fillMaxSize()
            // 浮层底：97% 不透明主题底色。旧版 82% 黑透出底下房间页（状态行/旋转封面/弧形底栏
            // 全部叠印，四周是遮罩×深色背景的宽黑边），观感即「黑边 + 裁切」
            .background(listenOverlayBackdrop())
            .pointerInput(Unit) {
                var totalX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalX = 0f },
                    onDragEnd = { if (abs(totalX) > 60.dp.toPx()) onDismiss() },
                ) { change, amount -> change.consume(); totalX += amount }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
    ) {
        // 标题区已删、关闭钮悬浮：列表吃满整屏（旧版底部固定钮区是一大块永远填不满的黑）
        // ★ 2026-10-01：搜索框从「固定表头」改为**列表第一项**。
        //   固定表头会在搜索框上方永久留出一条不滚动的空带（用户反馈「搜索框上方那条
        //   黑带，去不掉」），且列表内容永远顶不到最上面。改为列表项后：无固定表头、
        //   列表可顶到最上、搜索框随列表自然滚走。
        //   圆屏安全内缩用的纵向中心只在**列表静止（回到顶部）**时采样一次；滚动中不跟随
        //   —— 跟随会让输入框宽度随位置变化而抖动，还可能形成测量回环。
        var fieldCenterY by remember { mutableStateOf(0f) }
        val fieldInsetDp = with(androidx.compose.ui.platform.LocalDensity.current) {
            circleSafeInsetPx(fieldCenterY).toDp()
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp)
                .rotaryList(listState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(top = 12.dp, bottom = 78.dp),
            ) {
                // ★ 最上方：搜索歌曲添加（输入即搜，点结果直接进房间歌单；清空回房间歌单）
                // ★ 圆屏适配：这一条贴着屏幕最上缘，而窗口铺满 466×466 方屏、四角被圆表圈
                //   物理切掉 —— 通宽输入框的两端会被弧边吃掉（表现为两头被裁切）。
                //   这里按输入框自己的纵向中心算出该高度处圆内的可用宽度，左右内缩；
                //   内缩只改横向宽度、不改纵向位置，因此不会产生测量回环。
                item(key = "search") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { c ->
                                // 只在列表静止时采样（见上方说明）
                                val atRest = com.netmusiclite.ui.m3e.listFirstIndex(listState) == 0 &&
                                    com.netmusiclite.ui.m3e.listFirstOffset(listState) == 0
                                if (atRest) {
                                    val cy = c.positionInRoot().y + c.size.height / 2f
                                    if (abs(cy - fieldCenterY) > 0.5f) fieldCenterY = cy
                                }
                            }
                            .padding(horizontal = fieldInsetDp),
                    ) {
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 11.sp, color = TextPrimary),
                            cursorBrush = SolidColor(Accent),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(50))
                                .background(SurfaceGlass)
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            // ★ 2026-10-24 用户需求：搜索框恢复「搜点什么」占位（单行，不改变框高）。
                            decorationBox = { inner ->
                                Box(Modifier.fillMaxWidth()) {
                                    if (query.text.isEmpty()) {
                                        Text("搜点什么", fontSize = 11.sp, color = TextSecondary)
                                    }
                                    inner()
                                }
                            },
                        )
                    }
                }
                if (kw.isNotEmpty()) {
                    // ---- 搜索结果态：点一首即加进房间歌单 ----
                    when {
                        searching -> item(key = "s_wait") {
                            Box(
                                Modifier.fillMaxWidth().height(120.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("搜索中…", fontSize = 11.sp, color = TextSecondary) }
                        }
                        results.isEmpty() -> item(key = "s_none") {
                            Box(
                                Modifier.fillMaxWidth().height(120.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("没有找到「${kw.take(8)}」", fontSize = 11.sp, color = TextSecondary) }
                        }
                        else -> itemsIndexed(results, key = { _, s -> "s${s.id}" }) { _, s ->
                            StackedSongCard(song = s, height = 52.dp, onClick = { onPickRecommend(s) })
                        }
                    }
                } else {
                    // ★ 添加歌曲已移到列表**最上方**（原在列表最底部，得一路滚到底才点得到）
                    // FLT 乐迷团房：成员只跟听无控歌权（本地加歌会被轮询拉回），隐藏入口
                    if (!flt) item(key = "add") {
                        Text(
                            "＋ 添加歌曲到房间",
                            fontSize = 11.sp, color = Accent,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(50))
                                .background(Accent.copy(alpha = 0.14f))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onAddSong() }
                                .padding(vertical = 10.dp),
                        )
                    }

                    // ★ 2026-10-01：官方歌单的「换一批」从「房间推荐」标题行移到这里 ——
                    //   紧贴「＋ 添加歌曲到房间」正下方（用户口径）。玻璃底 + 主文字色，
                    //   与上面那颗强调色胶囊形成主次，不会两条抢眼。
                    if (!flt) item(key = "rcmd_refresh") {
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                // ★ 2026-10-23 二轮：本房官方不下发推荐时走的是本地「按口味」兜底，
                                //   按钮文案必须跟着来源改名（否则用户以为还是官方那批、换不动就=坏了）
                                when {
                                    rcmdBusy -> "正在取推荐…"
                                    ListenSession.rcmdSource == ListenSession.RcmdSource.LOCAL -> "换一批按口味推荐"
                                    else -> "换一批官方推荐"
                                },
                                fontSize = 11.sp,
                                color = if (rcmdBusy) TextSecondary else TextPrimary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(50))
                                    .background(SurfaceGlass)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { if (!rcmdBusy) rcmdTick++ }
                                    .padding(vertical = 10.dp),
                            )
                            // ★ 2026-10-23：点击结果文案（已换 / 官方没换 / 网络失败 / 超时）。
                            //   旧版点完毫无反馈，四种结局长得一模一样 → 用户只能判断成「按钮没用」。
                            if (rcmdNote.isNotEmpty() && !rcmdBusy) {
                                Text(
                                    rcmdNote,
                                    fontSize = 8.sp, color = TextSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 3.dp, start = 6.dp, end = 6.dp),
                                )
                            }
                        }
                    }

                    // ★ 2026-10-23 二轮：队列上报失败**不再静默**。
                    //   旧版 queueSyncNote 只在「歌单为空」时展示 → 非房主加歌被服务端拒、
                    //   导致本地有/全房没有时，界面上完全看不出来（用户以为加成功了）。
                    //   现在只要上报失败/被拒就常显一条（有内容才显示，成功后自动清空）。
                    if (!flt && ListenSession.queueSyncNote.isNotEmpty()) {
                        item(key = "queue_sync_note") {
                            Text(
                                text = ListenSession.queueSyncNote,
                                fontSize = 8.sp,
                                color = if (ListenSession.queueReportRejected) Color(0xFFFF8A65)
                                else TextSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp, start = 6.dp, end = 6.dp),
                            )
                        }
                    }

                    if (queue.isEmpty()) {
                        item(key = "empty") {
                            Column(
                                Modifier.fillMaxWidth().padding(top = 40.dp, start = 26.dp, end = 26.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    when {
                                        pulling -> "歌单加载中…"
                                        ListenSession.queueSyncNote.isNotEmpty() -> ListenSession.queueSyncNote
                                        flt -> "乐迷团歌单未返回内容"
                                        else -> "歌单还是空的\n点上方「＋ 添加歌曲到房间」或搜索添加"
                                    },
                                    fontSize = 10.sp, color = TextSecondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (!pulling) {
                                    Spacer(Modifier.height(14.dp))
                                    Text(
                                        "重新拉取歌单",
                                        fontSize = 11.sp, color = Accent,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(Accent.copy(alpha = 0.14f))
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                            ) { pullTick++ }
                                            .padding(horizontal = 20.dp, vertical = 9.dp),
                                    )
                                }
                            }
                        }
                    }
                    // ★ 2026-10-01：key 用「下标+id」复合键。房间歌单由服务端同步，
                    //   出现重复 id（别人重复上报、旧版「下一首播放」复制）时单键会抛
                    //   IllegalArgumentException 让整个浮层崩掉；复合键恒唯一。
                    itemsIndexed(queue, key = { i, s -> "q$i-${s.id}" }) { i, s ->
                        StackedSongCard(
                            song = s,
                            isCurrent = PlayerEngine.current?.id == s.id,
                            height = 52.dp,
                            onClick = { onPick(i) },
                            // ★ 2026-10-23：长按改为**操作浮层**（收藏/取消收藏 + 从房间歌单删除）。
                            //   旧版长按直接删除：用户想收藏时按了长按，歌被删了还以为是「收藏没反应」。
                            onLongClick = { actionItem = i to s },
                        )
                    }

                    // ---- 房间推荐（官方 displayList.rcmdSongIds） ----
                    // 标题常显：推荐为空时也能看出是「官方这批没给推荐」，而不是像旧版那样
                    // 整块消失、无从判断。「换一批」按钮已上移到「＋ 添加歌曲到房间」下方。
                    // FLT 乐迷团房无本地歌权，推荐入口整体隐藏。
                    if (!flt) {
                        // ★ 2026-10-24（用户反馈：推荐的歌曲不会进入播放列表，一直晾在推荐区）：
                        //   推荐列表按「已在房间歌单」过滤 —— 点一首加进歌单后，它应立即从推荐区消失，
                        //   房间歌单/播放队列里出现；旧版只在打开浮层时取一次 recommend，
                        //   加歌后不重算，于是同一首歌一直挂在推荐区，看起来像没加进去。
                        val rcmdVisible = recommend.filterNot { ListenSession.inQueue(it.id) }
                        item(key = "rcmd_head") {
                            Text(
                                // ★ 2026-10-23 二轮：来源跟着 rcmdSource 走（官方 vs 按口味兜底）
                                if (ListenSession.rcmdSource == ListenSession.RcmdSource.LOCAL)
                                    "按口味推荐 · 本机推荐源（官方本房未下发）"
                                else "房间推荐 · 官方同款推荐歌单",
                                fontSize = 9.sp, color = TextSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp, bottom = 2.dp, start = 4.dp),
                            )
                        }
                        if (rcmdVisible.isEmpty()) {
                            item(key = "rcmd_empty") {
                                Text(
                                    // ★ 2026-10-23：区分三种空态。旧版一律「官方这批还没给推荐」，
                                    //   把「详情接口失败」也说成官方没给，用户点换一批只会更困惑。
                                    // ★ 2026-10-23 二轮：优先展示 data 层给的真实结局文案
                                    //   （已换 / 官方未下发改按口味 / 详情失败 / 网络失败），
                                    //   不再把「本地兜底」也说成「官方没给推荐」。
                                    when {
                                        rcmdBusy -> "正在取推荐…"
                                        ListenSession.recommendNote.isNotEmpty() -> ListenSession.recommendNote
                                        ListenSession.rcmdSource == ListenSession.RcmdSource.LOCAL ->
                                            "还没有推荐 · 点上方「换一批按口味推荐」重取"
                                        else -> "还没有推荐 · 点上方「换一批」重取"
                                    },
                                    fontSize = 9.sp, color = TextSecondary,
                                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp),
                                )
                            }
                        } else {
                            itemsIndexed(rcmdVisible, key = { i, s -> "r$i-${s.id}" }) { _, s ->
                                StackedSongCard(
                                    song = s,
                                    height = 52.dp,
                                    onClick = { onPickRecommend(s) },
                                )
                            }
                        }
                    }
                }
            }
        }
        // ★ 2026-10-23：长按房间歌单条目后的操作浮层。
        //   为什么加：「一起听收藏不可用」的根因是一起听域根本没有收藏入口；
        //   本页长按旧版直接删除，用户在房间里收藏无门。现在长按先出本浮层，
        //   收藏走真写接口（NcmApi.likeSong），删除仍是原来的房间歌单移除。
        actionItem?.let { pair ->
            val (idx, s) = pair
            val liked = PlayerEngine.isLiked(s.id)
            DialogScrim(onDismiss = { actionItem = null }) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(BgElevated)
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
                    // 收藏状态用引擎里的红心集合反查：显示「取消收藏」即代表当前已收藏
                    RoomActionRow(if (liked) "取消收藏" else "收藏", Accent) {
                        actionItem = null
                        onToggleLike(s)
                    }
                    Spacer(Modifier.height(6.dp))
                    RoomActionRow("从房间歌单删除", TextSecondary) {
                        actionItem = null
                        onRemove(idx)
                    }
                }
            }
        }
        // 悬浮关闭钮：叠在列表上方底部居中（列表 contentPadding bottom 已留出空间）
        CircleIconButton(
            NcmIcons.Close, "关闭", onDismiss,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            size = 36.dp, iconSize = 15.dp,
        )
    }
}

/**
 * 房间歌单长按操作浮层里的单个动作按钮（胶囊）。
 * 视觉与搜索页长按菜单的 SongActionRow 同规格：玻璃底 + 动作色描边，
 * 深/浅两套主题下都不会出现「浅字压浅底看不见」。本文件不能用搜索页的 private 实现，
 * 故按同一形态在本地复刻（不新造样式）。
 */
@Composable
private fun RoomActionRow(
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    Text(
        label,
        fontSize = 10.sp, color = color, fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(SurfaceGlass)
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(50))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(vertical = 8.dp),
    )
}

// ==================== 添加歌曲浮层 ====================

/**
 * 从「我喜欢的音乐」或**搜索结果**选曲追加进房间歌单（房内所有人可加）。
 * 点卡片单选即加；长按任意卡片进入多选态（右侧圆形勾选角标），选完点底部确认胶囊批量添加。
 * 顶部搜索胶囊 350ms 防抖走 /cloudsearch/get/web，清空即回我喜欢的列表。
 */
@Composable
private fun AddSongOverlay(
    onDismiss: () -> Unit,
    onPick: (Song) -> Unit,
    onPickMany: (List<Song>) -> Unit,
) {
    // 系统 back（含华为式被系统吃掉的右滑）：先关本浮层，不动导航（2026-10-07 同批修复）
    OverlayBackHandler { onDismiss() }
    val listState = rememberLazyListState()
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // 搜索
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    // 多选
    var multi by remember { mutableStateOf(false) }
    val selected = remember { mutableStateListOf<Long>() }

    var loadTick by remember { mutableStateOf(0) }
    LaunchedEffect(loadTick) {
        loading = true
        songs = runCatching { NcmApi.likedSongs(SessionStore.uid) }.getOrDefault(emptyList())
        loading = false
    }
    // 搜索防抖：清空即回我喜欢的列表
    LaunchedEffect(query.text) {
        val kw = query.text.trim()
        if (kw.isEmpty()) { results = emptyList(); searching = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(350)
        searching = true
        results = runCatching { NcmApi.search(kw).songs }.getOrDefault(emptyList())
        searching = false
    }
    val kw = query.text.trim()
    val display = if (kw.isEmpty()) songs else results

    fun exitMulti() { multi = false; selected.clear() }

    Box(
        Modifier
            .fillMaxSize()
            // 浮层底：97% 不透明主题底色。旧版 82% 黑透出底下房间页（状态行/旋转封面/弧形底栏
            // 全部叠印，四周是遮罩×深色背景的宽黑边），观感即「黑边 + 裁切」
            .background(listenOverlayBackdrop())
            .pointerInput(Unit) {
                var totalX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalX = 0f },
                    onDragEnd = { if (abs(totalX) > 60.dp.toPx()) onDismiss() },
                ) { change, amount -> change.consume(); totalX += amount }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
    ) {
        // 标题区已删、关闭钮悬浮：列表吃满整屏（与歌单浮层同规格）
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp)
                .rotaryList(listState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(10.dp))
            // 搜索胶囊：搜索结果与我喜欢的共用下方列表
            // ★ 2026-10-01 圆屏适配：这一条贴在屏幕最上缘，通宽输入框的两端会被弧边吃掉
            //   （真机表现是「搜索框两头被裁平」）。按它自己的纵向中心算出该高度处圆内的
            //   可用宽度，左右内缩；只改横向、不改纵向，不会产生测量回环。
            //   （与歌单浮层同一套做法，此前只有歌单浮层做了、这一层漏了。）
            //
            // ⚠ 硬约束：**框内文字必须恒为单行**（占位文案单行）。
            //   否则会自激：宽度变窄 → 占位文案折行 → 框变高 → 中心下移 → 内缩变小 →
            //   宽度变宽 → 折行消失 → 框变矮 → 中心上移 → 内缩变大 …… 每帧来回，
            //   真机表现就是「点开添加歌曲后整页疯狂抽动」。
            //
            // ★ 2026-10-24 用户需求（m3e 主题）：搜索框不再悬浮在顶部 ——
            //   改为列表第一项、随歌曲列表一起滚动，并与下方第一首歌曲间隔 20px（@2.0 ≈ 10dp）。
            val isM3e = com.netmusiclite.data.AppearancePrefs.isM3e
            var fieldCenterY by remember { mutableStateOf(0f) }
            val fieldInsetDp = with(androidx.compose.ui.platform.LocalDensity.current) {
                circleSafeInsetPx(fieldCenterY).toDp()
            }
            val searchField: @Composable () -> Unit = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { c ->
                            // m3e 下搜索框随列表滚动：只在列表静止时采样中心 y，
                            // 否则滚动中每帧变化 → 横向内缩抖动（见上方自激说明）。
                            val atRest = !isM3e ||
                                (com.netmusiclite.ui.m3e.listFirstIndex(listState) == 0 &&
                                    com.netmusiclite.ui.m3e.listFirstOffset(listState) == 0)
                            if (atRest) {
                                val cy = c.positionInRoot().y + c.size.height / 2f
                                if (abs(cy - fieldCenterY) > 0.5f) fieldCenterY = cy
                            }
                        }
                        .padding(horizontal = fieldInsetDp),
                ) {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(fontSize = 11.sp, color = TextPrimary),
                        cursorBrush = SolidColor(Accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(50))
                            .background(SurfaceGlass)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        // ★ 2026-10-24 用户需求：搜索框恢复「搜点什么」占位（单行，不改变框高）。
                        decorationBox = { inner ->
                            Box(Modifier.fillMaxWidth()) {
                                if (query.text.isEmpty()) {
                                    Text("搜点什么", fontSize = 11.sp, color = TextSecondary)
                                }
                                inner()
                            }
                        },
                    )
                }
            }
            if (!isM3e) {
                searchField()
                Spacer(Modifier.height(6.dp))
            }

            if (isM3e) {
                LazyColumn(
                    Modifier.weight(1f),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 78.dp),
                ) {
                    item(key = "search") {
                        searchField()
                        Spacer(Modifier.height(10.dp)) // 20px @2.0：与下方第一首歌曲的间隔
                    }
                    when {
                        loading -> item(key = "loading") {
                            Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                                Text("加载中…", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                        searching -> item(key = "searching") {
                            Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                                Text("搜索中…", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                        display.isEmpty() -> item(key = "empty") {
                            Column(
                                Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 24.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    when {
                                        kw.isNotEmpty() -> "没有找到「${kw.take(8)}」"
                                        SessionStore.uid <= 0L -> "未登录，读不到「我喜欢的音乐」\n可直接用上方搜索添加"
                                        else -> "「我喜欢的音乐」为空\n可用上方搜索添加"
                                    },
                                    fontSize = 11.sp, color = TextSecondary, textAlign = TextAlign.Center,
                                )
                                if (kw.isEmpty()) {
                                    Spacer(Modifier.height(14.dp))
                                    Text(
                                        "重新加载",
                                        fontSize = 11.sp, color = Accent,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(Accent.copy(alpha = 0.14f))
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                            ) { loadTick++ }
                                            .padding(horizontal = 20.dp, vertical = 9.dp),
                                    )
                                }
                            }
                        }
                        else -> itemsIndexed(display, key = { _, s -> s.id }) { _, s ->
                            StackedSongCard(
                                song = s,
                                height = 52.dp,
                                selectMode = multi,
                                selected = s.id in selected,
                                onLongClick = {
                                    if (!multi) { multi = true }
                                    if (s.id !in selected) selected.add(s.id)
                                },
                                onClick = {
                                    if (multi) {
                                        if (s.id in selected) selected.remove(s.id) else selected.add(s.id)
                                    } else onPick(s)
                                },
                            )
                        }
                    }
                }
            } else {
                when {
                    loading -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text("加载中…", fontSize = 11.sp, color = TextSecondary)
                    }
                    searching -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text("搜索中…", fontSize = 11.sp, color = TextSecondary)
                    }
                    display.isEmpty() -> Column(
                        Modifier.weight(1f).padding(horizontal = 26.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            when {
                                kw.isNotEmpty() -> "没有找到「${kw.take(8)}」"
                                SessionStore.uid <= 0L -> "未登录，读不到「我喜欢的音乐」\n可直接用上方搜索添加"
                                else -> "「我喜欢的音乐」为空\n可用上方搜索添加"
                            },
                            fontSize = 11.sp, color = TextSecondary, textAlign = TextAlign.Center,
                        )
                        if (kw.isEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "重新加载",
                                fontSize = 11.sp, color = Accent,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Accent.copy(alpha = 0.14f))
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { loadTick++ }
                                    .padding(horizontal = 20.dp, vertical = 9.dp),
                            )
                        }
                    }
                    else -> LazyColumn(
                        Modifier.weight(1f),
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(top = 2.dp, bottom = 78.dp),
                    ) {
                        itemsIndexed(display, key = { _, s -> s.id }) { _, s ->
                            StackedSongCard(
                                song = s,
                                height = 52.dp,
                                selectMode = multi,
                                selected = s.id in selected,
                                onLongClick = {
                                    if (!multi) { multi = true }
                                    if (s.id !in selected) selected.add(s.id)
                                },
                                onClick = {
                                    if (multi) {
                                        if (s.id in selected) selected.remove(s.id) else selected.add(s.id)
                                    } else onPick(s)
                                },
                            )
                        }
                    }
                }
            }
        }
        // 底部悬浮钮：多选态 = 确认胶囊（0 首时即「取消多选」）；单选态 = 关闭钮
        if (multi) {
            Text(
                if (selected.isEmpty()) "取消多选" else "添加 ${selected.size} 首到房间",
                fontSize = 11.sp, color = if (selected.isEmpty()) TextPrimary else Accent,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp)
                    .clip(RoundedCornerShape(50))
                    .background(SurfaceGlass)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (selected.isEmpty()) { exitMulti(); return@clickable }
                        val picked = display.filter { it.id in selected }
                        exitMulti()
                        onPickMany(picked)
                    }
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            )
        } else {
            CircleIconButton(
                NcmIcons.Close, "关闭", onDismiss,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
                size = 36.dp, iconSize = 15.dp,
            )
        }
    }
}

// ==================== 邀请好友浮层 ====================

/** 点底部「多人」钮进入：好友列表，点谁就邀请谁；顶部首项是「分享邀请链接」 */
@Composable
private fun InviteFriendsOverlay(
    onDismiss: () -> Unit,
    onInvite: (FriendInfo) -> Unit,
    /** 复制邀请链接并立刻 toast「邀请链接已复制」（复制逻辑与提示都在 ListenRoomScreen 内） */
    onShareLink: () -> Unit,
) {
    // 系统 back（含华为式被系统吃掉的右滑）：先关本浮层，不动导航（2026-10-07 同批修复）
    OverlayBackHandler { onDismiss() }
    val listState = rememberLazyListState()
    var friends by remember { mutableStateOf<List<FriendInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        friends = runCatching { NcmApi.friends(SessionStore.uid) }.getOrDefault(emptyList())
        loading = false
    }

    Box(
        Modifier
            .fillMaxSize()
            // 浮层底：97% 不透明主题底色。旧版 82% 黑透出底下房间页（状态行/旋转封面/弧形底栏
            // 全部叠印，四周是遮罩×深色背景的宽黑边），观感即「黑边 + 裁切」
            .background(listenOverlayBackdrop())
            .pointerInput(Unit) {
                var totalX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalX = 0f },
                    onDragEnd = { if (abs(totalX) > 60.dp.toPx()) onDismiss() },
                ) { change, amount -> change.consume(); totalX += amount }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
    ) {
        // 标题区已删、关闭钮悬浮：列表吃满整屏（与歌单浮层同规格）
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp)
                .rotaryList(listState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ★ 2026-10-23 新增：**一眼可见的「分享邀请链接」入口**。
            //   背景：一起听页原本没有任何分享按钮（`shareLink()` 只出现在本浮层「点某个好友」
            //   的回调里，用户想"点分享"根本没地方点，复制动作也没有可见反馈）。
            //   放在 Column 顶部：好友列表空 / 加载中 / 已加载三种状态都能用。
            //   点击后由 ListenRoomScreen 复制链接并立刻弹 toast「邀请链接已复制」。
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, start = 16.dp, end = 16.dp),
            ) {
                RoomShareRow { onShareLink() }
            }
            if (loading) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("加载好友…", fontSize = 11.sp, color = TextSecondary)
                }
            } else if (friends.isEmpty()) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        "还没有关注的人\n在网易云关注对方后即可邀请",
                        fontSize = 11.sp, color = TextSecondary, textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(top = 14.dp, bottom = 78.dp),
                ) {
                    items(friends, key = { it.id }) { f ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(30.dp))
                                .background(SurfaceGlass)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onInvite(f) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CoverImage(f.avatarUrl, 30.dp, shape = CircleShape)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                f.name, fontSize = 12.sp, color = TextPrimary,
                                maxLines = 1, modifier = Modifier.weight(1f),
                            )
                            Text("邀请", fontSize = 9.sp, color = Accent, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        CircleIconButton(
            NcmIcons.Close, "关闭", onDismiss,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            size = 36.dp, iconSize = 15.dp,
        )
    }
}

/**
 * 邀请浮层顶部的「分享邀请链接」卡（2026-10-23 新增）。
 *
 * 一起听内唯一的分享动作就是复制邀请链接：点一下 → `ListenSession.shareLink()` →
 * clipboard.setText → 立刻 toast「邀请链接已复制」（复用本页 NcmToast，不新造弹窗组件）。
 * 房间还没拿到服务端真房号时 shareLink() 返回 null，本行不发复制、只提示「未就绪」。
 */
@Composable
private fun RoomShareRow(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(Accent.copy(alpha = 0.16f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(Accent.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(NcmIcons.Copy, null, tint = Accent, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "分享邀请链接", fontSize = 12.sp, color = TextPrimary,
                fontWeight = FontWeight.Medium, maxLines = 1,
            )
            Text(
                "复制后发给好友，点开即可进房", fontSize = 9.sp, color = TextSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text("复制", fontSize = 9.sp, color = Accent, fontWeight = FontWeight.SemiBold)
    }
}

/*
 * 关于聊天（2026-09-13 通道定性终稿，匿名参数矩阵 + 真机双重复现；2026-10-01 补 chatRoomId 兜底）：
 * - **读**：`listen/together/multi/special/msg/history`（官方 Lad0/v;->e）是一起听房
 *   （含乐迷团 SELFHOST 房）唯一可用的 HTTP 公共聊天通道；参数硬约束 size=50 / direction=0，
 *   其它组合一律 code 301。歌房那套 middle/im/chatroom/msg/history/query 对乐迷团房是
 *   网关级 400 空 body（即旧日志的 `End of input at character 0 of`），已作降级补充 + 自动判死。
 * - **发**：`middle/im/chatroom/send` 与 `middle/im/chatroom/special/send` 是官方 dex 里的
 *   活路由（匿名 400 空 body = 网关拒绝而非 404），本端按「路径 × 口径 × chatroomId 变体」
 *   三维探测。2026-10-01 补了 chatRoomId 兜底解析（NcmApi.resolveChatRoomId，走中间层
 *   `middle/im/token-and-chatroom-addr/get`）——此前 status 恢复会话或服务端本拍没下发
 *   `roomExt.chatRoomId` 时该字段为 null，房内发送/接收整条通道都不启动。
 *   ⚠ 官方客户端房内发言最终走云信（NIM）长连接，HTTP 通道能否落库取决于房型与登录态；
 *   未命中时不静默：乐迷团房明确提示，普通房退私信桥接（正文带 `[一起听]` 前缀）。
 * - **邀请**：`listen/together/invite/message/send {roomId, acceptorId, ltType}`（单发）与
 *   `listen/together/multi/invite {roomId, groupIds, inviteUids}`（批量）是官方邀请通道，
 *   邀请落进对方「一起听」消息箱（可点接受），比私信分享链接可靠（见 ListenSession.invite）。
 * - 历史：面板首次打开即拉到进房前的最近一页（50 条）房内消息，分隔线区分。
 */
