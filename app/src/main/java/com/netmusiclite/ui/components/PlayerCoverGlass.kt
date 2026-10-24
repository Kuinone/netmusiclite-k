package com.netmusiclite.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import com.netmusiclite.data.AppearancePrefs
import com.netmusiclite.data.CoverBackdrop
import com.netmusiclite.data.M3eBackgroundStyle
import kotlin.math.pow

/**
 * 封面模糊播放页：控制件材质必须**透出页面正在画的那张模糊封面**，
 * 不允许再叠一张独立取样的模糊图或白色高光（2026-10-17 上一轮 4% 规则的根因）。
 */
internal fun playerCoverGlassMatchesBackground(): Boolean =
    AppearancePrefs.isM3e && AppearancePrefs.m3eBackgroundStyle == M3eBackgroundStyle.COVER_BLUR

/**
 * ★ 2026-10-17 用户新口径（替换「与背景差 ≤4%」）：
 *   「m3e 主题的按钮的亮度永远比亮色封面暗 15%，比暗色封面亮 15%，
 *     并且给下方弯曲胶囊形状的栏目加一圈描边，随着封面亮暗变化」。
 *
 * 判定基准 = 播放页**实际可见的模糊封面底**（[CoverBackdrop.current] 的成图平均亮度，
 * 已含曝光归一；与 NcmBackground 的画法同一把尺子）。把线性亮度换算到 sRGB
 * （`pow(1/2.2)`，与 sRGB 混合同一空间）后：
 *  · 亮底（c ≥ [LIGHT_BACKDROP_THRESHOLD]）→ 叠 15% 黑，合成 c' = 0.85c；
 *  · 暗底 → 叠白，解 L' = c(1-a) + a = 1.15c ⇒ a = 0.15c / (1-c)，并限幅避免发灰/过曝。
 * 描边方向与填充相反（亮底深描边、暗底浅描边），保证任何封面上胶囊都有边界。
 *
 * 没有底图（无歌 / 底图还在算）时按中间灰兜底：仍给 15% 暗化，不会突然变成「无材质」。
 */
internal data class PlayerControlShade(val fill: Color, val stroke: Color)

/** 亮/暗分界：成图平均 sRGB 亮度 ≥ 0.5 视为亮底（深色目标 0.14 线性≈0.41 sRGB，浅色目标 0.50≈0.73）。 */
private const val LIGHT_BACKDROP_THRESHOLD = 0.5f
private const val CONTRAST_ALPHA = 0.15f
private const val STROKE_ALPHA = 0.30f

/** 模糊封面底的可见 sRGB 亮度；无底图时兜底 0.5。 */
internal fun playerBackdropSrgbLuminance(): Float {
    val layer = CoverBackdrop.current ?: return 0.5f
    return layer.luminance.coerceIn(0f, 1f).pow(1f / 2.2f)
}

/*
 * ★ 2026-10-17 二次修正：15% 必须按**控件所在位置**的局部背景算。
 *   第一版用整张成图的平均亮度反算 shade，深/浅渐变的封面上（本机播放页顶部亮、底部暗）
 *   把同一个 alpha 盖到所有位置，实测局部只差 2~6%，达不到用户要的 15%。
 *   这里直接对页面正在画的那张模糊底图（CoverBackdrop.current.image）按
 *   `Image(fillMaxSize, ContentScale.Crop)` 的同一映射取像素，逐控件算局部亮度。
 *   PixelMap 按底图实例缓存（底图只有 96px 级），只在主线程读。
 */
private var sampledImage: androidx.compose.ui.graphics.ImageBitmap? = null
private var sampledPixels: androidx.compose.ui.graphics.PixelMap? = null

/** 模糊封面底在窗口坐标 (xPx, yPx) 处的 sRGB 亮度；拿不到底图/尺寸时回退全图平均。 */
internal fun playerBackdropSrgbLuminanceAt(
    xPx: Float,
    yPx: Float,
    viewportW: Float,
    viewportH: Float,
): Float {
    val layer = CoverBackdrop.current ?: return 0.5f
    if (viewportW <= 1f || viewportH <= 1f) return playerBackdropSrgbLuminance()
    val image = layer.image
    val pixels = if (image === sampledImage) {
        sampledPixels ?: image.toPixelMap().also { sampledImage = image; sampledPixels = it }
    } else {
        image.toPixelMap().also { sampledImage = image; sampledPixels = it }
    }
    val scale = maxOf(viewportW / pixels.width, viewportH / pixels.height)
    val offX = (viewportW - pixels.width * scale) / 2f
    val offY = (viewportH - pixels.height * scale) / 2f
    val ix = ((xPx - offX) / scale).toInt().coerceIn(0, pixels.width - 1)
    val iy = ((yPx - offY) / scale).toInt().coerceIn(0, pixels.height - 1)
    val c = pixels[ix, iy]
    return (0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue).coerceIn(0f, 1f)
}

/** 亮度 → 控制件材质（纯函数，便于单测；[backdropSrgb] 为 0..1 的 sRGB 亮度）。 */
internal fun contrastShadeFor(backdropSrgb: Float): PlayerControlShade {
    val c = backdropSrgb.coerceIn(0.02f, 0.98f)
    val light = c >= LIGHT_BACKDROP_THRESHOLD
    val fill = if (light) {
        Color.Black.copy(alpha = CONTRAST_ALPHA)
    } else {
        // 亮 15%：sRGB 合成 c' = c(1-a)+a = 1.15c
        val a = (CONTRAST_ALPHA * c / (1f - c)).coerceIn(0.02f, 0.30f)
        Color.White.copy(alpha = a)
    }
    val stroke = if (light) Color.Black.copy(alpha = STROKE_ALPHA)
    else Color.White.copy(alpha = STROKE_ALPHA)
    return PlayerControlShade(fill, stroke)
}

/** 当前播放页背景对应的控制件材质；换歌/换底图时随 [CoverBackdrop.current] 自动重组。 */
internal fun playerControlShade(): PlayerControlShade =
    contrastShadeFor(playerBackdropSrgbLuminance())
