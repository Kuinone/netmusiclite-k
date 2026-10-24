package com.netmusiclite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netmusiclite.data.AppearancePrefs
import com.netmusiclite.data.ChatInvite
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.OnAccent
import com.netmusiclite.ui.theme.SurfaceGlass
import com.netmusiclite.ui.theme.TextSecondary

private val roomParamRx = Regex("""roomId=([0-9A-Za-z_\-]{6,})""", RegexOption.IGNORE_CASE)
private val inviterRx = Regex("""inviter(?:Id|Uid)=(\d+)""", RegexOption.IGNORE_CASE)
private val urlRx = Regex("""https?://\S+""")

/**
 * 裸房号：普通房 = 长 hex + `_` + 时间戳（`b1e860d130ddd40aa1c74e9bd2a7ce5a_1792796122`）；
 * 乐迷团房 = `fla_/fl_` 三段（`fla_1030001_29d65e_1785859686`）。
 * 与 [com.netmusiclite.data.ListenSession.parseInvite] 的 hex 口径一致，另补 fla_/fl_ 前缀。
 */
private val bareRoomRx = Regex(
    """(?:fla?|fl)_[0-9A-Za-z]+_[0-9A-Za-z]+_\d+|[0-9a-fA-F]{16,}_\d{6,}""",
)

/**
 * 私信自动识别一起听链接（★ 2026-10-24 需求④）。认三种写法，认不出返回 null：
 *
 * 1. 本端「发起一起听」发出去的分享链接：
 *    `https://st.music.163.com/listen-together/share/?songId=..&roomId=..&inviterId=..`
 * 2. **任何**带 `roomId=` 的一起听网址（官方 App、活动页的参数写法变体）；
 * 3. 裸房号 —— 只有同一条消息里出现「一起听 / listen」才认，否则会把
 *    `music.163.com/song?id=…` 这类也带下划线/数字的串误判成房号，
 *    把普通分享变成一张进不去的卡。
 *
 * 命中后 UI 用 [ListenInviteCard] 渲染成一张可点的邀请卡（点一下直接进房），
 * 而不是把几百字符的 URL 糊在气泡里。
 *
 * 只负责**正文文本**这一路；官方 App 发来的邀请卡（bodyType=23 generalMsg）没有 URL，
 * 由 [com.netmusiclite.data.NcmApi.msgHistory] 预先解析成 [ChatInvite] 挂在消息上。
 */
fun listenInviteOf(text: String): ChatInvite? {
    if (text.isBlank()) return null
    val s = text.replace("&amp;", "&")
    roomParamRx.find(s)?.let { m ->
        val code = urlRx.find(s)?.value ?: m.value
        return ChatInvite(
            code = code,
            roomId = m.groupValues[1],
            inviterId = inviterRx.find(s)?.groupValues?.get(1)?.toLongOrNull() ?: 0L,
        )
    }
    if (s.contains("一起听") || s.contains("listen", ignoreCase = true)) {
        bareRoomRx.find(s)?.let { m ->
            return ChatInvite(code = m.value, roomId = m.value, inviterId = 0L)
        }
    }
    return null
}

/** 卡片下方那行短链：去掉协议头、截断，看得出是个链接；长按复制的是整条原文 */
fun inviteCaption(code: String): String =
    code.removePrefix("https://").removePrefix("http://")
        .let { if (it.length > 34) it.take(33) + "…" else it }

/**
 * 一起听邀请卡（私信专用）。整卡可点 = 进房；长按 = 复制这条消息原文。
 *
 * 两主题两套材质：经典沿用私信气泡那套 Accent 实底，m3e 用 primaryContainer ——
 * 与各自的气泡语言一致，不另发明第三种样式。
 */
@Composable
fun ListenInviteCard(
    subtitle: String,
    busy: Boolean,
    onJoin: () -> Unit,
    onCopyText: String,
    modifier: Modifier = Modifier,
) {
    val action = if (busy) "进房中…" else "点我进房"
    val m3e = AppearancePrefs.isM3e
    val bg = if (m3e) {
        val c = androidx.wear.compose.material3.MaterialTheme.colorScheme
        if (busy) c.primaryContainer.copy(alpha = 0.5f) else c.primaryContainer
    } else if (busy) Accent.copy(alpha = 0.45f) else Accent.copy(alpha = 0.85f)
    val fg = if (m3e) {
        androidx.wear.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer
    } else OnAccent
    Row(
        modifier.widthIn(max = 178.dp).clip(RoundedCornerShape(if (m3e) 18.dp else 12.dp))
            .background(bg)
            .copyOnLongPress(onCopyText, onClick = { if (!busy) onJoin() })
            .padding(horizontal = 9.dp, vertical = if (m3e) 7.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(NcmIcons.Together, "一起听", Modifier.size(17.dp), tint = fg)
        Spacer(Modifier.width(7.dp))
        Column(Modifier.width(108.dp)) {
            Text(
                "一起听邀请", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle, fontSize = 9.sp, color = fg,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            action, fontSize = 9.sp, color = fg, textAlign = TextAlign.End,
            modifier = Modifier.width(34.dp),
        )
    }
}

/** 邀请卡下面那行短链（灰、单行；长按同样能复制整条原文） */
@Composable
fun InviteCaption(caption: String, onCopyText: String, modifier: Modifier = Modifier) {
    Text(
        caption,
        fontSize = 8.sp,
        color = TextSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceGlass.copy(alpha = 0.35f))
            .combinedClickable(onClick = {}, onLongClick = { CopySheet.show(onCopyText) })
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
