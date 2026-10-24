package com.netmusiclite.data

import android.os.Build

/**
 * 设备分流单点（2026-10-03 华为 Watch 3 适配 → 同日扩展三星魔改 Wear / Wear OS 7 / 鸿蒙 4）。
 *
 * 「原 ColorOS 适配 + 新机型适配，2 者不混淆」的分流原则：
 *  1) 能探测的一律按**能力**走，不认厂商 —— 震动通道（NcmHaptics：OPPO linearmotor →
 *     华为 VibratorEx → 系统 TICK 依次降级）、系统返回（SystemBackRouter：系统派发
 *     back 才接管）都在各自模块内自洽，两条路径天然互不干扰；
 *  2) 只有「探测不到」的选择 —— 播放页音量走**系统面板**还是**应用内自绘音量条** ——
 *     才需要机型判定，且只在这里判一次，业务侧不再散落厂商 if。
 *
 * ★ 2026-10-03 音量分流口径（用户口径「仅新机型启用，ColorOS 保持系统面板」）：
 *   判据取**反面** —— 只认「ColorOS/OPPO 系」走系统面板（这是真机验证过可用的那条），
 *   其余一律走应用内自绘音量条。理由：新机型的集合无法穷举（三星魔改 Wear OS 2 /
 *   Wear OS 7 / 鸿蒙 4 都在报「音钮点了没反应、转冠不调音量」），而自绘条在任何 Android
 *   上都能工作；反过来用白名单会让未识别机型继续坏着。
 *   注意：原实现给非华为机型的 `adjustStreamVolume(ADJUST_SAME, FLAG_SHOW_UI)` 本身
 *   **不改变音量**、只求系统弹面板，面板不存在时等于什么都没发生 —— 这正是那批机型
 *   「点了没反应」的根因（详见 InAppVolume 头注）。
 *
 * 华为判据：厂商串（ro.product.manufacturer/brand）+ 华为 ROM 私有 VibratorEx 类是否
 * 存在（与华为 uikit HwVibrateUtil 同款能力判据），任一命中即视为华为手表。鸿蒙手表
 * 厂商串同为 HUAWEI，天然被本条覆盖。
 * ColorOS 判据：厂商串 + OPPO ROM 私有线性马达类是否可加载（与 NcmHaptics 同款能力判据）。
 * 判错代价可控：自绘音量条在任何 Android 上都能工作，只是换了套 UI。
 */
object DeviceCompat {
    private val vendor = (Build.MANUFACTURER + "/" + Build.BRAND).lowercase()

    /** 华为 Watch 3 / HarmonyOS 系手表 */
    val isHuaweiWatch: Boolean by lazy {
        vendor.contains("huawei") || runCatching {
            Class.forName("com.huawei.android.os.VibratorEx")
        }.isSuccess
    }

    /**
     * ColorOS / OPPO 系手表：**唯一**继续走系统音量面板的机型族
     * （其系统面板 + 面板接管表冠的链路已有真机验证，本次一个字不动）。
     */
    val isColorOsWatch: Boolean by lazy {
        vendor.contains("oppo") || vendor.contains("oneplus") || vendor.contains("realme") ||
            runCatching {
                Class.forName("android.os.linearmotorvibrator.LinearmotorVibrator")
            }.isSuccess
    }

    /**
     * 音量是否走应用内自绘音量条（`InAppVolume`）。
     * = 非 ColorOS（华为 / 鸿蒙 / 三星魔改 Wear / 其它 Wear OS）。
     * 生效点：播放页转冠 + 播放页音量钮 + 一起听房音量钮。
     */
    val useInAppVolume: Boolean get() = !isColorOsWatch

    /**
     * m3e 主题的表冠是否走 Wear 原生 `rotaryScrollable`（2026-10-17 第四轮用户口径）：
     * 「m3e 主题下的表冠控制只在检测到谷歌/三星表时用原生 material 组件的控制，
     *   其他设备（OPPO/华为等）复用默认主题的表冠控制，material 组件只起显示进度的作用」。
     *
     * 判据：厂商串 [vendor] 含 `google` / `samsung` —— 只有这两家表的「表冠 → 焦点节点
     * onRotaryScrollEvent」标准链路是真机验证可用的（Wear 官方 rotaryScrollable 依赖它）。
     * 其他厂商（OPPO ColorOS / 华为鸿蒙等）要么事件不按标准旋入投递、要么焦点链无人消费
     * （见 Rotary.kt 的 RotaryFallback 头注），只能复用默认主题那套「平滑管道 + Activity
     * 兜底」来**真正驱动滚动**；Material 组件（TransformingLazyColumn 形变、主页弧形
     * 进度条）只读真实 scroll state 做外观显示。
     *
     * 判错代价：把原生路径给不支持的机型 = 表冠无响应/不跟手；把自定义管道给谷歌三星 =
     * 仍能用（管道只是多一层平滑），只是没有原生速度曲线。所以白名单收得越窄越安全。
     */
    val useNativeWearRotary: Boolean by lazy {
        vendor.contains("google") || vendor.contains("samsung")
    }

    /**
     * 三星 One UI Watch：m3e 子页右滑需要应用内兜底（2026-10-24）。
     * 见 [com.netmusiclite.ui.m3e.m3eSwipeBackFallback]：三星上 Wear 原生
     * SwipeDismissableNavHost 的右滑会被列表先消费，默认主题的 Initial-pass
     * 自绘右滑却正常 —— 只在三星类机型补一次被动观测的 pop。
     */
    val isSamsungWatch: Boolean by lazy { vendor.contains("samsung") }
}
