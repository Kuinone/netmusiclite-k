package com.netmusiclite.ui.components

import com.netmusiclite.ui.m3e.m3eSwipeLeft
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.BgElevated
import com.netmusiclite.ui.theme.LocalUiScale
import com.netmusiclite.ui.theme.SurfaceGlass
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import com.netmusiclite.ui.theme.playLightTheme
import com.netmusiclite.ui.theme.PlayBgElevated
import com.netmusiclite.ui.theme.PlaySurfaceGlass
import com.netmusiclite.ui.theme.PlaySurfaceGlassBorder
import com.netmusiclite.ui.theme.PlayTextPrimary
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.OverlayBackHandler
import com.netmusiclite.ui.nav.QzZoom
import com.netmusiclite.ui.nav.TransitionCoordinator
import com.netmusiclite.ui.theme.T
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** 玻璃高光渐变：静态缓存（流畅度 R7）——滚动期每帧 draw 不再新建 Brush 对象 */
private val GLASS_HIGHLIGHT_BRUSH = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.16f),
    0.35f to Color.Transparent,
)

/** 边缘高光描边：玻璃模式（[com.netmusiclite.data.BackgroundStore.cardGlass]）下卡片轮廓 ——
 *  · 浅色（含播放器组对调到浅色）：卡片模糊强度 < 50% 才描边（轻模糊透底清晰，描边辅助轮廓；
 *    重模糊本身有层次，不叠加）—— 原口径不变；
 *  · 深色（★ 2026-10-16）：**恒描边** —— 深色卡底（石墨素底 / 取样玻璃）与背景的亮度差
 *    天然很小，没有这条线卡片边界立不住。旧版「深色无壁纸恒不描边 + 重模糊恒不描边」
 *    正是用户口径「深色卡片材质劣质感」的来源之一；
 *  · 黑色卡片模式（玻璃开关关）不描边 —— 那是用户主动选的观感。
 *  ★ 2026-10-07 明暗读 [playLightTheme]（播放器组对调作用域内翻到另一套板）。 */
@Composable
fun Modifier.glassEdgeHighlight(shape: Shape): Modifier =
    if (!com.netmusiclite.data.AppearancePrefs.isM3e && com.netmusiclite.data.BackgroundStore.cardGlass &&
        (!playLightTheme || com.netmusiclite.data.BackgroundStore.cardBlur < 0.5f)
    )
        this.border(BorderStroke(1.dp, PlaySurfaceGlassBorder), shape)
    else this

/**
 * 环境色底是否仍是「纯基底」（★ 2026-10-16 二次修正，供 frostedGlass 无取样兜底分流）：
 * 未播放 / 取色从未落库时 [SongAmbient.color] 停在基底 #0A0A0C（三通道和 0.125）；
 * 播放中它是封面主色向黑压暗 75% 的**彩色取色底**（换歌即换），任何彩度都显著高于基底。
 * true = 底色近黑无彩 → 卡片画石墨抬升底（透出的底同色近黑，等于没有）；
 * false = 有真实取色底 → 卡片半透明透出取色色。
 */
private fun ambientBaseIsFlat(): Boolean {
    val c = SongAmbient.color
    return c.red + c.green + c.blue < 0.15f
}

/**
 * 磨砂玻璃卡片材质：背景图取样模糊 + 半透明填充；
 * 边缘高光跟随模糊强度：< 50% 绘制（辅助轮廓），≥ 50% 不画（干净玻璃）。
 * 无背景图 → 玻璃整体关闭（2026-09-13 全局生效）：卡片底色改半透明黑（融入环境色背景）。
 * ★ 2026-10-02 用户口径「浅色模式下未选择壁纸也可以设置为玻璃卡片」：浅色 + 无图 +
 * 开关开 → 新增「透底轻玻璃」分支（LightPalette 白 72% 填充 + 高光描边），开关不再空转。
 * ★ 2026-10-16 用户口径「深色模式卡片材质劣质感」修复（三处，浅色分支不动作）：
 *  ① 深色无可取样底的场合不再画纯黑 45%（与底色差仅 ~4.5/255，卡片糊进底里、无光无轮廓），
 *     改「石墨抬升底 + 顶部高光」——与 BgElevated 同族，与底色差 ~12/255；
 *  ② 取样底从「自定义壁纸」扩到「封面模糊底」——默认外观模式（封面模糊）下卡片取身后
 *     真实的底、随歌变色（旧版壁纸没在画就一律走纯黑，卡片与底毫无关联）；
 *  ③ 深色恒加顶部高光（旧版只有「卡片模糊 < 50%」才画，默认滑杆 = 1 的深色卡片永远无光）。
 *     描边同步：glassEdgeHighlight 深色恒描边（见其注释）。
 *  ★ 2026-10-16 二次修正（用户口径「播放音乐有背景取色的时候他不会透明透过」）：
 *     石墨抬升底收窄到「底色 = 近黑纯基底」的场合；播放中有**取色底**（SongAmbient.color
 *     是封面主色、页面底看得见颜色）时兜底恢复 45% 半透明黑透底 —— 抬升底 94% 不透明
 *     会把取色底盖死，这就是取色场景「透不过去」的来源。判据见 ambientBaseIsFlat()。
 *  ★ 2026-10-16 三次修正（用户口径「深色模式玻璃卡片改用现成的高斯模糊库，卡片模糊身后」）：
 *    ① 卡片**取样底图的模糊算法**换成 wasabeef/Blurry 的 ScriptIntrinsicBlur 真高斯
 *       （`data/GlassBlur.kt`；底图生成在 `BackgroundStore.regenerateBlurred`，
 *       仍是「整屏一张底 + 卡片按屏幕位置取样」的老架构，只是算法换了库）；
 *    ② **没有可取样底图但有环境取色**时不再画纯黑 45%，改用环境色 + BgElevated 抬升做玻璃底
 *       （见下方分支注释）——「不再画纯黑块」。
 *  ★ 2026-10-16 四次修正（用户口径「深色模式无壁纸的时候有默认壁纸取色当前的专辑封面底色
 *    或者专辑封面底色模糊」）：**深色主题 + 无壁纸时，取样底不再是 null** ——
 *    页面底就是当前歌曲封面取色（[SongAmbient.color]），用户口径里这就是那台「默认壁纸」，
 *    所以把**当前歌曲封面模糊成的底图**（CoverBackdrop，见 [darkCardCoverBlur]）接进卡片取样，
 *    深色下全站卡片都是随歌变色的真玻璃。配套两处：
 *      · 取样坐标改为**按底图尺寸等比折算**（壁纸底 466 / 封面底 96，边长不同，见下方 rootW/rootH）；
 *      · 设置页在「深色 + 无壁纸」下也放出「玻璃卡片」开关（原来被隐藏）。
 *    页面底仍保持取色不变（用户明确「页面背景仍保持原来的取色」）。
 */
