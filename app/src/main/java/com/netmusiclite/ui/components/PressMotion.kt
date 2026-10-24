package com.netmusiclite.ui.components

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring

/**
 * 全局按压反馈参数（2026-10-01 真机反解轻舟后统一）。
 *
 * ## 取证
 * 手表 OWW261 录轻舟（`com.xiyan.lan_transfer`）设置页，逐帧反解「按下一行不放」与
 * 「快速点一下松开」两组样本。缩放用**梯度幅值图**做归一化互相关 —— 轻舟按下时整行会
 * 高亮（实测亮度 ×2.5），直接比对灰度会被高亮带偏（边缘法量到 0.937、灰度 NCC 只有
 * 0.6 且跳变），而 ∇(k·I + c) = k·∇I，梯度图对亮度变化免疫，NCC 稳在 0.92。
 *
 * 实测（「保存设备名」按钮，NCC 0.92）：
 * ```
 *   按下  1.0000 → 0.9605   187 ms 后到位（慢速下压，肉眼可见整个过程）
 *   保持  0.9605            113 ms
 *   松开  0.9605 → 1.0000   150 ms 单调回位（轻舟无过冲）
 * ```
 * 缩放原点在**行自身中心**（几何量测：行的上边缘下移 2.88px、下边缘上移 2.83px，
 * 对称内缩，中心零漂移），等比缩放（水平 0.938 / 垂直 0.936）。
 *
 * ## 与轻舟的有意差异
 * 1. **幅度**：轻舟 0.96，原取 0.93；现在按用户要求将行程增强 20%：
 *    [SCALE] = 1 - 0.07 × 1.2 = 0.916。横展/竖压同步由 4% 增至 4.8%。
 * 2. **松开过冲**：轻舟松开是单调回位、零过冲；这里用低阻尼弹簧做出**可见回弹**——
 *    用户明确要求「点击回弹」。按 [UP] 的阻尼比，行程 8.4% 会过冲到约 1.029。
 *
 * ## 为什么按下/松开要用两条不同的曲线
 * 旧实现按下和松开共用 `spring(dampingRatio = 0.55, stiffness = 900)`：ω = √900 = 30 rad/s，
 * 到位只要 ~52ms —— 比一帧多一点点，用户根本看不到「沉下去」的过程，只看到「闪一下」。
 * 轻舟按下用 187ms，过程清晰可辨。所以按下改用 [DOWN]（ω ≈ 20.5，约 200ms 到位），
 * 松开才用低阻尼的 [UP]（ω ≈ 23.7，约 200ms 收尾并过冲）。
 */
internal object PressMotion {

    /** 旧 7% 行程增强 20%，scalar 与挤压变形共用，不能再重复乘增益。 */
    const val SCALE = 0.916f

    /**
     * 按下：阻尼比 0.72（近临界阻尼，几乎无过冲）+ ω≈20.5 rad/s —— 约 200ms 匀速沉下去。
     * 阻尼比不做 1.0 是留一点点活气，避免像机械滑块。
     */
    val DOWN: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.72f, stiffness = 420f)

    /**
     * 松开：阻尼比 0.32 → 过冲 exp(-πζ/√(1-ζ²)) ≈ 34.6% 行程，
     * 8.4% 的行程对应峰值 1.029，肉眼可辨的回弹；随后两次小余振收干净。
     */
    val UP: FiniteAnimationSpec<Float> =
        spring(dampingRatio = 0.32f, stiffness = 560f)

    /** 按状态取曲线：按住用 [DOWN]，松开用 [UP]。 */
    fun spec(pressed: Boolean): FiniteAnimationSpec<Float> = if (pressed) DOWN else UP

    /** Same mapping as before: 8.4% scalar travel now produces 4.8% squash/stretch. */
    fun stretchX(scale: Float): Float = 1f + (1f - scale) * (4f / 7f)
    fun stretchY(scale: Float): Float = 1f - (1f - scale) * (4f / 7f)

    /** Legacy scalar retained for callers and custom cover-button gesture handling. */
    fun targetScale(pressed: Boolean): Float = if (pressed) SCALE else 1f
}
