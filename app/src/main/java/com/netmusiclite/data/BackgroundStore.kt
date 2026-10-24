package com.netmusiclite.data

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * 底层背景图：用户从相册选一张图作为全局背景（高斯玻璃材质的底图）。
 * 图片复制进应用私有目录，重启仍在；ready 驱动全屏背景层与玻璃材质。
 */
object BackgroundStore {
    private lateinit var prefs: SharedPreferences

    /**
     * 卡片取样底图的缓存文件名（名字里带引擎版本）。
     *
     * ★ 2026-10-16 从 `bg_blur_cards_v2` 升到 **v3**：换了模糊引擎（盒式近似 → Blurry 真高斯），
     *   缓存判据只有「mtime ≥ 源图 mtime」，沿用旧名字会命中上一版盒式糊出来的旧底图 ——
     *   换名字是最省事的失效手段（旧文件留在 cacheDir 由系统清理）。
     *
     * ★ 2026-10-16 二次（用户口径「现在冷启动好卡」）：v2 → v3 那次换名**踩了冷启动**——
     *   换名 = 一次必然的缓存未命中，而旧逻辑「未命中就当场重算」的重算链路是
     *   「**全分辨率**解码壁纸 → 裁方 → 缩 466 → RenderScript 真高斯 → JPEG 编码落盘」，
     *   在手表上足以吃掉 1~3 秒 CPU，且正好落在冷启动最紧的窗口里（首启必卡）。
     *   现在换成 **v4**，配套两条结构性修复：
     *     ① 未命中时**不再当场重算**：先拿上一版引擎的缓存顶上（[LEGACY_CARD_BLUR_CACHES]），
     *        重算延后 [STARTUP_DEFER_MS]（见 [loadCachedBlurred]）；
     *     ② 重算链路的壁纸解码改为**按需降采样**（见 [decodeWallpaper]），
     *        不再把 4000px 的整张原图解成 64MB 的 ARGB_8888。
     *   ⇒ 以后无论怎么换名/换引擎，升级后的首启都不再付这笔钱。
     */
    private const val CARD_BLUR_CACHE = "bg_blur_cards_v4.jpg"

    /** 上一版 / 上上版引擎产出的卡片底图（新缓存缺失时先顶上，避免升级后首启没有玻璃底） */
    private val LEGACY_CARD_BLUR_CACHES =
        listOf("bg_blur_cards_v3.jpg", "bg_blur_cards_v2.jpg")

    /** 背景模糊底图的缓存名（与卡片底图分开；同样有「未命中延后重算」的保护） */
    private const val BG_BLUR_CACHE = "bg_blur_bg.jpg"

    /**
     * 启动路径上「缓存未命中 → 重算」的延后时长：把重算挪出首帧/首屏最紧的窗口。
     * 4s 足够让启动浮层揭开、首屏组合完成；重算本身仍在 `genExecutor` 后台线程上跑。
     */
    private const val STARTUP_DEFER_MS = 4_000L

    /** 壁纸解码的目标长边（重算链路只产出 466 方图，留 2x 余量即可，不必解全图） */
    private const val WALLPAPER_DECODE_TARGET = 1024

    var ready by mutableStateOf(false)
        private set

    /**
     * 背景图版本号：每次成功换图/清空 +1。
     * 2026-10-01 修「不重置直接重选背景图 → 背景颜色不变，只有玻璃卡片变色」：
     * 背景层用 Coil 加载同一个文件路径 `bg.jpg`，Coil 默认以「路径」作缓存 key，
     * 换图后路径不变 ⇒ 命中旧图；而玻璃卡片读的是 blurredImage 状态位图（已更新），
     * 于是只有卡片变色。version 既驱动背景层重组，也参与 Coil 的 cacheKey。
     */
    var version by mutableStateOf(0)
        private set

    /** 模糊底图(466x466，与物理屏 1:1)：卡片按自身屏幕位置取样 = 真毛玻璃（固定半径=最大强度） */
    var blurredImage by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        private set

    /** 卡片毛玻璃强度 0..1（1 = 当前最大；用取样透明度混合实现，免重算） */
    var cardBlur by mutableStateOf(1f)
        private set

