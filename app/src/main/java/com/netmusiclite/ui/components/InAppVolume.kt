package com.netmusiclite.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.Window
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.netmusiclite.data.ImageBlur
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.ui.nav.OverlayBackHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 应用内音量界面（2026-10-03 华为 Watch 3 引入 → 同日扩到「非 ColorOS」全机型 → 同日按用户
 * 截图改竖向胶囊 + 无极调节 + 背景高斯模糊）。
 *
 * ## 为什么需要它
 * 这批手表的**系统音量面板要么不存在、要么不响应音量钮/表冠** —— 三星魔改 Wear OS 2、
 * Wear OS 7、鸿蒙 4 用户实测「点音量钮没反应、转表冠不调音量」。原实现让非华为机型调
 * `adjustStreamVolume(STREAM_MUSIC, ADJUST_SAME, FLAG_SHOW_UI)`：注意 `ADJUST_SAME`
 * **本身不改变音量**，它只是「请求系统把音量面板显示出来、档位不动」；面板不存在时
 * 就等于什么都没发生 —— 这正是那批机型「点了没反应」的根因。
 *
 * ## 行为
 *  · 播放页/歌房**转表冠** → 先累积误触匡量，够了才「进入」本界面（带动画），之后逐格
 *    无极调音量；每格震动由 Rotary.kt 的 NcmHaptics.crownDetent 负责（含 ROM 降级通道）；
 *  · **音量钮** → [show]：显示当前档位（Wear 规范：媒体控件里的设备音量按钮用来
 *    「进入音量控制界面并指示输出设备」）；
 *  · **胶囊上竖向拖动** → 绝对定位直接跳到该档位（更直觉的无极兜底：表冠拿不到的机型用）；
 *  · **右滑 / 系统 back** → 关闭本界面，回到底下原页面（2026-10-07 小米手表修复：
 *    此前浮层不吃右滑/back，事件穿透到底下页面 —— 底下被解读成「返回上一页/回主页」，
 *    浮层 1.3s 后熄灭时用户看到的已是主页）；
 *  · **无极**：UI 与手势的事实来源是 0..1 的连续 [fraction]（不是整数档），
 *    只有落到系统时才量化成 STREAM_MUSIC 的整数档 —— 视觉/手感连续，声音受系统档位限制。
 *
 * ## 外观（2026-10-03 按用户截图逐像素实测，466×466）
 *  · 竖向胶囊：宽 31.1% 屏宽 × 高 69.1% 屏高，**屏幕正中**（实测中心 232,232 = 正中心）；
 *  · 轨道 = 白 10% 叠在模糊底上（实测背景 RGB(100,99,104) → 轨道 (115,114,119)，
 *    Δ15/155 ≈ α0.10）；填充 = 白 55%（实测 (185,185,187)，Δ85/155 ≈ α0.55），从底部向上；
 *  · 白色喇叭图标固定在胶囊正中；无百分比文字、无加减按钮。
 *
 * ## 背景模糊（「一比一复刻」）
 *  截图背景是一片**无任何结构的深灰**（整屏方差极小）= 极重高斯模糊 + 压暗。API 30 及以下
 *  没有 RenderEffect / Modifier.blur（需 API 31+），所以与 BackgroundStore 同路线：
   *  **抓一帧 → 缩到 132px → 纯 Kotlin 3 轮盒式模糊（[ImageBlur]）→ 放大铺满 → 压暗**。
 *  抓帧用 PixelCopy（API 26+，本项目 minSdk 26 直取）；抓帧期间先不画胶囊，保证糊的是干净底。
 *
 * ## 分流
 * `DeviceCompat.useInAppVolume`（= 非 ColorOS）→ 本界面；ColorOS 继续走系统面板
 * （其面板 + 面板接管表冠的链路已有真机验证），本界面从不出现 —— 两条适配互不干扰，
 * 分流单点在 DeviceCompat，业务侧不留厂商 if。
 */
object InAppVolume {
    /** 是否可见 */
    var visible: Boolean by mutableStateOf(false)

    /**
     * 无极音量（0..1）—— UI 与手势的**唯一事实来源**。
     * 不用整数档：整数档在 UI 上会一格一跳，读起来就是「有级」。
     */
    var fraction: Float by mutableStateOf(0f)

