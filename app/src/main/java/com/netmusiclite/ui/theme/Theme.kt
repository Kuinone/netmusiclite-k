package com.netmusiclite.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  Material 3 主题层（2026-10-01 主题化改造）
 * ════════════════════════════════════════════════════════════════════════════
 *
 * 改造原则：**布局零改动，只换颜色来源**。
 *
 * 项目原本把 Bg / TextPrimary / Accent 等写成顶层 `val` 常量，被 20+ 个文件
 * 直接 `import com.netmusiclite.ui.theme.Bg` 引用。这里把它们全部改成
 * 「读组合状态的 getter」——名字、类型、语义全部不变，**调用点一个字符都不用动**，
 * 但换主题时所有引用点会自动重组（因为 getter 内部读的是 AppearancePrefs 的组合状态）。
 *
 * 非 Composable 上下文（Canvas draw 回调、取色协程、@Composable 之外的初始化）
 * 读到的同样是当前值，只是不会主动触发重组 —— 与原常量行为一致。
 * ════════════════════════════════════════════════════════════════════════════
 */

/**
 * 一套完整的界面色板。分深/浅两套，字段与项目原有语义一一对应，
 * 只做「同一个位置在不同明暗下取什么色」的映射，不引入新语义。
 */
data class NcmPalette(
    val light: Boolean,
    /** 页面底色 */
    val bg: Color,
    /** 抬升表面（卡片兜底底、对话框底） */
    val bgElevated: Color,
    /** 胶囊半透明材质（高斯玻璃填充） */
    val glass: Color,
    /** 玻璃描边（边缘高光） */
    val glassBorder: Color,
    /** 弱色块（图标底、次级填充） */
    val strong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val separator: Color,
)

// ---- 深色（原有观感，一个色值都没动）----
// 2026-09-11 文字清晰度专项：旧值 TextSecondary 60% / TextTertiary 32% 的
// 小字号在 2.0 密度屏抗锯齿后发灰发虚，抬高下限到 70% / 55%（层级关系保留）
private val DarkPalette = NcmPalette(
    light = false,
    bg = Color(0xFF0A0A0C),
    bgElevated = Color(0xFF16161A),
    glass = Color(0x22FFFFFF),
    glassBorder = Color(0x30FFFFFF),
    strong = Color(0x24FFFFFF),
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xB3EBEBF5),
    textTertiary = Color(0x8CEBEBF5),
    separator = Color(0x1AFFFFFF),
)

// ---- 浅色（2026-10-01 新增）----
// 玻璃材质 alpha 重标定：深色下「白 13% 填充」靠透出深底来成材；
// 浅色下底色本身很亮，同样的白 13% 会彻底消失，必须改为「白 72% 填充 + 黑 8% 描边」，
// 才有可辨的玻璃块边界（对应《M3 换肤版预览》里的玻璃 alpha 标定表）。
// ⚠ 底色刻意压到 0xFFEFEFF3 而不是更亮的 #F6F6F9：手表上没有阴影可用（项目用描边+材质
//   区分层级），底色若太接近纯白，「白卡叠白底」会糊成一片、所有卡片边界消失。
//   压到 239 这档后，白卡（≈254）与底色的差值约 15/255，肉眼可辨。
private val M3ePalette = DarkPalette.copy(bg = Color.Black, bgElevated = Color(0xFF1C1C1C), glass = Color(0xFF1C1C1C), strong = Color(0xFF292929))

private val LightPalette = NcmPalette(
    light = true,
    bg = Color(0xFFEFEFF3),
    bgElevated = Color(0xFFFFFFFF),
    glass = Color(0xB8FFFFFF),        // 白 72%
    glassBorder = Color(0x14000000),  // 黑 8%
    strong = Color(0x0F000000),       // 黑 6%
    textPrimary = Color(0xFF17171A),
    textSecondary = Color(0xB31A1A1E), // 黑 70%
    textTertiary = Color(0x8C1A1A1E),  // 黑 55%
    separator = Color(0x1F000000),     // 黑 12%
)

/**
 * 当前色板。getter 内部读 AppearancePrefs 的组合状态（模式 / 跟随系统），
 * 所以任何在 @Composable 里读它的地方都会自动注册依赖 —— 换主题即整树重组。
 */
val palette: NcmPalette
    get() = if (com.netmusiclite.data.AppearancePrefs.isM3e) M3ePalette else if (com.netmusiclite.data.AppearancePrefs.isLight) LightPalette else DarkPalette

/** 当前是否浅色。供背景取色、系统栏适配等非组件代码判断明暗方向。 */
val isLightTheme: Boolean
    get() = !com.netmusiclite.data.AppearancePrefs.isM3e && com.netmusiclite.data.AppearancePrefs.isLight

// ════════════════════════════════════════════════════════════════════════════
//  强调色（M3 种子色）
// ════════════════════════════════════════════════════════════════════════════

/**
 * 深色底上的最低可用亮度。
 * 背景 Bg = 0xFF0A0A0C（相对亮度 ≈0.0035），按 WCAG 对比度公式
 * (L+0.05)/(0.0035+0.05) ≥ 3.0 ⇒ L ≥ 0.12 —— 低于此值的强调色作文字/图标等于「看不清」。
 */
private const val ACCENT_MIN_LUMINANCE_DARK = 0.12f

/**
 * 浅色底上的最高可用亮度。
 * 浅底 0xFFEFEFF3（相对亮度 ≈0.87），按 WCAG 对比度公式
 * (0.87+0.05)/(L+0.05) ≥ 3.0 ⇒ L ≤ 0.26 —— 取 0.22 留安全余量。
 * 白色（1.0）、亮黄、亮绿等种子在浅底上完全不可读，必须压深。
 */