    /**
     * 卡片材质（2026-10-01 用户要求可在设置里切换）：
     *  · `true`  = **玻璃卡片** —— 从模糊底图按屏幕位置取样（真毛玻璃），强度由 [cardBlur] 调；
     *  · `false` = **黑色半透明卡片** —— 纯黑 45%，不取样、不做糊，功耗更低、文字对比更强。
     *
     * 设为 false 时设置页会隐藏「卡片模糊强度」滑杆（对黑色卡片没有意义），
     * 由 `frostedGlass()` 统一按本开关分流，所以全站卡片（含播放器等）一起生效。
     */
    var cardGlass by mutableStateOf(true)
        private set

    /**
     * 卡片玻璃透明度 0..1（2026-10-02 新增）：仅浅色主题无背景图的「透底轻玻璃」使用。
     * 值越大越透 —— 轻玻璃填充 alpha = 1 - 本值；默认 0.28 = LightPalette 白 72% 的原观感。
     * 滑杆往右拖 = 更透（卡片更「轻」，底色透出更多）；往左 = 更实、文字对比更强。
     * 有背景图时的玻璃不走这个值（那边的形态控制是「卡片模糊强度」滑杆）。
     */
    var cardTransparency by mutableStateOf(0.28f)
        private set

    /** 背景模糊强度 0..1（0 = 清晰；>0 时用独立模糊底图替换清晰背景） */
    var bgBlur by mutableStateOf(0f)
        private set
    var blurredBgImage by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        private set
    var dim by mutableStateOf(0.5f)      // 背景压暗系数(0~0.85)，保证文字可读
        private set
    var blur by mutableStateOf(true)     // 高斯模糊开关(API31+ 生效)
        private set

    /** 播放页/歌词页保留取色（2026-09-13）：设置背景图后，这两页仍用歌曲主色底而非透出背景图 */
    var keepAmbient by mutableStateOf(true)
        private set

    /**
     * 背景图主色（2026-10-01 新增）。
     *
     * 用途：**Android 11 及以下的动态取色种子来源**。本机（OWW261 / Android 11）实测
     * 连 `wallpaper` 服务都不存在（`service list` 里没有、也没有 Monet 的 RRO overlay），
     * 系统级壁纸取色在这块表上物理不可用；而用户自己设置的这张背景图，就是这台设备上
     * 唯一可自定义的「壁纸」，取它的主色在语义与观感上都等价于「跟随壁纸取色」。
     *
     * 取色算法复用 `data/ColorExtract.kt` 的 `dominantColorOf()`，与封面环境色同一套实现。
     * `null` = 没有背景图或尚未算出（此时主题回落到用户选的预设种子色，不会空转）。
     */
    var seedColor by mutableStateOf<Color?>(null)
        private set

    val file: File get() = File(ctx0.filesDir, "bg.jpg")
    private lateinit var ctx0: Context

    fun init(ctx: Context) {
        ctx0 = ctx.applicationContext
        prefs = ctx0.getSharedPreferences("ncm_bg", Context.MODE_PRIVATE)
        dim = prefs.getFloat("bg_dim", 0.5f)
        cardBlur = prefs.getFloat("card_blur", 1f)
        cardGlass = prefs.getBoolean("card_glass", true)
        cardTransparency = prefs.getFloat("card_transparency", 0.28f)
        bgBlur = prefs.getFloat("bg_blur_v", 0f)
        keepAmbient = prefs.getBoolean("bg_keep_ambient", true)
        ready = prefs.getBoolean("bg_on", false) && file.exists() && file.length() > 0
        if (ready) {
            loadCachedBlurred()
            extractSeed()
        }
    }

