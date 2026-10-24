package com.netmusiclite.ui.m3e

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.netmusiclite.data.ChatMessage
import com.netmusiclite.data.Song
import com.netmusiclite.ui.components.CoverImage
import com.netmusiclite.ui.components.InviteCaption
import com.netmusiclite.ui.components.ListenInviteCard
import com.netmusiclite.ui.components.copyOnLongPress
import com.netmusiclite.ui.components.inviteCaption
import com.netmusiclite.ui.components.pressStretch
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.songCoverModel

@Composable
fun M3eContactRow(name: String, avatar: String?, subtitle: String?, trailing: String?, onClick: () -> Unit, onProfile: (() -> Unit)? = null) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().pressStretch(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        transformation = LocalItemTransformation.current,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)) {
        if (onProfile != null) IconButton(onProfile, Modifier.size(36.dp).pressStretch()) {
            CoverImage(avatar, 36.dp, shape = CircleShape)
        } else CoverImage(avatar, 36.dp, shape = CircleShape)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(name, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!trailing.isNullOrBlank()) Text(trailing, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

/**
 * m3e 私信气泡。
 *
 * ★ 2026-10-18 用户口径：「一个人发多条消息合并到一起，只显示最上面那个的头像，
 *   头像相较当前增大 50%，放置在消息上方」——
 *  · [showAvatar] = 这一段连续消息的**第一条**（由 itemsIndexedMessages 判定：
 *    同一个发言人 + 与上一条间隔 ≤ 5 分钟）。组内其余消息不再重复头像，
 *    只留等宽空白，气泡左边界仍然对齐；
 *  · 头像 20dp → 30dp（+50%），并且与**气泡顶部**对齐（原来是垂直居中）——
 *    视觉上就是「这段消息的作者标识挂在最上面那条旁边」。
 *
 * ★ 2026-10-24 用户口径②「m3e 私信界面消息不允许互相堆叠影响观感」——
 *   「合并」保留，但两条都要改：
 *  · 组内间距 1dp → 上下各 5dp、左右各 10dp：旧版只有组首留 6dp，组内条目几乎贴在一起，
 *    加上下面那条列表模式问题，看着就是一摞而不是对话；
 *  · **真正的根因在列表模式**：私信列表走 `AdaptiveLazyColumn`，m3e 默认
 *    [M3eListMode.WEAR] 会用 `transformedHeight` **压缩每条的占位高度**，内容比压缩后的
 *    槽位高就溢出、压到邻居 —— 就是评论区 2026-10-17 已经修过的同款「堆叠」
 *    （见 CommentsScreen 那条注释）。ChatScreen 的列表已改成 [M3eListMode.SINK]：
 *    条目占真实高度、不再互相遮盖，气泡自己挂 [m3eSinkFromCenter] 保留下沉观感。
 */
@Composable
fun M3eChatMessage(
    message: ChatMessage,
    avatar: String?,
    linkId: Long,
    onOpenSong: (Song) -> Unit,
    showAvatar: Boolean = true,
    /**
     * ★ 2026-10-22 用户口径⑤「m3e 好友私信界面点击头像查看好友主页」：
     * 非空 = 对方头像是一颗独立可点区域（m3e 主题下由 ChatScreen 传入
     * `Routes.userPage(friendId)`）。头像在气泡 [Card] 之外、与它并排但**不重叠**，
     * 所以点头像只会命中这层 clickable，不会连带触发整行/气泡的 onClick。
     *
     * 刻意不用 IconButton 包：Wear M3 的 IconButton 有最小触摸目标（≥40dp），
     * 会把 30dp 头像撑大、整段合并消息的行高跟着变 —— 直接给 CoverImage 挂
     * clickable，尺寸与改动前逐像素一致。无涟漪（indication = null）用
     * [pressStretch] 的缩放反馈代替，与项目其它头像入口一致。
     */
    onProfile: (() -> Unit)? = null,
    /**
     * ★ 2026-10-24 需求④：这条私信**认出了一起听邀请**（[com.netmusiclite.ui.components.listenInviteOf]）。
     * 非空 = 不显示那串几百字符的 URL 气泡，改画一张 [com.netmusiclite.ui.components.ListenInviteCard]：
     * 点卡片直接进房，卡片下面留一行截断短链（长按仍可复制整条原文）。
     * 歌曲卡片优先级更高（同一句话既带歌又带房号的情况没有）。
     */
    invite: com.netmusiclite.data.ChatInvite? = null,
    joinBusy: Boolean = false,
    onJoin: (() -> Unit)? = null,
    /** 邀请卡副标题（调用方给：「来自 昼小风 · 点卡片进房」这类，本机知道好友昵称） */
    inviteSubtitle: String = "",
) {
    val song = message.song
    val colors = MaterialTheme.colorScheme
    val sending = message.id == 0L && message.fromMe
    val actionable = song != null || linkId > 0
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp)
            .m3eSinkFromCenter(),
        verticalAlignment = if (showAvatar) Alignment.Top else Alignment.CenterVertically,
        // ★ 2026-10-23 需求②（用户口径「m3e 私信界面的消息卡片空白区域过大」）：
        //   根因是气泡用了 weight(1f) —— 每条消息都撑满整行，自己发的也不例外，
        //   文字缩在左侧、右侧是一大片卡片底。现在气泡按内容收缩（wrapContentWidth），
        //   自己发的靠右、对方靠左（微信式），空白不再是「卡片本身」。
        horizontalArrangement = if (message.fromMe) Arrangement.End else Arrangement.Start,
    ) {
        if (!message.fromMe) {
            if (showAvatar) {
                val avatarModifier = if (onProfile != null) {
                    Modifier
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onProfile() }
                        .pressStretch()
                } else Modifier
                CoverImage(avatar, 30.dp, avatarModifier, shape = CircleShape)
                Spacer(Modifier.width(5.dp))
            } else {
                Spacer(Modifier.width(35.dp)) // 30dp 头像 + 5dp 间距：组内气泡左边界不变
            }
        } else Spacer(Modifier.width(16.dp))
        val bubbleColor = if (message.fromMe) colors.primaryContainer else colors.surfaceContainer
        val bubbleContent = if (message.fromMe) colors.onPrimaryContainer else colors.onSurface
        val inviteCard = invite?.takeIf { song == null }
        if (inviteCard != null) {
            // ★ 2026-10-24 需求④：一起听邀请不再是一串糊在气泡里的 URL，
            //   改画一张可点邀请卡 + 一行截断短链。点卡片=进房，长按=复制整条原文。
            Column(horizontalAlignment = if (message.fromMe) Alignment.End else Alignment.Start) {
                ListenInviteCard(
                    subtitle = inviteSubtitle,
                    busy = joinBusy,
                    onJoin = { onJoin?.invoke() },
                    onCopyText = message.text,
                )
                InviteCaption(inviteCaption(inviteCard.code), message.text)
            }
        } else if (song == null) {
            // ★ 2026-10-24 用户口径「m3e 私信界面消息四周空白过多」。根因不在我们给的 9dp：
            //   Wear M3 的 `Card` 内部**无条件**追加 `defaultMinSize(minHeight = CardDefaults.Height)`，
            //   而 `CardDefaults.Height = CardTokens.containerMinHeight = 64.dp`
            //   （compose-material3 1.5.6 字节码确证）。一行「好的」于是被撑成 64dp 高的空盒子，
            //   文字上下各留 ~25dp 空白 —— 调用侧改 contentPadding 完全压不住它。
            //   所以文本气泡不再走 Card，直接自绘同材质气泡：同样的 primaryContainer /
            //   surfaceContainer + shapes.large + 同样的可点区与按压缩放，高度贴着文字
            //   （横向 9dp、纵向 5dp）。
            //   歌曲气泡继续用 Card：32dp 封面 + 两行文字本来就接近那个高度，也需要最小可点尺寸。
            //   ★ 订正上一版的注释：私信列表在 m3e 下走的是**原生** TransformingLazyColumn
            //   （AdaptiveLazyColumn → NativeList），不是普通 LazyColumn —— 所以形变压缩才会
            //   把气泡挤到互相遮盖（= 需求②「堆叠」）。本列已改 SINK 模式：占真实高度、
            //   transformation 恒为 null，下沉由整行挂 [m3eSinkFromCenter] 做。
            Box(
                Modifier
                    .widthIn(max = 178.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(bubbleColor)
                    // 长按 = 复制面板（需求③）；单击仍是打开歌词链接里的歌。
                    // 合并成同一个 combinedClickable：另起一层 pointerInput 会让长按松手
                    // 时把 onClick 也触发（长按复制却把歌播了）。
                    .copyOnLongPress(message.text) {
                        if (linkId > 0) onOpenSong(Song(linkId, "", "", 0L, "", 0L, 0, null))
                    }
                    .graphicsLayer { alpha = if (sending) 0.6f else 1f }
                    .pressStretch(enabled = linkId > 0)
                    .padding(horizontal = 9.dp, vertical = 5.dp),
            ) {
                Text(
                    message.text.ifEmpty { "[消息]" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = bubbleContent,
                    // 气泡按内容收缩，单行时宽度=文字宽 + 对称内边距 ⇒ 视觉上同样居中
                    textAlign = TextAlign.Center,
                )
            }
        } else Card(onClick = { onOpenSong(song) }, modifier = Modifier
            // ★ 2026-10-23 需求②：weight(1f) → widthIn(max=178dp)。
            //   气泡宽度跟随内容收缩（wrapContentWidth），不再撑满整行；
            //   保留 178dp 上限 = 屏宽 233dp − 头像 35dp − 右侧留白，长文本仍在框内换行。
            .widthIn(max = 178.dp)
            .graphicsLayer { alpha = if (sending) 0.6f else 1f }
            .pressStretch(enabled = actionable),
            colors = CardDefaults.cardColors(
                containerColor = if (message.fromMe) colors.primaryContainer else colors.surfaceContainer,
                contentColor = if (message.fromMe) colors.onPrimaryContainer else colors.onSurface,
                titleColor = if (message.fromMe) colors.onPrimaryContainer else colors.onSurface),
            contentPadding = PaddingValues(9.dp), transformation = LocalItemTransformation.current) {
            // 本分支只剩歌曲卡（文本消息已走上面的自绘气泡）
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverImage(songCoverModel(song), 32.dp, shape = MaterialTheme.shapes.medium)
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f).copyOnLongPress(
                    message.text.ifEmpty { "${song.title} - ${song.artist}" },
                ) { onOpenSong(song) }) {
                    Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artist.ifEmpty { "单曲" }, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(NcmIcons.Play, "播放", Modifier.size(16.dp))
            }
        }
        if (message.fromMe) Spacer(Modifier.width(5.dp))
    }
}

