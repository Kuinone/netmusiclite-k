@file:OptIn(ExperimentalFoundationApi::class)

package com.netmusiclite.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netmusiclite.data.AlbumItem
import com.netmusiclite.data.AppearancePrefs
import com.netmusiclite.data.ArtistItem
import com.netmusiclite.data.Song
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.SurfaceGlass
import com.netmusiclite.ui.theme.SurfaceStrong
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import com.netmusiclite.ui.theme.TextTertiary
import com.netmusiclite.ui.m3e.M3eSongCard
import com.netmusiclite.ui.m3e.M3eRowCard
import com.netmusiclite.ui.m3e.M3eCollectionCard
import com.netmusiclite.ui.m3e.M3eSelectionAction
import com.netmusiclite.ui.m3e.M3eSelectionActions

/** 歌曲行（普通列表样式，非卡片）：封面38 + 标题/艺人 + 时长 + 选中态 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean = false,
    isCurrent: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    selectMode: Boolean = false,
    /**
     * 跨语言搜索提示：这首歌**因为别名/译名**命中当前搜索词时，把那个译名缀在艺人后面
     * （「夜に駆ける」搜中文「向夜晚奔去」时显示「YOASOBI · 向夜晚奔去」），
     * 让用户一眼看懂为什么搜索结果里会出现一首外文标题的歌。歌名本身命中时传 null。
     */
    aliasHint: String? = null,
    /** 经典主题：细描边卡片（歌单详情 / 搜索结果用；m3e 忽略） */
    cardEdge: Boolean = false,
) {
    if (AppearancePrefs.isM3e) {
        M3eSongCard(song, onClick, Modifier, isCurrent, onLongClick, selectMode, selected, aliasHint, trailing)
        return
    }
    // ★ 2026-10-24 用户口径：歌单详情 / 搜索结果的歌曲行加「细描边卡片」——
    //   圆角 14dp、1dp 半透明描边、左右缩进 10dp、行间 2+2=4dp，不加底色。
    val edgeShape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (cardEdge) Modifier.padding(horizontal = 10.dp, vertical = 2.dp) else Modifier)
            // ★ 2026-10-23 修复「经典主题搜索结果里歌手名只显示一半」：
            //   旧版是固定 `.height(52.dp)`，行内容区只剩 52-12=40dp。标题 12sp + 副标题 9sp
            //   在系统字体放大 / 中文字体 ascent 偏大的机器上，两行的**字体行盒**会超过 40dp，
            //   固定高度把第二行（歌手名）从中间裁掉，看起来就是「歌手名字只有一半」。
            //   改成 heightIn(min = 52.dp)：正常字号下高度与以前一模一样（52dp），
            //   只有在行内容真的更高时才让行跟着长高 —— 宁可列表项高 2dp，也不裁字。
            .heightIn(min = 52.dp)
            .pressStretch()
            .then(
                if (cardEdge) Modifier
                    .clip(edgeShape)
                    .border(1.dp, TextTertiary.copy(alpha = 0.35f), edgeShape)
                else Modifier,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            SongCover(song, 38.dp, shape = RoundedCornerShape(8.dp))
            if (isCurrent) {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(8.dp))
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(NcmIcons.Play, null, tint = TextPrimary, modifier = Modifier.size(15.dp)) }
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title, fontSize = 12.sp, color = if (isCurrent) Accent else TextPrimary,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                // ★ 同上：显式行高，避免字体默认 metrics 撑开行盒后被父级高度裁切
                lineHeight = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                songSubtitle(song, aliasHint), fontSize = 9.sp, color = TextSecondary,
                lineHeight = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (selectMode) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(19.dp).clip(CircleShape)
                    .border(1.5.dp, if (selected) Accent else TextTertiary, CircleShape)
                    .background(if (selected) Accent else androidx.compose.ui.graphics.Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Icon(NcmIcons.Check, null, tint = com.netmusiclite.ui.theme.OnAccent, modifier = Modifier.size(12.dp))
            }
        } else {
            Spacer(Modifier.width(6.dp))
            Text(formatMs(song.durationMs.toLong()), fontSize = 9.sp, color = TextTertiary)
        }
        if (trailing != null) trailing()
    }
}

/**
 * 歌曲副标题：艺人名；跨语言搜索命中别名时用强调色追加译名。
 * 汇成一条 AnnotatedString 而不是两个 Text 并排，是为了让省略号统一作用在整行上
 * （并排时两个 Text 各自截断，窄屏上会变成「YOASO… · 向夜…」这种双截断）。
 */
internal fun songSubtitle(song: Song, aliasHint: String?): androidx.compose.ui.text.AnnotatedString =
    androidx.compose.ui.text.buildAnnotatedString {
        append(song.artist)
        if (!aliasHint.isNullOrBlank()) {
            withStyle(androidx.compose.ui.text.SpanStyle(color = Accent)) {
                append(" · ")
                append(aliasHint)
            }
        }
    }