private const val ACCENT_MAX_LUMINANCE_LIGHT = 0.22f

/**
 * 深色底可读性保障：深色系预设（Indigo 0xFF3F51B5 亮度≈0.105、部分深蓝）落在 Bg 上
 * 对比度不足 3:1，作为文字/图标时不可读。不足则保持色相朝白色插值到下限；
 * 网易云红(≈0.22)、蓝、橙、绿、紫等本就达标，原样返回不受影响。
 */
private fun Color.boostForDark(): Color {
    if (luminance() >= ACCENT_MIN_LUMINANCE_DARK) return this
    var t = 0.05f
    while (t < 1f) {
        val c = lerp(this, Color.White, t)
        if (c.luminance() >= ACCENT_MIN_LUMINANCE_DARK) return c
        t += 0.05f
    }
    return Color.White
}

/**
 * 浅色底可读性保障（与 boostForDark 对称）：太亮的种子朝黑插值压深。
 * 白色种子(0xFFFFFFFF)会一路压到接近纯黑；网易云红(≈0.22)刚好达标原样保留。
 */
private fun Color.boostForLight(): Color {
    if (luminance() <= ACCENT_MAX_LUMINANCE_LIGHT) return this
    var t = 0.05f
    while (t < 1f) {
        val c = lerp(this, Color.Black, t)
        if (c.luminance() <= ACCENT_MAX_LUMINANCE_LIGHT) return c
        t += 0.05f
    }
    return Color.Black
}

/**
 * 动态取色最终采用的种子色（2026-10-01；2026-10-02 按用户口径两次调整）：
 *  1. 未开启 → 用户选的预设种子色；
 *  2. 自定义背景图主色（见 `BackgroundStore.seedColor`）；
 *  3. 没设背景图 → **当前歌曲封面主色**（`SongAmbient.rawColor`，未插值的原始取色）。
 *     用户口径「自动取色打开以后无论是否设置壁纸都生效」—— 背景在未设图时本就跟随
 *     封面环境色，强调色补上同一色源后，整条自动取色链路无壁纸也能生效；
 *  4. 封面也还没取到（刚启动/无歌/取色失败）→ 回落预设种子色，永不空转。
 *
 * ★ 2026-10-02 用户口径「安卓 12 的系统壁纸效果删掉不要，只要安卓 11 的效果」：
 *   原 Android 12+ 的 Monet（dynamicXxxColorScheme）分支整体移除，所有版本统一走
 *   上面这条链 —— 观感跨版本一致，也不再依赖系统壁纸。
 *
 * 之所以统一成一个「有效种子」而不是让全局 Accent 与 M3 色板各走一路：
 * 必须保证「图标 / 按钮 / 选中态的强调色」与「ColorScheme.primary」永远是同一个色，
 * 否则会出现「色板是壁纸色、按钮还是网易云红」的撕裂感。
 */
val EffectiveSeed: Color
    get() {
        val accent = com.netmusiclite.data.AppearancePrefs.accent
        if (!com.netmusiclite.data.AppearancePrefs.dynamicColor) return accent
        // ★ 2026-10-07 用户 bug「选择自定义图片后，封面模糊主题浅色下玻璃卡片不生效，
        //   卡片和取色仍然跟随自定义图片」：壁纸主色只在壁纸**真的在画**时才作为色源 ——
        //   封面模糊顶掉壁纸（全背景封面模糊）时，底是封面模糊底，强调色必须跟封面走
        //   （下两级的 SongAmbient.rawColor 就是它），否则就是「底随歌、色随一张没露面的图」。
        val wallSeed = if (com.netmusiclite.ui.components.wallpaperShown())
            com.netmusiclite.data.BackgroundStore.seedColor else null
        return wallSeed
            ?: com.netmusiclite.ui.components.SongAmbient.rawColor
            ?: accent
    }

// ---- 全局强调色（动态）：种子来自 EffectiveSeed（预设色或动态取色），读的是 Compose 状态，
// 所有组合中读取 Accent 的地方都会在换色 / 换明暗 / 换背景图时自动重组 ----
/** 按指定明暗求强调色（对调作用域复用；全局 [Accent] 传 isLightTheme） */
private fun resolveAccent(light: Boolean): Color {
    val seed = EffectiveSeed
    return if (light) seed.boostForLight() else seed.boostForDark()
}

val Accent: Color
    get() = resolveAccent(isLightTheme)

/**
 * **歌曲主题色**（2026-10-06 用户口径）：「取色 / 封面模糊」两种播放页面风格下，
 * 歌词墨色与进度类元素（右缘进度弧 / 长按进度条）改跟**专辑封面主题色**。
 *
 * 为什么需要这一支：[EffectiveSeed] 的优先级是「壁纸主色 → 封面主色 → 预设种子」——
 * 一旦设了自定义壁纸，歌词与进度弧就永远跟着**壁纸**走；可这两种风格下页面底其实是
 * 跟着**歌**走的（取色 = 歌曲环境色，封面模糊 = 封面模糊底），底随歌、字与进度却随壁纸，
 * 换歌时就是「底变了、字和进度条没变」的撕裂感。
 *
 * 边界：
 *  · 没设壁纸时本值恒等于 [Accent]（EffectiveSeed 本来就取封面色），零差异；
 *  · 自定义风格（页面透出壁纸）**仍然跟壁纸** —— 与页面底保持一致；
 *  · 自动取色关闭时回落 [EffectiveSeed]（= 用户预设种子色），不抢用户的固定色；
 *  · 封面取色还没出结果（刚启动 / 无歌 / 取色失败）时同样回落，永不空转。
 */