@Composable
fun Modifier.frostedGlass(shape: Shape, fill: Color = PlaySurfaceGlass, forceGlass: Boolean = false): Modifier {
    if (forceGlass && playerCoverGlassMatchesBackground()) {
        // 2026-10-17 用户口径「按钮亮度永远比亮色封面暗 15%、比暗色封面亮 15%」：
        //   直接透出页面正在画的模糊封面，只叠一层按**控件所在位置局部背景亮度**反算的 shade
        //   （亮底叠黑 15% / 暗底叠白解 1.15 倍）。第一版用整图平均亮度，渐变封面上局部只差
        //   2~6%，达不到 15%；这里用 onGloballyPositioned 拿到控件中心在窗口里的坐标再取样。
        val windowSize = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
        var centerPx by remember { mutableStateOf(Offset.Zero) }
        return this
            .onGloballyPositioned { coords ->
                val p = coords.positionInWindow()
                centerPx = Offset(p.x + coords.size.width / 2f, p.y + coords.size.height / 2f)
            }
            .clip(shape)
            .drawBehind {
                val local = playerBackdropSrgbLuminanceAt(
                    centerPx.x,
                    centerPx.y,
                    windowSize.width.toFloat(),
                    windowSize.height.toFloat(),
                )
                drawRect(contrastShadeFor(local).fill)
            }
    }
    if (com.netmusiclite.data.AppearancePrefs.isM3e && !forceGlass) return this.clip(shape).background(androidx.wear.compose.material3.MaterialTheme.colorScheme.surfaceContainer)
    // ★ 2026-10-01：走「黑色半透明」分支的条件从「没有背景图」扩展为
    //   「没有背景图 **或** 用户主动选了黑色卡片」—— 设置页「卡片样式」开关切到黑色时，
    //   全站玻璃件（含播放器/歌词/歌房里的）立刻统一变成纯黑 45%，不取样、不做糊。
    // ★ 2026-10-02 用户口径「浅色模式下未选择壁纸也可以设置为玻璃卡片」：
    //   无背景图可取样时，浅色主题走「透底轻玻璃」—— LightPalette 标定的白 72% 填充
    //   （SurfaceGlass）透出页面底色形成层次，模糊强度 < 50% 时加边缘高光；
    //   深色主题无图依旧走下方黑色半透明兜底（深色观感本就成立），开关在设置页隐藏。
    //   注意条件里的 !live：壁纸真的在画时必须走下面的取样分支，不能被这里截走。
    // ★ 2026-10-07 用户 bug「选择自定义图片后，封面模糊主题浅色下玻璃卡片效果不生效，
    //   卡片和取色仍然跟随自定义图片」——三个分支的判据从「**有没有**背景图」改成
    //   「背景图**是否真的在画**」（wallpaperShown()，与全局背景层壁纸位同判据，见 NcmBackground）：
    //   封面模糊顶掉了壁纸（全背景封面模糊）时壁纸虽“有”却没露面，旧版仍走「有图」分支 ⇒
    //   浅色下跳过了轻玻璃分支（开关失效）、还要叠一层按壁纸压暗量算出的 shade。
    //   现在没露面 = 当作没有图：浅色走轻玻璃、深色走纯色兜底，卡片与身后真实的底一致。
    // ★ 2026-10-07「封面模糊明暗对调」：明暗与玻璃填充改读 [playLightTheme] /
    //   [PlaySurfaceGlass]（播放器组作用域内翻到另一套板；作用域外与全局值恒等，零差异）。
    val light = if (forceGlass) false else playLightTheme
    val glassFill = if (forceGlass) Color(0x22FFFFFF) else PlaySurfaceGlass
    val actualFill = if (forceGlass && fill == PlaySurfaceGlass) glassFill else fill
    // ★ 2026-10-16：石墨素底色在**组合期**读（drawBehind 里不能调 @Composable getter）——
    //   跟明暗作用域走（对调时取另一套板），与 BgElevated 同族。
    val elevFill = if (forceGlass) Color(0xFF16161A) else PlayBgElevated
    val live = wallpaperShown()
    if (!live &&
        light &&
        com.netmusiclite.data.BackgroundStore.cardGlass
    ) {
        return this
            .clip(shape)
            .drawBehind {
                // 显式传入填充的玻璃件（强调色圆钮等）维持原填充，不吃轻玻璃。
                // 透明度可调（2026-10-02）：cardTransparency 越大越透，默认 0.28 = 白 72% 观感。
                drawRect(
                    if (actualFill == glassFill)
                        glassFill.copy(alpha = (1f - com.netmusiclite.data.BackgroundStore.cardTransparency).coerceIn(0.15f, 1f))
                    else actualFill
                )
                if (com.netmusiclite.data.BackgroundStore.cardBlur < 0.5f) {
                    drawRect(GLASS_HIGHLIGHT_BRUSH)
                }
            }
    }
    // ★ 2026-10-16：本分支从「!live || !cardGlass」收窄为「!cardGlass」——
    //   深色 + 玻璃开 + 没有壁纸可取样，改走下面的「石墨素底 + 顶部高光」新材质。
    return if (!forceGlass && !com.netmusiclite.data.BackgroundStore.cardGlass) {
        this.drawBehind {
            // 默认玻璃底换 45% 半透明黑；显式传入的填充（强调色圆钮等）保留。
            // ★ 2026-10-01 浅色主题：底色本身就亮，再叠 45% 黑会得到一张脏灰卡，
            //   必须换成高不透明度白（92%）—— 叠在 #EFEFF3 底上约等于纯白，
            //   与底色拉开约 15/255 的差值，卡片边界才立得住（手表上没有阴影可用）。
            //   （drawBehind 里读组合状态：主题切换只触发重绘，不触发重组，零额外开销）
            val fallback = if (light)
                Color.White.copy(alpha = 0.92f) else Color.Black.copy(alpha = 0.45f)
            drawRect(if (actualFill == glassFill) fallback else actualFill)
        }
    } else {
        // ★ 2026-10-07 用户 bug「封面模糊主题下壁纸明明没被用到，卡片颜色却跟随自定义背景」：
        //   走到这里 = 玻璃开 且 有可取样底，取样图、压暗量与身后的底天然一致；
        //   壁纸没露面的场合不会糊上一层「没在用的壁纸」。
        // ★ 2026-10-16 深色材质修复：取样底从「仅自定义壁纸」扩到「封面模糊底」，
        //   且底图读取移到**绘制期**（drawBehind 里读 CoverBackdrop / BackgroundStore 状态：
        //   换歌换底只触发重绘，不把全屏每个玻璃件都重组一遍）。
        var pos by remember { mutableStateOf(IntOffset.Zero) }
        // ★ 2026-10-16 根（= 屏幕）像素尺寸：`positionInRoot()` 的坐标系。
        //   用来把「卡片的屏幕 px 坐标」折算成**底图坐标** —— 两张取样底的边长并不相同：
        //     · 壁纸底图（BackgroundStore.blurredImage）= 466，与屏幕 1:1；
        //     · 封面模糊底（CoverBackdrop）= **96**（它是「全屏拉伸显示」的色场，不是 1:1 图）。
        //   旧实现直接把屏幕 px 当底图坐标用（`pos.x.coerceIn(0, bmp.width - 卡片宽)`），
        //   碰到 96 的封面底必然越界：srcSize（卡片宽，往往 >96）大于底图 ⇒ Skia 按钳位
        //   取样，卡片颜色与自己的屏幕位置完全无关，右/下还会拖出一条拉丝色带。
        //   折算后底图 = 466 时系数恒为 1，与旧实现逐像素一致（壁纸路径零回归）。
        val rootW = with(androidx.compose.ui.platform.LocalDensity.current) { screenWidthUiDp().toPx() }
        val rootH = with(androidx.compose.ui.platform.LocalDensity.current) { screenHeightUiDp().toPx() }
        this
            .clip(shape)
            .onGloballyPositioned { ic ->
                // 2px 量化：底图本身是重模糊图，2px 取样偏移不可见；
                // 滚动吸附尾巴等小位移不再触发玻璃层重绘（滚动掉帧的主要来源）
                val p = ic.positionInRoot()
                val q = IntOffset(p.x.roundToInt() / 2 * 2, p.y.roundToInt() / 2 * 2)
                if (q != pos) pos = q
            }
            .drawBehind {
                // 取样底（2026-10-16）：① 自定义壁纸在画 → 模糊壁纸（「卡片模糊」滑杆 = 取样透明度，原行为）；
                //   ② 深色 + 壁纸没在画 → 封面模糊底（播放器组封面模糊风格 / 全背景封面模糊 /
                //      **深色主题无壁纸**——页面底就是封面取色，封面即那台「默认壁纸」，见 darkCardCoverBlur）。
                //   两张底都已是重糊图，取满强度。
                val bmp = when {
                    live -> com.netmusiclite.data.BackgroundStore.blurredImage
                    !light -> coverBlurSampleImage()
                    else -> null
                }
                if (bmp != null) {
                    val kx = if (rootW > 0f) bmp.width / rootW else 1f
                    val ky = if (rootH > 0f) bmp.height / rootH else 1f
                    val x = (pos.x * kx).toInt().coerceIn(0, (bmp.width - 1).coerceAtLeast(0))
                    val y = (pos.y * ky).toInt().coerceIn(0, (bmp.height - 1).coerceAtLeast(0))
                    val w = (size.width * kx).toInt().coerceIn(1, (bmp.width - x).coerceAtLeast(1))
                    val h = (size.height * ky).toInt().coerceIn(1, (bmp.height - y).coerceAtLeast(1))
                    drawImage(
                        image = bmp,
                        srcOffset = IntOffset(x, y),
                        srcSize = IntSize(w, h),
                        dstOffset = IntOffset.Zero,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        alpha = if (live) com.netmusiclite.data.BackgroundStore.cardBlur else 1f,
                    )
                    // 亮度随背景压暗同步变暗，减少卡片与背景的突兀感
                    // ★ 2026-10-01 浅色主题反向：叠加白色，让背景图向浅色主题淡化，而不是发脏
                    val shade = if (light)
                        Color.White.copy(alpha = com.netmusiclite.data.BackgroundStore.dim * 0.7f)
                    else
                        Color.Black.copy(alpha = com.netmusiclite.data.BackgroundStore.dim * 0.6f)
                    drawRect(shade)
                    drawRect(actualFill)
                } else if (actualFill == glassFill) {
                    // ★ 2026-10-16 用户口径「深色模式卡片材质劣质感」主来源：
                    //   旧版这里画纯黑 45% —— 与底色 #0A0A0C 的差仅 ~4.5/255（浅色卡是 ~15/255），
                    //   且无高光无描边，卡片像贴在底上的一层黑纸。
                    //   改画「石墨抬升底」：与 BgElevated 同族的抬升亮度、与底色差 ~12/255，
                    //   顶部高光由本 drawBehind 末尾叠加、描边由 glassEdgeHighlight 同步给出。
                    // ★ 2026-10-16 二次修正：石墨抬升底只对**近黑纯基底**成立（ambientBaseIsFlat）——
                    //   底色是彩色取色底时不能压 94% 不透明的抬升底，否则取色底被盖死，
                    //   用户口径「播放音乐有背景取色的时候他不会透明透过」。
                    // ★ 2026-10-16 三次修正：彩色取色底这条分支从「纯黑 45%」换成「环境色 +
                    //   抬升」（见下方分支注释，用户口径「不再画纯黑块」）。
                    //   壁纸在画但取样暂缺（live && bmp == null）仍走半透明（底是图片，透底永远对）。
                    if (!live && ambientBaseIsFlat()) {
                        // ★ 2026-10-18 用户口径「深色模式选择取色、开了玻璃卡片，卡片是黑色」：
                        //   94% 的抬升把卡片压成一整块石墨（与底色只差 ~12/255），用户读成"黑色卡片"。
                        //   降到 84% 仍保留「卡片比底色亮一档」的层次，但不再是死黑块；
                        //   边界由 glassEdgeHighlight 的描边 + 顶部高光负责，不靠这层不透明度撑。
                        drawRect(elevFill.copy(alpha = 0.84f))
                    } else if (!live) {
                        // ★ 2026-10-16 真·毛玻璃（用户口径「深色模式玻璃卡片改用现成的高斯
                        //   模糊库，卡片模糊身后」）：壁纸没在画、封面模糊底也没在画，
                        //   但**有环境取色**（正在播放、SongAmbient.color 是封面主色压暗版）
                        //   时，旧版画纯黑 45% —— 纯黑把卡片压进页面底里，还把歌曲色整个丢掉，
                        //   是「深色卡片材质劣质感」的另一半来源。
                        //   现在把**环境色本身**当玻璃底：环境色是单色底，「模糊前后完全一致」，
                        //   所以这里等价于「对环境色做高斯模糊」而不必真跑一遍卷积；
                        //   再叠一档 BgElevated 抬升，卡片与页面底拉开层次、同时保留歌曲色。
                        //   （卡片取样底图那条路 —— 壁纸 / 封面模糊底 —— 已改由 Blurry 真高斯
                        //     生成，见 data/BackgroundStore.regenerateBlurred 与 data/GlassBlur）
                        //   ★ 2026-10-18 抬升从 62% 收到 42%：62% 的 #16161A 只剩 38% 的取色，
                        //     深色取色底（本身已被压暗 75%）再乘 0.38 ⇒ 观感就是一块黑灰卡片
                        //     （用户口径「选择取色以后卡片是黑色」）。42% 保留足够彩度，
                        //     卡片与页面底同源、能看出「玻璃透出取色」。
                        drawRect(SongAmbient.color)
                        drawRect(elevFill.copy(alpha = 0.42f))
                    } else {
                        // 壁纸在画但取样底图还没生成好：底是图片，透底永远对，维持原半透明黑
                        drawRect(
                            if (light) Color.White.copy(alpha = 0.92f)
                            else Color.Black.copy(alpha = 0.45f)
                        )
                    }
                } else {
                    drawRect(actualFill)
                }
                // ★ 2026-10-16：深色恒加顶部高光 —— 旧版只有「卡片模糊 < 50%」才画，
                //   默认滑杆 = 1（最大）时深色玻璃/素底永远无光，也是劣质感来源之一；
                //   浅色维持原口径（轻模糊才加，重模糊干净玻璃）。
                if (!light || com.netmusiclite.data.BackgroundStore.cardBlur < 0.5f) {
                    drawRect(GLASS_HIGHLIGHT_BRUSH)
                }
            }
    }
}

