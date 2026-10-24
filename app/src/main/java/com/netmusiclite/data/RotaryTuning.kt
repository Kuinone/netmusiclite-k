package com.netmusiclite.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 表冠方向手动适配（2026-10-04 用户口径「设置里加一栏表冠适配：音量页一个选项、列表页一个选项，
 * 选项两个一个正一个反」）。
 *
 * ── 为什么需要用户自己选 ───────────────────────────────────────────────
 * 各厂商 ROM 把表冠上报成**不同通道、不同符号**，实测已经出现三种情况：
 *  · OPPO（OWW261 实测）：表冠设备 `oplus_crown` 声明 SOURCE_MOUSE(0x2002)，
 *    根本不是 ROTARY_ENCODER，走的是 Compose 滚轮通道；
 *  · 华为（用户真机实测）：ACTION_SCROLL + SOURCE_ROTARY_ENCODER，且**原始符号与 Wear 标准相反**
 *    （顺时针为负）。MainActivity 已按厂商做过一次归一化取反，但那次取反**只在事件冒泡到
 *    Activity 兜底时才生效** —— 若事件被 Compose 层消费，取反压根不执行，方向就是反的；
 *  · 三星等魔改 Wear OS 同理，且无法穷举。
 * 与其继续猜厂商/通道，不如给用户两个开关，选完立即生效、且落盘持久。
 *
 * ── 应用点（见 ui/components/Rotary.kt）─────────────────────────────────
 *  · 列表页 = `rotaryList` 全部 + `rotaryCustom(scope = List)` —— **除音量外的所有界面**
 *    （各类歌曲/歌单列表、主页卡片、向导翻卡…）。
 *    用户口径 2026-10-04：「列表的反向是除了音量之外所有界面都生效」。
 *  · 音量页 = `rotaryCustom(scope = Volume)` —— 只有播放页转冠调音量这一处。
 * 符号在**所有输入通道之后**相乘（Compose 的 rotary/滚轮两条通道 + Activity 兜底转发通道），
 * 所以无论这台表的事件走哪条路都覆盖得到。
 *
 * 默认两个都「正」= 与改动前行为完全一致（存量用户零变化）。
 */
object RotaryTuning {
    private const val SP = "ncm_rotary_tuning"
    private const val K_LIST = "list_reversed"
    private const val K_VOLUME = "volume_reversed"

    private var sp: SharedPreferences? = null

    /** 列表页是否反向 */
    var listReversed: Boolean by mutableStateOf(false)
        private set

    /** 音量页是否反向 */
    var volumeReversed: Boolean by mutableStateOf(false)
        private set

    /** 列表页符号（作用域 = 除音量外所有界面）：+1 = 正，-1 = 反。Rotary.kt 直接相乘 */
    val listSign: Float get() = if (listReversed) -1f else 1f

    /** 音量页符号：+1 = 正，-1 = 反 */
    val volumeSign: Float get() = if (volumeReversed) -1f else 1f

    fun init(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)
        sp = p
        listReversed = p.getBoolean(K_LIST, false)
        volumeReversed = p.getBoolean(K_VOLUME, false)
    }

    // ⚠ 函数名不能叫 setListReversed/setVolumeReversed —— 会与上面 var 的 JVM setter
    //   撞签名（Platform declaration clash），故用 apply* 前缀。
    fun applyListReversed(v: Boolean) {
        listReversed = v
        sp?.edit()?.putBoolean(K_LIST, v)?.apply()
    }

    fun applyVolumeReversed(v: Boolean) {
        volumeReversed = v
        sp?.edit()?.putBoolean(K_VOLUME, v)?.apply()
    }

    /** 设置页副标题：当前两个场景的方向 */
    fun label(): String =
        "音量页${if (volumeReversed) "反" else "正"} · 列表页${if (listReversed) "反" else "正"}"
}
