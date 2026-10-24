package com.netmusiclite.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 主题预设色板（2026-10-01 语义升级）：从「强调色」升级为 **Material 3 种子色**。
 *
 * 选中后不再只影响一个 Accent 色值 —— 而是作为 `ColorScheme.fromSeed` 的种子，
 * 由 HCT 算法派生出完整的 36 角色色板（primary/secondary/tertiary/各 *Container …）；
 * 其中 primary 会直接取用种子色本身，保证网易云红这类品牌色不被算法改写。
 *
 * ★ 2026-10-03 用户口径「自定义的种子颜色饱和度也降低一些」：5 个高饱和色按
 *   S≤65% 柔化（HSL 压饱和、色相/明度不变，与自动取色限饱和同一手法；档位由
 *   预览图 wm-配色预览-种子色降饱和.png 选定），低饱和的绿/紫/靛蓝/白原样保留。
 *   存量 sp 里的旧高饱和值由 [LEGACY_PRESET_MIGRATION] 在 init 迁移到新值，
 *   保证设置页选中环、种子名与实际配色三者一致。
 */
val PRESET_ACCENTS: List<Color> = listOf(
    Color(0xFFDA5353), // 网易云红（默认；原 #EC4141 柔化）
    Color(0xFF3E93D6), // Blue（原 #2196F3 柔化）
    Color(0xFF4CAF50), // Green（S 低于上限，不变）
    Color(0xFF9C27B0), // Purple（S 低于上限，不变）
    Color(0xFFD28F2D), // Orange（原 #FF9800 柔化）
    Color(0xFFD4336A), // Pink（原 #E91E63 柔化）
    Color(0xFF1A7C73), // Teal（原 #009688 柔化）
    Color(0xFF3F51B5), // Indigo（S 低于上限，不变）
    Color(0xFFFFFFFF), // White（无饱和度，不变）
)

/**
 * 存量迁移表：「降饱和」改版前的旧预设色 → 新柔化色（2026-10-03）。
 * 只列发生变化的 5 个高饱和色；旧值出现在 sp 里即换成对应新值。
 */
private val LEGACY_PRESET_MIGRATION: Map<Int, Int> = mapOf(
    0xFFEC4141.toInt() to 0xFFDA5353.toInt(),
    0xFF2196F3.toInt() to 0xFF3E93D6.toInt(),
    0xFFFF9800.toInt() to 0xFFD28F2D.toInt(),
    0xFFE91E63.toInt() to 0xFFD4336A.toInt(),
    0xFF009688.toInt() to 0xFF1A7C73.toInt(),
)

/** 种子色中文名（设置页副标题用，顺序与 [PRESET_ACCENTS] 严格一一对应） */
val PRESET_ACCENT_NAMES: List<String> = listOf(
    "网易云红", "蓝", "绿", "紫", "橙", "粉", "青", "靛蓝", "白",
)

/**
 * 主题明暗模式。
 *
 * ⚠ 2026-10-01：外观模式已**移除「跟随系统」**。用户反馈它与「深色」效果完全相同 ——
 * 手表系统深浅恒定（ColorOS Watch 没有浅色系统 UI），「跟随系统」永远解析成深色，
 * 是个点下去看不出区别的冗余选项。这里保留 [SYSTEM] 常量**仅用于存量值迁移**：
 * 之前选过「跟随系统」的用户 sp 里存的是 0，[AppearancePrefs.init] 会把它转成 [DARK]。
 */
object ThemeMode {
    /** 跟随系统深浅（已从设置页移除，仅作存量迁移用） */
    const val SYSTEM = 0
    /** 恒深色 */
    const val DARK = 1
    /** 恒浅色 */
    const val LIGHT = 2
    /**
     * ★ 2026-10-07 用户口径「增加外观模式封面模糊」：**全局自动明暗** ——
     * 全 App 的浅色/深色由当前歌曲封面自动决定（偏亮 → 浅色、偏暗 → 深色，切歌自动切换）。
     * 判据是封面模糊后、打光/曝光之前的中和亮度（CoverBackdrop.autoLight），
     * 与「播放页面风格 = 封面模糊」互不耦合：外观模式选它、播放页面风格随便选，
     * 播放页/歌词页照常按播放页面风格出画面。
     */
    const val COVER_BLUR = 3
}

