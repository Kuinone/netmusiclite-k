package com.netmusiclite.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.PlayTextPrimary
import kotlin.math.cos
import kotlin.math.sin

/**
 * 右缘弯曲进度条：贴着圆表右弧的 40° 弧段。
 * progress 0..1；track 为 8% 白，进度为主题色。
 *
 * 流畅度关键（2026-09-04 trace 实测）：Canvas 只占右缘条带（约 16dp 宽），
 * 不再 fillMaxSize——progress 每帧变化时的失效/重录范围从全屏缩到条带，
 * 消除「Record View#draw() 全窗重录」（实测 267ms 尖帧的元凶）。
 * progress 用 provider 在绘制期读取：滚动/播放进度变化只重绘弧线，不触发任何重组。
 *
 * ★ 2026-10-11 方屏（!isRoundScreen()）：整条弧拉直 —— 右缘竖直线条原地替换弧段
 *   （用户口径「方形屏幕时所有进度条类拉直」）。解弯不换位：中线距右缘 (1−0.94)R，
 *   长度 = 弦长 2·0.94R·sin14°（两端与弧端点 y 一致），圆头、进度仍锚上端向下生长。
 *   圆屏分支保持原样，一个像素不变。
 */
@Composable
fun CircularSideProgress(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    /**
     * 进度弧颜色，默认全站强调色 [Accent]。
     * 歌词页在「取色 / 封面模糊」风格下改传 [com.netmusiclite.ui.theme.SongAccent]
     * （跟专辑封面主题色），与同页歌词墨色、播放页进度弧同源（2026-10-06 用户口径）。
     */
    accent: Color = Accent,
) {
    if (com.netmusiclite.data.AppearancePrefs.isM3e) return

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val screenW = with(density) { maxWidth.toPx() }
        val rBase = minOf(screenW, with(density) { maxHeight.toPx() }) / 2f
        val r = rBase * 0.94f
        val stroke = with(density) { 3.dp.toPx() }
        val square = !isRoundScreen()
        // 弧段（-14°..+14°，纵向长度为原 40° 弧的 70%）包围盒：宽 = r(1-cos14°)+2·stroke，高 = 2·r·sin14°+2·stroke
        val halfSweep = Math.toRadians(14.0)
        // 方屏直条：中线距右缘 (1−0.94)R；长度 = 弦长 2·0.94R·sin14°（两端与弧端点 y 对齐）
        val lineMargin = 0.06f * rBase
        val lineSpan = 2f * r * sin(halfSweep).toFloat()
        val bandWpx = if (square) lineMargin + stroke else r * (1f - cos(halfSweep).toFloat()) + stroke * 2f
        val bandHpx = if (square) lineSpan + stroke else 2f * r * sin(halfSweep).toFloat() + stroke * 2f
        val bandW = with(density) { bandWpx.toDp() }
        val bandH = with(density) { bandHpx.toDp() }
        // 轨道色在组合期读取（draw 作用域内不能读 @Composable getter）：作用域外 = 原 TextPrimary
        val trackColor = PlayTextPrimary.copy(alpha = 0.10f)

        Box(modifier.fillMaxSize()) {
            Canvas(
                Modifier
                    .align(Alignment.CenterEnd)
                    .width(bandW)
                    .height(bandH),
            ) {
                val progress = progress().coerceIn(0f, 1f)
                if (square) {
                    // 方屏直条：竖直线（圆头），进度锚上端向下生长
                    val x = bandWpx - lineMargin
                    val top = stroke / 2f
                    val bottom = top + lineSpan
                    drawLine(trackColor, Offset(x, top), Offset(x, bottom), stroke, StrokeCap.Round)
                    if (progress > 0.01f) {
                        drawLine(accent, Offset(x, top), Offset(x, top + lineSpan * progress), stroke, StrokeCap.Round)
                    }
                } else {
                    // 条带右对齐屏幕右缘、垂直居中；圆心 = 屏幕中心
                    // 条带左缘屏幕 x = screenW - bandWpx → 圆心本地 x = screenW/2 - (screenW - bandWpx)
                    val cX = screenW / 2f - (screenW - bandWpx)
                    val cY = bandHpx / 2f
                    val topLeft = Offset(cX - r, cY - r)

                    // track（圆头：两端 StrokeCap.Round 半圆收尾，上下等宽）
                    drawArc(
                        // 轨道：跟随主题的弱色（深色主题→白 10%，浅色主题→黑 10%），
                        // 否则浅色页面上轨道完全消失
                        color = trackColor,
                        startAngle = -14f, sweepAngle = 28f, useCenter = false,
                        topLeft = topLeft, size = Size(r * 2, r * 2),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                    // progress
                    if (progress > 0.01f) {
                        drawArc(
                            color = accent,
                            startAngle = -14f, sweepAngle = 28f * progress, useCenter = false,
                            topLeft = topLeft, size = Size(r * 2, r * 2),
                            style = Stroke(stroke, cap = StrokeCap.Round),
                        )
                    }
                }
            }
        }
    }
}