@Composable
fun M3eChatComposer(input: TextFieldValue, onInput: (TextFieldValue) -> Unit, menuOpen: Boolean, onMenu: () -> Unit, onSend: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.width(176.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .padding(horizontal = 3.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onMenu, Modifier.size(32.dp).pressStretch()) { Icon(if (menuOpen) NcmIcons.Close else NcmIcons.Plus, "更多", Modifier.size(17.dp)) }
        // NO-NATIVE: Wear Material3 1.5.6 has no text field; Foundation is required for Android IME editing.
        BasicTextField(input, onInput, singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 7.dp),
            // ★ 2026-10-23 需求③（用户口径「所有文字输入框里的占位文字全部删除，只留空白框」）：
            //   删掉框内「说点什么…」，只保留 inner()；同排 IconButton 32dp 撑高 + 纵向 7dp
            //   内边距，删掉占位后高度不变、不会塌陷。
            decorationBox = { inner -> Box { inner() } })
        FilledTonalIconButton(onSend, Modifier.size(32.dp).pressStretch(enabled = input.text.isNotBlank()), enabled = input.text.isNotBlank()) { Icon(NcmIcons.Send, "发送", Modifier.size(17.dp)) }
    }
}

/**
 * m3e 私信的**圆弧胶囊**输入条（★ 2026-10-18 用户口径「m3e 风格主题私信界面聊天框
 * 没有在选择圆表的时候呈现圆弧胶囊状」）。
 *
 * 圆表没有底边可贴：一条水平直胶囊摆在圆里，两端会与表盘弧线打架。
 * 这里与经典主题同几何（圆心 = 屏幕中心、半径 96dp、64°~116°、stroke 34dp），
 * 只是材质换成 M3E 的 surfaceContainerHigh + 一圈 outline 描边，
 * 三个元素（更多 / 输入框 / 发送）沿用 [M3eChatComposer] 的尺寸与层级。
 *
 * 方表继续走 [M3eChatComposer] 的直胶囊 —— 两条路径都在 ChatScreen 里按 isRoundScreen 分流。
 */
