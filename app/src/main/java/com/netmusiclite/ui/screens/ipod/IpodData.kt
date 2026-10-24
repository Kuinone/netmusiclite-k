package com.netmusiclite.ui.screens.ipod

import com.netmusiclite.data.HistoryStore
import com.netmusiclite.data.PageCache
import com.netmusiclite.data.SessionStore
import com.netmusiclite.data.Song
import com.netmusiclite.ui.components.songCoverModel
import com.netmusiclite.ui.nav.Routes

/**
 * 随机抽取（iPod CoverFlow）选歌的数据与路由契约（2026-10-17 用户口径）：
 * 「长按 每日推荐 / 听歌排行 / 我的歌单 / 我喜欢 / 最近播放 卡片进入随机抽取界面，
 *  左右滑动随机出现歌曲封面，点击直接进播放页、以随机顺序播放并从被点击的开始；
 *  同专辑不要合并成单个选项」。
 *
 * 因此一张卡 = **一首歌**（不按专辑归并）；只有「我的歌单」来源的一张卡 = 一个歌单，
 * 点击后才拉歌单曲目、随机挑一首起播（仍然直接进播放页，没有中间页面）。
 */
enum class IpodSource(val label: String) {
    DAILY("每日推荐"),
    RECORD("听歌排行"),
    PLAYLISTS("我的歌单"),
    LIKED("我喜欢"),

    /**
     * ★ 2026-10-17（第二轮用户反馈）：长按「最近播放」卡片也要能进抽取页。
     * 数据源 = 本地播放历史 [HistoryStore.recent]（HistoryScreen 同源），不发网络请求，
     * 所以是本地即有即可抽，空历史时退回「暂无封面」提示。
     */
    HISTORY("最近播放"),
}

object IpodRoutes {
    const val ARG = "source"
    const val PATTERN = "ipod/{source}"
    fun of(source: IpodSource) = "ipod/${source.name}"
    fun parse(raw: String?): IpodSource =
        IpodSource.entries.firstOrNull { it.name == raw } ?: IpodSource.DAILY
}

/** 主页大卡片的 route → 长按进入的抽取来源（其余卡片返回 null，长按无动作）。 */
fun ipodSourceForHomeRoute(route: String?): IpodSource? = when (route) {
    Routes.DAILY_RECOMMEND -> IpodSource.DAILY
    Routes.RECORD -> IpodSource.RECORD
    Routes.MY_PLAYLISTS -> IpodSource.PLAYLISTS
    Routes.LIKED_SONGS -> IpodSource.LIKED
    Routes.PLAY_HISTORY -> IpodSource.HISTORY
    else -> null
}

/**
 * 抽取界面的一张卡。
 * - 歌曲来源：[song] != null，[cover] 是单曲封面；
 * - 我的歌单来源：[playlistId] != 0，点击时才加载歌单曲目（懒加载，避免进页发 N 个请求）。
 */
data class IpodPick(
    val key: String,
    val song: Song?,
    val title: String,
    val subtitle: String,
    val cover: Any?,
    val playlistId: Long = 0L,
)

/** 一页抽取数据：卡片（随机顺序由屏幕层打乱）+ 该来源的完整歌曲集合（用于随机播放队列）。 */
data class IpodPickPage(
    val picks: List<IpodPick>,
    val songs: List<Song>,
)

suspend fun loadIpodPickPage(source: IpodSource): IpodPickPage {
    if (source == IpodSource.PLAYLISTS) {
        val picks = PageCache.userPlaylists(SessionStore.uid).map { pl ->
            IpodPick(
                key = "pl:${pl.id}",
                song = null,
                title = pl.name,
                subtitle = "${pl.trackCount} 首歌曲",
                cover = pl.coverUrl,
                playlistId = pl.id,
            )
        }
        return IpodPickPage(picks, emptyList())
    }
    val songs = runCatching {
        when (source) {
            IpodSource.DAILY -> PageCache.dailyRecommend()
            IpodSource.LIKED -> PageCache.likedSongs(SessionStore.uid)
            IpodSource.RECORD -> {
                // 本周榜优先（更贴「最近在听」），空榜再回退全部时间
                val week = PageCache.userRecord(SessionStore.uid, 1)
                if (week.isNotEmpty()) week.map { it.song }
                else PageCache.userRecord(SessionStore.uid, 0).map { it.song }
            }
            // ★ 最近播放 = 本地播放历史（HistoryStore 与「最近播放」页同源），无需网络。
            //   在 Default 线程读取 Compose 快照列表并复制一份，避免持有可继续变化的列表引用。
            IpodSource.HISTORY -> HistoryStore.recent.toList()
            IpodSource.PLAYLISTS -> emptyList()
        }
    }.getOrDefault(emptyList())
    return IpodPickPage(songs.map(::pickOf), songs)
}

