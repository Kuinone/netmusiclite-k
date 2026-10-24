package com.netmusiclite.ui.screens.player

import com.netmusiclite.ui.nav.Routes



import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import com.netmusiclite.data.AppearancePrefs
import com.netmusiclite.data.BackgroundStore
import com.netmusiclite.data.DownloadStore
import com.netmusiclite.data.LocalLyricMatch
import com.netmusiclite.data.LocalLyricStore
import com.netmusiclite.data.LyricLine
import com.netmusiclite.data.LyricWord
import com.netmusiclite.data.NcmApi
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.ui.components.CircularSideProgress
import com.netmusiclite.ui.components.SongAmbient
import com.netmusiclite.ui.components.SwipeBackFader
import com.netmusiclite.ui.components.ambientSurfaceColor
import com.netmusiclite.ui.components.findActivity
import com.netmusiclite.ui.components.rotaryList
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.Bg
import com.netmusiclite.ui.theme.PlayAccent
import com.netmusiclite.ui.theme.PlaySongAccent
import com.netmusiclite.ui.theme.PlayTextPrimary
import com.netmusiclite.ui.theme.PlayTextSecondary
import com.netmusiclite.ui.theme.PlayToneScope
import com.netmusiclite.ui.theme.lyricInk
import com.netmusiclite.ui.theme.onColorFor
import com.netmusiclite.ui.theme.playLightTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val LRC_REGEX = Regex("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")

/**
 * 歌词切换曲线（2026-09-30，从参考录屏逐帧反解，非拍脑袋值）
 *
 * 反解方法：对参考录屏逐帧在歌词区做 1D 竖直互相关求位移，得到「位移—时间」曲线；
 * 取 4 次切换的均值曲线拟合三次贝塞尔 —— RMSE 0.015，优于最好的内置缓动
 * （FastOutSlowIn 0.062 / easeOutQuad 0.072）约 4 倍，且 4 次样本标准差极小
 * （中段最大 ±0.054），说明行为高度一致、不是噪声拟合。
 *
 * 曲线特征（归一化进度 vs 归一化时长）：
 *     15% 时长 →  8.6%   软起步（前 150ms 几乎不动，先蓄势）
 *     20% 时长 → 22.0%
 *     30% 时长 → 51.2%   爆发段：15% 的时长走完 43% 的位移
 *     40% 时长 → 70.6%
 *     60% 时长 → 88.0%
 *     80% 时长 → 95.9%   长尾滑行：最后 20% 时长只走 4%
 *     100%     → 100%    全程严格单调、零过冲（y'(t) 系数恒正，已解析验证）
 *
 * 时长 630ms 取自 4 次切换实测中位数（584/647/615/644）；位移恒为 1 个行距。
 */
private val LyricGlide = CubicBezierEasing(0.31f, 0.02f, 0.15f, 0.98f)
private const val LYRIC_GLIDE_MS = 630

/**
 * 明亮度（2026-10-01 六轮，用户口径「歌词聚焦切换时明亮度要加渐变效果」）：
 *
 *     d = 0     → 1.00   焦点行（纯白最大）
 *     d = 1     → 0.48   相邻行（延续「非聚焦 -27%」的口径，即 0.66 × 0.73）
 *     d = 1 → 5   →  0.48 → 0.36 随距离平滑递减（smoothstep）
 *     d ≥ 5     → 0.36   渐变下限（仍可读；之前一轮曾到 α≈0.13，圆表上读不清，不能再去）
 *
 * 为什么改：2026-09-30 那次用「FLOOR 一刀切」把非聚焦行统一成同一档（当时是为了修
 * 远处行太暗的可读性 bug），结果**整屏只剩焦点行和它相邻那一行在变明暗**，
 * 参考界面「整屏像一块竖直的透明度渐变板、随歌词一起平移」的核心观感就没了。
 * 现在改成随距离连续衰减：既是用户要的渐变，也让**每次切换时整屏每一行的亮度都在动**
 * （每行的 d 都平移了 1），渐变感是整屏的，不是只有两行在对调。
 */
private const val LYRIC_DIM_NEAR = 0.48f   // d=1（相邻行）
private const val LYRIC_DIM_FAR = 0.36f    // 渐变下限
private const val LYRIC_DIM_SPAN = 4f      // 从 d=1 衰减到 d=5

private fun lyricBrightness(distance: Float): Float {
    val d = abs(distance)
    if (d <= 0.001f) return 1f
    if (d <= 1f) return 1f + (LYRIC_DIM_NEAR - 1f) * d
    val t = ((d - 1f) / LYRIC_DIM_SPAN).coerceIn(0f, 1f)
    val s = t * t * (3f - 2f * t)
    return LYRIC_DIM_NEAR + (LYRIC_DIM_FAR - LYRIC_DIM_NEAR) * s
}

/**
 * 缩放**只在可读带之外**生效（2026-10-01 四轮）。
 *
 * 为什么必须这样切：**给文字图层加一个非整数的 scale，会带来亚像素位移**。
 * 原理：图层带 scale 时，硬件渲染要把内容画进一块离屏贴图再按变换贴回来，而贴图的
 * 边界是按变换后的（小数）范围取的 —— 采样栅格与屏幕像素栅格错开约半个像素，字就"偏"了。
 * 一行歌词的 scale 从 0.94 连续长到 1.0 的过程中，这个错位一直在变；等到它成为当前行、
 * scale 恰好落到 1.0（恒等变换）时，硬件改走直接绘制 —— **这一帧会看到一次离散的亚像素
 * 归位**，就是用户反复报的「切换歌词有轻微像素偏移」。
 *
 * 所以：**用户正在读的那一带（当前行 ±2 行）永远保持 scale = 1.0 的恒等变换，像素级精确**；
 * 缩放层级只在再往外的行上体现 —— 那一带本来就被 12.5dp 高斯模糊糊掉、透明度也只有 0.48，
 * 尺寸层级在那里才有意义（有景深、又不会被看清像素）。
 * 用 smoothstep 衔接，两端一阶连续，不会在阈值处出现折角。
 *
 * 副作用（正向）：自动推进时，即将成为当前行的那一行从 |d|=1 一路到 0 都恒为 1.0，
 * 中途**完全不缩放**；上一行也恒为 1.0 —— 可读带里一次尺寸变化都不会发生。
 */
private const val LYRIC_SCALE_INNER = 2f    // |d| ≤ 2：恒等变换，不缩放
private const val LYRIC_SCALE_OUTER = 4.5f  // |d| ≥ 4.5：收到缩放下限
private const val LYRIC_SCALE_DEPTH = 0.125f // 缩放下限 = 1 - 0.125 = 0.875

/** 0 = 不缩放（恒等），1 = 缩到下限。可读带内恒为 0，避免任何亚像素位移 */
private fun lyricScaleAmount(distance: Float): Float {
    val d = abs(distance)
    if (d <= LYRIC_SCALE_INNER) return 0f
    if (d >= LYRIC_SCALE_OUTER) return 1f
    val t = (d - LYRIC_SCALE_INNER) / (LYRIC_SCALE_OUTER - LYRIC_SCALE_INNER)
    return t * t * (3f - 2f * t) // smoothstep
}

/**
 * 行间距拉伸回弹（2026-09-30，同源反解）
 *
 * 参考界面在每次切换时，整列歌词行距会先被拉开、再弹回原位。用「自相关求主导空间周期」
 * 量化（该估计量与行的身份无关，不受行滚动/进出屏造成的误匹配干扰）：
 *     基线行距            117.03 px
 *     帧 77-83（+150ms）  120.4 px  +2.9%   ← 预拉
 *     帧 86（+190ms）     129.42 px +10.59% ← 峰值
 *     帧 89               124.8 px  +6.7%
 *     帧 94               119.7 px  +2.3%
 *     帧 114（+740ms）    117.0 px   0      ← 回弹完成
 * 即：约 190ms 拉到峰值 +10.5%，再用约 470ms 长尾回弹到 0，全程与滚动共用同一缓动。
 *
 * 实现要点：拉伸必须只拉「间距」，不能拉字形。因此不用 scaleY，而是给每行加一个
 * translationY = 距锚定行的距离 × 行高 × 拉伸量 —— 各行以当前行为中心向外推开／收拢，
 * 字形尺寸完全不变，且仍是纯渲染层操作（零重排版）。
 */
private const val LYRIC_STRETCH_PEAK = 0.105f
private const val LYRIC_STRETCH_ATTACK_MS = 190
private const val LYRIC_STRETCH_RELEASE_MS = 470

/**
 * 锚点扫掠的行数上限（2026-10-01 三轮）。
 *
 * 正常切换（自动推进、点相邻行、点击屏幕上任意一行歌词）跨越 1~6 行，锚点用 630ms 扫过这
 * 几行正好是参考界面「明暗整片滑过去」的观感，必须保留。
 *
 * 但**瞬移**（换歌后归位、快进到远处、手动滚完 3.5s 后的自动跟随）可能一次跨几十行：
 * 让锚点扫过整段，等于把沿途几十行的缩放 + 拉伸各演一遍，同时几十行的渲染图层逐帧改变换，
 * 与「双份歌词列表 + 全屏高斯模糊」叠加起来就是掉帧。
 * 超过这个距离改为**直接落位**：高亮立刻到目标行、拉伸归零，位移完全交给列表滚动 ——
 * 滚动期间每行的缩放/明暗都是常量，零逐帧变换。
 */
private const val LYRIC_ANCHOR_SNAP_LINES = 8f

/**
 * 歌词区上下边缘的**纯渐变淡出带**（2026-10-01 新增，★ 2026-10-16 去掉模糊、只留渐变）：
 *  - 距屏幕边框 16 像素内 = 淡出核心（用户口径）
 *  - 核心之外再延伸一段，做「透明 → 清晰」的渐变过渡
 *
 * ★ 2026-10-16（用户口径「在 WearOS 表上存在模糊范围异常，歌词出现高斯模糊，开全屏 AOD
 *   之后播放页也会存在模糊，目前只在三星表发现」）：
 *   原实现是**两份完整歌词列表** —— 底层 `.blur(12.5.dp)`（GPU RenderEffect）、上层清晰列表
 *   用 `CompositingStrategy.Offscreen` + `BlendMode.DstIn` 把上下边缘擦掉，露出底层模糊。
 *   这套结构只在 **API 31+** 真生效（本机 ColorOS Watch 是 API 30，`.blur()` 一直是空操作，
 *   所以从来没暴露过问题）；三星 One UI Watch（API 31+）上 RenderEffect 的模糊范围与离屏
 *   合成不按节点边界工作，擦除带失效 ⇒ 模糊层整屏透出 = 「歌词整屏发糊」；残留的
 *   RenderEffect 图层还会被系统 AOD 的整窗合成一起带走 = 「开全屏 AOD 后播放页也糊」。
 *
 *   现在**整层模糊与第二份列表一起删除**，只保留上层那条垂直渐变（DstIn）把歌词本身在
 *   上下边缘淡出 —— 观感是「边缘渐隐」而不是「边缘模糊」，但零 GPU 特效、零机型差异。
 *   顺带消灭一个历史大坑：两层列表靠「条目下标」同步，条目表必须逐项对齐，否则整列错位
 *   （2026-10-05「本地歌曲歌词显示两层」）。只剩一层，这个不变量不复存在。
 *
 * ★ 2026-10-01 二轮口径沿用：手动浏览（表冠/拖动，userScrolling）时淡出量归零，
 *   边缘恢复全清晰，用户能看清下方还没唱到的歌词；3.5s 无操作后自动复位。
 */
private const val LYRIC_EDGE_CORE_PX = 16f
private val LYRIC_EDGE_SPREAD = 55.dp

/**
 * ★ 2026-10-02 用户口径（三条）：
 *   ① 「歌词界面聚焦的歌词提高一些亮度，增加可读性」
 *   ② 「增加白色光线扫过聚焦歌词的效果（尽量同步该句速度）」
 *   ③ 「扫光显示区域仅在歌词字体上面」（同日追加）——
 *      首版除字形层外还有一层铺在「材质」上的背景光晕（参考图底栏那团白光），
 *      实机看过后用户不要它：光必须只出现在字形像素上，字与字之间、行的底色上
 *      不能留光。所以背景光晕层整体删除，最终只剩字形遮罩层。
 *
 * ── 为什么必须做成「绘制阶段的遮罩」而不是改文字颜色 ────────────────────────
 * 本页有三条已经被反复踩出来的硬约束（见 LyricRow 的注释），一条都不能破：
 *   · 不能新增 Text 节点          → 否则文本要测量/排版第二遍；
 *   · 不能改 Text 的颜色          → 否则 Paragraph 缓存整体失效，等价每帧重排版；
 *   · 不能在组合期读逐帧动画值    → 否则整页每帧重组。
 * 所以扫光整个发生在**绘制阶段**（LyricRow 的 drawWithContent 里），单层：
 *   **字形提亮 + 扫光**（画在文字之上）：把本行内容画进离屏层 → `SrcIn` 压成光色剪影
 *   → `DstIn` 用「底噪 + 移动径向光斑」遮罩 → `restore()` 叠回原文之上。
 *   `SrcIn` 只染**非透明像素** ⇒ 光天然只落在字形上 —— 这正是用户口径③的实现方式；
 *   遮罩的**底噪**（[LYRIC_SWEEP_BASE]）= 聚焦行整体提亮（用户口径①）；
 *   遮罩的**移动峰值**（[LYRIC_SWEEP_PEAK]）= 白色光线扫过（用户口径②）。
 * 位置与强度每帧从 lambda 延迟读取 ⇒ 零重组合、零重排版、零新增布局节点。
 * 非聚焦行（|d| 超阈值）在绘制入口就 return，一次多余绘制都不会发生。
 */
/** 扫光只作用于焦点行附近（|d| ≤ 该值），并按距离线性淡出 —— 与 anchorPos 共用同一根时间轴 */
private const val LYRIC_SWEEP_BAND = 0.9f
/** 遮罩底噪 = 聚焦行整体提亮量（用户口径①） */
private const val LYRIC_SWEEP_BASE = 0.30f
/** 遮罩峰值 = 光斑扫过处的提亮量（用户口径②） */
private const val LYRIC_SWEEP_PEAK = 0.82f
/** 光斑半径 = 行宽 × 该系数（≈ 行宽 42%，与参考图底栏那团光晕的尺度接近） */
private const val LYRIC_SWEEP_RADIUS = 0.42f
/** 某句没有下一句时（最后一句）的扫光时长兜底 */
private const val LYRIC_SWEEP_FALLBACK_MS = 4000f
/** 扫光时长夹取：太短的句子（<0.6s）光斑会闪成一条线，太长（>12s）慢到看不出在动 */
private const val LYRIC_SWEEP_MIN_MS = 600f
private const val LYRIC_SWEEP_MAX_MS = 12000f

/**
 * 聚焦行亮度压暗量（★ 2026-10-18 用户口径「当前聚焦歌词亮度过高给我降低百分之十
 * （只在背景为浅色触发）」）。
 *
 * 0.10 = 字形像素亮度 ×0.9。只作用于**聚焦行**（强度随 anchorPos 连续过渡，见 drawFocusDim），
 * 且只在本页底为浅色时打开 —— 深底上亮字是正确观感，不该压。
 *
 * 「背景为浅色」怎么判：用**屏幕实际看到的底**（封面模糊按归一化后的标定灰、
 * 壁纸按压暗层混合后的色），既不用主题开关、也不用封面原始主色 —— 后者在
 * 「浅封面 + 深色封面模糊底」的组合上会误判成浅底，把 10% 压暗落在本就半透明的
 * 未唱字上（用户反馈的「聚焦歌词发黑看不清」）。推导见页面层 backdropProbe。
 */
