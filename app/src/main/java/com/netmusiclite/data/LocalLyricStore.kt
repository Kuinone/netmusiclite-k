package com.netmusiclite.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * 本地歌曲（id < 0）的**歌词文件读取**。
 *
 * 背景（2026-10-04 用户口径「本地歌词没法看」）：
 *  歌词页旧版只有两条来源 —— 下载歌曲的 `ncm_{id}.lrc` 与线上接口 `/api/song/lyric/v1`。
 *  本地音乐（媒体库扫描 id = -mediaId、SAF 导入 id = -(1e9+n)）两条都走不通：
 *  前者文件名对不上、后者拿负数 id 请求服务端必然空 → 歌词页永远「暂无歌词」。
 *  而这批歌的歌词其实**就在手边**：
 *   ① 网易云等下载器落盘的**同名 .lrc**（音频旁边的 xxx.lrc / 少数用 .txt）；
 *   ② 音频文件**内嵌歌词帧**（MP3 的 ID3v2 USLT / FLAC 的 VORBIS_COMMENT LYRICS /
 *      M4A·MP4 的 ©lyr）。
 *
 * 本类只负责「把字节读出来」，解析交给 [parse]；在线匹配兜底见 [LocalLyricMatch]。
 *
 * ⚠ 只读，不写盘、不改动媒体库 —— 与 [ImportStore] 的「不复制文件」口径一致。
 */
object LocalLyricStore {

    /** 命中来源（UI 用来标注这首歌的歌词是从哪来的） */
    enum class Source { SIDECAR_LRC, EMBEDDED }

    /** 读到的原始歌词文本 + 来源 */
    data class Hit(val raw: String, val from: Source)

    /** ConcurrentHashMap 不允许 null 值，用哨兵表示「查过了，确实没有」 */
    private val NONE = Any()
    private val memo = ConcurrentHashMap<Long, Any>()

    /**
     * 读该本地歌曲的歌词；没有返回 null。结果按 song.id 记忆 ——
     * 歌词页可能被反复进出，同一首歌不必每次重读几 MB 音频头。
     */
    suspend fun read(ctx: Context, song: Song): Hit? = withContext(Dispatchers.IO) {
        memo[song.id]?.let { return@withContext if (it === NONE) null else it as Hit }
        val hit = runCatching { readInner(ctx, song) }.getOrNull()
        memo[song.id] = hit ?: NONE
        hit
    }

    /** 清掉记忆（「重新扫描本地音乐」后调用，避免换了同名文件还读旧结果） */
    fun invalidate() = memo.clear()

    /**
     * 批量预热（2026-10-06 用户口径「扫描本地音乐后自动查找是否有同名 lrc 读取」）：
     * 本地音乐扫描完成后调用 —— 后台把每首歌的歌词查找跑一遍填 [memo]，
     * 之后进歌词页直接命中缓存，旁挂 .lrc / 内嵌帧的查找成本不再落在打开歌词页那一刻。
     * 只处理 id<0 的本地歌；[read] 自带 memo 检查与 IO 调度，逐首串行即可。
     */
    suspend fun warmup(ctx: Context, songs: List<Song>) {
        songs.filter { it.id < 0 }.forEach { read(ctx, it) }
    }

    private fun readInner(ctx: Context, song: Song): Hit? {
        val uri = LocalMusic.localUri(song) ?: return null
        // ① 旁挂 .lrc 优先：下载器落盘的歌词比内嵌帧更新、且常带译文
        sidecarLrc(ctx, uri)?.let { return Hit(it, Source.SIDECAR_LRC) }
        // ② 音频内嵌歌词
        embeddedLyric(ctx, uri)?.let { return Hit(it, Source.EMBEDDED) }
        return null
    }

    // ---------------- ① 旁挂 .lrc ----------------

    private val SIDE_EXT = listOf("lrc", "LRC", "Lrc", "txt", "TXT")

    private fun sidecarLrc(ctx: Context, uri: Uri): String? {
        val path = guessFilePath(ctx, uri) ?: return null
        val audio = File(path)
        val dir = audio.parentFile ?: return null
        val base = audio.nameWithoutExtension
        if (base.isBlank()) return null
        for (ext in SIDE_EXT) {
            val f = File(dir, "$base.$ext")
            if (!f.isFile || f.length() <= 0L) continue
            val text = runCatching { decodeBytes(f.readBytes()) }.getOrNull()
            // 只有真像歌词（含时间轴或足够多行）才认，避免把同名的歌词说明 txt 当歌词
            if (!text.isNullOrBlank() && looksLikeLyric(text)) return text
        }
        return null
    }

