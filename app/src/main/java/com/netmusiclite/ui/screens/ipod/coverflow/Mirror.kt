package com.netmusiclite.ui.screens.ipod.coverflow

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.times
/**
 * 封面倒影（移植自 pakohan/compose-coverflow，MIT）。
 *
 * ★ 2026-10-17 滚动性能重写。
 * 旧实现（原库写法）每个可见卡片要做：**两份封面内容组合**（两份 AsyncImage 节点）+
 * `CompositingStrategy.Offscreen` 离屏合成 + `BlendMode.DstIn` 渐变遮罩 + `blur(1dp,3dp)`。
 * 手表 GPU 上这一串是滚动掉帧的大头（尤其 blur 的 RenderEffect，每卡每帧重算）。
 *
 * 现在改为「单份内容 + 单层复用 + 横带 alpha 近似渐变」：
 *  · 封面只组合、只绘制一次，录进一个 [rememberGraphicsLayer]（记完立刻画一次原图）；
 *  · 倒影直接复用这层录制结果，上下翻转画到封面正下方 —— **零 blur、零离屏合成**；
 *  · 渐变淡出用 8 条高度相同的横带近似：每条带画一次同一 layer、alpha 从上到下递减
 *    （[MIRROR_MAX_ALPHA] → ~0）。表盘尺寸下 8 级 alpha 阶梯肉眼不可辨，代价只是
 *    8 次小 quad 绘制，远低于一次 blur + saveLayer。
 *  · 录制/重绘都在同一个 drawWithContent 节点里完成：封面加载完成时子内容失效会带着
 *    整段重绘，倒影不会停留在「封面还没加载」的旧帧。
 *
 * 版式与原实现完全一致：Column 高 2×封面、上方留 1×封面的 padding，居中后原图正好落在
 * 封面的位置，倒影落在它正下方（不占布局空间、不影响点击区域语义）。
 */
@Composable
internal fun Mirror(
    modifier: Modifier = Modifier,
    coverSize: Float,
    content: @Composable () -> Unit,
) {
    val localDensity = LocalDensity.current
    val coverSizeDp = with(localDensity) { Dp(coverSize / localDensity.density) }
    // 原图只组合一次，录进这个 layer；原图与倒影都从这一份绘制（旧版倒影是第二份组合）
    val layer = rememberGraphicsLayer()

    Column(
        modifier = modifier
            .padding(top = coverSizeDp) // necessary to push the mirrored image down and keep the real one centered
            .fillMaxWidth()
            .requiredHeight(2 * coverSizeDp) // original + mirror image
            .drawWithContent {
                val half = size.height / 2f
                // ① 原图：录制当前内容并原样画出（后续倒影复用这一层，不再组合第二份封面）
                layer.record { this@drawWithContent.drawContent() }
                layer.alpha = 1f
                drawLayer(layer)
                if (half <= 0f) return@drawWithContent
                // ② 倒影：上下翻转（y' = 2·half - y）画到封面正下方，用递减 alpha 的横带模拟渐变淡出。
                //   只画封面高度的 ~55%：完整高度会一直延伸到屏幕底部、盖到下方标题文字后面。
                val mirrorHeight = half * MIRROR_LENGTH_RATIO
                for (band in 0 until MIRROR_BANDS) {
                    val top = half + mirrorHeight * band / MIRROR_BANDS
                    val bottom = half + mirrorHeight * (band + 1) / MIRROR_BANDS
                    // 贴近封面处最亮，越往下越透明：第一条 ≈ 0.26，最后一条 ≈ 0.01
                    val bandAlpha = MIRROR_MAX_ALPHA * (1f - (band + 0.5f) / MIRROR_BANDS)
                    if (bandAlpha <= 0f) continue
                    clipRect(0f, top, size.width, bottom) {
                        // 绕 y = half 这条水平线上下翻转：y' = 2·half − y
                        // ⚠ pivot 必须显式给 (0, half)：DrawTransform.scale 的默认 pivot 是画布中心，
                        //   直接用默认值会翻错位置。
                        withTransform({
                            scale(1f, -1f, pivot = Offset(0f, half))
                        }) {
                            layer.alpha = bandAlpha
                            drawLayer(layer)
                        }
                    }
                }
                // 复位：下次绘制第一帧原图时不能沿用最后一条横带的半透明 alpha
                layer.alpha = 1f
            },
    ) {
        // 原图占上半区
        Box(
            propagateMinConstraints = true,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .height(coverSizeDp),
        ) {
            content()
        }
        // 下半区不组合内容：倒影由上面的 drawWithContent 直接绘制
        Spacer(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
    }
}

/** 倒影横带数量：8 段在表盘尺寸下 alpha 阶梯不可辨，又只有 8 次小 quad 绘制。 */
private const val MIRROR_BANDS = 8

/** 倒影可见高度 = 封面高度 × 该比例（55%：经典 CoverFlow 的短倒影，不伸到屏幕底部压标题）。 */
private const val MIRROR_LENGTH_RATIO = 0.55f

/** 倒影贴近封面处的最大不透明度（与原 blur 倒影的观感接近）。 */
private const val MIRROR_MAX_ALPHA = 0.26f
