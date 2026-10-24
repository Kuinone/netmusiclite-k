package com.netmusiclite.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

/**
 * 播放页「转表冠调音量」开关（★ 2026-10-18 用户口径：「在设置里歌曲响度对齐下方加一个
 * 关闭播放页转动表冠调节音量的选项」）。
 *
 * 语义是**关闭**（[disabled] = true → 播放页转表冠不再动音量）：默认 false，
 * 存量用户的表冠行为一个字节不变；只有主动关掉的人才失去这条快捷调节。
 *
 * 关闭后表冠事件仍然被播放页消费（只是不调音量），不会冒泡到系统或误触其它手势 ——
 * 见 PlayerScreen.onCrownForVolume / M3eHorologistPlayer 的早退分支。
 */
object CrownVolumePrefs {
    private const val PREFS_NAME = "ncm_crown_volume"
    private const val KEY_DISABLED = "disabled"

    private var prefs: SharedPreferences? = null
    private val disabledState = mutableStateOf(false)

    /** true = 播放页转表冠不再调音量 */
    var disabled: Boolean
        get() = disabledState.value
        set(value) {
            disabledState.value = value
            prefs?.edit()?.putBoolean(KEY_DISABLED, value)?.apply()
        }

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        disabledState.value = prefs?.getBoolean(KEY_DISABLED, false) ?: false
    }
}
