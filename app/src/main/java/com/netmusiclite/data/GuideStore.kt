package com.netmusiclite.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 使用向导标记。
 *
 * 2026-10-02 新增：首次登录后弹一次「使用向导 + 官方QQ群 + 捐赠作者」，走完不再弹。
 * 2026-10-17 改为**按主题分别记录**：经典主题与 M3E 主题的手势指南内容不同，
 * 首次使用某个主题（新用户选完主题 / 老用户第一次切换过去）都要先看对应指南。
 */
object GuideStore {
    private lateinit var prefs: SharedPreferences

    /** 旧标记：任意主题的完整向导走完过（兼容旧流程与老用户判定） */
    var done = false
        private set

    private var doneClassic = false
    private var doneM3e = false

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("ncm_guide", Context.MODE_PRIVATE)
        done = prefs.getBoolean("done", false)
        // 旧版本只有 done：视为「经典主题」看过；M3E 指南是新增内容，老用户仍需看
        doneClassic = prefs.getBoolean("done_classic", done)
        doneM3e = prefs.getBoolean("done_m3e", false)
        m3eAnnounced = prefs.getBoolean("m3e_announced", false)
    }

    /** 某主题的指南是否看过（style = "classic" / "m3e"） */
    fun seen(style: String): Boolean = if (style == "m3e") doneM3e else doneClassic

    /**
     * 向导/快捷指南走完：标记该主题的指南已看。
     * 完整向导仍要置 done=true（登录分流依赖它）；默认 style 兼容旧调用。
     */
    fun markDone(style: String = "classic") {
        if (style == "m3e") doneM3e = true else doneClassic = true
        done = true
        if (::prefs.isInitialized) {
            prefs.edit()
                .putBoolean("done", true)
                .putBoolean("done_classic", doneClassic)
                .putBoolean("done_m3e", doneM3e)
                .apply()
        }
    }

    // ================== 仅 1.45 版（本次构建）生效的 M3E 新主题公告 ==================
    // ★ 这是一次性运营公告，不属于常驻功能：
    //   · 只在本次发版构建里显示一次（用户点掉后永久不再出现）；
    //   · 下一次发版构建时，把本段（M3E_ANNOUNCE_ENABLED / m3eAnnounced /
    //     shouldShowM3eAnnounce / markM3eAnnounced）整段删掉即可，调用点会自然编译失败，
    //     正好作为「删干净」的提醒。
    private const val M3E_ANNOUNCE_ENABLED = true
    private var m3eAnnounced = false

    fun shouldShowM3eAnnounce(): Boolean = M3E_ANNOUNCE_ENABLED && !m3eAnnounced

    fun markM3eAnnounced() {
        m3eAnnounced = true
        if (::prefs.isInitialized) prefs.edit().putBoolean("m3e_announced", true).apply()
    }
}