    /** 兼容旧接口：整数百分比（由 [fraction] 派生，只读） */
    val percent: Int get() = (fraction * 100f).roundToInt().coerceIn(0, 100)

    /** 转冠「进入」本界面所需的误触匡量（格）—— 单格蹭动/口袋误转不弹界面 */
    const val ENTER_DETENTS = 1.5f

    /** 误触匡量的静默复位窗口：转冠停手超过这个时长，重新从零累积 */
    const val ENTER_IDLE_MS = 400L

    /** 转冠满量程所需格数：越大越细腻（每格 ≈ 1/[CROWN_DETENTS_FULL_RANGE] 行程） */
    const val CROWN_DETENTS_FULL_RANGE = 18f

    /** 自动隐藏延迟：转完即收 */
    const val HIDE_MS = 1300L

    /** 最近一次调节/触摸的时刻：Host 的自动隐藏计时以它为准 */
    internal var lastActiveAt: Long = SystemClock.uptimeMillis()

    /** 拖动中：手指按在胶囊上时不计自动隐藏（按住不动不等于停手） */
    internal var interacting: Boolean = false

    /** STREAM_MUSIC 最大档；打开面板时读一次，避免每个拖动事件都走 binder */
    @Volatile
    private var maxVol = 15

    /** 已下发的整数档：同一档内的连续移动不再重复写系统音量 */
    private var lastAppliedIndex = -1

    // 音量落地放到后台线程 + CONFLATED 队列：拖动事件只更新 UI 状态，
    // 不和 AudioManager 的 binder 调用抢主线程；积压时只保留最新档位。
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val volumeWrites = Channel<Int>(Channel.CONFLATED)

    init {
        writeScope.launch {
            for (index in volumeWrites) {
                val max = maxVol.coerceAtLeast(1)
                PlayerEngine.setVolumeFraction(
                    index.toFloat() / max,
                    android.media.AudioManager.FLAG_PLAY_SOUND,
                )
            }
        }
    }

    /** 音量钮 / 一起听房音量钮：显示当前档位（不改音量） */
    fun show() {
        if (!visible) syncFromAudio()
        lastActiveAt = SystemClock.uptimeMillis()
        visible = true
    }

    fun hide() {
        visible = false
        interacting = false
    }

    /** 转冠「进入」：把攒下的匡量一次兑现（首段不白转），并开始无极调节 */
    fun enter(initialDelta: Float) {
        if (!visible) syncFromAudio()
        push((fraction + initialDelta).coerceIn(0f, 1f))
    }

    /** 转冠无极微调：delta 为行程比例（已按格数换算），不做取整 —— 无极的关键 */
    fun nudge(delta: Float) {
        if (delta == 0f) return
        push((fraction + delta).coerceIn(0f, 1f))
    }

    /** 胶囊上竖向拖动：绝对定位到该档位（名字不叫 setFraction —— 会与 [fraction] 的 JVM setter 撞签名） */
    fun setTo(f: Float) {
        push(f.coerceIn(0f, 1f))
    }

    /** 胶囊拖动开始/结束：按住期间不自动隐藏，松手后重新计时 */
    fun beginDrag() {
        interacting = true
        lastActiveAt = SystemClock.uptimeMillis()
    }

    fun endDrag() {
        interacting = false
        lastActiveAt = SystemClock.uptimeMillis()
    }

    /** 兼容旧接口（整数档步进）；新调用点请用 [enter] / [nudge] */
    fun adjust(steps: Int) {
        if (steps == 0) return
        val max = PlayerEngine.maxVolume().coerceAtLeast(1)
        if (!visible) syncFromAudio()
        push((fraction + steps.toFloat() / max).coerceIn(0f, 1f))
    }

    /** 落地：连续值 → UI 立即更新；系统音量按整数档合并后异步写入 */
    private fun push(f: Float) {
        fraction = f
        lastActiveAt = SystemClock.uptimeMillis()
        val max = maxVol.coerceAtLeast(1)
        val index = (f * max + 0.5f).toInt().coerceIn(0, max)
        if (index != lastAppliedIndex) {
            lastAppliedIndex = index
            volumeWrites.trySend(index)
        }
        visible = true
    }