/**
 * 播放页面风格（2026-10-06 用户口径「设置主题栏目里新增播放页面风格：取色 / 封面模糊 / 自定义」）。
 *
 * 只作用于**播放器组**三页（播放页 / 歌词页 / 更多页，见 AppNav 的 AMBIENT_SURFACE_ROUTES）：
 *  · [AMBIENT]    取色 —— 原有行为：歌曲封面主色向主题基准色压暗后作底（2026-09-13 起的默认）；
 *  · [COVER_BLUR] 封面模糊 —— 封面 scale-to-fill → 重模糊 → 顶部压暗/底部提亮（对齐
 *                 《歌词动画分析》的播放页背景策略），并启用逐字歌词与弹簧式行切换；
 *  · [CUSTOM]     自定义 —— 透出自定义壁纸（原「播放页/歌词页保留取色」关掉的效果）。
 *                 **没有壁纸时不显示这个选项**，存量值也会回落 [AMBIENT]（不会空转成透明底）。
 */
object PlayPageStyle {
    const val AMBIENT = 0
    const val COVER_BLUR = 1
    const val CUSTOM = 2
}

/** Independent Material background modes; classic appearance settings stay intact. */
object M3eBackgroundStyle {
    const val BLACK = 0
    const val AMBIENT = 1
    const val COVER_BLUR = 2
}

/**
 * m3e 播放页样式（2026-10-17 用户口径「当在 m3e 主题选择取色或者纯黑背景时，播放器样式采用
 * https://github.com/google/horologist，当选择背景取色时在主题中给出一个播放器样式的栏目」）。
 *
 * 只影响 m3e 主题的播放页（PLAYER 一条路由），且**只在背景样式为纯黑/取色时生效**：
 *  · [HOROLOGIST] 默认 —— 照 google/horologist 的 media/ui-material3 PlayerScreen 三段式：
 *    顶部歌名/艺人文字（不显示封面）、中部三控制键（中间大播放暂停 + 歌曲进度环）、
 *    底部「喜欢 / 音量 / 更多」次级按钮行；
 *  · [LEGACY] —— 原有 m3e 播放页（封面大按钮 + 右缘进度弧 + 底部弧形胶囊），一个像素不动。
 * 背景样式 = [M3eBackgroundStyle.COVER_BLUR] 时两档都不生效：封面模糊播放页保持原样。
 */
object M3ePlayerStyle {
    const val HOROLOGIST = 0
    const val LEGACY = 1
}

/** 外观偏好（SharedPreferences 持久化 + Compose 状态同步） */
object AppearancePrefs {
    /** Independent interface style: never rewrites classic appearance keys. */
    private var _interfaceStyle by mutableStateOf("classic")
    val interfaceStyle: String get() = _interfaceStyle
    val isM3e: Boolean get() = _interfaceStyle == "m3e"
    fun setInterfaceStyle(value: String) {
        val safe = if (value == "m3e") "m3e" else "classic"
        if (safe == "m3e" && ::sp.isInitialized && !sp.getBoolean("m3e_player_defaults_applied", false)) {
            // Only initialize once; later theme switches preserve the user's choices.
            setM3eBackgroundStyle(M3eBackgroundStyle.AMBIENT)
            setM3eBackgroundPlayerOnly(true)
            setDynamicColor(true)
            sp.edit().putBoolean("m3e_player_defaults_applied", true).apply()
        }
        _interfaceStyle = safe
        if (::sp.isInitialized) sp.edit().putString("interface_style", safe).apply()
        CrashLogger.step("theme → interface=$safe")
    }

    private var _m3eBackgroundStyle by mutableStateOf(M3eBackgroundStyle.BLACK)
    val m3eBackgroundStyle: Int get() = _m3eBackgroundStyle
    fun setM3eBackgroundStyle(value: Int) {
        val safe = value.coerceIn(M3eBackgroundStyle.BLACK, M3eBackgroundStyle.COVER_BLUR)
        _m3eBackgroundStyle = safe
        if (::sp.isInitialized) sp.edit().putInt("m3e_background_style", safe).apply()
        CrashLogger.step("theme → m3e background=$safe")
    }

    private var _m3eBackgroundPlayerOnly by mutableStateOf(false)
    val m3eBackgroundPlayerOnly: Boolean get() = _m3eBackgroundPlayerOnly
    fun setM3eBackgroundPlayerOnly(value: Boolean) {
        _m3eBackgroundPlayerOnly = value
        if (::sp.isInitialized) sp.edit().putBoolean("m3e_background_player_only", value).apply()
    }

    // ---- m3e 播放页样式（2026-10-17 新增；同日第三轮加「显式选择」语义）----

