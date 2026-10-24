package com.netmusiclite.ui.components

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.netmusiclite.data.AppearancePrefs
import com.netmusiclite.data.M3eBackgroundStyle
import com.netmusiclite.data.BackgroundStore
import com.netmusiclite.data.CoverBackdrop
import com.netmusiclite.data.CoverBackdropLayer
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.ThemeMode
import com.netmusiclite.data.dominantColorOf
import com.netmusiclite.ui.theme.Bg
import com.netmusiclite.ui.theme.isLightTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.pow

/**
 * 全局底层背景：
 * - 用户设置了背景图 → 绘制该图（背景模糊滑杆控制）+ 压暗层；毛玻璃模糊发生在卡片内部
 *   （各玻璃件用 frostedGlass() 从 BackgroundStore.blurredImage 按屏幕位置取样）。
 * - 无背景图 → 环境色模式（2026-09-13 全局生效）：底色取当前播放歌曲封面主色并向黑大幅
 *   压暗（与背景图模式同为全局），卡片同步纯黑（frostedGlass 无背景分支）。
 *   取色永不失败：主桶 → 有像素均值 → 整图均值三级兜底；封面加载失败保留上一首颜色。
 */
/**
 * 歌曲环境色（当前播放封面主色，已向黑压暗 75%）：
 * - 无背景图：NcmBackground 全局使用；
 * - 有背景图 + 「播放页/歌词页保留取色」开关开：播放页/歌词页覆盖使用。
 * 取色永不失败：主桶 → 有像素均值 → 整图均值三级兜底；封面加载失败保留上一首颜色。
 */
object SongAmbient {
    /**
     * 环境色基准色：取色结果向它插值。
     * 深色主题 → 近黑（原有行为）；浅色主题 → 近白。
     * ★ 2026-10-01：浅色主题下若仍向黑压暗，整个页面会变成深色底 + 深色文字，
     *   与卡片/文字的浅色板直接冲突，所以插值目标必须跟随主题翻转。
     */
    private fun base(): Color =
        if (com.netmusiclite.ui.theme.isLightTheme) Color(0xFFEFEFF3) else Color(0xFF0A0A0C)

    /**
     * ★ 2026-10-07「封面模糊明暗对调」：按**指定明暗**求环境色（纯函数，不依赖全局主题）。
     * 播放器组对调开启时，全局背景层的底要按翻转后的明暗插值 —— 底图未就绪的窗口
     * （冷启动 / 切换瞬间）里，页面墨色已经是翻转后的那套，底必须同向，否则深字压暗底。
     * 全局主题那支 [color] 保持原样（外围页共用，不能跟着播放器组翻）。
     */
    fun colorFor(light: Boolean): Color {
        val b = if (light) Color(0xFFEFEFF3) else Color(0xFF0A0A0C)
        val c = rawColor ?: return b
        return lerp(c, b, if (light) 0.86f else 0.75f)
    }

    var color by mutableStateOf(Color(0xFF0A0A0C))
        private set

    /**
     * ★ 2026-10-02 新增：**原始**封面主色（未向主题基准色插值的那一份）。
     * 「自动取色」在未设背景图时的色源（见 Theme.kt 的 EffectiveSeed）——
     * 用户口径「自动取色打开以后无论是否设置壁纸都生效」：背景在无图时本就跟随
     * 封面环境色（color 字段），强调色补上同一色源后整条链路无壁纸也生效。
     * 取色失败保留上一首的值（与 color 同策略）；初始 null = 尚未取到过，回落预设种子色。
     * 2026-10-03：取色出口已做 S≤50% 限饱和（见 data/ColorExtract.kt），此处直通不再处理。
     */
    var rawColor by mutableStateOf<Color?>(null)
        private set

    suspend fun pull(model: Any?) {
        val b = base()
        if (model == null) {
            color = b
            return
        }
        val c = withContext(Dispatchers.IO) {
            runCatching {
                val req = ImageRequest.Builder(NcmContextHolder.app).data(model).size(64).allowHardware(false).build()
                (NcmContextHolder.app.imageLoader.execute(req).drawable as? BitmapDrawable)?.bitmap
                    ?.let { dominantColorOf(it, Bg) }
            }.getOrNull()
        }
        // 封面网络/解码失败：保留上一首颜色（不退默认），快速切歌不再“失效”
        // 浅色主题插值系数加大到 0.86：浅底上需要更弱的彩度才不刺眼
        if (c != null) {
            rawColor = c
            color = lerp(c, b, if (com.netmusiclite.ui.theme.isLightTheme) 0.86f else 0.75f)
        }
    }
}

/** 应用上下文持有者：供无 Compose 上下文的取色协程使用 */
object NcmContextHolder {
    @Volatile
    lateinit var app: android.content.Context
}

/**
 * 播放页/歌词页表面色（2026-09-13；2026-10-06 扩为三态）：
 * 播放页面风格 = 取色 → 歌曲环境色（原行为）；
 * 自定义 → 透明（透出全局背景层里的自定义壁纸）；
 * 封面模糊 → 透明（底由全局背景层的封面模糊层画，见 NcmBackground）——
 * 页面根透明后，转场缝合、手势拖拽半透明、滑出淡出露出的都是同一张封面模糊底，
 * 不会出现「页面自己一层底、全局又一层底」的两套皮。
 */
