package com.netmusiclite.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

/**
 * 「手势操控（实验性）」偏好：总开关 + 识别灵敏度。
 *
 * 默认关闭、默认「灵敏」。关闭时不注册任何传感器监听 —— 零采样、零唤醒；
 * 打开后也只在本应用前台时识别（生命周期见 [GestureControl]）。
 */
object GesturePrefs {
    const val SENSITIVITY_STANDARD = 0
    const val SENSITIVITY_HIGH = 1
    const val SENSITIVITY_EXTREME = 2

    private const val PREFS_NAME = "ncm_gesture"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_DONATION_PROMPT_SHOWN = "donation_prompt_shown"

    private var prefs: SharedPreferences? = null
    private val enabledState = mutableStateOf(false)
    private val sensitivityState = mutableStateOf(SENSITIVITY_HIGH)
    private val donationPromptShownState = mutableStateOf(false)

    val enabled: Boolean get() = enabledState.value
    val sensitivity: Int get() = sensitivityState.value
    val donationPromptShown: Boolean get() = donationPromptShownState.value

    fun sensitivityLabel(level: Int = sensitivity): String = when (level) {
        SENSITIVITY_STANDARD -> "标准"
        SENSITIVITY_EXTREME -> "很灵敏"
        else -> "灵敏"
    }

    fun sensitivityHint(level: Int = sensitivity): String = when (level) {
        SENSITIVITY_STANDARD -> "误触更少，动作要更清晰"
        SENSITIVITY_EXTREME -> "最容易触发，日常动作也可能误判"
        else -> "推荐 · 兼顾识别率与误触"
    }

    /** 只由 [GestureControl] 调用：切换开关要同步管理传感器生命周期。 */
    internal fun setEnabled(value: Boolean) {
        enabledState.value = value
        prefs?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
    }

    /** 只由 [GestureControl] 调用：灵敏度变化后要重建识别器。 */
    internal fun setSensitivity(value: Int) {
        val level = value.coerceIn(SENSITIVITY_STANDARD, SENSITIVITY_EXTREME)
        sensitivityState.value = level
        prefs?.edit()?.putInt(KEY_SENSITIVITY, level)?.apply()
    }

    fun markDonationPromptShown() {
        if (donationPromptShownState.value) return
        donationPromptShownState.value = true
        prefs?.edit()?.putBoolean(KEY_DONATION_PROMPT_SHOWN, true)?.apply()
    }

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        enabledState.value = prefs?.getBoolean(KEY_ENABLED, false) ?: false
        sensitivityState.value = (prefs?.getInt(KEY_SENSITIVITY, SENSITIVITY_HIGH)
            ?: SENSITIVITY_HIGH).coerceIn(SENSITIVITY_STANDARD, SENSITIVITY_EXTREME)
        donationPromptShownState.value = prefs?.getBoolean(KEY_DONATION_PROMPT_SHOWN, false) ?: false
    }
}