    /**
     * m3e 播放页样式（[M3ePlayerStyle.HOROLOGIST] / [M3ePlayerStyle.LEGACY]），
     * sp key = `m3e_player_style`。
     *
     * ★ 2026-10-17 第三轮用户口径：该偏好现在**三种背景都能生效**（取色 / 纯黑 / 封面模糊），
     *   设置页栏目也扩到「取色 或 封面模糊」（纯黑维持固定 Horologist、无栏目）。
     *   是否「显式选过」由 sp 里有没有 `m3e_player_style` 这个 key 决定（见 [m3ePlayerStyleExplicit]）：
     *   · 显式选过 → 一律按用户选择；
     *   · 没选过 → 取色/纯黑默认 [M3ePlayerStyle.HOROLOGIST]，**封面模糊默认 [M3ePlayerStyle.LEGACY]**
     *     （保持第一轮「封面模糊保持原有 m3e 播放页」的口径）。
     */
    private var _m3ePlayerStyle by mutableStateOf(M3ePlayerStyle.HOROLOGIST)
    val m3ePlayerStyle: Int get() = _m3ePlayerStyle

    /**
     * 用户是否**显式**选过播放器样式（sp 里存在 `m3e_player_style`）。
     * 只影响「没选过时按背景样式给默认值」这一条；设置页据此显示默认规则说明。
     */
    private var _m3ePlayerStyleExplicit by mutableStateOf(false)
    val m3ePlayerStyleExplicit: Boolean get() = _m3ePlayerStyleExplicit

    fun setM3ePlayerStyle(value: Int) {
        val safe = value.coerceIn(M3ePlayerStyle.HOROLOGIST, M3ePlayerStyle.LEGACY)
        _m3ePlayerStyle = safe
        _m3ePlayerStyleExplicit = true
        if (::sp.isInitialized) sp.edit().putInt("m3e_player_style", safe).apply()
        CrashLogger.step("theme → m3e player=$safe")
    }

    /**
     * **当前生效**的播放页样式（设置页单选选中态、PlayerScreen 分支共用的唯一口径）：
     *  · 背景 = 封面模糊 → 显式选过就按用户选择，没选过默认原有样式；
     *  · **其它背景（取色 / 纯黑）→ 恒 Horologist**，忽略显式选择。
     *
     * ★ 2026-10-18 用户口径「m3e 主题除了封面模糊其他不允许使用原有样式播放器，
     *   现在在封面模糊里切换到原有样式之后再切回黑色背景会保留原有样式，修掉」：
     *   旧实现是「显式选过就一律按用户选择」，于是用户在封面模糊里选了原有样式后，
     *   切回纯黑 / 取色仍然沿用原有样式 —— 那两种背景的底色与原有样式的配色假设不一致
     *   （原有样式自带一层取色底 + 自己的排版），观感是「黑底上浮着一个别的播放器」。
     *   现在非封面模糊一律强制 Horologist；用户的显式选择**保留在 sp 里不删**，
     *   下次切回封面模糊仍然生效（选择不丢，只是不在别的背景下越权）。
     */
    val m3ePlayerStyleEffective: Int
        get() = when {
            _m3eBackgroundStyle != M3eBackgroundStyle.COVER_BLUR -> M3ePlayerStyle.HOROLOGIST
            _m3ePlayerStyleExplicit -> _m3ePlayerStyle
            else -> M3ePlayerStyle.LEGACY
        }

    /**
     * 当前 m3e 播放页是否走 Horologist 样式（**唯一判定口径**，PlayerScreen 与 M3eSettings 共用）。
     * = m3e 主题 && 生效样式 == HOROLOGIST（生效规则见 [m3ePlayerStyleEffective]）。
     *
     * ★ 2026-10-17 第三轮：封面模糊背景不再强制原有样式 —— 用户在设置页显式选 Horologist 时，
     *   封面模糊背景同样走 Horologist（背景层仍由 NcmBackground 画封面模糊）；
     *   没显式选时封面模糊仍默认原有样式（与第一轮口径一致，升级用户观感不变）。
     * ★ 纯黑背景维持固定 Horologist（无栏目可切）：显式选择对它同样生效，但设置页不显示入口。
     */
    val m3eHorologistPlayer: Boolean
        get() = isM3e && m3ePlayerStyleEffective == M3ePlayerStyle.HOROLOGIST

