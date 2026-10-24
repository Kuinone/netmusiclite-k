package com.netmusiclite.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.pow

/**
 * 单张底图 + 两把亮度。
 *
 * [luminance] = **成图后**（打光 + 曝光归一 + 饱和增益）的平均相对亮度，
 * 留给上层做**兜底**安全压暗层用：正常封面在 [CoverBackdrop.build] 里已经做了
 * 曝光归一（平均亮度落在主题目标上），这个值只在「极端封面」（纯黑/纯白图，
 * 曝光系数被夹住、够不到目标）时才非零 —— 见 NcmBackground 的 backdropScrim。
 *
 * [rawLuminance] = **封面模糊后、一切画面处理之前**的平均相对亮度（2026-10-07 新增）——
 * 「外观模式 = 封面模糊」的全局自动明暗判据（见 [CoverBackdrop.autoLight]）。
 * 量它（而不是 [luminance]）的原因：曝光归一的目标亮度本身就随明暗主题走，
 * 用成图亮度反推封面明暗会自证循环；与主题无关的原始模糊亮度才是「封面偏亮/偏暗」的定义。
 */
class CoverBackdropLayer(
    val image: ImageBitmap,
    val luminance: Float,
    val rawLuminance: Float,
    /**
     * ★ 2026-10-07：本张成图实际按哪套明暗打光（= [CoverBackdrop.build] 里解析出的
     * lightResolved）。上层（NcmBackground 的兜底压暗层 backdropScrim）必须按它算白/黑纱：
     * 「封面模糊明暗对调」时成图明暗与全局主题相反，读全局主题会把纱方向弄反
     * （深底上蒙黑纱 = 越压越黑）。
     */
    val light: Boolean,
    /** Glass buttons share the classic dark theme's Blurry Gaussian sampling bitmap. */
    val glassImage: ImageBitmap = image,
)

/**
 * 「封面模糊」播放页风格的底图生成（2026-10-06 用户口径 + 《歌词动画分析》报告）。
 *
 * 报告实测（Apple Music，1206 宽屏）：
 *  · 封面 scale-to-fill → 等效 σ≈300px（¼ 屏宽）大模糊 → 顶部 ×0.8 压暗 /
 *    底部 ×1.1 提亮（与封面自身明暗相反，属 UI 打光）。见报告 §六、§八。
 *
 * ★ 2026-10-07 三稿（用户口径「切换到歌词页面背后的模糊的形状会变，让他不变」）
 *  —— 废除 2026-10-06 的「歌词页 = 封面调色板重构图」（报告 §六 的方案）：
 *    播放页与歌词页改**共用同一张模糊封面**（[CoverBackdropLayer]）。旧方案在歌词页
 *    把封面重新构图为「横向色带 + 径向强调块」，与播放页的模糊封面**形状不同**，
 *    从播放页切歌词页底图会明显换一副构图；现在两页底图逐像素相同，页面切换背景零变化。
 *    歌词页的「高斯模糊材质」观感与播放页完全一致，可读性由 lyricInk 的标定
 *    （LIGHT_TARGET 0.50 / DARK_TARGET 0.14 对应灰）与 backdropScrim 兜底共同保证。
 *    （调色板一边的 V 钳位/横向色带生成、轻模糊取色全链路一并删除，每首歌省一次
 *    高斯 + 4 次主色提取。）
 *
 * ★ 2026-10-06 二稿（用户口径「封面模糊用高斯模糊，你这个不够通透」）——三处改动：
 *  ① 模糊改**真高斯**（[ImageBlur.gaussian]，σ = 0.17×边长）：旧版是三轮盒式近似 + 半径 ¼ 边长，
 *     低频被压成一片平色，整屏就是一块糊色；
 *  ② 缩放改**面积平均**（旧版最近邻，192→96 等于丢一半像素，边缘噪点被模糊成脏色）；
 *  ③ 主题适配从「蒙白/蒙黑」改成**曝光 + 饱和增益**（[grade]）：旧版按亮度差糊一层
 *     白（浅色）/黑（深色），饱和度被直接稀释 —— 通透感的来源就是这一步，
 *     曝光是**整体缩放**（色相、饱和度原样保留），暗封面也能透出彩色而不是灰。
 *     压暗/提亮层只剩「极端封面」兜底（正常封面算出的 alpha ≈ 0）。
 *
 * 实现要点（本机 OWW261 / API 30 没有 RenderEffect，Modifier.blur 是空操作）：
 *  · 全部走**预模糊位图**：缩到 96px 再真高斯，**不做 466px 的大图**；
 *
 * 生成时机：由 NcmBackground 在切歌 / 切主题时调用 [pull]。**两种场合需要它**：
 *  ① 播放页面风格 = 封面模糊（要拿成图当播放器组底图）；
 *  ② 外观模式 = 封面模糊（全局自动明暗，只要 [CoverBackdropLayer.rawLuminance]）。
 * 两处都不满足时完全不付这份解码/模糊成本；结果缓存在 [current]，同一首歌不重算。
 */
