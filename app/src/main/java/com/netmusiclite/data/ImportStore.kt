package com.netmusiclite.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 手动导入的本地音乐（2026-10-03 用户口径「本地音乐里增加导入选项，点击打开系统文件管理；
 * 导入后的音乐**只在本地音乐列表内生效**，在我喜欢等歌单中不生效」）。
 *
 * 实现要点：
 *  · **不复制文件**：SAF（系统文件管理）选中的 content:// uri 直接持久化授权
 *    （takePersistableUriPermission），应用重启后继续可读 —— 省一份磁盘、也避免
 *    媒体库里出现重复文件；
 *  · **落地即「本地歌曲」**：条目合成 [Song] 时 coverUrl 写 "local://<uri>"，
 *    与媒体库扫描/下载歌曲走完全相同的 local:// 播放链路（PlayerEngine.localUri /
 *    封面取内嵌图），播放器无需认识导入这个概念；
 *  · **id 取大负数**：媒体库歌曲是 -mediaId、下载歌曲是线上正 id，
 *    导入条目用 -(1e9+n) 递减，既避开两者、又落在「id<0 即本地」的既有判据内
 *    （评论/百科等线上入口对负 id 有统一屏蔽），LazyColumn 的 key 也稳定；
 *  · **只在本地音乐页生效**：数据只被 LocalMusic.scan 合并进列表，
 *    不写任何歌单/红心存储 —— 因此「我喜欢」等页面天然看不到导入歌曲。
 */
object ImportStore {

    private const val SP_NAME = "local_imports"
    private const val KEY_LIST = "list"
    private const val KEY_NEXT = "next_num"

    /** id 基数：媒体库 id 远小于它，负向递减不会撞车 */
    private const val BASE_NUM = 1_000_000_000L

    data class Entry(
        val id: Long,
        val uri: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Int,
    )

    @Volatile
    private var loaded = false

    private val entries = ConcurrentHashMap<Long, Entry>()

    /** 下一个 id 序号（id = -nextNum），从 sp 恢复 */
    private var nextNum = BASE_NUM

