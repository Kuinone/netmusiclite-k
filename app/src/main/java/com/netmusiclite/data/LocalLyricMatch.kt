package com.netmusiclite.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地歌曲的**在线歌词兜底匹配**（2026-10-04）。
 *
 * 用途：本地歌曲既没有旁挂 .lrc、也没有内嵌歌词帧时，用「标题 + 艺人」去网易云搜一次，
 * 取最匹配的一条拉歌词 —— 好过歌词页只剩一句「暂无歌词」。
 *
 * ⚠ 这是**兜底**，不是主路（主路见 [LocalLyricStore]）。所以判据刻意收得很紧：
 *  · 标题归一化后必须**完全相等**（去括号补充信息、去空格与标点、全角转半角、忽略大小写），
 *    或一方完整包含另一方且长度接近；
 *  · 艺人至少一方包含另一方（本地文件名常把多位艺人写成 "A/B"）；
 *  · 达不到阈值就返回 null，宁可显示「暂无歌词」，也不给用户配一首错的。
 *
 * 结果按「标题+艺人」记忆 —— 同一首歌反复进出歌词页只搜一次。
 */
object LocalLyricMatch {

    /** @param onlineId 匹配到的网易云歌曲 id（UI 标注 / 后续详情跳转都要用） */
    data class Hit(
        val onlineId: Long,
        val title: String,
        val artist: String,
        val bundle: NcmApi.LyricBundle,
    )

    private val NONE = Any()
    private val memo = ConcurrentHashMap<String, Any>()

    /** 命中阈值：标题完全一致 = 100，包含关系 = 70；低于 70 一律不认 */
    private const val THRESHOLD = 70

    suspend fun find(song: Song): Hit? = withContext(Dispatchers.IO) {
        val key = "${song.title}\u0000${song.artist}"
        memo[key]?.let { return@withContext if (it === NONE) null else it as Hit }
        val hit = runCatching { findInner(song) }.getOrNull()
        memo[key] = hit ?: NONE
        hit
    }

    private suspend fun findInner(song: Song): Hit? {
        val kw = listOf(song.title, song.artist)
            .filter { it.isNotBlank() && it != "未知艺人" && it != "未知歌曲" }
            .joinToString(" ")
        if (kw.isBlank()) return null
        val cands = NcmApi.searchSongs(kw, limit = 10)
        if (cands.isEmpty()) return null
        val best = cands.map { it to score(song, it) }.filter { it.second >= THRESHOLD }
            .maxByOrNull { it.second }?.first ?: return null
        val bundle = NcmApi.lyricV1(best.id)
        if (bundle.lrc.isNullOrBlank()) return null
        return Hit(best.id, best.title, best.artist, bundle)
    }

    /** 相似度打分；< 70 = 不像同一首 */
    private fun score(local: Song, cand: Song): Int {
        val lt = norm(local.title)
        val ct = norm(cand.title)
        if (lt.isEmpty() || ct.isEmpty()) return 0
        var s = when {
            lt == ct -> 100
            ct.contains(lt) || lt.contains(ct) -> {
                val ratio = minOf(lt.length, ct.length).toDouble() / maxOf(lt.length, ct.length)
                // 长度差太多（如「夜曲」命中「夜曲( Live )」之外的杂项）不给满分
                if (ratio >= 0.6) 70 else 0
            }
            else -> 0
        }
        if (s == 0) return 0
        val la = norm(local.artist)
        val ca = norm(cand.artist)
        if (la.isNotEmpty() && ca.isNotEmpty()) {
            if (la == ca) s += 25
            else if (ca.contains(la) || la.contains(ca)) s += 15
        }
        return s
    }

    /**
     * 归一化：丢掉 (…) […] 【…】 这类补充信息，去掉空白与常见标点，
     * 全角转半角并统一小写 —— 本地文件名里的差异几乎都出在这几处。
     */
    private fun norm(raw: String): String {
        var s = raw
        s = s.replace(Regex("""[（(\[【][^)）\]】]*[)）\]】]"""), "")
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val c = when {
                ch.code == 0x3000 -> ' '
                ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()
                else -> ch
            }
            if (c.isLetterOrDigit()) sb.append(c.lowercaseChar())
        }
        return sb.toString()
    }
}