object CoverBackdrop {

    /** 当前底图；null = 无歌 / 还没算出来 / 空 model（空串=没有封面，直接清空不挂旧图） */
    var current by mutableStateOf<CoverBackdropLayer?>(null)
        private set

    /**
     * ★ 2026-10-07 新增：当前封面「偏亮？」—— 外观模式 = 封面模糊时驱动全 App 明暗。
     *
     * 判据 = [CoverBackdropLayer.rawLuminance] ≥ [AUTO_LIGHT_THRESHOLD]：
     * 与明暗主题、曝光归一全部解耦（量的是封面自己的亮度），所以定完明暗再按明暗出成图，
     * 不会来回震荡。null（无歌/无封面/还没算出来）按亮处理 —— 与默认主题（浅色）一致。
     *
     * 用 derivedStateOf 包住：只有整个布尔**翻转**时才通知读者。换到另一首同样亮的
     * 封面（rawLuminance 变了、布尔没变）不会引起全 App 重组 —— 否则每次切歌都要
     * 把整棵树按「换主题」的代价重组一遍。
     */
    private val autoLightState = derivedStateOf {
        val layer = current
        layer == null || layer.rawLuminance >= AUTO_LIGHT_THRESHOLD
    }

    /** 见 [autoLightState]。AppearancePrefs.isLight 在 ThemeMode.COVER_BLUR 分支读它。 */
    val autoLight: Boolean
        get() = autoLightState.value

    /**
     * [current] 对应的取图 key（= songCoverModel 的返回值）。
     * 上层（NcmBackground 的底图层）用它判定「现在这张底是不是当前这首歌的」：
     * 切歌后新底图还没算好时旧底可以宽限展示一会儿，但宽限期一过就要收回，
     * 绝不让上一首歌的模糊底永久挂在新歌上（2026-10-07 用户 bug③的兜底）。
     */
    var currentKey by mutableStateOf<Any?>(null)
        private set

    /** pull 序号：只让**最新一次** pull 的结果落库（被取消/乱序的旧协程不覆盖新图） */
    private var seq = 0