@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDesc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 46.dp,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
    // ★ 2026-10-07 明暗对调作用域：默认材质与墨色读 Play* 变体（作用域外 = 全局值恒等）
    container: Color = PlaySurfaceGlass,
    tint: Color = PlayTextPrimary,
    dim: Boolean = false,
    navMotionKind: NavMotionKind? = null,
) {
    if (com.netmusiclite.data.AppearancePrefs.isM3e) {
        // ★ 2026-10-17 用户口径「m3e 播放页最下方统一为一个有弧度贴合屏幕的胶囊」：
        //   m3e 分支以前**无视 container 参数**一律画 FilledIconButton（实心 primary 圆），
        //   于是弧形底栏里的三个按钮各带一颗橙色大圆底，看上去是"三个圆"而不是"一条胶囊"。
        //   container = Transparent 的调用点（弧形底栏）现在画裸图标，胶囊由底栏自己一条画出来。
        if (container == Color.Transparent) {
            // ⚠ modifier 必须挂到 Pressable 上：弧形底栏/一起听底栏是通过
            //   Modifier.offset{...} 摆位的，上一版漏了这个 modifier ⇒ 三个图标全叠在 (0,0)
            //   堆到屏幕左上角外，观感就是"胶囊还在、按钮没了"。
            Pressable(onClick = onClick, modifier = modifier) {
                Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                    Icon(
                        icon, contentDescription = contentDesc,
                        tint = tint.copy(alpha = if (dim) 0.38f else 1f),
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        } else {
            androidx.wear.compose.material3.FilledIconButton(onClick, modifier = modifier.pressStretch(enabled = !dim).size(size), enabled = !dim) {
                androidx.wear.compose.material3.Icon(icon, contentDesc, Modifier.size(iconSize))
            }
        }
        return
    }

    val coordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
    Pressable(
        onClick = {
            if (navMotionKind == null) onClick()
            else TransitionCoordinator.withSource(navMotionKind, coordinates.value, onClick)
        },
        modifier = modifier.onGloballyPositioned { coordinates.value = it },
    ) {
        // 透明容器 = 纯图标悬浮（不画玻璃底），供弧形底栏等已有整体背景的场景使用
        Box(
            if (container == Color.Transparent) Modifier.size(size)
            else Modifier.size(size).clip(CircleShape).frostedGlass(CircleShape, fill = container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDesc,
                tint = tint.copy(alpha = if (dim) 0.38f else 1f), modifier = Modifier.size(iconSize))
        }
    }
}

/** 圆屏内文本输入对话框（新建歌单 / 房间号） */
@Composable
fun TextInputDialog(
    title: String,
    placeholder: String = "",
    confirmText: String = "确定",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (com.netmusiclite.data.AppearancePrefs.isM3e) {
        com.netmusiclite.ui.m3e.M3eTextInputDialog(title, placeholder, confirmText, onConfirm, onDismiss)
        return
    }

    // TextFieldValue 状态重载：保留光标/选区，避免按删除键时光标跳到上一个字
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    DialogScrim(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(BgElevated)
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(12.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 14.sp, color = TextPrimary, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceGlass)
                    .padding(vertical = 10.dp, horizontal = 12.dp),
                // ★ 2026-10-23 需求③（用户口径「所有文字输入框里的占位文字全部删除，只留空白框」）：
                //   全站 TextInputDialog（新建歌单 / 加入一起听 / 写评论 / 房间号…）统一不再画
                //   框内 placeholder —— 这里是唯一渲染点，一处改完全站生效。
                //   [placeholder] 参数保留仅为兼容调用点签名（新代码不必再传）。
                //   框高由 background + 纵向 10dp 内边距 + 一行行高撑住，删占位不会塌陷。
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { inner() }
                },
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircleIconButton(NcmIcons.Close, "取消", onDismiss, size = 40.dp, iconSize = 17.dp)
                CircleIconButton(
                    NcmIcons.Check, confirmText,
                    { if (text.text.isNotBlank()) onConfirm(text.text.trim()) }, size = 40.dp, iconSize = 17.dp,
                    container = Accent,
                )
            }
        }
    }
}

