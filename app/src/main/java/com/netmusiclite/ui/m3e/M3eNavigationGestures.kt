package com.netmusiclite.ui.m3e

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.netmusiclite.ui.nav.Routes
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Claim only an established left swipe; right swipes belong to the native Wear nav host. */
fun Modifier.m3eSwipeLeft(onSwipeLeft: (() -> Unit)?): Modifier = composed {
    val action = rememberUpdatedState(onSwipeLeft)
    if (onSwipeLeft == null) this else pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
            var dx = 0f
            var dy = 0f
            var claimed = false
            var cancelled = false
            while (true) {
                // Child sliders and overlays get first refusal; the outer Wear host runs after us.
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (claimed && !cancelled && dx < -56.dp.toPx()) action.value?.invoke()
                    break
                }
                if (change.isConsumed) { cancelled = true; break }
                val delta = change.positionChange()
                dx += delta.x
                dy += delta.y
                if (!claimed && maxOf(abs(dx), abs(dy)) > viewConfiguration.touchSlop) {
                    if (dx < 0f && abs(dx) > abs(dy) * 1.5f) claimed = true
                    else if (dx > 0f || abs(dy) > abs(dx) * 1.5f) break
                }
                if (claimed) change.consume()
            }
        }
    }
}

/**
 * 三星 One UI Watch 的 m3e 右滑兜底（2026-10-24，用户口径「方案 B、仅三星类机型」）。
 *
 * 背景：m3e 的右滑返回完全依赖 Wear 原生 SwipeDismissableNavHost（Main pass 的
 * SwipeToDismissBox）。三星上子页的列表/滚动容器会先把横向拖动消费掉，原生手势收不到；
 * 默认主题的 SwipeBackBox 在 Initial pass 抢先判定，所以「默认主题正常、m3e 不行」。
 *
 * 这里在 Initial pass **只观测、不消费**：确认是明确右滑、松手位移过阈值，且延迟
 * [FALLBACK_POP_DELAY_MS] 后栈顶仍是按下滑的那一页（说明原生 dismiss 没生效），
 * 才补一次 popBackStack。非三星机型直接 no-op，其它机型行为零变化。
 *
 * 为什么延迟而不是立即 pop：原生手势生效时 SwipeDismissableNavHost 在 dismiss 动画后
 * 才 pop，立即补会双 pop 退两层。延迟窗口同时覆盖手表上的 dismiss 动画时长。
 */
private const val FALLBACK_POP_DELAY_MS = 500L

fun Modifier.m3eSwipeBackFallback(nav: NavHostController): Modifier = composed {
    if (!com.netmusiclite.data.DeviceCompat.isSamsungWatch) return@composed this
    pointerInput(nav) {
      coroutineScope {
        // awaitEachGesture 的 lambda 是 restricted suspend scope（不能 delay），
        // 待补 pop 的 entry 交给外层协程延迟处理。
        val pending = kotlinx.coroutines.channels.Channel<String>(
            capacity = kotlinx.coroutines.channels.Channel.CONFLATED,
        )
        launch {
            for (id in pending) {
                delay(FALLBACK_POP_DELAY_MS)
                if (nav.currentBackStackEntry?.id == id) nav.popBackStack()
            }
        }
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val entry = nav.currentBackStackEntry
            // 主页右滑进播放页（M3eHome 自己处理），登录/向导等栈底页也不接管
            if (entry == null || entry.destination.route == Routes.HOME) return@awaitEachGesture
            var dx = 0f
            var dy = 0f
            var horizontal = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.positionChange()
                dx += delta.x
                dy += delta.y
                if (!horizontal) {
                    if (dx > viewConfiguration.touchSlop && abs(dx) > abs(dy) * 1.5f) horizontal = true
                    else if (dx < 0f || abs(dy) > abs(dx) * 1.5f) break
                }
            }
            if (!horizontal || dx < 56.dp.toPx()) return@awaitEachGesture
            pending.trySend(entry.id)
        }
      }
    }
}