    /**
     * 取当前播放歌的封面并生成底图。[model] 与封面同源（见 songCoverModel）：
     * 设备本地 Uri → 已下载落盘封面 → 网络 URL，所以断网时已下载的歌照样有底图。
     * [light] = 浅色主题（曝光目标不同，必须跟着主题重建）；
     * ★ 2026-10-07「封面模糊明暗对调」开启时按 [AppearancePrefs.coverBlurToneFlip] 翻到反面。
     */
    suspend fun pull(ctx: Context, model: Any?, light: Boolean) {
        // 空串也当「没有封面」：旧代码只判 null —— 空串会发一个必然失败的请求，
        // 而 LaunchedEffect 的 key 对两首同样缺封面的歌都是 ""（不重跑），
        // 底图从此卡在更早那一首上。这种情况直接清空，上层回落到环境色底。
        if (model == null || (model is String && model.isBlank())) {
            current = null
            currentKey = null
            return
        }
        val token = ++seq
        val app = ctx.applicationContext
        val images = withContext(Dispatchers.IO) {
            runCatching {
                // size(192) 只解出小图；allowHardware(false) 才能 getPixels（硬件位图读不了像素）
                val req = ImageRequest.Builder(app)
                    .data(model)
                    .size(SRC_HINT)
                    .allowHardware(false)
                    .build()
                val src = (app.imageLoader.execute(req).drawable as? BitmapDrawable)?.bitmap
                    ?: return@runCatching null
                // build 同时产出两张：背景成图（96px 重糊）+ 卡片取样底（同源同 grade、少糊一档，
                // 见 buildGlassSample —— 用户口径「卡片要以封面模糊后的背景为基准」）。
                build(src, light)
            }.getOrNull()
        }
        // 失败保留上一首（切歌不闪底，由上层宽限兜底决定何时收回）；LaunchedEffect 取消时
        // withContext 抛 CancellationException，这一行不会执行 —— 旧协程不会把新歌的底图覆盖掉。
        // token 双保险：即使旧协程跑完，只要期间又发起过新的 pull，它的结果一律作废。
        if (images != null && token == seq) {
            // ★ 2026-10-07：同图同参数的重复 pull 不换实例 —— 外观模式 = 封面模糊时，
            //   底图落库会让 autoLight 翻转 → isLightTheme 变化触发上层 LaunchedEffect
            //   重跑一次；而 build 已按 rawLum 自解明暗，这次重建的成图与库里那张逐像素
            //   一致。照旧换新实例 = 换掉 Crossfade 的目标态，白白多跑一段「两层同绘」的
            //   420ms 交叉淡化（图一模一样，纯浪费）。亮度两把尺子全等 = 输入与处理参数
            //   全等 = 成图一致（grade 是确定性的）。
            val prev = current
            if (prev != null && currentKey == model &&
                prev.rawLuminance == images.rawLuminance && prev.luminance == images.luminance
            ) {
                return
            }
            current = images
            currentKey = model
        }
    }

    /** 解码提示尺寸：够做 96px 方块裁切 + 面积平均取色即可（重模糊前的一切细节都是噪声） */
    private const val SRC_HINT = 192

    // ── 卡片玻璃取样底（★ 2026-10-18）────────────────────────────────────────
    /**
     * 卡片取样底的模糊强度（σ / 边长）——★ 2026-10-18 用户口径「卡片要以封面模糊后的背景
     * 为基准来糊」。
     *
     * 壁纸路径的取样底是 `BackgroundStore.regenerateBlurred`：466 方图 + 半径 25，
     * 即 σ ≈ 25 / 466 = 5.4% 屏宽 —— 色块边界还看得见，卡片才像「玻璃后面透出东西」。
     * 卡片取样底用同一比例，只是源图是封面的 192px 裁方（σ ≈ 10.3）。
     *
     * 为什么不能直接拿播放页背景成图（96px、σ=0.17×边长≈16）当取样底：
     *  · 它已经被糊到 17% 边长，且缩到 96px —— 卡片宽 400px 取样时，
     *    横向几乎覆盖整张图（86%）⇒ 取出来的就是「一条平均色」，等于纯色块；
     *  · 它经过了曝光归一 + 纵向打光，是"给白字压底的成图"，不是"可再取样的底"。
     * 现在这张是**同一张封面、同一套 grade、同一套打光**，只把糊度放轻一档、分辨率留高 ——
     * 卡片取到的颜色与身后背景同源，同时保得住色块走向。
     */
    private const val GLASS_SIGMA_FRAC = 25f / 466f
    /** 播放页模糊底图边长（放大到全屏：重模糊后是平滑色场，小图无差） */
    private const val BLUR_SIDE = 96
    /**
     * 播放页模糊强度（σ / 边长 = 0.17）。
     * 报告的实测值是 ¼ 屏宽（0.25），但那是**手机屏**上的比例，且报告本身也提示
     * 「整行实时高斯开销大，用等效管线」；本机 466px 圆表上取 0.17 —— 保留得下
     * 封面的色块走向（通透），又不至于让底图抢到 UI 前面。
     */
    private const val BLUR_SIGMA_FRAC = 0.17f
    /** 顶部压暗 / 底部提亮（报告 §六：×0.8 → ×1.1 的纵向线性打光） */
    private const val LIGHT_TOP = 0.8f
    private const val LIGHT_BOTTOM = 1.1f
    /**
     * 饱和增益：模糊必然把相邻色块混灰（互补色相加 = 灰），这里按「围绕灰度轴外推」
     * 补回来 —— 直接决定底图是「彩色玻璃」还是「灰玻璃」，也就是用户说的通透。
     */
    private const val SAT_BOOST = 1.28f

