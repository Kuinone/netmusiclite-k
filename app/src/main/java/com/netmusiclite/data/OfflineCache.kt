package com.netmusiclite.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 离线兜底缓存（2026-09-04）：
 * 断网时「我喜欢」回退到上次成功拉取的列表（files/liked_cache.json）。
 * 只在网络成功时覆写；断网读到的是缓存快照，联后自动刷新。
 */
object OfflineCache {
    private lateinit var ctx: Context

    fun init(c: Context) { ctx = c.applicationContext }

    private fun likedFile() = File(ctx.filesDir, "liked_cache.json")

    private fun songJson(s: Song) = JSONObject()
        .put("id", s.id).put("t", s.title).put("ar", s.artist).put("arid", s.artistId)
        .put("al", s.album).put("alid", s.albumId).put("dt", s.durationMs)
        .put("cv", s.coverUrl ?: "")

    private fun songFrom(o: JSONObject) = Song(
        o.optLong("id"), o.optString("t"), o.optString("ar"), o.optLong("arid"),
        o.optString("al"), o.optLong("alid"), o.optInt("dt"),
        o.optString("cv").ifEmpty { null },
    )

    fun saveLiked(songs: List<Song>) {
        runCatching {
            val arr = JSONArray()
            songs.forEach { arr.put(songJson(it)) }
            val tmp = File(ctx.filesDir, "liked_cache.tmp")
            tmp.writeText(JSONObject().put("songs", arr).toString())
            tmp.renameTo(likedFile())
        }
    }

    /** 断网兜底：返回上次缓存的我喜欢，从未缓存过返回 null */
    fun loadLiked(): List<Song>? {
        val f = likedFile()
        if (!f.exists()) return null
        return runCatching {
            val arr = JSONObject(f.readText()).optJSONArray("songs") ?: return null
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { runCatching { songFrom(it) }.getOrNull() }
            }
        }.getOrNull()
    }

    // ---------- 歌单列表（2026-10-10 离线缓存专项）----------
    // 只缓存列表元数据（歌单名/封面地址/曲目数），不含音频文件；
    // 仅在网络成功时覆写，断网读到缓存快照。

    private fun playlistsFile() = File(ctx.filesDir, "playlists_cache.json")

    private fun plJson(p: PlaylistItem) = JSONObject()
        .put("id", p.id).put("n", p.name).put("cv", p.coverUrl ?: "")
        .put("tc", p.trackCount).put("pc", p.playCount)
        .put("mine", p.isMine).put("sp", p.specialType).put("sub", p.subscribed)

    private fun plFrom(o: JSONObject) = PlaylistItem(
        o.optLong("id"), o.optString("n"), o.optString("cv").ifEmpty { null },
        o.optInt("tc"), o.optLong("pc"), o.optBoolean("mine"), o.optInt("sp"),
        o.optBoolean("sub"),
    )

    fun savePlaylists(list: List<PlaylistItem>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { arr.put(plJson(it)) }
            val tmp = File(ctx.filesDir, "playlists_cache.tmp")
            tmp.writeText(JSONObject().put("items", arr).toString())
            tmp.renameTo(playlistsFile())
        }
    }

    /** 断网兜底：返回上次缓存的歌单列表，从未缓存过返回 null */
    fun loadPlaylists(): List<PlaylistItem>? {
        val f = playlistsFile()
        if (!f.exists()) return null
        return runCatching {
            val arr = JSONObject(f.readText()).optJSONArray("items") ?: return null
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { runCatching { plFrom(it) }.getOrNull() }
            }
        }.getOrNull()
    }

    // ---------- 歌单详情（每歌单一文件：点击过的歌单才落盘，天然满足「浏览过的才缓存」）----------

    private fun plDetailFile(id: Long) = File(ctx.filesDir, "pl_detail_$id.json")

    fun savePlaylistDetail(id: Long, pl: PlaylistItem, songs: List<Song>) {
        runCatching {
            val tmp = File(ctx.filesDir, "pl_detail_$id.tmp")
            tmp.writeText(
                JSONObject()
                    .put("pl", plJson(pl))
                    .put("songs", JSONArray().also { a -> songs.forEach { a.put(songJson(it)) } })
                    .toString()
            )
            tmp.renameTo(plDetailFile(id))
            trimPlaylistDetails()
        }
    }

    /** 断网兜底：返回上次缓存的该歌单详情，从未点击过（无缓存文件）返回 null */
    fun loadPlaylistDetail(id: Long): Pair<PlaylistItem, List<Song>>? {
        val f = plDetailFile(id)
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            val pl = o.optJSONObject("pl")?.let { runCatching { plFrom(it) }.getOrNull() }
                ?: return null
            val songs = o.optJSONArray("songs")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { runCatching { songFrom(it) }.getOrNull() }
                }
            } ?: emptyList()
            pl to songs
        }.getOrNull()
    }

    /** 详情缓存上限：超过时按文件修改时间淘汰最旧的（防 filesDir 无限膨胀） */
    private const val PL_DETAIL_MAX = 30

    private fun trimPlaylistDetails() {
        runCatching {
            val files = ctx.filesDir.listFiles { f -> f.name.startsWith("pl_detail_") && f.name.endsWith(".json") }
                ?.sortedBy { it.lastModified() } ?: return
            if (files.size > PL_DETAIL_MAX) {
                files.take(files.size - PL_DETAIL_MAX).forEach { it.delete() }
            }
        }
    }

    // ---------- 红心 id 集合（独立落盘：断网启动时歌单里所有歌的红心标识都来自它）----------

    private fun likedIdsFile() = File(ctx.filesDir, "liked_ids_cache.json")

    fun saveLikedIds(ids: Set<Long>) {
        runCatching {
            val tmp = File(ctx.filesDir, "liked_ids_cache.tmp")
            tmp.writeText(JSONArray().also { a -> ids.forEach { a.put(it) } }.toString())
            tmp.renameTo(likedIdsFile())
        }
    }

    /** 断网兜底：返回上次缓存的全部红心歌曲 id，从未缓存过返回 null */
    fun loadLikedIds(): Set<Long>? {
        val f = likedIdsFile()
        if (!f.exists()) return null
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { arr.optLong(it) }.toSet()
        }.getOrNull()
    }
}