/** 按指定明暗求歌曲主题色（对调作用域复用；全局 [SongAccent] 传 isLightTheme） */
private fun resolveSongAccent(light: Boolean): Color {
    val song = com.netmusiclite.ui.components.SongAmbient.rawColor
    val seed = if (com.netmusiclite.data.AppearancePrefs.dynamicColor && song != null) {
        song
    } else {
        EffectiveSeed
    }
    return if (light) seed.boostForLight() else seed.boostForDark()
}

val SongAccent: Color
    get() = resolveSongAccent(isLightTheme)

/** 强调色柔光底：白色等浅色种子下改用深色半透明 ——
 *  否则「白字 + 白 20% 底」在玻璃卡上完全糊掉（2026-10-01 主题可读性修复）。
 *  浅色模式下种子已被压深，直接用同色低 alpha 即可，不需要反色。 */
val AccentSoft: Color
    get() = when {
        isLightTheme -> Accent.copy(alpha = 0.14f)
        Accent.luminance() > 0.7f -> Color(0xFF111111).copy(alpha = 0.28f)
        else -> Accent.copy(alpha = 0.2f)
    }

/** 实底上的反色内容：底色够亮时切深字、否则白字（实底强调色 / 胶囊选中态共用） */
fun onColorFor(c: Color): Color = if (c.luminance() > 0.75f) Color(0xFF111111) else Color.White

/** 实底强调色上的内容色：主题色为浅色（如白色）时自动切深字，保证对比度 */
val OnAccent: Color
    get() = onColorFor(Accent)

// ════════════════════════════════════════════════════════════════════════════
//  歌词文字取色（2026-10-01）
// ════════════════════════════════════════════════════════════════════════════

/**
 * 歌词可读性下限：WCAG 对比度 4.5:1（正文档）。
 *
 * 为什么取正文档档位而不是大字号档的 3:1：歌词正文是 15sp / W900，按 WCAG 的
 * 「大字号」定义 3:1 就够。但同等对比度下彩色字的感知清晰度低于白字（白字靠
 * 亮度差、彩字还要靠色相辨认），而且歌词页在取色之外还要再被图层 alpha 压暗
 * （相邻行 0.48、远端 0.36，见 LyricsScreen 的 lyricBrightness）——
 * 所以取色本身按 4.5:1 标定，把余量留给渐变。
 *
 * ⚠ 这**不是**「渐变后的每一行都 ≥4.5:1」。alpha 压暗会把对比度一起压下去，
 *   这是渐变本身的代价，而那条渐变是用户逐轮调出来的（见 LyricsScreen 里
 *   LYRIC_DIM_FAR 的注释：曾压到 0.13 导致远处行读不清，抬到 0.36 是用户口径），
 *   不能为了凑对比度把它拉平。这条下限保证的是**满亮那一行 —— 正在唱的那句 ——
 *   清晰可读**，其余行按与当前行的距离自然衰减。
 */
private const val LYRIC_MIN_CONTRAST = 4.5f

/**
 * 浅色主题 + 自动取色开启时的对比度下限（2026-10-02 用户口径
 * 「提亮浅色模式打开自动取色的聚焦歌词颜色，增加可读性」）。
 *
 * 为什么这条路径要单独放宽：浅色主题下自动取色色会被**两级压暗** ——
 * 先是 [Accent] 的 boostForLight 把亮度压到 ≤0.22（按页面底色标定），
 * 再被 [lyricInk] 的 4.5:1 对着歌词页的浅色环境底（SongAmbient 向近白插值 86%，
 * 相对亮度约 0.61~0.87）继续压到 ≈0.10~0.15 —— 壁纸/背景图的颜色被压成近黑的
 * 深泥色，用户看到的「聚焦歌词发黑、看不清取到的颜色」就是这两级叠加的结果。
 *
 * 放宽到 3.2:1 的依据：聚焦行是 15sp / W900，远超 WCAG「大字号」（≥14pt 粗体）的定义，
 * 大字号的 AA 线是 3:1 —— 取 3.2 留一点余量。效果：最亮底上墨色直接用 Accent 原色
 * （不再二次压暗，亮度约 +45%），最暗底上的压暗量也砍半以上。非聚焦行的明暗仍由
 * 图层 alpha 渐变承担（那是用户逐轮调出来的，不动），本下限保证的仍是满亮那行。
 */
private const val LYRIC_MIN_CONTRAST_DYNAMIC_LIGHT = 3.2f

/**
 * ★ 2026-10-10 封面模糊主题的歌词对比度下限（3.5:1）。
 *
 * 用户口径：「封面模糊主题的歌词取色经常为纯白导致扫光效果无法显示」。
 *
 * 成因：封面模糊底被 backdropScrim 归一化到**固定亮度**（深色 ≈0.14 / 浅色 ≈0.50），
 * 而 4.5:1 在亮度 0.14 的底上要求墨色亮度 ≥0.805 —— 那就是「极淡的近白」。
 * 扫光（LyricsScreen 的 drawLyricSweep）本身是**纯白**光斑，
 * 扫过近白字形时亮度差趋近 0 ⇒ 视觉上「扫光没了」。
 *
 * 放宽到 3.5:1 ⇒ 墨色只需 ≈0.62，**保住封面色彩度、留出 ≈0.38 的亮度余量**，
 * 白光扫过时清晰可见。
 *
 * 可读性依据：聚焦行是 15sp / W900，远超 WCAG「大字号」（≥14pt 粗体）的定义，
 * 大字号的 AA 线就是 3:1，取 3.5 留余量（与 [LYRIC_MIN_CONTRAST_DYNAMIC_LIGHT] 同思路）。
 * 另：封面模糊底的局部起伏远小于自定义壁纸（后者实测同屏 1.2:1~5.2:1），
 * 单点标定在这种「被压平的底」上比透壁纸场景更可靠。
 */