    /**
     * 底图平均亮度目标（深色 / 浅色主题）——与 Theme.kt 的对比度口径同一把尺子：
     *  · 深色：文字 TextPrimary(#F5F5F7, ≈0.91) 要 4.5:1 ⇒ 底 ≤ 0.163，取 0.14 留余量；
     *  · 浅色：文字 TextPrimary(#17171A, ≈0.008) 要 7:1 ⇒ 底 ≥ 0.36；取 0.50 留出
     *    底图局部起伏的余量（旧值 0.55 太白，把封面色洗没了）。
     * LyricsScreen 的 lyricInk 就是按这两个亮度对应的灰（0xFF696969 / 0xFFBABABA）标定的。
     */
    const val DARK_TARGET = 0.14f
    const val LIGHT_TARGET = 0.50f

    /**
     * 自动明暗的翻转阈值（外观模式 = 封面模糊）：封面模糊后平均相对亮度 ≥ 它 → 浅色。
     * 取 0.22 ≈ sRGB 0.50（中灰）—— 与 DARK_TARGET / LIGHT_TARGET 同一把 WCAG 尺子，
     * 观感口径就是「封面亮过中灰 → 浅色观感，暗过中灰 → 深色观感」。
     * 想调敏感度只需要动这一个数（判据、缺省、文案都不依赖它）。
     */
    private const val AUTO_LIGHT_THRESHOLD = 0.22f

    private fun build(src: Bitmap, light: Boolean): CoverBackdropLayer? {
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return null
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        // ① scale-to-fill 方形中心裁切 → 96：**面积平均**（不是最近邻）——
        //    最近邻是丢像素，边缘/纹理区会出噪点，噪点再被模糊混淆成脏色
        val side = minOf(w, h)
        val x0 = (w - side) / 2
        val y0 = (h - side) / 2
        val small = IntArray(BLUR_SIDE * BLUR_SIDE)
        for (y in 0 until BLUR_SIDE) {
            val sy0 = y0 + y * side / BLUR_SIDE
            val sy1 = (y0 + (y + 1) * side / BLUR_SIDE).coerceAtLeast(sy0 + 1)
            for (x in 0 until BLUR_SIDE) {
                val sx0 = x0 + x * side / BLUR_SIDE
                val sx1 = (x0 + (x + 1) * side / BLUR_SIDE).coerceAtLeast(sx0 + 1)
                var a = 0
                var rr = 0
                var gg = 0
                var bb = 0
                var cnt = 0
                for (yy in sy0 until sy1) {
                    val row = yy * w
                    for (xx in sx0 until sx1) {
                        val p = px[row + xx]
                        a += p ushr 24
                        rr += (p shr 16) and 0xFF
                        gg += (p shr 8) and 0xFF
                        bb += p and 0xFF
                        cnt++
                    }
                }
                small[y * BLUR_SIDE + x] =
                    ((a / cnt) shl 24) or ((rr / cnt) shl 16) or ((gg / cnt) shl 8) or (bb / cnt)
            }
        }
        val base = Bitmap.createBitmap(BLUR_SIDE, BLUR_SIDE, Bitmap.Config.ARGB_8888)
        base.setPixels(small, 0, BLUR_SIDE, 0, 0, BLUR_SIDE, BLUR_SIDE)
        // ② 真高斯重模糊（播放页 / 歌词页共用同一张底）
        val blurred = ImageBlur.gaussian(base, BLUR_SIDE * BLUR_SIGMA_FRAC)
        base.recycle()
        // ②.5 ★ 2026-10-07：自动明暗判据在这里量 —— 打光 / 曝光归一**之前**的平均亮度。
        //   那两步是本应用的画面处理（把底拉到主题目标亮度），量成图等于用「我们想让它
        //   多亮」反推「封面多亮」，会自证循环；封面本身（模糊后）的亮度才是判据的定义。
        val rawLum = meanLuminance(blurred)
        // ③ 打光 + 曝光归一 + 饱和增益（不蒙白/蒙黑，颜色全留住）
        // ★ 2026-10-07 用户 bug「进入软件完成取色后突然闪出」的根因之一：
        //   外观模式 = 封面模糊时，明暗本来就由这张底的 rawLum 决定（见 autoLight）。
        //   但首轮 pull 发生在 current 还是 null 的时刻 —— autoLight 默认亮，传进来的
        //   light 也是亮。照它出图，会先产出一张按「亮」标定的底；落库后 autoLight 按
        //   真实亮度翻转 → 主题变 → LaunchedEffect 以 light=false 重建出「暗」标定的底。
        //   两张底先后落库互相替换，观感就是「闪一下才对」。这里直接按 rawLum 自解明暗：
        //   第一张就是终稿；后续主题翻转触发的重建也产出同一张（幂等，pull 里同参数的
        //   重复结果直接跳过，不再换实例触发交叉淡化）。
        val lightResolved = when {
            AppearancePrefs.isM3e -> false
            AppearancePrefs.mode == ThemeMode.COVER_BLUR -> rawLum >= AUTO_LIGHT_THRESHOLD
            // ★ 2026-10-07「封面模糊明暗对调」：播放器组整体走另一套明暗时，底图打光跟着翻
            //   （开关只在「不确定明暗的方向」时隐藏，见 AppearancePrefs.coverBlurToneFlip）
            AppearancePrefs.coverBlurToneFlip -> !light
            else -> light
        }
        val graded = grade(blurred, lightResolved, lighting = true)
        blurred.recycle()
        // ④ 卡片取样底（★ 2026-10-18）—— 见 [buildGlassSample] / [GLASS_SIGMA_FRAC]。
        val glass = buildGlassSample(src) ?: graded.image.asImageBitmap()
        return CoverBackdropLayer(
            graded.image.asImageBitmap(), graded.luminance, rawLum, lightResolved, glass,
        )
    }