/**
 * 全屏半透明遮罩容器（浅色主题下遮罩减淡到 32%，600/1000 会糊成一片死黑）
 *
 * @param m3eShowClose m3e 主题下是否在对话框最下方渲染「关闭」按钮。
 *   ★ 2026-10-24 用户需求：m3e「结束一起听」确认框最下方的关闭按钮要删掉
 *   （该框本身已有「取消/确定」两颗圆钮 + 点遮罩关闭，多一个关闭键是重复且压屏）。
 *   默认 true 保持其它 m3e 对话框行为不变。
 */
@Composable
fun DialogScrim(onDismiss: () -> Unit, m3eShowClose: Boolean = true, content: @Composable () -> Unit) {
    if (com.netmusiclite.data.AppearancePrefs.isM3e) {
        com.netmusiclite.ui.m3e.M3eDialog(onDismiss, showClose = m3eShowClose, content = content)
        return
    }
    // 系统 back（含华为式被系统吃掉的右滑）：先关本弹窗，不动导航（2026-10-07 同批修复）
    OverlayBackHandler { onDismiss() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(
                alpha = if (com.netmusiclite.ui.theme.isLightTheme) 0.32f else 0.6f
            ))
            .clickable(onClick = onDismiss)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        // ── 可滚动内容框（2026-10-01 圆屏弹窗可见性修复）─────────────────────
        // 旧版这里是 `Box { content() }`，内容高度上限 = 屏高 − 24dp×2（本机 185dp）。
        // Column 在高度不够时会**逐个把剩余高度分给孩子**：前面的兄弟吃满后，
        // 最后一个按钮拿到 0dp —— 于是「长按歌曲」那个面板底部按钮的文字整段消失
        // （用户反馈「看不到按钮上的字，被挡住了」）。
        //
        // 加 verticalScroll 后，内容以**无界高度**测量：不再被压扁，超长就纵向滚动，
        // 保证每一条都真实存在、都能滚到。高度上限仍由外层 padding 控制（不额外收紧，
        // 否则原本刚好放得下的弹窗反而要滚动）。
        Box(
            Modifier
                .clickable(enabled = false) {}
                .verticalScroll(rememberScrollState()),
        ) {
            content()
        }
    }
}

/**
 * 当前设备是否为**圆形屏**（Wear OS 圆表）。
 *
 * ★ 2026-10-02 方形设备适配（用户口径「检测到安装设备是方形的时候，填充空缺区域」）：
 *   本 App 的整套布局是按「466×466 方屏面板 + 圆形表圈」设计的 —— 窗口铺满方屏、
 *   四角在物理上被表圈切掉，所以根布局一直 `clip(CircleShape)`。
 *   装到**方形/矩形屏**设备上时这个裁切就变成了 bug：四角被裁掉后没有内容可画，
 *   而 MainActivity 里 `window.setBackgroundDrawable(null)`（首帧优化）让窗口也没有底色，
 *   于是屏幕四角留出四块纯黑空白 —— 也就是用户看到的「空缺区域」。
 *
 * 判据用系统配置里的 SCREENROUND 位（[android.content.res.Configuration.isScreenRound]，
 * API 23+，本项目 minSdk 26），比任何「按宽高比/分辨率猜形状」都可靠：
 *   · 圆表                → true  → 保留圆形裁切 + 弧边内缩（原有行为，一个像素都不变）
 *   · 方表 / 手机 / 模拟器 → false → 不裁切、不内缩，内容直接铺满整屏
 *
 * ⚠ 用 [androidx.compose.ui.platform.LocalConfiguration] 而不是在启动时读一次存全局：
 *   跟随配置变化，且在 Compose 里读到的就是本 Activity 的真实配置。
 *
 * ★ 2026-10-03 用户向导「方表 / 圆表」选择接管（[com.netmusiclite.data.ScreenShapePrefs]）：
 *   系统 SCREENROUND 位在国产方表 ROM / 鸿蒙手表 / 魔改 Wear OS 上并不可靠（方表被报成圆屏
 *   → 四角裁空；圆表被报成方屏 → 通宽控件两端被弧边切）。首次启动的向导第一页让用户
 *   **目视指定**一次，此后一律以用户选择为准；没选过（AUTO）才回落到系统位 ——
 *   存量用户升级后行为与旧版完全一致，一个像素不变。
 */
@Composable
fun isRoundScreen(): Boolean {
    com.netmusiclite.data.ScreenShapePrefs.forcedRound?.let { return it }
    return androidx.compose.ui.platform.LocalConfiguration.current.isScreenRound
}

/**
 * UI 坐标系下的屏幕宽度（dp）。
 *
 * ★ 2026-10-03 屏幕尺寸自适应：NcmTheme 会把 LocalDensity.density 乘上
 *   [LocalUiScale]（= 屏幕短边 dp / [com.netmusiclite.ui.theme.DESIGN_SCREEN_DP]），
 *   让 233dp 的设计基准在任何屏上都恰好铺满短边。**副作用**是
 *   `LocalConfiguration.screenWidthDp.dp` 这个写法彻底失效 —— screenWidthDp 是
 *   **系统 dp**，再被缩放后的 density 换算一次，得到的是「屏宽 × 缩放系数」而不是屏宽。
 *
 *   凡是要表达「屏幕有多宽」的地方（滑出距离、画廊列数、通宽控件的基准宽度…）
 *   都必须改用本函数：它把系统 dp 除回缩放系数，得到**UI 坐标系下的真实屏宽**
 *   —— 基准屏上恒为 233dp，小屏上同样是 233dp（因为整个坐标系已被等比放大到铺满）。
 */