private const val LYRIC_MIN_CONTRAST_COVER_BLUR = 3.5f

/** WCAG 相对对比度（两色互换不影响结果） */
private fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * 歌词文字取色 —— 把「自动取色」的范围延伸到歌词正文。
 *
 * 用户口径：「自动取色范围延伸到歌词上面，同时保证歌词可读性」。
 *
 * 色源就是 [Accent]（= [EffectiveSeed]）：本项目里「自动取色」指的正是
 * 设置页那个「跟随壁纸取色」开关 —— 它此前只作用在图标 / 按钮 / 选中态 /
 * 进度弧上，歌词正文一直是恒定的 [TextPrimary]。现在同一支颜色延伸到歌词，
 * 所以自动取色开着时歌词跟着壁纸（Android 12+）或背景图（Android 11）走，
 * 关着时跟随用户选的种子色 —— 与全站强调色始终是同一个色，不会出现撕裂。
 *
 * 可读性为什么要单独算，而不是直接拿 [Accent] 用：
 *   ① [Accent] 是按页面底色 [Bg] 标定的（boostForDark / boostForLight），
 *      而歌词页的底不是 Bg，是 [com.netmusiclite.ui.components.SongAmbient] 的
 *      歌曲环境色 —— 封面主色向近黑压暗 75%（浅色主题向近白压暗 86%）。
 *      底色本身带彩度、亮度还随歌变化：亮封面（亮黄 / 亮青）压暗后仍有
 *      ~0.05 的相对亮度，直接套用「按 Bg 标定的 Accent」在那种底上对比度不足。
 *   ② 所以这里以**歌词页的真实底色**为基准重算对比度，不达标就保持色相朝
 *      白 / 黑插值（与 boostForDark / boostForLight 同款步进插值），直到满足
 *      [LYRIC_MIN_CONTRAST]。底色偏亮朝黑压、偏暗朝白提，方向由底色亮度决定。
 *
 * ⚠ 只定「色」，不碰透明度：歌词的明暗渐变完全由 LyricRow 的图层 alpha 承担。
 *   LyricsScreen 里有一条硬约束 —— 逐帧改 Text 颜色会让 Paragraph 缓存整体失效
 *   等价于每帧重排版，所以取色结果必须是**每个重组周期内的常量**，
 *   由页面层算一次、逐行传入，行内不再重算。
 *
 * @param backdrop 歌词页的真实底色（环境色模式取 SongAmbient.color；
 *                 有自定义背景图且没开「保留取色」时页面透出背景图，按 [Bg] 标定）
 * @param seed 色源：默认 [Accent]（全站强调色）；「取色 / 封面模糊」两种播放风格下
 *             由 LyricsScreen 传 [SongAccent] —— 那两种风格页面底跟着歌走，
 *             歌词也该跟着**封面**走（见 [SongAccent] 的注释）
 * @param light 按哪个明暗标定可读性下限：默认全局主题；「封面模糊明暗对调」开启时
 *              播放页由 [PlayToneScope] 语义传到（见 [playLightTheme]）
 */
fun lyricInk(
    backdrop: Color,
    seed: Color = Accent,
    light: Boolean = isLightTheme,
    /** ★ 2026-10-10 封面模糊主题：下限放宽到 [LYRIC_MIN_CONTRAST_COVER_BLUR]（保住扫光可见性） */
    coverBlur: Boolean = false,
): Color {
    // 对比度下限按场景取：
    //  · 封面模糊       —— 3.5:1（见 LYRIC_MIN_CONTRAST_COVER_BLUR：既保可读、又让白扫光看得出来）；
    //  · 浅色 + 自动取色 —— 3.2:1（提亮墨色，见 LYRIC_MIN_CONTRAST_DYNAMIC_LIGHT 的推导）；
    //  · 其余           —— 4.5:1。
    val floor = when {
        coverBlur -> LYRIC_MIN_CONTRAST_COVER_BLUR
        light && com.netmusiclite.data.AppearancePrefs.dynamicColor -> LYRIC_MIN_CONTRAST_DYNAMIC_LIGHT
        else -> LYRIC_MIN_CONTRAST
    }
    if (contrastRatio(seed, backdrop) >= floor) return seed
    // ★ 2026-10-07 修复「浅色主题下歌词发白不可读」：方向阈值原为 0.5，但黑/白两条
    //   方向的对比度分水岭其实在亮度 ≈0.179（(L+0.05)² = 1.05×0.05 ⇒ L ≈ 0.179，
    //   比它亮 → 朝黑压更容易达标；比它暗 → 朝白提）。
    //   封面模糊底的标定灰 0xFFBABABA 亮度恰为 0.49 —— 旧阈值下被判为「暗底」朝白提，
    //   而白比 0.49 的底还亮，对比度永远够不到下限，循环空跑 20 步后直接返回纯白：
    //   浅色 + 封面模糊下的歌词整列发白（用户口径「保证可读性」）。
    //   换成 0.18 后：0.49 的底朝黑压（正常路径），深色主题的 0x696969（亮度 0.14）
    //   仍然朝白提，其余路径（环境色底很亮/很暗）方向不变。
    val toward = if (backdrop.luminance() > 0.18f) Color.Black else Color.White
    // ★ 2026-10-10 用户口径「取色取到深色的封面模糊主题的时候歌词一直是白色」：
    //   「朝白插值」把三个通道**分别**往 255 推 ⇒ 通道差被压平 = 去饱和，颜色越插越白 ——
    //   深色封面的 seed 本来偏暗，要提到达标的亮度就几乎全白，光调下限治不好。
    //   封面模糊主题改用**等比缩放**：色相保留、通道差被拉开（饱和度不降反升），
    //   墨色是「有颜色的亮色」，既够可读、又不再是死白，白色扫光扫过也有对比。
    if (coverBlur) {
        scaleInk(seed, backdrop, floor, up = toward == Color.White)?.let { return it }
    }
    var t = 0.05f
    while (t < 1f) {
        val c = lerp(seed, toward, t)
        if (contrastRatio(c, backdrop) >= floor) return c
        t += 0.05f
    }
    return toward
}