    /** 形如 [mm:ss.xx] 至少一处，或非空行 >= 4 行 —— 排除「版权声明.txt」这类同名干扰 */
    private fun looksLikeLyric(text: String): Boolean {
        val tags = TAG.findAll(text).take(2).count()
        if (tags >= 2) return true
        return text.lineSequence().count { it.isNotBlank() } >= 4
    }

    /**
     * 由 content uri 反推真实文件路径（只为找旁边的 .lrc，读失败一律降级为「没有」）。
     *  · MediaStore：查 DATA 列（API 29+ 已废弃但手表上仍可用）
     *  · SAF 导入（DocumentsProvider）：把 "primary:Music/a.mp3" 还原成 /storage/emulated/0/Music/a.mp3
     */
    private fun guessFilePath(ctx: Context, uri: Uri): String? {
        when (uri.scheme) {
            "file" -> return uri.path
        }
        val auth = uri.authority ?: return null
        if (auth.contains("media")) {
            return runCatching {
                ctx.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                        if (i >= 0) c.getString(i)?.takeIf { it.isNotBlank() } else null
                    } else null
                }
            }.getOrNull()
        }
        if (auth.contains("documents")) {
            return runCatching {
                val docId = DocumentsContract.getDocumentId(uri)
                val parts = docId.split(":", limit = 2)
                if (parts.size != 2) return@runCatching null
                val root = if (parts[0].equals("primary", ignoreCase = true)) {
                    Environment.getExternalStorageDirectory()
                } else {
                    File("/storage/${parts[0]}")
                }
                File(root, parts[1]).absolutePath
            }.getOrNull()
        }
        return null
    }

    // ---------------- ② 音频内嵌歌词 ----------------

    private fun embeddedLyric(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            // 读 8 字节：前 4 认 ID3/FLAC，MP4 的 "ftyp" 在偏移 4（前 4 字节是 box size）
            val head = ByteArray(8)
            if (readFully(ins, head) < 8) return@use null
            when {
                head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() &&
                    head[2] == '3'.code.toByte() -> readId3v2(ctx, uri)
                head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
                    head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte() -> readFlacVorbis(ctx, uri)
                head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
                    head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte() -> readMp4Lyric(ctx, uri)
                else -> null
            }
        }
    }.getOrNull()

    /** MP3：ID3v2 里的 USLT（非同步歌词）/ TXXX:LYRICS / v2.2 的 ULT */
    private fun readId3v2(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val h = ByteArray(10)
            if (readFully(ins, h) < 10) return@use null
            val verMajor = h[3].toInt() and 0xFF
            val size = syncSafe(h, 6)
            if (size <= 0) return@use null
            // 正文帧区一次性读入（设上限，防止伪造头把内存拉爆）
            val want = min(size, 16 * 1024 * 1024)
            val buf = ByteArray(want)
            val got = readFully(ins, buf)
            if (got <= 0) return@use null
            parseId3Frames(if (got == want) buf else buf.copyOf(got), verMajor)
        }
    }.getOrNull()

    private fun syncSafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or
            ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or
            (b[off + 3].toInt() and 0x7F)

    private fun parseId3Frames(d: ByteArray, verMajor: Int): String? {
        var p = 0
        var uslt: String? = null
        var txxx: String? = null
        while (p < d.size) {
            val id: String
            val fsize: Int
            if (verMajor == 2) {
                if (p + 6 > d.size) break
                id = String(d, p, 3, Charsets.ISO_8859_1)
                if (d[p] == 0.toByte() || !id[0].isLetterOrDigit()) break
                fsize = ((d[p + 3].toInt() and 0xFF) shl 16) or
                    ((d[p + 4].toInt() and 0xFF) shl 8) or (d[p + 5].toInt() and 0xFF)
                p += 6
            } else {
                if (p + 10 > d.size) break
                id = String(d, p, 4, Charsets.ISO_8859_1)
                if (d[p] == 0.toByte() || !id[0].isLetterOrDigit()) break
                fsize = if (verMajor >= 4) {
                    syncSafe(d, p + 4)
                } else {
                    ((d[p + 4].toInt() and 0xFF) shl 24) or ((d[p + 5].toInt() and 0xFF) shl 16) or
                        ((d[p + 6].toInt() and 0xFF) shl 8) or (d[p + 7].toInt() and 0xFF)
                }
                p += 10
            }
            if (fsize <= 0 || p + fsize > d.size) break
            when (id) {
                "USLT", "ULT" -> if (uslt == null) uslt = readDescFrame(d, p, fsize)
                "TXXX" -> if (txxx == null) txxx = readTxxxLyric(d, p, fsize)
            }
            p += fsize
            if (uslt != null && txxx != null) break
        }
        return uslt ?: txxx
    }

    /** USLT/ULT：encoding(1) + language(3) + 描述(terminated) + 歌词正文 */
    private fun readDescFrame(d: ByteArray, off: Int, size: Int): String? {
        if (size < 5) return null
        val enc = d[off].toInt() and 0xFF
        val end = off + size
        var p = off + 4 // 跳过 encoding + language
        p = skipTerminated(d, p, end, enc)
        if (p >= end) return null
        return decodeByEnc(d, p, end, enc)
    }

    /** TXXX：encoding(1) + 描述(terminated) + 值；描述里含 LYRICS 才认 */
    private fun readTxxxLyric(d: ByteArray, off: Int, size: Int): String? {
        if (size < 4) return null
        val enc = d[off].toInt() and 0xFF
        val end = off + size
        val descEnd = indexTerminated(d, off + 1, end, enc)
        val desc = String(d, off + 1, (descEnd - off - 1).coerceAtLeast(0), Charsets.ISO_8859_1)
        if (!desc.uppercase().contains("LYRIC")) return null
        if (descEnd >= end) return null
        return decodeByEnc(d, descEnd, end, enc)
    }

    private fun skipTerminated(d: ByteArray, start: Int, end: Int, enc: Int): Int =
        indexTerminated(d, start, end, enc)

    /** 返回终止符**之后**的位置；双字节编码按 2 字节对齐找 00 00 */
    private fun indexTerminated(d: ByteArray, start: Int, end: Int, enc: Int): Int {
        var p = start
        if (enc == 1 || enc == 2) {
            while (p + 1 < end) {
                if (d[p] == 0.toByte() && d[p + 1] == 0.toByte()) return p + 2
                p += 2
            }
            return end
        }
        while (p < end) {
            if (d[p] == 0.toByte()) return p + 1
            p++
        }
        return end
    }

    /** FLAC：跳过 4 字节 "fLaC" 后，在元数据块区找 VORBIS_COMMENT(type 4) 的 LYRICS */
    private fun readFlacVorbis(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            if (!skip(ins, 4)) return@use null
            var guard = 0
            while (guard++ < 64) {
                val h = ByteArray(4)
                if (readFully(ins, h) < 4) return@use null
                val last = (h[0].toInt() and 0x80) != 0
                val type = h[0].toInt() and 0x7F
                val size = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or
                    (h[3].toInt() and 0xFF)
                if (size < 0 || size > 32 * 1024 * 1024) return@use null
                if (type == 4) {
                    val b = ByteArray(size)
                    if (size > 0 && readFully(ins, b) < size) return@use null
                    parseVorbisComment(b)?.let { return@use it }
                } else if (size > 0 && !skip(ins, size.toLong())) {
                    return@use null
                }
                if (last) return@use null
            }
            null
        }
    }.getOrNull()

    private fun parseVorbisComment(b: ByteArray): String? {
        if (b.size < 8) return null
        var p = 0
        fun u32(): Int {
            if (p + 4 > b.size) return -1
            val v = (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
                ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)
            p += 4
            return v
        }
        val vlen = u32()
        if (vlen < 0 || p + vlen > b.size) return null
        p += vlen
        val count = u32()
        if (count < 0) return null
        repeat(count.coerceAtMost(512)) {
            val len = u32()
            if (len < 0 || p + len > b.size) return@repeat
            val s = String(b, p, len, Charsets.UTF_8)
            p += len
            val eq = s.indexOf('=')
            if (eq > 0) {
                val k = s.substring(0, eq).uppercase()
                if (k == "LYRICS" || k == "UNSYNCEDLYRICS" || k == "UNSYNCED LYRICS") {
                    val v = s.substring(eq + 1).trim()
                    if (v.isNotBlank()) return v
                }
            }
        }
        return null
    }

    /** M4A/MP4：ilst 里的 ©lyr 原子（正文在紧随其后的 data 原子） */
    private fun readMp4Lyric(ctx: Context, uri: Uri): String? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            // ©lyr 一般在文件头附近，扫前 2MB 足够；解析完整 atom 树成本不值得
            val cap = 2 * 1024 * 1024
            val buf = ByteArray(cap)
            val got = readFully(ins, buf)
            if (got < 24) return@use null
            val d = if (got == cap) buf else buf.copyOf(got)
            val key = byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte())
            var i = 0
            while (i + 16 <= d.size) {
                if (d[i] == key[0] && d[i + 1] == key[1] && d[i + 2] == key[2] && d[i + 3] == key[3]) {
                    var j = i + 4
                    while (j + 16 <= d.size &&
                        !(d[j + 4] == 'd'.code.toByte() && d[j + 5] == 'a'.code.toByte() &&
                            d[j + 6] == 't'.code.toByte() && d[j + 7] == 'a'.code.toByte())
                    ) {
                        j += 4
                    }
                    if (j + 16 > d.size) return@use null
                    val size = ((d[j].toInt() and 0xFF) shl 24) or ((d[j + 1].toInt() and 0xFF) shl 16) or
                        ((d[j + 2].toInt() and 0xFF) shl 8) or (d[j + 3].toInt() and 0xFF)
                    // data 原子 = size(4) + 'data'(4) + type/locale(8) + 正文
                    val bodyStart = j + 16
                    val bodyLen = size - 16
                    if (bodyLen > 0 && bodyStart + bodyLen <= d.size) {
                        val text = decodeBytes(d.copyOfRange(bodyStart, bodyStart + bodyLen))
                        if (text.isNotBlank()) return@use text
                    }
                }
                i++
            }
            null
        }
    }.getOrNull()

    // ---------------- 解析 ----------------

    /** `[mm:ss]` / `[mm:ss.xx]` / `[mm:ss.xxx]` / `[mm:ss:xx]`，一行可挂多个 */
    private val TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /**
     * ★ 2026-10-22 用户口径⑨「本地歌曲文件需要支持逐字歌词」：
     * 增强 LRC / A2 格式的**逐字时间标签**用尖括号包住，与 [TAG] 同构（分组 1=分、2=秒、3=小数）：
     *     `[00:12.34]<00:12.34>字<00:12.80>字2<00:13.20>`
     * 时间同样是**相对整首歌的绝对毫秒**，与在线 yrc 解析出的 [LyricWord.startMs] 语义一致，
     * 所以 LyricsScreen 的逐字渲染/扫光不需要任何改动。
     */
    private val WORD_TAG = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    /** 解析中间结果：一行歌词 + 它自己的逐字表（普通 lrc 行为 null，交给「假逐字」逻辑） */
    private class RawLine(val timeMs: Long, val text: String, val words: List<LyricWord>?)

    private fun fracMs(s: String): Long = when {
        s.isEmpty() -> 0L
        s.length == 1 -> s.toLong() * 100
        s.length == 2 -> s.toLong() * 10
        else -> s.take(3).toLong()
    }

    /** 单个时间标签（`[mm:ss.xx]` 或 `<mm:ss.xx>`，分组结构相同）→ 绝对毫秒 */
    private fun tagTimeMs(m: MatchResult): Long =
        m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1_000 +
            fracMs(m.groupValues[3])

    /**
     * 本地 lrc 文本 → (带轴歌词行, 同时间戳译文表)。
     * 与 [DownloadStore.localLyric] 的合并规则保持一致：同一时间戳的第二行 = 译文。
     *
     * ★ 2026-10-22 用户口径⑨：除普通 lrc 外，还支持两种**逐字**写法，解析进 [LyricLine.words]
     *   （A2 增强 LRC 的 `<mm:ss.xx>字`，以及行内时间标签的逐字 LRC `[mm:ss.xx]字`）。
     *   没有逐字标签的行 words = null —— 与改动前完全一样，调用方回落到整行/假逐字。
     *
     * @param durationMs 歌曲时长；**仅当整篇没有时间轴**（纯文本歌词）时用来均分出轴，
     *                   否则传 0 即可 —— 没轴的歌词至少能滚起来，好过一片空白。
     */
    fun parse(raw: String, durationMs: Int = 0): Pair<List<LyricLine>, Map<Long, String>> {
        val pending = ArrayList<RawLine>()
        raw.lineSequence().forEach { rawLine -> parseLine(rawLine)?.let { pending.addAll(it) } }
        if (pending.isEmpty()) {
            // 无时间轴（不少内嵌歌词就是纯文本）：按行序在歌曲时长内均分，让它能跟唱
            val plain = raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }
                .filterNot { it.startsWith("[") && it.endsWith("]") } // 丢掉 [ti:] [ar:] 这类元信息
                .toList()
            if (plain.size < 2) return emptyList<LyricLine>() to emptyMap()
            val span = if (durationMs > 1_000) durationMs.toLong() else (plain.size * 4_000L)
            val step = span / plain.size
            return plain.mapIndexed { i, t -> LyricLine(i * step, t) } to emptyMap()
        }
        pending.sortBy { it.timeMs }
        val lines = ArrayList<LyricLine>(pending.size)
        val trans = HashMap<Long, String>()
        var prev = -1L
        pending.forEach { p ->
            if (p.timeMs == prev && lines.isNotEmpty()) {
                // 同时间戳的第二行 = 译文。但**内容与原文完全相同**时那是重复行、不是译文，
                // 收下会渲染成「主歌词 + 一模一样的译文」上下两行 = 重影（2026-10-04 修复）
                if (p.text != lines.last().text) trans[p.timeMs] = p.text
            } else {
                lines.add(LyricLine(p.timeMs, p.text, p.words))
                prev = p.timeMs
            }
        }
        return fillWordTails(lines) to trans
    }

    /**
     * 单行 → 若干 [RawLine]；null = 这行不是带时间轴的歌词（元信息 / 空行 / 纯文本）。
     *
     * 三种写法（★ 2026-10-22 用户口径⑨：本地歌曲也要逐字）：
     *  ① **A2 / 增强 LRC**：`[行时间]<词时间>字<词时间>字…` —— 行首可有多个行时间标签
     *     （同一句在多个时间点重复出现），词时间标签在正文里；
     *  ② **逐字 LRC**：`[行时间]字[词时间]字…` —— 时间标签**穿插在正文中间**；
     *  ③ **普通 lrc**：所有标签都在正文之前（一行多个 = 重复行），正文里没有标签。
     *
     * ③ 的取值方式与改动前**逐字符一致**（[parseLegacyLine] 就是原实现）—— 本地歌词
     * 里有大量历史文件，普通行不能因为这次改动出现任何排版/滚动差异。
     */
    private fun parseLine(rawLine: String): List<RawLine>? {
        if (rawLine.isBlank()) return null
        // ① 有尖括号词标签 → 按 A2 解析；畸形（缺行首行时间等）则返回 null 继续往下走
        if (rawLine.contains('<')) parseA2Line(rawLine)?.let { return it }
        val tags = TAG.findAll(rawLine).toList()
        if (tags.isEmpty()) return null
        // ②/③ 判据：标签是否全部落在正文之前（标签之间只允许空白）
        val prefix = tagsArePrefix(rawLine, tags)
        if (prefix) return parseLegacyLine(rawLine, tags)
        // ② 行内标签：先尝试逐字；解析不出至少 2 个词段再退回旧行为（保证不丢行）
        return parseInlineWordLine(rawLine, tags) ?: parseLegacyLine(rawLine, tags)
    }

    /** 标签全部位于正文之前（第一个标签前、相邻标签之间都只有空白） */
    private fun tagsArePrefix(line: String, tags: List<MatchResult>): Boolean {
        if (line.substring(0, tags.first().range.first).isNotBlank()) return false
        for (i in 0 until tags.size - 1) {
            if (line.substring(tags[i].range.last + 1, tags[i + 1].range.first).isNotBlank()) return false
        }
        return true
    }

    /**
     * 普通 lrc 行（**改动前的原实现**）：删掉行内全部时间标签当正文，
     * 每个标签各产出一行（标准 lrc 的一行多时间戳 = 同一句重复出现）。
     */
    private fun parseLegacyLine(line: String, tags: List<MatchResult>): List<RawLine>? {
        val text = TAG.replace(line, "").trim()
        if (text.isEmpty()) return null
        return tags.map { RawLine(tagTimeMs(it), text, null) }
    }

    /**
     * A2 / 增强 LRC 逐字行：`[行时间]<词时间>字<词时间>字…`
     * 行首的 `[mm:ss.xx]` 是**行的**开始时间（可多个 = 重复出现），`<mm:ss.xx>` 是每个字的开始时间。
     * 行尾可能还有一个 `<mm:ss.xx>`（无文本）专门用来给最后一个字收尾 —— 有就用它，没有由
     * [fillWordTails] 按下一句估。
     */
    private fun parseA2Line(line: String): List<RawLine>? {
        val firstWord = WORD_TAG.find(line) ?: return null
        // 行首行时间标签：都在第一个词标签之前、且标签前只能是空白
        val headTags = TAG.findAll(line).takeWhile { it.range.first < firstWord.range.first }.toList()
        if (headTags.isNotEmpty() && line.substring(0, headTags.first().range.first).isNotBlank()) return null
        // 畸形 A2（行首没写 [行时间]）：词标签前只有空白时，用第一个词的时间当行时间 ——
        // 总比把 `<mm:ss.xx>` 原样显示在歌词里好
        if (headTags.isEmpty() && line.substring(0, firstWord.range.first).isNotBlank()) return null
        val collects = WORD_TAG.findAll(line).toList()
        val rawWords = ArrayList<LyricWord>(collects.size)
        collects.forEachIndexed { i, m ->
            val start = m.range.last + 1
            val end = collects.getOrNull(i + 1)?.range?.first ?: line.length
            val text = line.substring(start, end)
            if (text.isEmpty()) return@forEachIndexed // 收尾标签：只提供时间，不出词
            val at = tagTimeMs(m)
            val nextAt = collects.getOrNull(i + 1)?.let { tagTimeMs(it) }
            rawWords.add(LyricWord(text, at, if (nextAt != null && nextAt > at) nextAt - at else 0L))
        }
        val lineTime = headTags.firstOrNull()?.let { tagTimeMs(it) }
            ?: rawWords.firstOrNull()?.startMs ?: return null
        val base = wordLine(lineTime, rawWords)?.firstOrNull() ?: return null
        return if (headTags.isEmpty()) listOf(base) else headTags.map { RawLine(tagTimeMs(it), base.text, base.words) }
    }

    /**
     * 逐字 LRC 行：`[行时间]字[词时间]字2…` —— 每个时间标签后面紧跟属于它的文本。
     * 第一个标签同时是**行的**开始时间（其后可能直接跟第二个标签，表示第一个字从第二个时间开始）。
     */
    private fun parseInlineWordLine(line: String, tags: List<MatchResult>): List<RawLine>? {
        val segs = tags.mapIndexed { i, m ->
            val start = m.range.last + 1
            val end = tags.getOrNull(i + 1)?.range?.first ?: line.length
            tagTimeMs(m) to line.substring(start, end)
        }
        val rawWords = ArrayList<LyricWord>(segs.size)
        segs.forEachIndexed { i, (at, text) ->
            if (text.isEmpty()) return@forEachIndexed
            val nextAt = segs.getOrNull(i + 1)?.first
            rawWords.add(LyricWord(text, at, if (nextAt != null && nextAt > at) nextAt - at else 0L))
        }
        return wordLine(segs.first().first, rawWords)
    }

    /**
     * 把逐字片段收成一行。**关键约束**：返回的 `text` 必须严格等于 `words` 的文本拼接 ——
     * LyricsScreen.drawCharProgressMask 从排版文本的第 0 个字符开始，按 words 顺序逐字对齐，
     * 首尾多一个空格就会让整行逐字框错位（所以这里按 trim 后的全局区间重新切分每个词）。
     *
     * 少于 2 个词段时认为没有逐字信息（一个词 = 整句），words 置 null 交给「假逐字」逻辑。
     */
    private fun wordLine(lineTime: Long, raw: List<LyricWord>): List<RawLine>? {
        if (raw.isEmpty()) return null
        val joined = buildString { raw.forEach { append(it.text) } }
        val first = joined.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return null
        val last = joined.indexOfLast { !it.isWhitespace() }
        val text = joined.substring(first, last + 1)
        val words = ArrayList<LyricWord>(raw.size)
        var pos = 0
        raw.forEach { w ->
            val from = maxOf(pos, first)
            val to = minOf(pos + w.text.length, last + 1)
            pos += w.text.length
            if (from < to) words.add(w.copy(text = joined.substring(from, to)))
        }
        if (words.size < 2) return listOf(RawLine(lineTime, text, null))
        return listOf(RawLine(lineTime, text, words))
    }

    /**
     * 逐字表尾巴兜底：行内最后一个 `<t>字` 后面没有收尾标签时，这个字的时长是未知的。
     * 用「下一句开始 - 本字开始」估，并**封顶 2000ms**：
     * LyricsScreen.singWindowMs 拿 `last.start + last.duration` 当本句演唱窗口，
     * 若直接取间隔，间奏二十秒就会让最后一个字亮二十秒（该文件 807 行注释记录过同类 bug）。
     */
    private fun fillWordTails(lines: List<LyricLine>): List<LyricLine> {
        if (lines.none { it.words?.isNotEmpty() == true }) return lines
        return lines.mapIndexed { i, line ->
            val w = line.words
            if (w.isNullOrEmpty()) return@mapIndexed line
            val tail = w.last()
            if (tail.durationMs > 0L) return@mapIndexed line
            val gap = (lines.getOrNull(i + 1)?.timeMs ?: (line.timeMs + 4_000L)) - tail.startMs
            line.copy(words = w.dropLast(1) + tail.copy(durationMs = gap.coerceIn(300L, 2_000L)))
        }
    }

    // ---------------- 文本解码 ----------------

    /** 带 BOM 与 UTF-8 严格校验、失败回落 GBK —— 国产下载器的 lrc 常是 GBK */
    fun decodeBytes(b: ByteArray): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
            return String(b, 3, b.size - 3, Charsets.UTF_8)
        }
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) {
            return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        }
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) {
            return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        }
        val strict: CharsetDecoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { strict.decode(ByteBuffer.wrap(b)).toString() }
            .getOrElse {
                runCatching { String(b, charset("GBK")) }.getOrElse { String(b, Charsets.UTF_8) }
            }
    }

    /** 按 ID3 的 encoding 字节解帧正文 */
    private fun decodeByEnc(d: ByteArray, start: Int, end: Int, enc: Int): String? {
        if (end <= start) return null
        val s = when (enc) {
            0 -> {
                // ISO-8859-1 是「标称」值，但国产工具常往这里塞 GBK —— 有高位字节就按 GBK 再试
                val high = (start until end).count { (d[it].toInt() and 0xFF) >= 0x80 }
                if (high > 0) {
                    runCatching { String(d, start, end - start, charset("GBK")) }
                        .getOrElse { String(d, start, end - start, Charsets.ISO_8859_1) }
                } else {
                    String(d, start, end - start, Charsets.ISO_8859_1)
                }
            }
            1 -> {
                if (end - start >= 2 && d[start] == 0xFF.toByte() && d[start + 1] == 0xFE.toByte()) {
                    String(d, start + 2, end - start - 2, Charsets.UTF_16LE)
                } else if (end - start >= 2 && d[start] == 0xFE.toByte() && d[start + 1] == 0xFF.toByte()) {
                    String(d, start + 2, end - start - 2, Charsets.UTF_16BE)
                } else {
                    String(d, start, end - start, Charsets.UTF_16LE)
                }
            }
            2 -> String(d, start, end - start, Charsets.UTF_16BE)
            else -> String(d, start, end - start, Charsets.UTF_8)
        }
        return s.trim().takeIf { it.isNotBlank() }
    }

    // ---------------- 字节工具 ----------------

    private fun readFully(ins: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = ins.read(buf, off, buf.size - off)
            if (r <= 0) break
            off += r
        }
        return off
    }

    private fun skip(ins: InputStream, n: Long): Boolean {
        var left = n
        val tmp = ByteArray(8192)
        while (left > 0) {
            val r = ins.read(tmp, 0, min(left, tmp.size.toLong()).toInt())
            if (r <= 0) return false
            left -= r
        }
        return true
    }
}