@Composable
fun ambientSurfaceColor(): Color {
    // ★ 2026-10-07 用户 bug「封面模糊主题下播放页下滑切评论页，过渡动画中播放页变黑屏」：
    //   评论页不在 AMBIENT_SURFACE_ROUTES 里，导航一开始路由就切换 ⇒ active 立刻翻 false。
    //   旧实现 !active 分支把页面自身底无脑翻成不透明环境色：封面模糊风格下页面本是透明的
    //   （底由全局背景层画），这一翻 = 滑出中的播放页整页渐变成黑（深色主题），就是黑屏来源。
    //   修复：透明系风格（封面模糊 / 自定义透壁纸）的底色与「是否还在播放器组」无关，恒透明 ——
    //   离场途中透出的仍是同一张全局底（模糊封面 / 壁纸），与右滑跟手半透明、转场缝隙的
    //   既有口径一致。取色风格原两个分支同值（环境色），合并后行为不变。
    val target = when {
        AppearancePrefs.isM3e -> Color.Transparent
        AppearancePrefs.coverBlurStyle -> Color.Transparent
        AppearancePrefs.playShowsWallpaper -> Color.Transparent
        else -> SongAmbient.color
    }
    // 220ms：颜色动画每帧触发含背景色的节点重绘，W5 上 450ms 的重绘窗口在切歌瞬间可感拖尾
    return animateColorAsState(target, tween(220), label = "ambientSurface").value
}

/**
 * 播放器组页面激活标记（2026-09-26，AppNav 按当前栈顶路由驱动）：
 * 置位时全局背景层从自定义图切到环境色。手势返回的拖拽半透明、滑出淡出、转场缝隙
 * 透出的都是与页面自身底色一致的环境色 —— 自定义背景图不再从任何透明瞬间闪现
 * （转场动画只能管住导航期间，管不住松手前的拖拽阶段，必须在这里治本）。
 *
 * 2026-10-07：封面模糊风格下播放页与歌词页**共用同一张模糊封面**（CoverBackdrop 三稿），
 * [route] 不再用于底图分流；现在只有「背景（自定义壁纸）风格下给歌词页盖一层高斯模糊材质」
 * 用得到它（见 [onLyricsPage]）。
 */
object AmbientSurface {
    var active by mutableStateOf(false)

    /** 当前栈顶路由（由 AppNav 同步；只用于底色/底图分流，不参与导航） */
    var route: String? by mutableStateOf(null)

    /** 是否停在歌词页（背景主题下给歌词页加「高斯模糊材质」覆盖层用） */
    val onLyricsPage: Boolean
        get() = route == com.netmusiclite.ui.nav.Routes.LYRICS
}

/**
 * 封面模糊底图的**兜底**安全层（2026-10-06 二稿）：
 * 正常封面在 CoverBackdrop 里已经做过**曝光归一**（把平均亮度乘到主题目标上，
 * 乘性 = 不褪色），所以这里算出来的 alpha 基本恒为 0，等于不加层 —— 这正是「通透」的关键：
 * 旧版在这里按亮度差糊一层白/黑，等于往颜色里掺灰，封面彩度被稀释，就是用户说的
 * 「不够通透」。
 *
 * 只有**极端封面**（近纯黑 / 近纯白图，曝光系数被夹在 [0.45, 2.2] 够不到目标）才会落到
 * 非零：混合式直接解「要多少 alpha 才能把底压/抬到目标」，比旧版的拍脑袋系数准。
 * 目标亮度与代表色（LyricsScreen 的 lyricInk 标定值）定义在 CoverBackdrop 的
 * DARK_TARGET / LIGHT_TARGET —— 一处改、三处同步（评分、兜底、墨色标定）。
 * 指数 1/2.2 = 把「线性亮度」换算到 sRGB 空间（混合是在 sRGB 上做的）。
 */
private fun backdropScrim(luminance: Float, light: Boolean): Color =
    if (light) {
        val target = CoverBackdrop.LIGHT_TARGET
        val cT = target.pow(1f / 2.2f)
        val cL = luminance.coerceIn(0f, 1f).pow(1f / 2.2f)
        val a = ((cT - cL) / (1f - cL)).coerceIn(0f, 0.72f)
        Color.White.copy(alpha = a)
    } else {
        val target = CoverBackdrop.DARK_TARGET
        val a = (1f - (target / luminance.coerceAtLeast(0.0005f)).pow(1f / 2.2f)).coerceIn(0f, 0.9f)
        Color.Black.copy(alpha = a)
    }

/**
 * 切歌宽限期（2026-10-07 用户 bug③兜底）：新歌的底图还没算好（封面解码/下载中）时，
 * 旧底最多再挂这么久；到点立刻收回（回落到下面的环境色底）——宁可短暂素底，
 * 也绝不让上一首歌的模糊底永久留在新歌上（旧实现用 remember 缓存旧图，无时限）。
 * 900ms 覆盖 Coil 取 192px 封面（本地文件/内存缓存/已下载落盘）的通常耗时。
 */
private const val BACKDROP_STALE_GRACE_MS = 900L

/**
 * 封面模糊底图层（2026-10-06；2026-10-07 改「两页共用一张底」+「可选全背景」）：
 * 播放页 / 歌词页 / 更多页画的都是 [CoverBackdrop.current] 这一张模糊封面，
 * 页面之间零形状变化（用户口径「切换到歌词页面背后的模糊的形状会变，让他不变」）；
 * 外观模式 = 封面模糊 且「保留取色」关时，外围页（主页等）也画同一张
 * （见 [coverBlurGlobal]），全 App 一张底、一处形状都没有。
 * 换歌 → Crossfade 交叉淡化；离开封面模糊风格 → 淡出（目标态 null），
 * 露出下面的歌曲环境色底，所以风格切换本身也是一次溶解。
 */