    /**
     * 启动即时毛玻璃：只做「解码缓存底图」（几十毫秒），**绝不在启动窗口里重算**。
     *
     * ★ 2026-10-16 二次（用户口径「现在冷启动好卡」）—— 旧版这里未命中就 `regenerateBlurred()`，
     *   而重算要「全分辨率解码壁纸 + RenderScript 真高斯 + JPEG 编码」，几秒钟 CPU 直接压在
     *   冷启动上。现在拆成两条：
     *     · 命中 → 解码（原路径，快）；
     *     · 未命中 → ① 先用上一版引擎的缓存顶上（有就零成本），② 重算延后 [STARTUP_DEFER_MS]
     *       再跑（那时启动已经结束，重算本身仍在 genExecutor 后台线程）。
     *   背景模糊底图（bgBlur 滑杆）同款处理。
     *
     * ⚠ 换壁纸（[setFrom]）那条**立即重算**保持不变：那是用户主动动作，不在启动路径上，
     *   延后反而会让用户看到旧壁纸的玻璃底。
     */
    private fun loadCachedBlurred() {
        genExecutor.execute {
            val srcTime = file.lastModified()
            val cards = java.io.File(ctx0.cacheDir, CARD_BLUR_CACHE)
            if (cards.exists() && cards.lastModified() >= srcTime) {
                decodeInto(cards) { blurredImage = it }
            } else {
                // 上一版引擎的底图先顶上：观感与新版只差「盒式近似 vs 真高斯」，肉眼几乎不可分，
                // 但省掉了一整个重算链路 —— 这是升级后首启不再卡的关键一步。
                val legacy = LEGACY_CARD_BLUR_CACHES
                    .map { java.io.File(ctx0.cacheDir, it) }
                    .firstOrNull { it.exists() && it.lastModified() >= srcTime }
                if (legacy != null) decodeInto(legacy) { blurredImage = it }
                uiHandler.postDelayed({ regenerateBlurred() }, STARTUP_DEFER_MS)
            }
            if (bgBlur > 0.01f) {
                val bgf = java.io.File(ctx0.cacheDir, BG_BLUR_CACHE)
                if (bgf.exists() && bgf.lastModified() >= srcTime) {
                    decodeInto(bgf) { blurredBgImage = it }
                } else {
                    val strength = bgBlur
                    uiHandler.postDelayed({ regenerateBgBlurred(strength) }, STARTUP_DEFER_MS)
                }
            }
        }
    }

    /** 解码缓存底图 → 主线程写状态（启动路径上唯一的 IO，几十毫秒） */
    private fun decodeInto(
        f: java.io.File,
        assign: (androidx.compose.ui.graphics.ImageBitmap) -> Unit,
    ) {
        android.graphics.BitmapFactory.decodeFile(f.absolutePath)?.let {
            val ib = it.asImageBitmap()
            uiHandler.post { assign(ib) }
        }
    }

