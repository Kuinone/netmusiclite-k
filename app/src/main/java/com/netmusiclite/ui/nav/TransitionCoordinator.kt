package com.netmusiclite.ui.nav
import com.netmusiclite.data.CrashLogger
import com.netmusiclite.ui.components.pressStretch

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder

/** How the destination should enter, based on the control that opened it. */
enum class NavMotionKind {
    Pill,
    Circle,
    Directional,

    /**
     * 滑动来源：由横向/纵向手势发起的导航（左划进播放页、左划进歌词页、下滑进评论页、
     * 栈底右滑回主页等）。
     *
     * ★ 2026-10-01 用户要求：「所有涉及点击的场景切换换成缩放动画，滑动切换不变」。
     *   缩放与滑动必须按**来源**分派，不能按枚举值的名字 —— 旧代码里 `Directional`
     *   同时被「点击」和「滑动」两拨调用方使用（HomeScreen 左划、PlayerScreen 左划/下滑
     *   都是 Directional），按名字改渲染层必然误伤。故把「滑动」独立成 Slide：
     *   只有 Slide 走滑动转场，其余（Pill / Circle / Directional）一律走统一缩放。
     */
    Slide,

    /**
     * 竖向滑动来源：下滑进评论页（2026-10-03 用户要求「进入评论区从上方往下滑入」）。
     * 与 [Slide] 同为滑动来源 —— 动画曲线/时长一个字不改（NavGlide / NAV_MS），
     * 只是把传送带从横向换成竖向：入页从屏幕上方往下滑入、旧页同向往下滑出；
     * 返回方向整条反向（评论页往上滑出、播放页从下方滑回）。
     */
    SlideV,
    ;

    /** 是否滑动来源 —— [Slide]/[SlideV] 保留「传送带」滑动转场，其余一律缩放。 */
    val isSwipe: Boolean get() = this == Slide || this == SlideV

    /** 是否竖向滑动 —— 渲染层据此选 slideIn/OutVertically 而非 Horizontally。 */
    val isVertical: Boolean get() = this == SlideV
}

/** Origin is stored as a fraction of the NavHost viewport, so it survives size and density changes. */
data class NavMotionSpec(
    val kind: NavMotionKind,
    val originX: Float = 0.5f,
    val originY: Float = 0.5f,
) {
    fun normalized(): NavMotionSpec = copy(
        originX = originX.normalizedFraction(),
        originY = originY.normalizedFraction(),
    )
}

/**
 * Tracks the current NavHost viewport and carries source metadata only for the synchronous
 * navigation started by that source click. Destination metadata is saved on its back-stack
 * entry, so pop transitions can reverse the same motion later.
 */
object TransitionCoordinator {
    private const val KIND_KEY = "wm_nav_motion_kind"
    private const val ORIGIN_X_KEY = "wm_nav_motion_origin_x"
    private const val ORIGIN_Y_KEY = "wm_nav_motion_origin_y"
    private const val GESTURE_POP_KEY = "wm_nav_gesture_pop"
    private const val GESTURE_EXIT_MS_KEY = "wm_nav_gesture_pop_exit_ms"

    /**
     * 触点缩放原点（2026-10-02 用户要求「所有缩放动画打开中心由使用场景点击位置而定」）：
     * NavHost 层有一个被动触点观察者（见 AppNav 的 pointerInput），把每次按下的位置
     * 归一化后记在这里；点击导航时 `navigateWithMotion` 优先取这份**新鲜**触点作为
     * 缩放原点 —— 一处采集覆盖全部点击入口，不用逐个调用点传位置。
     *
     * ★ 超窗回退：距按下超过 [TAP_FRESH_MS] 的旧触点视为与本次导航无关（登录扫码完成、
     *   登出后的 backToHome 等异步跳转），回退到 spec 自带原点（控件中心采集）或屏幕中心。
     */
    private const val TAP_FRESH_MS = 1000L
    private var tapX = 0.5f
    private var tapY = 0.5f
    private var tapAtMs = 0L

    private var hostCoordinates: LayoutCoordinates? = null
    private var armedSpec: NavMotionSpec? = null
    private var navController: NavHostController? = null

    /** 记录一次按下的归一化位置（由 NavHost 层触点观察者调用；仅观察，不消费事件）。 */
    fun noteTap(x: Float, y: Float) {
        tapX = x.normalizedFraction()
        tapY = y.normalizedFraction()
        tapAtMs = android.os.SystemClock.uptimeMillis()
    }