/**
 * 等比缩放着色：把 [seed] 的 RGB **同比**放大（[up]）/ 缩小，直到对 [backdrop] 的对比度
 * ≥ [floor]；达不到（seed 无彩度、或缩放到边界）返回 null，交调用方的插值兜底。
 *
 * 与 `lerp(seed, toward, t)` 的本质差异：
 *  · lerp —— 三通道各自朝 255（或 0）线性插值，通道差被压缩 ⇒ **去饱和**，越插越白/越灰；
 *  · 等比 —— 三通道乘同一个系数，通道差同比例拉开 ⇒ **色相不变、饱和度不降**，
 *    得到的是「更亮的蓝 / 更深的蓝」，而不是「更白的蓝」。
 * 这正是「封面模糊主题下歌词不该是死白」的关键。
 */
private fun scaleInk(seed: Color, backdrop: Color, floor: Float, up: Boolean): Color? {
    var k = 1f
    var guard = 0
    while (guard++ < 200) {
        k += if (up) 0.08f else -0.06f
        if (k <= 0.02f || k >= 14f) return null
        val c = Color(
            (seed.red * k).coerceIn(0f, 1f),
            (seed.green * k).coerceIn(0f, 1f),
            (seed.blue * k).coerceIn(0f, 1f),
        )
        if (contrastRatio(c, backdrop) >= floor) return c
    }
    return null
}

// ---- 语义色（随明暗换深浅，保证在各自底色上都可读）----
val Gold: Color get() = if (isLightTheme) Color(0xFF8A6000) else Color(0xFFFFD60A)
val Green: Color get() = if (isLightTheme) Color(0xFF11702C) else Color(0xFF30D158)
val Blue: Color get() = if (isLightTheme) Color(0xFF0A5CBB) else Color(0xFF0A84FF)

// ════════════════════════════════════════════════════════════════════════════
//  基础色（原顶层常量 → 改为读当前色板的 getter，调用点零改动）
// ════════════════════════════════════════════════════════════════════════════

val Bg: Color get() = palette.bg
val BgElevated: Color get() = palette.bgElevated
val SurfaceGlass: Color get() = palette.glass           // 胶囊半透明材质（高斯玻璃：透出模糊背景）
val SurfaceGlassBorder: Color get() = palette.glassBorder
val SurfaceStrong: Color get() = palette.strong
val TextPrimary: Color get() = palette.textPrimary
val TextSecondary: Color get() = palette.textSecondary
val TextTertiary: Color get() = palette.textTertiary
val Separator: Color get() = palette.separator

// ════════════════════════════════════════════════════════════════════════════
//  播放器组明暗作用域（2026-10-07 用户口径「封面模糊明暗对调」）
// ════════════════════════════════════════════════════════════════════════════
//
// 用户口径：「深色外观 + 播放页风格封面模糊 → 开关『使用浅色封面模糊』；
//   浅色外观 + 封面模糊 → 开关『使用深色封面模糊』」——开 = 播放器组三页
//   （播放页 / 歌词页 / 更多页）整体走另一种明暗（底图打光 + 墨色 + 卡片）。
//
// 为什么用 CompositionLocal 做**页面作用域**而不是一个全局开关：
//   全局开关在路由边界会露出破绽 —— 进播放页时身后的主页当场换色、
//   离开播放页时正在滑出的页面中途翻色。作用域挂在三个页面根上，
//   页面外的一切（主页、设置、列表…）永远读全局主题，零闪烁。
//
// 页面内的取色走下面的 Play* 变体（与全局同名色一一对应），
// 不在 [PlayToneScope] 里时 `LocalPlayLight.current == null` ⇒ 恒等于全局值，
// 所以所有 Play* 变体在作用域外零行为差异。

/** 播放器组明暗（null = 不在作用域内，回落全局主题） */
private val LocalPlayLight = compositionLocalOf<Boolean?> { null }

/**
 * 当前作用域下的「播放器组明暗」：不在 [PlayToneScope] 里 = 全局主题明暗 [isLightTheme]。
 * 页面内的明暗判断（卡片、文字、底图打光）应读它而不是 [isLightTheme]。
 */
val playLightTheme: Boolean
    @Composable get() = LocalPlayLight.current ?: isLightTheme

/**
 * 播放器组三页的根作用域：对调开关生效时把「播放器组明暗」翻到全局的反面。
 * 开关关闭时**不挂 Provider**（Local 保持 null），零额外开销、零行为差异。
 */
@Composable
fun PlayToneScope(content: @Composable () -> Unit) {
    if (com.netmusiclite.data.AppearancePrefs.isM3e || !com.netmusiclite.data.AppearancePrefs.coverBlurToneFlip) {
        content()
        return
    }
    CompositionLocalProvider(LocalPlayLight provides !isLightTheme, content = content)
}