private const val LYRIC_FOCUS_DIM = 0.10f
/** 判定「浅底」的亮度阈值（0..1，线性亮度；0.5 ≈ 中灰） */
private const val LIGHT_BACKDROP_LUM = 0.5f

// ── 字形光晕（2026-10-02 方案 1，二代：聚焦行专属 + 渐变出现）────────────────
/**
 * 用户 bug「浅色模式开启自定义背景取色后歌词可见性不足」的补足手段：透底场景
 * （自定义背景图垫在歌词后面）给**焦点行**字形垫一圈反向柔光 —— 深墨配白晕、浅墨配黑晕。
 *
 * 为什么取色本身救不了（实测数据）：底色是**图像**时，任何"对单点底色标定"的对比度
 * 都管不住局部 —— 同一屏上墨色 Y≈75，壁纸暗部 Y≈86（1.2:1）、中间调 Y≈148（2.9:1）、
 * 亮部 Y≈238（5.2:1）。把取色重标到暗部会让亮部发灰，反之暗部依旧埋掉。
 * 光晕等于给字形压一块**局部底色**：白晕 0.62 叠在 Y86 暗部上合成 Y≈191，
 * 与墨色对比 ≈ 4.7:1（亮部 ≈ 8.3:1，中间调 ≈ 6:1）—— 任意局部背景都过 AA 大字线。
 *
 * 自掩蔽特性：白晕叠在亮部上几乎不可见、黑晕叠在暗部上几乎不可见，
 * 光晕只在"需要它的地方"显形 —— 这是选它而不是加磨砂条带的另一个原因。
 *
 * 作用域仅「浅色主题 + 自定义背景图垫在歌词后面」（isLightTheme && ready && !keepAmbient）：
 * 环境色模式底色是纯色，lyricInk 已按真实底色保证 4.5/3.2:1，不碰，避免字幕感外溢；
 * **深色主题不做**（2026-10-02 用户口径「深色模式下不要光晕效果」）。
 *
 * ★ 二代（用户口径「改为只有聚焦歌词存在光晕，而且光晕渐变出现」）：
 *   一代把 Shadow 挂在**所有行**的 TextStyle 上 —— 全屏行行带晕，字幕感重；而且
 *   shadow 是组合期常量，"渐变"若逐帧改 Shadow 会击穿 Paragraph 缓存（每帧重排版，
 *   本页铁律）。二代整条移到**绘制期**：强度按与焦点行的连续距离 d 逐帧求值
 *   （零重组、零重排版），静止时 |d| ∈ {0,1,2…} ⇒ 恰好只有聚焦那一行有晕；
 *   切换歌词时 d 连续滑动 0↔1 ⇒ 光晕跟着整列滑动渐变出现/消失。
 */
private const val LYRIC_HALO_ALPHA = 0.62f
/** 膨胀半径（px）：8 方向错位重画的膨胀量，等价于零偏移模糊的半径。≈1.25dp，与笔画同量级 */
private const val LYRIC_HALO_RADIUS = 2.5f
/** 光晕强度渐变带：|d| ≤ FROM 全强、≥ TO 归零，中间线性 —— 「渐变出现」的时长就是它 */
private const val LYRIC_HALO_FADE_FROM = 0.10f
private const val LYRIC_HALO_FADE_TO = 0.80f

/** 8 方向膨胀偏移（模长 [LYRIC_HALO_RADIUS]；1.77 ≈ 2.5/√2 —— 预计算，逐帧路径不做三角函数） */
private val LYRIC_HALO_RING: Array<Offset> = arrayOf(
    Offset(2.5f, 0f), Offset(1.77f, 1.77f), Offset(0f, 2.5f), Offset(-1.77f, 1.77f),
    Offset(-2.5f, 0f), Offset(-1.77f, -1.77f), Offset(0f, -2.5f), Offset(1.77f, -1.77f),
)

// ════════════════════════════════════════════════════════════════════════════
//  逐字数据只控制明暗；所有主题共用整行扫光
// ════════════════════════════════════════════════════════════════════════════
/*
 * 本段参数取自《歌词动画分析/分析报告.md》（Apple Music 安卓/macOS 逐帧逆解），
 * 有逐字数据时用于已唱/未唱明暗，所有主题均生效，与扫光开关独立。
 *
 * 逐字点亮（§一 / §四 / §八）：
 *    未唱词 0.65–0.70 白 → 唱到 1.0 白，单音节上升沿 0.17–0.35s（交叉淡化，非硬切）；
 *    不再给当前词单独辉光；扫光与无逐字数据的歌曲共用整行进度。
 *
 * ★ 2026-10-07 用户口径（本次改造）：「删除封面模糊特有的歌词动画，与封面取色的动画同步」。
 *   行切换的弹簧（cubic-bezier(0.3,1.08,0.42,1) × 820ms，过冲 1–2%）与 0.4s 预启动
 *   （原报告 §二/§八 方案）整体删除 —— 820ms 弹簧收尾时锚点回吐过冲的那一下，正好是
 *   用户报的「切换完成后刚静止出现一次像素偏移」的来源；且单独一套切换时长也违背
 *   「两个风格观感一致」的期望。现在封面模糊与取色共用同一套切换动画
 *   （LyricGlide 630ms / 零过冲 / 190+470 拉伸，与另一份参考录屏逐帧反解对齐的那套）。
 *   译文透明度仍保留各背景风格的设定。
 *   （原「行模糊梯队」已于 2026-10-16 整体删除，理由见下方常量区注释。）
 */
/**
 * 未唱词基态透明度 = **与下方未聚焦行同一档**（★ 2026-10-18 用户口径
 * 「聚焦歌词未到达进度时亮度透明度与下方歌词一致」）。
 *
 * 下方那一行（d = +1）的图层 alpha 是 `lyricBrightness(1) × (1 - 0.18)` = 0.48 × 0.82 ≈ 0.394，
 * 所以这里不能再取旧的 0.67 —— 那是「比下方亮一档」的老口径，正是用户说的"还没唱到就整句亮着"。
 * 已唱到的字仍然走 1.0（满亮），也就是「逐字效果亮度不变」。
 *
 * ★ 2026-10-18 二轮：0.394 只适用于**近黑环境色底**（它本来就是按那种底标定的，
 *   即便如此也只有 ~2.9:1，贴着小字号可读线）。中亮底上同一档叠出来会掉到 ~1.7:1
 *   （浅封面 + 封面模糊 = 用户反馈的整句发黑），所以页面层用 [karaokeDimFor]
 *   按**真实底色**验一遍，达不到 3:1 才往上抬：近黑底只微调，中亮底才显著抬高。
 */
private val LYRIC_KARAOKE_DIM: Float = lyricBrightness(1f) * 0.82f

/**
 * 聚焦行未唱字的最低对比度：15sp / W900 属 WCAG「大字号」，AA 线 = 3:1。
 * 不取 4.5 是因为封面模糊深色底（亮度 0.14）上亮墨**满亮**也只有 3.87:1 ——
 * 要求再高就等于禁止压暗未唱字，逐字明暗效果会整个消失。
 */
private const val LYRIC_UNSUNG_MIN_CONTRAST = 3.0f

/**
 * 未唱字的基态透明度：从 [LYRIC_KARAOKE_DIM] 起步往上找，直到
 * 「墨色 × alpha 叠在 [backdrop] 上」的对比度 ≥ [LYRIC_UNSUNG_MIN_CONTRAST]。
 *
 * 混合必须在 sRGB 通道上做（GPU 的 alpha 合成就是在这个空间），再用线性亮度算 WCAG；
 * 直接用线性亮度插值会高估可读性（实测 0.394 在 0.14 灰底上仍算出 2.1:1，实际只有 1.7:1）。
 *
 * 近黑底（歌曲环境色）原本就在 3:1 附近，最多抬 0.02-0.1；中亮底才会抬到 ~0.7-0.8。
 */
private fun karaokeDimFor(backdrop: Color, ink: Color): Float {
    if (unsungContrast(backdrop, ink, LYRIC_KARAOKE_DIM) >= LYRIC_UNSUNG_MIN_CONTRAST) {
        return LYRIC_KARAOKE_DIM
    }
    var alpha = LYRIC_KARAOKE_DIM
    while (alpha < 1f) {
        alpha = (alpha + 0.02f).coerceAtMost(1f)
        if (unsungContrast(backdrop, ink, alpha) >= LYRIC_UNSUNG_MIN_CONTRAST) return alpha
    }
    return 1f
}

/** 墨色按 [alpha] 叠在 [backdrop] 上之后的 WCAG 对比度（sRGB 混合，与 GPU 合成一致） */
private fun unsungContrast(backdrop: Color, ink: Color, alpha: Float): Float {
    val mixed = Color(
        red = ink.red * alpha + backdrop.red * (1f - alpha),
        green = ink.green * alpha + backdrop.green * (1f - alpha),
        blue = ink.blue * alpha + backdrop.blue * (1f - alpha),
    )
    val lm = mixed.luminance()
    val lb = backdrop.luminance()
    return (maxOf(lm, lb) + 0.05f) / (minOf(lm, lb) + 0.05f)
}

/** 罗马音/译文层透明度（§一「中文翻译层恒定 ≈0.50」） */
private const val LYRIC_SUB_ALPHA_COVER = 0.50f

/** 歌词行的上下内边距，与字符遮罩的竖直偏移保持一致。 */
private val LYRIC_ROW_PAD_V = 3.dp

/**
 * ★ 2026-10-16 删除「逐行模糊梯队（景深）」：
 *   原实现按与焦点行的距离分 5 档，在 `LyricRow` 的 graphicsLayer 里给每行挂一个
 *   `RenderEffect = BlurEffect(radius, radius)`（API 31+ 才生效，本机 API 30 恒为空操作）。
 *   它就是三星表「模糊范围异常」的第二处来源 —— RenderEffect 按行挂，但模糊不遵守
 *   节点边界，远景行的糊会溢到可读带上。
 *   景深层次现在完全由既有的 `lyricBrightness(d)` 亮度衰减 + 缩放承担（本就在做），
 *   行内不再有任何 GPU 特效。
 */

/**
 * 把「本行内容」重绘一遍并染成光色、再用移动光斑遮罩叠回原文之上（字形遮罩层，
 * 也是唯一一层 —— 用户口径③「扫光仅在歌词字体上面显示」，见文件头注释）。
 *
 * 抽成普通函数是为了让 [LyricRow] 的 drawWithContent 保持可读 —— 它必须是
 * `DrawScope` 的扩展（要拿 size / drawRect / drawContext），且**不持有任何 Compose 状态**：
 * 所有逐帧量都由调用方以参数传入，保证这里是纯粹的绘制代码。
 *
 * @param drawOriginal 原文的绘制入口（即 drawContent 的引用）
 * @param base 底噪强度（0..1，= 聚焦行整体提亮量）
 * @param peak 光斑峰值强度（0..1）；等于 base 时只提亮、不扫光
 * @param cx 光斑中心横坐标（px，由调用方按扫光进度算好）
 * @param radius 光斑半径（px）
 * @param cy 光斑中心纵坐标（px）：★ 2026-10-18 折行时由调用方传「当前显示行的行心」
 * @param vScale 纵向压缩系数（★ 2026-10-18）：< 1 时把圆形径向压成椭圆，只盖住一行；
 *               1 = 不压缩（单行句子的老几何，观感与改动前完全一致）
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLyricSweep(
    drawOriginal: () -> Unit,
    base: Float,
    peak: Float,
    cx: Float,
    radius: Float,
    cy: Float = size.height / 2f,
    vScale: Float = 1f,
    /**
     * ★ 2026-10-18「未到达进度的部分不留光」：一个只画 `DstIn` 字框的遮罩（见
     * [drawCharProgressMask]，alphaOf = 进度）。null = 不做（老行为）。
     * 有了它，底噪白光与移动光带都只落在**已经唱到**的字上，
     * 未唱到的字保持与下方歌词同一档透明度。
     */
    revealMask: (() -> Unit)? = null,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    // 光色恒为纯白（2026-10-02 用户口径「浅色主题的歌词呈现黑色无法体现扫光，需要与深色主题同步」）：
    // 深色主题下字是亮色/彩色，白光扫过 = 提亮；浅色主题下字是深色，白光扫过 = 字形被洗亮，
    // 同样是「一道白光扫过去」的观感，而且比叠黑看得清楚得多（叠黑等于黑字上再叠黑，什么也看不见）。
    // ⚠ 浅色主题下这一层的**底噪**由调用方置 0（见 LyricRow 的 drawWithContent）——
    //   光带之外不去动深色字，避免把对比度抹平。
    val ink = Color.White
    val canvas = drawContext.canvas

    canvas.saveLayer(Rect(0f, 0f, w, h), Paint())
    drawOriginal()
    // ① 非透明像素全部染成光色（alpha 保留 ⇒ 字形边缘的抗锯齿不丢）
    drawRect(ink, blendMode = BlendMode.SrcIn)
    // ② 移动光斑遮罩：TileMode.Clamp ⇒ 光斑半径之外自动落在「底噪」那一档上，
    //    这正是参考图里「光晕之外还有一层淡底光」的观感，不需要额外再画一层。
    val brush = Brush.radialGradient(
        colors = listOf(
            Color.White.copy(alpha = peak),
            Color.White.copy(alpha = base + (peak - base) * 0.72f),
            Color.White.copy(alpha = base + (peak - base) * 0.26f),
            Color.White.copy(alpha = base),
        ),
        center = Offset(cx, cy),
        radius = radius,
    )
    if (vScale >= 0.999f) {
        drawRect(brush = brush, blendMode = BlendMode.DstIn)
    } else {
        // 纵向压扁成椭圆（★ 2026-10-18）：以 cy 为轴缩 y，横向剖面（也就是「光的行进」）不变。
        // ⚠ 缩放后矩形必须**放大到覆盖整块图层**：否则被压缩的矩形之外根本没有绘制，
        //   DstIn 不作用 ⇒ 图层那片区域保持「全亮白剪影」，整行会闪成一块白 —— 见下面的 2h/vScale。
        canvas.save()
        // 以 cy 为轴压扁（Canvas 只提供 scale(sx, sy)，没有带 pivot 的重载 ⇒ 手动平移过去再拉回来）
        canvas.translate(0f, cy)
        canvas.scale(1f, vScale)
        canvas.translate(0f, -cy)
        drawRect(
            brush = brush,
            topLeft = Offset(0f, cy - h / vScale),
            size = androidx.compose.ui.geometry.Size(w, 2f * h / vScale),
            blendMode = BlendMode.DstIn,
        )
        canvas.restore()
    }
    // ③ 未到达进度的字把光擦干净（光只跟着字走）：再乘一层「逐字进度」遮罩。
    //    必须画**每一个**字（进度 0 的写成 0），不能只画已唱的那些 ——
    //    DstIn 是乘法，没画的区域会保留上一层的底噪光。
    revealMask?.invoke()
    canvas.restore()
}

