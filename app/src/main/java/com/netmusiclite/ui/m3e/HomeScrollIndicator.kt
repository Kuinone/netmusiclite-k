package com.netmusiclite.ui.m3e

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.MaterialTheme
import com.netmusiclite.ui.components.isRoundScreen
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * m3e 主页的**原生样式滑动进度条**（2026-10-17 第三轮用户口径：
 * 「主页不显示 m3e 原生滑动进度条了请你添加，并且不被手表边缘弧度裁切的同时不和卡片重叠」）。
 *
 * 背景：m3e 主页是 `LazyStaggeredGrid`（瀑布流），而 Wear M3 的 ScrollIndicator 只支持
 * LazyListState / ScalingLazyListState / TransformingLazyColumnState / ScrollState 四种 state，
 * **没有瀑布流版本**；主页用的 `ScreenScaffold(scrollInfoProvider = ...)` 重载里
 * scrollIndicator 默认是 null —— 所以之前完全没有指示条（不是被改没了，是这条重载没给默认值）。
 *
 * 这里按 Wear M3 官方 `IndicatorImpl.drawCurvedIndicator` 的几何复刻：
 *  · 圆屏：沿表盘右缘画**圆弧** —— 弧半径 = 屏半径 − 2dp 边距 − 条宽/2，
 *    整条永远落在圆内，不会被边缘弧度裁切；
 *  · 方屏：右缘居中的竖直线段（官方方屏同口径）；
 *  · 滚动位置/长度：由瀑布流 layoutInfo 折算（与官方 LazyColumnStateAdapter 同一套公式，
 *    可见占比夹取 0.3~0.7）；
 *  · 水平位置：由 `ScreenScaffold` 的 AnimatedIndicator 叠在内容之上；条只占屏幕最右
 *    ~7.5dp，而主页卡片 contentPadding end = 16dp ⇒ **不会和卡片重叠**。
 *
 * 所有 layoutInfo 读数都发生在 Canvas 的绘制期 → 滚动只重绘这一小块，不触发主页列表重组。
 */