/** 作用域色板：对调时取另一套明暗的色板（作用域外 = [palette]） */
@Composable
private fun playPalette(): NcmPalette = if (playLightTheme) LightPalette else DarkPalette

val PlayBg: Color
    @Composable get() = playPalette().bg

/** 抬升表面（★ 2026-10-16 深色石墨卡片素底用；对调作用域内取另一套板） */
val PlayBgElevated: Color
    @Composable get() = playPalette().bgElevated

val PlaySurfaceGlass: Color
    @Composable get() = playPalette().glass
val PlaySurfaceGlassBorder: Color
    @Composable get() = playPalette().glassBorder
val PlayTextPrimary: Color
    @Composable get() = playPalette().textPrimary
val PlayTextSecondary: Color
    @Composable get() = playPalette().textSecondary
val PlayTextTertiary: Color
    @Composable get() = playPalette().textTertiary
val PlayAccent: Color
    @Composable get() = resolveAccent(playLightTheme)
val PlaySongAccent: Color
    @Composable get() = resolveSongAccent(playLightTheme)

/**
 * 页面底色：恒透明（2026-09-04 流畅度专项 R1）。
 * 全局背景层（AppRoot 底部）已铺 Bg/背景图，页面再刷一遍底色是纯 overdraw；
 * 恒透明后每页少一层全屏填充。
 */
@Composable
fun screenBg(): Color = Color.Transparent

/** 头图底部渐变目标色：启用背景图时渐隐到透明（露出全局背景），否则渐到纯色底 */
@Composable
fun headerFade(): Color =
    if (com.netmusiclite.data.BackgroundStore.ready) Color.Transparent else Bg

/**
 * 文字样式阶梯。
 * ⚠ 必须是 getter 而不是 val 常量：常量会在 object 初始化时把颜色值冻结，
 *   后续换主题不会生效（原实现正是这个坑）。
 */
object T {
    // 字号体系（圆屏 233dp，全部偏小）
    val title: TextStyle
        get() = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp, color = TextPrimary)
    val body: TextStyle
        get() = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
    val secondary: TextStyle
        get() = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, color = TextSecondary)
    val caption: TextStyle
        get() = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Medium, color = TextTertiary)
    val button: TextStyle
        get() = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
}

// ════════════════════════════════════════════════════════════════════════════
//  种子色 → M3 角色梯度派生
// ════════════════════════════════════════════════════════════════════════════
//
// ⚠ 这里为什么不用 `ColorScheme.fromSeed`：
//   本项目 compose-bom = 2024.10.00 ⇒ material3 **1.3.0**，而 `ColorScheme.fromSeed`
//   是 material3 **1.4.0** 才加入的 API（已用 javap 核实 1.3.0 的 aar 里连
//   `ColorScheme$Companion` 都不存在，只有 `dynamicLight/DarkColorScheme`）。
//   所以改为「M3 baseline 全套角色打底 + 自己按 HSL 近似派生种子相关角色」，
//   效果等价于 fromSeed，且不引入依赖升级风险。
//   另注：1.3.0 的 ColorScheme 恰好是 **36 个角色**（无 `*Fixed` 系列，那也是 1.4.0 的），
//   所以下面 copy() 覆盖 32 个 + error 四色沿用 baseline = 36，不多不少。

/** HSL 三元组（各分量 0..1） */
private data class Hsl(val h: Float, val s: Float, val l: Float)

private fun Color.toHsl(): Hsl {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    if (max == min) return Hsl(0f, 0f, l)
    val d = max - min
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    val h = if (max == r) {
        ((g - b) / d + if (g < b) 6f else 0f) / 6f
    } else if (max == g) {
        ((b - r) / d + 2f) / 6f
    } else {
        ((r - g) / d + 4f) / 6f
    }
    return Hsl(h, s, l)
}

private fun hsl(h: Float, s: Float, l: Float): Color {
    val ss = s.coerceIn(0f, 1f)
    val ll = l.coerceIn(0f, 1f)
    if (ss <= 0f) return Color(ll, ll, ll)
    val q = if (ll < 0.5f) ll * (1f + ss) else ll + ss - ll * ss
    val p = 2f * ll - q
    fun ch(t0: Float): Float {
        var t = t0
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }
    return Color(ch(h + 1f / 3f), ch(h), ch(h - 1f / 3f))
}

/** 由种子色派生的一组角色（明度按 M3 tonal 规范取档：40/80/90/30/10） */
private data class AccentRamp(
    val primary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
)

/**
 * 从种子色派生角色梯度。
 * - primary 档次（tone 40/80）：色相与饱和度直接沿用种子，只调明度
 * - secondary：饱和度压到 34%（M3 里 secondary 是低彩度版主色）
 * - tertiary：色相 +60°、饱和度 50%（与主色形成和谐对比）
 * 纯白种子（s=0）会退化成中性灰梯度，正好符合「白主题色」的语义。
 */
