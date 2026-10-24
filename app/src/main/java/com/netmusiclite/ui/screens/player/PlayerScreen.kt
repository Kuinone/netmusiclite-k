package com.netmusiclite.ui.screens.player

import com.netmusiclite.ui.m3e.m3eSwipeLeft
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import com.netmusiclite.data.ArtistItem
import com.netmusiclite.data.NcmApi
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.update.UpdateChecker
import com.netmusiclite.update.UpdateFlow
import com.netmusiclite.update.UpdatePrompt
import com.netmusiclite.ui.components.CircleIconButton
import com.netmusiclite.ui.components.EdgeProgressRing
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.Pressable
import com.netmusiclite.ui.components.RotatingCover
import com.netmusiclite.ui.components.SwipeBackBox
import com.netmusiclite.ui.components.SwipeBackFader
import com.netmusiclite.ui.components.UpdateAvailableDialog
import com.netmusiclite.ui.components.UpdateHintPill
import com.netmusiclite.ui.components.ambientSurfaceColor
import com.netmusiclite.ui.components.frostedGlass
import com.netmusiclite.ui.components.findActivity
import com.netmusiclite.ui.components.installedVersionNameOf
import com.netmusiclite.ui.components.isRoundScreen
import com.netmusiclite.ui.components.rotaryCustom
import com.netmusiclite.ui.components.screenHeightUiDp
import com.netmusiclite.ui.components.updatePillLabel
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.NavMotionSpec
import com.netmusiclite.ui.nav.OverlayBackHandler
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.TransitionCoordinator
import com.netmusiclite.ui.nav.navigateWithMotion
import com.netmusiclite.ui.theme.PlayAccent
import com.netmusiclite.ui.theme.PlaySongAccent
import com.netmusiclite.ui.theme.PlayTextPrimary
import com.netmusiclite.ui.theme.PlayTextSecondary
import com.netmusiclite.ui.theme.PlayToneScope
import com.netmusiclite.ui.theme.playLightTheme
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val DROP_WRIST_KEEP_SCREEN_TAG = "TITLE_KEEP_SCREEN_ON"

/**
 * 播放页（核心）：
 * - 顶部：歌名跑马灯（过长滚动），下方艺人
 * - 中部：上一首 / 暂停(大、镂空、内嵌旋转封面) / 下一首
 * - 底部沿弧：喜欢 / 声音(唤出系统音量面板) / 更多(三点)
 * - 右缘进度弧（官方样式 53° 弧，弧带纵向拖拽 seek，防误触）
 * - 左划 → 歌词页；右划 → 栈底时切卡片主页（应用入口为播放页），否则返回上一页
 */
@Composable
fun PlayerScreen(nav: NavHostController) {
    // 手势控制开启时播放页强制亮屏，两层：
    // 1) FLAG_KEEP_SCREEN_ON：Wear OS 官方的常规息屏超时屏蔽；
    // 2) 窗口标题设成 TITLE_KEEP_SCREEN_ON：命中 OPPO system_server 垂腕息屏白名单
    //    （McuGestureController.isIngorePackageOrActivity 比对当前焦点窗口 tag）。
    // 离开本页 / 关闭手势时恢复原标题，只影响播放页。
    val gestureEnabled = com.netmusiclite.data.GesturePrefs.enabled
    val windowCtx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.DisposableEffect(gestureEnabled) {
        val win = windowCtx.findActivity()?.window
        val originalTitle = win?.attributes?.title
        var tagged = false
        if (gestureEnabled && win != null) {
            win.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val attrs = android.view.WindowManager.LayoutParams()
            attrs.copyFrom(win.attributes)
            attrs.title = DROP_WRIST_KEEP_SCREEN_TAG
            win.attributes = attrs
            tagged = true
            android.util.Log.d("WMusicGesture", "drop-wrist keep-screen tag on")
        }
        onDispose {
            win?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (tagged && win != null) {
                val attrs = android.view.WindowManager.LayoutParams()
                attrs.copyFrom(win.attributes)
                attrs.title = originalTitle
                win.attributes = attrs
                android.util.Log.d("WMusicGesture", "drop-wrist keep-screen tag off")
            }
        }
    }
    PlayToneScope { PlayerScreenContent(nav) }
}

/**
 * ★ 2026-10-07「封面模糊明暗对调」：页面整体包在 [PlayToneScope] 里 ——
 * 开关打开时本页（含墨色 / 卡片 / 强调色）按**另一套明暗**出画面，
 * 底图的打光目标由 CoverBackdrop 同口径翻转（见 AppearancePrefs.coverBlurToneFlip）。
 * 页面外的一切（主页 / 设置 / 列表）不受影响。
 */
