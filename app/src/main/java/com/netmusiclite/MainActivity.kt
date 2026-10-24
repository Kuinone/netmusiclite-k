package com.netmusiclite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.netmusiclite.data.CrashLogger
import com.netmusiclite.data.DownloadStore
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.SessionStore
import com.netmusiclite.ui.components.CopySheetHost
import com.netmusiclite.ui.components.NcmHaptics
import com.netmusiclite.ui.nav.AppRoot
import com.netmusiclite.ui.theme.NcmTheme
import com.netmusiclite.update.UpdateFlow

/**
 * 入口：初始化会话/震动/播放器/下载，然后交给 Compose 导航。
 * 未登录首启直接进扫码登录页。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLogger.step("MainActivity.onCreate")
        // 流畅度 R2：Compose 首帧即满屏自绘，系统窗口背景层是多余的根层填充
        window.setBackgroundDrawable(null)
        // ★ 2026-10-03 Wear 音量规范：把硬件音量控件（音量键 / 某些 ROM 映射到表冠的键）
        //   显式绑到 STREAM_MUSIC（Android 官方《处理音频输出变化》要求
        //   setVolumeControlStream(AudioAttributes.getVolumeControlStream)）。
        //   本 App 只播媒体，取值与系统默认一致，属显式兜底、不改现有手感。
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        // 2026-10-03（华为 Watch 3）：系统返回接管 —— 华为把右滑做成系统级返回，触摸被
        //   系统吃掉（应用内跟手动画收不到事件），松手直接给 Activity 派发 back、默认
        //   finish 应用（用户报「右滑无法控制界面，直接退出」）。这里最早注册回调，把
        //   系统 back 接管成应用内语义（子页回退 / 播放页栈底回主页 / 主页退出动画），
        //   分发规则见 SystemBackRouter。注册在 setContent 之前：页面内 Compose BackHandler
        //   （弹窗等）后注册、优先级更高不受影响；无系统 back 派发的机型零影响。
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    com.netmusiclite.ui.nav.SystemBackRouter.dispatch()
                }
            },
        )
        // 播放保活（2026-09-26）：前台媒体服务的通知可见性走运行时权限；
        // 不授予时服务照常保活，仅媒体通知不显示
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
        SessionStore.init(this)
        NcmHaptics.init(this)
        // 冷启动优化：这里只做轻量登记（context/偏好读取），ExoPlayer/MediaSession 构建
        // 延迟到 Compose 首帧后由 AppRoot 调 PlayerEngine.warmUp()，落在启动浮层窗口内
        PlayerEngine.init(this)
        DownloadStore.init(this)
        com.netmusiclite.data.BackgroundStore.init(this)
        com.netmusiclite.data.AppearancePrefs.init(this)
        com.netmusiclite.data.QualityPrefs.init(this)
        com.netmusiclite.data.HistoryStore.init(this)
        com.netmusiclite.data.ListenSession.init(this)
        com.netmusiclite.data.MsgOrderStore.init(this)
        com.netmusiclite.data.OfflineCache.init(this)
        com.netmusiclite.data.ConsentStore.init(this)
        com.netmusiclite.data.GuideStore.init(this)
        // ★ 2026-10-03 表盘形状偏好：必须在 setContent 之前 init —— AppRoot 的起始路由
        //   （startDestination）要读 ScreenShapePrefs.chosen 决定是否先弹形状选择页。
        com.netmusiclite.data.ScreenShapePrefs.init(this)
        // ★ 2026-10-04 表冠适配（设置 → 表冠适配：音量页 / 列表页方向各一个正/反开关）：
        //   必须在 setContent 之前 init —— Rotary.kt 的 rotaryList/rotaryCustom 在组合期就会读它。
        com.netmusiclite.data.RotaryTuning.init(this)
        // ★ 2026-10-18 播放页表冠音量开关（设置 → 歌曲响度均衡下方）：播放页的表冠回调
        //   在组合期读它，同样要在 setContent 之前 init。
        com.netmusiclite.data.CrownVolumePrefs.init(this)
        // ★ 2026-10-18 m3e 纯黑底的中心柔光开关（默认开）
        com.netmusiclite.data.M3eGlowPrefs.init(this)
        // ★ 2026-10-04 高级版公告问卷：同样要在 setContent 之前 init —— startDestination
        //   要读 SurveyStore.done 决定老用户首次启动是否先弹问卷。
        com.netmusiclite.data.SurveyStore.init(this)
        CrashLogger.step("stores ready")
        // 全局 Coil：封面/背景无透明通道，RGB565 解码省一半显存带宽，滚动更稳；
        // 流畅度 R6：crossfade(false) 省掉淡入动画帧，allowHardware(true) 硬件位图走 GPU 合成
        coil.Coil.setImageLoader(
            coil.ImageLoader.Builder(this)
                .allowRgb565(true)
                .allowHardware(true)
                .crossfade(false)
                .respectCacheHeaders(false)
                .build()
        )
        setContent {
            NcmTheme {
                // 长按复制的全局面板宿主（Copyable.kt）：叠在导航之上，
                // 私信页 / 房间内聊天 / 两种主题共用一份，页面不必各自 hoist 复制状态。
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    AppRoot()
                    CopySheetHost()
                }
            }
        }
        CrashLogger.step("setContent dispatched")
        // ★ 2026-10-18 华强北表顶部黑条修复（主题里已声明 windowFullscreen，这里补运行时兜底）
        window.decorView.post { ensureImmersiveFullscreen() }
        // 评论点赞诊断（临时版）：冷启动 3 秒后自动跑一次请求形态矩阵，结果进 logcat 的 LTDiag
        if (com.netmusiclite.data.NcmApi.DIAG_ON) {
            Thread {
                runCatching {
                    Thread.sleep(3000)
                    kotlinx.coroutines.runBlocking { com.netmusiclite.data.NcmApi.diagCommentLike(3355480212L) }
                }
            }.start()
        }
    }

    /**
     * 表冠兜底（2026-10-03 华为 Watch 4 / 鸿蒙 4）。
     *
     * 华为把表冠投成标准 `ACTION_SCROLL` + `SOURCE_ROTARY_ENCODER` 的通用运动事件
     * （诊断包实测：Root/Activity 的 dispatch/onGenericMotionEvent 全部命中，事件确实进了应用），
     * 但焦点链上没有旋入节点时无人消费，一路冒泡到这里被丢弃 —— 真机表现就是「所有界面转表冠
     * 都没反应」。这里把**未被消费**的表冠事件转发给当前界面注册的兜底处理器
     * （rotaryList / rotaryCustom 自动注册，见 RotaryFallback），各页面无需改动。
     *
     * ⚠ ColorOS 等焦点正常的机型：事件在 Compose 内已被消费，本方法**不会被调用**，手感零变化。
     *   轴值按系统 verticalScrollFactor 换算成与 Compose 滚轮通道一致的像素口径再转发。
     *
     * ★ 2026-10-04 华为方向统一修正（华为 Watch 4 真机两轮取证）：
     *   上一版把「华为 AXIS_SCROLL 符号与标准 Wear 相反」的补偿打在**播放页那条路径**上
     *   （onCrownForVolume 里 -deltaRaw），结果真机表现为「音量页方向正、主页列表方向反」——
     *   列表（rotaryList）和音量（rotaryCustom）本就共用这一个事件入口，补丁打在局部必然漏掉
     *   其它页面。现在收口到这里：**所有**消费方一次性拿到标准方向，播放页那行局部取反同步删掉
     *   （不删会双重取反又变反）。
     *   厂商判定走 DeviceCompat.isHuaweiWatch（厂商串 + 华为私有 VibratorEx 能力双判据），
     *   其它走兜底通道的机型（如三星魔改 Wear OS）sign 恒为 +1，不受影响。
     */
    override fun onGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_SCROLL &&
            event.isFromSource(android.view.InputDevice.SOURCE_ROTARY_ENCODER)
        ) {
            val axis = event.getAxisValue(android.view.MotionEvent.AXIS_SCROLL)
            if (axis != 0f) {
                val sign = if (com.netmusiclite.data.DeviceCompat.isHuaweiWatch) -1f else 1f
                val px = axis * sign *
                    android.view.ViewConfiguration.get(this).scaledVerticalScrollFactor
                if (com.netmusiclite.ui.components.RotaryFallback.deliver(px)) return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /**
     * ★ 2026-10-18 用户 bug「华强北手表上软件内顶部状态栏区域显示黑色」的运行时兜底。
     *
     * 背景：ColorOS Watch / 华为 / 三星之外的一批手表（华强北普通 Android 方案）系统状态栏
     * 是**常驻可见**的；而本工程 windowBackground 与 statusBarColor 都是透明（圆屏四角要
     * 露出表盘），状态栏区域就露出 SurfaceFlinger 的默认黑底 —— 顶部一条「有时间/电量图标、
     * 底色纯黑」的条。ColorOS Watch（OWW261）看不到这个现象，是因为它的 ROM 在系统层就把
     * 状态栏整个隐藏了（App 窗口已带 FULLSCREEN flag）。
     *
     * 两道修复：
     *  ① 声明式：themes.xml 的 `android:windowFullscreen=true`（系统创建窗口时直接不画状态栏）；
     *  ② 运行时（本方法）：给不遵守该 flag、或把状态栏唤出/恢复的 ROM 补一刀 ——
     *     每次获得窗口焦点 / 回前台检查一遍。
     *
     * ⚠ **只有系统栏当前真的可见时才动手**：OWW261 这类系统本身已全屏的机型，
     *   insets 恒为不可见 → 这里直接 return，布局与手势零变化，不引入回归。
     *
     * 用平台 API + 版本分支实现（不新增 androidx.core 依赖）：
     *  · API 30+：WindowInsetsController.hide(systemBars) + 滑动可临时唤出（immersive 手势行为）；
     *  · API 26~29：老的 SYSTEM_UI_FLAG_IMMERSIVE_STICKY 一套（本工程 minSdk 26）。
     */
    private fun ensureImmersiveFullscreen() {
        val decor = window.decorView
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val insets = decor.rootWindowInsets ?: return
            if (!insets.isVisible(android.view.WindowInsets.Type.systemBars())) return
            window.insetsController?.let { c ->
                c.hide(android.view.WindowInsets.Type.systemBars())
                c.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            val vis = decor.systemUiVisibility
            @Suppress("DEPRECATION")
            val alreadyHidden =
                (vis and android.view.View.SYSTEM_UI_FLAG_FULLSCREEN) != 0 &&
                    (vis and android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0
            if (alreadyHidden) return
            @Suppress("DEPRECATION")
            decor.systemUiVisibility =
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    /**
     * 华强北表：手势临时唤出状态栏、或某些 ROM 在焦点切换后把状态栏恢复 ——
     * 每次重新获得焦点再收一次（内部先判可见性，OWW261 上恒为 no-op）。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ensureImmersiveFullscreen()
    }

    /**
     * 更新流程续接（2026-10-02 用户口径）：回前台时把「正在做的这次更新」接上 ——
     * 下载重挂进度 / 授权回来自动续装 / 装完清尾（删包+清断点）。见 UpdateFlow.onAppResume。
     */
    override fun onResume() {
        super.onResume()
        CrashLogger.step("MainActivity.onResume")
        UpdateFlow.onAppResume(this)
        // 华强北表：切后台回来时状态栏可能被系统恢复 → 再收一次（不可见时是 no-op）
        window.decorView.post { ensureImmersiveFullscreen() }
    }

    override fun onPause() {
        CrashLogger.step("MainActivity.onPause")
        super.onPause()
    }
}