@Composable
fun M3eChatComposerArc(
    input: TextFieldValue,
    onInput: (TextFieldValue) -> Unit,
    menuOpen: Boolean,
    onMenu: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier) {
        val cx = maxWidth / 2
        val cyc = maxHeight / 2
        val rB = 96.dp
        val stroke = 34.dp
        // 弧带底 + 一圈描边（M3E 卡片风格：容器色 + outline）
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val r = rB.toPx()
            val topLeft = androidx.compose.ui.geometry.Offset(
                (size.width - r * 2f) / 2f, (size.height - r * 2f) / 2f,
            )
            val arcSize = androidx.compose.ui.geometry.Size(r * 2f, r * 2f)
            drawArc(
                color = colors.surfaceContainerHigh,
                startAngle = 64f, sweepAngle = 52f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    stroke.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
            drawArc(
                color = colors.outlineVariant,
                startAngle = 64f, sweepAngle = 52f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    stroke.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round),
            )
        }
        // 左端：更多 / 关闭
        val radL = Math.toRadians(-52.0)
        IconButton(
            onMenu,
            Modifier.offset {
                androidx.compose.ui.unit.IntOffset(
                    (cx + rB * kotlin.math.sin(radL).toFloat() - 16.dp).roundToPx(),
                    (cyc + rB * kotlin.math.cos(radL).toFloat() - 16.dp).roundToPx(),
                )
            }.size(32.dp).pressStretch(),
        ) { Icon(if (menuOpen) NcmIcons.Close else NcmIcons.Plus, "更多", Modifier.size(17.dp)) }
        // 右端：发送
        val radR = Math.toRadians(52.0)
        FilledTonalIconButton(
            onSend,
            Modifier.offset {
                androidx.compose.ui.unit.IntOffset(
                    (cx + rB * kotlin.math.sin(radR).toFloat() - 16.dp).roundToPx(),
                    (cyc + rB * kotlin.math.cos(radR).toFloat() - 16.dp).roundToPx(),
                )
            }.size(32.dp).pressStretch(enabled = input.text.isNotBlank()),
            enabled = input.text.isNotBlank(),
        ) { Icon(NcmIcons.Send, "发送", Modifier.size(17.dp)) }
        // 中段：输入框（贴底部居中，落在弧带内 —— 与经典主题同一位置）
        BasicTextField(input, onInput, singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 7.dp)
                .width(112.dp)
                .padding(horizontal = 4.dp, vertical = 7.dp),
            // ★ 2026-10-23 需求③：删除圆表弧带输入框内的「说点什么…」占位（只留空白框）。
            //   该输入框用 bottom padding + width(112dp) 定位，高度由一行行高撑住，不会塌陷。
            decorationBox = { inner -> Box { inner() } })
    }
}