@Composable
private fun PlayerScreenContent(nav: NavHostController) {
    // ★ 2026-10-17 用户口径「当在 m3e 主题选择取色或者纯黑背景时，播放器样式采用 Horologist」：
    //   新样式自包含在 ui/m3e/M3eHorologistPlayer.kt（三段式：文字信息 / 三键 + 进度环 / 操作行）。
    //   这里分支后直接 return —— 下面的原有布局**一个像素不动**：封面模糊背景、以及用户在
    //   「主题 → 播放器样式」里选「原有样式」时，照旧走原路径（唯一判定见 AppearancePrefs）。
    if (com.netmusiclite.data.AppearancePrefs.m3eHorologistPlayer) {
        com.netmusiclite.ui.m3e.M3eHorologistPlayer(nav)
        return
    }
    val m3e = com.netmusiclite.data.AppearancePrefs.isM3e
    val song = PlayerEngine.current
    val playing = PlayerEngine.isPlaying
    // ★ 2026-10-11 方屏（!isRoundScreen()）：标题/艺人与三键区整组下移 —— 用户口径
    //   「三大按钮以及标题名称艺人名称下移，直到上一首/下一首按钮落在屏幕 x 轴（水平中线）上」。
    //   三键 Row 中心原 = 88dp + 68dp/2 = 122dp → 目标 = uiH/2（屏幕物理中线，UI 坐标系自洽）；
    //   标题区随移同一增量 Δ = uiH/2 − 122dp（22dp + Δ = uiH/2 − 100dp）。
    //   圆屏两个值不变，一个像素不动。
    val squareScreen = !isRoundScreen()
    val uiH = screenHeightUiDp()
    val titleTop = if (squareScreen) uiH / 2 - 100.dp else 22.dp
    val controlsTop = if (squareScreen) uiH / 2 - 34.dp else 88.dp
    // ★ 2026-10-17 用户口径「material 主题下播放页歌名以及歌手名称下移动 25 像素并且放大百分之十」：
    //   25 物理像素按 density 折算（本机 2.0 → 12.5dp）；放大 10% 只作用于歌名/艺人两行字号。
    //   更新胶囊在标题上方，不跟着下移 —— 位移加在标题之前的 Spacer 上，不动整个 Column。
    val m3eTitleDrop = if (m3e) {
        with(androidx.compose.ui.platform.LocalDensity.current) { 25f.toDp() }
    } else 0.dp
    val titleFontSize = if (m3e) 15.4.sp else 14.sp
    val artistFontSize = if (m3e) 9.9.sp else 9.sp
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    // 右滑返回淡出引擎（跟手右移+非线性淡出+速度加成，无震动）
    // 返回行程 34dp（2026-09-13 二次下调 40%：70dp → 56dp → 34dp）
    val swipePx = with(androidx.compose.ui.platform.LocalDensity.current) { 34.dp.toPx() }
    val backFader = remember(swipePx) { SwipeBackFader(swipePx, scope) }
    // 滑出目标 = 屏宽（松手速度接续滑出用）
    // ⚠ screenWidthUiDp = UI 坐标系屏宽；不能用 LocalConfiguration.screenWidthDp
    //   （密度被等比缩放后它不再等于屏宽，见 Common.kt 的注释）
    val screenPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        com.netmusiclite.ui.components.screenWidthUiDp().toPx()
    }
    var swipeLifted by remember { mutableStateOf(false) } // 右滑期间置顶，返回时下层接场无空白帧
    var showArtistPicker by remember { mutableStateOf(false) }
    var artistOptions by remember { mutableStateOf<List<ArtistItem>>(emptyList()) }

    // 更新提示（2026-10-02 用户口径）：每次启动静默检查 → 新版则顶部弹胶囊；
    // 2026-10-03 收紧：同一版本只弹一次、点一次即消失（UpdatePrompt 持久化记录）。
    val updateInfo by UpdateChecker.available.collectAsState()
    val updateFlowState by UpdateFlow.state.collectAsState()
    val installedVersionName = remember { installedVersionNameOf(ctx) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var pillClicked by remember { mutableStateOf(false) } // 本进程点过胶囊 → 立即隐藏
    val pillCode = updateInfo?.versionCode
    fun openUpdateDialog() {
        // 点胶囊 = 看过这次更新：该版本胶囊就此消失（本进程 + 下次启动）
        pillCode?.let {
            pillClicked = true
            UpdatePrompt.markClicked(ctx, it)
        }
        if (updateInfo != null) {
            showUpdateDialog = true
        } else {
            // 进程刚起、清单还没回来（胶囊由下载断点驱动）：补一次检查再开
            scope.launch {
                UpdateChecker.checkQuietly(ctx)
                if (UpdateChecker.available.value != null) {
                    showUpdateDialog = true
                } else {
                    android.widget.Toast.makeText(
                        ctx, "暂时获取不到更新信息，请稍后再试", android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }
    }

    // 表冠唤出音量面板（2026-09-25）：误触宽量——400ms 窗口内累计增量 ≥96px（约 1.5 格
    // 刻度）才触发，单格蹭动/口袋误转不弹面板；触发后 1s 冷却防连发。面板弹出后继续转
    // 冠由系统音量面板接管调节
    var volAccum by remember { mutableStateOf(0f) }
    var volAccumAt by remember { mutableStateOf(0L) }
    var volFiredAt by remember { mutableStateOf(0L) }
    // ★ 2026-10-03（华为 Watch 3 引入，同日扩到「非 ColorOS」全机型）：这批手表的系统音量
    //   面板要么不存在（华为官方音乐为此自绘音量条，见《华为音乐扫描》）、要么不响应
    //   音钮/表冠（三星魔改 Wear OS 2 / Wear OS 7 / 鸿蒙 4 实测「点了没反应」）——
    //   转冠改为**逐格直接调音量** + 应用内自绘音量条（InAppVolume）。一格一级、转完
    //   1.3s 自动隐去；自绘条轻，不需要误触宽量/冷却。每格像素取系统
    //   scaledVerticalScrollFactor（与刻度震动同口径，不猜厂商刻度）。
    //   ⚠ ColorOS 保持原路：转冠只唤出系统面板，后续由面板接管调节（分流见 DeviceCompat）。
    val hwDetentPx = remember(ctx) {
        android.view.ViewConfiguration.get(ctx).scaledVerticalScrollFactor.coerceAtLeast(1f)
    }
    // 误触匡量累积（「非 ColorOS」转冠进入音量界面用）：静默超过 ENTER_IDLE_MS 就从零重攒
    var volEnterAccum by remember { mutableStateOf(0f) }
    var volEnterAt by remember { mutableStateOf(0L) }
    fun onCrownForVolume(delta: Float) {
        // ★ 2026-10-18 用户口径「设置里加一个关闭播放页转动表冠调节音量的选项」：
        //   关闭后表冠事件仍由此处消费（不冒泡），但不做任何音量动作 —— 播放页手势
        //   不会因此变成"翻页/切歌"（nativeVolume 的其它分支本来就是音量专用）。
        if (com.netmusiclite.data.CrownVolumePrefs.disabled) return
        // ★ 2026-10-04 华为方向取反已**上收**到 MainActivity.onGenericMotionEvent（见其 KDoc）：
        //   上一版只在这条路径上取反，于是真机表现为「音量页方向正、主页列表方向反」——
        //   列表与音量共用同一个事件入口，补丁打在局部必然漏掉其它页面。
        //   现在源头统一出标准方向，这里直接消费，**不要再取反**（会双重取反又变反）。
        if (com.netmusiclite.data.DeviceCompat.useInAppVolume) {
            // 一格 = scaledVerticalScrollFactor 像素；整量程 = CROWN_DETENTS_FULL_RANGE 格。
            // ★ 无极：delta 按**行程比例**直接给出去、不取整 —— 界面逐帧连续，不跳格。
            val pxFullRange = hwDetentPx *
                com.netmusiclite.ui.components.InAppVolume.CROWN_DETENTS_FULL_RANGE
            if (!com.netmusiclite.ui.components.InAppVolume.visible) {
                // 未进入界面：先攒误触匡量，够了才「进入」并播进入动画
                //（播放页转冠是高频动作，单格蹭动/口袋误转不应该把音量界面唤醒）
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - volEnterAt >
                    com.netmusiclite.ui.components.InAppVolume.ENTER_IDLE_MS
                ) {
                    volEnterAccum = 0f
                }
                volEnterAt = now
                volEnterAccum += delta
                if (abs(volEnterAccum) <
                    hwDetentPx * com.netmusiclite.ui.components.InAppVolume.ENTER_DETENTS
                ) {
                    return
                }
                val carry = volEnterAccum
                volEnterAccum = 0f
                com.netmusiclite.ui.components.InAppVolume.enter(carry / pxFullRange)
            } else {
                com.netmusiclite.ui.components.InAppVolume.nudge(delta / pxFullRange)
            }
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - volAccumAt > 400L) volAccum = 0f // 停转超时重新累计
        volAccumAt = now
        if (now - volFiredAt < 1000L) return // 冷却期内吞掉
        volAccum += delta
        if (abs(volAccum) >= 96f) {
            volAccum = 0f
            volFiredAt = now
            val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            am.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.ADJUST_SAME,
                android.media.AudioManager.FLAG_SHOW_UI,
            )
        }
    }

    // 点艺人名：实时拉歌曲的 ar 数组 → 单艺人直达主页，多艺人弹胶囊选择列表
    fun openArtistPicker() {
        val s = song ?: return
        if (s.id <= 0) return  // 本地歌曲无艺人主页
        scope.launch {
            val list = runCatching { NcmApi.songArtists(s.id) }.getOrNull().orEmpty()
                .ifEmpty { if (s.artistId > 0) listOf(ArtistItem(s.artistId, s.artist, null)) else emptyList() }
            when {
                list.isEmpty() -> Unit
                list.size == 1 -> nav.navigateWithMotion(
                    Routes.artistDetail(list[0].id), NavMotionSpec(NavMotionKind.Pill),
                )
                else -> {
                    artistOptions = list
                    showArtistPicker = true
                }
            }
        }
    }

    // 栈底右滑 = 前进导航到主页（装成返回）：手势路径与系统 back 路径共用同一条动作
    //（2026-10-03 抽出 —— 华为 Watch 3 的系统 back 落到栈底播放页时也要播这一段）。
    fun rootBackToHome() {
        if (m3e) {
            nav.navigateWithMotion(Routes.HOME, NavMotionSpec(NavMotionKind.Slide))
            return
        }
        // ★ 2026-10-01：栈底没有上一页可 pop，只能前进导航到主页。
        //   但前进导航默认走 enter/exit 转场 —— 播放页会被 NavHost
        //   按「前进」方向往左滑出，而手指刚把它拖到右边，观感就是
        //   「往左飘」（只在冷启动后第一次发生，因为只有这时它在栈底）。
        //   现在武装手势旗标：NavHost 对它只做淡出、主页从左侧滑入，
        //   页面继续跟着 fader 向右滑出场 —— 和真正的手势返回一致。
        val ms = backFader.flingOutDuration(screenPx)
        TransitionCoordinator.withGesturePop(ms) {
            nav.navigateWithMotion(Routes.HOME, NavMotionSpec(NavMotionKind.Slide))
        }
        backFader.flingOut(screenPx, ms)
    }
    // 2026-10-03（华为 Watch 3）系统返回接管：系统右滑被系统吃掉触摸、直接派发 back ——
    // 栈底播放页的 back 注册到这里，走与内部右划完全相同的「回主页」动作（含收场动画）。
    val rootExit by androidx.compose.runtime.rememberUpdatedState { rootBackToHome() }
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.netmusiclite.ui.nav.SystemBackRouter.playerRootExit = { rootExit() }
        onDispose { com.netmusiclite.ui.nav.SystemBackRouter.playerRootExit = null }
    }

    // ★ 2026-10-03 表冠焦点修复（Wear OS 规范）：旋转输入是**全局硬件信号**，系统只把它
    //   投递给当前持有焦点的组件（官方《Compose 中的旋转输入》给的正是
    //   onRotaryScrollEvent + focusRequester + focusable 三件套）；页面根没有焦点时事件
    //   被系统直接丢弃 —— 真机表现就是「播放页转表冠完全没反应」（三星魔改 Wear /
    //   Wear OS 7 / 鸿蒙 4 实测）。这里让播放页根持有焦点，rotaryCustom 才拿得到表冠增量。
    //   （本页没有输入框等抢焦点的控件，请求焦点不会影响其它交互。）
    val volFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { volFocus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(volFocus)
            .focusable()
            // ★ 2026-10-17（本轮）恢复 1.24-r2 的播放页自带环境色底：取色风格下转场中
            //   页面保持不透明环境色（m3e 下 ambientSurfaceColor() 恒为 Transparent，零影响）。
            .background(ambientSurfaceColor())
            .zIndex(if (swipeLifted) 2f else 0f)
            // 右滑返回跟手缩放+非线性淡出（速度感应、无震动；2026-10-01 由跟手右移改为缩放）
            // Material 的页面缩放、裁切及右滑返回统一由 Wear 导航宿主完成。
            .graphicsLayer {
                if (!m3e) {
                    alpha = backFader.alpha
                    translationX = backFader.slidePx
                    scaleX = backFader.scale
                    scaleY = backFader.scale
                    // 2026-10-02：缩放圆心 = 本页入场时的点击位置（原路返回）
                    transformOrigin = backFader.origin
                }
            }
            .m3eSwipeLeft(if (m3e) ({ nav.navigateWithMotion(Routes.LYRICS, NavMotionSpec(NavMotionKind.Slide)) }) else null)
            .pointerInput(m3e) {
                if (m3e) return@pointerInput
                val px = 70.dp.toPx()
                var leftX = 0f
                var rightX = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        leftX = 0f
                        rightX = 0f
                        backFader.onDragStart()
                        swipeLifted = true
                    },
                    onDragEnd = {
                        val back = backFader.passedThreshold
                        backFader.onDragEnd(back)
                        if (back) {
                            // 右划：入口播放页（栈底）→ 切卡片主页；非入口 → 返回上一页
                            if (nav.previousBackStackEntry == null) {
                                rootBackToHome()
                            } else {
                                // 松手速度接续：fader 线性滑出全屏，转场时长同步武装给 NavHost
                                val ms = backFader.flingOutDuration(screenPx)
                                TransitionCoordinator.withGesturePop(ms) { nav.popBackStack() }
                                backFader.flingOut(screenPx, ms)
                            }
                        } else {
                            if (leftX > px) {
                                // 左划进歌词：滑动来源 → 保留滑动转场
                                nav.navigateWithMotion(Routes.LYRICS, NavMotionSpec(NavMotionKind.Slide))
                            }
                            swipeLifted = false
                        }
                    },
                    onDragCancel = {
                        backFader.onDragCancel()
                        swipeLifted = false
                    },
                ) { change, amount ->
                    change.consume()
                    backFader.onDrag(change, amount) // 左移增量引擎内部清零并淡回
                    rightX = (rightX + amount).coerceAtLeast(0f)
                    if (amount < 0) leftX += -amount else leftX = 0f
                }
            }
            .pointerInput(Unit) {
                var totalY = 0f
                // 下滑进评论页；右缘弧带内的纵向拖拽被弧 seek 消费，不会误触
                detectVerticalDragGestures(
                    onDragStart = { totalY = 0f },
                    onDragEnd = {
                        val cur = PlayerEngine.current
                        if (totalY > 70.dp.toPx() && cur != null) {
                            // 下滑进评论：竖向滑动来源（2026-10-03 用户要求）——
                            // 评论页从上方往下滑入屏幕、播放页同向往下滑出；曲线/时长不变
                            nav.navigateWithMotion(
                                Routes.comments(cur.id), NavMotionSpec(NavMotionKind.SlideV),
                            )
                        }
                    },
                ) { change, amount ->
                    change.consume()
                    totalY += amount
                }
            }
            // 表冠：累计超过误触宽量唤出系统音量面板（含通用滚轮通道）
            // ★ scope = Volume：只有这一处是「音量」场景，方向走设置里的「音量页」开关；
            //   其余界面（列表、向导翻卡…）一律走「列表页」开关（用户口径 2026-10-04）。
            .rotaryCustom(
                scope = com.netmusiclite.ui.components.RotaryScope.Volume,
                onDelta = { onCrownForVolume(it) },
            ),
    ) {
        // 默认主题背景由 AppRoot 全局层负责；m3e（含 LEGACY 播放页样式）仍由页面自带。
        if (com.netmusiclite.data.AppearancePrefs.isM3e) {
            com.netmusiclite.ui.components.NcmBackground(route = Routes.PLAYER, prepareResources = false)
        }
        // 右缘进度弧（绘制期读进度：500ms tick 只重绘弧线，本页不重组）
        EdgeProgressRing(
            progress = {
                val d = PlayerEngine.durationMs.coerceAtLeast(1L)
                (PlayerEngine.positionMs.toFloat() / d).coerceIn(0f, 1f)
            },
            onSeek = if (song == null) null else { p ->
                PlayerEngine.seekTo((p * PlayerEngine.durationMs).toLong())
            },
            modifier = Modifier.fillMaxSize(),
            // ★ 2026-10-06 用户口径：设了自定义壁纸时，取色 / 封面模糊这两条风格下的
            //   右缘进度弧改跟**专辑封面主题色**（SongAccent）；自定义风格（透壁纸）跟壁纸。
            //   ★ 2026-10-07 明暗对调：走 PlaySongAccent / PlayAccent（作用域外 = 原值）
            accent = if (com.netmusiclite.data.AppearancePrefs.playShowsWallpaper) {
                PlayAccent
            } else {
                PlaySongAccent
            },
        )

        // 睡眠定时倒计时（右上角小字，定时关闭后自动消失）
        if (com.netmusiclite.data.SleepTimer.mode != com.netmusiclite.data.SleepMode.OFF) {
            com.netmusiclite.ui.screens.player.SleepCountdownLabel(
                fontSize = 9.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 14.dp, end = 16.dp),
            )
        }

        // 标题区（顶部固定；2026-09-06 上移 20px；★ 2026-10-11 方屏随三键整组下移，见 titleTop）
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = titleTop),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 更新胶囊（「发现新版本」）：每个版本只弹一次、点一次即消失（2026-10-03）；
            // 下载/授权/失败等过程态交给更新弹窗，不再占用胶囊。
            // lastPillLabel 兜住淡出期间的文案（状态清空时内容 lambda 会以 null 重组）
            val pillShown = pillCode != null && updateFlowState is UpdateFlow.State.Idle && !pillClicked
            LaunchedEffect(pillShown, pillCode) {
                // ★ 编译警告清理（2026-10-17，K2 恒真条件）：pillShown 的定义本身已蕴含
                //   pillCode != null，重复判空会被报 "Condition is always 'true'"。
                //   先取非空值、再判 pillShown —— 行为完全等价（原布局零改动）。
                val code = pillCode ?: return@LaunchedEffect
                if (pillShown) UpdatePrompt.markShown(ctx, code)
            }
            val pillLabel = if (pillShown && UpdatePrompt.visible(ctx, pillCode ?: 0)) {
                updatePillLabel(updateInfo?.versionName)
            } else null
            var lastPillLabel by remember { mutableStateOf("") }
            if (pillLabel != null && pillLabel != lastPillLabel) lastPillLabel = pillLabel
            AnimatedVisibility(
                visible = pillLabel != null,
                enter = if (m3e) fadeIn(tween(200)) else fadeIn(tween(200)) + scaleIn(initialScale = 0.7f, animationSpec = tween(220)),
                exit = if (m3e) fadeOut(tween(140)) else fadeOut(tween(140)) + scaleOut(targetScale = 0.8f, animationSpec = tween(140)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    UpdateHintPill(lastPillLabel) { openUpdateDialog() }
                    Spacer(Modifier.height(6.dp))
                }
            }
            // material 主题：歌名/艺人与更新胶囊之间留出 25 物理像素（见 m3eTitleDrop）
            if (m3eTitleDrop > 0.dp) Spacer(Modifier.height(m3eTitleDrop))
            Box(Modifier.width(if (m3e) 112.dp else 100.dp), contentAlignment = Alignment.Center) {
                Text(
                    song?.title ?: "未在播放",
                    fontSize = titleFontSize, fontWeight = FontWeight.Bold, color = PlayTextPrimary,
                    maxLines = 1,
                    modifier = Modifier
                        .basicMarquee()
                        .then(
                            if (song != null) Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                nav.navigateWithMotion(
                                    Routes.songBaikePage(song.id), NavMotionSpec(NavMotionKind.Pill),
                                )
                            }
                            else Modifier
                        ),
                )
            }
            Spacer(Modifier.height(2.dp))
            // 艺人名可点：单艺人直达艺人主页，多艺人弹胶囊列表（见 ArtistPickerOverlay）
            Text(
                song?.artist ?: "点任意歌曲开始",
                fontSize = artistFontSize, color = PlayTextSecondary, maxLines = 1,
                modifier = Modifier
                    .padding(horizontal = 60.dp)
                    .then(
                        if (song != null) Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { openArtistPicker() }
                        else Modifier
                    ),
            )
            val err = PlayerEngine.errorMsg
            if (err != null) {
                Spacer(Modifier.height(4.dp))
                Text(err, fontSize = 9.sp, color = PlayAccent, textAlign = TextAlign.Center)
            }
            // 「正在获取播放地址…」提示已移除（2026-09-25）：切歌静默加载，不打扰
        }

        // 三键区（固定偏移，位于中上部，给弧形底栏让位）
        // 2026-09-13 对齐参考样式：上一首/下一首为裸图标（无玻璃圆底），中央暂停键细圈+暗化封面；
        // 位置按用户口径：整体下移 20px（=10dp），左右两键各向外 25px（=12.5dp）
        // ★ 2026-10-11 方屏：行中心从 122dp 改到屏幕水平中线（controlsTop = uiH/2 − 34dp），
        //   即「上一首/下一首按钮落在屏幕 x 轴上」；圆屏仍 88dp 不动。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = controlsTop),
        ) {
            if (m3e) {
                com.netmusiclite.ui.m3e.M3ePlayerControlButton(NcmIcons.Prev, "上一首", { PlayerEngine.prev() })
            } else Pressable(onClick = { PlayerEngine.prev() }) {
                Icon(NcmIcons.Prev, contentDescription = "上一首", tint = PlayTextPrimary, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(if (m3e) 15.5.dp else 22.5.dp))
            PauseButton(playing = playing, song = song) { PlayerEngine.toggle() }
            Spacer(Modifier.width(if (m3e) 15.5.dp else 22.5.dp))
            if (m3e) {
                com.netmusiclite.ui.m3e.M3ePlayerControlButton(NcmIcons.Next, "下一首", { PlayerEngine.next() })
            } else Pressable(onClick = { PlayerEngine.next() }) {
                Icon(NcmIcons.Next, contentDescription = "下一首", tint = PlayTextPrimary, modifier = Modifier.size(30.dp))
            }
        }

        // 底部弧形三按钮
        ArcBottomBar(
            liked = song != null && PlayerEngine.isLiked(song.id),
            onLike = { song?.let { PlayerEngine.toggleLike(it) } },
            onVolume = {
                if (com.netmusiclite.data.DeviceCompat.useInAppVolume) {
                    // 非 ColorOS：自绘音量条（后续转冠 / 条上竖向拖动调节全走应用内，
                    // 见 onCrownForVolume 与 InAppVolume.VolumeBar）
                    com.netmusiclite.ui.components.InAppVolume.show()
                } else {
                    // 唤出系统音量调节面板（STREAM_MUSIC，行为/表冠/滑块全部跟系统走）
                    val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                    am.adjustStreamVolume(
                        android.media.AudioManager.STREAM_MUSIC,
                        android.media.AudioManager.ADJUST_SAME,
                        android.media.AudioManager.FLAG_SHOW_UI,
                    )
                }
            },
            onMore = { nav.navigateWithMotion(Routes.MORE) },
        )

        // 多艺人选择浮层（压暗背景；右滑或点背景退回播放页）
        if (showArtistPicker) {
            ArtistPickerOverlay(
                artists = artistOptions,
                onPick = { id ->
                    showArtistPicker = false
                    nav.navigateWithMotion(
                        Routes.artistDetail(id), NavMotionSpec(NavMotionKind.Pill),
                    )
                },
                onDismiss = { showArtistPicker = false },
            )
        }

        // ★ 2026-10-06 长按暂停键 1.7s 唤出的长条进度条浮层（高斯模糊遮挡 + Accent 主题色，
        //   右滑退出）：盖在播放页全部内容之上（本 Box 最后一个子项 = 最顶层）
        com.netmusiclite.ui.components.PlayerSeekOverlayHost()

        // 更新弹窗（胶囊点开；下载/校验/安装全程共用 UpdateFlow 状态）
        val dialogInfo = updateInfo
        if (showUpdateDialog && dialogInfo != null) {
            UpdateAvailableDialog(
                info = dialogInfo,
                installedVersionName = installedVersionName,
                onStart = { UpdateFlow.start(ctx, dialogInfo) },
                onInstallNow = { UpdateFlow.installLocal(ctx) },
                onOpenPermission = { UpdateFlow.gotoInstallPermission(ctx) },
                onDismiss = { showUpdateDialog = false },
            )
        }
    }
}

