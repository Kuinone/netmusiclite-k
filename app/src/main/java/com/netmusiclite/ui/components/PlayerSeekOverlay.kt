package com.netmusiclite.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.ui.nav.OverlayBackHandler
import com.netmusiclite.ui.theme.PlayAccent
import com.netmusiclite.ui.theme.PlaySongAccent
import com.netmusiclite.ui.theme.playLightTheme
import com.netmusiclite.ui.theme.PlayTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 长按播放页暂停键 0.5 秒唤出的「长条进度条」浮层（2026-10-06 用户口径）。
 *
 * 外观 / 交互：
 *  · 屏幕正中一条**两头圆润的长条进度条**（胶囊形），**条本体正压屏幕垂直中心**，
 *    时间读数单独挪到条下方（不再把条顶高 —— 2026-10-06 用户口径「进度条改成居中，
 *    数字往下移动」）：填充色 = [PlayAccent]（自定义风格）/ [PlaySongAccent]
 *    （取色、封面模糊风格，2026-10-06 用户口径：跟专辑封面主题色）—— 主题色本身就走「自动取色」链路
 *    （Theme.kt 的 EffectiveSeed：壁纸主色 → 封面主色 → 预设种子），
 *    开着自动取色时这条颜色永远跟当前环境走；
 *  · 四周用**高斯模糊**遮挡下方播放页：复用应用内音量界面的「PixelCopy 抓帧 → 缩图 →
 *    3 轮盒式模糊」链路（[captureBlurredBackdrop]，API 30 及以下没有 RenderEffect 的
 *    机型同款方案），抓帧期间先不画条，保证糊的是干净底；
 *  · 压暗层**随主题自适应**（2026-10-06 用户口径）：深色主题黑 42%（原行为），
 *    浅色主题改白色半透明 —— 浅底上再压黑会糊成一块脏灰，与浅色玻璃材质也不同调；
 *  · 条上**横向拖动 / 点击** = 无极 seek（拖动中只更新条、松手落地一次 seekTo，避免
 *    逐帧 seek 打断解码器）；
 *  · 条外区域**右滑**退出本浮层（阈值与应用内返回一致 37dp）；系统 back / 华为式系统级
 *    右滑同样先关本浮层（[OverlayBackHandler]，2026-10-07 与音量页同批修复）；
 *  · 条下方一行极小的时间读数（当前 / 总时长），拖动时实时跟随 —— 除此之外不加任何
 *    按钮，保持视觉克制。
 *
 * 显示期间音乐照常播放：未拖动时进度跟播放位置走（500ms tick），拖动中跟手指走。
 */
object PlayerSeekOverlay {

    /** 是否可见（播放页 PauseButton 长按 0.5s 置 true） */
    var visible: Boolean by mutableStateOf(false)

    fun show() {
        visible = true
    }

    fun hide() {
        visible = false
    }
}

/* ==================== 外观参数 ==================== */

/** 长条宽度占屏比 */
private const val BAR_WIDTH_RATIO = 0.86f

/** 长条厚度（dp）：细长条，两头用 RoundedCornerShape(50) 收圆 */
private val BAR_THICKNESS = 12.dp

/** 条触摸热区高度（dp）：比条本身高很多，圆表上好拖 */
private val BAR_HIT_HEIGHT = 56.dp

/** 右滑退出阈值（px）—— 与 SwipeBackBox 的返回行程同款（37dp） */
private const val EXIT_THRESHOLD_DP = 37f

/** 模糊底压暗（同 InAppVolume 口径） */
private const val BACKDROP_DIM = 0.42f

/**
 * 浅色主题的压暗系数（2026-10-06 用户口径「高斯模糊做深色浅色主题的自适应」）。
 * 浅色主题下压黑 42% 会把浅色播放页糊成脏灰，且与本主题的玻璃材质（白 72%）完全不同调；
 * 改为白色半透明，观感 = 一层磨砂白玻璃，条/文字仍用主题深色，对比度反而更高。
 */
