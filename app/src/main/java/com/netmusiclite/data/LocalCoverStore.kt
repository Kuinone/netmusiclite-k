package com.netmusiclite.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
// 注意：limitedParallelism 是 CoroutineDispatcher 的成员函数（kotlinx.coroutines 1.8.1 实测），
// 不是顶层扩展 —— 写 `import kotlinx.coroutines.limitedParallelism` 会直接 Unresolved reference。

/**
 * 本地歌曲封面：从音频文件内嵌封面（ID3 APIC / MP4 covr / FLAC picture…）提取并落盘缓存。
 *
 * ## 背景
 *
 * 本地歌曲（MediaStore 扫描 + 用户导入）的 coverUrl 字段复用为 `local://<音频文件Uri>`
 * （见 LocalMusic.localUri），本身没有封面地址 —— 此前封面模型直接把音频 Uri 当图片
 * 交给 Coil，必然加载失败，最终只能显示生成的兜底封面。
 *
 * ## 用法（songCoverModel 内）
 *
 * ```
 * LocalCoverStore.ensure(song.id, uri)          // 幂等后台提取（IO 线程）
 * if (LocalCoverStore.states[song.id] == 2)     // 可观察态：就绪的一瞬触发重组
 *     LocalCoverStore.coverFile(song.id)?.let { return it }
 * return null                                   // 无内嵌封面 → 走兜底生成封面
 * ```
 *
 * states 值：2=就绪 / 1=提取中 / -1=该文件无内嵌封面（不再重试）/ 未出现=未处理。
 * PlayerEngine.coverUriFor 复用 songCoverModel，故媒体会话/系统媒体卡片自动同样受益。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
object LocalCoverStore {

    private const val TAG = "LocalCover"

    @Volatile private var appCtx: Context? = null

    /**
     * 提取协程**单并发**串行：
     *  ① states 这张快照表的写入完全顺序化 —— 多个 IO 线程并发 put 同一张
     *     SnapshotStateMap 存在丢更新（copy-on-write 竞态）风险；
     *  ② 手表上不让 N 个 MediaMetadataRetriever 并发开音频文件压垮 IO。
     */
    private val scope =
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO.limitedParallelism(1))
    private val inflight = HashSet<Long>()

    /** 可观察态：songId → 2=就绪 / 1=提取中 / -1=无内嵌封面。读取处会建立重组依赖。 */
    val states = mutableStateMapOf<Long, Int>()

    /** WmusicApp.onCreate 调一次。 */
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    private fun dir(ctx: Context): File = File(ctx.filesDir, "covers_local")

    fun coverFile(ctx: Context, songId: Long): File = File(dir(ctx), "$songId.jpg")

    /** 供组合期/封面解析调用的便捷重载（用内部 appCtx）：就绪且文件有效才返回。 */
    fun coverFileIfReady(songId: Long): File? {
        val ctx = appCtx ?: return null
        if (states[songId] != 2) return null
        val f = coverFile(ctx, songId)
        return f.takeIf { it.exists() && it.length() > 0 }
    }

    /**
     * 幂等提取：未处理过的本地歌曲排队提取一次内嵌封面。
     * 可在组合期安全调用 —— **组合期只读 [states]，所有写入都在后台协程里**。
     *
     * ★ 2026-10-11 崩溃修复（用户反馈：只导入本地音乐，本地音乐列表滑动几下就闪退；
     *   模拟器实测必现）：旧版在这里同步写 `states[songId] = 1`（组合期写快照状态），
     *   与提取协程在 IO 线程写同一张表并发时，Compose 直接抛
     *   `IllegalStateException: Unsupported concurrent change during composition.
     *   A state object was modified by composition as well as being modified outside
     *   composition.` —— 滑动越快、新卡组合与提取完成撞在一起的概率越高。
     *   规则：**任何快照状态的写入都不允许发生在组合期**；ensure 在组合期只做
     *   「读状态 + 入队」，"提取中"标记由后台协程落。
     */
    fun ensure(songId: Long, audioUri: Uri) {
        val ctx = appCtx ?: return
        if (states[songId] != null) return // 2/1/-1 均视为已处理；未处理（null）才入队
        synchronized(inflight) {
            if (!inflight.add(songId)) return
        }
        scope.launch {
            states[songId] = 1
            val state = runCatching { extract(ctx, songId, audioUri) }
                .onFailure { Log.w(TAG, "extract fail id=$songId: $it") }
                .getOrDefault(-1)
            states[songId] = state
            synchronized(inflight) { inflight.remove(songId) }
        }
    }

    private fun extract(ctx: Context, songId: Long, audioUri: Uri): Int {
        val mmr = MediaMetadataRetriever()
        val bytes = runCatching {
            mmr.setDataSource(ctx, audioUri)
            mmr.embeddedPicture
        }.getOrNull()
        runCatching { mmr.release() }
        if (bytes == null || bytes.isEmpty()) return -1
        return runCatching {
            val f = coverFile(ctx, songId)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) {
                tmp.delete()
                f.writeBytes(bytes)
            }
            2
        }.getOrDefault(-1)
    }
}