    private lateinit var sp: SharedPreferences
    /** 默认种子色 = 网易云红（2026-10-03 起为柔化后的 #DA5353，与 PRESET_ACCENTS[0] 一致） */
    private const val DEF_ACCENT = 0xFFDA5353L
    /**
     * 默认外观模式。
     * 2026-10-03 用户口径「给默认主题改成浅色主题」：由 DARK 改为 LIGHT；
     * ★ 2026-10-10 用户口径「修改封面模糊主题为软件进入时默认主题」：再由 LIGHT 改为 **COVER_BLUR** ——
     * 只影响新装 / 从未在设置里切换过模式的用户；sp 里已显式存过模式的照旧读回。
     */
    private const val DEF_MODE = ThemeMode.COVER_BLUR

    // ---- 明暗模式 ----

    private var _mode by mutableStateOf(DEF_MODE)

    /** 明暗模式（ThemeMode.SYSTEM / DARK / LIGHT / COVER_BLUR） */
    val mode: Int
        get() = _mode

    /**
     * 系统当前是否深色。由 NcmTheme 每帧同步（SideEffect），
     * 初值在 [init] 里从 Configuration 读出，保证首帧就是对的。
     */
    var systemDark by mutableStateOf(true)

    /**
     * 当前实际是否浅色。跟随系统时解析为确定值，供颜色计算使用；
     * [ThemeMode.COVER_BLUR] 时解析为「当前封面偏亮？」（CoverBackdrop.autoLight
     * 是 derivedStateOf，只在整个布尔翻转时才通知读者 —— 换成同样亮的下一首
     * 不会引起全 App 重组）。
     */
    val isLight: Boolean
        get() = when (_mode) {
            ThemeMode.LIGHT -> true
            ThemeMode.DARK -> false
            ThemeMode.COVER_BLUR -> CoverBackdrop.autoLight
            else -> !systemDark
        }

    // ---- 动态取色（Material You，Android 12+）----

    /**
     * ★ 2026-10-02 用户口径「背景取色功能改为默认开启」：默认值由 false 改为 true。
     *
     * 为什么敢默认开：本机（Android 11）上这个开关的色源是**用户自己设的背景图主色**
     * （见 [BackgroundStore.seedColor]）；2026-10-02 起未设背景图时进一步回落**当前歌曲
     * 封面主色**（用户口径「无论是否设置壁纸都生效」，见 Theme.kt 的 EffectiveSeed）——
     * 与背景的环境色同一色源，界面整体协调；封面也没有时才回落预设种子色，永不空转。
     * 存量用户不受影响：sp 里已经显式存过 true/false 的，照旧读回自己的选择。
     */
    private const val DEF_DYNAMIC = true

    private var _dynamic by mutableStateOf(DEF_DYNAMIC)

    /** 是否跟随系统壁纸（Android 11 及以下：自定义背景图）动态取色 */
    val dynamicColor: Boolean
        get() = _dynamic

    /**
     * 动态取色是否**可用**（2026-10-01 起恒为 true）。
     *
     * 原实现要求 Android 12+（系统 Monet 取色），导致本机（OWW261 / Android 11）上
     * 开关是灰的、用户根本开不了，改为任何版本都能开。2026-10-02 用户口径
     * 「安卓 12 的系统壁纸效果删掉不要」后，所有版本统一走一条色源链：
     * 自定义背景图主色 → 歌曲封面主色 → 预设种子色（见 Theme.kt 的 EffectiveSeed）。
     */
    val dynamicSupported: Boolean
        get() = true

    // ---- 歌词扫光 ----

    /**
     * ★ 2026-10-02 用户口径「设置主题栏目中添加歌词扫光的开关」：默认开 ——
     * 歌词扫光是 1.0.2 版新增的聚焦行效果（见 LyricsScreen 的 drawLyricSweep），
     * 升级后保持与上一版一致的观感。关掉 = 聚焦行不再有移动白色光斑，
     * 但聚焦行提亮（深色主题的底噪，用户口径①「聚焦歌词提高亮度增加可读性」）仍然保留 ——
     * 那是独立于扫光的可读性改进，不该被同一个开关连带关掉。
     */
    private const val DEF_LYRIC_SWEEP = true

    private var _lyricSweep by mutableStateOf(DEF_LYRIC_SWEEP)

    /** 歌词页聚焦行的白色扫光是否开启 */
    val lyricSweep: Boolean
        get() = _lyricSweep

    fun setLyricSweep(v: Boolean) {
        _lyricSweep = v
        if (::sp.isInitialized) sp.edit().putBoolean("lyric_sweep", v).apply()
    }

