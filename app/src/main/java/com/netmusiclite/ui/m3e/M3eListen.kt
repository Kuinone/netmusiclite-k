package com.netmusiclite.ui.m3e

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.LinearProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.netmusiclite.data.Song
import com.netmusiclite.ui.components.pressStretch
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.SongCover

/** UI only: the existing room owns all synchronization, permission checks and actions. */
@Composable
fun M3eListenRoomPanel(
    song: Song?,
    playing: Boolean,
    /** 当前房间歌曲是否已在「我喜欢」里（红心填充态） */
    liked: Boolean,
    /** 收藏 / 取消收藏当前歌（实现全在 ListenScreens.toggleRoomLike，这里只发事件） */
    onToggleLike: () -> Unit,
    isHost: Boolean,
    /** 本端是否可切歌：双人房（非乐迷团房）全员 true，乐迷团房只房主 true */
    canControl: Boolean,
    members: String,
    progress: () -> Float,
    playbackTime: String,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onMembers: () -> Unit,
    onChat: () -> Unit,
    onInvite: () -> Unit,
    onQueue: () -> Unit,
    onVolume: () -> Unit,
    onExit: () -> Unit,
) {
    M3eList(title = "一起听") {
        item(key = "song") {
            Card(onClick = onQueue, modifier = Modifier.fillMaxWidth().pressStretch(), transformation = LocalItemTransformation.current) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (song != null) {
                        SongCover(song, 48.dp, shape = MaterialTheme.shapes.medium)
                        Spacer(Modifier.width(8.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(song?.title ?: "等待同步…", style = MaterialTheme.typography.titleSmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(song?.artist ?: "房间暂无歌曲", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    // ★ 2026-10-23「收藏 / 取消收藏当前歌」：做成 song 卡片的 trailing 动作。
                    //   ① 语义最近：卡片显示的就是房间正在播的歌（PlayerEngine.current），收藏目标不用另找入口；
                    //   ② IconButton 自带 clickable，点击会被它先消费，不会连带触发卡片 onClick（进歌单）；
                    //   ③ 40dp = Wear M3 IconButton 的最小触摸目标，不能再小；行高由左侧 48dp 封面决定，
                    //      不改变 M3e 列表（TransformingLazyColumn）的形变高度口径。
                    //   ④ 未登录 / 本地歌由 ListenScreens.toggleRoomLike 统一 toast 兜底，这里不重复判断。
                    if (song != null) {
                        IconButton(
                            onClick = onToggleLike,
                            modifier = Modifier.size(40.dp).pressStretch(),
                        ) {
                            Icon(
                                if (liked) NcmIcons.HeartFill else NcmIcons.Heart,
                                contentDescription = if (liked) "取消收藏" else "收藏",
                                modifier = Modifier.size(20.dp),
                                tint = if (liked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Room synchronization owns seeking, matching the original display-only ring.
                LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                Text(playbackTime, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = "playback") {
            val previousSource = remember { MutableInteractionSource() }
            val playSource = remember { MutableInteractionSource() }
            val nextSource = remember { MutableInteractionSource() }
            M3eTransformingItem(Modifier.fillMaxWidth()) {
                ButtonGroup(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                    // ★ 2026-10-24：双人房全员可切歌（[canControl]），不再跟着 isHost 走。
                    //   中键权重按「有没有两颗切歌钮」而不是「是不是房主」变化，视觉与旧版一致。
                    if (canControl) {
                        FilledIconButton(onClick = onPrevious,
                            modifier = Modifier.weight(1f).animateWidth(previousSource), interactionSource = previousSource) {
                            Icon(NcmIcons.Prev, "上一首")
                        }
                    }
                    FilledIconButton(onClick = onToggle,
                        modifier = Modifier.weight(if (canControl) 1.3f else 1f).animateWidth(playSource), interactionSource = playSource) {
                        Icon(if (playing) NcmIcons.Pause else NcmIcons.Play, if (playing) "暂停" else "播放")
                    }
                    if (canControl) {
                        FilledIconButton(onClick = onNext,
                            modifier = Modifier.weight(1f).animateWidth(nextSource), interactionSource = nextSource) {
                            Icon(NcmIcons.Next, "下一首")
                        }
                    }
                }
            }
        }
        item(key = "members") {
            Button(onClick = onMembers, modifier = Modifier.fillMaxWidth().pressStretch(),
                transformation = LocalItemTransformation.current,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(NcmIcons.Friends, null) },
                secondaryLabel = { Text(modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, text = if (isHost) "$members · 房主" else members) },
                label = { Text(modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, text = "房间成员") })
        }
        item(key = "chat") {
            Button(onClick = onChat, modifier = Modifier.fillMaxWidth().pressStretch(),
                transformation = LocalItemTransformation.current,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(NcmIcons.CommentIc, null) }, label = { Text(modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, text = "房间聊天") })
        }
        item(key = "invite_queue") {
            ListenActionPair("邀请好友", NcmIcons.Together, onInvite, "房间歌单", NcmIcons.Playlist, onQueue)
        }
        item(key = "volume_exit") {
            ListenActionPair("音量", NcmIcons.Volume, onVolume, "退出房间", NcmIcons.Close, onExit)
        }
    }
}

/** Four operations are split into two native groups, each within the three-button limit. */
@Composable
fun M3eListenActions(onInvite: () -> Unit, onQueue: () -> Unit, onVolume: () -> Unit, onExit: () -> Unit) {
    ListenActionPair("邀请好友", NcmIcons.Together, onInvite, "房间歌单", NcmIcons.Playlist, onQueue)
    Spacer(Modifier.height(6.dp))
    ListenActionPair("音量", NcmIcons.Volume, onVolume, "退出房间", NcmIcons.Close, onExit)
}

@Composable
private fun ListenActionPair(
    firstLabel: String, firstIcon: ImageVector, onFirst: () -> Unit,
    secondLabel: String, secondIcon: ImageVector, onSecond: () -> Unit,
) {
    val firstSource = remember { MutableInteractionSource() }
    val secondSource = remember { MutableInteractionSource() }
    M3eTransformingItem(Modifier.fillMaxWidth()) {
        ButtonGroup(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
            Button(onClick = onFirst, modifier = Modifier.weight(1f).animateWidth(firstSource),
                interactionSource = firstSource,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(firstIcon, null, Modifier.size(18.dp)) }, label = { Text(modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, text = firstLabel) })
            Button(onClick = onSecond, modifier = Modifier.weight(1f).animateWidth(secondSource),
                interactionSource = secondSource,
                colors = ButtonDefaults.filledTonalButtonColors(),
                icon = { Icon(secondIcon, null, Modifier.size(18.dp)) }, label = { Text(modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, text = secondLabel) })
        }
    }
}