/** 单曲卡：同专辑的每首歌各占一张卡（用户明确要求不合并）。 */
internal fun pickOf(song: Song): IpodPick = IpodPick(
    key = "s:${song.id}",
    song = song,
    title = song.title.ifBlank { "未知歌曲" },
    subtitle = listOf(song.artist.ifBlank { "未知歌手" }, song.album)
        .filter { it.isNotBlank() }
        .joinToString(" · "),
    cover = songCoverModel(song),
)

/** 歌单卡对应曲目（点击时才加载）。 */
suspend fun IpodPick.resolvedSongs(): List<Song> {
    song?.let { return listOf(it) }
    if (playlistId == 0L) return emptyList()
    return runCatching { PageCache.playlistDetail(playlistId)?.second ?: emptyList() }
        .getOrDefault(emptyList())
}

/**
 * 以随机顺序播放、从被点曲目开始：队列 = 被点曲目 + 其余曲目打乱；
 * 同时把全局播放模式设为随机（后续跨专辑也有随机感）。
 */
fun playIpodShuffled(songs: List<Song>, start: Song) {
    val queue = ipodShuffleQueue(songs, start)
    if (queue.isEmpty()) return
    com.netmusiclite.data.PlayerEngine.playMode = com.netmusiclite.data.PlayMode.SHUFFLE
    com.netmusiclite.data.PlayerEngine.playQueue(queue, 0)
}

/** 纯函数（便于单测）：被点曲目永远第一，其余随机但其全集与来源完全一致。 */
internal fun ipodShuffleQueue(songs: List<Song>, start: Song): List<Song> {
    if (songs.isEmpty()) return emptyList()
    val head = songs.firstOrNull { it.id == start.id } ?: start
    return listOf(head) + songs.filter { it.id != head.id }.shuffled()
}

// ---------- 封面预热（2026-10-17 进页/滑动提速）----------

/** 预热半径：当前卡之后 2~4 张（≈接下来甩一两格就会露出来的位置）。 */
private const val IPOD_PREWARM_NEAR = 2

/** 预热的最远偏移（含）。 */
private const val IPOD_PREWARM_FAR = 4

/**
 * 预热抽取页当前卡「即将进入视口」的相邻封面。
 *
 * 为什么需要：LazyRow 只为视口内的卡片组合 AsyncImage，甩动时下一张封面要等请求
 * 磁盘/网络解码完才出现（黑块/空白帧）。这里在进页定位完成后，用与 [songCoverModel]
 * 同一个数据源、与 `CoverImage` **完全相同**的解码尺寸（`size.value.toInt() * 2`，见
 * components/AlbumCover.kt）提前 enqueue 请求 —— 尺寸不一致会导致内存缓存键不命中、
 * 预热等于白做，所以必须传同一个数。
 *
 * 只预热当前视口外的位置（±[IPOD_PREWARM_NEAR]..±[IPOD_PREWARM_FAR]）：视口内那几张
 * 已经由 LazyRow 自己发起请求，重复 enqueue 只会白白多读一次。
 * 非网络源（本地文件 / 内嵌封面 Uri / 兜底生成封面）跳过：那些是零网络、按布局约束解码，
 * 提前请求没有收益。
 */
internal fun prewarmIpodCovers(
    context: android.content.Context,
    picks: List<IpodPick>,
    centerIndex: Int,
    requestSizePx: Int,
) {
    if (picks.isEmpty() || requestSizePx <= 0) return
    val loader = coil.Coil.imageLoader(context)
    for (distance in IPOD_PREWARM_NEAR..IPOD_PREWARM_FAR) {
        for (sign in intArrayOf(-1, 1)) {
            val pick = picks.getOrNull(centerIndex + sign * distance) ?: continue
            // 与 CoverImage 的网络分支同一条件：只有非空、非 local:// 的 String 才走 Coil
            val url = pick.cover as? String ?: continue
            if (url.isEmpty() || url.startsWith("local://")) continue
            runCatching {
                loader.enqueue(
                    coil.request.ImageRequest.Builder(context)
                        .data(url)
                        .size(requestSizePx)
                        .allowHardware(true)
                        .build(),
                )
            }
        }
    }
}
