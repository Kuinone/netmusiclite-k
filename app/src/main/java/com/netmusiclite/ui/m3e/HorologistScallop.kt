package com.netmusiclite.ui.m3e

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.pillStar
import androidx.graphics.shapes.toPath

/**
 * M3 Expressive「扇贝形」按钮轮廓（2026-10-17 第二轮，用户参考截图 = Horologist media sample
 * 的 animated PlayerScreen）。
 *
 * 官方实现是 `PlayPauseButtonDefaults.outerScallopPolygon(...)`：
 * ```kotlin
 * RoundedPolygon.pillStar(
 *     numVerticesPerRadius = 10,
 *     width = size.toPx() / 2f,
 *     height = size.toPx() / 2f,
 *     innerRadiusRatio = 0.8f,
 *     rounding = CornerRounding(12.dp.toPx()),
 * )
 * ```
 * 这里把它包成 Compose 的 [Shape]：`createOutline` 按实际尺寸现算 10 瓣圆角星，
 * 用 `androidx.graphics:graphics-shapes` 的 `RoundedPolygon.toPath()` 转成 `Outline.Generic`。
 * · 10 瓣 + innerRadiusRatio 0.8 = 花瓣深度只有 20%（官方观感：远看像圆、近看是花瓣），
 *   太深会变成"齿轮"，太浅就没有扇贝感；
 * · 圆角 12dp（官方同值，相对 62dp 按钮半径约 39%，瓣尖圆润）。
 *
 * 为什么用库而不是手写 Path：`pillStar` 的花瓣沿圆周均匀分布、圆角过渡是 G2 连续的，
 * 手写贝塞尔很难在 62dp 尺寸上保持一致观感；本库是 wear compose-material3 1.5.6 的
 * 运行时传递依赖（Lead 负责在 app/build.gradle.kts 补 implementation 行，版本 1.0.1）。
 */
internal class ScallopShape(
    private val verticesPerRadius: Int = 10,
    private val innerRadiusRatio: Float = 0.8f,
    private val cornerRadius: Dp = 12.dp,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        // 尺寸防御：Compose 在首次测量/被折叠到 0 时可能给 0 尺寸，避免生成非法多边形
        val width = size.width.coerceAtLeast(1f)
        val height = size.height.coerceAtLeast(1f)
        val cornerPx = with(density) { cornerRadius.toPx() }
        val polygon = RoundedPolygon.pillStar(
            // pillStar 的 width/height 是「半宽/半高」语义：传 size/2 时多边形正好铺满 size
            width = width / 2f,
            height = height / 2f,
            numVerticesPerRadius = verticesPerRadius,
            innerRadiusRatio = innerRadiusRatio,
            rounding = CornerRounding(cornerPx),
            centerX = width / 2f,
            centerY = height / 2f,
        )
        return Outline.Generic(polygon.toPath().asComposePath())
    }
}