    /**
     * 卡片玻璃取样底（★ 2026-10-18 用户口径「卡片要以封面模糊后的背景为基准来糊」）。
     *
     * 与播放页背景成图**同一张封面**，但刻意不做曝光归一：
     *  · 背景成图 [build] 会把平均亮度压到 DARK_TARGET(0.14) —— 那是"给白字压底"的目标，
     *    压完之后整张图只剩 0.1 量级的明暗起伏，色块被压平。卡片若直接取样它，
     *    透出的就是一块均匀暗灰（用户三轮口径里的「假玻璃」）。
     *  · 这张取样底保留封面的原始亮度：卡片再叠 `frostedGlass` 的 shade（黑 30%）+
     *    玻璃填充（白 13%），最终亮度仍低于原封面 —— 观感是"玻璃后面透出封面色块"，
     *    与壁纸路径（取样未压暗的 `BackgroundStore.blurredImage`）是同一套逻辑。
     *
     * 糊度按 `25 / 466`（壁纸取样底的 σ 比例 = 5.4% 屏宽）而不是 0.17 ——
     * 色块边界留得住，卡片才有"看得到东西"的玻璃感。
     * 分辨率保留 src 的 192px：缩到 96 的话，卡片横向取样要吃掉八成图宽，
     * 取出来必然是一条平均色。
     *
     * 返回 null（源图退化）时调用方回落到背景成图。
     */
    private fun buildGlassSample(src: Bitmap): ImageBitmap? {
        val side = minOf(src.width, src.height)
        if (side <= 1) return null
        val square = if (src.width == side && src.height == side) src else
            Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val blurred = ImageBlur.gaussian(square, side * GLASS_SIGMA_FRAC)
        // square 是我们新建的临时图，blurred 之后就没用了；blurred 本身要留给 ImageBitmap 包装
        if (square !== src && blurred !== square) square.recycle()
        return blurred.asImageBitmap()
    }

    /** 位图平均相对亮度（WCAG 口径，与 [grade] 里的 lumSum 同一算法）；给自动明暗判据用 */
    private fun meanLuminance(src: Bitmap): Float {
        val n = src.width * src.height
        if (n <= 0) return 1f
        val px = IntArray(n)
        src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
        var sum = 0.0
        for (p in px) {
            sum += luminanceOf(
                ((p shr 16) and 0xFF) / 255f,
                ((p shr 8) and 0xFF) / 255f,
                (p and 0xFF) / 255f,
            )
        }
        return (sum / n).toFloat()
    }

    /** 生成结果 + 平均亮度（给兜底压暗层判「极端封面」用） */
    private class Graded(val image: Bitmap, val luminance: Float)