/**
 * ★ 2026-10-18 聚焦行亮度压暗（用户口径「当前聚焦歌词亮度过高，降低 10%」）。
 *
 * 做法：把本行内容重画进离屏层 → `SrcAtop` 叠一层 [amount] 的黑
 * （= 目标色 × (1-amount)，字形 alpha 完全保留）→ `restore()` 叠回原文之上。
 * 结果 = 每个字形像素的亮度 ×0.9，色相/字形/排版都不动。
 *
 * ⚠ 必须用 SrcAtop，**不能用 SrcIn**：SrcIn 的合成结果是「源色 × 目标 alpha」——
 *   字形颜色会被替换成纯黑、只剩 amount×alpha 的透明度，整个聚焦行变成一层
 *   比底还暗的淡黑影（用户反馈的「聚焦歌词变成暗色偏黑看不清」，实测截图里
 *   聚焦行像素比背景更暗）。SrcAtop 才是「原地压暗 10%」，这正是本函数的口径。
 *
 * 为什么不能走别的路：
 *  · 改 Text 的 color —— 会让 Paragraph 缓存整体失效（本页铁律，等价每帧重排版）；
 *  · 降图层 alpha —— 浅底上「半透明白字」等于和底混色，看着更糊更灰，不是"暗一点"，
 *    而且焦点行必须是严格 alpha=1f（见 graphicsLayer 注释）。
 * 只有这条绘制期的压暗层是零重排版、且真的把亮度按比例压下去。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFocusDim(
    drawOriginal: () -> Unit,
    amount: Float,
) {
    val w = size.width
    val h = size.height
    if (amount <= 0.004f || w <= 0f || h <= 0f) {
        drawOriginal()
        return
    }
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(0f, 0f, w, h), Paint())
    drawOriginal()
    // SrcAtop = 目标色 × (1 - amount)，alpha 不变 —— 这才是「亮度压 10%」；
    // SrcIn 会把字形颜色整个替换成黑色（见上方注释的实测 bug）。
    drawRect(Color.Black.copy(alpha = amount), blendMode = BlendMode.SrcAtop)
    canvas.restore()
}

/**
 * 焦点行光晕强度：随「与焦点行的连续距离」渐变（用户口径「光晕渐变出现」）。
 * d 是 anchorPos 的连续动画量 —— 切换歌词时整列滑动 630ms，光晕跟着滑入/淡出，
 * 不会硬闪；手拖列表时同样连续。静止时 |d| ∈ {0,1,2…} ⇒ 只有聚焦行非零。
 */
private fun lyricHaloAmount(d: Float): Float {
    val a = abs(d)
    return when {
        a <= LYRIC_HALO_FADE_FROM -> 1f
        a >= LYRIC_HALO_FADE_TO -> 0f
        else -> (LYRIC_HALO_FADE_TO - a) / (LYRIC_HALO_FADE_TO - LYRIC_HALO_FADE_FROM)
    }
}

/**
 * 把焦点行光晕画到字形**底下**（调用方必须先于本行内容绘制调用它）。
 *
 * 做法（2026-10-02 二代）：同一 saveLayer 里把本行内容按「中心 + 8 方向」共画 9 遍 ——
 * 轮廓向外膨胀约一个半径，等价于一圈零偏移的柔光；随后对整层做一次 SrcIn 染成晕色、
 * 一次 DstIn 按强度 k 淡出。9 遍合进同一图层的关键收益：染/淡各只做一次整层操作，
 * 而不是每份偏移都走一轮 saveLayer 往返。只被 k > 0.01 的行调用（通常就聚焦那一行），
 * 其余行零开销；边缘模糊镜像层不调用（那层只露屏幕最上下缘，聚焦行恒在中央，画了也看不见）。
 *
 * @param drawOriginal 本行内容的绘制入口（即 drawContent 的引用）
 * @param color 晕色（深墨配白 / 浅墨配黑，由页面层按墨色反相算好）
 * @param k 强度 0..1（绘制期延迟读的距离函数值）
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFocusHalo(
    drawOriginal: () -> Unit,
    color: Color,
    k: Float,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    val canvas = drawContext.canvas
    // 图层外扩：给膨胀轮廓留边，避免行边缘的晕被图层边界裁出硬边
    val pad = LYRIC_HALO_RADIUS + 2f
    canvas.saveLayer(Rect(-pad, -pad, w + pad, h + pad), Paint())
    drawOriginal()
    LYRIC_HALO_RING.forEach { o ->
        canvas.save()
        canvas.translate(o.x, o.y)
        drawOriginal()
        canvas.restore()
    }
    // 整层染成晕色（alpha 场保留 ⇒ 膨胀轮廓的边缘保持抗锯齿的柔）
    drawRect(color, blendMode = BlendMode.SrcIn)
    // 按强度整体淡出：k 是绘制期延迟读的连续量 ⇒「渐变出现/消失」就落在这一行
    drawRect(Color.White.copy(alpha = k), blendMode = BlendMode.DstIn)
    canvas.restore()
}

/**
 * 逐字进度遮罩（★ 2026-10-18 抽成公共函数）：把「每个字此刻的进度 0..1」交给 [alphaOf]
 * 换成透明度，按真实排版框铺一层 `DstIn`。两处复用：
 *  · [drawKaraokeDim]（压暗未唱字）—— alphaOf = dim + (1-dim)·进度；
 *  · 扫光层的「未到达部分不留光」—— alphaOf = 进度（未唱 = 0，光带不落在它上面）。
 *
 * @param skipOpaque 已满进度的字直接跳过（压暗时它们本来就不需要再画一次，省 N 次 drawRect）；
 *                   扫光遮罩必须**逐字都画**（进度 0 的字要显式写成 0，才能把底噪光擦掉）。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCharProgressMask(
    words: List<LyricWord>,
    posMs: Float,
    layout: TextLayoutResult,
    left: Float,
    top: Float,
    skipOpaque: Boolean,
    alphaOf: (Float) -> Float,
) {
    val textLength = layout.layoutInput.text.length
    var offset = 0
    words.forEach { word ->
        val fraction = if (word.durationMs <= 0L) {
            if (posMs >= word.startMs) 1f else 0f
        } else ((posMs - word.startMs) / word.durationMs).coerceIn(0f, 1f)
        val end = (offset + word.text.length).coerceAtMost(textLength)
        for (index in offset until end) {
            val characterProgress = (fraction * word.text.length - (index - offset)).coerceIn(0f, 1f)
            val alpha = alphaOf(characterProgress).coerceIn(0f, 1f)
            if (skipOpaque && alpha >= 0.999f) continue
            val box = layout.getBoundingBox(index)
            drawRect(
                Color.White.copy(alpha = alpha),
                topLeft = Offset(left + box.left, top + box.top),
                size = androidx.compose.ui.geometry.Size(box.width, box.height),
                blendMode = BlendMode.DstIn,
            )
        }
        offset = end
    }
}

/** 保留已唱字的亮度，只压暗未唱字；字框来自真实排版，支持自动换行。 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawKaraokeDim(
    drawOriginal: () -> Unit,
    words: List<LyricWord>,
    posMs: Float,
    layout: TextLayoutResult,
    left: Float,
    top: Float,
    dim: Float,
) {
    val canvas = drawContext.canvas
    canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint())
    drawOriginal()
    drawCharProgressMask(
        words = words,
        posMs = posMs,
        layout = layout,
        left = left,
        top = top,
        skipOpaque = true,
    ) { progress -> dim + (1f - dim) * progress }
    canvas.restore()
}

fun parseLrc(raw: String): List<LyricLine> {
    val out = mutableListOf<LyricLine>()
    raw.lines().forEach { line ->
        val matches = LRC_REGEX.findAll(line).toList()
        if (matches.isEmpty()) return@forEach
        val text = line.substringAfterLast(']').trim()
        if (text.isEmpty()) return@forEach
        matches.forEach { match ->
            val minutes = match.groupValues[1].toLong()
            val seconds = match.groupValues[2].toLong()
            val fraction = match.groupValues[3].ifEmpty { "0" }
            val fractionMs = when (fraction.length) {
                1 -> fraction.toLong() * 100
                2 -> fraction.toLong() * 10
                else -> fraction.take(3).toLong()
            }
            out.add(LyricLine(minutes * 60_000 + seconds * 1000 + fractionMs, text))
        }
    }
    return out.sortedBy { it.timeMs }
}

fun parseLrcTrans(raw: String): Map<Long, String> {
    val out = mutableMapOf<Long, String>()
    raw.lines().forEach { line ->
        val match = LRC_REGEX.find(line) ?: return@forEach
        val text = line.substringAfterLast(']').trim()
        if (text.isEmpty()) return@forEach
        val minutes = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].toLong()
        val fraction = match.groupValues[3].ifEmpty { "0" }
        val fractionMs = when (fraction.length) {
            1 -> fraction.toLong() * 100
            2 -> fraction.toLong() * 10
            else -> fraction.take(3).toLong()
        }
        out[minutes * 60_000 + seconds * 1000 + fractionMs] = text
    }
    return out
}

/**
 * 译文匹配容差：只认「时间戳完全相同」或 ±150ms 内的最近一条。
 *
 * ⚠ 曾经是 ±500ms —— 那会把**上一条译文错配给 500ms 内的下一句歌词**：
 * 本地 lrc 常常不是每句都有翻译，于是「原文1 + 译文1 / 原文2 + 译文1」，
 * 屏幕上同一句译文出现两次 = 用户反馈的「歌词重影」（2026-10-04 修复）。
 * 150ms 足够覆盖「译文行时间戳与原文差几十毫秒」的容错，又不会跨句误配。
 */
private const val LYRIC_TRANS_TOLERANCE_MS = 150L

private fun Map<Long, String>.transFor(timeMs: Long): String? =
    this[timeMs] ?: entries.filter { abs(it.key - timeMs) <= LYRIC_TRANS_TOLERANCE_MS }
        .minByOrNull { abs(it.key - timeMs) }?.value

private fun Map<Long, String>.nearFor(timeMs: Long): String? =
    this[timeMs] ?: entries.filter { abs(it.key - timeMs) <= LYRIC_TRANS_TOLERANCE_MS }
        .minByOrNull { abs(it.key - timeMs) }?.value

/**
 * 逐字歌词（yrc）解析（2026-10-06 用户口径③，只在支持时启用）。
 *
 * 网易 `/api/song/lyric/v1` 的 `yrc.lyric` 格式：
 *     [行起始ms,行时长ms](词起始ms,词时长ms,0)词(词起始ms,词时长ms,0)词…
 * 与 lrc 的三点区别（都踩过）：
 *   ① 时间戳是**裸毫秒整数**，没有 `mm:ss.xx` 这种写法，逗号分隔，行头没有 `]` 以外的歧义；
 *   ② 词元前的第三个数字恒为 0（保留位），**不要**当时间用；
 *   ③ 整首歌的行会**重复**（网易的 yrc 里同一时间戳可能出现多遍，有的是空词行）——
 *      所以这里按时间戳去重，且只保留「拼起来非空」的行。
 * 词文本本身可能带空格（英文按词分组），不做 trim，否则字符偏移会和排版对不上。
 */
private fun parseYrc(raw: String): List<LyricLine> {
    if (raw.isBlank()) return emptyList()
    val out = LinkedHashMap<Long, LyricLine>()
    raw.lines().forEach { line ->
        if (line.startsWith("{")) return@forEach          // yrc 头部可能带一串 JSON 元信息
        val head = YRC_LINE_REGEX.find(line) ?: return@forEach
        val startMs = head.groupValues[1].toLongOrNull() ?: return@forEach
        val body = line.substring(head.range.last + 1)
        val words = ArrayList<LyricWord>(body.length / 8 + 1)
        val text = StringBuilder(body.length / 4)
        YRC_WORD_REGEX.findAll(body).forEach { m ->
            val ws = m.groupValues[1].toLongOrNull() ?: return@forEach
            val wd = m.groupValues[2].toLongOrNull() ?: 0L
            val t = m.groupValues[4]
            if (t.isNotEmpty()) {
                words.add(LyricWord(t, ws, wd))
                text.append(t)
            }
        }
        val joined = text.toString().trim()
        if (joined.isEmpty() || words.isEmpty()) return@forEach
        // 同一时间戳只留一份（后写的覆盖，网易的重复行里靠后的那份更完整）
        out[startMs] = LyricLine(startMs, joined, words)
    }
    return out.values.sortedBy { it.timeMs }
}

/**
 * 把逐字数据挂到 lrc 解析出来的行上（**只认时间戳**，不碰正文）。
 *
 * 为什么不直接用 yrc 的正文当歌词：正文与翻译/罗马音的对齐、以及用户已经看惯的
 * 断行，全部建立在 lrc 那一份上；yrc 只提供「每个字什么时候唱」这一条额外信息。
 * 时间戳对不上（超过 [LYRIC_TRANS_TOLERANCE_MS]）就这一行不挂 —— 宁可整行走扫光，
 * 也不要拿邻行的字去点亮。
 */
private fun mergeWords(lines: List<LyricLine>, yrc: List<LyricLine>): List<LyricLine> {
    if (lines.isEmpty() || yrc.isEmpty()) return lines
    var j = 0
    return lines.map { line ->
        while (j < yrc.size - 1 &&
            abs(yrc[j + 1].timeMs - line.timeMs) <= abs(yrc[j].timeMs - line.timeMs)
        ) {
            j++
        }
        val cand = yrc[j]
        if (cand.words != null && abs(cand.timeMs - line.timeMs) <= LYRIC_TRANS_TOLERANCE_MS) {
            line.copy(words = cand.words)
        } else {
            line
        }
    }
}

private val YRC_LINE_REGEX = Regex("^\\[(\\d+),(\\d+)]")
private val YRC_WORD_REGEX = Regex("\\((\\d+),(\\d+),(\\d+)\\)([^(]*)")

/**
 * 一句的**演唱窗口**（ms，从本句时间戳算起）—— 扫光/逐字都只在这个窗口里推进。
 *
 * ★ 2026-10-18 用户 bug「有真逐字的歌词当进入间奏的时候还在逐字」：
 *   旧版的三处推进（扫光 `span`、假逐字的分段、逐字位置的发布循环）全部拿
 *   **「到下一句的间隔」** 当窗口。正常句子两者差不多，但**间奏**那一段没有新歌词，
 *   间隔可能是十几二十秒 ⇒ 一句明明已经唱完，光带还在慢慢爬、假逐字还在一个个字亮，
 *   整段间奏都在"逐字"。
 *
 * 现在的口径：
 *   · 有真逐字 → 窗口 = **最后一个词的收尾**（`last.start + last.duration - 本句时间戳`），
 *     唱完即止（再夹到「到下一句的间隔」以内，防止 yrc 尾部越界）；
 *   · 没有逐字 → 只能估：间隔明显大于「字数所需时长」时按字数封顶
 *     （[fakeCharWindowMs] = 800ms + 字数×420ms ≈ 慢歌字速），把间奏那种超长间隔砍掉。
 *     正常句子的间隔本来就在这个上限内，所以扫光时长与改动前完全一致。
 */
