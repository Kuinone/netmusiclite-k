package com.netmusiclite.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 高级版公告问卷的「已确认」标记（2026-10-04 新增）。
 *
 * 用户口径：「在当前版本的安装包首次启动时弹出问卷（如果之前没有登录过那就在向导末尾出现）」。
 * 所以这个页面有**两条触发路径**，但只认同一个 done 标记：
 *
 *  ① 老用户（升级上来的、已登录）—— 首次启动直接弹（AppNav 的 startDestination 分流）；
 *  ② 新用户（没登录过）—— 先走完向导，在**向导末尾**（捐赠页之后）出现。
 *
 * 与 [GuideStore] 同款实现：SharedPreferences 落盘 + 内存标记，确认一次后永不重复。
 * sp 文件名独立（ncm_survey），与向导的 ncm_guide 互不影响 —— 向导看过的人仍然要看这份公告。
 */
object SurveyStore {
    private lateinit var sp: SharedPreferences

    var done = false
        private set

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("ncm_survey", Context.MODE_PRIVATE)
        done = sp.getBoolean("done", false)
    }

    fun markDone() {
        if (done) return
        sp.edit().putBoolean("done", true).apply()
        done = true
    }
}
