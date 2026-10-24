package com.netmusiclite.ui.screens.local

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netmusiclite.data.DownloadStore
import com.netmusiclite.data.ImportStore
import com.netmusiclite.data.LocalMusic
import com.netmusiclite.data.Song
import com.netmusiclite.ui.components.CenterHint
import com.netmusiclite.ui.components.MediaPermissions
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.SelectionDeleteCircles
import com.netmusiclite.ui.components.StackedCardList
import com.netmusiclite.ui.components.StackedIconCard
import com.netmusiclite.ui.components.StackedSongCard
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.OnAccent
import com.netmusiclite.ui.theme.SurfaceStrong
import com.netmusiclite.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 导入本地音乐（2026-10-03 用户口径「点击导入本地音乐后进入界面，有一个胶囊按钮，
 * 扫描本地音乐，扫描后下方歌曲列表出现相应音乐，长按单个音乐进入多选选择多个音乐
 * 一次性导入，直接点击单选」）：
 *
 * · 「扫描本地音乐」胶囊：**点这一下才申请**音频权限（33+ READ_MEDIA_AUDIO /
 *   ≤32 READ_EXTERNAL_STORAGE）—— 用户口径「使用相关功能的时候获取相关权限」；
 * · 扫描结果 = 设备媒体库 − 已导入（按 uri）− 已下载（按 歌名+艺人，与 LocalMusic
 *   合并去重同口径）—— 已在应用内的歌再导入只会得到重复条目，所以直接不列；
 * · **点击 = 单首导入**（导入即从列表移除并提示）；**长按 = 多选**，右上角确认钮
 *   一次性导入所选（复用 SelectionDeleteCircles 的动作位，换成 ✓ /「导入所选」）；
 * · 保留旧通路：SAF「从系统文件管理选择」—— 媒体库未收录的散装音频文件仍可导入；
 * · 导入 = ImportStore.add（落地即本地歌曲，只在本地音乐列表生效，见其 KDoc）。
 */
@Composable
fun ImportLocalScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var songs by remember { mutableStateOf<List<Song>?>(null) } // null = 尚未扫描
    var scanning by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val selected = remember { mutableStateMapOf<Long, Song>() }
    val selectMode = selected.isNotEmpty()
    val audioPerm = remember { MediaPermissions.audio() }

    suspend fun doScan() {
        scanning = true
        val imported = ImportStore.uris(ctx)
        val downloaded = DownloadStore.allDownloaded()
        songs = LocalMusic.scanDevice(ctx).filter { s ->
            val uri = LocalMusic.localUri(s)?.toString()
            uri != null && uri !in imported &&
                downloaded.none { it.title == s.title && it.artist == s.artist }
        }
        scanning = false
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        if (ok) scope.launch { doScan() }
        else toast = "需要音频读取权限才能扫描本地音乐"
    }

    fun startScan() {
        if (MediaPermissions.granted(ctx, audioPerm)) scope.launch { doScan() }
        else permLauncher.launch(audioPerm)
    }

    /** 批量导入并把这些行从可导入列表里移除；返回实际新增数 */
    suspend fun importSongs(list: List<Song>): Int {
        val uris = list.mapNotNull { LocalMusic.localUri(it) }
        if (uris.isEmpty()) return 0
        val n = ImportStore.add(ctx, uris)
        if (n > 0) {
            val ids = list.map { it.id }.toSet()
            songs = songs?.filterNot { it.id in ids }
        }
        return n
    }

    // 旧通路保留：系统文件管理（SAF）多选导入（媒体库未收录的文件）
    val safPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            val n = ImportStore.add(ctx, uris)
            toast = if (n > 0) "已导入 $n 首到本地音乐" else "所选文件均已在本地音乐中"
        }
    }

    LaunchedEffect(toast) { if (toast != null) { delay(3200); toast = null } }

    Box(Modifier.fillMaxSize()) {
        val list = songs
        StackedCardList(
            title = "导入本地音乐",
            progressTotal = (list?.size ?: 0) + 3,
            cardHeight = 56.dp,
            horizontalInsetPx = 10,
        ) {
            item(key = "scan") {
                ScanCapsule(
                    label = when {
                        scanning -> "扫描中…"
                        list == null -> "扫描本地音乐"
                        else -> "重新扫描"
                    },
                    enabled = !scanning,
                    onClick = { startScan() },
                )
            }
            item(key = "saf") {
                StackedIconCard(
                    title = "从系统文件管理选择",
                    subtitle = "适合媒体库未收录的音频文件",
                    icon = NcmIcons.Plus,
                    iconTint = Accent,
                    onClick = { safPicker.launch(arrayOf("audio/*")) },
                )
            }
            when {
                scanning -> item {
                    CenterHint("扫描中…", Modifier.fillMaxWidth().height(200.dp))
                }
                list == null -> item {
                    CenterHint("点上方胶囊扫描设备上的音乐\n长按可多选批量导入", Modifier.fillMaxWidth().height(200.dp))
                }
                list.isEmpty() -> item {
                    CenterHint("没有可导入的音乐\n设备歌曲都已导入或已下载", Modifier.fillMaxWidth().height(200.dp))
                }
                else -> items(list, key = { it.id }) { song ->
                    StackedSongCard(
                        song = song,
                        isCurrent = false,
                        selectMode = selectMode,
                        selected = selected.containsKey(song.id),
                        onClick = {
                            if (selectMode) {
                                if (selected.containsKey(song.id)) selected.remove(song.id)
                                else selected[song.id] = song
                            } else {
                                // 直接点击 = 单选导入这一首
                                scope.launch {
                                    val n = importSongs(listOf(song))
                                    toast = if (n > 0) "已导入《${song.title}》到本地音乐"
                                    else "这首已在本地音乐中"
                                }
                            }
                        },
                        onLongClick = {
                            if (selected.containsKey(song.id)) selected.remove(song.id)
                            else selected[song.id] = song
                        },
                    )
                }
            }
        }
        if (selectMode) {
            SelectionDeleteCircles(
                count = selected.size,
                onDelete = {
                    scope.launch {
                        val n = importSongs(selected.values.toList())
                        selected.clear()
                        toast = if (n > 0) "已导入 $n 首到本地音乐" else "所选均已在本地音乐中"
                    }
                },
                onCancel = { selected.clear() },
                selectAll = { list?.forEach { selected[it.id] = it } },
                actionIcon = NcmIcons.Check,
                actionDesc = "导入所选",
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        if (toast != null) {
            Box(Modifier.align(Alignment.Center)) {
                Text(
                    toast!!, fontSize = 11.sp, color = Color.White, textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xCC2B2B33))
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/** 「扫描本地音乐」胶囊（强调色实心、全宽，与下载多选条同语汇） */
@Composable
private fun ScanCapsule(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) Accent else SurfaceStrong)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                NcmIcons.Search, null,
                tint = if (enabled) OnAccent else TextSecondary,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = if (enabled) OnAccent else TextSecondary,
            )
        }
    }
}