private fun singWindowMs(line: LyricLine, nextMs: Long?): Long {
    val gap = ((nextMs ?: (line.timeMs + LYRIC_SWEEP_FALLBACK_MS.toLong())) - line.timeMs)
        .coerceAtLeast(0L)
    val w = line.words
    if (!w.isNullOrEmpty()) {
        val last = w.last()
        val end = last.startMs + last.durationMs - line.timeMs
        if (end > 0L) return end.coerceAtMost(gap.coerceAtLeast(300L)).coerceAtLeast(300L)
    }
    return gap.coerceAtMost(fakeCharWindowMs(line.text)).coerceAtLeast(300L)
}

/** 「假逐字」估出来的合理演唱时长：字数越多越长，用来砍掉间奏那种超长间隔 */
private fun fakeCharWindowMs(text: String): Long = 800L + text.length * 420L

/**
 * ★ 2026-10-18 用户口径「歌词不支持逐字那就用假的逐字」：「假逐字」表。
 *
 * 这一句没有 yrc 逐字数据时，按**字数**把本句的演唱窗口均分，造一份和主歌词逐字对齐的逐字表：
 * 第 i 个字占 `[start + i·step, start + (i+1)·step)`，`step = 演唱窗口 / 字数`。
 *
 * 用它算出来的「唱到第几个字」与真实逐字走**同一套绘制代码**（[drawCharProgressMask]），
 * 所以明暗观感完全一致（"逐字效果亮度不变"）；字框仍取真实排版，自动换行也不受影响。
 *
 * @param windowMs 本句的演唱窗口（见 [singWindowMs]），**不是**到下一句的间隔 ——
 *                 否则间奏那一段会被均分成一个个字慢慢亮
 */