@Composable
fun screenWidthUiDp(): Dp {
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    return (cfg.screenWidthDp / LocalUiScale.current).dp
}

/** UI 坐标系下的屏幕高度（dp）。语义同 [screenWidthUiDp]（手表屏多为正方形，两者相等）。 */
@Composable
fun screenHeightUiDp(): Dp {
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    return (cfg.screenHeightDp / LocalUiScale.current).dp
}

/**
 * 圆屏「水平安全内缩」（px）—— 控件纵向中心距屏幕顶部 [centerYFromTopPx] 像素时，
 * 返回让它左右两端完整落在圆形可视区内的最小内缩量（已含 [marginPx] 余量）。
 *
 * 为什么需要：手表是 466×466 方屏面板 + 圆形表圈，窗口铺满整个方屏，**四角在物理上被切掉**。
 * 通宽控件放在屏幕最上/最下时，两端会被弧边吃掉（真机表现就是「输入框两头被裁切」）。
 *
 * 推导：圆心在方屏正中、半径 R = 屏宽/2。距圆心 dy 处的可用半宽 = √(R² − dy²)，
 * 故所需内缩 = R − √(R² − dy²)。越靠上/下，圆越窄，内缩越大；屏幕正中为 0。
 *
 * 用法：拿控件自己的纵向中心（`positionInRoot().y + size.height/2`）算一次即可；
 * 内缩只影响横向宽度、不影响纵向位置，因此不会产生测量回环。
 *
 * ⚠ 屏宽必须取 [screenWidthUiDp]（UI 坐标系），不能取 `LocalConfiguration.screenWidthDp`
 *   —— 后者在密度被等比缩放后不再等于屏宽（见 [screenWidthUiDp] 的推导）。
 */
@Composable
fun circleSafeInsetPx(centerYFromTopPx: Float, marginPx: Float = 6f): Float {
    // ★ 2026-10-02 方形设备适配：方屏没有弧边可避，内缩恒为 0 ——
    //   否则通宽控件（房间号输入框等）会无谓地缩窄，白白浪费本来就有限的宽度。
    if (!isRoundScreen()) return 0f
    val screenWpx = with(androidx.compose.ui.platform.LocalDensity.current) {
        screenWidthUiDp().toPx()
    }
    val r = screenWpx / 2f
    if (r <= 0f) return 0f
    val dy = kotlin.math.abs(centerYFromTopPx - r).coerceAtMost(r)
    val half = kotlin.math.sqrt((r * r - dy * dy).coerceAtLeast(0f))
    return (r - half) + marginPx
}

/**
 * 右滑返回淡出引擎（SwipeBackBox / 播放页 / 歌词页 / 歌房页共用）：
 * - 跟手「右移 + 非线性淡出」：页面随手指向右移动（35% 位移）并以 smoothstep 非线性曲线淡出；
 * - 淡出速度随滑动速度变化：瞬时速度折算成进度加成，快滑时淡出领先手指，慢滑跟手缓淡；
 * - 松手未达阈值 150ms 淡回原位；达阈值由调用方触发返回 —— 页面保持当前位移/透明度
 *   继续向右滑出场，下层页面立即接上（配合 NavHost popEnter 60ms 接场，无空白帧）；
 * - 2026-09-13：滑动返回全程不再触发震动。
 * fade/slide 用 snapshot float 直写：拖动路径零协程/零 mutex（旧版每个拖动事件
 * scope.launch 两次 + Animatable.snapTo 的 mutex 抢占，W5 高频触摸下有可感抖动）。
 */
/**
 * 右滑跟手缩放的档位（2026-10-03 重排，修「滑动过长滑到底顿一下」）：
 * - 阈值行程（37dp）内：1.00 → [QZ_DRAG_SCALE]（0.80），与旧版一致；
 * - 阈值之外的**超程**继续跟手：每再拖 [QZ_DRAG_OVER_UNITS] 倍阈值，
 *   多缩 ([QZ_DRAG_SCALE] − [QZ_DRAG_MAX])，封顶 [QZ_DRAG_MAX]（0.62）。
 *
 * 为什么旧版在 0.80 处硬停：当年跟手缩太狠（0.55）+ 松手固定 190ms，末段「几乎不动
 * 却跑满时长」→ 犹豫。硬停的代价是**超程死区** —— 阈值只有 37dp，长滑后 3/4 行程
 * 零反馈，用户观感就是「滑到底了、顿住」。2026-10-03 两条一起修：跟手继续收（长滑
 * 全程有反馈），松手时长按**剩余行程**折算（见 [flingOutDuration]），不再有「行程小
 * 却跑满时长」的犹豫 —— 两段速度连续，长滑变成一条完整动作。
 */
private const val QZ_DRAG_SCALE = 0.80f

/** 超程跟手封顶（再滑也不再缩）：保留 ≥0.12 的松手行程，收场不至于「没东西可演」。 */
private const val QZ_DRAG_MAX = 0.62f

/** 超程跟手的量程：阈值之外再拖 2 倍阈值（74dp）走完 0.80 → 0.62 这段。 */
private const val QZ_DRAG_OVER_UNITS = 2f

/** 右滑松手返回时缩到的终值，与页面转场常量 QZ_FROM 取同一个数（退页收向 50%）。 */
private const val QZ_EXIT_SCALE = 0.50f

/*
 * 松手缩放的缓动（2026-10-02）：直接复用转场曲线 [QzZoom]（同形镜像，收尾回弹）。
 * 旧方案 QZ_EXIT_EASE（严格单调、起手斜率 ≈5.9）只追求「不犹豫」，但没有与入场对称的
 * 回弹 —— 用户反馈「退出界面动画没有回弹」。现在与 NavHost popExit 用同一条曲线：
 * 先越过 0.50 缩到 ~0.46（过冲）再回稳到 0.50，快起步 + 收尾回弹。
 */