private const val BACKDROP_DIM_LIGHT = 0.62f

/** 抓帧超时：真机 PixelCopy 偶发要几十到几百毫秒，超时才降级为纯压暗 */
private const val BACKDROP_TIMEOUT_MS = 300L

/** 底图/长条淡入时长 */
private const val BACKDROP_FADE_MS = 120

/** 退场淡出时长（与 AnimatedVisibility 的 fadeOut 对齐，之后才能丢弃底图） */
private const val EXIT_FADE_MS = 200L

/** 抓帧前等两帧，确保「浮层全透明」这一帧已经真正上屏 */
private const val CLEAN_FRAMES = 2

/**
 * 时间读数相对屏幕中心的垂直偏移（dp）：条本体压在正中，读数往下让位
 * （≥ 热区半高 28dp，避免与条的触摸区重叠；比旧版「紧贴条下沿」再往下挪一档）。
 */
private val READOUT_OFFSET_Y = 34.dp

/**
 * 全局浮层 Host：挂在播放页根 Box 内（调用点见 PlayerScreen）。
 * ★ 抓帧顺序同 VolumeHost：先把模糊底抓完再放条 —— 抓帧期间条不参与绘制，
 *   否则 PixelCopy 抓到的是「带条的一帧」，糊完会在背景里留一个亮斑。
 */
@Composable
fun PlayerSeekOverlayHost() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var backdrop by remember { mutableStateOf<ImageBitmap?>(null) }
    // backdropReady：模糊底是否就绪（就绪前不画条，保证抓帧抓到干净底）
    var backdropReady by remember { mutableStateOf(false) }

    // 系统 back（含华为式被系统吃掉的右滑）：展示期间先关本浮层，不动导航
    OverlayBackHandler(enabled = PlayerSeekOverlay.visible) { PlayerSeekOverlay.hide() }

    LaunchedEffect(PlayerSeekOverlay.visible) {
        if (!PlayerSeekOverlay.visible) {
            // 等退场动画放完再丢弃：退场期间继续显示上一张底图/长条
            delay(EXIT_FADE_MS)
            backdrop = null
            backdropReady = false
            return@LaunchedEffect
        }
        backdrop = null
        backdropReady = false
        // 抓帧期间整层透明；等两帧确保这一帧已上屏，避免把上一次浮层/压暗层截进底图
        repeat(CLEAN_FRAMES) { withFrameNanos { } }
        backdrop = withTimeoutOrNull(BACKDROP_TIMEOUT_MS) { captureBlurredBackdrop(ctx) }
        backdropReady = true
    }

    AnimatedVisibility(
        visible = PlayerSeekOverlay.visible,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(180)),
    ) {
        // 右滑退出（条外区域）：右向累计超过阈值松手 → 关闭，退场交给 AnimatedVisibility
        // 的 fadeOut。条的热区在更上层消费横向拖拽（seek），两层手势互不干扰。
        var swipeAccum by remember { mutableStateOf(0f) }
        val exitPx = with(androidx.compose.ui.platform.LocalDensity.current) {
            EXIT_THRESHOLD_DP.dp.toPx()
        }
        val contentAlpha by animateFloatAsState(
            targetValue = if (backdropReady) 1f else 0f,
            animationSpec = if (backdropReady) tween(BACKDROP_FADE_MS) else snap(),
            label = "seekContentAlpha",
        )
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = contentAlpha }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (swipeAccum >= exitPx) PlayerSeekOverlay.hide()
                            swipeAccum = 0f
                        },
                        onDragCancel = { swipeAccum = 0f },
                    ) { change, amount ->
                        change.consume()
                        if (amount > 0f) swipeAccum += amount else swipeAccum = 0f
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
            // 2) 压暗（按播放组明暗：深色压黑、浅色压白 —— 见 BACKDROP_DIM_LIGHT；
            //    条外的右滑手势也挂在这一层）
            Box(
                Modifier.fillMaxSize().background(
                    if (playLightTheme) Color.White.copy(alpha = BACKDROP_DIM_LIGHT)
                    else Color.Black.copy(alpha = BACKDROP_DIM)
                )
            )
            // 3) 长条（等模糊底就绪才画 —— 见上面抓帧顺序）
            if (backdropReady) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    SeekBarContent()
                }
            }
        }
    }
}

