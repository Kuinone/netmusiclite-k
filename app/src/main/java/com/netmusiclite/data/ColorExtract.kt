package com.netmusiclite.data

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color

/**
 * 图片主色提取（纯 Kotlin，无 Palette 依赖），永不返回 null：
 * RGB 4bit 量化桶统计，剔除近黑/近白/低饱和块 → 饱和主桶均值；
 * 无饱和主桶 → 有信息像素均值；全灰图 → 整图均值。
 *
 * 2026-10-01 从 `ui/components/NcmBackground.kt` 的 private `dominantOf()` 抽到 data 层。
 * 抽出的原因：现在有**两处**需要这套算法 ——
 *  1. `SongAmbient`（封面主色 → 播放页环境色）
 *  2. `BackgroundStore.seedColor`（背景图主色 → Android 11 的动态取色种子）
 * 留两份实现必然漂移，所以合并到一处。
 *
 * ⚠ 原实现里 `bmp` 为空时直接返回 ui 层的 `Bg`，抽到 data 层后不能再反向依赖 ui 包，
 *   改为由调用方传入 [fallback]。
 *
 * ★ 2026-10-03 用户口径「禁用自动取色出现的高饱和度颜色」：取色结果统一做 HSL 限饱和
 *   （[MAX_EXTRACT_SATURATION]，色相/明度不变）—— 本函数是封面主色与背景图主色两条
 *   链路的唯一出口，在这一处压制即两条链路同时生效；预设主题色 / 中性元素不经过这里，
 *   不受影响。[fallback] 是上游主题色，不做限制。
 */
internal fun dominantColorOf(bmp: Bitmap, fallback: Color): Color {
    val w = bmp.width
    val h = bmp.height
    val n = w * h
    if (n == 0) return fallback
    val px = IntArray(n)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    val count = IntArray(4096)
    val sumR = IntArray(4096)
    val sumG = IntArray(4096)
    val sumB = IntArray(4096)
    var used = 0L
    var allR = 0L
    var allG = 0L
    var allB = 0L
    for (p in px) {
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        allR += r
        allG += g
        allB += b
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val sat = if (max == 0) 0 else (max - min) * 255 / max
        if (max < 36 || sat < 26 || (max > 232 && sat < 40)) continue
        val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
        count[key]++
        sumR[key] += r
        sumG[key] += g
        sumB[key] += b
        used++
    }
    var best = -1
    for (i in 0 until 4096) if (count[i] > (if (best < 0) 0 else count[best])) best = i
    if (best >= 0 && used > 0 && count[best] * 8 >= used) {
        return capSaturation(
            Color(
                red = sumR[best] / count[best],
                green = sumG[best] / count[best],
                blue = sumB[best] / count[best],
            ),
        )
    }
    if (used > 0) {
        return capSaturation(
            Color(
                red = (allR / used).toInt(),
                green = (allG / used).toInt(),
                blue = (allB / used).toInt(),
            ),
        )
    }
    return capSaturation(
        Color(
            red = (allR / n).toInt(),
            green = (allG / n).toInt(),
            blue = (allB / n).toInt(),
        ),
    )
}

/**
 * 自动取色饱和度上限（HSL S ≤ 0.50）。
 * 依据 2026-10-03 颜色预览图（`wm-配色预览-自动取色限饱和.png`）用户选定的档位：
 * 高饱和取色（如 #1411F0）压到 #4341C0 —— 仍保留可辨认的色相，同时压掉刺眼彩度。
 */
private const val MAX_EXTRACT_SATURATION = 0.50f

/**
 * HSL 限饱和：饱和度超过上限时压到上限（色相、明度不变）；未超上限原样返回。
 * 公式与 `ui/theme/Theme.kt` 的 toHsl / hsl 同式 —— data 层不能反向依赖 ui 包，
 * 故此处自带一份；若 Theme.kt 的取色插值公式有改动，两者需同步。
 */
private fun capSaturation(color: Color, max: Float = MAX_EXTRACT_SATURATION): Color {
    val r = color.red
    val g = color.green
    val b = color.blue
    val mx = maxOf(r, g, b)
    val mn = minOf(r, g, b)
    val l = (mx + mn) / 2f
    if (mx == mn) return color
    val d = mx - mn
    val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
    if (s <= max) return color
    val h = if (mx == r) {
        ((g - b) / d + if (g < b) 6f else 0f) / 6f
    } else if (mx == g) {
        ((b - r) / d + 2f) / 6f
    } else {
        ((r - g) / d + 4f) / 6f
    }
    val q = if (l < 0.5f) l * (1f + max) else l + max - l * max
    val p = 2f * l - q
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
