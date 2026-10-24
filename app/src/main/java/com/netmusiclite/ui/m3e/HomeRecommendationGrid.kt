package com.netmusiclite.ui.m3e

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.wear.compose.foundation.ScrollInfoProvider
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScrollProgress
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.TransformedContainerPainterScope

/** Wear has no transforming staggered grid. Reuse its public material spec per native grid item. */
@Composable
internal fun rememberHomeGridTransformation(
    state: LazyStaggeredGridState,
    key: Any,
    spec: TransformationSpec,
): SurfaceTransformation = remember(state, key, spec) {
    object : SurfaceTransformation, TransformedContainerPainterScope {
        fun progress(): TransformingLazyColumnItemScrollProgress {
            val layout = state.layoutInfo
            val item = layout.visibleItemsInfo.firstOrNull { it.key == key }
                ?: return TransformingLazyColumnItemScrollProgress.Unspecified
            val height = layout.viewportSize.height.toFloat()
            if (height <= 0f) return TransformingLazyColumnItemScrollProgress.Unspecified
            val top = item.offset.y - layout.viewportStartOffset
            return TransformingLazyColumnItemScrollProgress(top / height, (top + item.size.height) / height)
        }

        override val DrawScope.scrollProgress: TransformingLazyColumnItemScrollProgress
            get() = progress()
        override val DrawScope.itemHeight: Float
            get() = size.height

        override fun createContainerPainter(painter: Painter, shape: Shape, border: BorderStroke?): Painter =
            with(spec) { createTransformedContainerPainter(painter, shape, border) }

        override fun GraphicsLayerScope.applyContainerTransformation() {
            with(spec) { applyContainerTransformation(progress()) }
        }

        override fun GraphicsLayerScope.applyContentTransformation() {
            with(spec) { applyContentTransformation(progress()) }
        }
    }
}

/** Keep native Wear time-text scroll-away behavior while the whole home uses one lazy grid. */
@Composable
internal fun rememberHomeGridScrollInfo(state: LazyStaggeredGridState): ScrollInfoProvider = remember(state) {
    object : ScrollInfoProvider {
        override val isScrollAwayValid: Boolean get() = true
        override val isScrollable: Boolean get() = state.canScrollForward || state.canScrollBackward
        // ★ 2026-10-17 第五轮（真机反馈「表冠滚动时进度条不认、自动隐藏」）：
        //   自定义表冠管道是逐帧 dispatchRawDelta，不经过 scroll 会话 ⇒
        //   state.isScrollInProgress 恒 false，ScreenScaffold 会误判 Idle 而隐藏进度条。
        //   RotaryScrollActivity 在「有输入 → 停稳收尾」期间为真，这里并进显示口径；
        //   滚动本身仍由真实 gridState 提供（Material 弧形进度条读的就是真实进度）。
        override val isScrollInProgress: Boolean
            get() = state.isScrollInProgress || com.netmusiclite.ui.components.RotaryScrollActivity.active
        override val anchorItemOffset: Float
            get() = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "search_play" }
                ?.let { (-it.offset.y).coerceAtLeast(0).toFloat() } ?: Float.NaN
        override val lastItemOffset: Float
            get() {
                val layout = state.layoutInfo
                val last = layout.visibleItemsInfo.firstOrNull { it.index == layout.totalItemsCount - 1 }
                    ?: return 0f
                return (layout.viewportEndOffset - last.offset.y - last.size.height).coerceAtLeast(0).toFloat()
            }
    }
}