    /** 从真实音量回读（面板刚打开时对齐；隐藏期间系统音量可能被别处改过） */
    private fun syncFromAudio() {
        maxVol = PlayerEngine.maxVolume().coerceAtLeast(1)
        fraction = PlayerEngine.volumeFraction().coerceIn(0f, 1f)
        lastAppliedIndex = (fraction * maxVol + 0.5f).toInt().coerceIn(0, maxVol)
    }
}

/* ==================== 外观参数（截图实测，改这里即可调外觀） ==================== */

/** 胶囊宽 / 高占屏比（实测 145/466 = 31.1%、322/466 = 69.1%） */
private const val BAR_WIDTH_RATIO = 0.311f
private const val BAR_HEIGHT_RATIO = 0.691f

/** 轨道 / 填充的白色不透明度（实测 α0.10 / α0.55） */
private const val TRACK_WHITE = 0.10f
private const val FILL_WHITE = 0.55f

/** 右滑关闭阈值（dp）—— 与 PlayerSeekOverlay / SwipeBackBox 的返回行程同款（37dp） */
private const val EXIT_THRESHOLD_DP = 37f

/** 抓帧预览边长与模糊参数（132px 上半径 12×3 轮 ≈ 参考图「糊到无结构」） */
private const val BACKDROP_SIDE = 132
private const val BACKDROP_BLUR_RADIUS = 12
private const val BACKDROP_BLUR_PASSES = 3

/** 模糊底压暗：参考图背景 ≈ #60~#68 中灰 */
private const val BACKDROP_DIM = 0.42f

/** 抓帧超时：真机 PixelCopy 偶发要几十到几百毫秒，超时才降级为纯压暗 */
private const val BACKDROP_TIMEOUT_MS = 300L

/** 自动隐藏轮询间隔（替代旧的 generation 重组唤醒） */
private const val AUTO_HIDE_POLL_MS = 100L

/** 退场淡出时长（与 AnimatedVisibility 的 fadeOut 对齐，之后才能丢弃底图） */
private const val EXIT_FADE_MS = 200L

/** 抓帧前等两帧，确保「浮层全透明」这一帧已经真正上屏（快速连开时不糊进上一轮残影） */
private const val CLEAN_FRAMES = 2

/**
 * 全局音量界面浮层（挂 AppRoot 导航图之上）。
 *
 * ★ 触摸（2026-10-07 小米手表修复）：根 Box 挂横向拖拽检测 —— 右滑累计 ≥37dp 松手关闭
 *   本界面（与全应用浮层/右滑返回同一语义），横向拖拽全部吞掉、不下传。
 *   ⚠ Compose 命中测试：同一层里有带 pointerInput 的兄弟时触摸不再下传给下层兄弟 ——
 *   所以浮层展示期间对触摸是「模态」的。此前注释写「只有胶囊自己消费触摸，其余区域不拦」
 *   是错的：根层没有任何 pointerInput 节点时右滑穿透到底下的播放页/导航（被解读成
 *   「返回上一页」，栈底则是「回主页」），浮层还盖在屏幕上、底下已经退走了。
 *   系统 back / 华为式系统级右滑 → [OverlayBackHandler] 先关浮层，同样不穿透。
 *
 * ★ 抓帧顺序（关键）：进入时先让整层浮层保持**完全透明**，抓完干净底再淡入 ——
 *   压暗/胶囊只要先画出来，PixelCopy 都会把它们截进底图（压暗层会让底图二次变暗，
 *   胶囊会留下一个亮斑）。
 *
 * ★ 2026-10-18 修复「模糊时有时无 + 滑动不跟手」：
 *   ① 抓帧的 LaunchedEffect 只以 visible 为 key。旧实现把 generation 也当 key，
 *      拖动/转冠每个事件都让 generation 自增 → 抓帧协程被反复取消，连续调节期间
 *      永远抓不完，表现为「有时候没有模糊」；停手后抓到的那帧还可能带着上一轮浮层。
 *   ② 自动隐藏改成轮询 lastActiveAt（非 Compose 状态），不再因每个拖动事件重组 Host、
 *      重启协程；interacting 期间不隐藏。
 *   ③ 音量写入移到后台 CONFLATED 队列，拖动事件不再在主线程做 3 次 AudioManager binder
 *      调用；同一整数档内不重复写系统音量。
 */