class SwipeBackFader(
    private val thresholdPx: Float,
    private val scope: kotlinx.coroutines.CoroutineScope,
    /**
     * 跟手与收场用哪种视觉。
     *
     * · `true`（默认）= **缩放**：页面级右滑返回（用户要求「退出也要是缩放」）。
     * · `false` = **位移**：播放器组内部的横向切换，例如歌词页 → 播放页
     *   （用户明确要求「歌词界面切换到播放界面保留滑动切换动画，不要用缩放」）。
     *
     * 2026-10-01 补：此前把整条 SwipeBackFader 无差别改成了缩放，连带把播放器组内部的
     * 横向切换也一起改掉 —— 那是用户明确要保留的滑动，所以这里必须按用途分开。
     */
    private val scaleMode: Boolean = true,
) {
    private val fadeState = androidx.compose.runtime.mutableFloatStateOf(1f) // 1=不透明
    private val slideState = androidx.compose.runtime.mutableFloatStateOf(0f) // 跟手位移 px（已停用，恒 0）
    private val scaleState = androidx.compose.runtime.mutableFloatStateOf(1f) // 缩放（右滑返回主视觉）
    /**
     * 右滑缩放的圆心（= 本页入场时记录的点击位置），2026-10-02 用户实测「退出不是原路返回」
     * ——此前 graphicsLayer 用默认中心原点，页面缩向屏幕正中，与入场的触点缩放不对称。
     * 在手势开始时从当前 entry 的 saved spec 取一次（入场时已写入）；滑动手势打开的
     * 页面没有点击原点，保持屏幕正中。使用点：`transformOrigin = <fader>.origin`。
     */
    private val originState = androidx.compose.runtime.mutableStateOf(
        androidx.compose.ui.graphics.TransformOrigin.Center,
    )
    /** 供 graphicsLayer 的 transformOrigin 读取（延迟读：拖动期零重组） */
    val origin: androidx.compose.ui.graphics.TransformOrigin get() = originState.value
    private val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
    private var settleJob: kotlinx.coroutines.Job? = null
    private var dragX = 0f

    /** 内容不透明度（供 graphicsLayer 读） */
    val alpha: Float get() = fadeState.floatValue

    /** 内容跟手位移 px（供 graphicsLayer 读；右滑返回改缩放后恒为 0，保留属性兼容调用点） */
    val slidePx: Float get() = slideState.floatValue

    /**
     * 内容缩放（供 graphicsLayer 读）。
     *
     * ★ 2026-10-01：右滑返回改为**缩放主导**。
     *   此前是「跟手右移 35% + 松手滑出屏幕」，用户两次反馈「右滑（退出）不是缩放」
     *   （第一次原话「你只改了进入动画没改退出动画」）—— 右移把缩放完全盖住了，
     *   就算 NavHost 侧加了 scale 也看不出来。
     *   轻舟的返回实测是**纯缩放**：退页恒 1.000 → 回弹 1.038 → 收向 0.50 并淡出，
     *   被揭示的下层页不缩放、只在后半程淡入。这里照此把跟手位移归零，改用跟手缩小。
     */
    val scale: Float get() = if (com.netmusiclite.data.AppearancePrefs.isM3e) 1f else scaleState.floatValue

    /** 右滑累计是否已达返回阈值 */
    val passedThreshold: Boolean get() = dragX >= thresholdPx

    fun onDragStart() {
        settleJob?.cancel(); settleJob = null
        tracker.resetTracking()
        // 每次手势从零计程（2026-10-02 修）：此前 dragX 只在反向拖动时清零，
        // 未达阈值的两次小右滑会累加 —— 主页「右滑退出应用」会被两次短滑误触发。
        dragX = 0f
        // 原路返回（2026-10-02）：缩放圆心 = 本页入场时的点击位置（入场时写入 entry 的 spec）
        originState.value = TransitionCoordinator.currentSpec()?.let {
            androidx.compose.ui.graphics.TransformOrigin(it.originX, it.originY)
        } ?: androidx.compose.ui.graphics.TransformOrigin.Center
    }

    /** 每个横向拖动事件调用；左移按净值逐点回退（不是整段清零，长滑中手指微抖不再闪回） */
    fun onDrag(change: androidx.compose.ui.input.pointer.PointerInputChange, delta: Float) {
        settleJob?.cancel()
        tracker.addPosition(change.uptimeMillis, change.position)
        // ★ 2026-10-03 修「滑动过长滑到底顿一下」之二：旧版 `delta<0 → dragX=0` 太脆 ——
        //   长滑里手指一点左向分量（或抬手前的自然回滚）就把整段进度清零 + 130ms 弹回，
        //   观感是页面「莫名涨回去顿一下」，甚至抬手时把已达标的手势取消掉。
        //   改为净值累计：左移逐点收回，拖回阈值内照常不触发返回（语义不变、手感不脆）。
        dragX = (dragX + delta).coerceAtLeast(0f)
        val v = runCatching { tracker.calculateVelocity().x }.getOrDefault(0f)
        val units = dragX / thresholdPx // 以阈值为单位的行程（可 >1，超程部分继续跟手）
        val p = units.coerceIn(0f, 1f) // 提交进度（0..1）
        val t = (p * 0.72f + (v / 2400f).coerceIn(0f, 1f) * 0.28f).coerceIn(0f, 1f)
        // 爆音空白帧缓解：松手返回时新页首帧组合在 W5 上耗时几十 ms（巨帧期间屏幕定格在拖拽末帧），
        // 淡出越接近 0 定格越像"闪白"。给透明度设 0.45 下限，定格帧仍是可辨识的页面内容。
        val a = (1f - t * t * (3f - 2f * t)).coerceAtLeast(0.45f) // smoothstep 非线性：前缓后急
        fadeState.floatValue = a
        // 缩放模式：跟手缩小（右滑返回的主视觉）——阈值段 + 超程段，长滑不再有死区。
        // 位移模式：跟手右移 35%（播放器组内横向切换，用户要求保留滑动）。
        if (scaleMode) {
            val over = ((units - 1f) / QZ_DRAG_OVER_UNITS).coerceIn(0f, 1f)
            scaleState.floatValue =
                1f - (1f - QZ_DRAG_SCALE) * p - (QZ_DRAG_SCALE - QZ_DRAG_MAX) * over
            slideState.floatValue = 0f
        } else {
            scaleState.floatValue = 1f
            slideState.floatValue = dragX * 0.35f
        }
    }

    /** 松手：triggered=true 表示即将返回（页面已入场接场，无需回弹） */
    fun onDragEnd(triggered: Boolean) {
        if (!triggered) settleBack(150)
    }

    fun onDragCancel() = settleBack(150)

    /**
     * 松手返回的收束时长。
     *
     * 2026-10-01：右滑返回由「滑出屏幕」改为「缩放收束」，时长不再取决于屏幕距离。
     * ★ 2026-10-03：改为按**剩余行程**折算（190ms 走完 0.30 的基准速率，夹取 120–190ms）。
     *   配合超程跟手（[QZ_DRAG_MAX]）：跟得越深、剩得越少、收得越快 —— 跟手段与收场段
     *   速度连续，长滑是一条完整动作，不再出现「滑到底（跟手封顶）顿一下再走」。
     *   旧版固定 190ms 正是「行程小却跑满时长」犹豫感的根源。
     * screenPx 保留在签名里兼容调用点。
     */
    fun flingOutDuration(screenPx: Float): Int =
        if (scaleMode) {
            val travel = (scaleState.floatValue - QZ_EXIT_SCALE).coerceAtLeast(0f)
            (travel / 0.30f * 190f).toInt().coerceIn(120, 190)
        }
        else {
            // 位移模式仍按剩余距离折算（平均速率 ~2200px/s，夹取 140–220ms）
            val dist = (screenPx - slideState.floatValue).coerceAtLeast(0f)
            (dist / 2200f * 1000f).toInt().coerceIn(140, 220)
        }

    /**
     * 松手返回：把页面从当前跟手缩放收束到 [QZ_EXIT_SCALE]（0.50），淡出交给 NavHost。
     *
     * 2026-10-01 由「单条 Hermite 滑出屏幕」改为缩放收束 —— 位移整段移除，只留缩放。
     * 缓动用 [QzZoom]（2026-10-02 起，与转场 popExit 同一条曲线）：快速收束的过程中
     * 越过终点 0.50 缩到 ~0.46 再回稳 —— 收尾带回弹，与入场观感对称，也不失果断。
     *
     * [fadeToZero]（2026-10-02 主页右滑退到后台）：没有下层页接场，淡出必须自己收到底
     * —— 曲线与 NavHost 手势返回分支的 `fadeOut(tween(ms))` 一致（tween 默认缓动）。
     * 普通返回保持 false：淡出由 NavHost popExit 负责，两层淡出叠加会提前变透明。
     */
    fun flingOut(screenPx: Float, durationMs: Int, fadeToZero: Boolean = false) {
        settleJob?.cancel()
        settleJob = scope.launch {
            if (fadeToZero) {
                launch {
                    androidx.compose.animation.core.animate(
                        initialValue = fadeState.floatValue, targetValue = 0f,
                        animationSpec = tween(durationMs),
                    ) { v, _ -> fadeState.floatValue = v }
                }
            }
            if (scaleMode) {
                // 缩放模式：收束到 0.50，不再滑出屏幕（页面级右滑返回）。
                // QzZoom 的值域可达 1.077：animate 插值 initial + (0.5-initial)·E(u)
                // 会先把 scale 推低到过冲位（过冲量随剩余行程等比）再回稳到 0.50。
                androidx.compose.animation.core.animate(
                    initialValue = scaleState.floatValue, targetValue = QZ_EXIT_SCALE,
                    animationSpec = tween(durationMs, easing = QzZoom),
                ) { v, _ -> scaleState.floatValue = v }
            } else {
                // 位移模式：单条 Hermite（k=1.8）单调减速滑到 screenPx ——
                // f'(0)=1.8×（先快）、f'(1)=0（后慢）、f''≤0 全程严格单调减速，中段无速度波动。
                val ease = androidx.compose.animation.core.Easing { x ->
                    val k = 1.8f
                    (k - 2f) * x * x * x + (3f - 2f * k) * x * x + k * x
                }
                androidx.compose.animation.core.animate(
                    initialValue = slideState.floatValue, targetValue = screenPx,
                    animationSpec = tween(durationMs, easing = ease),
                ) { v, _ -> slideState.floatValue = v }
            }
        }
    }

    /**
     * 立即复位到常态（主页右滑退到后台后、应用回到前台时调用）：页面不会停在
     * 收小/半透明帧上，右滑计程也从零重新开始。无动画 —— 只应在页面不可见时调用。
     */
    fun snapNormal() {
        settleJob?.cancel(); settleJob = null
        fadeState.floatValue = 1f
        slideState.floatValue = 0f
        scaleState.floatValue = 1f
        dragX = 0f
    }

    private fun settleBack(ms: Int) {
        settleJob = scope.launch {
            launch {
                androidx.compose.animation.core.animate(
                    initialValue = fadeState.floatValue, targetValue = 1f,
                    animationSpec = tween(ms),
                ) { v, _ -> fadeState.floatValue = v }
            }
            launch {
                androidx.compose.animation.core.animate(
                    initialValue = slideState.floatValue, targetValue = 0f,
                    animationSpec = tween(ms),
                ) { v, _ -> slideState.floatValue = v }
            }
            androidx.compose.animation.core.animate(
                initialValue = scaleState.floatValue, targetValue = 1f,
                animationSpec = tween(ms),
            ) { v, _ -> scaleState.floatValue = v }
        }
    }
}