    /**
     * 松手时刷新新鲜度（位置不变，仍是按下点）：慢点击（按住超过 [TAP_FRESH_MS] 才松手）
     * 也按「本次点击」计算；异步跳转（扫码完成、登出回主页）距松手已很久，照旧超窗回退。
     */
    fun markTapFresh() {
        if (tapAtMs != 0L) tapAtMs = android.os.SystemClock.uptimeMillis()
    }

    /** 新鲜触点原点（超窗/未记录返回 null） */
    fun freshTapOrigin(): Pair<Float, Float>? {
        if (tapAtMs == 0L) return null
        if (android.os.SystemClock.uptimeMillis() - tapAtMs > TAP_FRESH_MS) return null
        return tapX to tapY
    }

    fun updateViewport(coordinates: LayoutCoordinates) {
        hostCoordinates = coordinates.takeIf { it.isAttached }
    }

    fun bind(controller: NavHostController) {
        navController = controller
    }

    fun unbind(controller: NavHostController) {
        if (navController === controller) {
            navController = null
            hostCoordinates = null
            armedSpec = null
        }
    }

    /** Builds a spec from the center of a source control in the current NavHost viewport. */
    fun specFromSource(kind: NavMotionKind, source: LayoutCoordinates?): NavMotionSpec {
        if (kind == NavMotionKind.Directional) return NavMotionSpec(NavMotionKind.Directional)
        val host = hostCoordinates
        if (source == null || !source.isAttached || host == null || !host.isAttached ||
            source.size.width <= 0 || source.size.height <= 0 ||
            host.size.width <= 0 || host.size.height <= 0
        ) {
            return NavMotionSpec(NavMotionKind.Directional)
        }

        return runCatching {
            val sourceCenter = source.localToRoot(
                androidx.compose.ui.geometry.Offset(source.size.width / 2f, source.size.height / 2f),
            )
            val hostOrigin = host.localToRoot(androidx.compose.ui.geometry.Offset.Zero)
            NavMotionSpec(
                kind = kind,
                originX = (sourceCenter.x - hostOrigin.x) / host.size.width,
                originY = (sourceCenter.y - hostOrigin.y) / host.size.height,
            ).normalized()
        }.getOrElse { NavMotionSpec(NavMotionKind.Directional) }
    }

    /** Arms an exact source spec while the click callback runs; a navigation consumes it once. */
    fun <T> withSource(kind: NavMotionKind, source: LayoutCoordinates?, action: () -> T): T {
        val previous = armedSpec
        armedSpec = specFromSource(kind, source)
        return try {
            action()
        } finally {
            // Restore an enclosing source scope after this click callback has completed.
            armedSpec = previous
        }
    }

    fun consumeArmedOrDirectional(): NavMotionSpec {
        val spec = armedSpec ?: return NavMotionSpec(NavMotionKind.Directional)
        armedSpec = null
        return spec.normalized()
    }

    fun write(entry: NavBackStackEntry, spec: NavMotionSpec) {
        val stable = spec.normalized()
        entry.savedStateHandle[KIND_KEY] = stable.kind.name
        entry.savedStateHandle[ORIGIN_X_KEY] = stable.originX
        entry.savedStateHandle[ORIGIN_Y_KEY] = stable.originY
        entry.savedStateHandle.remove<Boolean>(GESTURE_POP_KEY)
    }

    /**
     * 当前栈顶页的转场规格（含入场时记录的缩放原点）。
     *
     * ★ 2026-10-02：右滑返回的**跟手缩放/收场缩放**（SwipeBackFader 各使用点）用它做
     *   graphicsLayer 的 transformOrigin —— 退出才能「原路返回」（缩回当初点进来的位置），
     *   否则默认中心原点会让页面缩向屏幕正中，与入场的触点缩放不对称（用户实测反馈）。
     */
    fun currentSpec(): NavMotionSpec? =
        navController?.currentBackStackEntry?.let { read(it) }

    fun read(entry: NavBackStackEntry): NavMotionSpec {
        val kind = runCatching {
            NavMotionKind.valueOf(entry.savedStateHandle.get<String>(KIND_KEY) ?: "")
        }.getOrDefault(NavMotionKind.Directional)
        return NavMotionSpec(
            kind = kind,
            originX = entry.savedStateHandle.get<Float>(ORIGIN_X_KEY) ?: 0.5f,
            originY = entry.savedStateHandle.get<Float>(ORIGIN_Y_KEY) ?: 0.5f,
        ).normalized()
    }