/**
 * 多艺人选择浮层：压暗背景 + 玻璃胶囊纵向排列，点胶囊进对应艺人主页；
 * 右滑（SwipeBackBox 阈值）或点背景任意处退回播放页；系统 back 同样先关本浮层。
 * 纵向拖拽在此层被吞掉，不会穿透触发播放页的下滑进评论页手势。
 *
 * ★ 2026-10-17：可见性由 private 放宽到 internal —— m3e 的 Horologist 播放页
 *   （ui/m3e/M3eHorologistPlayer.kt）复用同一浮层，保证两套播放页的艺人选择交互一致。
 *
 * ★ 2026-10-23（task-8）「m3e 播放页点多艺人歌曲 → 二级菜单移位」修复：内容定位基准改为
 *   **屏幕居中**，不再继承 [SwipeBackBox] 内部内容层的对齐方式（根因与修法见函数体内
 *   `Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center)` 上方注释）。
 *   经典主题 / m3e 主题、原有播放器 / Horologist 播放器四个组合下位置完全一致。
 */
@Composable
internal fun ArtistPickerOverlay(
    artists: List<ArtistItem>,
    onPick: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    // 系统 back（含华为式被系统吃掉的右滑）：先关本浮层，不动导航（2026-10-07 同批修复）
    OverlayBackHandler { onDismiss() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(
                alpha = if (playLightTheme) 0.32f else 0.55f
            ))
            .pointerInput(Unit) {
                detectVerticalDragGestures { change, _ -> change.consume() }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        SwipeBackBox(onBack = onDismiss) {
            // ★ 2026-10-23（task-8）「m3e 播放页多艺人二级菜单移位」：定位基准改为**屏幕**。
            //
            // 根因链（两套 SwipeBackBox 实现不同，见 Common.kt 的 isM3e 分支）：
            //  · 经典主题：SwipeBackBox = Box(fillMaxSize) → 内层 Box(graphicsLayer{…})，
            //    内层**没有尺寸修饰符**（wrap-content），在外层 Box 里按默认 TopStart 摆放；
            //  · m3e 主题：SwipeBackBox = Wear 的 SwipeToDismissBox（BasicSwipeToDismissBox；
            //    1.5.6 反汇编确认：根 Box 用 Alignment.TopStart，前景内容层是 fillMaxSize +
            //    graphicsLayer{scale/translationX} 的过渡层）。
            //  两套实现都不会把子内容垂直居中 ⇒ 本浮层的胶囊列一直从 **y=0** 起排（贴屏幕顶）。
            //  同样的贴顶在经典播放页落差不大（艺人名在 y≈44dp），但 m3e Horologist 播放页的
            //  艺人名在顶段**底对齐**（topH≈76.5dp，艺人名 y≈61~74dp），点击后菜单却出现在屏幕
            //  最上方 —— 用户看到的就是「二级菜单移位」。
            //
            // 修法：在 SwipeBackBox 的内容侧自带一层「全屏尺寸 + Center」的基准容器。
            //  位置只由屏幕尺寸决定，不依赖父容器的测量/对齐，也不依赖 m3e 转场缩放层的坐标
            //  ⇒ 经典主题 / m3e 主题 × 原有播放器 / Horologist 播放器 四个组合完全一致。
            //  该层不消费触摸（没有 pointerInput / clickable）：压暗层点击关闭与右滑关闭手势照旧，
            //  点胶囊进艺人主页也照旧。
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp),
                ) {
                    Text("查看艺人主页", fontSize = 9.sp, color = PlayTextSecondary)
                    artists.forEach { a ->
                        Text(
                            a.name,
                            fontSize = 12.sp,
                            color = PlayTextPrimary,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(50))
                                // 跟随卡片材质（玻璃 / 黑色半透明）
                                .frostedGlass(RoundedCornerShape(50))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { onPick(a.id) }
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 大暂停键：镂空圆环 + 内嵌旋转封面（暂停时覆盖播放键）
 *
 * ★ 2026-10-06 长按 0.5s 唤出长条进度条浮层（用户口径；首版 1.7s，同日按用户要求改 0.5s）：
 *  · Pressable 的 clickable 与长按检测会双响应 —— 改为自绘按压缩放（PressMotion 同款参数）
 *    + detectTapGestures 单一手势源：onPress 里用 withTimeoutOrNull(500) { awaitRelease() }
 *    判定 —— 正常抬起返回 Unit（click 语义交给 onTap）、超时返回 null（长按成立 → 长震一记
 *    + 唤出浮层）；手势取消（拖出控件/被右滑返回消费）抛 CancellationException，runCatching
 *    兜住，两条路径都不触发；
 *  · 长按成立后松手时的 onTap 用 longPressFired 拦掉 —— 避免「唤出浮层的同时又切了一下暂停」。
 */
@Composable
private fun PauseButton(playing: Boolean, song: com.netmusiclite.data.Song?, onClick: () -> Unit) {
    if (com.netmusiclite.data.AppearancePrefs.isM3e) {
        com.netmusiclite.ui.m3e.M3ePlayerCoverButton(playing, song, onClick)
        return
    }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    var longPressFired by remember { mutableStateOf(false) }
    val s by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (pressed) com.netmusiclite.ui.components.PressMotion.SCALE else 1f,
        animationSpec = com.netmusiclite.ui.components.PressMotion.spec(pressed),
        label = "pausePress",
    )
    Box(
        Modifier
            .graphicsLayer {
                scaleX = com.netmusiclite.ui.components.PressMotion.stretchX(s)
                scaleY = com.netmusiclite.ui.components.PressMotion.stretchY(s)
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        longPressFired = false
                        // Success(Unit) = 短按抬起（走 onTap）；Failure（取消）不触发
                        val result = runCatching<Unit?> {
                            kotlinx.coroutines.withTimeoutOrNull(500L) { awaitRelease() }
                        }
                        pressed = false
                        // Success(null) = 按住满 0.5s 未松手 → 长按成立 → 长震一记 + 唤出浮层
                        if (result.isSuccess && result.getOrNull() == null) {
                            longPressFired = true
                            haptic.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress,
                            )
                            com.netmusiclite.ui.components.PlayerSeekOverlay.show()
                        }
                    },
                    onTap = { if (!longPressFired) onClick() },
                )
            }
            .size(68.dp)
            .clip(CircleShape)
            .border(3.dp, Color.White.copy(alpha = 0.9f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        RotatingCover(song, 62.dp, playing)
        // 暂停遮罩只在「真暂停」时出现：切歌加载间隙（loading=true）不闪暂停图标
        if (!playing && !PlayerEngine.loading) {
            // 对齐参考样式：暂停时封面暗化，白播放键浮于其上
            Box(
                Modifier.size(62.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(NcmIcons.Play, contentDescription = "播放", tint = Color.White, modifier = Modifier.size(27.dp))
            }
        }
    }
}

/**
 * 底部弧形操作区：一条贴合屏幕弧度的弯曲胶囊整体包裹 喜欢/音量/更多 三个按钮。
 * 按钮只留图标悬浮（无各自玻璃圆底，避免与小胶囊互相重叠），仅保留这一条外部大胶囊。
 * 面积约为上一版的 71%（缩小 29%≈30%）：带高 40dp，带中心半径 88dp，弧段 90°±30°，圆头。
 *
 * W5 流畅度（2026-09-30）：Canvas 条带化（同 EdgeProgressRing 方案）——旧版 fillMaxSize 的
 * 全屏 RenderNode 即使只画一条弧，合成/重录也按整屏算（实测播放页静止帧 GPU 中位 12ms）。
 * 收缩到弧带包围盒 200×112dp（466px/2x 屏 ≈ 全屏 41%，实际绘制像素更少），扫渐变光栅化面积随之缩小。
 * 弧带 y 范围（相对圆心向下）：端帽顶 88·cos60°−20 = 24dp → 底点 88+20 = 108dp。
 *
 * ★ 2026-10-11 方屏（!isRoundScreen()）：弯曲胶囊拉直成水平 stadium —— 用户口径
 *   「所有播放页底边栏由曲线胶囊变为直的胶囊，并且距离屏幕底边 20 像素」。
 *   高 40dp（同弧带 stroke）、半长 64dp（= 88·sin30° + 20 端帽，与弧带可视外沿同长），
 *   三按钮仍居胶囊中线（水平偏移 ±34.4dp = 88·sin23°）；整条胶囊底边距屏底 20 物理像素。
 *   圆屏走原弧带分支，一个像素不变。
 */
@Composable
private fun ArcBottomBar(
    liked: Boolean,
    onLike: () -> Unit,
    onVolume: () -> Unit,
    onMore: () -> Unit,
) {
    val m3e = com.netmusiclite.data.AppearancePrefs.isM3e
    val matchedCoverGlass = com.netmusiclite.ui.components.playerCoverGlassMatchesBackground()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cx = maxWidth / 2
        val cyc = maxHeight / 2
        // ★ 2026-10-17 用户口径「m3e 播放页最下方统一为一个有弧度贴合屏幕的胶囊」：
        //   弧带半径不再写死 88dp，而是**跟着屏幕弧度算**：外沿 = 屏半径 − 7dp，
        //   描边粗 40dp ⇒ 中心线半径 = 外沿 − 20dp。233dp 基准屏上 = 89.5dp（原来是 88），
        //   换到 198dp / 240dp 的表时同样贴着各自的表盘边走，不会浮在中间。
        val rBand = (minOf(maxWidth, maxHeight) / 2 - 7.dp - 20.dp).coerceAtLeast(60.dp)

        // ★ 2026-10-01：弧形胶囊**跟随设置页的「玻璃卡片 / 黑色半透明卡片」**。
        //   玻璃 = 白 10%（原来固定的那档，透出模糊底的观感）；
        //   黑色半透明 = 黑 45%（与 frostedGlass() 黑分支取同一档，全站观感一致）。
        //   注意判定条件与 frostedGlass 完全对齐：★ 2026-10-07 起叠 wallpaperShown()
        //   —— 壁纸没在画（本页是取色/封面模糊底）时 frostedGlass 走「无图」分支，
        //   弧带也必须跟着走黑，不能还按「有壁纸」透白。
        // ★ 2026-10-18 用户口径「深色模式没有选择自定义图片时的假玻璃同步为高斯模糊玻璃」：
        //   旧判据只看「壁纸是否在画」，无壁纸就一律黑 45%（= 一块贴在模糊封面上的黑胶布，
        //   与同屏卡片「从模糊封面取样」的材质完全不是一件事）。
        //   深色 + 无壁纸时，页面底是封面取色、身后可用取样底就是模糊封面
        //   （NcmBackground 的 darkCardCoverBlur：卡片已经在取它），胶囊跟着取同一张底，
        //   两者材质就对齐了 —— 玻璃模式下画白 10% 透出模糊底，不再画黑。
        val darkCoverGlass = !playLightTheme &&
            com.netmusiclite.ui.components.coverBlurSampleImage() != null
        val glassCard = m3e || (com.netmusiclite.data.BackgroundStore.cardGlass &&
            (com.netmusiclite.ui.components.wallpaperShown() || darkCoverGlass))
        // ★ 2026-10-07 明暗对调：作用域内的明暗读 playLightTheme（播放器组整页翻色）
        val light = playLightTheme
        val arcBandColor: Color = when {
            // ★ 2026-10-17 用户口径「material 主题的播放页最下方的三个按钮胶囊复用默认主题的样式」：
            //   m3e 走同一套弧形/直条胶囊与裸图标按钮（下方分支已合并），只是底材取 m3e 的
            //   glass 色（#1C1C1C）—— 默认主题那档「黑 45%」压在 m3e 的纯黑播放页上等于看不见胶囊。
            m3e -> Color.White.copy(alpha = 0.16f)
            // ★ 2026-10-01 浅色主题：弧带底在浅色页面上必须是浅色，
            //   取白 55% 与 frostedGlass() 的浅色分支同一档
            light -> Color.White.copy(alpha = 0.55f)
            glassCard -> Color.White.copy(alpha = 0.10f)
            else -> Color.Black.copy(alpha = 0.45f)
        }

        // 方屏直胶囊几何：高 40dp、半长 64dp；底边距屏底 20 物理 px（用户口径按 density 折算，
        // 先例 StackedCardList 标题上移 10px），三按钮中线 = 胶囊中心线
        val square = !isRoundScreen()
        val lift = with(LocalDensity.current) { 20f.toDp() }
        val capH = 40.dp
        val capHalfLen = 64.dp
        val squareBandCy = maxHeight - lift - capH / 2

        // 弧带局部 Canvas：宽 200dp 高 112dp，Canvas 顶 = 圆心（局部圆心 (100dp, 0)）。
        // 弧段 60°~120° 全在圆心下方：端帽顶圆心下 24dp、底点 108dp（+cap/余量 110dp），
        // 实际绘制像素 x ∈ [4, 196]、y ∈ [24, 110] 全在界内 —— Canvas 外的圆部分不产生任何像素。
        // （旧版 fillMaxSize 的全屏 RenderNode 即使只画一条弧，合成/重录也按整屏算）
        val bandW = 200.dp
        val bandH = 112.dp
        val bandCx = 100.dp
        val bandCy = 0.dp
        if (square) {
            // 方屏：水平 stadium 直胶囊（圆头粗线 = 两端半圆帽），底边距屏幕底边 lift
            Canvas(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = lift)
                    .width(capHalfLen * 2)
                    .height(capH),
            ) {
                val cy2 = size.height / 2f
                val glassPath = androidx.compose.ui.graphics.Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(
                        androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height),
                        androidx.compose.ui.geometry.CornerRadius(size.height / 2f)))
                }
                val sampled = m3e && drawPlayerGaussianGlass(
                    Offset((maxWidth.toPx() - size.width) / 2f, maxHeight.toPx() - lift.toPx() - size.height),
                    Size(maxWidth.toPx(), maxHeight.toPx()), glassPath,
                    listOf(Offset(maxWidth.toPx() / 2f, maxHeight.toPx() - lift.toPx() - capH.toPx() / 2f)))
                if (!sampled) drawLine(
                    arcBandColor,
                    Offset(size.height / 2f, cy2), Offset(size.width - size.height / 2f, cy2),
                    size.height, StrokeCap.Round,
                )
                // ★ 2026-10-17 用户口径「下方弯曲胶囊形状的栏目加一圈描边，随封面亮暗变化」：
                //   方屏直胶囊同样描边，颜色按胶囊所在位置的局部背景亮度反算（亮底深描边 / 暗底浅描边）。
                if (matchedCoverGlass) {
                    val capsuleShade = com.netmusiclite.ui.components.contrastShadeFor(
                        com.netmusiclite.ui.components.playerBackdropSrgbLuminanceAt(
                            maxWidth.toPx() / 2f,
                            maxHeight.toPx() - lift.toPx() - capH.toPx() / 2f,
                            maxWidth.toPx(), maxHeight.toPx(),
                        ),
                    )
                    drawPath(glassPath, capsuleShade.stroke, style = Stroke(1.5f))
                }
                // 上缘高光：对应弧版 taperedArc 的内缘高光（平头 + 两端渐隐），仅玻璃模式
                if (glassCard && !matchedCoverGlass) {
                    val hlLen = 71.dp.toPx()
                    val fade = 12.dp.toPx()
                    val x0 = (size.width - hlLen) / 2f
                    drawLine(
                        Brush.horizontalGradient(
                            0f to Color.Transparent,
                            fade / hlLen to Color.White.copy(alpha = 0.10f),
                            1f - fade / hlLen to Color.White.copy(alpha = 0.10f),
                            1f to Color.Transparent,
                            startX = x0, endX = x0 + hlLen,
                        ),
                        Offset(x0, 0.75.dp.toPx()), Offset(x0 + hlLen, 0.75.dp.toPx()),
                        1.5.dp.toPx(), StrokeCap.Butt,
                    )
                }
            }
        } else {
            Canvas(
                Modifier
                    .offset {
                        // Canvas 左上 = (圆心x − bandW/2, 圆心y + bandCy)：圆心 = 屏幕中心（与旧全屏版完全同位）
                        IntOffset(
                            ((maxWidth / 2) - bandW / 2).roundToPx(),
                            ((maxHeight / 2) + bandCy).roundToPx(),
                        )
                    }
                    .width(bandW)
                    .height(bandH),
            ) {
                val stroke = 40.dp.toPx()
                val r = rBand.toPx()
                val cX = bandCx.toPx()
                val cY = bandCy.toPx()
                val tl = Offset(cX - r, cY - r)
                fun taperedArc(radiusPx: Float, widthPx: Float, startDeg: Float, sweepDeg: Float, fadeDeg: Float, color: Color) {
                    val arcTopLeft = Offset(cX - radiusPx, cY - radiusPx)
                    val center = Offset(cX, cY)
                    val tip0 = startDeg / 360f
                    val full0 = (startDeg + fadeDeg) / 360f
                    val full1 = (startDeg + sweepDeg - fadeDeg) / 360f
                    val tip1 = (startDeg + sweepDeg) / 360f
                    val brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        tip0 to Color.Transparent,
                        full0 to color,
                        full1 to color,
                        tip1 to Color.Transparent,
                        1f to Color.Transparent,
                        center = center,
                    )
                    drawArc(
                        brush = brush, startAngle = startDeg, sweepAngle = sweepDeg, useCenter = false,
                        topLeft = arcTopLeft, size = Size(radiusPx * 2, radiusPx * 2),
                        style = Stroke(widthPx, cap = StrokeCap.Butt),
                    )
                }
                // 主体弧带：圆头胶囊（90°±30°）—— 颜色跟随卡片材质（见上方 arcBandColor）
                val androidArc = android.graphics.Path().apply {
                    addArc(android.graphics.RectF(tl.x, tl.y, tl.x + r * 2f, tl.y + r * 2f), 60f, 60f)
                }
                val filledArc = android.graphics.Path()
                android.graphics.Paint().apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = stroke
                    strokeCap = android.graphics.Paint.Cap.ROUND
                }.getFillPath(androidArc, filledArc)
                val sampled = m3e && drawPlayerGaussianGlass(
                    Offset((maxWidth.toPx() - bandW.toPx()) / 2f, maxHeight.toPx() / 2f + bandCy.toPx()),
                    Size(maxWidth.toPx(), maxHeight.toPx()), filledArc.asComposePath(),
                    // 弧带很宽（圆屏 60°~120°）：沿弧取 3 点平均，避免用单点亮度代表整条胶囊
                    listOf(70f, 90f, 110f).map { deg ->
                        val rad = Math.toRadians(deg.toDouble())
                        Offset(
                            maxWidth.toPx() / 2f + (r * cos(rad)).toFloat(),
                            maxHeight.toPx() / 2f + (r * sin(rad)).toFloat(),
                        )
                    })
                if (!sampled) drawArc(
                    color = arcBandColor,
                    startAngle = 60f, sweepAngle = 60f, useCenter = false,
                    topLeft = tl, size = Size(r * 2, r * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
                // ★ 2026-10-17 用户口径「下方弯曲胶囊形状的栏目加一圈描边，随封面亮暗变化」：
                //   描边沿胶囊的实际轮廓（getFillPath 的外沿）画 1.5px；颜色按弧带位置的局部背景算。
                if (matchedCoverGlass) {
                    val capsuleShade = com.netmusiclite.ui.components.contrastShadeFor(
                        listOf(70f, 90f, 110f).map { deg ->
                            val rad = Math.toRadians(deg.toDouble())
                            com.netmusiclite.ui.components.playerBackdropSrgbLuminanceAt(
                                maxWidth.toPx() / 2f + (r * cos(rad)).toFloat(),
                                maxHeight.toPx() / 2f + (r * sin(rad)).toFloat(),
                                maxWidth.toPx(), maxHeight.toPx(),
                            )
                        }.average().toFloat(),
                    )
                    drawPath(filledArc.asComposePath(), capsuleShade.stroke, style = Stroke(1.5f))
                }
                // 胶囊上缘（内缘）高光：平头+两端渐隐，端头不再出现小台阶。
                // 只在玻璃模式下画 —— 黑色卡片上再压一道白色高光会立刻露馅。
                if (glassCard && !matchedCoverGlass) {
                    val rInner = r - stroke / 2 - 0.75.dp.toPx()
                    taperedArc(rInner, 1.5.dp.toPx(), 58f, 64f, 10f, Color.White.copy(alpha = 0.10f))
                }
            }
        }

        val angles = listOf(-23f, 0f, 23f)
        val icons = listOf(
            if (liked) NcmIcons.HeartFill else NcmIcons.Heart,
            NcmIcons.Volume,
            NcmIcons.MoreDots,
        )
        val actions = listOf(onLike, onVolume, onMore)
        angles.forEachIndexed { i, deg ->
            val rad = Math.toRadians(deg.toDouble())
            val x = cx + rBand * sin(rad).toFloat()
            // 方屏：三按钮同高（直胶囊中线）；圆屏：沿弧半径分布（±23° 处略高于中点）
            val y = if (square) squareBandCy else cyc + rBand * cos(rad).toFloat()
            // ★ 2026-10-17 用户口径「material 主题的播放页最下方的三个按钮胶囊复用默认主题的样式」：
            //   去掉 m3e 的 32dp FilledTonalIconButton 分支，两套主题共用同一组裸图标按钮
            //   （无各自圆底，只留外面那一条胶囊），位置/尺寸/触控面积全部与默认主题一致。
            CircleIconButton(
                icons[i], "操作", actions[i],
                modifier = Modifier.offset {
                    IntOffset((x - 26.dp).roundToPx(), (y - 26.dp).roundToPx())
                },
                size = 52.dp, iconSize = 20.dp,
                container = Color.Transparent,
                // ★ 2026-10-07 用户口径：取色 / 封面模糊风格下点赞的实心红心改跟**专辑封面主题色**
                //   （SongAccent）—— 这两种风格页面底跟着歌走，心却跟壁纸 = 换歌时底变了、心没变。
                //   自定义风格（页面透壁纸）仍然跟壁纸（Accent），与页面底一致 ——
                //   与右缘进度弧 / 歌词墨色 / 长按进度条同一条规则。
                tint = if (icons[i] == NcmIcons.HeartFill) {
                    if (com.netmusiclite.data.AppearancePrefs.playShowsWallpaper) PlayAccent
                    else PlaySongAccent
                } else PlayTextPrimary,
                navMotionKind = if (i == 2) NavMotionKind.Circle else null,
            )
        }
    }
}