@Composable
internal fun M3eHomeScrollIndicator(state: LazyStaggeredGridState) {
    val scheme = MaterialTheme.colorScheme
    val thumb = scheme.onSurface
    val track = scheme.onSurface.copy(alpha = 0.30f)
    val round = isRoundScreen()

    // 与官方 ScrollIndicatorDefaults 一致：长 50dp、粗 5dp、离边缘 2dp、弧段间隙 3dp
    val indicatorHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { 50.dp.toPx() }
    val indicatorWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { 5.dp.toPx() }
    val edgePaddingPx = with(androidx.compose.ui.platform.LocalDensity.current) { 2.dp.toPx() }
    val gapHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { 3.dp.toPx() }

    Canvas(Modifier.fillMaxSize()) {
        // ---- 滚动进度（绘制期读取 layoutInfo）----
        val layout = state.layoutInfo
        val visible = layout.visibleItemsInfo
        if (visible.isEmpty() || layout.totalItemsCount == 0) return@Canvas
        val firstItem = visible.first()
        val lastItem = visible.last()
        val firstIsFirst = firstItem.index == 0
        val beforeFirstPadding = if (firstIsFirst) layout.beforeContentPadding else 0
        val firstOffset = firstItem.offset.y - layout.viewportStartOffset - beforeFirstPadding
        val decimalFirst = firstItem.index.toFloat() -
            firstOffset.coerceAtMost(0).toFloat() /
            (firstItem.size.height + beforeFirstPadding).coerceAtLeast(1).toFloat()
        val lastIsLast = lastItem.index == layout.totalItemsCount - 1
        val lastSize = lastItem.size.height + (if (lastIsLast) layout.afterContentPadding else 0)
        val lastOffset = (layout.viewportEndOffset - lastItem.offset.y)
            .coerceAtMost(lastSize).coerceAtLeast(1)
        val decimalLast = lastItem.index.toFloat() +
            lastOffset.toFloat() / lastSize.coerceAtLeast(1).toFloat()
        val distanceFromEnd = layout.totalItemsCount - decimalLast
        val positionFraction = if (decimalFirst + distanceFromEnd == 0f) {
            0f
        } else {
            decimalFirst / (decimalFirst + distanceFromEnd)
        }.coerceIn(0f, 1f)
        val sizeFraction = ((decimalLast - decimalFirst) / layout.totalItemsCount.toFloat())
            .coerceIn(0.3f, 0.7f)

        if (round) {
            // ---- 圆屏：沿右缘画弧形指示条（官方 drawCurvedIndicator 同几何）----
            val diameterPx = size.minDimension
            val usableRadius = diameterPx / 2f - edgePaddingPx
            val arcRadius = usableRadius - indicatorWidthPx / 2f
            val gapSweep = pixelsToDegrees(indicatorWidthPx + gapHeightPx, usableRadius)
            val sweep = pixelsToDegrees(indicatorHeightPx, usableRadius) + gapSweep
            // 弧圆心 = 屏幕中心；外接框左上 = 圆心 − 半径
            val arcTopLeft = Offset(size.width / 2f - arcRadius, size.height / 2f - arcRadius)
            val arcSize = Size(arcRadius * 2f, arcRadius * 2f)
            // 官方角度约定：0° = 3 点钟方向，正值顺时针；右缘居中的弧 = [-sweep/2, +sweep/2]
            val startAngle = -sweep / 2f
            val topSweep = sweep * positionFraction
            val midSweep = sweep * sizeFraction
            val bottomSweep = sweep * (1f - sizeFraction - positionFraction)
            drawArcSegment(startAngle, topSweep, arcTopLeft, arcSize, track, indicatorWidthPx, gapSweep)
            drawArcSegment(startAngle + topSweep, midSweep, arcTopLeft, arcSize, thumb, indicatorWidthPx, gapSweep)
            drawArcSegment(
                startAngle + topSweep + midSweep,
                bottomSweep,
                arcTopLeft,
                arcSize,
                track,
                indicatorWidthPx,
                gapSweep,
            )
        } else {
            // ---- 方屏：官方在方屏画竖直线段 ----
            val x = size.width - edgePaddingPx - indicatorWidthPx / 2f
            val cy = size.height / 2f
            val top = cy - indicatorHeightPx / 2f
            drawLine(
                color = track,
                start = Offset(x, top),
                end = Offset(x, top + indicatorHeightPx),
                strokeWidth = indicatorWidthPx,
                cap = StrokeCap.Round,
            )
            val midStart = top + indicatorHeightPx * positionFraction
            val midLen = indicatorHeightPx * sizeFraction
            drawLine(
                color = thumb,
                start = Offset(x, midStart),
                end = Offset(x, midStart + midLen),
                strokeWidth = indicatorWidthPx,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 复制自官方：把「像素弦长」换算成对应圆心角（度）。 */
private fun pixelsToDegrees(lengthPx: Float, radius: Float): Float {
    if (radius <= 0f) return 0f
    return 2f * asin((lengthPx / 2f / radius).coerceIn(-1f, 1f)) * (180f / Math.PI.toFloat())
}

/**
 * 复制自官方 drawCurvedIndicatorSegment：太短的弧退化成圆点（渐进出现），正常弧画圆头 arc。
 * 参数都是本 Canvas 的局部坐标（Canvas = 全屏）。
 */
private fun DrawScope.drawArcSegment(
    startAngle: Float,
    sweep: Float,
    arcTopLeft: Offset,
    arcSize: Size,
    color: Color,
    strokeWidth: Float,
    gapSweep: Float,
) {
    if (sweep <= 0f) return
    if (sweep <= gapSweep) {
        val angle = Math.toRadians((startAngle + sweep / 2f).toDouble())
        val fraction = (sweep / gapSweep).coerceIn(0f, 1f)
        val dotRadius = strokeWidth / 2f * fraction
        if (dotRadius <= 0f) return
        val radius = arcSize.width / 2f
        drawCircle(
            color = color.copy(alpha = color.alpha * fraction),
            radius = dotRadius,
            center = Offset(
                arcTopLeft.x + radius + radius * cos(angle).toFloat(),
                arcTopLeft.y + radius + radius * sin(angle).toFloat(),
            ),
        )
    } else {
        drawArc(
            color = color,
            startAngle = startAngle + gapSweep / 2f,
            sweepAngle = max(sweep - gapSweep, 0f),
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
    }
}
