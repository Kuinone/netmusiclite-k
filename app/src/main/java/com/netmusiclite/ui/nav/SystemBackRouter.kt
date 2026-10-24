package com.netmusiclite.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

/**
 * 系统返回统一接管（2026-10-03，华为 Watch 3 专项）。
 *
 * 背景：华为手表把「右滑」做成系统级返回手势 —— 触摸被系统吃掉，应用内 SwipeBackBox
 * 的跟手动画收不到事件（用户报「右滑无法控制应用界面」），松手后系统向 Activity 派发
 * back，默认行为是 finish 整个应用（「直接退出」）。MainActivity 在 setContent 之前
 * 最早注册 back 回调接住它，按应用内右滑的语义分发到这里（规则见 AppNav.onSystemBack）：
 *
 *  · **展示中的浮层**（音量页/进度条长按/选人/各类弹窗）→ 先关浮层，不动导航（见 [OverlayBackHandler]）；
 *  · 有上一页 → NavHost popBackStack（应用内回退，不再退出应用）；
 *  · 栈底播放页 → 镜像内部右滑的「前进导航到主页（装成返回）」（PlayerScreen 注册整段动作）；
 *  · 主页 → 播内部右滑退出的收小+淡出（SwipeBackBox 注册），播完 moveTaskToBack；
 *  · 其余栈底页（登录/同意/向导）→ 消费但不动作 —— 屏蔽系统「直接退出」。
 *
 * 其他机型（ColorOS 等）系统不派发 back 时本对象全程只注册不发火，行为零影响；
 * 页面内 Compose BackHandler（弹窗等）注册在 MainActivity 回调之后、优先级更高，不受影响。
 *
 * ★ 2026-10-07 小米手表「音量页右滑直接退回主页」修复引入浮层登记：
 *   音量页是挂在导航图**之上**的全屏浮层，不在导航栈里 —— 系统 back 派进来时按
 *   「有上一页就 popBackStack」处理，于是浮层还盖在屏幕上、底下已经悄悄退到了主页
 *   （浮层 1.3s 后淡出，用户看到的就是「直接退回主页」）。登记过的浮层先于导航消费
 *   back，与用户预期「右滑 = 关掉眼前这层」对齐。
 */
object SystemBackRouter {
    /** AppNav 注册：导航侧系统返回语义；返回 true = 已消费（false 落到主页退出动画）。 */
    var navBack: (() -> Boolean)? = null

    /** 栈底播放页注册：整段「右滑回主页」动作（含 fader 收场与转场旗标）。 */
    var playerRootExit: (() -> Unit)? = null

    /** 主页 SwipeBackBox(exitAppOnBack) 注册：播退出动画并自行 moveTaskToBack。 */
    var homeExit: (() -> Unit)? = null

    /**
     * 展示中浮层的 back 处理器，**后注册者优先**（越晚登记 = 盖得越晚 = 越靠上）。
     * 处理器返回 true = 已消费，dispatch 立即结束、不再走导航。
     */
    private val overlays = mutableListOf<() -> Boolean>()

    /** 登记浮层（返回注销函数）；只应在浮层可见期间持有登记。 */
    fun registerOverlayBack(handler: () -> Boolean): () -> Unit {
        overlays.add(handler)
        return { overlays.remove(handler) }
    }

    fun dispatch() {
        com.netmusiclite.data.CrashLogger.step("system back")
        // 副本遍历：处理器关浮层时若注销了登记，不会动到正在遍历的列表
        overlays.toList().asReversed().forEach { if (it()) return }
        if (navBack?.invoke() == true) return
        homeExit?.invoke()
    }
}

/**
 * 浮层系统返回接管：可见期间系统 back / 右滑先关本浮层。
 *
 * 用法：在浮层顶层 Composable 里调用 `OverlayBackHandler(enabled = XxxOverlay.visible) { XxxOverlay.hide() }`。
 * [enabled] 为 false 时（或浮层离开组合时）自动注销，不泄漏登记。
 */
@Composable
fun OverlayBackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val current = rememberUpdatedState(onBack)
    // Wear's nav host also registers with the dispatcher; a visible overlay must win there too.
    androidx.activity.compose.BackHandler(enabled = enabled) { current.value() }
    DisposableEffect(enabled) {
        val unregister = if (enabled) SystemBackRouter.registerOverlayBack { current.value(); true } else null
        onDispose { unregister?.invoke() }
    }
}