@Composable
private fun CoverBlurBackdropLayer(active: Boolean, coverModel: Any?) {
    // A cached light classic backdrop must not flash behind Material's light text.
    val current = CoverBackdrop.current?.takeUnless { AppearancePrefs.isM3e && it.light }
    val currentKey = CoverBackdrop.currentKey
    // 「库里这张底属不属于当前这首歌」：空 model（无封面）恒不属；否则比对取图 key
    // （切歌后新底没落库前 key 还是旧歌的 ⇒ false，走宽限窗）
    val belongs = current != null && coverModel != null &&
        !(coverModel is String && coverModel.isBlank()) &&
        currentKey == coverModel
    // 最近一张到过库里的底：Crossfade 淡出、「切歌宽限」两处都要它续命
    // （终态由下面的 shown 收敛，这里只做有限的过渡缓冲）
    var last by remember { mutableStateOf(current) }
    LaunchedEffect(current) { if (current != null) last = current }
    // 切歌（model 变）/ 进出播放器组时重置宽限窗；graceUp 置位后不属于本歌的底立即收回
    var graceUp by remember { mutableStateOf(false) }
    LaunchedEffect(active, coverModel) {
        graceUp = false
        if (active) {
            delay(BACKDROP_STALE_GRACE_MS)
            graceUp = true
        }
    }
    val shown = when {
        !active -> null
        belongs -> current
        !graceUp -> last
        else -> null
    }
    // Visibility owns a permanent image layer. Never crossfade the image to null on a route
    // change: retain its bitmap and fade the entire image+scrim together, including an
    // interrupted exit. Image replacement and route visibility have independent timelines.
    val image = shown ?: current ?: last
    val visibility by animateFloatAsState(
        targetValue = if (shown != null) 1f else 0f,
        animationSpec = tween(420, easing = LinearEasing),
        label = "coverBlurVisibility",
    )
    // Keep this node mounted even before a slow cover arrives, so its first appearance
    // also animates. On exit the cached image remains attached at zero opacity.
    Crossfade(
        targetState = image,
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = visibility },
        animationSpec = tween(420),
        label = "coverBlurBackdrop",
    ) { layer ->
        if (layer != null) {
            Image(
                bitmap = layer.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // ★ 2026-10-07：纱的方向按**成图自己**的明暗（layer.light）算，不读全局主题 ——
            //   明暗对调时成图与全局主题相反（见 CoverBackdropLayer.light）。
            val scrim = backdropScrim(layer.luminance, layer.light)
            if (scrim.alpha > 0.01f) {
                Box(Modifier.fillMaxSize().background(scrim))
            }
        }
    }
}

/**
 * 「全背景封面模糊」是否生效（2026-10-07 用户口径「保留取色」开关；2026-10-16 扩了一条）。
 *
 * ① 外观模式 = 封面模糊 且「保留取色」关 → 主页等**外围页**（非播放器组）的背景
 *    也换成模糊封面（与播放页同一张底，用户口径「关闭后主页的背景同为封面模糊后
 *    播放页的背景」）；开关打开则外围页保留原有取色 / 壁纸机制。
 * ② ★ 2026-10-16 用户口径「深色模式默认不选择壁纸、选择封面模糊主题，主页背景底色是黑色的」：
 *    他只把**播放页面风格**选成「封面模糊」（外观模式仍是深色）⇒ [AppearancePrefs.coverBlurOuter]
 *    为 false ⇒ 外围页没有模糊封面底 ⇒ 页面底落到 [SongAmbient.color]（没有封面取色时就是
 *    纯黑 #0A0A0C）。同一时刻**玻璃卡片却取到了模糊封面**（见 [coverBlurSampleImage] 的
 *    [darkCardCoverBlur] 那条），于是出现「卡片是彩色模糊封面、卡片之间却是纯黑」的割裂。
 *    现在补上这一条：**播放页面风格 = 封面模糊 且没设自定义壁纸**时，外围页也铺同一张模糊封面。
 *    · 有壁纸时不生效 —— 外围页该显示壁纸，不去抢它的位（[wallpaperShown] 仍为 true）；
 *    · 「保留取色」开着也不生效 —— 那是用户显式要外围页走取色。
 *
 * ⚠ 只覆盖外围页（!AmbientSurface.active）—— 播放器组三页的打底仍由「播放页面风格」
 *   决定（用户口径「仍听播放页面风格」），所以本判据不会与播放器组的
 *   取色 / 自定义（透壁纸）打架。
 */
private fun coverBlurGlobal(active: Boolean = AmbientSurface.active): Boolean {
    if (AppearancePrefs.isM3e) return !active &&
        !AppearancePrefs.m3eBackgroundPlayerOnly &&
        AppearancePrefs.m3eBackgroundStyle == M3eBackgroundStyle.COVER_BLUR
    if (active) return false
    return AppearancePrefs.coverBlurOuter ||
        (AppearancePrefs.coverBlurStyle && !BackgroundStore.ready)
}