private fun fakeCharWords(text: String, startMs: Long, windowMs: Long): List<LyricWord> {
    val n = text.length
    if (n == 0) return emptyList()
    val span = windowMs.coerceAtLeast(n.toLong())
    val step = (span / n).coerceAtLeast(1L)
    return ArrayList<LyricWord>(n).apply {
        for (i in 0 until n) add(LyricWord(text[i].toString(), startMs + i * step, step))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LyricsScreen(nav: NavHostController) = PlayToneScope { LyricsScreenContent(nav) }

/**
 * ★ 2026-10-07「封面模糊明暗对调」：整页包在 [PlayToneScope] 里 ——
 * 开关打开时歌词墨色 / 标题 / 发音胶囊按**另一套明暗**出画面；
 * 底图的打光目标由 CoverBackdrop 同口径翻转（见 AppearancePrefs.coverBlurToneFlip）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LyricsScreenContent(nav: NavHostController) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            // ★ 2026-10-17（本轮）恢复 1.24-r2 的歌词页自带环境色底（m3e 下恒 Transparent）。
            .background(ambientSurfaceColor()),
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val scope = rememberCoroutineScope()
        val song = PlayerEngine.current
        val lowPerformance = AppearancePrefs.lowPerformanceLyrics
        // ★ 2026-10-04 本地歌曲歌词读取要 context（内容解析器 / 文件路径）
        val ctx = androidx.compose.ui.platform.LocalContext.current
        // ★ 2026-10-11 设置页「歌词页屏幕常亮」开关（AppearancePrefs.lyricKeepScreenOn）：
        //   停留在歌词页且开关打开时给窗口挂 FLAG_KEEP_SCREEN_ON —— 只压住这一页的
        //   自动息屏；离开歌词页（离开组合）onDispose 必然清掉，播放页/其他页面不受影响。
        //   开关是 Compose 状态：在设置里切换后本组合自动重组、立即生效，无需重进歌词页。
        val keepScreenOn = AppearancePrefs.lyricKeepScreenOn
        DisposableEffect(keepScreenOn) {
            val win = ctx.findActivity()?.window
            if (keepScreenOn && win != null) {
                win.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            onDispose {
                win?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        val viewportHeight = maxHeight
        val circleDiameter = minOf(maxWidth, maxHeight)
        // A centered text band stays inside the round screen's useful area and leaves room for
        // the right-edge progress arc. This is in Dp and follows the measured window constraints.
        val lyricSafeWidth = circleDiameter * 0.68f
        // ★ 2026-10-06 播放页面风格 = 封面模糊（用户口径①②③）：本页全部新动效的总开关。
        //   读的是 Compose 状态 ⇒ 用户在设置里换风格时本页自动重组、立即生效。
        val coverBlur = AppearancePrefs.coverBlurStyle
        // ★ 2026-10-01 歌词取色（用户口径「自动取色范围延伸到歌词上面，同时保证歌词可读性」）：
        //   歌词正文不再是恒定白，改用「自动取色」的强调色（见 theme/Theme.kt 的 lyricInk）。
        //   底色必须传**本页真实底色**，不是 Bg —— 环境色模式下本页铺的是歌曲环境色
        //   （封面主色向近黑压暗 75%，自带彩度、亮度随歌变化），对比度得按它算；
        //   只有「有自定义背景图且关了保留取色」时本页才透明透出背景图，那种情况按 Bg 标定
        //   （与全站文字同一个假设）。
        //   ⚠ 只在页面层算一次并逐行下传：LyricRow 的颜色必须是**重组周期内的常量**，
        //     行内逐帧改颜色会让 Paragraph 缓存整体失效（等价每帧重排版，见 LyricRow 注释）。
        // ★ 2026-10-06 封面模糊：本页底也不是环境色，而是全局背景层的模糊封面底
        //   （2026-10-07 三稿起播放页/歌词页**共用同一张**，见 CoverBackdrop）——
        //   该底图已被 backdropScrim 压到固定亮度的目标值（深色 ≈0.14 / 浅色 ≈0.50，
        //   NcmBackground 的常量），所以这里直接给一个同亮度的代表色，
        //   让 lyricInk 按**真实**对比度算墨色。
        val lyricInkColor = lyricInk(
            backdrop = when {
                // 2026-10-06 二稿：底图改为「曝光归一 + 饱和增益」后，平均亮度就是
                // CoverBackdrop.LIGHT_TARGET(0.50) / DARK_TARGET(0.14)，对应灰 = 0xBABABA / 0x696969
                // ★ 2026-10-07 明暗对调：读 playLightTheme —— 底图打光目标已翻到另一套（见 CoverBackdrop），
                //   标定灰必须跟着翻，否则墨色按「亮底」算却在暗底上出画面
                coverBlur -> if (playLightTheme) Color(0xFFBABABA)
                else Color(0xFF696969)
                !AppearancePrefs.playShowsWallpaper -> SongAmbient.color
                else -> Bg
            },
            // ★ 2026-10-06 用户口径：设了自定义壁纸时，取色 / 封面模糊这两条风格的歌词
            //   改跟**专辑封面主题色**（SongAccent），不再被壁纸主色顶掉 —— 这两种风格的
            //   页面底本就跟着歌走（环境色 / 封面模糊底），底随歌、字也得随歌。
            //   自定义风格（透壁纸）仍然跟壁纸（Accent），与页面底一致。
            //   ★ 2026-10-07 明暗对调：走 Play* 变体（作用域外 = 原值）
            seed = if (AppearancePrefs.playShowsWallpaper) {
                PlayAccent
            } else {
                PlaySongAccent
            },
            light = playLightTheme,
            // ★ 2026-10-10 用户口径「封面模糊主题的歌词取色经常为纯白导致扫光效果无法显示」：
            //   本页底是**归一化压平的定值亮度底**，放宽歌词对比度下限（4.5:1 → 3.5:1），
            //   让墨色保住封面色彩度、不再顶到近白 —— 白色扫光扫过时才有可见的亮度差。
            coverBlur = coverBlur,
        )
        // ★ 2026-10-02 方案 1「字形光晕」：修复用户 bug「浅色模式开启自定义背景取色后
        //   歌词可见性不足」。只作用于**浅色主题 + 自定义背景图垫在歌词后面**的透底场景 ——
        //   此时歌词底下是图像，局部亮度任意（实测同屏 1.2:1~5.2:1），单点标定的取色管不住局部；
        //   给字形垫一圈反向柔光（深墨配白晕）= 给聚焦行压一块局部底色。
        //   环境色模式（纯色底，lyricInk 已保证 4.5/3.2:1）不碰；**深色主题不做**
        //   （2026-10-02 用户口径「深色模式下不要光晕效果」—— 这条分支同时抹掉了深色下的
        //   晕色计算与整条绘制路径，halo 恒为 null）。推导见 LYRIC_HALO_ALPHA。
        //   只算「颜色」这一个常量（重组周期内不变）；强度/作用行由 LyricRow 在绘制期
        //   按与焦点行的距离渐变（二代口径：只有聚焦歌词有晕、且渐变出现），不进 TextStyle。
        //   ⚠ 2026-10-06：判定条件由 BackgroundStore 的旧字段换成 AppearancePrefs 的三态
        //     （playShowsWallpaper 才等于「歌词底下是图像」）—— 封面模糊底自带压暗，
        //     且它底下的字是**近白墨**，不需要这圈反向柔光。
        val lyricHalo: Color? = if (!lowPerformance && playLightTheme &&
            AppearancePrefs.playShowsWallpaper
        ) {
            (if (lyricInkColor.luminance() > 0.5f) Color.Black else Color.White)
                .copy(alpha = LYRIC_HALO_ALPHA)
        } else null
        // ★ 2026-10-18 用户口径「当前聚焦歌词亮度过高给我降低百分之十（只在背景为浅色触发）」。
        //   判定必须用**屏幕实际看到的底**，不能用封面原始主色：
        //   封面模糊/取色风格下页面底是「已压到固定亮度目标」的成图（深色 ≈0.14 / 浅色 ≈0.50），
        //   封面再亮也不代表实际底色亮。旧探针用 SongAmbient.rawColor，于是浅色封面
        //   （夜鹿夏草）在**深色**封面模糊底上被误判成浅底 ⇒ 10% 压暗落在本就只有
        //   ~0.4 透明度的未唱字上，整句发灰发黑（用户反馈的「变成暗色偏黑看不清」）。
        //   现在探针与上面 lyricInk 的 backdrop **同源**，逐档对应真实底色：
        //     · 封面模糊 → 曝光归一后的标定灰（深 0x696969 / 浅 0xBABABA）；
        //     · m3e → 纯黑 / 歌曲环境色（m3e 从不显示自定义壁纸）；
        //     · 自定义壁纸 → 壁纸主色叠上页面压暗层（Bg × dim）后的实际观感色；
        //     · 其余 → 歌曲环境色。
        val backdropProbe = when {
            coverBlur -> if (playLightTheme) Color(0xFFBABABA) else Color(0xFF696969)
            AppearancePrefs.isM3e -> when (AppearancePrefs.m3eBackgroundStyle) {
                com.netmusiclite.data.M3eBackgroundStyle.BLACK -> Color.Black
                else -> SongAmbient.colorFor(light = false)
            }
            !AppearancePrefs.playShowsWallpaper -> SongAmbient.color
            BackgroundStore.ready && BackgroundStore.seedColor != null -> {
                val s = BackgroundStore.seedColor!!
                val d = BackgroundStore.dim.coerceIn(0f, 1f)
                Color(
                    red = s.red * (1f - d) + Bg.red * d,
                    green = s.green * (1f - d) + Bg.green * d,
                    blue = s.blue * (1f - d) + Bg.blue * d,
                )
            }
            else -> Bg
        }
        val lightBackdrop = backdropProbe.luminance() > LIGHT_BACKDROP_LUM &&
            // 再加一道「墨色本身是亮的」：浅色主题下 lyricInk 会把墨色往黑压，
            // 那种情况本来就是深字浅底、再压 10% 只会把对比度做低（不是"太亮"）。
            // 两个条件同时成立 = 「浅底 + 亮字」这个真正刺眼的组合。
            lyricInkColor.luminance() > 0.5f
        // ★ 2026-10-18 聚焦行未唱字：按真实底色把基态透明度抬到可读下限（见 karaokeDimFor）。
        //   近黑环境色底只微调（基本保持「未唱字与下方歌词同档」的旧口径）；
        //   中亮底（封面模糊 / 浅底）才会明显抬高 —— 那里旧口径的对比度已经不足，可读性优先。
        val karaokeDimBase = karaokeDimFor(backdropProbe, lyricInkColor)
        // 一行 logcat 便于现场核对判定是否成立（用户口径「只在背景为浅色触发」）：
        //   adb logcat -s LyricsBackdrop
        LaunchedEffect(lightBackdrop, backdropProbe, karaokeDimBase) {
            android.util.Log.d(
                "LyricsBackdrop",
                "probe=$backdropProbe lum=${backdropProbe.luminance()} " +
                    "ink=${lyricInkColor.luminance()} karaokeDim=$karaokeDimBase " +
                    "→ dimFocus=$lightBackdrop",
            )
        }
        val backFader = remember(density) {
            // ★ scaleMode = false：歌词页 → 播放页保持**位移滑动**，不用缩放
            //   （用户明确要求「歌词界面切换到播放界面保留滑动切换动画」）
            SwipeBackFader(with(density) { 34.dp.toPx() }, scope, scaleMode = false)
        }
        // 滑出目标 = 屏宽（松手速度接续滑出用）
        // ⚠ screenWidthUiDp = UI 坐标系屏宽（密度等比缩放后 LocalConfiguration.screenWidthDp 不再等于屏宽）
        val screenPx = with(density) {
            com.netmusiclite.ui.components.screenWidthUiDp().toPx()
        }
        var swipeLifted by remember { mutableStateOf(false) }
        var lines by remember(song?.id) { mutableStateOf<List<LyricLine>>(emptyList()) }
        var trans by remember(song?.id) { mutableStateOf<Map<Long, String>>(emptyMap()) }
        var romaMap by remember(song?.id) { mutableStateOf<Map<Long, String>>(emptyMap()) }
        var loading by remember(song?.id) { mutableStateOf(true) }
        // ★ 2026-10-04 本地歌曲专用：歌词来源标注（"本地歌词文件" / "歌曲内嵌歌词" /
        //   "匹配自网易云 · 歌名 - 艺人"）。线上歌曲恒为 null，不显示。
        var lyricFrom by remember(song?.id) { mutableStateOf<String?>(null) }
        var userScrolling by remember { mutableStateOf(false) }
        // ★ 2026-10-17 秒定位（用户口径「由播放页进入歌词页要秒定位歌词」）：
        //   旧实现先进组合（列表从头开始）、再由 LaunchedEffect 去 scrollToItem —— 中间至少有一帧
        //   停在顶部（第一句/标题）；而且定位还没完成时若 activeIdx 变化，effect 被取消重启，
        //   positionedForSong 已置位 ⇒ 掉进 ② 的 animateScrollToItem，变成「从顶部慢慢滚下去」。
        //   现在把「当前行」直接作为 LazyColumn 的**初始滚动位置**（歌词到达的那一次重组就生效，
        //   第一帧即停在当前行）。后面的首次定位只再做一次像素级居中修正。
        val listState = remember(lines) {
            val start = 1 + if (lyricFrom != null) 1 else 0 // title [+ 本地歌词来源标注]
            // ★ 2026-10-18 耗电：这里必须读 positionSnapshot（普通字段）而不是 positionMs。
            //   remember 的 lambda 在**组合期**执行，读 mutableStateOf 会把本页注册成它的
            //   依赖 —— 播放中每 500ms 一次心跳就会把整页（BoxWithConstraints + 全屏 Canvas
            //   + LazyColumn + 每行 Text 重新测量）重组一遍。快照与 positionMs 同一赋值动作
            //   里更新，精度一样，但读它不产生重组依赖：本页从此只在「歌词变化 / 切歌」时重组。
            val active = lines.indexOfLast { it.timeMs <= PlayerEngine.positionSnapshot }
            LazyListState((active + start).coerceAtLeast(0))
        }

        LaunchedEffect(song?.id) {
            lines = emptyList()
            trans = emptyMap()
            romaMap = emptyMap()
            lyricFrom = null
            loading = true
            if (song != null) {
                // 冷启动切歌词页卡帧根因（2026-09-25）：本地歌词文件读 + 全量 LRC 正则解析
                // 此前在主线程协程同步执行（转场动画的前几帧正好被堵）——全部挪到 Default 线程，
                // 网络请求本身是 suspend 不变；解析完回主线程一次性赋值
                val sid = song.id
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val local = DownloadStore.localLyric(sid)
                    if (local != null) {
                        val l = local.first; val t = local.second
                        val localRoma = DownloadStore.localRomaText(sid)
                        val rm = if (localRoma != null) {
                            parseLrc(localRoma).associate { it.timeMs to it.text }
                        } else {
                            runCatching {
                                val roma = NcmApi.lyricV1(sid).roma
                                if (roma.isNullOrBlank()) null else {
                                    DownloadStore.saveRoma(sid, roma)
                                    parseLrc(roma).associate { it.timeMs to it.text }
                                }
                            }.getOrNull() ?: emptyMap()
                        }
                        lines = l; trans = t; romaMap = rm
                    } else if (sid < 0) {
                        // ★ 2026-10-04 本地歌曲（媒体库扫描 id = -mediaId、SAF 导入 id = -(1e9+n)）：
                        //   旧版和线上歌共用下面那个 else —— 拿负数 id 请求 /api/song/lyric/v1
                        //   必然返回空，歌词页就永远停在「暂无歌词」（用户口径「本地歌词没法看」）。
                        //   现在分两级兜底：
                        //   ① [LocalLyricStore] 读音频**旁边的同名 .lrc** 或**内嵌歌词帧**
                        //      （MP3 ID3v2 USLT / FLAC VORBIS LYRICS / M4A ©lyr）；
                        //   ② 都没有才用 [LocalLyricMatch] 按「标题 + 艺人」在线匹配一条
                        //      （阈值收得紧，宁可没有也不乱配），并在歌名下标注来源。
                        val own = LocalLyricStore.read(ctx, song)
                        if (own != null) {
                            val (l, t) = LocalLyricStore.parse(own.raw, song.durationMs)
                            lines = l
                            trans = t
                            lyricFrom = when (own.from) {
                                LocalLyricStore.Source.SIDECAR_LRC -> "本地歌词文件"
                                LocalLyricStore.Source.EMBEDDED -> "歌曲内嵌歌词"
                            }
                        } else {
                            val m = LocalLyricMatch.find(song)
                            if (m != null) {
                                lines = mergeWords(
                                    parseLrc(m.bundle.lrc ?: ""),
                                    parseYrc(m.bundle.yrc ?: ""),
                                )
                                trans = parseLrcTrans(m.bundle.trans ?: "")
                                romaMap = parseLrc(m.bundle.roma ?: "").associate { it.timeMs to it.text }
                                lyricFrom = "匹配自网易云 · ${m.title} - ${m.artist}"
                            }
                        }
                    } else {
                        val bundle = NcmApi.lyricV1(sid)
                        // ★ 2026-10-06 逐字（用户口径③）：yrc 只提供「每个字什么时候唱」，
                        //   正文/断行/译文对齐仍用 lrc 那一份 —— 按时间戳挂上去，对不上就不挂。
                        //   这里**无条件**解析（与风格无关）：用户在某首歌里现切「封面模糊」
                        //   不该等下一次进页才生效。
                        lines = mergeWords(
                            parseLrc(bundle.lrc ?: ""),
                            parseYrc(bundle.yrc ?: ""),
                        )
                        trans = parseLrcTrans(bundle.trans ?: "")
                        romaMap = parseLrc(bundle.roma ?: "").associate { it.timeMs to it.text }
                    }
                    loading = false
                }
            } else {
                loading = false
            }
        }

        val activeIdx by remember(lines) {
            derivedStateOf {
                val position = PlayerEngine.positionMs
                var active = -1
                lines.forEachIndexed { index, line -> if (line.timeMs <= position) active = index }
                active
            }
        }
        // ★ 2026-10-07：原「封面模糊专属：高亮锚点提前 0.4s 出发（scrollIdx）」已删除
        //   （用户口径「删除封面模糊特有的歌词动画，与封面取色的动画同步」）。
        //   预启动是为了给 820ms 弹簧抵掉 positionMs 500ms 采样颗粒度的滞后；弹簧删掉后
        //   它成了纯粹的提前量 —— 那会让高亮比人声早走，「与取色风格一致」也就无从谈起。
        //   滚动/高亮锚点重新只由 activeIdx（人声）驱动，两个风格逐帧同源。
        var showRoma by LyricsPronunciationState::enabled
        LaunchedEffect(song?.id, romaMap, loading) {
            if (!loading) {
                song?.id?.let { LyricsPronunciationState.record(it, romaMap.values.any { text -> text.isNotBlank() }) }
            }
        }

        // 亚像素补偿量（2026-10-01 五轮）：整数滚动追不到的那半个像素以内，交给每行的
        // graphicsLayer{translationY}。只被 graphicsLayer 延迟读取 → 每帧写它不会引起任何重组合。
        var glideTy by remember(lines) { mutableStateOf(0f) }

        // Scroll to the measured center of the active row. If it was offscreen, first bring it
        // into the lazy list's viewport, then use its actual measured offset and height.
        // 首次同步（进页/切歌）硬定位当前行，不从顶部做长距离滚动动画（2026-09-25）
        var positionedForSong by remember(lines) { mutableStateOf(false) }
        // 高亮锚点的「连续行坐标」（2026-09-30）：整数 activeIdx 的平滑版本。
        // 每行的高亮强度由 |index - anchorPos| 推出 —— 因为 anchorPos 用与滚动完全相同的
        // 曲线/时长、在同一帧启动，整屏的明暗渐变就与滚动锁相，即参考界面的核心观感。
        // 注意：这是唯一的动画驱动源，LyricRow 内部只在 graphicsLayer 里读它（延迟读 →
        // 不触发任何行的重组合，也不触发任何文本重新排版）。
        // 初值 -1 = 「尚无当前行」（前奏期间），此时第一行处于半亮，唱到时滑到满亮。
        val anchorPos = remember(lines) { Animatable(-1f) }
        // 行距拉伸量（0=常态，1=峰值）。与 anchorPos 同帧启动、同缓动，各自衰减。
        // 同样只在 LyricRow 的 graphicsLayer 里被读（延迟读 → 零重组合）
        val stretchAnim = remember(lines) { Animatable(0f) }

        // 切换曲线/时长（2026-10-07 两个风格合并）：统一走 LyricGlide 630ms / 零过冲的
        // 那套曲线（从参考录屏逐帧反解出来的，不要动）；封面模糊原有的 820ms 弹簧与
        // 300/400 拉伸时长已删除（收尾过冲即用户报的「刚静止一次像素偏移」）。

        // ★ 2026-10-02 白色扫光进度（用户口径②）：0 = 光斑还在行左端外侧，
        //   1 = 已完全扫出行右端外侧；< 0 = 不扫光，只保留底噪提亮（前奏/暂停/已扫完）。
        //   ⚠ 只在 LyricRow 的 drawWithContent 里被读（延迟读）→ 每帧写它不引起任何重组。
        val sweepFrac = remember(lines) { mutableFloatStateOf(-1f) }
        val lyricPlaying = PlayerEngine.isPlaying
        // ★ 2026-10-02 设置页「歌词扫光」开关（AppearancePrefs.lyricSweep）：
        //   关 = sweepFrac 恒 -1，即「不扫光，只保留底噪提亮」—— 聚焦行提亮（用户口径①）
        //   不随扫光开关连带消失。开关本身是 Compose 状态，读在这里会让本组合自动订阅，
        //   切换开关时本 LaunchedEffect 重建、立即生效，无需重进歌词页。
        val sweepOn = !lowPerformance && AppearancePrefs.lyricSweep
        LaunchedEffect(lines, activeIdx, lyricPlaying, sweepOn) {
            val i = activeIdx
            if (!sweepOn || !lyricPlaying || i < 0 || i >= lines.size) {
                // 扫光开关关闭 / 暂停 / 前奏（还没有当前行）/ 歌词为空：不扫光（底噪提亮仍然保留）
                sweepFrac.floatValue = -1f
                return@LaunchedEffect
            }
            // 「同步该句速度」：扫光时长 = 这一句的**演唱窗口**（见 singWindowMs）——
            // 不是「到下一句的间隔」，否则间奏那一段光带会一直慢慢爬（用户 bug：
            // 「进入间奏的时候还在逐字/还在扫」）。
            val startMs = lines[i].timeMs
            val nextMs = lines.getOrNull(i + 1)?.timeMs
            val span = singWindowMs(lines[i], nextMs).toFloat()
                .coerceIn(LYRIC_SWEEP_MIN_MS, LYRIC_SWEEP_MAX_MS)
            // 连续播放进度 =「最近一次 positionMs 采样」+「按帧时间外推」。
            // PlayerEngine 的 ticker 是 500ms 一跳（见 PlayerEngine.ticker），直接拿 positionMs
            // 当光斑位置会得到 2fps 的台阶；外推后每帧连续，而每来一个新采样就重新锚定 ⇒
            // 与真实进度长期不漂移，seek 也能自动跟上。
            var sampleMs = PlayerEngine.positionMs
            var sampleRt = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val latest = PlayerEngine.positionMs
                if (latest != sampleMs) {
                    sampleMs = latest
                    sampleRt = now
                }
                val pos = sampleMs + (now - sampleRt) / 1_000_000f
                val f = (pos - startMs) / span
                if (f >= 1f) {
                    // 光斑已扫出行右端：停在「只提亮」状态，不再逐帧空转
                    sweepFrac.floatValue = -1f
                    break
                }
                sweepFrac.floatValue = f.coerceAtLeast(0f)
            }
        }

        // ★ 2026-10-06 逐字点亮（用户口径③）：当前句的**连续播放位置**（ms）。
        //   与扫光同源：PlayerEngine 的 ticker 是 500ms 一跳，直接拿 positionMs 当逐字进度
        //   会一秒两跳；这里按帧时间外推、每来一个新采样就重新锚定（不漂、seek 自动跟上）。
        //   只在本句有逐字数据时发布；发布值只被 LyricRow 的绘制期读
        //   ⇒ 每帧写它不引起任何重组、不触发任何文本测量。整句唱完即停（停在满亮）。
        val karaokePosMs = remember(lines) {
            // 同 listState：组合期读快照，避免把本页注册成 positionMs 的重组依赖
            mutableFloatStateOf(PlayerEngine.positionSnapshot.toFloat())
        }
        // ★ 2026-10-18 用户口径「聚焦歌词未到进度时亮度/透明度与下方歌词一致」：
        //   逐字推进要对**每一句**都发布（不再要求这一句有 yrc）—— 没有逐字数据的句子用
        //   「假逐字」（按字数均分本句时长，见 fakeCharWords），同样能算出"唱到第几个字"。
        //   所以这里只按「有没有当前行」开闸，跟 yrc 无关。
        // ★ 2026-10-18 用户口径「当扫光效果关闭时保留原有的聚焦亮度」：
        //   逐字推进（含假逐字）属于扫光这一套观感 —— 开关关掉就完全退回改动前：
        //   整句满亮、不压暗未唱字、也不做 10% 的聚焦压暗。所以这里跟 [sweepOn] 走，
        //   关掉后连逐帧位置都不用发布（省一条满帧循环）。
        val karaokeLineOn = sweepOn && activeIdx >= 0
        LaunchedEffect(lines, activeIdx, lyricPlaying, karaokeLineOn) {
            if (!karaokeLineOn) {
                karaokePosMs.floatValue = Float.NaN
                return@LaunchedEffect
            }
            // 本句的结束时刻 = 本句时间戳 + **演唱窗口**（见 singWindowMs）。
            // ★ 2026-10-18：不能再用「下一句的时间戳」—— 间奏那一段没有新歌词，
            //   这个循环会在整段间奏里满帧跑，而且逐字前沿的终点也被推到间奏末尾。
            val line = lines.getOrNull(activeIdx) ?: run {
                karaokePosMs.floatValue = Float.NaN
                return@LaunchedEffect
            }
            val endMs = (line.timeMs + singWindowMs(line, lines.getOrNull(activeIdx + 1)?.timeMs)).toFloat()
            if (!lyricPlaying) {
                // 暂停：定格在当前位置（不能置 NaN —— 那等于把已唱的进度整段抹掉）
                karaokePosMs.floatValue = PlayerEngine.positionMs.toFloat()
                return@LaunchedEffect
            }
            var sampleMs = PlayerEngine.positionMs
            var sampleRt = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val latest = PlayerEngine.positionMs
                if (latest != sampleMs) {
                    sampleMs = latest
                    sampleRt = now
                }
                val pos = sampleMs + (now - sampleRt) / 1_000_000f
                karaokePosMs.floatValue = pos
                if (pos > endMs + 80f) break
            }
        }

        // 正文行在 LazyColumn 里的条目起始下标：title(1) = 1；
        // 本地歌曲多一项「歌词来源」标注（lyric_from，是否挂载见 lyricBody 的硬不变量注释）⇒ 2。
        // ⚠ 定位下标必须与条目表同步 —— 2026-10-11 之前写死 active + 2，漏算了这一项，
        //   本地歌每次定位都整体差一行：聚焦行永远落在屏幕横向中轴下方一行
        //   （用户反馈「本地导入歌词聚焦进度歌词不在屏幕x轴上」），线上歌无此项不受影响。
        // ★ 2026-10-17：pad_top/pad_bottom 两个半屏 spacer **条目**改成了 LazyColumn 的
        //   contentPadding（见 lyricBody）—— 条目表里不再有它们，起始下标由 2 变 1。
        val lyricStartIndex = 1 + if (lyricFrom != null) 1 else 0
        LaunchedEffect(lines, activeIdx, userScrolling, lyricStartIndex, lowPerformance) {
            if (userScrolling) return@LaunchedEffect
            val active = activeIdx
            val itemCount = lines.size
            if (itemCount == 0) return@LaunchedEffect

            // ① 本曲首次就位（进页 / 切歌 / 歌词刚解析出来）：只做一次硬定位。
            //    ⚠ 这里必须先判「是否已就位」，再判 activeIdx —— 早期版本把 `active < 0`
            //    提前返回写在前面，导致**前奏期间 positionedForSong 永远不置位**：
            //    用户在前奏点歌词跳转时，第一次激活恰好命中这个硬定位分支，
            //    滚动的曲线动画被 scrollToItem 吃掉，表现为「硬切」（2026-09-30 实测 bug）。
            //    ★ 2026-10-17：置位**挪到定位完成之后**。旧版先置位再 await 滚动，effect 若在
            //      这期间被 activeIdx 变化取消，重启后会误入 ② 的 animateScrollToItem ——
            //      正是用户报的「从顶部慢慢动画滚下去」。现在取消后重试仍走本分支（瞬时）。
            if (!positionedForSong) {
                if (active < 0) {
                    // 前奏中：没有「当前行」可对齐，锚点置 -1（无高亮行）；第一句唱到时正常走曲线滑入。
                    // 列表初始位置是 contentPadding 顶部（标题会在屏幕正中），这里瞬时上滚半屏让
                    // 标题回到顶部 —— 与旧「列表停在顶部」观感一致，又不会在标题与第一句之间留空行。
                    listState.scrollToItem(0)
                    listState.scrollBy(with(density) { viewportHeight.toPx() } / 2f)
                    positionedForSong = true
                    anchorPos.snapTo(-1f)
                    stretchAnim.snapTo(0f)
                    return@LaunchedEffect
                }
                val target = active + lyricStartIndex // title + [本地歌词来源标注]
                listState.scrollToItem(target)
                withFrameNanos { }
                val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
                if (info != null) {
                    val viewport = listState.layoutInfo
                    val viewportCenter = (viewport.viewportStartOffset + viewport.viewportEndOffset) / 2f
                    val correction = ((info.offset + info.size / 2f) - viewportCenter).roundToInt().toFloat()
                    if (correction != 0f) listState.scrollBy(correction)
                }
                // 硬定位 → 高亮锚点与拉伸量必须一起瞬移，否则会看到一次从上行扫过来的假动画
                anchorPos.snapTo(active.toFloat())
                stretchAnim.snapTo(0f)
                glideTy = 0f   // 换歌是硬切，亚像素补偿必须清干净，不能留残值
                positionedForSong = true
                return@LaunchedEffect
            }

            // ② 之后每一次切换都走曲线；前奏期间没有切换可言
            if (active < 0) return@LaunchedEffect
            val target = active + lyricStartIndex // title + [本地歌词来源标注]

            /**
             * 把目标行滑到视口中心 —— **自带亚像素补偿**（2026-10-01 五轮根治）。
             *
             * 为什么不能用 `animateScrollBy`：`LazyListItemInfo.offset` / `viewportStartOffset` /
             * `viewportEndOffset` 全是 `Int`，**LazyColumn 的列表项只能落在整数像素上**。
             * 于是缓动的慢速尾段必然退化成「每 2~3 帧才跳 1px」的台阶 —— 录屏逐帧实测：
             * 焦点行上边缘 `211→210→210→209→209→209→208→208`，而当时真实速度只有 ~0.5px/帧。
             * 这就是用户报的「自动切换歌词时有轻微像素偏移」：不是落位偏了（实测每次都精确落在
             * 同一行），而是**慢速段一格一格地跳**。
             *
             * 做法：整数滚动只追「连续目标位置的最近整数」，把不超过 ±0.5px 的余量交给每行的
             * `graphicsLayer { translationY }`（纯渲染层变换，不受整数约束）。
             * 于是绘制位置 = -applied - (want - applied) = -want **完全连续**，一帧台阶都没有；
             * 而整数滚动与连续位置的偏差恒 ≤ 0.5px ⇒ 顶/底最多露出半像素的空带，肉眼不可见
             * （而且那一条带本来就被边缘模糊遮挡层盖住）。
             * 终点 want 正好是整数 ⇒ applied = 目标、余量 = 0 ⇒ 收尾干净，落位仍旧是整数像素。
             */
            suspend fun glideRowToCenter(
                durationMs: Int = LYRIC_GLIDE_MS,
                easing: androidx.compose.animation.core.Easing = if (lowPerformance) LinearEasing else LyricGlide,
            ) {
                val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
                if (info == null) {
                    glideTy = 0f
                    return
                }
                val viewport = listState.layoutInfo
                val viewportCenter = (viewport.viewportStartOffset + viewport.viewportEndOffset) / 2f
                val total = ((info.offset + info.size / 2f) - viewportCenter).roundToInt()
                if (total == 0) {
                    glideTy = 0f
                    return
                }
                var applied = 0
                val t0 = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    val u = ((now - t0) / 1_000_000f / durationMs).coerceIn(0f, 1f)
                    val want = total * easing.transform(u)
                    val whole = want.roundToInt()
                    if (whole != applied) {
                        listState.scrollBy((whole - applied).toFloat())
                        applied = whole
                    }
                    // 余量 ≤ 0.5px，交给渲染层；终端帧 want == total ⇒ 余量归零
                    glideTy = applied - want
                    if (u >= 1f) break
                }
                if (applied != total) {
                    listState.scrollBy((total - applied).toFloat())
                }
                glideTy = 0f
            }

            // 目标行在视口外（大幅跳转/快进）：先把它卷进视口 —— 这一段不做曲线，
            // 距离可能几十行，套 630ms 曲线会变成慢速长滚。锚点照常动画（高亮不能僵在旧行）
            if (listState.layoutInfo.visibleItemsInfo.none { it.index == target }) {
                listState.animateScrollToItem(target)
                withFrameNanos { }
            }
            // 高亮锚点与滚动同一帧启动、同一曲线同时长 —— 两者锁相。
            // 但瞬移（跨度 > LYRIC_ANCHOR_SNAP_LINES 行）改为直接落位：见常量处的说明。
            val sweep = abs(active - anchorPos.value)
            if (sweep > LYRIC_ANCHOR_SNAP_LINES) {
                anchorPos.snapTo(active.toFloat())
                stretchAnim.snapTo(0f)
            } else {
                launch {
                    anchorPos.animateTo(active.toFloat(), tween(LYRIC_GLIDE_MS, easing = if (lowPerformance) LinearEasing else LyricGlide))
                }
                // 行距拉伸回弹：起跳到峰值，再长尾弹回（190 / 470ms，与取色风格共用同一缓动）。
                // 不做 snapTo(0) —— 连打时（一句还没弹完下一句就到了）从当前值继续拉起，避免跳变；
                // 新的 animateTo 会通过 Animatable 的 MutatorMutex 自动取消上一次动画
                if (lowPerformance) {
                    stretchAnim.snapTo(0f)
                } else {
                    launch {
                        stretchAnim.animateTo(1f, tween(LYRIC_STRETCH_ATTACK_MS, easing = LyricGlide))
                        stretchAnim.animateTo(0f, tween(LYRIC_STRETCH_RELEASE_MS, easing = LyricGlide))
                    }
                }
            }
            glideRowToCenter()
            // ★ 2026-10-01 说明：四轮删掉的「残余校正」不是元凶（删完用户仍报偏移）。
            //   真正的原因是上面 glideRowToCenter 注释里写的那条 —— LazyColumn 只能整数滚动，
            //   慢速尾段退化成 1px 台阶。现在由亚像素补偿根治，不需要残余校正，也不要再往回加。
        }
        LaunchedEffect(userScrolling) {
            if (userScrolling) {
                delay(3500)
                userScrolling = false
            }
        }

        val lyricEdgeCorePx = LYRIC_EDGE_CORE_PX
        val lyricEdgeSpreadPx = with(density) { LYRIC_EDGE_SPREAD.toPx() }
        // 边缘淡出强度（2026-10-01 二轮口径）：手动浏览（表冠/拖动）时淡出量归零，边缘恢复
        // 全清晰 —— 用户口径「滑动浏览下方未播放歌词时，模糊渐变消失」。userScrolling 由
        // rotaryList / 拖动手势置位，3.5s 无操作后自动复位（见上方 LaunchedEffect）。
        //
        // ★ 2026-10-16：这里**不再驱动任何模糊层**，只是那条 DstIn 垂直渐变的强度 ——
        //   edgeK = 1 → 上下边缘淡出到全透明；edgeK = 0（浏览中）→ 渐变整体失效、全清晰。
        val edgeK by animateFloatAsState(
            targetValue = if (lowPerformance || userScrolling) 0f else 1f,
            animationSpec = tween(220),
            label = "lyricEdgeFade",
        )
        // 边缘渐隐层是否要参与离屏合成：edgeK 归零时渐变是恒等变换（画面完全一致），
        // 那一层 Offscreen 合成纯属浪费，直接不挂。
        // ⚠ 必须用 derivedStateOf 包成布尔：直接读 edgeK 会让 220ms 淡入淡出期间
        // **逐帧重组整个歌词页**，反而更卡；包成布尔后只在「开/关」翻转的那一帧重组。
        val edgeFadeOn by remember { derivedStateOf { edgeK > 0.001f } }
        val edgeFadeActive = !lowPerformance && lines.isNotEmpty() && edgeFadeOn

        // 歌词正文（★ 2026-10-16 只剩一层：原「底层模糊 + 上层擦除」的双列表结构已删，
        // 见上方 LYRIC_EDGE_CORE_PX 的注释 —— 那个「两层条目表必须逐项对齐」的硬不变量
        // 随之作废，不再需要第二份列表与每帧 scrollToItem 同步）；modifier 由调用处决定
        val lyricBody: @Composable (Modifier) -> Unit = { m ->
            CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                LazyColumn(
                    state = listState,
                    modifier = m,
                    // ★ 2026-10-17 用户口径「标题和第一句歌词之间不要有空行」：
                    //   原来首尾各有一个半屏 spacer **条目**（pad_top 夹在标题与第一句之间）——
                    //   列表滚到顶部时，标题下方就是半个屏幕的空白，正是用户看到的「空行」。
                    //   同样的半屏留白改放 contentPadding（列表两端之外、标题之上），
                    //   ⇒ 标题与第一句紧挨（只保留标题 8dp 的底边距/来源标注 6dp），
                    //   而「第一句也能滚到屏幕正中」的几何前提完全不变（首行居中所需滚动量
                    //   = 标题高度 + 半行高 > 0，不会被列表起点钳制）。
                    //   ⚠ 加载中/无歌词这两个兜底状态**不能**加半屏 padding：那里标题要贴顶、
                    //     提示文字在 30% 高度处（否则会被推到屏幕底部），所以按 lines 是否为空分流。
                    contentPadding = if (lines.isEmpty()) {
                        PaddingValues(0.dp)
                    } else {
                        PaddingValues(top = viewportHeight / 2f, bottom = viewportHeight / 2f)
                    },
                ) {
                item(key = "title") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            song?.title ?: "歌词",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = PlayTextPrimary,
                            modifier = Modifier
                                .widthIn(max = lyricSafeWidth)
                                .fillMaxWidth()
                                .padding(top = 52.dp, bottom = 8.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                // ★ 2026-10-04 本地歌曲的歌词来源标注：只有本地歌才非空。
                //   让用户一眼能分清「歌本身带的歌词」和「网上匹配来的」，错配时不会被当成官方歌词。
                //
                // 2026-10-05 曾有一条硬约束挂在这一项上：当年边缘模糊层是**第二份完整 LazyColumn**，
                //   两层靠「条目下标」同步 ⇒ 这一项只挂一侧就会让整列错位（本地歌「两层歌词」bug）。
                //   ★ 2026-10-16 双列表结构已整体删除（只留一层 + 纯渐变淡出），该约束作废。
                val srcText = lyricFrom
                if (srcText != null) {
                    item(key = "lyric_from") {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                srcText,
                                fontSize = 8.5.sp,
                                color = PlayTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .widthIn(max = lyricSafeWidth)
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                if (loading) {
                    item(key = "loading") {
                        Text(
                            "歌词加载中…",
                            fontSize = 11.sp,
                            color = PlayTextSecondary,
                            modifier = Modifier.fillMaxWidth().padding(top = viewportHeight * 0.30f),
                            textAlign = TextAlign.Center,
                        )
                    }
                } else if (lines.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            // ★ 2026-10-04 本地歌曲（id < 0）的路径是「歌旁 .lrc / 内嵌帧 → 在线兜底」，
                            //   两条都没命中时说「未找到本地歌词」比笼统的「暂无歌词」更准确
                            if ((song?.id ?: 0L) < 0) "未找到本地歌词" else "暂无歌词",
                            fontSize = 11.sp,
                            color = PlayTextSecondary,
                            modifier = Modifier.fillMaxWidth().padding(top = viewportHeight * 0.30f),
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    // 半屏首尾留白已由上面的 contentPadding 提供（不再用 pad_top/pad_bottom 条目，
                    // 否则标题与第一句之间会多出半个屏幕的空行 —— 见 contentPadding 处注释）。
                    itemsIndexed(
                        lines,
                        key = { index, line -> "l:${line.timeMs}:$index:${line.text}" },
                        contentType = { _, _ -> "lyric" },
                    ) { index, line ->
                        // ★ 2026-10-18「假逐字」：只有当前句需要逐字推进；没有 yrc 就用
                        //   fakeCharWords 按字数均分本句时长。其它行保持原样（不改它们的明暗）。
                        val ownerRow = index == activeIdx
                        val nextMs = lines.getOrNull(index + 1)?.timeMs
                        val revealWords = remember(index, line.text, line.words, line.timeMs, nextMs, ownerRow) {
                            when {
                                // 非当前句不需要逐字（明暗由 graphicsLayer 的 alpha 负责）
                                !ownerRow -> emptyList()
                                !line.words.isNullOrEmpty() -> line.words!!
                                // ★ 2026-10-18：按「演唱窗口」均分，不是「到下一句的间隔」——
                                //   间奏那一段没有新歌词，按间隔均分会让人一个字一个字亮十几秒。
                                else -> fakeCharWords(line.text, line.timeMs, singWindowMs(line, nextMs))
                            }
                        }
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            LyricRow(
                                modifier = Modifier.widthIn(max = lyricSafeWidth).fillMaxWidth(),
                                seekTimeMs = line.timeMs,
                                // 延迟读：距离只在 graphicsLayer 阶段求值，anchorPos 每帧变化
                                // 不会引起本行重组合，也不会让文本重新测量/排版
                                // 有符号：正 = 在当前行下方（行距拉伸要靠符号决定推开方向）
                                signedDistance = { index - anchorPos.value },
                                stretchAmount = { stretchAnim.value },
                                glideOffset = { glideTy },
                                // 白色扫光进度（延迟读：只在绘制期求值，见 drawLyricSweep）。
                                // ★ 2026-10-16 双列表删除后不再需要给镜像层传 NaN 关特效。
                                sweepFrac = { sweepFrac.floatValue },
                                // ★ 2026-10-18 光带归属（见 LyricRow 参数注释）：只有当前句
                                //   所在的那一行会画移动光带，换行瞬间不会在上一行重头扫一遍。
                                lineIndex = index,
                                sweepOwner = activeIdx,
                                // ★ 2026-10-18 浅底页面上把聚焦行亮度压 10%（见 LYRIC_FOCUS_DIM）；
                                //   扫光关掉时不做 —— 用户口径「保留原有的聚焦亮度」。
                                focusDim = if (lightBackdrop && sweepOn) LYRIC_FOCUS_DIM else 0f,
                                // ★ 2026-10-18 未唱字基态透明度：页面层按真实底色算好
                                //   （近黑底 = 原常量；中亮底抬高到可读下限，见 karaokeDimFor）。
                                karaokeDimBase = karaokeDimBase,
                                // 歌词取色：页面层算好的常量（已按本页底色做对比度限幅）
                                tint = lyricInkColor,
                                mainText = line.text,
                                translation = trans.transFor(line.timeMs),
                                roma = romaMap.nearFor(line.timeMs),
                                showRoma = showRoma,
                                // 字形光晕：透底场景的局部保底（页面层算好，null = 环境色模式不加）
                                halo = lyricHalo,
                                // ★ 2026-10-18 只有当前句传（真/假）逐字表 —— 未到达进度的字
                                //   要压到「与下方歌词同档」，唱到的字保持满亮。
                                //   扫光关闭时整句保持原有亮度（不传逐字表即可）。
                                words = if (ownerRow) revealWords else line.words,
                                karaokeOn = sweepOn && ownerRow && revealWords.isNotEmpty(),
                                lowPerformance = lowPerformance,
                                linePosMs = { karaokePosMs.floatValue },
                                // 罗马音/译文层透明度：封面模糊按报告 §一「恒定 0.50」
                                subAlpha = if (coverBlur) LYRIC_SUB_ALPHA_COVER else 0.70f,
                            )
                        }
                    }
                }
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .zIndex(if (swipeLifted) 2f else 0f)
                .graphicsLayer {
                    alpha = backFader.alpha
                    translationX = backFader.slidePx
                    scaleX = backFader.scale
                    scaleY = backFader.scale
                }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(onDragStart = { userScrolling = true }) { change, amount ->
                        change.consume()
                        listState.dispatchRawDelta(amount)
                    }
                }
                .pointerInput(AppearancePrefs.isM3e) {
                    if (AppearancePrefs.isM3e) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { backFader.onDragStart(); swipeLifted = true },
                        onDragEnd = {
                            val goBack = backFader.passedThreshold
                            backFader.onDragEnd(goBack)
                            if (goBack) {
                                // ★ 2026-10-01：歌词页 → 播放页保持**滑动**转场（用户要求）。
                                //   所以这里**不武装手势旗标**：一旦武装，NavHost 就走「淡出 + 淡入」
                                //   的手势分支；不武装则按 LYRICS entry 自己记的 Slide 来源走
                                //   popExit/popEnter 的滑动分支（旧页左移滑出、播放页从左侧滑入），
                                //   与 fader 的位移滑出接成一条连贯的横向滑动。
                                val ms = backFader.flingOutDuration(screenPx)
                                nav.popBackStack()
                                backFader.flingOut(screenPx, ms)
                            } else {
                                swipeLifted = false
                            }
                        },
                        onDragCancel = { backFader.onDragCancel(); swipeLifted = false },
                    ) { change, amount ->
                        change.consume()
                        backFader.onDrag(change, amount)
                    }
                }
                .rotaryList(listState, sensitivity = 0.4f, onUserInput = { userScrolling = true }),
        ) {
            // 默认主题背景由 AppRoot 全局层负责；m3e 仍由页面自带。
            if (com.netmusiclite.data.AppearancePrefs.isM3e) {
                com.netmusiclite.ui.components.NcmBackground(route = Routes.LYRICS, prepareResources = false)
            }
            // 禁用列表边缘 stretch 拉伸（Android 12+ 果冻回弹）：歌词页高频触边（自动跟随+表冠滚），
            // 拉伸回弹在圆表上被放大观感且费 GPU —— 关闭后到边即停，回弹彻底消失
            //
            // ★ 2026-10-16 边缘「纯渐变淡出」（原「边缘高斯模糊遮挡」）：
            //   旧结构 = 底层整屏模糊歌词 + 上层清晰歌词用 DstIn 擦掉上下边缘露出底层
            //   （模糊层走 GPU 的 Modifier.blur，API 31+ 才生效 —— 三星表就是它整屏发糊）。
            //   现在只剩一层：直接把**歌词自己**在上下边缘用垂直渐变淡出（DstIn 乘 alpha）。
            //   渐变关键点：距边框 16px 内为完全透明，之后 55dp 内渐变过渡到全不透明。
            //   edgeK→0（手动浏览中）时边缘 alpha→1，等于完全不淡出，歌词全清晰。
            //   「发音」胶囊在下面最后绘制，不受这层影响。
            //   edgeFadeActive 在上方定义（淡出量归零时整层退出组合，省一次离屏合成）。
            lyricBody(
                Modifier
                    .fillMaxSize()
                    .then(
                        // 淡出量归零时渐变是恒等变换，做与不做画面完全一致，但离屏合成是实打实的开销。
                        if (!edgeFadeActive) Modifier
                        else Modifier
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .drawWithContent {
                                drawContent()
                                val end = ((lyricEdgeCorePx + lyricEdgeSpreadPx) / size.height)
                                    .coerceIn(0.02f, 0.49f)
                                // DstIn：src.alpha 直接乘进目标 alpha —— 边缘 alpha 越接近 0 淡得越干净。
                                // edgeK=1 → 最外沿 alpha=0（完全淡出）；edgeK=0 → 边缘 alpha=1（不淡出）。
                                val edgeAlpha = 1f - edgeK
                                drawRect(
                                    Brush.verticalGradient(
                                        0f to Color.Black.copy(alpha = edgeAlpha),
                                        end to Color.Black,
                                        1f - end to Color.Black,
                                        1f to Color.Black.copy(alpha = edgeAlpha),
                                    ),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                    ),
            )

            CircularSideProgress(
                progress = {
                    val count = lines.size
                    val active = activeIdx
                    if (count == 0) 0.15f else ((active + 1).toFloat() / (count + 1)).coerceIn(0.15f, 1f)
                },
                modifier = Modifier.fillMaxSize(),
                // 与歌词墨色同源：取色 / 封面模糊风格跟专辑封面主题色，自定义风格跟壁纸
                // ★ 2026-10-07 明暗对调：走 Play* 变体（作用域外 = 原值）
                accent = if (AppearancePrefs.playShowsWallpaper) {
                    PlayAccent
                } else {
                    PlaySongAccent
                },
            )
            // ★ 2026-10-01 用户口径「没有发音文件的歌词不显示发音按钮」：
            //   之前是按钮恒在、没有罗马音时置灰 —— 置灰仍然占着顶部那条位置。
            //   现在这首歌没有 roma 数据就整个不画，顶部干净。
            if (!AppearancePrefs.isM3e && romaMap.values.any { it.isNotBlank() }) {
                ArcPill(
                    label = "发音",
                    active = showRoma,
                    onToggle = { showRoma = !showRoma },
                    modifier = Modifier.align(Alignment.TopCenter).offset(y = 10.5.dp),
                    // ★ 2026-10-07 用户口径：选中态的实底跟**专辑封面主题色**（取色 / 封面模糊
                    //   风格，与歌词墨色/边弧同源）；自定义风格（透壁纸）仍然跟壁纸。
                    //   ★ 2026-10-07 明暗对调：走 Play* 变体（作用域外 = 原值）
                    activeColor = if (AppearancePrefs.playShowsWallpaper) {
                        PlayAccent
                    } else {
                        PlaySongAccent
                    },
                )
            }
        }
    }
}

@Composable
private fun LyricRow(
    modifier: Modifier,
    seekTimeMs: Long,
    signedDistance: () -> Float,
    stretchAmount: () -> Float,
    /** 亚像素滚动补偿（2026-10-01 五轮）：整列一起平移，只补 |·| ≤ 0.5px 的零头 */
    glideOffset: () -> Float,
    /**
     * 白色扫光进度（2026-10-02）：0 = 光斑在行左端外侧，1 = 已扫出行右端外侧，
     * < 0 = 不扫光（前奏/暂停/已扫完），只保留底噪提亮；**NaN = 本层完全不做扫光**。
     * 同样是延迟读。
     */
    sweepFrac: () -> Float,
    /**
     * ★ 2026-10-18 扫光的**归属行**：`lineIndex` = 本行在歌词表里的下标，
     * `sweepOwner` = 当前正在唱的那一行下标（= activeIdx）。
     *
     * 为什么需要：`sweepFrac` 是**整页唯一**的一份进度，它描述的是「当前这一句」的扫光；
     * 但旧实现里每个进入焦点带的行走都用 `focusK` 去乘它 —— 换行瞬间（anchorPos 要
     * 630ms 才滑到新行）上一行的 focusK 还是 1、新行还是 0，于是**同一条光带的进度
     * 被画在了上一行上**：上一行从左端重新亮一下再淡出，新行随后再从头扫一遍。
     * 观感就是「同一句歌词在上下两行里串行扫」。
     * 现在光带只由**它真正所属的那一行**绘制（lineIndex == sweepOwner），
     * 其余行只剩底噪提亮（那是 focusK 的职责，与光带无关）。
     */
    lineIndex: Int = -1,
    sweepOwner: Int = -1,
    /**
     * ★ 2026-10-18 聚焦行亮度压暗量（0 = 关闭）：浅底页面上「亮度过高」的那一行按比例压暗，
     * 见 [LYRIC_FOCUS_DIM] / [drawFocusDim]。页面层判定浅底后才传非 0。
     */
    focusDim: Float = 0f,
    /**
     * ★ 2026-10-18 聚焦行未唱字的基态透明度（默认 = 旧常量）：页面层按**真实底色**
     * 算出可读下限（见 [karaokeDimFor]）—— 近黑底接近 [LYRIC_KARAOKE_DIM]，
     * 封面模糊 / 浅色底等中亮底会更高，避免未唱字整句发黑。
     */
    karaokeDimBase: Float = LYRIC_KARAOKE_DIM,
    /**
     * 歌词取色（2026-10-01）：由 LyricsScreen 算好的常量（见 theme/Theme.kt 的 lyricInk）。
     * 必须是常量 —— 逐帧变化的颜色会让 Paragraph 缓存整体失效，等价于每帧重排版。
     */
    tint: Color,
    mainText: String,
    translation: String? = null,
    roma: String? = null,
    showRoma: Boolean,
    /**
     * 字形光晕颜色（2026-10-02 方案 1 二代，null = 不加）：**浅色主题**透底场景给
     * **焦点行**字形垫的一圈反向柔光（深墨配白），由页面层算好传入 —— 全程不碰
     * TextStyle/Paragraph，强度在绘制期按与焦点行的距离渐变（见 [drawFocusHalo] /
     * [lyricHaloAmount]）。深色主题/环境色模式恒为 null，绘制路径与改动前一致。
     */
    halo: Color? = null,
    /** 逐字数据（2026-10-06 用户口径③）：null = 这一行没有逐字信息（老接口/本地文件/纯 lrc） */
    words: List<LyricWord>? = null,
    /** 有逐字数据时只增加已唱/未唱明暗遮罩，扫光仍按整行运行。 */
    karaokeOn: Boolean = false,
    lowPerformance: Boolean = false,
    /**
     * 当前句的**连续播放位置**（ms，逐帧外推；来源见 LyricsScreen 的 karaokePosMs）。
     * **NaN = 本层不做逐字点亮**（边缘模糊镜像层）。只在 [karaokeOn] 且本行进入焦点带时
     * 才被读 —— 其余行连这次快照读都不会发生（延迟读，零重组）。
     */
    linePosMs: () -> Float = { Float.NaN },
    /** 罗马音/译文层透明度（封面模糊按报告 §一「恒定 0.50」，其余风格维持 0.70） */
    subAlpha: Float = 0.70f,
) {
    // 逐字明暗使用主歌词的真实字符框，兼容换行和居中排版。
    // 只有需要逐字的行才登记回调 —— 其余行连这次 lambda 分配都不做。
    // 存的是**可空**列表本身（而不是「非空 + 布尔」两件套）：绘制期那条 `!= null` 判定
    // 才拿得到智能转换，逐帧路径上不用再写一次 !! 或局部兜底。
    val karaokeWords = if (karaokeOn) words else null
    var layoutRes by remember { mutableStateOf<TextLayoutResult?>(null) }
    // ★ 2026-10-07 明暗对调：明暗在组合期读好，绘制期（drawWithContent）只读这个常量 ——
    //   CompositionLocal 只能在组合上下文里读，绘制 lambda 里读不了（见扫光底噪分支）
    val sweepLight = playLightTheme
    // 流畅度专项（骁龙 W5）：
    // ① 字号/字重/内边距逐帧动画会强制每帧文本重新测量+排版+布局 —— 歌词页掉帧元凶，
    //    改为固定排版 + graphicsLayer 缩放（纯渲染层，零重排版）。
    // ② 2026-09-30 二代：连「颜色动画」也移出 Text。animateColorAsState 每帧产出新
    //    TextStyle，会让 Paragraph 缓存整体失效，等价于每帧重排版。现在文字颜色全部为常量，
    //    明暗完全由图层 alpha 承担，并且随「与当前行的连续距离」连续变化。
    // ③ 同二代：行距拉伸回弹也走 translationY —— 只推间距、不动字形、不触发布局。
    // ④ 2026-10-02 字形光晕：同样不进 TextStyle —— 它是绘制期的一层膨胀剪影（见下方
    //    drawWithContent 的调用点），强度逐帧渐变也不触发任何文本测量/排版。
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { PlayerEngine.seekTo(seekTimeMs) }
            .graphicsLayer {
                // 唯一每帧变化的状态在这里求值（延迟读）：只失效本行图层，
                // 不触发重组合，也不触发任何文本测量/排版
                val d = signedDistance()
                if (lowPerformance) {
                    // 低性能模式只平移固定字形。连续非整数缩放会重采样文字贴图，
                    // 到 1f 时又切回直接绘制，造成切行时的字体颤抖（正常模式已保护可读带）。
                    scaleX = 1f
                    scaleY = 1f
                    alpha = 1f
                    translationY = glideOffset()
                    return@graphicsLayer
                }
                // |d| ≤ 0.001 即「这一行就是当前行」—— alpha 必须严格取 1f
                val focused = abs(d) <= 0.001f
                // 字号缩放：
                //  二轮：上限锁 1.0（当前行不放大，避免放大带来的重采样）。
                //  三轮：删掉 1/16 档位量化（量化会在阈值处硬跳 6.25%，就是「缩放抽动」）。
                //  四轮（用户口径「切换歌词还是有轻微的像素偏移」）：见 lyricScaleAmount 的注释 ——
                //    **非整数 scale 本身就会带来亚像素位移**，所以可读带（当前行 ±2 行）内一律
                //    保持 scale = 1.0 的恒等变换，缩放层级只在被模糊掉的远端体现。
                val scale = 1f - LYRIC_SCALE_DEPTH * lyricScaleAmount(d)
                scaleX = scale
                scaleY = scale
                // 当前行必须**严格** alpha = 1f。只要差一个 ULP（0.99999994）——
                // 硬件就会把它当成半透明图层，为此多走一次离屏合成；
                // 半透明 + 变换正是亚像素错位的另一条来路。
                // ★ 2026-10-01 六轮：亮度改用随距离连续衰减的渐变曲线（见 lyricBrightness 注释）。
                // 已唱句比同距离的未唱句更亮，焦点仍保持完全不透明。
                alpha = if (focused) 1f else lyricBrightness(d) * (1f - 0.18f * d.coerceIn(0f, 1f))
                // 行距拉伸：以当前行为中心把各行向外推开（d=0 的当前行不动，即保持锚定）。
                // 位移量 = 行距离 × 行高 × 拉伸比例 —— 比例相同 ⇒ 所有间距等比拉伸，
                // 这正是参考界面「整列拉开再弹回」的观感；字形因不参与缩放而完全不变形。
                // ＋ 亚像素滚动补偿（glideOffset，|·| ≤ 0.5px）：让整数滚动看不见的零头也连续。
                translationY = d * size.height * (LYRIC_STRETCH_PEAK * stretchAmount()) + glideOffset()
                // ★ 2026-10-16 删除「逐行模糊梯队」（原在这里按距离给每行挂 RenderEffect =
                //   BlurEffect，见上方常量区注释）：三星表上 RenderEffect 不遵守节点边界，
                //   远景行的糊会溢到可读带上 = 用户报的「模糊范围异常」。
                //   本行图层现在只有 scale / alpha / translationY 三个纯变换参数，零 GPU 特效。
            }
            // ★ 2026-10-02 聚焦行提亮 + 白色光线扫过（用户口径①②，实现与理由见 drawLyricSweep）。
            //   放在 graphicsLayer 之后、padding 之前：绘制范围 = 整行（含内边距），
            //   光斑可以完整地从行左端扫到右端，不会被内边距提前截断。
            .drawWithContent {
                if (lowPerformance) {
                    drawContent()
                    return@drawWithContent
                }
                val d = signedDistance()
                val f = sweepFrac()
                // 与焦点行的连续距离（1 = 焦点行，0 = 焦点带外）：扫光与逐字点亮共用。
                // 延迟读（绘制期求值）⇒ anchorPos 逐帧变化不引起任何重组合/重排版。
                val focusK = (1f - abs(d) / LYRIC_SWEEP_BAND).coerceIn(0f, 1f)
                // ★ 2026-10-18 聚焦行亮度 ×(1 - focusDim)（用户口径「亮度过高，降 10%，
                //   只在背景为浅色触发」）：强度跟着 anchorPos 走同一条连续曲线，
                //   换行时和歌词光晕一起滑过去，不会 10% 硬跳。实现见 drawFocusDim。
                val dimK = focusDim * lyricHaloAmount(d)
                // ★ 2026-10-02 方案 1 二代（用户口径「改为只有聚焦歌词存在光晕，而且光晕
                //   渐变出现」）：先在本行内容**底下**铺一层晕 —— 强度 hk 由与焦点行的
                //   连续距离 d 在绘制期求值（延迟读，零重组/零重排版）：
                //     静止时 |d| ∈ {0,1,2…} ⇒ 恰好只有聚焦那一行 hk = 1；
                //     切换歌词 d 连续滑动 0⇄1 ⇒ 光晕随整列滑动渐变出现/消失。
                //   NaN = 边缘模糊镜像层（只在屏幕最上下缘露出）不做，省一次离屏合成。
                if (halo != null && !f.isNaN()) {
                    val hk = lyricHaloAmount(d)
                    if (hk > 0.01f) {
                        // 光晕只取字形**轮廓**，走原始内容（不带压暗层 —— 那会变成 9 层离屏）
                        drawFocusHalo(drawOriginal = { drawContent() }, color = halo, k = hk)
                    }
                }
                // 逐字只决定已唱/未唱的明暗；扫光始终复用下面的整行时间轴。
                // 用每个字符的排版框做遮罩，自动换行和译文不再被水平前沿误伤。
                val kw = karaokeWords
                val lr = layoutRes
                val pos = if (kw != null && focusK > 0.01f) linePosMs() else Float.NaN
                val karaokeNow = kw != null && lr != null && !pos.isNaN() && focusK > 0.01f
                val textLeft = if (lr != null) (size.width - lr.size.width) / 2f else 0f
                val textTop = LYRIC_ROW_PAD_V.toPx()
                val drawLine: () -> Unit = {
                    if (karaokeNow) {
                        drawKaraokeDim(
                            drawOriginal = { drawContent() },
                            words = kw!!,
                            posMs = pos,
                            layout = lr!!,
                            left = textLeft,
                            top = textTop,
                            dim = 1f - (1f - karaokeDimBase) * focusK,
                        )
                    } else {
                        drawContent()
                    }
                }
                // ★ 2026-10-18 光带也按同一份逐字进度收边：未唱到的字**一点光都不留**
                //   （底噪 + 光带都擦掉），已唱到的字保持原有亮度（"逐字效果亮度不变"）。
                val revealMask: (() -> Unit)? = if (karaokeNow) {
                    {
                        drawCharProgressMask(
                            words = kw!!,
                            posMs = pos,
                            layout = lr!!,
                            left = textLeft,
                            top = textTop,
                            skipOpaque = false,
                        ) { p -> p }
                    }
                } else null
                // NaN = 本层不做扫光（边缘模糊镜像层用，见调用处：那层只在屏幕最上下缘露出，
                // 而焦点行恒在屏幕中央，画了也看不见，白费一次离屏合成 + 一次文本重绘）
                //
                // ★ 2026-10-18 光带只属于它自己的那一行（见 [lineIndex] / [sweepOwner] 的注释）：
                //   旧版这里是「所有进入焦点带的行走 × focusK」—— 换行瞬间上一行 focusK 仍是 1、
                //   却拿到**新一句**刚归零的 f，于是光带在上一行左端又亮一次，再淡出、再在新行
                //   从左端重扫 ⇒ 「同一句歌词在不同行串行扫」。现在逐行判定归属：
                //     · owns = 本行就是当前句所在的行 ⇒ 画光带、强度**不再乘 focusK**
                //       （本行自己的 graphicsLayer alpha 已经在做「进入/离开」的渐变）；
                //     · 非 owns 行 ⇒ 只有底噪（浅色主题恒 0），一次光带都不画。
                val owns = lineIndex >= 0 && lineIndex == sweepOwner
                val bandOn = f >= 0f && owns
                // ★ 浅色主题：聚焦行是**深色字**，再整体叠白等于把「深字压浅底」的对比度抹平 ——
                //   所以浅色主题下底噪置 0，只保留**移动光带**那一下（瞬时白光扫过字形）。
                //   深色主题底噪照旧（彩色/亮字整体提亮 = 用户口径①的「提高亮度」）。
                //   两个主题共用同一条光带、同一套位置/时长，观感完全同步。
                val base = if (sweepLight) 0f else LYRIC_SWEEP_BASE * focusK
                // 归属行不乘 focusK：那句歌词一开口光带就该在它自己身上，
                // 不能等 630ms 的锚点滑到位才显形（旧版正是靠上一层行「借」了这 630ms 的亮度）
                val peak = if (bandOn) LYRIC_SWEEP_PEAK else base
                // 本行要不要画扫光层：f 为 NaN（低性能/无逐字）或底噪/峰值都归零时整层不做。
                // 旧版这里走的是「paint(); return」，现在改成布尔量 —— 因为内容只画一遍、
                // 最后统一过「聚焦压暗」（见下面的 drawRow）。
                val sweepOn2 = !f.isNaN() && peak > 0.01f
                val w = size.width
                val h = size.height
                // ── 光带几何（★ 2026-10-18 用户口径「光跟着字走」）─────────────────────
                // · 单行句子：一条横贯整行的径向光带 —— 与改动前**完全一致**（零回归）。
                // · 折成多行的句子：光带**按显示行逐行推进** ——
                //     f 先按各显示行的行宽加权分段（短行分到的时长按比例少 ⇒ 光的"字速"恒定），
                //     光斑只落在当前这一行的行框内（径向按行高纵向压扁成椭圆，别行只剩底噪），
                //     行内仍然走「行左端外侧 → 行右端外侧」的同一套几何。
                //   ⇒ 观感是光沿着字一行一行走下来，而不是一根竖光柱同时压过好几行。
                val lrp = lr
                var radius = (w * LYRIC_SWEEP_RADIUS).coerceAtLeast(1f)
                var cx = -radius + f.coerceIn(0f, 1f) * (w + radius * 2f)
                var cy = h / 2f
                var vScale = 1f
                if (bandOn && lrp != null && lrp.lineCount > 1) {
                    val count = lrp.lineCount
                    val textLeft = (w - lrp.size.width) / 2f
                    val textTop = LYRIC_ROW_PAD_V.toPx()
                    // 每行的行进宽度（不取整行宽：短行光斑同比缩小，扫得快些才对得上"字速"）
                    val widths = FloatArray(count) { i ->
                        (lrp.getLineRight(i) - lrp.getLineLeft(i)).coerceAtLeast(1f)
                    }
                    var total = 0f
                    for (i in widths.indices) total += widths[i]
                    var acc = f.coerceIn(0f, 1f) * total
                    var li = 0
                    var local = 0f
                    while (li < count) {
                        val last = li == count - 1
                        if (acc <= widths[li] || last) {
                            local = (acc / widths[li]).coerceIn(0f, 1f)
                            break
                        }
                        acc -= widths[li]
                        li++
                    }
                    val lineW = widths[li]
                    val left = textLeft + lrp.getLineLeft(li)
                    val top = textTop + lrp.getLineTop(li)
                    val bottom = textTop + lrp.getLineBottom(li)
                    radius = (lineW * LYRIC_SWEEP_RADIUS).coerceAtLeast(1f)
                    cx = left - radius + local * (lineW + radius * 2f)
                    cy = (top + bottom) / 2f
                    // 纵向压扁：竖直半径 ≈ 0.62 行高 ⇒ 光只盖住本行，不会糊到上下邻行
                    vScale = (((bottom - top) * 0.62f) / radius).coerceIn(0.04f, 1f)
                } else {
                    // 光斑半径与中心：frac = 0 时整个光斑在行左端外侧，= 1 时已扫出右端外侧
                    radius = (w * LYRIC_SWEEP_RADIUS).coerceAtLeast(1f)
                    cx = -radius + f.coerceIn(0f, 1f) * (w + radius * 2f)
                }
                // ★ 2026-10-02 用户口径「扫光仅在歌词字体上面显示」：原「第①层」铺在材质上的
                //   背景光晕（BlendMode.Plus 的径向白光）已整体删除 —— 光只经下面的
                //   drawLyricSweep 以 SrcIn 剪影落在字形不透明像素上，行的底色不再发光。
                //
                // ★ 2026-10-18：内容只画一遍，扫光叠在它上面，**最后**统一过「聚焦压暗」。
                //   压在最后是因为它必须作用在"最终观感"上 —— 塞在扫光层里面的话，
                //   白光会把那 10% 又提回去（实测只能压到 ~7%）。
                //   顺带把每帧的文本绘制从 3 遍降到 2 遍（扫光层本来就要重画一遍剪影）。
                val drawRow: () -> Unit = {
                    drawLine()
                    if (sweepOn2) {
                        // ── 字形提亮 + 白色光线扫过（画在文字之上，见 drawLyricSweep）──
                        drawLyricSweep(
                            drawOriginal = { drawLine() },
                            base = base,
                            peak = peak,
                            cx = cx,
                            radius = radius,
                            cy = cy,
                            vScale = vScale,
                            revealMask = revealMask,
                        )
                    }
                }
                if (dimK > 0.004f) {
                    drawFocusDim(drawOriginal = drawRow, amount = dimK)
                } else {
                    drawRow()
                }
            }
            .padding(horizontal = 8.dp, vertical = LYRIC_ROW_PAD_V),
    ) {
        Text(
            mainText,
            fontSize = 15.sp,
            lineHeight = 16.sp,
            // Apple Music 材质（2026-09-25）：所有行同等超重 W900，仅靠图层 alpha 分层
            fontWeight = FontWeight.Black,
            // 2026-10-01 主题化：浅色主题下歌词页是浅底，白字会彻底消失
            // ★ 2026-10-01 歌词取色：改为「自动取色」的强调色（已按本页底色限幅保证对比度）。
            //   明暗分层仍由外层 graphicsLayer 的 alpha 承担，这里只定色相。
            color = tint,
            textAlign = TextAlign.Center,
            // 逐字明暗用排版结果定位每个字符：
            // 只在有逐字数据的行登记回调。写的是**只被绘制期读**的状态 ⇒ 只让本行重绘，
            // 不引起重组、不触发第二次测量（本页铁律见 LyricRow 顶部注释）。
            // ★ 2026-10-18：**所有行**都登记 —— 折行扫光（光跟着字走）要靠 lineCount /
            //   getLineTop… 拿到每个显示行的行框；没有这份排版就只能退回"竖光柱压整块"。
            //   回调只在文本重新测量时触发（本页文本排版是常量），写入的 state 只被绘制期读。
            onTextLayout = { l: TextLayoutResult -> layoutRes = l },
        )
        if (showRoma && !roma.isNullOrEmpty()) {
            Text(
                roma,
                fontSize = 9.5.sp,
                lineHeight = 10.sp,
                color = tint.copy(alpha = subAlpha),
                textAlign = TextAlign.Center,
            )
        }
        // ★ 2026-10-04「歌词重影」修复（用户反馈「带翻译的本地音乐歌词出现重影」）：
        //   本地 lrc 有两种常见写法会让**译文与主歌词内容完全相同** ——
        //   ① 同一时间戳把原文行重复写了两遍；② 一行里挂两个相同的时间标签。
        //   两者都会被解析成「主歌词 + 内容一模一样的译文」，上下两行同样的字 = 重影。
        //   这里做最后一道兜底：译文与主歌词（去空白后）相同就不画。
        val transShown = translation?.takeIf { it.isNotBlank() && it.trim() != mainText.trim() }
        if (transShown != null) {
            Text(
                transShown,
                fontSize = 9.5.sp,
                lineHeight = 10.sp,
                color = tint.copy(alpha = subAlpha),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ArcPill(
    label: String,
    active: Boolean,
    // 2026-10-01：没有发音数据的歌连按钮都不画了，这个开关就没了用武之地，改成默认可用
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    /**
     * 选中态实底色。★ 2026-10-07 用户口径：取色 / 封面模糊风格改传**专辑封面主题色**
     * （SongAccent，调用点见「发音」胶囊）—— 与歌词墨色/进度弧同一条规则；
     * 默认 [Accent]（= 壁纸/预设种子），非歌词页的用法不受影响。
     */
    activeColor: Color = Accent,
    onToggle: () -> Unit = {},
) {
    // ★ 2026-10-02 用户反馈：浅色主题下发音按钮读不出来 —— 底色硬编码深玻璃 Color(0xB3202020)，
    //   而浅色板 TextSecondary = 70% 黑，深字压深玻璃。按已定规范「浅色未选中 = 纯白 + 深字」
    //   （与排行页胶囊一致）单独做一套；深色主题维持原玻璃底 + 浅灰字。
    //   选中态两主题统一：Accent 实底 + 反色字（Accent/OnAccent 本身随主题自适应）。
    //   ★ 2026-10-07 明暗对调：明暗读 playLightTheme；选中态反色字按**实底自身**亮度取
    //   （activeColor 在作用域内是翻过的专辑主题色，用全局 OnAccent 会与之脱钩）。
    val light = playLightTheme
    val foreground = when {
        active -> onColorFor(activeColor)
        !enabled -> PlayTextSecondary.copy(alpha = 0.4f)
        light -> PlayTextPrimary
        else -> PlayTextSecondary
    }
    Text(
        label,
        fontSize = 10.sp,
        color = foreground,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) activeColor else if (light) Color.White else Color(0xB3202020))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggle,
            )
            .padding(horizontal = 11.dp, vertical = 4.dp),
    )
}