/**
 * 全局滑动返回：屏幕任意位置向右滑动（全屏右滑，非边缘限定），内容跟手「右移+非线性淡出」
 * （淡出速度随滑动速度变化，快滑淡得更快），超过阈值松手即返回。
 * 右滑期间本页置于转场最上层（lifted）：松手返回时下层页面立即接上，无空白帧。
 * 竖向列表滚动/子组件横滑手势优先级更高，互不干扰。
 * 圆表上左上角按钮难点按，统一用这个替代返回钮。
 *
 * 2026-09-13 重写：原「跟手位移」改为跟手右移+非线性速度感应淡出（SwipeBackFader），
 * 并取消滑动返回/左划切换时的震动。
 * 2026-09-04 扩展：新增 onSwipeLeft（左划回调，主页/搜索左划进播放页）——
 * ⚠ 子级不要再挂横向拖拽检测，会先消费事件把本组件的右滑返回饿死（真机踩坑）。
 * 方向互斥：左划累计与右划累计各自独立，换向即清零。
 * 2026-10-02 扩展：新增 exitAppOnBack（仅主页）—— 右滑退到后台（见参数注释）。
 */
@Composable
fun SwipeBackBox(
    onBack: (() -> Unit)? = null,
    onSwipeLeft: (() -> Unit)? = null,
    /**
     * 右滑「退出应用」模式（2026-10-02 主页专用）：达阈值松手后不走 NavHost 返回转场
     * —— 下面没有可接场的页面，所以由 fader 自己缩到 0.50 并淡到全透明，动画播完**才**
     * 回调 [onBack]（执行 moveTaskToBack；半路执行会让窗口在收小动画中途被撤掉，像闪退）。
     * 应用回到前台（ON_START）自动 [SwipeBackFader.snapNormal]，页面不会停在收小/透明帧。
     */
    exitAppOnBack: Boolean = false,
    nativeNavigation: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (com.netmusiclite.data.AppearancePrefs.isM3e) {
        // Route-level dismissal is owned by SwipeDismissableNavHost so its background is
        // the actual previous entry. Standalone overlays still own their local dismissal.
        if (nativeNavigation) {
            Box(Modifier.fillMaxSize().m3eSwipeLeft(onSwipeLeft)) { content() }
        } else {
            androidx.wear.compose.material3.SwipeToDismissBox(onDismissed = { onBack?.invoke() }) { background ->
                if (!background) Box(Modifier.fillMaxSize().m3eSwipeLeft(onSwipeLeft)) { content() }
            }
        }
        return
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 返回行程 37dp（2026-09-13 二次下调 40%：76dp → 61dp → 37dp）
    val popPx = with(androidx.compose.ui.platform.LocalDensity.current) { 37.dp.toPx() }
    val fader = remember(popPx) { SwipeBackFader(popPx, scope) }
    // 滑出目标 = 屏宽（flingOut 清屏用）
    // ⚠ 用 screenWidthUiDp（UI 坐标系屏宽），不能用 LocalConfiguration.screenWidthDp
    //   —— 后者在密度被等比缩放后不再等于屏宽，滑出距离会算错（见 screenWidthUiDp 注释）。
    val screenPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        screenWidthUiDp().toPx()
    }
    // 回调经 rememberUpdatedState 读取：pointerInput(Unit) 不随 lambda 变化重启，拖动中重组不打断手势
    val currentBack by androidx.compose.runtime.rememberUpdatedState(onBack)
    val currentSwipeLeft by androidx.compose.runtime.rememberUpdatedState(onSwipeLeft)
    val currentExitApp by androidx.compose.runtime.rememberUpdatedState(exitAppOnBack)
    // 右滑期间把本页抬到转场最上层：松手返回时退场页盖在入场页上方继续滑出（下层接场无空白帧）
    var lifted by remember { mutableStateOf(false) }
    // 退到后台再回来时复位：退出动画把页面收成了 0.5/透明，不复位主页就一直"不见了"
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, fader) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) fader.snapNormal()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 2026-10-03（华为 Watch 3）系统返回接管：华为把右滑做成系统级返回 —— 触摸被系统
    // 吃掉（本组件收不到跟手事件），松手直接给 Activity 派发 back、默认 finish 应用。
    // 这里把「退出应用」整段动作抽成 exitAppNow 并注册给 SystemBackRouter：系统 back
    // 落到主页时播与手势退出完全相同的收小+淡出，播完 moveTaskToBack。
    // exiting 去重：退出动画/兜底窗口内再来 back 不叠发（连续右滑不双杀）。
    val exiting = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val exitAppNow: () -> Unit = {
        if (currentExitApp && currentBack != null && exiting.compareAndSet(false, true)) {
            // 与手势松手退出共用同一收场：fadeToZero —— 无下层页接场，淡出自己收到底
            val ms = fader.flingOutDuration(screenPx)
            fader.flingOut(screenPx, ms, fadeToZero = true)
            // 退出无下层页接场，不必再抬在转场最上层
            // ⚠ 普通返回**不能**在这里复位 —— lifted 要一直保持到收场结束，
            //   否则退场页会被后入场的页面盖住（原实现刻意不复位）。
            lifted = false
            scope.launch {
                delay(ms.toLong())
                currentBack?.invoke()
                // 兜底：动作没生效（窗口仍在前台时）页面会停在透明态，
                // 等半秒确认还可见就复位，避免用户面对一张"空屏"。
                delay(500)
                if (lifecycleOwner.lifecycle.currentState
                        .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                ) {
                    fader.snapNormal()
                }
                exiting.set(false)
            }
        }
    }
    if (exitAppOnBack) {
        val currentExit by androidx.compose.runtime.rememberUpdatedState(exitAppNow)
        DisposableEffect(Unit) {
            com.netmusiclite.ui.nav.SystemBackRouter.homeExit = { currentExit() }
            onDispose { com.netmusiclite.ui.nav.SystemBackRouter.homeExit = null }
        }
    }

    Box(
        Modifier
            .zIndex(if (lifted) 2f else 0f)
            .fillMaxSize()
            .pointerInput(Unit) {
                var dragL = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragL = 0f
                        fader.onDragStart()
                        lifted = currentBack != null
                    },
                    onDragEnd = {
                        val back = currentBack != null && fader.passedThreshold
                        val left = !back && currentSwipeLeft != null && dragL >= popPx
                        fader.onDragEnd(back)
                        if (back) {
                            if (currentExitApp) {
                                // 退出应用（退到后台）：与系统 back 触发的退出统一走 exitAppNow
                                //（收小+淡到底、播完 moveTaskToBack、失败兜底复位全在其中）
                                exitAppNow()
                            } else {
                                // 松手速度接续：fader 从当前位移线性滑出全屏（时长按拖拽末速度折算），
                                // NavHost 转场只做淡出+下层入场，不再叠加位移（消除两段动画接缝）
                                val ms = fader.flingOutDuration(screenPx)
                                TransitionCoordinator.withGesturePop(ms) { currentBack?.invoke() }
                                fader.flingOut(screenPx, ms)
                            }
                        }
                        else {
                            if (left) currentSwipeLeft?.invoke()
                            lifted = false
                        }
                    },
                    onDragCancel = {
                        fader.onDragCancel()
                        lifted = false
                    },
                ) { change, amt ->
                    change.consume()
                    if (amt < 0) dragL += -amt else dragL = 0f
                    // onBack 为空（如登录页）右滑无动作，淡出/位移不介入
                    if (currentBack != null) fader.onDrag(change, amt)
                }
            },
    ) {
        Box(
            Modifier.graphicsLayer {
                alpha = fader.alpha
                translationX = fader.slidePx
                // ★ 2026-10-01：右滑返回的主视觉 —— 跟手缩放（替代原来的跟手右移）
                scaleX = fader.scale
                scaleY = fader.scale
                // ★ 2026-10-02：缩放圆心 = 本页入场时的点击位置（原路返回），不再缩向屏幕正中
                transformOrigin = fader.origin
            },
        ) { content() }
    }
}