/**
 * 当前上下文里自定义壁纸是否**真的会被画出来**（= 全局背景层壁纸位的 showImage 条件）。
 * ★ 2026-10-07 从 NcmBackground 内联条件提出来，**卡片玻璃取样共用**（见 Common.kt 的
 * frostedGlass）：卡片玻璃只能取样「真正显示的那层底」——壁纸没在显示时
 * （封面模糊顶掉了它 / 播放器组没选「自定义」透壁纸），绝不能画壁纸取样，
 * 否则卡片颜色会跟着一个根本没露过面的壁纸走（用户口径「就算没有使用到自定义背景，
 * 卡片颜色也会跟随自定义背景」）。
 */
internal fun wallpaperShown(active: Boolean = AmbientSurface.active): Boolean =
    !AppearancePrefs.isM3e && BackgroundStore.ready &&
        (!active || AppearancePrefs.playShowsWallpaper) &&
        !coverBlurGlobal(active)

/** 封面模糊底图**是否参与显示**：播放器组内选了封面模糊风格，或全背景封面模糊生效。
 *  ⚠ 与 [coverBackdropNeeded] 的差别：外观模式 = 封面模糊 + 「保留取色」开 + 人在外围页时，
 *  底图仍需计算（autoLight 明暗判据要它）但不显示 —— 显示与计算是两个问题。 */
private fun coverBlurOnNow(active: Boolean = AmbientSurface.active): Boolean =
    (active && AppearancePrefs.coverBlurStyle) || coverBlurGlobal(active)

/**
 * ★ 2026-10-16 用户口径「深色模式无壁纸的时候有默认壁纸取色当前的专辑封面底色或者专辑
 *   封面底色模糊」——**深色主题 + 没设自定义壁纸时，「身后」并不是空的**：
 *   页面底就是**当前歌曲封面取色**（[SongAmbient.color] = 封面主色向黑压暗 75%），
 *   用户口径里这就是那台「默认壁纸」。所以卡片照旧可以做成真玻璃：
 *   把**当前歌曲封面**模糊成底图（[CoverBackdrop]）供卡片按位置取样。
 *
 * ⚠ 只影响**卡片取样**（[coverBlurSampleImage]）与**底图计算**（[coverBackdropNeeded]），
 *   **不**影响页面底（[coverBlurOnNow]）——用户确认「页面背景仍保持原来的取色」。
 *   也正因为如此，卡片会透出与页面底「同源但不同形」的封面色彩：
 *   两者都来自同一张封面，观感是和谐的（底色是封面的主色、卡片是封面的模糊）。
 *
 * ★ 2026-10-18 用户 bug「深色模式选择取色主题时卡片不以取色好的背景为基准进行模糊」——
 *   上面那条口径被**收窄**：只有当身后真有「模糊封面」这张底可看时（即播放页面风格 =
 *   封面模糊，[AppearancePrefs.coverBlurStyle]）才取样封面。
 *   原因：取色风格下页面底是**纯色**的歌曲环境色，而旧判据只看「深色 + 无壁纸」，
 *   于是在取色风格下也硬取一张封面彩色图铺进卡片 —— 背景是一块纯色、
 *   卡片是一张彩色模糊图，两套皮（用户口径的「不以取色背景为基准」）。
 *   收窄后：取色风格 → 卡片回落到「环境色 + 抬升」的玻璃底（与页面底同一色源，
 *   见 Common.kt 的 frostedGlass 兜底分支）；封面模糊风格 → 取样那张模糊封面，
 *   与页面底同源。两种风格的卡片都跟随身后真实的底。
 */
internal fun darkCardCoverBlur(): Boolean =
    !AppearancePrefs.isM3e && !com.netmusiclite.ui.theme.isLightTheme && !BackgroundStore.ready &&
        AppearancePrefs.coverBlurStyle

/**
 * 深色卡片玻璃的取样底（★ 2026-10-16 深色材质修复，调用方 Common.kt 的 frostedGlass）：
 * 自定义壁纸没在画、而封面模糊底**可用**时，返回当前模糊封面 —— 卡片不再画与底色几乎
 * 同黑的纯黑块，而是取身后真实的底、随歌变色。
 *
 * 「可用」= 底图正在画（[coverBlurOnNow]：播放器组封面模糊风格 / 全背景封面模糊）
 * **或** 深色主题 + 无壁纸（[darkCardCoverBlur]：页面底是封面取色，封面即默认底）。
 * null = 无可取样（无歌 / 无封面 / 还没算出来）→ 调用方回落到「环境色玻璃底」。
 *
 * ★ 2026-10-18 用户口径「没有选择自定义图片时的假玻璃同步为高斯模糊玻璃」——
 *   **classic 也改用 [CoverBackdropLayer.glassImage]**（原来只有 m3e 用它）。
 *   旧实现给 classic 的是 [CoverBackdropLayer.image]：那是**播放页背景成图**，
 *   已做过曝光归一（深色主题平均亮度被压到 DARK_TARGET 0.14）+ 96px 上半径 25 的巨糊 ——
 *   卡片从里面取到的只是「一块均匀的暗色」，透不出封面自己的色块走向，
 *   观感就是一块半透明色块（用户说的「假玻璃」）。而设了壁纸时取的是
 *   `BackgroundStore.blurredImage`：壁纸原图（未压暗）+ 466px 上的半径 25，色块肉眼可辨 ——
 *   两者根本不是同一种材质。
 *   [CoverBackdropLayer.glassImage] 正是「封面原图 → Blurry 真高斯（半径按 466 屏折算）」
 *   的那一张，语义就是「给玻璃件取样用」，与壁纸路径同规格、同亮度基准；
 *   播放页背景继续用 [CoverBackdropLayer.image]（它需要暗到能压住白色歌词）。
 */
