package com.netmusiclite.ui.m3e

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.netmusiclite.data.PlayMode
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.Song
import com.netmusiclite.data.SongQuery
import com.netmusiclite.ui.components.ListFilterCard
import com.netmusiclite.ui.components.pressStretch
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.SongRow
import com.netmusiclite.ui.components.rememberAliasAwareSongs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Album art drives this collection's tonal surfaces without changing classic appearance settings. */
@Composable
private fun rememberCollectionColor(cover: Any?): Color {
    val context = LocalContext.current
    val base = MaterialTheme.colorScheme.surfaceContainer
    var seed by remember(cover) { mutableStateOf<Color?>(null) }
    LaunchedEffect(cover) {
        if (cover == null) return@LaunchedEffect
        seed = withContext(Dispatchers.IO) {
            runCatching {
                val result = context.imageLoader.execute(
                    ImageRequest.Builder(context).data(cover).size(96).allowHardware(false).build(),
                )
                val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@runCatching null
                Palette.from(bitmap).maximumColorCount(12).generate().dominantSwatch?.rgb?.let(::Color)
            }.getOrNull()
        }
    }
    return seed?.let { monetColor(it, 25, 28.0).m3eSurface() } ?: base
}

@Composable
private fun CollectionArt(cover: Any?, fallback: ImageVector, modifier: Modifier) {
    val shape = MaterialTheme.shapes.large
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        // A rounded library icon also remains visible if an unavailable cover fails to load.
        Icon(fallback, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        if (cover != null) AsyncImage(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** Reused by the album, playlist, liked and daily-recommendation screens. */
@Composable
fun M3eCollectionSongs(
    title: String,
    cover: Any?,
    songs: List<Song>,
    fallback: ImageVector = NcmIcons.Playlist,
    state: LazyListState = rememberLazyListState(),
    loading: Boolean = false,
    emptyMessage: String = "暂无歌曲",
    status: String? = null,
    subscribed: Boolean? = null,
    onSubscribe: (() -> Unit)? = null,
    selected: Set<Long> = emptySet(),
    onToggleSelection: (Song) -> Unit,
    onClearSelection: () -> Unit,
    onDownload: () -> Unit,
    onSelectAll: (() -> Unit)? = null,
    onAddNext: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    onPlay: (List<Song>, Int) -> Unit,
) {
    if (loading && state.firstVisibleItemIndex > 0) {
        // Preserve the saved viewport until real items are available, just as the classic page does.
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("加载中…", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    var filter by remember { mutableStateOf("") }
    val visible = rememberAliasAwareSongs(songs, filter)
    val tint = rememberCollectionColor(cover)
    Box(Modifier.fillMaxSize()) {
    M3eList(title = title, state = state) {
        item(key = "collection_head") {
            // Match the lazy item's compressed height by transforming the entire art/playback group.
            // Keeping nested surfaces untransformed makes the cover and both buttons move together.
            M3eTransformingItem(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    CollectionArt(cover, fallback, Modifier.size(114.dp))
                    Text(
                        "${songs.size} 首歌曲",
                        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(7.dp))
                    val playSource = remember { MutableInteractionSource() }
                    val modeSource = remember { MutableInteractionSource() }
                    val groupH = 38.25.dp
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ButtonGroup(
                            modifier = Modifier.width(116.5.dp).height(groupH),
                            contentPadding = PaddingValues(0.dp), spacing = 4.dp, expansionWidth = 4.dp,
                        ) {
                            Button(
                                onClick = { if (visible.isNotEmpty()) onPlay(visible, 0) }, enabled = visible.isNotEmpty(),
                                modifier = Modifier.weight(2f).minWidth(1.dp).animateWidth(playSource).height(groupH),
                                interactionSource = playSource, contentPadding = PaddingValues(horizontal = 5.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = tint, contentColor = MaterialTheme.colorScheme.onSurface),
                            ) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                    Icon(NcmIcons.Play, null, Modifier.size(13.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("播放", style = MaterialTheme.typography.labelSmall, maxLines = 1, textAlign = TextAlign.Center)
                                }
                            }
                            FilledIconButton(
                                onClick = {
                                    PlayerEngine.setMode(when (PlayerEngine.playMode) {
                                        PlayMode.LOOP, PlayMode.SINGLE -> PlayMode.SHUFFLE
                                        PlayMode.SHUFFLE -> PlayMode.HEART
                                        PlayMode.HEART -> PlayMode.LOOP
                                    })
                                },
                                modifier = Modifier.weight(1f).minWidth(1.dp).animateWidth(modeSource).height(groupH),
                                interactionSource = modeSource,
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = tint, contentColor = MaterialTheme.colorScheme.onSurface),
                            ) {
                                Icon(when (PlayerEngine.playMode) {
                                    PlayMode.SHUFFLE -> NcmIcons.Shuffle
                                    PlayMode.HEART -> NcmIcons.HeartMode
                                    PlayMode.SINGLE -> NcmIcons.SingleLoop
                                    else -> NcmIcons.Repeat
                                }, PlayerEngine.playMode.label, Modifier.size(17.dp))
                            }
                        }
                    }
                }
            }
        }
        if (subscribed != null && onSubscribe != null) item(key = "collection_subscribe") {
            Button(onClick = onSubscribe, modifier = Modifier.fillMaxWidth().pressStretch(), transformation = LocalItemTransformation.current) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (subscribed) NcmIcons.HeartFill else NcmIcons.Heart, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (subscribed) "已收藏" else "收藏", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                }
            }
        }
        if (status != null) item(key = "collection_status") {
            M3eTransformingItem(Modifier.fillMaxWidth()) {
                Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            }
        }
        item(key = "filter") { ListFilterCard(query = filter, onQuery = { filter = it }) }
        if (loading || songs.isEmpty()) item(key = "collection_empty") {
            M3eTransformingItem(Modifier.fillMaxWidth()) {
                Text(if (loading) "加载中…" else emptyMessage, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(14.dp))
            }
        }
        itemsIndexed(visible, key = { _, song -> song.id }, contentType = { _, _ -> "song" }) { index, song ->
            SongRow(
                song = song, isCurrent = PlayerEngine.current?.id == song.id,
                selected = song.id in selected, selectMode = selected.isNotEmpty(),
                aliasHint = SongQuery.matchedAlias(song, filter),
                onClick = { if (selected.isNotEmpty()) onToggleSelection(song) else onPlay(visible, index) },
                onLongClick = { onToggleSelection(song) },
            )
        }
    }
    if (selected.isNotEmpty()) {
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
                .clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 6.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("已选择 ${selected.size} 首", style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.Center) {
                IconButton(onDownload, Modifier.size(30.dp).pressStretch()) { Icon(NcmIcons.Download, "下载所选", Modifier.size(18.dp)) }
                if (onSelectAll != null) IconButton(onSelectAll, Modifier.size(30.dp).pressStretch()) { Icon(NcmIcons.CheckAll, "全选", Modifier.size(18.dp)) }
                if (onAddNext != null) IconButton(onAddNext, Modifier.size(30.dp).pressStretch()) { Icon(NcmIcons.Next, "添加到下一首播放", Modifier.size(18.dp)) }
                if (onRemove != null) IconButton(onRemove, Modifier.size(30.dp).pressStretch()) { Icon(NcmIcons.Trash, "移出歌单", Modifier.size(18.dp)) }
                IconButton(onClearSelection, Modifier.size(30.dp).pressStretch()) { Icon(NcmIcons.Close, "取消选择", Modifier.size(18.dp)) }
            }
        }
    }
    }
}

/** Native collection card with an album-derived tone. */
@Composable
fun M3eCollectionCard(title: String, subtitle: String, cover: Any?, onClick: () -> Unit) {
    M3eCollectionCard(title, subtitle, cover, compact = false, onClick = onClick)
}

/** Compact dimensions are reserved for the user's playlist/album library. */
@Composable
fun M3eCollectionCard(title: String, subtitle: String, cover: Any?, compact: Boolean, onClick: () -> Unit) {
    val tint = rememberCollectionColor(cover)
    val cardHeight = if (compact) 75.65.dp else 89.dp
    M3ePressSurface(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Card(
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(if (compact) .95f else 1f).height(cardHeight),
                colors = CardDefaults.cardColors(containerColor = tint), contentPadding = PaddingValues(10.dp),
                transformation = LocalItemTransformation.current,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CollectionArt(cover, NcmIcons.Playlist, Modifier.size(if (compact) 55.65.dp else 68.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