private fun accentRamp(seed: Color, dark: Boolean): AccentRamp {
    val h = seed.toHsl()
    val h2 = (h.h + 1f / 6f) % 1f
    val s2 = h.s * 0.34f
    val s3 = h.s * 0.5f
    return if (!dark) AccentRamp(
        primary = hsl(h.h, h.s, 0.40f),
        primaryContainer = hsl(h.h, h.s, 0.90f),
        onPrimaryContainer = hsl(h.h, h.s, 0.12f),
        secondary = hsl(h.h, s2, 0.40f),
        onSecondary = Color.White,
        secondaryContainer = hsl(h.h, s2, 0.90f),
        onSecondaryContainer = hsl(h.h, s2, 0.12f),
        tertiary = hsl(h2, s3, 0.40f),
        onTertiary = Color.White,
        tertiaryContainer = hsl(h2, s3, 0.90f),
        onTertiaryContainer = hsl(h2, s3, 0.12f),
    ) else AccentRamp(
        primary = hsl(h.h, h.s, 0.80f),
        primaryContainer = hsl(h.h, h.s, 0.30f),
        onPrimaryContainer = hsl(h.h, h.s, 0.90f),
        secondary = hsl(h.h, s2, 0.80f),
        onSecondary = hsl(h.h, s2, 0.20f),
        secondaryContainer = hsl(h.h, s2, 0.30f),
        onSecondaryContainer = hsl(h.h, s2, 0.90f),
        tertiary = hsl(h2, s3, 0.80f),
        onTertiary = hsl(h2, s3, 0.20f),
        tertiaryContainer = hsl(h2, s3, 0.30f),
        onTertiaryContainer = hsl(h2, s3, 0.90f),
    )
}

// ════════════════════════════════════════════════════════════════════════════
//  Material 3 ColorScheme（36 角色全覆盖）
// ════════════════════════════════════════════════════════════════════════════

/**
 * 构建完整的 M3 ColorScheme（36 个角色全部落到项目语义色，不留系统默认紫）：
 *
 * 1. 基底用 M3 baseline 全套角色打底（★ 2026-10-02 用户口径「安卓 12 的系统壁纸效果
 *    删掉不要」：原 Android 12+ 会改用 `dynamicXxxColorScheme` 的系统 Monet 色板，
 *    已整体移除 —— 所有版本统一 baseline，观感跨版本一致）；
 * 2. 再用「种子色派生的角色梯度」覆盖 primary/secondary/tertiary 及其 container；
 *    error 四色沿用 baseline（M3 的错误红是通用语义色，不该被种子色改写）。
 * 3. 最后把项目既有语义色覆盖回 background / surface / outline —— 必须与 20+ 个
 *    引用 `Bg` / `TextPrimary` 的页面一致，否则会出现「卡片用浅色板、页面底色用
 *    派生色」的两套皮。
 */
@Composable
private fun buildColorScheme(pal: NcmPalette, accent: Color): ColorScheme {
    val base: ColorScheme = if (pal.light) lightColorScheme() else darkColorScheme()
    val r = accentRamp(accent, dark = !pal.light)
    // 中性 surface 容器阶梯：避免 Switch / Slider 落在 baseline 的紫调灰上
    val scLowest = if (pal.light) Color(0xFFFFFFFF) else Color(0xFF0E0E11)
    val scLow = if (pal.light) Color(0xFFF3F3F6) else Color(0xFF141418)
    val scMid = if (pal.light) Color(0xFFEDEDF1) else Color(0xFF1B1B1F)
    val scHigh = if (pal.light) Color(0xFFE7E7EC) else Color(0xFF232328)
    val scHighest = if (pal.light) Color(0xFFE0E0E6) else Color(0xFF2C2C32)
    return base.copy(
        primary = accent,
        onPrimary = OnAccent,
        primaryContainer = r.primaryContainer,
        onPrimaryContainer = r.onPrimaryContainer,
        inversePrimary = r.primary,
        secondary = r.secondary,
        onSecondary = r.onSecondary,
        secondaryContainer = r.secondaryContainer,
        onSecondaryContainer = r.onSecondaryContainer,
        tertiary = r.tertiary,
        onTertiary = r.onTertiary,
        tertiaryContainer = r.tertiaryContainer,
        onTertiaryContainer = r.onTertiaryContainer,
        background = pal.bg,
        onBackground = pal.textPrimary,
        surface = pal.bgElevated,
        onSurface = pal.textPrimary,
        surfaceVariant = pal.strong,
        onSurfaceVariant = pal.textSecondary,
        surfaceTint = accent,
        inverseSurface = if (pal.light) Color(0xFF2F2F33) else Color(0xFFF0F0F3),
        inverseOnSurface = if (pal.light) Color(0xFFF5F5F7) else Color(0xFF17171A),
        outline = pal.glassBorder,
        outlineVariant = pal.separator,
        scrim = Color.Black.copy(alpha = 0.6f),
        surfaceBright = if (pal.light) Color(0xFFFBFBFD) else Color(0xFF26262B),
        surfaceDim = if (pal.light) Color(0xFFE2E2E7) else Color(0xFF0C0C0F),
        surfaceContainerLowest = scLowest,
        surfaceContainerLow = scLow,
        surfaceContainer = scMid,
        surfaceContainerHigh = scHigh,
        surfaceContainerHighest = scHighest,
    )
}

