package com.netmusiclite.ui.screens.ipod.coverflow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * CoverFlow 单张封面（移植自 pakohan/compose-coverflow，MIT）。
 *
 * ★ 2026-10-17 滚动性能重写（原实现会在滚动时逐帧重组每个可见卡片，是抽取页最大的卡顿源）：
 *   ① 水平位置不再在组合期读：`onGloballyPositioned` 每帧写入的 px 位置只被
 *      [graphicsLayer] 的**延迟读 lambda** 读取 —— 位置变化只脏 layer，不触发重组/重测量。
 *      旋转 / 位移 / 缩放的计算也全部挪进这个 lambda（只在绘制前算一次）。
 *   ② 选中态不再「每次重组都调一遍 onSelectedHandler」：改为 snapshotFlow 观察位置跨越
 *      正中（[Geometry.isSelected]）的翻转，只在选中/取消选中时回调一次。
 *   ③ zIndex 必须参与父布局的绘制排序、不能延迟读，所以把它离散化成一个整型档位：
 *      `-round(|距中心| / 单卡间距)`。相邻卡片的档位恒不相同、跨中心对称时档位相同（回退到
 *      列表绘制顺序），排序语义与原版的连续 `1 - abs(distance)` 完全一致；但值只在
 *      「跨过半张卡」时才变 → 一次甩动只重组几次，而不是每帧。
 *   功能（点按、居中卡片判定、3D 变换与倒影）与重写前完全一致。
 */
@Composable
internal fun Cover(
    geometry: Geometry,
    onClickHandler: () -> Unit = {},
    onSelectedHandler: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    // 位置用 Float.NaN 表示「尚未测得」：只在绘制期（延迟读）消费，不参与组合
    val horizontalPosition = remember { mutableFloatStateOf(Float.NaN) }
    // 首帧位置未测得前不画封面（沿用原库语义：避免用错误的居中变换画出第一帧）
    var positioned by remember { mutableStateOf(false) }
    // zIndex 的离散档位（见类头 ③）
    var zRank by remember { mutableIntStateOf(0) }

    // 选中态：位置变化不进组合，只在「是否位于正中」翻转时回调一次
    val currentOnSelected by rememberUpdatedState(onSelectedHandler)
    LaunchedEffect(geometry) {
        snapshotFlow {
            val x = horizontalPosition.floatValue
            if (x.isNaN()) null else geometry.isSelected(x)
        }.distinctUntilChanged().collect { selected ->
            selected?.let { currentOnSelected(it) }
        }
    }

    val density = LocalDensity.current
    // slot 宽 = 相邻两张卡的间距（coverOffset），封面自身比 slot 宽，会向两侧溢出并相互叠压
    val slotWidth = with(density) { Dp(geometry.coverOffset / density.density) }
    val coverSide = with(density) { Dp(geometry.coverSize / density.density) }

    // This box is used to space the elements horizontally and to give them the proper z index.
    Box(
        modifier = Modifier
            .width(slotWidth)
            .zIndex(zRank.toFloat())
            .onGloballyPositioned { coordinates ->
                val x = coordinates.positionInParent().x
                horizontalPosition.floatValue = x
                if (!positioned) positioned = true
                val rank = if (geometry.coverOffset > 0) {
                    // 相邻卡片的 |距离| 差恰好一个 coverOffset ⇒ 取整档位必不相同；
                    // 对称两侧同档（与原版连续 zIndex 打平一致，回退列表绘制顺序）
                    -abs(geometry.distanceToCenter(x) / geometry.coverOffset).roundToInt()
                } else {
                    0
                }
                if (zRank != rank) zRank = rank
            },
        contentAlignment = Alignment.Center,
    ) {
        // this makes sure that the cover only gets painted after we got the proper horizontal position.
        if (positioned) {
            // This Box applies the transformation effects: rotation, horizontal shift, and zoom
            Box(
                modifier = Modifier
                    .requiredSize(coverSide)
                    // ★ 延迟读：位置/几何的读取全部发生在绘制前的 layer 更新里，滚动期本函数不重组
                    .graphicsLayer {
                        val x = horizontalPosition.floatValue
                        if (!x.isNaN()) {
                            val distanceToCenter = geometry.distanceToCenter(x)
                            val scale = geometry.scale(distanceToCenter)
                            rotationY = geometry.rotation(distanceToCenter)
                            translationX = geometry.translationX(distanceToCenter)
                            scaleX = scale
                            scaleY = scale
                        }
                    },
                propagateMinConstraints = true,
            ) {
                val interactionSource = remember { MutableInteractionSource() }
                if (geometry.params.mirror) {
                    Mirror(
                        modifier = Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClickHandler,
                        ),
                        coverSize = geometry.coverSize,
                    ) {
                        content()
                    }
                } else {
                    Box(
                        modifier = Modifier.clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClickHandler,
                        ),
                    ) {
                        content()
                    }
                }
            }
        }
    }
}