@Composable
fun VolumeHost() {
    val ctx = LocalContext.current
    var backdrop by remember { mutableStateOf<ImageBitmap?>(null) }
    // backdropReady：模糊底是否已就绪（就绪前整层透明，保证抓帧抓到干净底）
    var backdropReady by remember { mutableStateOf(false) }

    // 系统 back（含华为式被系统吃掉的右滑）：展示期间先关本浮层，不动导航
    OverlayBackHandler(enabled = InAppVolume.visible) { InAppVolume.hide() }

    // 抓帧：只跟 visible 走。调节事件再多也不再取消它。
    LaunchedEffect(InAppVolume.visible) {
        if (!InAppVolume.visible) {
            // 等退场动画放完再丢弃：退场期间继续显示上一张底图/胶囊
            delay(EXIT_FADE_MS)
            backdrop = null
            backdropReady = false
            return@LaunchedEffect
        }
        backdrop = null
        backdropReady = false
        // 等「全透明浮层」这一帧真正上屏再抓（快速连开时上一轮浮层可能还在退场）
        repeat(CLEAN_FRAMES) { withFrameNanos { } }
        backdrop = withTimeoutOrNull(BACKDROP_TIMEOUT_MS) { captureBlurredBackdrop(ctx) }
        backdropReady = true
    }

    // 自动隐藏：不依赖 Compose 状态自增，按住胶囊期间也不收
    LaunchedEffect(InAppVolume.visible) {
        if (!InAppVolume.visible) return@LaunchedEffect
        while (InAppVolume.visible) {
            delay(AUTO_HIDE_POLL_MS)
            if (!InAppVolume.interacting &&
                SystemClock.uptimeMillis() - InAppVolume.lastActiveAt >= InAppVolume.HIDE_MS
            ) {
                InAppVolume.hide()
            }
        }
    }

    AnimatedVisibility(
        visible = InAppVolume.visible,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(180)),
    ) {
        // 抓帧期间整层 alpha=0（不是不组合，而是不绘制），既不污染截图也不打断手势；
        // 底图就绪后 120ms 淡入。目标为 0 时必须 snap —— 快速连开时旧内容不能残留一帧。
        val contentAlpha by animateFloatAsState(
            targetValue = if (backdropReady) 1f else 0f,
            animationSpec = if (backdropReady) tween(120) else snap(),
            label = "volumeContentAlpha",
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = contentAlpha }
                .pointerInput(Unit) {
                    // 右滑退出：右向累计超阈值松手 → 关闭；左滑清零（不穿透成「打开歌词页」）。
                    // 手势约定与 PlayerSeekOverlay 完全相同。
                    var accum = 0f
                    val exitPx = EXIT_THRESHOLD_DP.dp.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { accum = 0f },
                        onDragEnd = { if (accum >= exitPx) InAppVolume.hide() },
                        onDragCancel = { accum = 0f },
                    ) { change, amount ->
                        change.consume()
                        if (amount > 0f) accum += amount else accum = 0f
                    }
                },
        ) {
            // 1) 模糊底（抓帧 + 盒式模糊的位图，放大铺满）
            backdrop?.let { img ->
                Image(
                    bitmap = img,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            // 2) 压暗（截图背景是中灰，底不压成死黑、也不让亮封面色透出来）
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = BACKDROP_DIM)))
            // 3) 胶囊（等模糊底就绪才画 —— 见上面抓帧顺序）
            if (backdropReady) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    VolumeBar()
                }
            }
        }
    }
}

/**
 * 竖向胶囊音量条：轨道白 10% + 填充白 55% 从底部向上（高度随 [InAppVolume.fraction] 平滑跟手），
 * 白色喇叭图标居中；胶囊上竖向拖动 = 绝对定位调音量（无极兜底）。
 */