/**
 * 中央长条进度条：轨道（主题感知低对比底色）+ [Accent] 填充；
 * 横向拖动 / 点击 = seek；未拖动时跟随播放位置（500ms tick）。
 * 条本体垂直居中在屏幕正中，时间读数按 [READOUT_OFFSET_Y] 往下让位。
 */
@Composable
private fun SeekBarContent() {
    val durationMs = PlayerEngine.durationMs.coerceAtLeast(1L)
    // 拖动中的绝对进度（0..1）；null = 未拖动（跟播放位置走）
    var drag by remember { mutableStateOf<Float?>(null) }
    // 读播放位置（State）：显示期间每 500ms 刷新一次进度，浮层重组范围只有本组件
    val posMs = PlayerEngine.positionMs
    val fraction = drag ?: ((posMs.toFloat() / durationMs).coerceIn(0f, 1f))
    val displayMs = (fraction * durationMs).toLong().coerceAtLeast(0L)

    val light = playLightTheme
    // 轨道底色：浅色模糊底偏亮，白轨道会消失 —— 主题感知
    val trackColor = if (light) Color.Black.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.16f)
    // ⚠ 拖动状态在手势回调里要读最新值：Kotlin 属性委托闭包天然读委托，无需 rememberUpdatedState
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // ---- 条本体（含热区）：正好压屏幕中心，不受下方读数影响 ----
        Box(
            Modifier
                .width(screenWidthUiDp() * BAR_WIDTH_RATIO)
                .height(BAR_HIT_HEIGHT),
            contentAlignment = Alignment.Center,
        ) {
            // 长条本体（轨道）：RoundedCornerShape(50) = 两头圆润
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(BAR_THICKNESS)
                    .clip(RoundedCornerShape(50))
                    .background(trackColor)
                    .pointerInput(durationMs) {
                        fun applyAt(x: Float) {
                            if (size.width > 0) drag = (x / size.width).coerceIn(0f, 1f)
                        }
                        detectHorizontalDragGestures(
                            onDragStart = { off -> applyAt(off.x) },
                            onDragEnd = {
                                // 松手落地一次 seek（拖动中不 seek，避免逐帧打断解码器）
                                drag?.let { PlayerEngine.seekTo((it * durationMs).toLong()) }
                                drag = null
                            },
                            onDragCancel = { drag = null },
                        ) { change, _ ->
                            change.consume()
                            applyAt(change.position.x)
                        }
                    }
                    // 点击定位：点条上任意处直接跳到该位置
                    .pointerInput(durationMs) {
                        detectTapGestures { off ->
                            if (size.width > 0) {
                                val f = (off.x / size.width).coerceIn(0f, 1f)
                                PlayerEngine.seekTo((f * durationMs).toLong())
                            }
                        }
                    },
            ) {
                // 填充色（2026-10-06 用户口径）：取色 / 封面模糊两种播放风格跟**专辑封面
                // 主题色**（SongAccent），自定义风格跟壁纸（Accent）—— 与同页进度弧、
                // 歌词墨色同源，下面那条注释里的撕裂感就是这么消掉的
                val fill = if (com.netmusiclite.data.AppearancePrefs.playShowsWallpaper) {
                    PlayAccent
                } else {
                    PlaySongAccent
                }
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(50))
                        .background(fill),
                )
            }
        }
        // ---- 时间读数：往下让位，拖动时实时跟随 ----
        Text(
            formatMs(displayMs) + " / " + formatMs(durationMs),
            fontSize = 9.sp,
            color = PlayTextSecondary,
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = READOUT_OFFSET_Y),
        )
    }
}