    /** 从相册 Uri 复制为背景图（覆盖旧图），成功返回 true */
    fun setFrom(uri: Uri, resolver: ContentResolver): Boolean = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: return false
        file.length() > 0
    }.getOrDefault(false).also { ok ->
        if (ok) {
            prefs.edit().putBoolean("bg_on", true).apply()
            ready = true
            version++          // 通知背景层换 key 重载（否则命中 Coil 旧缓存）
            regenerateBlurred()
            extractSeed()      // 换图后同步刷新主色（Android 11 动态取色的种子来源）
            if (bgBlur > 0.01f) regenerateBgBlurred(bgBlur)
        }
    }

    fun updateDim(v: Float) {
        dim = v.coerceIn(0f, 0.85f)
        prefs.edit().putFloat("bg_dim", dim).apply()
    }

    fun updateCardBlur(v: Float) {
        cardBlur = v.coerceIn(0f, 1f)
        prefs.edit().putFloat("card_blur", cardBlur).apply()
    }

    /** 切换卡片材质：玻璃 ⇄ 黑色半透明（2026-10-01） */
    fun updateCardGlass(v: Boolean) {
        cardGlass = v
        prefs.edit().putBoolean("card_glass", v).apply()
    }

    /** 浅色轻玻璃透明度滑杆（2026-10-02）：值越大越透，填充 alpha = 1 - 本值 */
    fun updateCardTransparency(v: Float) {
        cardTransparency = v.coerceIn(0f, 0.85f)
        prefs.edit().putFloat("card_transparency", cardTransparency).apply()
    }

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 背景模糊滑杆：去抖 160ms 后重生成对应半径的模糊底图 */
    fun updateBgBlur(v: Float) {
        bgBlur = v.coerceIn(0f, 1f)
        prefs.edit().putFloat("bg_blur_v", bgBlur).apply()
        if (bgBlur <= 0.01f) { blurredBgImage = null; return }
        val strength = bgBlur
        uiHandler.postDelayed({ regenerateBgBlurred(strength) }, 160)
    }

    fun updateBlur(v: Boolean) {
        blur = v
        prefs.edit().putBoolean("bg_blur", v).apply()
    }

    fun updateKeepAmbient(v: Boolean) {
        keepAmbient = v
        prefs.edit().putBoolean("bg_keep_ambient", v).apply()
    }

    /** 恢复默认纯色背景 */
    fun clear() {
        file.delete()
        prefs.edit().putBoolean("bg_on", false).apply()
        ready = false
        version++
        blurredImage = null
        blurredBgImage = null
        seedColor = null      // 无图即无种子，主题回落预设种子色
    }

    private val genExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * 提取背景图主色（2026-10-01）：后台线程，降采样解码后走主桶均值统计。
     * 466 屏上用 `inSampleSize = 8` —— 一张 4000px 的图只需解到 500px 级别，
     * 4096 个量化桶的统计结果完全够稳，且不会在选图/冷启动瞬间造成解码卡顿。
     */
    private fun extractSeed() {
        if (!ready) {
            seedColor = null
            return
        }
        val f = file
        genExecutor.execute {
            runCatching {
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
                val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
                    ?: return@execute
                // fallback 只在空图时触发；2026-10-03 起与柔化后的默认种子色（AppearancePrefs.DEF_ACCENT）一致
                val c = dominantColorOf(bmp, Color(0xFFDA5353))
                bmp.recycle()
                uiHandler.post { seedColor = c }
            }
        }
    }

    /**
     * 卡片取样底图（★ 2026-10-16 改用 Blurry 真高斯，见 [GlassBlur] 的文件头）：
     * 半径取 `ScriptIntrinsicBlur` 上限 25（旧版是半径 26 的三轮盒式近似 —— 强度等价，
     * 但盒式会把相邻色块和成一片平色，观感是「蒙了层灰」；真高斯的长尾让色块边界平滑
     * 过渡但仍看得出，才是「玻璃后面透出彩色」）。
     *
     * 仍然是「整屏一张底、卡片按自身屏幕位置取样」的老架构 —— 只是把**模糊算法**换成库。
     * 不逐卡片现场糊的理由：本工程的玻璃件（设置行 / 列表行 / 播放控件…）同屏可能十几个，
     * 每个都跑一次 RenderScript 就是十几毫秒 × N；而底图只在换图/换主题时变，
     * 一次算好全站共用，观感完全一致、代价低一个数量级。
     */
    fun regenerateBlurred() = generateInto(
        radius = GlassBlur.MAX_RADIUS,
        engine = BlurEngine.BLURRY,
        cacheName = CARD_BLUR_CACHE,
    ) { ib -> blurredImage = ib }

    /** 背景模糊底图：二次曲线 2..14 —— 滑杆前半段几乎无感，轻滑不再剧变 */
    fun regenerateBgBlurred(strength: Float) {
        val radius = (2 + strength * strength * 12).toInt().coerceIn(2, 14)
        generateInto(radius = radius, cacheName = BG_BLUR_CACHE) { ib -> blurredBgImage = ib }
    }

    /** 底图的模糊算法：卡片玻璃走 Blurry 真高斯，背景层滑杆维持原盒式近似（未在本次改动范围） */
    private enum class BlurEngine { BOX, BLURRY }

    private fun generateInto(
        radius: Int,
        cacheName: String,
        passes: Int = 2,
        engine: BlurEngine = BlurEngine.BOX,
        assign: (androidx.compose.ui.graphics.ImageBitmap) -> Unit,
    ) {
        genExecutor.execute {
            runCatching {
                val src = decodeWallpaper() ?: return@execute
                val side = minOf(src.width, src.height)
                val square = android.graphics.Bitmap.createBitmap(
                    src, (src.width - side) / 2, (src.height - side) / 2, side, side)
                val scaled = android.graphics.Bitmap.createScaledBitmap(square, 466, 466, true)
                if (scaled != square) square.recycle()
                // ★ 2026-10-16：卡片底图走 Blurry（ScriptIntrinsicBlur 真高斯）；
                //   库返回 null（RenderScript 与 stack 兜底都失败）时回落盒式近似，不空转。
                val out = when (engine) {
                    BlurEngine.BLURRY ->
                        GlassBlur.blur(ctx0, scaled, radius) ?: boxBlurred(scaled, radius, passes)
                    BlurEngine.BOX -> boxBlurred(scaled, radius, passes)
                } ?: return@execute
                java.io.FileOutputStream(java.io.File(ctx0.cacheDir, cacheName)).use {
                    out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it)
                }
                val ib = out.asImageBitmap()
                uiHandler.post { assign(ib) }
                src.recycle()
            }
        }
    }

    /**
     * 解码壁纸，**按需降采样**（★ 2026-10-16 新增）。
     *
     * 旧实现是 `BitmapFactory.decodeFile(path)` 全分辨率解码：一张 4000px 的手机壁纸在手表上
     * 就是 ~64MB 的 ARGB_8888 分配 + 一次全图解码，而我们最终只要一张 466 方图 ——
     * 这笔开销在冷启动窗口里（缓存未命中时）尤其致命，用户口径「现在冷启动好卡」。
     * 现在先 `inJustDecodeBounds` 读尺寸、再选 2 的幂 `inSampleSize` 解到
     * 长边 ≥ [WALLPAPER_DECODE_TARGET]（留 2x 余量给裁方 + 缩放），解码量降到 ~1/16。
     */
    private fun decodeWallpaper(): android.graphics.Bitmap? {
        val path = file.absolutePath
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= WALLPAPER_DECODE_TARGET) {
            sample *= 2
        }
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        return android.graphics.BitmapFactory.decodeFile(path, opts)
    }

    /** 盒式近似（三轮默认）：原卡片/背景底图的算法，作为 Blurry 不可用时的兜底保留 */
    private fun boxBlurred(
        src: android.graphics.Bitmap,
        radius: Int,
        passes: Int,
    ): android.graphics.Bitmap? {
        val w = src.width; val h = src.height
        if (w <= 0 || h <= 0) return null
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        boxBlur(px, w, h, radius = radius, passes = passes)
        val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** 分离式盒式模糊（滑窗累加，纯 Kotlin，API 无关） */
    private fun boxBlur(px: IntArray, w: Int, h: Int, radius: Int, passes: Int) {
        val tmp = IntArray(px.size)
        repeat(passes) {
            blurDir(px, tmp, w, h, radius, true)
            blurDir(tmp, px, w, h, radius, false)
        }
    }

    private fun blurDir(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val other = if (horizontal) h else w
        val win = r * 2 + 1
        for (o in 0 until other) {
            var aS = 0; var rS = 0; var gS = 0; var bS = 0
            for (i in -r..r) {
                val c = i.coerceIn(0, len - 1)
                val p = if (horizontal) src[o * w + c] else src[c * w + o]
                aS += p ushr 24; rS += (p shr 16) and 0xFF; gS += (p shr 8) and 0xFF; bS += p and 0xFF
            }
            for (x in 0 until len) {
                dst[if (horizontal) o * w + x else x * w + o] =
                    ((aS / win) shl 24) or ((rS / win) shl 16) or ((gS / win) shl 8) or (bS / win)
                val outP = if (horizontal) src[o * w + (x - r).coerceIn(0, len - 1)]
                           else src[(x - r).coerceIn(0, len - 1) * w + o]
                val inP = if (horizontal) src[o * w + (x + r + 1).coerceIn(0, len - 1)]
                          else src[(x + r + 1).coerceIn(0, len - 1) * w + o]
                aS += (inP ushr 24) - (outP ushr 24)
                rS += ((inP shr 16) and 0xFF) - ((outP shr 16) and 0xFF)
                gS += ((inP shr 8) and 0xFF) - ((outP shr 8) and 0xFF)
                bS += (inP and 0xFF) - (outP and 0xFF)
            }
        }
    }
}