    fun isGesturePop(entry: NavBackStackEntry): Boolean =
        entry.savedStateHandle.get<Boolean>(GESTURE_POP_KEY) == true

    /**
     * 手势返回旗标的一次性清理。
     *
     * 正常手势返回 pop 掉的页面会离开返回栈，旗标挂着也无妨；但**栈底右滑**走的是
     * 「前进导航到主页」，被滑出的页面（栈底）仍留在栈里 —— 旗标一直挂着，
     * 下次它自己被 pop 时会被误判成手势返回（NavHost 只做淡出、页面原地不动）。
     * 所以页面重新入场时要把旗标清掉。
     */
    fun clearGesturePop(entry: NavBackStackEntry) {
        entry.savedStateHandle.remove<Boolean>(GESTURE_POP_KEY)
        entry.savedStateHandle.remove<Int>(GESTURE_EXIT_MS_KEY)
    }

    /** 手势返回的滑出/入场时长（松手时按拖拽末速度折算；未武装时默认 220ms） */
    fun gesturePopExitMs(entry: NavBackStackEntry): Int =
        entry.savedStateHandle.get<Int>(GESTURE_EXIT_MS_KEY) ?: 220

    /**
     * Marks the page being popped as already following a custom finger-tracked swipe. NavHost
     * then avoids adding a second translation over that page's own SwipeBackFader animation.
     * exitDurationMs 同时落到该 back-stack entry 上，供 popEnter/popExit 转场读取对齐时长。
     */
    fun <T> withGesturePop(exitDurationMs: Int, action: () -> T): T {
        CrashLogger.step("gesture pop ${exitDurationMs}ms")
        val controller = navController
        val outgoing = controller?.currentBackStackEntry
        if (outgoing != null) {
            outgoing.savedStateHandle[GESTURE_POP_KEY] = true
            outgoing.savedStateHandle[GESTURE_EXIT_MS_KEY] = exitDurationMs
        }
        return try {
            action()
        } finally {
            if (outgoing != null && controller?.currentBackStackEntry === outgoing) {
                outgoing.savedStateHandle.remove<Boolean>(GESTURE_POP_KEY)
            }
        }
    }
}

/**
 * A click modifier for bespoke navigation controls. It preserves the normal click semantics
 * while exposing the control's measured center to the transition coordinator.
 */
fun Modifier.navMotionSource(
    kind: NavMotionKind,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = null,
    onNavigate: () -> Unit,
): Modifier = composed {
    val coordinates = remember { mutableStateOf<LayoutCoordinates?>(null) }
    val source = interactionSource ?: remember { MutableInteractionSource() }
    this
        .onGloballyPositioned { coordinates.value = it }
        .pressStretch(interactionSource = source)
        .clickable(interactionSource = source, indication = indication) {
            TransitionCoordinator.withSource(kind, coordinates.value, onNavigate)
        }
}

/** Navigates and persists the source motion on the newly-created destination entry. */
fun NavHostController.navigateWithMotion(
    route: String,
    spec: NavMotionSpec? = null,
    builder: NavOptionsBuilder.() -> Unit = {},
) {
    val base = (spec ?: TransitionCoordinator.consumeArmedOrDirectional()).normalized()
    // ★ 2026-10-02：缩放原点 = 使用场景点击位置（手指触点）。新鲜触点优先于显式 spec
    //   和控件中心采集；滑动来源不缩放（传送带），无需原点。超窗旧触点已在
    //   freshTapOrigin 内回退 null → 保留 base 自带原点（控件中心 / 屏幕正中）。
    val tap = if (base.kind.isSwipe) null else TransitionCoordinator.freshTapOrigin()
    val stableSpec = if (tap != null) base.copy(originX = tap.first, originY = tap.second) else base
    navigate(route, builder)
    currentBackStackEntry?.let { TransitionCoordinator.write(it, stableSpec) }
    CrashLogger.step("nav → $route (${stableSpec.kind.name})")
}

private fun Float.normalizedFraction(): Float =
    if (isFinite()) coerceIn(0f, 1f) else 0.5f