    private fun sp(ctx: Context) = ctx.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)

    /** 首次访问时从 sp 恢复（加载后常驻内存，列表页每次重扫零成本） */
    private fun ensure(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val p = sp(ctx)
            nextNum = p.getLong(KEY_NEXT, BASE_NUM)
            p.getString(KEY_LIST, null)?.let { raw ->
                runCatching {
                    val arr = JSONObject(raw).getJSONArray("list")
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val e = Entry(
                            id = o.getLong("id"),
                            uri = o.getString("uri"),
                            title = o.getString("title"),
                            artist = o.optString("artist").ifBlank { "未知艺人" },
                            album = o.optString("album"),
                            durationMs = o.optInt("dur"),
                        )
                        entries[e.id] = e
                    }
                }
            }
            loaded = true
        }
    }

    private fun persist(ctx: Context) {
        val arr = JSONArray()
        entries.values.sortedBy { it.id }.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("uri", e.uri)
                put("title", e.title)
                put("artist", e.artist)
                put("album", e.album)
                put("dur", e.durationMs)
            })
        }
        // ★ 2026-10-04 改为 commit() 同步落盘（原为 apply()）：
        //   apply() 是**异步**写盘，导入之后若进程立刻被杀、或紧接着走更新安装，
        //   这一次导入就可能压根没落到磁盘 —— 表现正是「更新后之前导入的全没了」。
        //   导入是低频动作，同步写这点开销完全值得。
        sp(ctx).edit()
            .putString(KEY_LIST, JSONObject().apply { put("list", arr) }.toString())
            .putLong(KEY_NEXT, nextNum)
            .commit()
    }

    private data class Meta(val title: String?, val artist: String?, val album: String?, val durationMs: Int)

    /** 读音频内嵌标签；失败返回全空（条目仍会导入，标题回落文件名） */
    private fun readMeta(ctx: Context, uri: Uri): Meta = runCatching {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(ctx, uri)
            Meta(
                title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() },
                artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() },
                album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() },
                durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toIntOrNull() ?: 0,
            )
        } finally {
            runCatching { mmr.release() }
        }
    }.getOrDefault(Meta(null, null, null, 0))

    /** 文件名兜底标题（SAF uri 形如 content://…/document/primary%3AMusic%2Fsong.mp3） */
    private fun fallbackTitle(uri: Uri): String {
        val seg = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
        val decoded = seg?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        return decoded?.takeIf { it.isNotBlank() } ?: "未知歌曲"
    }

    /** 导入一组 uri，返回新增条数；已在列表中的 uri 直接跳过（幂等）。 */
    suspend fun add(ctx: Context, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        ensure(ctx)
        val known = entries.values.map { it.uri }.toHashSet()
        var added = 0
        uris.forEach { uri ->
            val s = uri.toString()
            if (!known.add(s)) return@forEach
            // 持久化读权限：重启后 uri 仍可读（个别 provider 不支持时导入继续，仅当次有效）
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val meta = readMeta(ctx, uri)
            val e = Entry(
                id = -(nextNum++),
                uri = s,
                title = meta.title ?: fallbackTitle(uri),
                artist = meta.artist ?: "未知艺人",
                album = meta.album ?: "",
                durationMs = meta.durationMs,
            )
            entries[e.id] = e
            added++
        }
        if (added > 0) persist(ctx)
        added
    }

    /** 从列表移除（不删原文件）：释放持久化授权并落盘。 */
    fun remove(ctx: Context, ids: Collection<Long>) {
        ensure(ctx)
        var changed = false
        ids.forEach { id ->
            entries.remove(id)?.let { e ->
                changed = true
                runCatching {
                    ctx.contentResolver.releasePersistableUriPermission(
                        Uri.parse(e.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
        }
        if (changed) persist(ctx)
    }

    /** 该 id 是否属于「手动导入」（本地音乐页删除路径据此放行） */
    fun isImported(ctx: Context, id: Long): Boolean {
        ensure(ctx)
        return entries.containsKey(id)
    }

    /** 已导入的全部 uri —— 「导入本地音乐」页据此把已在库的条目滤出（不再重复导入） */
    fun uris(ctx: Context): Set<String> {
        ensure(ctx)
        return entries.values.map { it.uri }.toSet()
    }

    /** 已导入歌曲（新导入在前 —— id 递减，升序即最新优先），供 LocalMusic.scan 合并 */
    fun list(ctx: Context): List<Song> {
        ensure(ctx)
        healIfNeeded(ctx)
        return entries.values.sortedBy { it.id }.map(::toSong)
    }

    /* ==================== uri 失效自愈（2026-10-04） ==================== */

    /** 自愈每进程只跑一次（避免每次进本地音乐页都全量校验 IO） */
    @Volatile
    private var healed = false

    /**
     * ★ 2026-10-04 修复「更新后之前导入的本地音乐失效」。
     *
     * 导入条目存的是完整 uri（媒体库条目形如 `content://media/external/audio/media/<id>`）。
     * 两种常见失效：
     *  ① **uri 指向的行没了** —— 媒体库被重建（_id 重排）后旧 uri 指向不存在的行；
     *  ② **读权限丢了** —— 音频权限被系统/ROM 撤销后，所有媒体库 uri 都读不了。
     * 这里只处理 ①（②要靠页面层提示用户重新授权，见 LocalMusicScreen 的权限卡片）：
     * 按「标题 + 艺人」在媒体库里重新定位，命中就把新 uri 写回，让导入条目自己长回来。
     *
     * ⚠ 必须在**有音频权限**时才跑：无权限时每个 uri 都打不开，会把好条目全误判成失效，
     *   接着拿不到任何媒体库索引（查询同样为空）而白跑一遍 —— 不如等有权限时再做。
     */
    private fun healIfNeeded(ctx: Context) {
        if (healed) return
        if (!hasAudioPermission(ctx)) return
        healed = true
        val bad = entries.values.filter { !readable(ctx, Uri.parse(it.uri)) }
        if (bad.isEmpty()) return
        val index = queryMediaIndex(ctx)
        if (index.isEmpty()) return
        var changed = false
        bad.forEach { e ->
            val newUri = index["${e.title}|${e.artist}"] ?: return@forEach
            entries[e.id] = e.copy(uri = newUri)
            changed = true
        }
        if (changed) persist(ctx)
    }

    /** uri 当前是否真的读得开（权限 + 行是否存在，一次 openFileDescriptor 即可判定） */
    private fun readable(ctx: Context, uri: Uri): Boolean = runCatching {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    /** 当前是否持有音频读取权限（33+ 用细分媒体权限，26~32 落在 READ_EXTERNAL_STORAGE） */
    private fun hasAudioPermission(ctx: Context): Boolean {
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.READ_MEDIA_AUDIO
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ctx.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** 媒体库索引：「标题|艺人」→ uri（同名同艺人重复时保留先出现的那条） */
    private fun queryMediaIndex(ctx: Context): Map<String, String> = runCatching {
        val collection: Uri = if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.provider.MediaStore.Audio.Media.getContentUri(
                android.provider.MediaStore.VOLUME_EXTERNAL,
            )
        } else {
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val proj = arrayOf(
            android.provider.MediaStore.Audio.Media._ID,
            android.provider.MediaStore.Audio.Media.TITLE,
            android.provider.MediaStore.Audio.Media.ARTIST,
        )
        val out = HashMap<String, String>()
        ctx.contentResolver.query(collection, proj, null, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID)
            val iTitle = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.TITLE)
            val iArtist = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ARTIST)
            while (c.moveToNext()) {
                val t = c.getString(iTitle) ?: continue
                val a = c.getString(iArtist) ?: "未知艺人"
                val uri = android.content.ContentUris.withAppendedId(collection, c.getLong(iId))
                out.putIfAbsent("$t|$a", uri.toString())
            }
        }
        out
    }.getOrDefault(emptyMap())

    private fun toSong(e: Entry): Song = Song(
        id = e.id,
        title = e.title,
        artist = e.artist,
        artistId = 0,
        album = e.album,
        albumId = 0,
        durationMs = e.durationMs,
        coverUrl = "local://${e.uri}",
    )
}