internal fun coverBlurSampleImage(): ImageBitmap? =
    if (coverBlurOnNow() || darkCardCoverBlur() ||
        (AppearancePrefs.isM3e && AppearancePrefs.m3eBackgroundStyle != M3eBackgroundStyle.BLACK))
        CoverBackdrop.current?.glassImage
    else null

/**
 * 当前是否需要封面模糊底图：**显示**需要（见 [coverBlurOnNow]），
 * 或**外观模式 = 封面模糊**（全 App 明暗由 [CoverBackdrop.autoLight] 决定，只要它的 rawLuminance），
 * 或**深色主题 + 无壁纸**（见 [darkCardCoverBlur]：底图不显示，但要给玻璃卡片当取样底）。
 * = NcmBackground 取图判定的完整口径，同时供 AppRoot 启动预取共用 ——
 * 预取不能等 NcmBackground 组合后才开始，否则慢封面（网络歌）会落在刷新揭开之后，
 * 明暗/底色就要当着用户的面换一次。
 */
internal fun coverBackdropNeeded(): Boolean =
    if (AppearancePrefs.isM3e) AppearancePrefs.m3eBackgroundStyle != M3eBackgroundStyle.BLACK
    else coverBlurOnNow() || AppearancePrefs.mode == ThemeMode.COVER_BLUR || darkCardCoverBlur()

@Composable
fun PrepareNcmBackground() {
    val song = PlayerEngine.current
    val ctx = LocalContext.current
    remember { NcmContextHolder.app = ctx.applicationContext }
    // 取色源与封面同源（已下载用落盘文件，断网也能取色；设备本地歌解析为媒体库 Uri，
    // 旧版直接把 local:// 串丢给取色必然失败、环境色永远是上一首——顺带修掉）
    val coverModel = songCoverModel(song)
    // ★ 2026-10-17 用户口径「m3e 主题的主题色取色要跟随歌曲封面主题色实时变化」：
    //   m3e 分支以前是「画一块黑底 + return」——SongAmbient.pull 永远不执行 ⇒ rawColor 恒 null
    //   ⇒ Theme.kt 的 EffectiveSeed 永远回落预设种子色 ⇒ M3eTheme 的 primary/secondary/
    //   tertiary 换歌不变（实测：m3e 播放页橙色环一路固定，与封面无关）。
    //   取色始终在背景风格分支之前执行：纯黑背景下主题色也随歌曲封面实时变化。
    LaunchedEffect(song?.id, coverModel, com.netmusiclite.ui.theme.isLightTheme) {
        SongAmbient.pull(coverModel)
    }
    // 封面模糊底图：只在「封面模糊风格 + 站在播放器组」时取 —— 其余风格完全不付
    // 这份解码 + 模糊成本（切回该风格再进播放器组时现算，一次几毫秒）。
    // 判据与 AppRoot 启动预取（coverBackdropNeeded）同源，见其注释。
    // ★ 2026-10-07 新增「外观模式 = 封面模糊」（全局自动明暗）：全 App 明暗由封面亮度
    //   决定，所以任何播放页面风格下都要这张底 —— 只取它的 rawLuminance 做判据，
    //   不参与显示（显示仍只由上面的 coverBlurOn 决定，取色/自定义风格不被改变）。
    // ★ 2026-10-16：判据收敛到与 AppRoot 启动预取**同一个** [coverBackdropNeeded]
    //   （= 底图在显示 ∪ 外观模式=封面模糊 ∪ 深色主题无壁纸要当卡片取样底）。
    //   旧版在这里自己拼 `coverBlurOn || autoTone`，漏掉「深色 + 无壁纸」那一档 ——
    //   底图不会被算出来，卡片自然取不到样（用户口径「深色模式默认主题没有适配玻璃卡片」）。
    val needBackdrop = coverBackdropNeeded()
    // 主题也是 key：曝光归一的目标亮度随明暗主题不同，换主题必须重算底图。
    // ★ 2026-10-07 再加 song?.id：两首歌封面同源（同一专辑图 / 都缺封面时 model 相等）时
    //   旧 key 不重跑，pull 的「切歌必重取」时序保障就断了 —— 用 song.id 兜住
    //   （bug③：切歌后歌词页仍是上一首的模糊效果）。
    LaunchedEffect(song?.id, coverModel, needBackdrop, AppearancePrefs.interfaceStyle, com.netmusiclite.ui.theme.isLightTheme,
        AppearancePrefs.coverBlurToneFlip) {
        if (needBackdrop) CoverBackdrop.pull(ctx, coverModel, com.netmusiclite.ui.theme.isLightTheme)
    }
}

/** A destination owns its backdrop, so its transforms move the background with its controls.
 * Decode, blur and palette work remain shared by [PrepareNcmBackground] in AppRoot.
 * A null route retains AmbientSurface behavior for standalone previews/tests.
 */
