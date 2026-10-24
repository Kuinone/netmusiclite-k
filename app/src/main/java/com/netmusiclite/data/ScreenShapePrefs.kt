package com.netmusiclite.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 表盘形状偏好（2026-10-03 新增：向导第一页「方表 / 圆表」选择）。
 *
 * 背景：整套 UI 是按「圆表」标定的（根布局 clip(CircleShape) + 通宽控件按弧边内缩）。
 * 原实现只靠系统的 SCREENROUND 配置位分流（见 ui/components/Common.kt 的 isRoundScreen），
 * 但不少机型 —— 尤其是国产方表 ROM、鸿蒙手表、部分魔改 Wear —— 这个位与真实表壳不符
 * （方表被报成圆屏 → 四角被裁空；圆表被报成方屏 → 弧边内容不再内缩、两端被切）。
 * 用户口径「在首次启动的时候向导第一个页面改成方表圆表选择」即为此兜底：
 * 由用户**目视指定**一次，之后一切形状判定以它为准。
 *
 * 三态（[MODE_AUTO] / [MODE_ROUND] / [MODE_SQUARE]）：
 *  · AUTO  = 没选过 —— 继续信任系统 SCREENROUND 位（存量用户升级后行为零变化）；
 *  · ROUND = 用户明确选了圆表 —— 强制圆形裁切 + 弧边内缩；
 *  · SQUARE= 用户明确选了方表 —— 不裁切、不内缩，内容铺满整屏四角。
 *
 * 与 [AppearancePrefs] 同款实现：SharedPreferences 落盘 + Compose mutableStateOf 内存态，
 * 所以用户在选择页点一下，**整个 UI 树立刻按新形状重组**（无需重启）。
 */
object ScreenShapePrefs {
    /** 未指定：沿用系统 SCREENROUND 配置位 */
    const val MODE_AUTO = 0
    /** 用户指定为圆表 */
    const val MODE_ROUND = 1
    /** 用户指定为方表 */
    const val MODE_SQUARE = 2

    private lateinit var sp: SharedPreferences

    private var _mode by mutableStateOf(MODE_AUTO)

    /** 当前形状模式（读组合状态 → 选择后整树重组） */
    val mode: Int
        get() = _mode

    /**
     * 用户是否已经做过形状选择。
     * 首次启动（false）时向导第一页必须是形状选择页 —— 见 AppNav 的启动分流。
     */
    val chosen: Boolean
        get() = _mode != MODE_AUTO

    /**
     * 用户**显式**指定的形状：true=圆表 / false=方表 / null=未指定（交给系统位）。
     * 这是 ui.components.isRoundScreen() 的唯一读取口。
     */
    val forcedRound: Boolean?
        get() = when (_mode) {
            MODE_ROUND -> true
            MODE_SQUARE -> false
            else -> null
        }

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("ncm_screen_shape", Context.MODE_PRIVATE)
        _mode = sp.getInt("mode", MODE_AUTO).coerceIn(MODE_AUTO, MODE_SQUARE)
    }

    fun setMode(m: Int) {
        val c = m.coerceIn(MODE_AUTO, MODE_SQUARE)
        _mode = c
        if (::sp.isInitialized) sp.edit().putInt("mode", c).apply()
    }

    /** 当前形状的中文名（设置页副标题用） */
    fun label(): String = when (_mode) {
        MODE_ROUND -> "圆形表盘"
        MODE_SQUARE -> "方形表盘"
        else -> "自动判定"
    }
}
