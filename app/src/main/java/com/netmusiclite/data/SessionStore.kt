package com.netmusiclite.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** 登录态 / 多账号本地存储 */
object SessionStore {
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("ncm_session", Context.MODE_PRIVATE)
    }

    private val deviceIdLock = Any()

    /**
     * 稳定设备身份（2026-10-17 风控加固）。
     *
     * 旧实现每次进程启动都用 `Build.FINGERPRINT` 现算 deviceId：系统 OTA / 重装后
     * FINGERPRINT 变化 ⇒ deviceId 跟着变，服务端看到「同一账号又冒出一台新设备」，
     * 收藏这类写操作会进异步审计（当场 200、隔天回滚）。
     * 现改为「首次算出后落盘、之后永远复用」，且首次值仍由调用方传旧算法
     * ⇒ 已装用户第一次升级不会换设备号（零漂移）。
     */
    fun stableDeviceId(key: String, bootstrap: () -> String): String = synchronized(deviceIdLock) {
        // 极端时序：Store 还没 init 就被网络层用到（理论不发生）→ 不落盘，保持旧行为
        if (!::prefs.isInitialized) return bootstrap()
        prefs.getString(key, null)?.takeIf { it.isNotEmpty() }?.let { return it }
        val v = bootstrap()
        prefs.edit().putString(key, v).apply()
        v
    }

    /**
     * ★ 2026-10-06 访客模式（用户口径）：登录页点「不登录进入音乐」后的精简使用态 ——
     *  主页只显示 听本地音乐 / 听歌排行 / 最近播放 / 设置 四卡、不显示头像徽章；
     *  设置内添加账号（扫码成功）后自动回到正常模式（见 addAccount / applyAccount）。
     *  与 musicU 互相独立：登录态恒优先（启动分流先看 loggedIn 再看 guestMode）。
     */
    var guestMode: Boolean
        get() = prefs.getBoolean("guest_mode", false)
        set(v) = prefs.edit().putBoolean("guest_mode", v).apply()

    /**
     * 登录态版本号（Compose 状态）：登录态可能变化的事件（添加账号 / 应用账号 / 退出）
     * 都在这里自增。SessionStore 其余字段是普通 SharedPreferences getter，组合期读取
     * **不会**注册订阅 —— 主页需要「设置内添加账号后立刻从访客列表变回正常列表」，
     * 就靠读本字段建立重组依赖（HomeScreen 访客/正常两套列表由此切换）。
     */
    var epoch: Int by mutableStateOf(0)
        private set

    var musicU: String?
        get() = prefs.getString("music_u", null)
        set(v) = prefs.edit().putString("music_u", v).apply()

    /**
     * 登录回包 Set-Cookie 里的 __csrf。
     * 官方客户端写操作（点赞 / 发评论）会带 `checkToken`（dex: `Lqz0/b;->G0()` → security SDK
     * 的 securityGetToken），web/eapi 线对应的就是 __csrf，故登录时把它一并落盘。
     */
    var csrf: String?
        get() = prefs.getString("csrf", null)
        set(v) = prefs.edit().putString("csrf", v).apply()

    var uid: Long
        get() = prefs.getLong("uid", 0L)
        set(v) = prefs.edit().putLong("uid", v).apply()

    var nickname: String
        get() = prefs.getString("nickname", "") ?: ""
        set(v) = prefs.edit().putString("nickname", v).apply()

    var avatarUrl: String
        get() = prefs.getString("avatar", "") ?: ""
        set(v) = prefs.edit().putString("avatar", v).apply()

    var vipType: Int
        get() = prefs.getInt("vip_type", 0)
        set(v) = prefs.edit().putInt("vip_type", v).apply()

    val loggedIn: Boolean get() = !musicU.isNullOrEmpty()

    /** 已添加的账号（多账号切换用，首项为当前） */
    fun accounts(): List<AccountEntry> {
        val s = prefs.getString("accounts", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AccountEntry(
                    o.getString("u"), o.optLong("id"), o.optString("n"), o.optString("a"),
                    o.optString("c").takeIf { it.isNotEmpty() },
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveAccounts(list: List<AccountEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("u", it.musicU).put("id", it.uid).put("n", it.nickname)
                    .put("a", it.avatarUrl ?: "").put("c", it.csrf ?: "")
            )
        }
        prefs.edit().putString("accounts", arr.toString()).apply()
    }

    fun addAccount(e: AccountEntry) {
        val list = accounts().filter { it.uid != e.uid }.toMutableList()
        list.add(0, e)
        saveAccounts(list)
        // ★ 2026-10-06 用户口径「在设置内添加账号后进入正常模式」：任何成功登录都退出访客态
        guestMode = false
        epoch++
    }

    fun applyAccount(e: AccountEntry) {
        musicU = e.musicU; uid = e.uid; nickname = e.nickname; avatarUrl = e.avatarUrl ?: ""
        csrf = e.csrf
        val rest = accounts().filter { it.uid != e.uid }
        val merged = AccountEntry(e.musicU, e.uid, e.nickname, e.avatarUrl, e.csrf)
        saveAccounts(listOf(merged) + rest)
        // 已有账号切换同样视为回到正常模式（登录态优先于访客态）
        guestMode = false
        epoch++
    }

    fun logoutCurrent() {
        musicU = null; uid = 0; nickname = ""; avatarUrl = ""; vipType = 0; csrf = null
        // guestMode 不动：退出登录后落回登录页，用户仍可选「不登录进入音乐」
        epoch++
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
