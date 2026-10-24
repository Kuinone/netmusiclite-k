package com.netmusiclite.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 私信会话「真实最后消息时间」补偿（2026-10-24）。
 *
 * 已读上报 `msg/private/service/lastmsg/update` 会把服务端 `lastMsgTime` 改写成**当前时间**
 * （真机日志：1792801570597 → 1792802678845），于是刚看过的会话跳到列表第一。
 * 这里按 `lastMsg.msgId` 记下每条会话真实的最新消息时间：
 *  - msgId 相同 = 只是已读上报刷了时间 → 返回记录的旧时间，排序不变；
 *  - msgId 变了 = 真来了新消息 → 用服务端时间（此时还没上报，时间是消息真实发送时间）。
 * 打开聊天页拿到历史后先 [record] 再上报已读，避免「看到新消息 → 上报 → 时间被污染」。
 */
object MsgOrderStore {
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("ncm_msg_order", Context.MODE_PRIVATE)
    }

    fun record(uid: Long, msgId: Long, timeMs: Long) {
        if (uid <= 0L || msgId <= 0L || timeMs <= 0L) return
        prefs?.edit()?.putString("u$uid", "$msgId:$timeMs")?.apply()
    }

    /** 修正服务端可能被已读上报污染的最后消息时间 */
    fun realTime(uid: Long, lastMsgId: Long, serverTimeMs: Long): Long {
        if (uid <= 0L || serverTimeMs <= 0L) return serverTimeMs
        if (lastMsgId > 0L) {
            val parts = prefs?.getString("u$uid", null)?.split(':')
            val savedId = parts?.getOrNull(0)?.toLongOrNull() ?: 0L
            val savedTime = parts?.getOrNull(1)?.toLongOrNull() ?: 0L
            if (savedId == lastMsgId && savedTime > 0L) return savedTime
            record(uid, lastMsgId, serverTimeMs)
        }
        return serverTimeMs
    }
}