    // 两个主题共用低性能模式；单独保存，不改写用户原有扫光偏好。
    private const val DEF_LOW_PERFORMANCE_LYRICS = false
    private var _lowPerformanceLyrics by mutableStateOf(DEF_LOW_PERFORMANCE_LYRICS)

    val lowPerformanceLyrics: Boolean
        get() = _lowPerformanceLyrics

    fun setLowPerformanceLyrics(v: Boolean) {
        _lowPerformanceLyrics = v
        if (::sp.isInitialized) sp.edit().putBoolean("low_performance_lyrics", v).apply()
    }

    // ---- 歌词页屏幕常亮 ----

    /**
     * ★ 2026-10-11 用户口径「设置页主题下方添加在歌词页常亮屏幕开关」：默认关 ——
     *   手表上常亮直接影响续航，且这是新增行为，升级后默认观感必须不变（屏幕照旧
     *   按系统息屏时间熄灭）；想要「听歌看歌词不灭屏」的用户手动打开。
     *   生效点在歌词页（LyricsScreen 的 DisposableEffect 挂/清 FLAG_KEEP_SCREEN_ON），
     *   作用域只有歌词页，离开立即恢复系统息屏节奏。
     */
    private const val DEF_LYRIC_KEEP_SCREEN_ON = false

    private var _lyricKeepScreenOn by mutableStateOf(DEF_LYRIC_KEEP_SCREEN_ON)

    /** 停留在歌词页时屏幕是否保持常亮 */
    val lyricKeepScreenOn: Boolean
        get() = _lyricKeepScreenOn

    fun setLyricKeepScreenOn(v: Boolean) {
        _lyricKeepScreenOn = v
        if (::sp.isInitialized) sp.edit().putBoolean("lyric_keep_screen_on", v).apply()
    }

    // ---- 字体大小 / 字体粗细 ----

    /**
     * ★ 2026-10-03 用户口径「设置主题里增加字体大小调整滑块以及字体粗细调整滑块」。
     *
     * 两者都是 **NcmTheme 层的全局覆写**（不逐个改 Text 调用点）：
     *  · 字体大小 → 覆写 LocalDensity.fontScale（所有 sp 文本一处生效，见 Theme.kt）；
     *  · 字体粗细 → 包装 FontFamily.Resolver 平移字重（见 ui/theme/FontWeightShift.kt）。
     * 取值域与滑块档位一一对应（滑块在 ThemeSettingsScreen）：
     *  · fontScale 0.8~1.3，步进 0.1，默认 1.0（相对全局 106% 基准）；
     *  · fontWeightLevel 0~3 为 ui.theme.FONT_WEIGHT_SHIFTS 的索引
     *    （细/标准/加粗/特粗），默认 1（标准）。
     */
    private const val DEF_FONT_SCALE = 1f

    private var _fontScale by mutableStateOf(DEF_FONT_SCALE)

    /** 字体缩放倍数（乘在系统 fontScale 上；0.8~1.3） */
    val fontScale: Float
        get() = _fontScale

    private const val DEF_FONT_WEIGHT_LEVEL = 1

    private var _fontWeightLevel by mutableStateOf(DEF_FONT_WEIGHT_LEVEL)

    /** 字体粗细档位索引（0 细 / 1 标准 / 2 加粗 / 3 特粗） */
    val fontWeightLevel: Int
        get() = _fontWeightLevel

    fun setFontScale(v: Float) {
        val c = v.coerceIn(0.8f, 1.3f)
        _fontScale = c
        if (::sp.isInitialized) sp.edit().putFloat("font_scale", c).apply()
    }

    fun setFontWeightLevel(v: Int) {
        val c = v.coerceIn(0, 3)
        _fontWeightLevel = c
        if (::sp.isInitialized) sp.edit().putInt("font_weight_level", c).apply()
    }

    // ---- 播放页面风格 ----

    /**
     * 默认风格 = [PlayPageStyle.AMBIENT]（取色）—— 与 2026-09-13 起的默认观感一致，
     * 升级后不改变任何存量用户看到的画面。
     */
    private const val DEF_PLAY_STYLE = PlayPageStyle.AMBIENT

    private var _playStyle by mutableStateOf(DEF_PLAY_STYLE)

    /**
     * 生效的播放页风格。
     * 存量防御：用户选了「自定义」后又把壁纸清掉（恢复默认背景），
     * 此时若还按自定义走，播放器组会透出一个空背景层 —— 回落 [PlayPageStyle.AMBIENT]，
     * 下次进设置页时选中态也显示为「取色」，与实际观感一致。
     */
    val playStyle: Int
        get() = if (_playStyle == PlayPageStyle.CUSTOM && !BackgroundStore.ready) {
            PlayPageStyle.AMBIENT
        } else {
            _playStyle
        }