    /**
     * 主题适配（2026-10-06 二稿）：**曝光归一 + 饱和增益**，替代旧版的「蒙白 / 蒙黑」。
     *
     * 旧版把整层向白（浅色）或黑（深色）混合以达到目标亮度 —— 亮度是达标了，但混合
     * 等于往颜色里掺灰，封面的彩度被稀释掉，观感就是「蒙了一层灰」（用户口径「不够通透」）。
     * 曝光是**乘性**的：色相不变、饱和度（相对值）不变，暗封面提亮后仍是彩色玻璃。
     * 系数按「目标 / 当前平均亮度」在 sRGB 空间求，并夹在 [0.45, 2.2] 防止
     * 极端封面（纯黑/纯白图）被拉爆 —— 夹住后够不到目标的那部分，由上层兜底压暗层补。
     *
     * @param lighting 播放页底图 true（顶部 ×0.8 → 底部 ×1.1 的纵向打光，报告 §六）
     */
    private fun grade(src: Bitmap, light: Boolean, lighting: Boolean): Graded {
        val w = src.width
        val h = src.height
        val n = w * h
        val px = IntArray(n)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val rp = FloatArray(n)
        val gp = FloatArray(n)
        val bp = FloatArray(n)
        var lumSum = 0.0
        for (y in 0 until h) {
            val f = if (lighting) LIGHT_TOP + (LIGHT_BOTTOM - LIGHT_TOP) * y / (h - 1) else 1f
            for (x in 0 until w) {
                val i = y * w + x
                val p = px[i]
                val r = (((p shr 16) and 0xFF) * f / 255f).coerceIn(0f, 1f)
                val g = (((p shr 8) and 0xFF) * f / 255f).coerceIn(0f, 1f)
                val b = ((p and 0xFF) * f / 255f).coerceIn(0f, 1f)
                rp[i] = r
                gp[i] = g
                bp[i] = b
                lumSum += luminanceOf(r, g, b)
            }
        }
        val target = if (light) LIGHT_TARGET else DARK_TARGET
        val mean = (lumSum / n).toFloat()
        // 上界给到 3.0：深色封面（平均亮度 0.05 上下）也能被拉到目标，而不是被夹住后
        // 再吃一层兜底白纱（白纱一上来颜色就没了，正是旧版不通透的原因）
        val k = if (mean <= 0.0005f) 1f else {
            (target.pow(1f / 2.2f) / mean.pow(1f / 2.2f)).coerceIn(0.45f, 3.0f)
        }
        var sR = 0L
        var sG = 0L
        var sB = 0L
        val out = IntArray(n)
        for (i in 0 until n) {
            val r0 = (rp[i] * k).coerceIn(0f, 1f)
            val g0 = (gp[i] * k).coerceIn(0f, 1f)
            val b0 = (bp[i] * k).coerceIn(0f, 1f)
            // 围绕灰度轴外推 = 加饱和；luma 权重用 sRGB 常用系数（与 HSL/HSV 无关，
            // 这里只要「亮度感」一致）
            val gray = 0.299f * r0 + 0.587f * g0 + 0.114f * b0
            val r = (gray + (r0 - gray) * SAT_BOOST).coerceIn(0f, 1f)
            val g = (gray + (g0 - gray) * SAT_BOOST).coerceIn(0f, 1f)
            val b = (gray + (b0 - gray) * SAT_BOOST).coerceIn(0f, 1f)
            val rI = (r * 255f).toInt()
            val gI = (g * 255f).toInt()
            val bI = (b * 255f).toInt()
            sR += rI
            sG += gI
            sB += bI
            out[i] = (0xFF shl 24) or (rI shl 16) or (gI shl 8) or bI
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return Graded(
            bmp,
            luminanceOf(sR / n / 255f, sG / n / 255f, sB / n / 255f),
        )
    }

    /** WCAG 相对亮度（与 Compose 的 Color.luminance 同式）：曝光系数按它算 */
    private fun luminanceOf(r: Float, g: Float, b: Float): Float {
        fun ch(v: Float): Float =
            if (v <= 0.03928f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
        return 0.2126f * ch(r) + 0.7152f * ch(g) + 0.0722f * ch(b)
    }
}