@Composable
fun NcmBackground(
    modifier: Modifier = Modifier,
    route: String? = null,
    prepareResources: Boolean = true,
) {
    if (prepareResources) PrepareNcmBackground()
    val ctx = LocalContext.current
    val coverModel = songCoverModel(PlayerEngine.current)
    val pageRoute = route ?: AmbientSurface.route
    val active = if (route == null) AmbientSurface.active else
        route == com.netmusiclite.ui.nav.Routes.PLAYER ||
            route == com.netmusiclite.ui.nav.Routes.LYRICS ||
            route == com.netmusiclite.ui.nav.Routes.MORE
    val coverBlurOn = coverBlurOnNow(active)
    // 显示自定义壁纸：设置了背景图，且当前不在播放器组，或用户在播放器组选了「自定义」。
    // ★ 2026-10-07：全背景封面模糊（见 coverBlurGlobal）时外围页的壁纸位让给模糊封面 ——
    //   模糊层画在壁纸层之上本就会盖住它，这里直接把壁纸层关掉省一次全屏绘制；
    //   播放器组不受影响（coverBlurGlobal 已排除该场合）。
    val showImage = wallpaperShown(active)
    // ★ 2026-10-07「封面模糊明暗对调」：人在播放器组三页时底按翻转后的明暗插值
    //   （模糊底图未就绪的窗口 / 无封面时露出的就是这层底，墨色已翻、底必须同向）；
    //   外围页与未开对调时走原 SongAmbient.color，零差异。进出播放器组由 220ms 颜色动画承接。
    val playToneFlipNow = AppearancePrefs.coverBlurToneFlip && active
    // Both themes use the same ambient-color transition and CoverBlurBackdropLayer below.
    // Material only selects the scope/style; its light foreground needs the dark exposure.
    // ★ 2026-10-18 m3e 黑色底色：是否在本页生效（「仅在播放页」开关把范围收窄到播放器组）
    val m3eBgApplies = !AppearancePrefs.m3eBackgroundPlayerOnly || active
    // m3e 中心柔光（可在 主题 → 背景样式 → 中心柔光 关掉），见 M3eCenterGlow。
    // 生效范围 =「本页底色实际是纯黑」的两种情形：
    //  ① 背景样式 = 纯黑（全 App）；
    //  ② 背景样式 = 取色 / 封面模糊，但开了「仅在播放器生效」—— 那么外围页（主页等）
    //     底色就是纯黑，同样该有这抹柔光（★ 2026-10-18 用户口径）。
    //     `!m3eBgApplies` 正好表示"本页不在生效范围内"，即底色走的是 Color.Black 那一支。
    // ★ 刻意**不**用 style==BLACK 时再叠 m3eBackgroundPlayerOnly 的第二重判断：
    //   纯黑档那个开关是隐藏的（黑底恒定），存量值可能是 true，叠了会让主页丢光。
    val m3eBlackGlow = AppearancePrefs.isM3e && com.netmusiclite.data.M3eGlowPrefs.enabled &&
        (AppearancePrefs.m3eBackgroundStyle == M3eBackgroundStyle.BLACK || !m3eBgApplies)
    val ambientBase = when {
        AppearancePrefs.isM3e -> {
            if (m3eBgApplies && AppearancePrefs.m3eBackgroundStyle != M3eBackgroundStyle.BLACK)
                SongAmbient.colorFor(light = false)
            else Color.Black
        }
        playToneFlipNow -> SongAmbient.colorFor(!com.netmusiclite.ui.theme.isLightTheme)
        else -> SongAmbient.color
    }
    // ★ 2026-10-17（本轮）classic 回退（对齐 1.24-r2）：全局底色过渡用 220ms；
    //   420ms 是 m3e 目的地背景的配套值，m3e 保持不动。
    val base = animateColorAsState(
        if (showImage) Bg else ambientBase,
        tween(if (AppearancePrefs.isM3e) 420 else 220),
        label = "ambientBase",
    ).value
    Box(modifier.fillMaxSize().background(base)) {
        // ★ 2026-10-18 m3e「黑色底色」：屏幕中心一抹主题色径向柔光。
        //   画在纯黑底之上、壁纸 / 封面模糊层之下 —— 那两层在黑色底色下本来就不画，
        //   所以这一层是唯一的"底"，卡片之间的缝隙里透出的就是它。
        //   颜色走 [com.netmusiclite.ui.theme.Accent]（当前主题色，动态取色时跟随封面），
        //   换歌由 M3eCenterGlow 内部做 600ms 线性过渡。
        if (m3eBlackGlow) {
            M3eCenterGlow(com.netmusiclite.ui.theme.Accent)
        }
        if (BackgroundStore.ready) {
            // 图层随 showImage 淡入淡出（与环境色底交叉溶解），alpha=0 时整层跳过绘制
            val imgAlpha by animateFloatAsState(if (showImage) 1f else 0f, tween(220), label = "bgImgAlpha")
            if (imgAlpha > 0.01f) {
                Box(Modifier.matchParentSize().graphicsLayer { alpha = imgAlpha }) {
                    val bgBitmap = if (BackgroundStore.bgBlur > 0.01f) BackgroundStore.blurredBgImage else null
                    if (bgBitmap != null) {
                        // 背景模糊开启：画模糊底图（滑杆控制强度）
                        Image(
                            bitmap = bgBitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    } else {
                        // 默认：清晰原图（按需解码防大图卡顿）
                        // ★ 2026-09-30 冷启动专项：解码档从 932 收到 480 —— 屏幕只有 466px，
                        //   932 是 2 倍冗余：4 倍像素的解码 + GPU 纹理上传全挤在启动首次绘制那一下。
                        // ★ 2026-10-01：cacheKey 带上 version —— 换背景图时文件路径不变，
                        //   不带版本会命中 Coil 旧图缓存（用户报「背景没变、只有玻璃卡变色」）。
                        val bgVer = BackgroundStore.version
                        AsyncImage(
                            model = ImageRequest.Builder(ctx)
                                .data(BackgroundStore.file)
                                .memoryCacheKey("bg:${bgVer}")
                                .diskCacheKey("bg:${bgVer}")
                                .size(480)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize(),
                        )
                    }
                    // ★ 2026-10-07 用户口径②：背景（自定义壁纸）主题下**歌词页**改「高斯模糊
                    //   材质」—— 盖一层 BackgroundStore.blurredImage（466px、半径 26 × 3 轮盒式
                    //   重模糊，与玻璃卡片取样同源、任何壁纸都常备的那张），不再透出清晰/轻糊原图。
                    //   420ms 交叉淡入淡出（进歌词页糊上、回来淡出），且画在下面压暗层**之前** ——
                    //   压暗同时盖住原图与糊层，「不要太透」不叠加、可读性由压暗 + 浅色主题的
                    //   歌词光晕（LyricsScreen.lyricHalo）保证。
                    val lyricsFrost = active && pageRoute == com.netmusiclite.ui.nav.Routes.LYRICS
                    val frostAlpha by animateFloatAsState(
                        if (lyricsFrost) 1f else 0f, tween(420), label = "lyricsFrost",
                    )
                    val frost = BackgroundStore.blurredImage
                    if (frostAlpha > 0.01f && frost != null) {
                        Image(
                            bitmap = frost,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .matchParentSize()
                                .graphicsLayer { alpha = frostAlpha },
                        )
                    }
                    // 压暗层：滑杆可调，兼顾玻璃质感与可读性
                    Box(Modifier.matchParentSize().background(Bg.copy(alpha = BackgroundStore.dim)))
                }
            }
        }
        // 封面模糊层（播放页面风格 = 封面模糊）：画在壁纸层之上、页面内容之下。
        // 传 coverModel：底图层据此判定「当前的底是不是这首歌的」（切歌宽限窗，bug③兜底）。
        CoverBlurBackdropLayer(coverBlurOn, coverModel)
    }
}

// 主色提取算法已抽到 `data/ColorExtract.kt` 的 dominantColorOf()：
// 封面环境色（上面的 SongAmbient）与背景图取色（BackgroundStore.seedColor）共用同一实现。

/**
 * m3e「纯黑底色」的中心径向柔光（★ 2026-10-18 用户口径「m3e 主题选择黑色底色的时候
 * 添加这个效果」+「使用标准」+「加个开关默认打开」）。
 *
 * ## 几何（三档标定里的"强"档）
 *  · 中心点 (50%, 46%)：圆表的视觉重心比几何中心高一点，放正中整屏会显得往下坠；
 *  · 半径 0.62×屏宽：让 alpha 在圆屏边缘刚好衰减到 0 —— 否则弧边会被裁出一道亮边；
 *  · 13 段色标（见 [GLOW_STOP_POSITIONS] / [GLOW_STOP_ALPHAS]）：两段渐变在纯黑底上
 *    会出现一圈肉眼可见的色阶环（用户 2026-10-22 口径⑧「不要有色彩断层」）。
 *
 * ## 颜色
 * 传当前主题色（[com.netmusiclite.ui.theme.Accent]）—— 动态取色开着时它跟随封面主色，
 * 换歌就是"光斑跟着歌变色"。这里不给它做明度钳制：光斑叠在纯黑上，
 * 暗一点只是更含蓄，不会出现对比度问题。
 *
 * ## 去色带（★ 2026-10-22 用户口径⑧，两手并用）
 * 色带的根因是**屏幕色深**：radialGradient 的 alpha 在 8bit 输出上被量化成 256 级，
 * 线性渐变在低亮度区每级占的**半径区间**变大，于是等值线肉眼可辨。两条廉价对策：
 *  ① **多色标 + 非线性插值**：3 个锚点 (0→0.50 / 0.45→0.20 / 1→0) 用单调三次
 *     Hermite 插值采样成 13 个色标 —— 相邻色标的 alpha 落差从 0.30 降到 ≤0.06，
 *     等值线间距随之缩到约 1/5（细密渐层而不是几道粗环）。**端点斜率保留原值**
 *     （中心 -0.667、圆边 -0.364），所以光斑大小/边缘硬度的观感与改动前一致。
 *  ② **有序抖动**（[buildGlowDitherTile]）：在柔光圆内平铺一张 128×128 的 8×8 Bayer
 *     阈值图，50% 像素各加 1/255 白 —— 把「相邻两级色阶」那条硬边界打散成空间点阵，
 *     人眼融合后等效多 1 bit 灰阶。这是标准 dithering 做法，幅度低于可辨阈值，
 *     不会让画面发灰；圆外仍保持纯黑（clip 到圆内）。
 *
 * ## 开销
 * 一层全屏 [Brush.radialGradient] 填充 = GPU 一次 shader 绘制；
 * 抖动 tile 一次性生成后 remember，绘制期只是 16 次 128px 纹理平铺（不是整屏位图）。
 * 静止时不重绘（Canvas 无 invalidate），只有换歌时有 600ms 的颜色插值。
 * 仍可在 主题 → 背景样式 → 中心柔光 里一键关掉（见 data/M3eGlowPrefs）。
 */
@Composable
private fun M3eCenterGlow(color: Color) {
    // 换歌：线性过渡，避免"啪一下"换色（与封面模糊层 420ms 同一节奏量级）
    val animated by animateColorAsState(color, tween(600, easing = LinearEasing), label = "m3eGlow")
    // 色标只随颜色变化重建（动画期每帧一次；13 个 Pair 的小分配，静止时零成本）
    val stops = remember(animated) {
        Array(GLOW_STOP_POSITIONS.size) { i ->
            GLOW_STOP_POSITIONS[i] to animated.copy(alpha = GLOW_STOP_ALPHAS[i])
        }
    }
    // 抖动 tile：一次生成，失败（预览/无 Android 图形环境）就退化为「只有多色标」的旧观感
    val dither = remember { runCatching { buildGlowDitherTile() }.getOrNull() }
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2f
        val cy = size.height * 0.46f
        val radius = size.width * GLOW_RADIUS_RATIO
        drawRect(
            Brush.radialGradient(
                *stops,
                center = Offset(cx, cy),
                radius = radius,
            )
        )
        if (dither != null) {
            // 只抖动柔光圆内：圆外保持纯黑（1/255 的白在 OLED 纯黑区也是可避免的抬灰）
            val r = radius * 1.02f
            val clip = Path().apply {
                addOval(Rect(cx - r, cy - r, cx + r, cy + r))
            }
            clipPath(clip) {
                // 整像素平移 + FilterQuality.None ⇒ 1:1 平铺，噪声不会被双线性插值抹平
                var y = 0
                while (y < size.height.toInt()) {
                    var x = 0
                    while (x < size.width.toInt()) {
                        drawImage(
                            dither,
                            dstOffset = IntOffset(x, y),
                            filterQuality = FilterQuality.None,
                        )
                        x += dither.width
                    }
                    y += dither.height
                }
            }
        }
    }
}