/** 居中提示（加载/空/错误） */
@Composable
fun CenterHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 12.sp, color = TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 48.dp))
    }
}

/** 简易 toast 提示条（圆屏友好，2.2s 自动消失由调用方控制显示状态）
 *  浅色主题下反转为「浅底深字」，否则会在浅色页面里留一块突兀的深色方块 */
@Composable
fun NcmToast(message: String?, modifier: Modifier = Modifier) {
    if (message != null) {
        val light = playLightTheme
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                message,
                fontSize = 11.sp, color = if (light) Color(0xFF1A1A1E) else Color.White,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (light) Color(0xF2FFFFFF) else Color(0xCC2B2B33))
                    .padding(horizontal = 16.dp, vertical = 7.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun rememberAutoClearMessage(): Pair<String?, (String) -> Unit> {
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(msg) {
        if (msg != null) { delay(2200); msg = null }
    }
    return msg to { msg = it }
}

// ---------- 时间/数字格式 ----------
fun formatMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

fun formatDate(ts: Long): String {
    if (ts <= 0) return ""
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    return "%d-%02d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
}

fun formatCount(n: Long): String = when {
    n >= 100_000_000 -> "%.1f亿".format(n / 100_000_000f)
    n >= 10_000 -> "%.1f万".format(n / 10_000f)
    else -> n.toString()
}

// ================= 列表滚动位置记忆 / 恢复（★ 2026-10-23 修复项 10） =================

/**
 * 列表滚动位置记忆 + 延迟恢复：解决「从歌单/播放列表/歌手页点歌进播放页，返回后列表直接回到顶部」。
 *
 * 为什么原生 [rememberLazyListState] 不够（它不是没有 saveable —— 它内部就是 rememberSaveable）：
 *   项目里的列表页数据几乎都是**异步重新加载**的（页面的 `songs/albums` 用 remember + LaunchedEffect
 *   拉 PageCache/网络）。导航返回时页面被重建：rememberSaveable 把 firstVisibleItemIndex=30 恢复了，
 *   但**第一帧列表里往往只有头图/标题一个 item**，LazyColumn 布局时按当帧条目数把它 clamp 到 0，
 *   数据到位后也不会再滚回去 —— 用户看到的就是「返回 = 回到列表顶部」。
 *   （m3e 主题另有一层问题：真实滚动在 TransformingLazyColumnState 上，经典 LazyListState 恒为 0，
 *    见 M3eShared.kt 的 M3eListPositionKeeper。）
 *
 * 做法（只做两件事，且都克制）：
 *  ① 记忆：只在**滚动停止**后、且只记用户**自己滚出来**的位置（userScrolled 之后）。
 *     这样「数据没回来时被 clamp 成 0」不会污染记忆；用户没滚过的页面也不会多记一个 0。
 *     写入 rememberSaveable ⇒ 页面重建（含进程内导航返回）后能取回。
 *  ② 恢复：重建后若「记忆位置 > 0 且当前不在该位置」，等列表条目数长到能容纳它（数据到位）再
 *     scrollToItem 一次。用户一旦手动滚动即放弃（绝不与用户抢滚动）；2s 内数据没回来也放弃，
 *     避免加载失败时把列表钉在旧位置。**不会**在常规重组里反复执行。
 *
 * 接入：所有走 [com.netmusiclite.ui.m3e.AdaptiveLazyColumn] 的页面已自动接入（非 m3e 分支调用本函数），
 * 包括 StackedCardList / NcmListPage 的经典路径，因此歌单/播放列表/歌手页无需逐页改。
 */
@Composable
fun ListViewportRestorer(state: LazyListState) {
    val savedIndex = rememberSaveable { mutableIntStateOf(0) }
    val savedOffset = rememberSaveable { mutableIntStateOf(0) }
    var userScrolled by remember { mutableStateOf(false) }

    // ① 记忆：滚动停止后记一次（滚动中每帧写会引发不必要的状态抖动）；
    //    离开组合时再补记最后一次 —— 覆盖「刚滚动就点歌离开」这种停止事件还没被处理完的时序窗口。
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                userScrolled = true
            } else if (userScrolled) {
                val index = state.firstVisibleItemIndex
                // 数据充足才记：数据未到位时的「0」是 clamp 出来的假位置，不能覆盖记忆
                if (state.layoutInfo.totalItemsCount > index) {
                    savedIndex.intValue = index
                    savedOffset.intValue = state.firstVisibleItemScrollOffset
                }
            }
        }
    }
    DisposableEffect(state) {
        onDispose {
            val index = state.firstVisibleItemIndex
            if (state.layoutInfo.totalItemsCount > index) {
                savedIndex.intValue = index
                savedOffset.intValue = state.firstVisibleItemScrollOffset
            }
        }
    }

    // ② 恢复：只尝试一次
    LaunchedEffect(state) {
        val target = savedIndex.intValue
        val targetOffset = savedOffset.intValue
        if (target <= 0 && targetOffset <= 0) return@LaunchedEffect
        // 同一次组合里已经正确恢复（数据同步可用时常见）→ 无需再滚
        if (state.firstVisibleItemIndex == target && state.firstVisibleItemScrollOffset == targetOffset) {
            return@LaunchedEffect
        }
        val restored = withTimeoutOrNull(2000L) {
            // 等「数据够长」或「用户已接管」；后者出现就放弃恢复
            snapshotFlow { state.layoutInfo.totalItemsCount to state.isScrollInProgress }
                .first { (total, scrolling) -> scrolling || total > target }
            !state.isScrollInProgress
        } ?: false
        if (restored) state.scrollToItem(target, targetOffset)
    }
}