    /** 播放器组三页（播放页/歌词页/更多页）是否透出自定义壁纸（= 自定义风格） */
    val playShowsWallpaper: Boolean
        get() = !isM3e && playStyle == PlayPageStyle.CUSTOM

    /** 封面模糊风格是否生效（背景封面模糊 + 逐字歌词 + 报告 §五 行模糊梯队的唯一开关） */
    val coverBlurStyle: Boolean
        get() = if (isM3e) _m3eBackgroundStyle == M3eBackgroundStyle.COVER_BLUR
        else playStyle == PlayPageStyle.COVER_BLUR

    /** 播放页是否显示「自定义」选项：只有设了壁纸才有意义 */
    val playStyleCustomVisible: Boolean
        get() = BackgroundStore.ready

    fun setPlayStyle(v: Int) {
        // 没有壁纸时不落盘「自定义」：避免存下一个当场不生效、装上壁纸后又突然变化的悬空值
        val c = if (v == PlayPageStyle.CUSTOM && !BackgroundStore.ready) {
            PlayPageStyle.AMBIENT
        } else {
            v.coerceIn(PlayPageStyle.AMBIENT, PlayPageStyle.CUSTOM)
        }
        _playStyle = c
        if (::sp.isInitialized) sp.edit().putInt("play_style", c).apply()
    }

    /** 播放页面风格中文名（设置页副标题 / 选中态用） */
    fun playStyleLabel(): String = when (_playStyle) {
        PlayPageStyle.COVER_BLUR -> "封面模糊"
        PlayPageStyle.CUSTOM -> "自定义"
        else -> "取色"
    }

    // ---- 封面模糊明暗对调（2026-10-07 用户口径）----

    /**
     * 「使用浅色/深色封面模糊」开关（只在 播放页面风格 = 封面模糊 且外观模式为
     * 确定的深/浅 时出现）：
     *  · 深色外观下标签为「使用浅色封面模糊」—— 开 = 播放器组三页整体走浅色观感；
     *  · 浅色外观下标签为「使用深色封面模糊」—— 开 = 整体走深色观感；
     *  · 关（默认）= 跟随外观模式，与升级前观感完全一致。
     * 存的是「对调」语义（一个布尔），标签与含义按外观模式换算，
     * 这样切换外观模式后开关状态不用迁移、两边语义天然自洽。
     */
    private var _coverBlurInvert by mutableStateOf(false)

    /** 见 [setCoverBlurInvert]。默认 false = 关闭（封面模糊跟随外观模式）。 */
    val coverBlurInvert: Boolean
        get() = _coverBlurInvert

    fun setCoverBlurInvert(v: Boolean) {
        _coverBlurInvert = v
        if (::sp.isInitialized) sp.edit().putBoolean("cover_blur_invert", v).apply()
    }

    /**
     * 播放器组三页的明暗是否对调（= 全局明暗翻转的**生效值**）。
     * 条件：播放页面风格选了封面模糊 + 外观模式是确定的深/浅 ——
     * 「封面模糊」外观模式本来就逐封面自动明暗，没有可对调的方向。
     */
    val coverBlurToneFlip: Boolean
        get() = !isM3e && _coverBlurInvert && playStyle == PlayPageStyle.COVER_BLUR &&
            (_mode == ThemeMode.DARK || _mode == ThemeMode.LIGHT)

    // ---- 封面模糊外观（2026-10-07 用户口径「增加外观模式封面模糊」/「保留取色开关」）----

    /**
     * 「保留取色」开关（只在 外观模式 = 封面模糊 时出现；此时其余模式读到它也没有意义）。
     *
     * 用户口径原文：「打开时保留原有的取色机制，封面模糊只在播放页及其附属页生效，
     * 关闭后主页的背景同为封面模糊后播放页的背景」：
     *  · 关（默认，2026-10-07 用户在选项里确认）—— 主页等**外围页**（非播放器组）的
     *    背景也换成模糊封面（与播放页同一张底）；
     *  · 开 —— 外围页继续走原有的背景机制（自定义壁纸 / 歌曲环境色），
     *    模糊只在播放器组生效。
     *
     * ⚠ 播放器组三页不受本开关影响：用户确认「仍听播放页面风格」
     * （取色 / 封面模糊 / 自定义照旧各自生效）。
     */
    private var _coverKeepTint by mutableStateOf(false)