// ---------------- m3e 中心柔光的色标 / 抖动表（★ 2026-10-22 去色带） ----------------

/** 柔光半径 / 屏宽（原值 0.62，不要改：改了这个圆的边缘就会贴到圆表弧线上） */
private const val GLOW_RADIUS_RATIO = 0.62f

/**
 * 13 个色标位置（0..1，均匀）。
 * 与 [GLOW_STOP_ALPHAS] 一一对应；为什么是 13 而不是原 3 个，见 [M3eCenterGlow] 的去色带说明。
 */
private val GLOW_STOP_POSITIONS = floatArrayOf(
    0.0000f, 0.0833f, 0.1667f, 0.2500f, 0.3333f, 0.4167f, 0.5000f,
    0.5833f, 0.6667f, 0.7500f, 0.8333f, 0.9167f, 1.0000f,
)

/**
 * 对应位置的 alpha：**过原三锚点 (0, 0.50) / (0.45, 0.20) / (1, 0) 的单调三次 Hermite 插值**。
 * 端点斜率刻意取原折线的段斜率（中心 -0.667、末段 -0.364），段内再平滑过渡 ——
 * 这样中心亮度、圆边衰减速度都与原来的 3 色标版本一致，改的只是「每级的半径间距」。
 * 数值由插值公式离线算好硬编码（避免运行时逐帧算幂函数）。
 */
