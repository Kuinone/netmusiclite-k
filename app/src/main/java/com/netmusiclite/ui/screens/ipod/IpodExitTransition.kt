package com.netmusiclite.ui.screens.ipod

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch

/**
 * 随机抽取页（iPod CoverFlow）的**退出转场**（2026-10-17 用户口径「退出也要有转场」）。
 *
 * 为什么需要这一层：m3e 主题走 androidx.wear.compose.navigation 1.5.6 的
 * SwipeDismissableNavHost —— 它只给**前进导航**做官方转场（新页 0.75→1 缩放 +
 * 0.1→1 淡入 + 白闪），而程序化 pop（本页的上滑返回、系统返回键）是**瞬间消失**、
 * 没有任何动画。右滑返回是 Wear 原生 swipe-dismiss 的跟手动画，不在本层范围。
 *
 * 做法：把「背景 + 抽取界面整页」包进一个 graphicsLayer，退出时按前进方向的
 * **镜像**演出 [IPOD_EXIT_MS]：
 *   · 缩放 1 → [IPOD_EXIT_SCALE]（与前进方向新页的入场起点 0.75 同一个数，原点=屏幕中心）；
 *   · 不透明度 1 → [IPOD_EXIT_ALPHA]（与前进方向新页的入场起点 0.1 同一个数）；
 *   · 曲线沿用 Wear 官方 NAV_HOST_ENTER_TRANSITION_EASING_STANDARD =
 *     CubicBezierEasing(0.4, 0, 0.2, 1)（见 BasicSwipeToDismissBoxNavHost.kt）。
 * 动画播完才真正 [onExitFinished]（= popBackStack）—— 保证「不能 pop 后动画还在跑」；
 * 动画期间本页仍盖在下层页面之上，淡出时透出的是 Wear 原生 nav host 垫在下面的
 * 真实上一页（不是黑底/空白）。
 *
 * 触发去重：上滑返回与系统返回键可能先后到达（甚至动画期间再按一次返回），
 * [requestExit] 用 [exiting] 做一次性闸门，避免双 pop。
 *
 * @param onExitFinished 退出动画播完后的收尾（真正的 popBackStack）
 * @param content 整页内容；回调参数是「请求退出」入口，应传给页面内的上滑/系统返回
 */
@Composable
fun IpodExitHost(
    onExitFinished: () -> Unit,
    content: @Composable (requestExit: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 0 = 常态，1 = 退出动画终点。只被 graphicsLayer 的延迟读 lambda 读取 → 逐帧播动画时零重组。
    val progress = remember { Animatable(0f) }
    var exiting by remember { mutableStateOf(false) }
    // 收尾回调经 rememberUpdatedState 读取：动画 400ms 内万一外层重组，拿到的永远是最新闭包
    val exitFinished by rememberUpdatedState(onExitFinished)

    val requestExit: () -> Unit = {
        if (!exiting) {
            exiting = true
            scope.launch {
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(IPOD_EXIT_MS, easing = IpodExitEasing),
                )
                exitFinished()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = progress.value
                if (p > 0f) {
                    // 整页（含背景）一起缩、一起淡：与前进方向「新页 0.75→1 + 0.1→1」镜像
                    val scale = 1f - (1f - IPOD_EXIT_SCALE) * p
                    scaleX = scale
                    scaleY = scale
                    alpha = 1f - (1f - IPOD_EXIT_ALPHA) * p
                }
            },
    ) {
        content(requestExit)
    }
}

/** 退出转场时长（ms）。约 400ms：比前进方向的 Wear 官方时长略长，收尾更从容、不「啪」地消失。 */
internal const val IPOD_EXIT_MS = 400

/** 退出终态缩放 0.75：与 Wear 原生前进方向新页的入场起点一致（同形镜像）。 */
private const val IPOD_EXIT_SCALE = 0.75f

/** 退出终态不透明度 0.1：与 Wear 原生前进方向新页的入场起点一致。 */
private const val IPOD_EXIT_ALPHA = 0.1f

/**
 * 与 Wear 官方 NAV_HOST_ENTER_TRANSITION_EASING_STANDARD 完全同一条曲线：
 * 起步快、中段匀速、收尾缓——时长拉长到 400ms 后仍不拖沓。
 */
internal val IpodExitEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