@Composable
fun NcmTheme(content: @Composable () -> Unit) {
    // Preserve the navigation/saveable subtree when switching between the two independent themes.
    val latestContent = androidx.compose.runtime.rememberUpdatedState(content)
    val preservedContent = remember { androidx.compose.runtime.movableContentOf { latestContent.value() } }

    // 把系统深浅同步到偏好（「跟随系统」模式已从设置页移除，这里保留同步做防御）。
    // 用 SideEffect 而不是组合期直接赋值 —— 组合期写状态是反模式（可能触发重组环）。
    // 首次启动的初值由 AppearancePrefs.init() 从 Configuration 读出，所以不会有一帧错色。
    val sysDark = isSystemInDarkTheme()
    SideEffect { com.netmusiclite.data.AppearancePrefs.systemDark = sysDark }

    val pal = palette        // 读组合状态 → 换主题整树重组
    // ★ 2026-10-02：原 Android 12+ 的 Monet 种子回填（dynamicXxxColorScheme →
    //   DynamicSeed.systemColor）已随「删掉系统壁纸效果」整体移除。

    val accent = Accent

    // ★ 2026-10-03 字体大小/粗细（设置主题页两个滑块，用户口径）：
    //   大小 → 覆写 LocalDensity.fontScale：所有文本都按 sp 定义，一处覆写全局生效；
    //   粗细 → 换用自建 FontFamily.Resolver（内置官方 AndroidFontResolveInterceptor
    //   平移字重，见 FontWeightShift.kt）—— Resolver 是 sealed 不能包装，只能整只替换。
    //   ⚠ 两个 remember 的 key 必须含用户值：解析器实例与 density 都参与文本版式缓存键，
    //   不换实例则滑块拖了不生效（旧版式会被原样复用）。
    val baseDensity = LocalDensity.current
    val baseResolver = LocalFontFamilyResolver.current
    val appContext = LocalContext.current.applicationContext
    val scale = com.netmusiclite.data.AppearancePrefs.fontScale
    val weightLevel = com.netmusiclite.data.AppearancePrefs.fontWeightLevel
    // ★ 2026-10-03 屏幕尺寸自适应（用户口径「466x466 分辨率之外的手表会存在组件位移」）：
    //   整套 UI 的 dp 值是按 466×466 / density 2.0 = **233dp** 的设计基准写死的。换到别的
    //   dp 尺寸（三星 40mm 198dp、Ticwatch 227dp、Galaxy Watch 6 44mm 240dp…）后，同样
    //   的固定 dp 在屏上占比不同，居中/贴边/留白全部错位 —— 就是用户看到的「组件位移」。
    //   修法是**整体等比缩放**：把 LocalDensity.density 乘上「屏宽 / 设计基准」，
    //   让 233dp 的设计在任何屏上都恰好铺满短边，所有 dp 与 sp 一起等比 ——
    //   零散落改动（不必去逐个文件改 dp），触摸/命中测试也跟着缩放，不会错位。
    //   基准屏（本机 466×466）uiScale = 1.0 ⇒ density 与旧版**完全一致**，一个像素不变。
    //   ⚠ 代价：LocalConfiguration.screenWidthDp 是「系统 dp」，缩放后不再等于屏宽 ——
    //     凡是要「屏幕尺寸」的地方必须改走 screenWidthUiDp()/screenHeightUiDp()（见 Common.kt）。
    val uiScale = screenUiScale()
    val density = remember(baseDensity, scale, uiScale) {
        val d = baseDensity.density * uiScale
        val f = baseDensity.fontScale * DEFAULT_TEXT_SCALE * scale
        if (d == baseDensity.density && f == baseDensity.fontScale) baseDensity
        else Density(d, f)
    }
    val resolver = remember(baseResolver, weightLevel) {
        val shift = FONT_WEIGHT_SHIFTS.getOrElse(weightLevel) { 0 }
        if (shift == 0) baseResolver else shiftedFontFamilyResolver(appContext, shift)
    }
    CompositionLocalProvider(
        LocalDensity provides density,
        LocalClockFontScale provides baseDensity.fontScale,
        // 缩放系数供「屏幕尺寸换算」使用（screenWidthUiDp 等），值极少变 → static 更省开销
        LocalUiScale provides uiScale,
        LocalFontFamilyResolver provides resolver,
    ) {
        if (com.netmusiclite.data.AppearancePrefs.isM3e) {
            com.netmusiclite.ui.m3e.M3eTheme(EffectiveSeed, preservedContent)
        } else {
            MaterialTheme(colorScheme = buildColorScheme(pal, accent), content = preservedContent)
        }
    }
}

/**
 * 设计基准：短边 466px @ density 2.0 = **233dp**。
 * 全部布局 dp / sp 都是照这个尺寸标定的（圆屏弧边内缩、140dp 演示区、46dp 圆钮…）。
 */
const val DESIGN_SCREEN_DP = 233f

/** 100% in settings uses the enlarged application baseline. */
private const val DEFAULT_TEXT_SCALE = 1.06f

/**
 * 全局 UI 缩放系数 = 屏幕短边 dp / [DESIGN_SCREEN_DP]。
 *
 * 在 [NcmTheme] 里乘进 LocalDensity.density（布局与字体一起等比），并作为
 * [LocalUiScale] 下发给「屏幕尺寸换算」（screenWidthUiDp / screenHeightUiDp）。
 *
 * 夹取 [0.60, 1.50]：覆盖 140dp~350dp 的短边区间，异常上报值不会把界面缩到不可用。
 * 读的是 [LocalConfiguration]（系统配置，不受本函数下游的 density 覆写影响）——
 * 所以这里拿到的恒是**真实**的屏幕 dp 尺寸，不会自我循环。
 */
@Composable
fun screenUiScale(): Float {
    val cfg = LocalConfiguration.current
    val shortDp = minOf(cfg.screenWidthDp, cfg.screenHeightDp).toFloat()
    if (shortDp <= 0f) return 1f
    return (shortDp / DESIGN_SCREEN_DP).coerceIn(0.60f, 1.50f)
}

/**
 * 当前生效的 UI 缩放系数（[NcmTheme] 注入）。
 * 默认 1f —— 在 [NcmTheme] 之外（预览 / 单测）读到的是恒等缩放。
 */
val LocalUiScale = staticCompositionLocalOf { 1f }

/** System clock keeps its original font size independently of application text settings. */
val LocalClockFontScale = staticCompositionLocalOf { 1f }