private val GLOW_STOP_ALPHAS = floatArrayOf(
    0.50000f, 0.44200f, 0.38124f, 0.32123f, 0.26525f, 0.21660f, 0.17740f,
    0.14334f, 0.11269f, 0.08428f, 0.05685f, 0.02915f, 0.00000f,
)

/**
 * 经典 8×8 Bayer 有序抖动阈值矩阵（0..63，行优先）。
 * 取 `值 < 32` 的像素各加 1/255 白 ⇒ 恰好 50% 覆盖率、图案在 8px 尺度上规则且无低频聚集；
 * 相比随机 hash 噪声，Bayer 不会出现「一块亮一块暗」的团簇（那在纯黑底上比色带更难看）。
 */
private val GLOW_BAYER8 = intArrayOf(
    0, 32, 8, 40, 2, 34, 10, 42,
    48, 16, 56, 24, 50, 18, 58, 26,
    12, 44, 4, 36, 14, 46, 6, 38,
    60, 28, 52, 20, 62, 30, 54, 22,
    3, 35, 11, 43, 1, 33, 9, 41,
    51, 19, 59, 27, 49, 17, 57, 25,
    15, 47, 7, 39, 13, 45, 5, 37,
    63, 31, 55, 23, 61, 29, 53, 21,
)

/** 抖动 tile 边长（px）。128 覆盖 466px 屏只要 4×4 = 16 次平铺，又足够大以免看出重复 */
private const val GLOW_DITHER_TILE = 128

/**
 * 生成抖动 tile：白色 + alpha 1/255 的「每像素二值」位图（Bayer 8×8 循环平铺到 128×128）。
 *
 * 为什么是 1/255 而不是更大：屏幕量化步长就是 1 个 8bit 色阶，抖动量必须等于**一个步长**
 * 才能把那条硬边界刚好糊开；再大就会看出噪点。
 * ARGB 值 `0x01FFFFFF` = alpha 1、RGB 白 —— Bitmap 存的是预乘 alpha，1/255 白正好也是 1。
 */
private fun buildGlowDitherTile(): ImageBitmap {
    val px = IntArray(GLOW_DITHER_TILE * GLOW_DITHER_TILE)
    var i = 0
    for (y in 0 until GLOW_DITHER_TILE) {
        val row = (y and 7) * 8
        for (x in 0 until GLOW_DITHER_TILE) {
            px[i++] = if (GLOW_BAYER8[row + (x and 7)] < 32) 0x01FFFFFF else 0x00000000
        }
    }
    return Bitmap.createBitmap(px, GLOW_DITHER_TILE, GLOW_DITHER_TILE, Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