    /** 见 [setCoverKeepTint]。默认 false = 关闭（全背景模糊）。 */
    val coverKeepTint: Boolean
        get() = _coverKeepTint

    fun setCoverKeepTint(v: Boolean) {
        _coverKeepTint = v
        if (::sp.isInitialized) sp.edit().putBoolean("cover_keep_tint", v).apply()
    }

    /**
     * 「封面模糊外观」下外围页（非播放器组）是否由全局背景层绘制模糊封面。
     * = NcmBackground.coverBlurGlobal 的核心条件（那边再叠「当前不在播放器组」）。
     * ★ 2026-10-07 也供头图判定渐隐分支（BlurHeader / SharpCoverHeader / LikedCoverHeader）：
     *   这些头图的「纯色模式」渐变终点是 **不透明** 的歌曲环境色，在模糊封面底上会露出
     *   一道硬边（深色封面下就是黑边，用户口径「渐变头图有黑边」）——此开关打开时
     *   头图必须改走「渐隐到透明」分支，让底下的模糊封面透出来。
     */
    val coverBlurOuter: Boolean
        get() = if (isM3e) _m3eBackgroundStyle == M3eBackgroundStyle.COVER_BLUR && !_m3eBackgroundPlayerOnly
        else _mode == ThemeMode.COVER_BLUR && !_coverKeepTint

    // ---- 种子色 ----

    // 私有后备状态：ui.theme.Accent 是它的公开读取口，换色即全局重组
    private var _accent by mutableStateOf(Color(DEF_ACCENT.toInt()))

    /** 当前全局主题色（所有页面的强调色 / M3 种子色） */
    val accent: Color
        get() = _accent

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        _interfaceStyle = if (sp.getString("interface_style", "classic") == "m3e") "m3e" else "classic"
        _m3eBackgroundStyle = sp.getInt("m3e_background_style", M3eBackgroundStyle.BLACK)
            .coerceIn(M3eBackgroundStyle.BLACK, M3eBackgroundStyle.COVER_BLUR)
        _m3eBackgroundPlayerOnly = sp.getBoolean("m3e_background_player_only", false)
        // m3e 播放页样式（2026-10-17 新增）：是否显式选择 = sp 里是否存过这个 key；
        // 没存过时「生效样式」按背景样式给默认（封面模糊 → 原有样式，取色/纯黑 → Horologist），
        // 见 m3ePlayerStyleEffective。迁移：v2 用户改过样式才有 key，天然进入显式分支。
        _m3ePlayerStyleExplicit = sp.contains("m3e_player_style")
        _m3ePlayerStyle = sp.getInt("m3e_player_style", M3ePlayerStyle.HOROLOGIST)
            .coerceIn(M3ePlayerStyle.HOROLOGIST, M3ePlayerStyle.LEGACY)
        // 存量迁移：旧高饱和预设值 → 新柔化值（见 LEGACY_PRESET_MIGRATION，其余原样保留）
        val storedAccent = sp.getLong("accent", DEF_ACCENT).toInt()
        _accent = Color(LEGACY_PRESET_MIGRATION[storedAccent] ?: storedAccent)
        _mode = sp.getInt("theme_mode", DEF_MODE).let {
            // 存量迁移：「跟随系统」(0) 已从设置页移除 → 转成恒深色。
            // 手表系统本就恒深色，迁移前后观感完全一致，用户无感。
            if (it == ThemeMode.SYSTEM) ThemeMode.DARK else it
        }
        _dynamic = sp.getBoolean("dynamic_color", DEF_DYNAMIC)
        _lyricSweep = sp.getBoolean("lyric_sweep", DEF_LYRIC_SWEEP)
        _lowPerformanceLyrics = sp.getBoolean("low_performance_lyrics", DEF_LOW_PERFORMANCE_LYRICS)
        // 歌词页屏幕常亮（2026-10-11 新增）：无存量值 = 关（升级后默认观感不变）
        _lyricKeepScreenOn = sp.getBoolean("lyric_keep_screen_on", DEF_LYRIC_KEEP_SCREEN_ON)
        _fontScale = sp.getFloat("font_scale", DEF_FONT_SCALE)
        _fontWeightLevel = sp.getInt("font_weight_level", DEF_FONT_WEIGHT_LEVEL)
        // 「保留取色」（仅封面模糊外观下生效）：无存量值 = 关（全背景模糊，用户确认的默认）
        _coverKeepTint = sp.getBoolean("cover_keep_tint", false)
        // 封面模糊明暗对调：无存量值 = 关（跟随外观模式，升级后观感不变）
        _coverBlurInvert = sp.getBoolean("cover_blur_invert", false)
        // 播放页面风格（2026-10-06 新增）：无存量值时从旧开关「播放页/歌词页保留取色」迁移 ——
        // keepAmbient=true（默认）→ 取色；关掉且已设壁纸 → 播放器组透出壁纸 = 自定义。
        // 必须在 BackgroundStore.init 之后调用（见 MainActivity.onCreate 的顺序），否则 ready 还是 false。
        val legacyStyle = when {
            // ★ 2026-10-10：默认外观模式 = 封面模糊（见 DEF_MODE）→ 播放页风格默认同样落到封面模糊
            _mode == ThemeMode.COVER_BLUR -> PlayPageStyle.COVER_BLUR
            BackgroundStore.ready && !BackgroundStore.keepAmbient -> PlayPageStyle.CUSTOM
            else -> PlayPageStyle.AMBIENT
        }
        _playStyle = sp.getInt("play_style", legacyStyle)
            .coerceIn(PlayPageStyle.AMBIENT, PlayPageStyle.CUSTOM)
        // 首帧正确性：跟随系统模式必须在第一次组合前就知道系统深浅，
        // 否则 NcmTheme 的 SideEffect 晚一步，会先闪一帧深色。
        systemDark = (ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        CrashLogger.step(
            "appearance ready: interface=$_interfaceStyle mode=$_mode " +
                "m3eBg=$_m3eBackgroundStyle m3ePlayer=$m3ePlayerStyleEffective " +
                "explicit=$_m3ePlayerStyleExplicit"
        )
    }