/** Sample the shared dark-theme Gaussian cover through the capsule's actual silhouette. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlayerGaussianGlass(
    rootOffset: Offset,
    viewport: Size,
    mask: androidx.compose.ui.graphics.Path,
    /** 取样点（root 坐标）：胶囊/按钮所在位置的局部背景亮度，决定 15% 对比方向与幅度。 */
    samplePoints: List<Offset> = emptyList(),
): Boolean {
    if (com.netmusiclite.ui.components.playerCoverGlassMatchesBackground()) {
        // 2026-10-17 用户口径：胶囊材质亮度比亮色封面暗 15%、比暗色封面亮 15%。
        //   按样本点的**局部**背景亮度反算 shade（渐变色封面上整图平均值会让局部只剩 2~6%）。
        val local = if (samplePoints.isEmpty()) {
            com.netmusiclite.ui.components.playerBackdropSrgbLuminance()
        } else {
            samplePoints.map {
                com.netmusiclite.ui.components.playerBackdropSrgbLuminanceAt(
                    it.x, it.y, viewport.width, viewport.height,
                )
            }.average().toFloat()
        }
        clipPath(mask) {
            drawRect(com.netmusiclite.ui.components.contrastShadeFor(local).fill)
        }
        return true
    }
    val image = com.netmusiclite.ui.components.coverBlurSampleImage() ?: return false
    if (viewport.width <= 0f || viewport.height <= 0f) return false
    val sx = image.width / viewport.width
    val sy = image.height / viewport.height
    val left = (rootOffset.x * sx).toInt().coerceIn(0, image.width - 1)
    val top = (rootOffset.y * sy).toInt().coerceIn(0, image.height - 1)
    val width = (size.width * sx).toInt().coerceIn(1, image.width - left)
    val height = (size.height * sy).toInt().coerceIn(1, image.height - top)
    clipPath(mask) {
        drawImage(image,
            srcOffset = IntOffset(left, top),
            srcSize = androidx.compose.ui.unit.IntSize(width, height),
            dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()))
        drawRect(Color.Black.copy(alpha = 0.2f))
        drawRect(Color.White.copy(alpha = 0.13f))
    }
    return true
}