/** 搜索结果里的艺人胶囊卡：最左圆形头像 + 艺人名 */
@Composable
fun ArtistCapsule(artist: ArtistItem, onClick: () -> Unit) {
    if (AppearancePrefs.isM3e) {
        M3eRowCard(onClick = onClick, height = 56.dp) {
            CoverImage(artist.avatarUrl, 40.dp, shape = CircleShape)
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                androidx.wear.compose.material3.Text(artist.name,
                    style = androidx.wear.compose.material3.MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                androidx.wear.compose.material3.Text("艺人",
                    style = androidx.wear.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.wear.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    Row(
        modifier = Modifier
            .padding(horizontal = 18.dp, vertical = 4.dp)
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(50))
            .frostedGlass(RoundedCornerShape(50))
            .glassEdgeHighlight(RoundedCornerShape(50))
            .combinedClickable(onClick = onClick)
            .padding(start = 10.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(artist.avatarUrl, 38.dp, shape = CircleShape)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(artist.name, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("艺人", fontSize = 9.sp, color = TextSecondary)
        }
        Spacer(Modifier.weight(1f))
        Icon(NcmIcons.ChevronRight, null, tint = TextTertiary, modifier = Modifier.size(14.dp))
    }
}

/** 专辑卡片行：左封面右名称（按发布时间排列） */
@Composable
fun AlbumCardRow(album: AlbumItem, onClick: () -> Unit) {
    if (AppearancePrefs.isM3e) {
        M3eCollectionCard(album.name,
            listOf(album.artist, if (album.songCount > 0) "${album.songCount} 首" else "",
                if (album.publishTime > 0) formatDate(album.publishTime) else "").filter { it.isNotBlank() }.joinToString(" · "),
            album.coverUrl, onClick)
        return
    }
    // 2026-10-01（艺人页专辑区）：竖向缝隙缩小 50%（5dp→2.5dp，卡间由 10dp→5dp），
    // 卡片整体尺寸缩小 15%（圆角 16→13.6 / 内距 9→7.65 / 封面 52→44.2 / 字号 12→10.2、9→7.65）。
    Row(
        modifier = Modifier
            .padding(horizontal = 15.3.dp, vertical = 2.5.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.6.dp))
            .background(SurfaceGlass)
            .combinedClickable(onClick = onClick)
            .padding(7.65.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverImage(album.coverUrl, 44.2.dp, shape = RoundedCornerShape(8.5.dp))
        Spacer(Modifier.width(9.35.dp))
        Column {
            Text(album.name, fontSize = 10.2.sp, color = TextPrimary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val y = if (album.publishTime > 0) formatDate(album.publishTime) else ""
            // ★ 2026-10-07 用户口径「添加艺人主页专辑页面单个专辑歌曲数量显示」：
            //   歌曲数排第二段（艺人名可能很长，单行尾部截断先吃掉日期，数量尽量保住可见）。
            //   songCount <= 0（旧接口/异常数据）时整段不出现。
            val cnt = if (album.songCount > 0) "${album.songCount} 首" else ""
            Text(
                listOf(album.artist, cnt, y).filter { it.isNotBlank() }.joinToString(" · "),
                fontSize = 7.65.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 多选下载悬浮胶囊 */
@Composable
fun DownloadSelectionBar(count: Int, onDownload: () -> Unit, onClear: () -> Unit) {
    if (count <= 0) return
    if (AppearancePrefs.isM3e) {
        M3eSelectionActions(count, listOf(
            M3eSelectionAction(NcmIcons.Download, "下载所选", onDownload),
            M3eSelectionAction(NcmIcons.Close, "取消选择", onClear),
        ))
        return
    }
    Row(
        modifier = Modifier
            .padding(horizontal = 40.dp)
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(50))
            .background(Accent)
            .combinedClickable(onClick = onDownload)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(NcmIcons.Download, null, tint = com.netmusiclite.ui.theme.OnAccent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(7.dp))
        Text("下载 $count 首", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = com.netmusiclite.ui.theme.OnAccent)
        Spacer(Modifier.width(12.dp))
        Icon(
            NcmIcons.Close, "取消选择",
            tint = com.netmusiclite.ui.theme.OnAccent.copy(alpha = 0.8f),
            modifier = Modifier.size(14.dp).combinedClickable(onClick = onClear),
        )
    }
}

/**
 * 多选删除控制组（本地音乐页）：全选（可选）+ 取消 + 删除（右上角数量角标）。
 * ★ 2026-10-03：动作图标/描述可换 —— 「导入本地音乐」页的多选确认复用同一组
 * （actionIcon = Check、actionDesc = "导入所选"），默认值保持删除页原样。
 * ★ 2026-10-03 用户口径：全选图标改为「上下两个勾叠在一起」（NcmIcons.CheckAll）——
 *   导入页的确认动作也是单勾，全选再画单勾就成了两个一样的按钮，双勾用于消歧。
 */
@Composable
fun SelectionDeleteCircles(
    count: Int,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    selectAll: (() -> Unit)? = null,
    actionIcon: ImageVector = NcmIcons.Trash,
    actionDesc: String = "删除所选",
) {
    if (AppearancePrefs.isM3e) {
        M3eSelectionActions(count, buildList {
            if (selectAll != null) add(M3eSelectionAction(NcmIcons.CheckAll, "全选", selectAll))
            add(M3eSelectionAction(NcmIcons.Close, "取消选择", onCancel))
            add(M3eSelectionAction(actionIcon, actionDesc, onDelete))
        }, modifier)
        return
    }
    Row(
        modifier = modifier.padding(top = 50.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 全选（可选）：双勾图标（上下叠）——与单勾的确认/导入动作区分
        if (selectAll != null) {
            Box(
                Modifier
                    .size(31.dp)
                    .clip(CircleShape)
                    .background(SurfaceStrong)
                    .combinedClickable(onClick = selectAll),
                contentAlignment = Alignment.Center,
            ) {
                Icon(NcmIcons.CheckAll, "全选", tint = Accent, modifier = Modifier.size(14.dp))
            }
        }
        // 取消选择
        Box(
            Modifier
                .size(31.dp)
                .clip(CircleShape)
                .background(SurfaceStrong)
                .combinedClickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Icon(NcmIcons.Close, "取消选择", tint = TextPrimary, modifier = Modifier.size(13.dp))
        }
        // 动作按钮（右上角数量角标）
        Box(Modifier.size(37.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Accent)
                    .combinedClickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                Icon(actionIcon, actionDesc, tint = com.netmusiclite.ui.theme.OnAccent, modifier = Modifier.size(15.dp))
            }
            if (count > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$count", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Accent)
                }
            }
        }
    }
}

/**
 * 多选模式顶部控制组：屏幕上方的小圆形按钮组（[全选] + 取消 ✕ + 下一首播放 ＋ + 下载 ↓ 带数量角标）。
 * 用于卡片堆叠歌曲列表（我喜欢/每日推荐/歌单/专辑）的长按多选。
 *
 * [onAddNext] 传 null 时不显示「＋」（当前只在「我喜欢」页启用）。
 * [onRemove] 传 null 时不显示「移出歌单」（只在「自己创建的歌单」详情页传入，走 op=del）。
 */
@Composable
fun SelectionDownloadCircles(
    count: Int,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    selectAll: (() -> Unit)? = null,
    onAddNext: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    if (AppearancePrefs.isM3e) {
        M3eSelectionActions(count, buildList {
            if (selectAll != null) add(M3eSelectionAction(NcmIcons.CheckAll, "全选", selectAll))
            add(M3eSelectionAction(NcmIcons.Close, "取消选择", onCancel))
            if (onAddNext != null) add(M3eSelectionAction(NcmIcons.Plus, "添加到下一首播放", onAddNext))
            if (onRemove != null) add(M3eSelectionAction(NcmIcons.Trash, "移出歌单", onRemove))
            add(M3eSelectionAction(NcmIcons.Download, "下载所选", onDownload))
        }, modifier)
        return
    }
    Row(
        modifier = modifier.padding(top = 50.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 全选（可选，仅我喜欢页启用）
        if (selectAll != null) {
            Box(
                Modifier
                    .size(31.dp)
                    .clip(CircleShape)
                    .background(SurfaceStrong)
                    .combinedClickable(onClick = selectAll),
                contentAlignment = Alignment.Center,
            ) {
                Icon(NcmIcons.Check, "全选", tint = Accent, modifier = Modifier.size(14.dp))
            }
        }
        // 取消选择
        Box(
            Modifier
                .size(31.dp)
                .clip(CircleShape)
                .background(SurfaceStrong)
                .combinedClickable(onClick = onCancel),
            contentAlignment = Alignment.Center,
        ) {
            Icon(NcmIcons.Close, "取消选择", tint = TextPrimary, modifier = Modifier.size(13.dp))
        }
        // 添加到「下一首播放」（可选）：把选中的歌插到当前歌之后
        if (onAddNext != null) {
            Box(
                Modifier
                    .size(31.dp)
                    .clip(CircleShape)
                    .background(SurfaceStrong)
                    .combinedClickable(onClick = onAddNext),
                contentAlignment = Alignment.Center,
            ) {
                Icon(NcmIcons.Plus, "添加到下一首播放", tint = Accent, modifier = Modifier.size(15.dp))
            }
        }
        // 移出歌单（可选，仅自己创建的歌单详情页）
        if (onRemove != null) {
            Box(
                Modifier
                    .size(31.dp)
                    .clip(CircleShape)
                    .background(SurfaceStrong)
                    .combinedClickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(NcmIcons.Trash, "移出歌单", tint = TextPrimary, modifier = Modifier.size(14.dp))
            }
        }
        // 下载（右上角数量角标）
        Box(Modifier.size(37.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Accent)
                    .combinedClickable(onClick = onDownload),
                contentAlignment = Alignment.Center,
            ) {
                Icon(NcmIcons.Download, "下载所选", tint = com.netmusiclite.ui.theme.OnAccent, modifier = Modifier.size(15.dp))
            }
            if (count > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$count", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Accent)
                }
            }
        }
    }
}