    fun setAccent(c: Color) {
        _accent = c
        if (::sp.isInitialized) {
            sp.edit().putLong("accent", c.toArgb().toLong() and 0xFFFFFFFFL).apply()
        }
    }

    fun setMode(m: Int) {
        _mode = m
        if (::sp.isInitialized) sp.edit().putInt("theme_mode", m).apply()
        CrashLogger.step("theme → mode=$m")
        // ★ 2026-10-10 用户口径「选择封面模糊主题时播放器主题默认为封面模糊主题」：
        //   切到封面模糊外观时，播放页面风格一并落到封面模糊。
        //   只在**选中该外观的那一刻**联动 —— 之后用户仍可单独把播放页风格改回取色/自定义。
        if (m == ThemeMode.COVER_BLUR) setPlayStyle(PlayPageStyle.COVER_BLUR)
    }

    fun setDynamicColor(v: Boolean) {
        // 不支持动态取色的机型上不落盘，避免换机后出现「开了但没生效」的悬空状态
        if (v && !dynamicSupported) return
        _dynamic = v
        if (::sp.isInitialized) sp.edit().putBoolean("dynamic_color", v).apply()
    }

    /**
     * 动态取色当前**实际生效的色源**说明（设置页副标题）。
     * Android 11 上这个开关取的是自定义背景图，副标题必须写清来源 ——
     * 否则用户开了开关却没设背景图，只会以为功能坏了。
     *
     * ★ 2026-10-07：[wallpaperLive] = 壁纸**真的在画**（ui 层 wallpaperShown()，
     *   数据层不能反向依赖 ui 层，所以由调用方传入）。封面模糊顶掉壁纸（全背景封面模糊）时，
     *   色源已经切到歌曲封面（EffectiveSeed 同判据），文案必须如实说，不能还写「自定义背景图」。
     */
    fun dynamicSourceLabel(wallpaperLive: Boolean): String = when {
        !_dynamic -> "关闭时使用下方预设种子色"
        wallpaperLive -> "已开启 · 配色取自定义背景图"
        else -> "已开启 · 配色跟随歌曲封面主色"
    }

    /** 明暗模式中文名（设置页副标题用） */
    fun modeLabel(): String = when (_mode) {
        ThemeMode.LIGHT -> "浅色"
        ThemeMode.DARK -> "深色"
        ThemeMode.COVER_BLUR -> "封面模糊"
        else -> "跟随系统"
    }

    /** 当前种子色中文名；自定义色（非预设）返回「自定义」 */
    fun accentLabel(): String {
        val i = PRESET_ACCENTS.indexOfFirst { it == _accent }
        return if (i >= 0) PRESET_ACCENT_NAMES[i] else "自定义"
    }
}
