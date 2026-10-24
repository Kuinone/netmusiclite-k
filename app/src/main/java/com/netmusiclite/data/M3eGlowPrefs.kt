package com.netmusiclite.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

/**
 * m3e「纯黑底色」的中心径向柔光开关（★ 2026-10-18 用户口径
 * 「m3e 主题选择黑色底色的时候添加这个效果，加个开关吧默认打开」）。
 *
 * 只在「背景样式 = 纯黑」这一档有意义：取色 / 封面模糊两档身后本来就有一张图，不需要补光。
 * 默认 true —— 升级后立刻能看到效果；不喜欢的用户在主题设置里一键关掉即恢复纯黑。
 *
 * 性能备注（为什么默认敢开）：整个效果是一层 `Brush.radialGradient` 的全屏填充，
 * GPU 一次 shader 绘制，静止时零重绘；只有换歌时有一段 600ms 的颜色插值。
 */
object M3eGlowPrefs {
    private const val PREFS_NAME = "ncm_m3e_glow"
    private const val KEY_ENABLED = "enabled"

    private var prefs: SharedPreferences? = null
    private val enabledState = mutableStateOf(true)

    var enabled: Boolean
        get() = enabledState.value
        set(value) {
            enabledState.value = value
            prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
        }

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        enabledState.value = prefs?.getBoolean(KEY_ENABLED, true) ?: true
    }
}