@Composable
private fun VolumeBar() {
    val shape = RoundedCornerShape(50)
    // ⚠ 胶囊尺寸按「UI 坐标系屏宽/屏高」的比例算（screenWidthUiDp / screenHeightUiDp）：
    //   密度被 NcmTheme 等比缩放后，LocalConfiguration.screenWidthDp 不再等于屏宽，
    //   直接乘比例会让胶囊在非 233dp 屏上偏小/偏大（见 Common.kt 的 screenWidthUiDp 注释）。
    val screenW = screenWidthUiDp()
    val screenH = screenHeightUiDp()
    var dragging by remember { mutableStateOf(false) }
    // 拖动中必须 snap：90ms 补间 = 手指已经在上面、白条还在后面追，观感就是「不跟手」。
    // 转冠/点击仍保留 90ms 平滑（离散增量需要一点过渡）。
    val fill by animateFloatAsState(
        targetValue = InAppVolume.fraction,
        animationSpec = if (dragging) snap() else tween(90),
        label = "volumeFill",
    )
    Box(
        modifier = Modifier
            .width(screenW * BAR_WIDTH_RATIO)
            .height(screenH * BAR_HEIGHT_RATIO)
            .clip(shape)
            .background(Color.White.copy(alpha = TRACK_WHITE))
            .pointerInput(Unit) {
                // 绝对定位：手指落在胶囊的哪个高度就是哪个档位（真·无极，无步进感）
                fun applyAt(y: Float) {
                    val h = size.height.toFloat()
                    if (h > 0f) InAppVolume.setTo(1f - y / h)
                }
                detectVerticalDragGestures(
                    onDragStart = { off ->
                        dragging = true
                        InAppVolume.beginDrag()
                        applyAt(off.y)
                    },
                    onDragEnd = {
                        dragging = false
                        InAppVolume.endDrag()
                    },
                    onDragCancel = {
                        dragging = false
                        InAppVolume.endDrag()
                    },
                ) { change, _ ->
                    change.consume()
                    applyAt(change.position.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // 从底部向上生长的填充
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(fill.coerceIn(0f, 1f))
                .background(Color.White.copy(alpha = FILL_WHITE)),
        )
        Icon(
            NcmIcons.Volume,
            contentDescription = "音量",
            tint = Color.White,
            modifier = Modifier.size(26.dp),
        )
    }
}

/* ==================== 抓帧 + 模糊 ==================== */

/**
 * 抓当前窗口一帧 → 缩图 → 3 轮盒式模糊（≈高斯）→ ImageBitmap；任何一步失败返回 null。
 *
 * ★ 2026-10-06 改 internal：长按暂停键唤出的长条进度条浮层（[PlayerSeekOverlayHost]）
 *   用同一套「抓帧 → 缩图 → 盒式模糊 → 压暗」遮挡下方页面，两条浮层共用这一条链路，
 *   参数（边长/半径/轮数/压暗）各自持有，互不影响。
 */
internal suspend fun captureBlurredBackdrop(ctx: Context): ImageBitmap? {
    val win = ctx.findActivity()?.window ?: return null
    val shot = captureOnce(win) ?: return null
    return withContext(Dispatchers.Default) {
        runCatching {
            val small = ImageBlur.scaleToSide(shot, BACKDROP_SIDE)
            val blurred = ImageBlur.blur(small, BACKDROP_BLUR_RADIUS, BACKDROP_BLUR_PASSES)
            if (blurred !== small) small.recycle()
            if (shot !== small) shot.recycle()
            blurred.asImageBitmap()
        }.getOrNull()
    }
}

/** PixelCopy 抓窗口一帧（API 26+；失败/异常返回 null，调用方降级为纯压暗） */
internal suspend fun captureOnce(window: Window): Bitmap? {
    val view = window.decorView ?: return null
    val w = view.width
    val h = view.height
    if (w <= 0 || h <= 0) return null
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    return suspendCancellableCoroutine { cont ->
        try {
            PixelCopy.request(
                window,
                bmp,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        if (cont.isActive) cont.resumeWith(Result.success(bmp)) else bmp.recycle()
                    } else {
                        bmp.recycle()
                        if (cont.isActive) cont.resumeWith(Result.success(null))
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (t: Throwable) {
            bmp.recycle()
            if (cont.isActive) cont.resumeWith(Result.success(null))
        }
    }
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
