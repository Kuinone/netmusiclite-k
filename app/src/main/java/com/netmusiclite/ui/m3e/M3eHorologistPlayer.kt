@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.netmusiclite.ui.m3e

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.pillStar
import androidx.graphics.shapes.toPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.ButtonGroup
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.netmusiclite.data.ArtistItem
import com.netmusiclite.data.CrashLogger
import com.netmusiclite.data.DeviceCompat
import com.netmusiclite.data.NcmApi
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.SleepMode
import com.netmusiclite.data.SleepTimer
import com.netmusiclite.ui.components.InAppVolume
import com.netmusiclite.ui.components.NcmBackground
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.PlayerSeekOverlay
import com.netmusiclite.ui.components.PlayerSeekOverlayHost
import com.netmusiclite.ui.components.RotaryScope
import com.netmusiclite.ui.components.UpdateAvailableDialog
import com.netmusiclite.ui.components.UpdateHintPill
import com.netmusiclite.ui.components.installedVersionNameOf
import com.netmusiclite.ui.components.isRoundScreen
import com.netmusiclite.ui.components.pressStretch
import com.netmusiclite.ui.components.rotaryCustom
import com.netmusiclite.ui.components.screenHeightUiDp
import com.netmusiclite.ui.components.updatePillLabel
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.NavMotionSpec
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.SystemBackRouter
import com.netmusiclite.ui.nav.navigateWithMotion
import com.netmusiclite.ui.screens.player.ArtistPickerOverlay
import com.netmusiclite.ui.screens.player.SleepCountdownLabel
import com.netmusiclite.update.UpdateChecker
import com.netmusiclite.update.UpdateFlow
import com.netmusiclite.update.UpdatePrompt
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * m3e 播放页的 **Horologist 样式**（2026-10-17 用户口径「当在 m3e 主题选择取色或者纯黑背景时，
 * 播放器样式采用 https://github.com/google/horologist」）。
 *
 * 第二轮（2026-10-17 用户参考截图 = Horologist media sample 的 **animated / M3 Expressive**
 * 版 PlayerScreen）：中键改「扇贝形播放键 + 外圈短弧进度」、底部改胶囊按钮行。
 * 第三轮（同日用户口径）：底部胶囊行下移 20 物理像素（上移量 20dp→10dp）；两侧上一首/下一首
 * 从大圆键改成 **56×76dp 竖长圆角矩形**（大倒角、长边竖直）。
 * 第五轮（同日用户口径）「两侧上一首/下一首的上下宽度缩窄 20%，接近方形」：侧键高
 * **76dp → 60.8dp（= 76 × 0.8）**，宽仍 56dp ⇒ **56×60.8dp**，竖向只比横向多 4.8dp，观感近方形。
 *
 * 布局照 google/horologist 的 `media/ui-material3` 官方实现（animated 版
 * AnimatedPlayPauseButton / AnimatedMediaControlButtons / ButtonGroupLayout + 基础版
 * PlayerScreen 的三段式骨架），三段式：
 *
 *  ```
 *  ┌──────────────────────────────┐
 *  │ ① 顶段（高 max(68dp,(屏高-80dp)/2)，内容底对齐）
 *  │      歌名（跑马灯/点击进百科）＋ 艺人（点击→艺人选择浮层）
 *  │      ＋ 更新胶囊 / 错误提示（同原播放页，保留）
 *  │ ② 中段（高 80dp，中心 = 屏幕中线）
 *  │      上一首(56×60.8 近方圆角)  [扇贝播放键 62dp + 外圈短弧进度 80dp 盒]  下一首(56×60.8)
 *  │ ③ 底段（weight 1）
 *  │      [♥ 52×40 胶囊] [音量胶囊] [更多胶囊]   ← 圆屏整行上移 10dp 避开弧边
 * └──────────────────────────────┘
 * ```
 *
 * 设计取舍（对官方的**有意偏离**，均有实际原因）：
 *  · **不显示封面**：官方媒体信息就是纯文字（TrackMediaDisplay），本页照此；
 *    封面旋转仍保留在「原有样式」档（用户可切回），新样式不再画封面。
 *  · **扇贝键**：[ScallopShape]（同目录 HorologistScallop.kt）用 graphics-shapes 的
 *    `RoundedPolygon.pillStar(10 瓣, innerRadiusRatio 0.8, 12dp 圆角)` 生成，primary 实底 +
 *    onPrimary 图标。官方在播放/暂停之间还有 Lottie 形变（扇贝↔圆）；本项目**不加 lottie
 *    依赖**（硬约束），形状固定扇贝、图标用 160ms Crossfade 切换。
 *  · **进度指示**：官方是沿扇贝轮廓的 RotatingWavyProgressIndicator（wavy）；按用户口径换成
 *    **普通短弧**（细 3dp、圆头、12 点起顺时针按进度增长），画在外圈 Canvas 上 ——
 *    进度在**绘制期**读 PlayerEngine.positionMs/durationMs（播放中 500ms 更新一次），
 *    只失效绘制、不触发整页重组（与右缘 EdgeProgressRing 同一性能口径）。
 *  · **底部行**：官方 buttons 槽由调用方给；本项目是 喜欢/音量/更多 三个 fixed 操作，
 *    改成 stadium 胶囊（surfaceContainerHigh 底 + onSurface 图标，喜欢选中态 primary）。
 *    宽度取 52dp（参考截图 56~64dp）是 233dp 圆屏底部弧区的几何上限，见调用点注释。
 *  · **ButtonGroup**：用 Wear M3 官方 ButtonGroup 摆三键（中键 weight 0.5 + minWidth 80dp、
 *    两侧 weight 1），按钮固定尺寸 + 全项目统一的 pressStretch 按压缩放做反馈 ——
 *    ButtonGroup 的 animateWidth 只改槽位宽度，固定尺寸按钮上看不出 expansion。
 *
 * **背景**：沿用本项目 NcmBackground（route=PLAYER）——纯黑背景 = 纯黑，取色背景 = 歌曲
 * 环境色；按设置「播放器样式」的生效规则，封面模糊背景也可显式选 Horologist（背景层仍画
 * 封面模糊）。纯黑背景不额外加封面模糊层（用户口径纯黑必须还是纯黑）。
 *
 * ⚠ **功能与手势必须与原有播放页完全一致**（硬需求，逐条对应 PlayerScreenContent）：
 * 歌名跑马灯点击进歌曲百科 / 艺人点击→多艺人选择浮层 / 更新胶囊 / 错误提示 / 睡眠倒计时 /
 * 右滑返回（交给 SwipeDismissableNavHost，本页不吞横向手势）/ 左滑进歌词 / 下滑进评论 /
 * 表冠音量（RotaryScope.Volume）/ 长按播放暂停键 → 长条进度条浮层 / 喜欢·音量·更多 /
 * 艺人浮层 / 退出转场。
 *
 * 本文件是**自包含**实现（不复用 PlayerScreenContent 的布局代码），原样式路径一个像素不动
 * （分支见 PlayerScreen.kt 的 PlayerScreenContent 开头）。两处共用的只有纯展示/工具组件：
 * ArtistPickerOverlay / SleepCountdownLabel / PlayerSeekOverlayHost / 更新胶囊与弹窗。
 */
@Composable
fun M3eHorologistPlayer(nav: NavHostController) {
    remember { CrashLogger.step("m3e horologist player compose") }
    val song = PlayerEngine.current
    val playing = PlayerEngine.isPlaying
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- 多艺人选择浮层（与 PlayerScreenContent.openArtistPicker 同源逻辑）----
    var showArtistPicker by remember { mutableStateOf(false) }
    var artistOptions by remember { mutableStateOf<List<ArtistItem>>(emptyList()) }

    fun openArtistPicker() {
        val s = song ?: return
        if (s.id <= 0) return // 本地歌曲无艺人主页
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

    // ---- 更新胶囊（同一版本只弹一次、点一次即消失；逻辑与 PlayerScreenContent 同源）----
    val updateInfo by UpdateChecker.available.collectAsState()
    val updateFlowState by UpdateFlow.state.collectAsState()
    val installedVersionName = remember { installedVersionNameOf(ctx) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var pillClicked by remember { mutableStateOf(false) }
    val pillCode = updateInfo?.versionCode
    fun openUpdateDialog() {
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

    // ---- 表冠音量（与 PlayerScreenContent.onCrownForVolume 同源逻辑，勿单独改方向）----
    // 误触宽量：400ms 窗口内累计增量 ≥96px 才唤出系统音量面板；非 ColorOS 走应用内音量条。
    var volAccum by remember { mutableStateOf(0f) }
    var volAccumAt by remember { mutableStateOf(0L) }
    var volFiredAt by remember { mutableStateOf(0L) }
    val hwDetentPx = remember(ctx) {
        android.view.ViewConfiguration.get(ctx).scaledVerticalScrollFactor.coerceAtLeast(1f)
    }
    var volEnterAccum by remember { mutableStateOf(0f) }
    var volEnterAt by remember { mutableStateOf(0L) }
    fun onCrownForVolume(delta: Float) {
        // ★ 2026-10-18 与 PlayerScreen 同一条开关（设置 → 歌曲响度均衡下方）：
        //   关掉后播放页转表冠不再调音量，事件在此消费掉、不冒泡。
        if (com.netmusiclite.data.CrownVolumePrefs.disabled) return
        // 方向已在 MainActivity.onGenericMotionEvent 统一归一化，这里直接消费、**不要取反**
        if (DeviceCompat.useInAppVolume) {
            val pxFullRange = hwDetentPx * InAppVolume.CROWN_DETENTS_FULL_RANGE
            if (!InAppVolume.visible) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - volEnterAt > InAppVolume.ENTER_IDLE_MS) volEnterAccum = 0f
                volEnterAt = now
                volEnterAccum += delta
                if (abs(volEnterAccum) < hwDetentPx * InAppVolume.ENTER_DETENTS) return
                val carry = volEnterAccum
                volEnterAccum = 0f
                InAppVolume.enter(carry / pxFullRange)
            } else {
                InAppVolume.nudge(delta / pxFullRange)
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

    // ---- 表冠焦点（Wear OS 规范：页面根持焦点，rotaryCustom 才拿得到表冠增量）----
    val volFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { volFocus.requestFocus() } }

    // ---- 栈底播放页的系统返回（与原有播放页同语义：前进导航到主页，装成返回）----
    // m3e 的 SwipeDismissableNavHost 在 PLAYER 上面恒垫着 HOME，正常路径走 popBackStack；
    // 这里注册兜底，保证「系统 back 落到栈底播放页」时与原有播放页行为一致。
    val rootExit by rememberUpdatedState {
        nav.navigateWithMotion(Routes.HOME, NavMotionSpec(NavMotionKind.Slide))
    }
    DisposableEffect(Unit) {
        SystemBackRouter.playerRootExit = { rootExit() }
        onDispose { SystemBackRouter.playerRootExit = null }
    }

    // ---- 底部三操作的音量入口（与 PlayerScreenContent.ArcBottomBar 的 onVolume 同源）----
    fun openVolumePanel() {
        if (DeviceCompat.useInAppVolume) {
            InAppVolume.show()
        } else {
            val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            am.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                android.media.AudioManager.ADJUST_SAME,
                android.media.AudioManager.FLAG_SHOW_UI,
            )
        }
    }

    // 三段几何（2026-10-17 第二轮：照官方 **animated / M3 Expressive** 版重标）：
    //  · 中段高 80dp —— 恰好容纳「扇贝键 62dp + 外圈进度弧」的 80dp 控制盒；中段中心 = 屏高中线；
    //  · 顶段高 = max(68dp, (屏高 − 中段)/2)；顶段内容底对齐（下留 2dp）；
    //  · 底段 = 剩余空间（weight 1）。
    //  233dp 圆屏实测推算：顶段文字顶 ≈ 40dp（系统时钟占 0~30dp，不遮）；中段中心 116.5dp；
    //  底段中心 194.75dp —— 233dp 圆屏在该处的**包围盒**可用宽只有 ~174dp，但胶囊两端是半圆、
    //  角落为空，按真实轮廓校验后整行只需上移 10dp（落在 y≈184.75dp，见底段调用点注释的
    //  胶囊端圆心算法）。所有 dp 常量都是「UI 坐标系」值（NcmTheme 已按屏宽等比缩放 density，
    //  UI 坐标恒为 233dp）。
    val uiH = screenHeightUiDp()
    val middleH = 80.dp
    val topH = maxOf(68.dp, (uiH - middleH) / 2f)
    // 方屏没有圆弧裁切：底段胶囊行保持居中，不做上移。
    val squareScreen = !isRoundScreen()

    Box(
        Modifier
            .fillMaxSize()
            // 表冠焦点：页面根持有焦点，rotaryCustom 才拿得到事件（同原有播放页）
            .focusRequester(volFocus)
            .focusable()
            // 左划进歌词：只声明左划，右滑返回仍归 SwipeDismissableNavHost（本页不吞右滑）
            .m3eSwipeLeft { nav.navigateWithMotion(Routes.LYRICS, NavMotionSpec(NavMotionKind.Slide)) }
            .pointerInputComments(nav)
            // 表冠：累计超过误触宽量唤出音量面板 / 非 ColorOS 直接调音量（Volume 通道方向）
            .rotaryCustom(
                scope = RotaryScope.Volume,
                onDelta = { onCrownForVolume(it) },
            ),
    ) {
        // 背景层：纯黑背景 = 纯黑；取色背景 = 歌曲环境色。与原有 m3e 播放页同一底色实现。
        NcmBackground(route = Routes.PLAYER, prepareResources = false)

        // 睡眠定时倒计时（右上角小字，与原有播放页同一位置/字号）
        if (SleepTimer.mode != SleepMode.OFF) {
            SleepCountdownLabel(
                fontSize = 9.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 14.dp, end = 16.dp),
            )
        }

        // ================= 三段式主体 =================
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {

            // ---------- ① 顶段：media info（文字，不显示封面），内容底对齐 ----------
            Box(
                Modifier
                    .fillMaxWidth()
                    .requiredHeight(topH)
                    .padding(bottom = 2.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // 更新胶囊：与原播放页一样放在标题上方；仅「发现新版本」时出现
                    val pillShown = pillCode != null && updateFlowState is UpdateFlow.State.Idle && !pillClicked
                    LaunchedEffect(pillShown, pillCode) {
                        // ★ 编译警告清理（2026-10-17，K2 恒真条件）：pillShown 的定义本身已蕴含
                        //   pillCode != null，再写 `pillShown && pillCode != null` 会被报
                        //   "Condition is always 'true'"。先取非空值、再判 pillShown —— 行为完全等价。
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
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(140)),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            UpdateHintPill(lastPillLabel) { openUpdateDialog() }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                    // 歌名：跑马灯 + 点击进歌曲百科（硬需求，与原有播放页同语义）
                    Box(Modifier.width(150.dp), contentAlignment = Alignment.Center) {
                        Text(
                            song?.title ?: "未在播放",
                            fontSize = 15.4.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
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
                                    } else Modifier
                                ),
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    // 艺人：单艺人直达艺人主页，多艺人弹胶囊选择浮层（硬需求）
                    Text(
                        song?.artist ?: "点任意歌曲开始",
                        fontSize = 9.9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(horizontal = 50.dp)
                            .then(
                                if (song != null) Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { openArtistPicker() }
                                else Modifier
                            ),
                    )
                    // 播放错误提示（原播放页同款位置：艺人下方）
                    val err = PlayerEngine.errorMsg
                    if (err != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            err,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            // ---------- ② 中段：三控制键（上一首 / 扇贝播放键 + 外圈短弧进度 / 下一首）----------
            Box(
                Modifier
                    .fillMaxWidth()
                    .requiredHeight(middleH),
                contentAlignment = Alignment.Center,
            ) {
                // ButtonGroup：中键 weight 0.5 + minWidth 80dp（扇贝键控制盒），两侧 weight 1。
                // 槽位只负责摆位；按钮本身固定尺寸（两侧 56×60.8dp 近方圆角矩形 / 中键 62dp 扇贝），
                // 按压反馈用 pressStretch。
                val middleSrc = remember { MutableInteractionSource() }
                ButtonGroup(
                    modifier = Modifier.fillMaxWidth(),
                    spacing = 0.dp,
                    // ★ 2026-10-17（第四轮用户口径）「两侧按钮外侧无显示空白区做小 20%」：
                    //   外侧空白 = row contentPadding + (侧槽宽−按钮宽)/2。233dp 屏上原值
                    //   = 8dp + (68.5−56)/2 ≈ 14.25dp；want −20% ⇒ 11.4dp。
                    //   按钮尺寸不动（用户只要求缩小外侧空白），把 row 内边距 8dp 降到 2.5dp：
                    //   外侧空白 = 2.5/2 + (233−192)/4 ≈ 11.5dp（−19.3%，取整误差内）。
                    //   按钮本身仍是 56×60.8dp（第五轮起高 60.8，比 76 矮 → 外侧水平空白不变），
                    //   中段仍居中，不会顶到表盘边。
                    contentPadding = PaddingValues(horizontal = 2.5.dp),
                    expansionWidth = 12.dp,
                ) {
                    Box(
                        Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        HorologistSideControl(
                            icon = NcmIcons.Prev,
                            description = "上一首",
                            onClick = { PlayerEngine.prev() },
                        )
                    }
                    Box(
                        Modifier.weight(0.5f).minWidth(80.dp).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        HorologistPlayPauseButton(
                            playing = playing,
                            interactionSource = middleSrc,
                            onToggle = { PlayerEngine.toggle() },
                        )
                    }
                    Box(
                        Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        HorologistSideControl(
                            icon = NcmIcons.Next,
                            description = "下一首",
                            onClick = { PlayerEngine.next() },
                        )
                    }
                }
            }

            // ---------- ③ 底段：次级操作行（喜欢 / 音量 / 更多），胶囊按钮 ----------
            Box(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val scheme = MaterialTheme.colorScheme
                val liked = song != null && PlayerEngine.isLiked(song.id)
                // 圆屏：底段几何中心在 y≈194.75dp（弧区可用宽只有 ~174dp），整行上移 10dp
                // （= 相对上一版 v2 下移 10dp / 466px 屏在 2.0x 下即 20 物理像素），落在 y≈184.75dp。
                // 弧区内合法性按**胶囊真实轮廓**校验（不是包围盒角落 —— 两端是半圆，角落是空的）：
                //   行宽 3×52+2×6=168dp，外侧胶囊远端半圆心距屏心 dx=64dp、dy=68.25dp
                //   ⇒ 圆心距 93.6dp + 胶囊端半径 20dp = 113.6dp < 屏半径 116.5dp（余 2.9dp），
                //   所以下移 10dp 后 52dp/6dp 的组合仍然完整落在圆内，不需要再收间距或宽。
                // 方屏没有弧区裁切，保持底段居中。
                Row(
                    modifier = if (squareScreen) Modifier else Modifier.offset(y = (-10).dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HorologistPillButton(
                        icon = if (liked) NcmIcons.HeartFill else NcmIcons.Heart,
                        description = "喜欢",
                        tint = if (liked) scheme.primary else scheme.onSurface,
                        onClick = { song?.let { PlayerEngine.toggleLike(it) } },
                    )
                    HorologistPillButton(
                        icon = NcmIcons.Volume,
                        description = "音量",
                        onClick = { openVolumePanel() },
                    )
                    HorologistPillButton(
                        icon = NcmIcons.MoreDots,
                        description = "更多",
                        onClick = { nav.navigateWithMotion(Routes.MORE) },
                    )
                }
            }
        }

        // 多艺人选择浮层（复用原有播放页组件：压暗背景 + 玻璃胶囊 + 右滑/点背景关闭）
        // ★ 2026-10-23（task-8）「多艺人二级菜单移位」修复后：浮层的位置**以屏幕为基准居中**，
        //   由 ArtistPickerOverlay 自己的「全屏 + Center」层保证（根因见该函数内注释）。
        //   ⚠ 本调用点必须保持现在的"直接挂在播放页根 Box 下"—— 不要再给它套
        //   padding / offset / graphicsLayer / Column 之类的父级布局或变换，
        //   否则定位会被父容器再次带偏（这正是修复前的病根形态）。
        if (showArtistPicker) {
            ArtistPickerOverlay(
                artists = artistOptions,
                onPick = { id ->
                    showArtistPicker = false
                    nav.navigateWithMotion(Routes.artistDetail(id), NavMotionSpec(NavMotionKind.Pill))
                },
                onDismiss = { showArtistPicker = false },
            )
        }

        // 长按播放/暂停键唤出的长条进度条浮层：必须挂在播放页根 Box 内（本页最后一个普通层）
        PlayerSeekOverlayHost()

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
 * 下滑进评论页（与原有播放页同一条手势：累计下滑 > 70dp 松手即进当前歌曲评论页）。
 * 单独抽成 Modifier 扩展只为本文件排版清晰，行为与 PlayerScreenContent 内联实现一致。
 */
private fun Modifier.pointerInputComments(nav: NavHostController): Modifier =
    this.then(
        Modifier.pointerInput(Unit) {
            var totalY = 0f
            detectVerticalDragGestures(
                onDragStart = { totalY = 0f },
                onDragEnd = {
                    val cur = PlayerEngine.current
                    if (totalY > 70.dp.toPx() && cur != null) {
                        // 下滑进评论：竖向滑动来源（与原有播放页相同的 SlideV 转场）
                        nav.navigateWithMotion(
                            Routes.comments(cur.id), NavMotionSpec(NavMotionKind.SlideV),
                        )
                    }
                },
            ) { change, amount ->
                change.consume()
                totalY += amount
            }
        },
    )

/**
 * 中段两侧的控制键（上一首 / 下一首）—— **近方形圆角矩形**。
 *
 *  · 第三轮口径（2026-10-17）「不是圆形，改成大倒角、长边竖直的长方形」：从大圆键改成
 *    56×76dp 竖长矩形；第五轮口径（同日）「上下宽度缩窄 20%，接近方形」：高 76 → **60.8dp**
 *    （= 76 × 0.8，精确 −20%），现为 **56×60.8dp** —— 竖向只比横向多 4.8dp，读起来是
 *    「圆角方块」而不是「竖胶囊」；
 *  · 中段高 80dp，按钮垂直居中后上下各留 ~9.6dp 余量（不会顶到顶段文字/底段胶囊）；
 *  · 圆角 25dp（用户要求 24~26dp 大倒角；小于高度一半 30.4dp，四个角明显可见，但不至于变成椭圆）；
 *  · 容器 primaryDim / 图标 onPrimary（官方 MediaButtonDefaults.mediaButtonDefaultColors 同源）。
 *  · 保留 pressStretch 按压缩放（PressMotion 是缩小不会溢出中段），不挂 ButtonGroup 的
 *    animateWidth expansion（固定尺寸按钮上 expansion 只改槽位宽度，看不出 expansion）。
 */
@Composable
private fun HorologistSideControl(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    // ★ 2026-10-18 资源库移植（用户口径「谷歌官方播放器样式改成资源库样式换掉自绘」）：
    //   侧键回到 horologist m3 `MediaButton` 的默认几何 —— **52dp 正圆 + primaryDim 容器 +
    //   LargeIconSize(26dp) 图标**（`MediaButtonDefaults.mediaButtonDefaultColors` 的取色）。
    //   旧版 56×60.8dp 圆角方块是自绘近似（上一轮「上下缩窄 20%」的口径），本次按库回归；
    //   按压反馈保留项目自己的 pressStretch（库那边是 ButtonGroup 膨胀 + ripple，观感不同）。
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(52.dp).pressStretch(),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = scheme.primaryDim,
            contentColor = scheme.onPrimary,
        ),
    ) {
        Icon(
            icon,
            description,
            Modifier.size(IconButtonDefaults.LargeIconSize),
            tint = scheme.onPrimary,
        )
    }
}

/**
 * 底部胶囊按钮（Stadium）：宽 52dp / 高 40dp / 圆角 50%（= 两端半圆）。
 *
 * 官方 buttons 槽在 animated 版里就是一排「胶囊 pills」；这里用 Wear M3
 * FilledTonalIconButton + 静态 stadium 形状实现（自带水波纹/无障碍语义，按下用 pressStretch
 * 做缩放反馈），容器 surfaceContainerHigh、图标 onSurface，喜欢选中态换 primary。
 *
 * ⚠ 宽度取 52dp 而不是参考截图的 56~64dp：第三轮整行下移到 y≈184.75dp 后，按胶囊**真实轮廓**
 *   （两端半圆）校验：外侧胶囊远端半圆心距屏心 93.6dp + 端半径 20dp = 113.6dp < 屏半径 116.5dp，
 *   3×52 + 2×6 = 168dp 仍完整落在弧内；若加宽到 56~64dp 会顶到表盘弧边（详见底段调用点注释）。
 *   这是圆屏几何约束，不是设计缩水。
 */
@Composable
private fun HorologistPillButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(width = 52.dp, height = 40.dp).pressStretch(),
        shapes = IconButtonDefaults.shapes(shape = RoundedCornerShape(50)),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = tint,
        ),
    ) {
        Icon(icon, description, Modifier.size(22.dp), tint = tint)
    }
}

/**
 * 进度环的**波浪轮廓**（★ 2026-10-18 用户口径「进度条波浪贴合内圈暂停按钮」）。
 *
 * 与 [ScallopShape] 用同一个 `RoundedPolygon.pillStar`（10 瓣 / innerRadiusRatio 0.8 /
 * 圆角按半径同比例放大），只是把外接半径换成环半径 —— 所以波纹的**相位、瓣深、圆润度**
 * 与按钮轮廓逐点一致，任何角度上到按钮的间距都相等（正圆环做不到：瓣尖贴 3.5dp、
 * 瓣谷空 9.7dp）。
 *
 * ⚠ **不能旋转这条路径**。第一版为了让进度从 12 点开始，把整条路径转了 -90°，
 * 结果 10 瓣 × 36° 的图形转了 2.5 个瓣 —— 环的瓣尖正对按钮的瓣谷，比正圆更难看。
 * 现在路径保持与按钮同相位，只平移到位；"从 12 点开始"由 [topLengthOn] 求弧长起点解决。
 *
 * @param outerRadius 环中心线的外接半径（瓣尖处）
 * @param corner      圆角半径（与按钮圆角同比例）
 * @param center      环心（相对 Canvas 左上角）
 */
private fun scallopRingPath(
    outerRadius: Float,
    corner: Float,
    center: Offset,
): android.graphics.Path {
    val polygon = RoundedPolygon.pillStar(
        width = outerRadius,
        height = outerRadius,
        numVerticesPerRadius = 10,
        innerRadiusRatio = 0.8f,
        rounding = CornerRounding(corner),
        centerX = 0f,
        centerY = 0f,
    )
    val path = polygon.toPath()
    android.graphics.Matrix().apply {
        setTranslate(center.x, center.y)
        path.transform(this)
    }
    return path
}

/**
 * 波浪轮廓上「12 点方向」对应的弧长位置（★ 2026-10-18）。
 *
 * 为什么要它：pillStar 的第一个顶点不在顶部，而进度必须从 12 点起长。
 * **不能**用「旋转整条路径」的办法校正 —— 10 瓣每瓣 36°，把图形转 90° = 转 2.5 个瓣，
 * 正好错开半个相位，环的瓣尖会正对按钮的瓣谷（用户口径「波浪要贴合内部按钮」说的就是这个）。
 * 所以保持路径相位与按钮完全一致，只把**截取起点**挪到 12 点：按 1px 步长扫一遍轮廓，
 * 取 y 最小（最靠上）的那个弧长。
 */
private fun topLengthOn(measure: android.graphics.PathMeasure, total: Float): Float {
    if (total <= 0f) return 0f
    val pos = FloatArray(2)
    var best = 0f
    var bestY = Float.MAX_VALUE
    var d = 0f
    while (d < total) {
        measure.getPosTan(d, pos, null)
        if (pos[1] < bestY) {
            bestY = pos[1]
            best = d
        }
        d += 1f
    }
    return best
}

/**
 * 从 [start]（12 点）起、按长度取 [progress] 比例的一段波浪路径。
 * 超过终点时回绕到路径开头接着取（进度接近 100% 时会跨过 12 点），
 * 两段各自成子路径 —— 圆头 cap 重叠处同色，肉眼看不到接缝。
 */
private fun progressSegment(
    measure: android.graphics.PathMeasure,
    start: Float,
    total: Float,
    progress: Float,
): android.graphics.Path {
    val out = android.graphics.Path()
    val len = total * progress
    if (len <= 0f || total <= 0f) return out
    val end = start + len
    if (end <= total) {
        measure.getSegment(start, end, out, true)
    } else {
        measure.getSegment(start, total, out, true)
        val tail = android.graphics.Path()
        measure.getSegment(0f, end - total, tail, true)
        out.addPath(tail)
    }
    return out
}

/**
 * 中段中央的 **扇贝形播放/暂停键 + 外圈短弧进度**（官方 animated 版
 * AnimatedPlayPauseProgressButton 的近似实现，2026-10-17 第二轮）：
 *
 *  · **扇贝形状**：62dp 按键用 [ScallopShape]（10 瓣圆角星，graphics-shapes pillStar 生成）
 *    裁剪出花瓣轮廓，primary 实底 + onPrimary 图标。官方在播放态是扇贝、暂停态回圆并带
 *    Lottie 形变；本项目不加 lottie 依赖，形状固定扇贝、播放/暂停图标用 160ms Crossfade 切换。
 *  · **进度环**：外圈是**与按钮同相位、同瓣深的波浪轮廓**（2026-10-18 用户口径
 *    「波浪贴合内圈按钮、圈增大 5% 留间隙」，见 [scallopRingPath]），stroke 3dp 圆头；
 *    轨道 = onSurface 12% 弱色整圈，进度 = 从 12 点方向按弧长截取的同一轮廓
 *    （[progressSegment]）。进度在**绘制期**读 PlayerEngine.positionMs / durationMs ——
 *    播放中引擎每 500ms 更新一次，读取点在 draw 里，所以只失效这一圈、
 *    不触发整页重组（右缘 EdgeProgressRing 同一口径）。
 *  · **长按**：按键本体用 combinedClickable（约 500ms、自带 LongPress 震动）唤出
 *    PlayerSeekOverlay 长条进度条浮层；短按 = 播放/暂停；长按松手不会再触发一次点击。
 *  · 加载中（PlayerEngine.loading）：键内画小型不定量转圈（原播放页同语义）。
 */
@Composable
private fun HorologistPlayPauseButton(
    playing: Boolean,
    interactionSource: MutableInteractionSource,
    onToggle: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scallopShape = remember { ScallopShape() }
    // ★ 2026-10-18 资源库移植：波浪环「播放时旋转」的相位（库的 RotatingWavyProgressIndicator）。
    //   暂停时冻结在当前相位（库同款表现）；每圈 6s 匀速。
    //
    // ★ 2026-10-23 修复项 6（用户 bug「m3e 主题下点『下一首』后，中间暂停键/波浪环的旋转明显变慢」）：
    //   【旧实现】
    //     val ringRotation = remember { Animatable(0f) }
    //     LaunchedEffect(playing) {
    //         if (!playing) return@LaunchedEffect
    //         while (true) {
    //             ringRotation.animateTo(1f, tween(6000, easing = LinearEasing))
    //             ringRotation.snapTo(0f)
    //         }
    //     }
    //   【根因】
    //     ① `tween(6000)` 的语义是「从**当前值**走到 1 一共用 6000ms」，不是「一圈 6000ms」。
    //        而切歌时 PlayerEngine 会先把播放态按下去：PlayerEngine.kt:189-190
    //        `loading = true; isPlaying = false`（缓冲/加载完成才回到 true）。本页的 playing
    //        直接取 PlayerEngine.isPlaying（见本文件 M3eHorologistPlayer 顶部），于是
    //        LaunchedEffect(playing) 在切歌瞬间被取消、加载完成后以 playing=true 重启。
    //        重启后 animateTo 从当前相位 X 出发，却仍被分配整整 6000ms ——
    //        角速度变成原来的 (1−X) 倍（相位越靠后越慢，接近完整一圈时几乎停住），
    //        要等这一轮走完 snapTo(0) 才恢复正常。缓冲期 onIsPlayingChanged 的抖动每抖一次再掉一档
    //        —— 这就是用户看到的「点下一首后转速掉下来、越用越慢」。
    //     ② 暂停分支直接 return 会让协程停住、相位冻结；恢复时又从头重启一次，
    //        还有一次角速度突变（观感「卡一下再慢转」）。
    //   【新驱动：累计播放时长定相位】
    //     相位 = (累计「该转」的墙钟纳秒 mod 6000ms) / 6000ms，每帧写一次 state。
    //     · 转速恒为 1 圈 / 6s，与协程重启、组合重建、切歌、缓冲抖动**完全无关**；
    //     · 相位是时间基的，不存在「用剩余路程补满 6 秒」的归一化，切歌不跳相位；
    //     · 「该转」= playing || PlayerEngine.loading：切歌/缓冲期间 isPlaying 短暂为 false
    //       仍继续匀速转（用户口径「切歌前后转速一致」），只有**真正暂停**才冻结相位；
    //     · 暂停时降到 100ms 轮询（不逐帧空转，与 RotatingCover 的省电口径一致）；
    //       播放中本来就必须逐帧重绘这一圈（旧 animateTo 同样逐帧），开销不变。
    //     单帧增量钳到 100ms：掉帧 / 从后台回来时不会一次跳过大半圈。
    val playingNow = rememberUpdatedState(playing)
    val ringRotation = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val periodNanos = 6_000_000_000L // 一圈 6s（与原 tween(6000) 同速，观感不变）
        var accumulatedNanos = 0L
        // 帧时钟基准：先对齐到当前帧，组合挂起 / 后台停留的时长不会被算进相位
        var lastFrameNanos = withFrameNanos { it }
        while (true) {
            if (!playingNow.value && !PlayerEngine.loading) {
                // 真正暂停：冻结相位（不累加），低频等待恢复
                delay(100)
                lastFrameNanos = withFrameNanos { it }
                continue
            }
            withFrameNanos { now ->
                val delta = (now - lastFrameNanos).coerceIn(0L, 100_000_000L)
                lastFrameNanos = now
                accumulatedNanos = (accumulatedNanos + delta) % periodNanos
                ringRotation.floatValue = accumulatedNanos.toFloat() / periodNanos
            }
        }
    }
    Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
        // ★ 2026-10-18 用户口径「外圈进度条波浪贴合内圈暂停按钮」：
        //   旧实现是**正圆**环（半径 34.5dp）—— 按钮是 10 瓣扇贝（外接 31dp、瓣谷 24.8dp），
        //   正圆只在瓣尖处贴（3.5dp），瓣谷处空出 9.7dp，看上去就是「一圈圆的套在花瓣上、不贴」。
        //   现在轨道与进度都用**同一颗 pillStar**（同 10 瓣 / 同 innerRadiusRatio / 同比例圆角）
        //   按环半径放大后的轮廓：波纹相位、瓣深与按钮天然一致，任何角度上都是等距贴合。
        //   ⚠ 相位对齐的前提是**不旋转路径** —— 10 瓣每瓣 36°，转 90° 会错开 2.5 个瓣
        //     （瓣尖对瓣谷）；"从 12 点开始"改用截取起点实现，见 topLengthOn / progressSegment。
        //   半径再 ×1.05（用户口径「圈增大百分之五留出间隙」），与按钮之间留出可见的一圈空。
        Box(
            Modifier.matchParentSize().drawWithCache {
                val stroke = 3.dp.toPx()
                // 环中心线的"外接半径"：旧圆环 34.5dp × 1.05（留间隙）+ 4 物理像素
                //（2026-10-18 用户口径「外面那一圈波浪圈与内部暂停键之间距离加大 4 像素」）
                val ringOuter = 34.5.dp.toPx() * 1.05f + 4f
                // 圆角按按钮的比例放大（按钮 31dp 半径配 12dp 圆角 ⇒ 环也按同比例），
                // 保证花瓣的圆润度不会因为半径变大而显得更"尖"
                val buttonOuter = 31.dp.toPx()
                val corner = 12.dp.toPx() * (ringOuter / buttonOuter)
                val center = Offset(size.width / 2f, size.height / 2f)
                val ring = scallopRingPath(ringOuter, corner, center)
                val ringCompose = ring.asComposePath()
                // 12 点方向在波浪轮廓上的弧长（只算一次，路径不变就一直是这个值）
                val measure = android.graphics.PathMeasure(ring, false)
                val total = measure.length
                val startLen = topLengthOn(measure, total)
                val trackColor = scheme.onSurface.copy(alpha = 0.12f)
                val progressColor = scheme.onSecondaryContainer
                // ★ 2026-10-18 资源库移植：`RotatingWavyProgressIndicator` 的「播放时整圈旋转」。
                //   库里旋转相位由 infiniteTransition 驱动（faster 曲线）；这里改为
                //   「累计播放时长」的相位 state（见本函数开头的 2026-10-23 修复说明），
                //   暂停时冻结在当前相位、恢复后从原相位继续匀速转。
                //   进度段的起点要跟着相位平移（startLen + total·rot，回绕取模），
                //   否则转的是环、进度线却钉死在 12 点。
                val rotation = ringRotation
                onDrawBehind {
                    val duration = PlayerEngine.durationMs
                    val progress = if (duration <= 0L) 0f
                    else (PlayerEngine.positionMs.toFloat() / duration).coerceIn(0f, 1f)
                    val rot = rotation.floatValue
                    rotate(degrees = 360f * rot) {
                        // 轨道：同一条波浪轮廓，弱色整圈（先整圈、再叠进度，与库同序）
                        drawPath(ringCompose, trackColor, style = Stroke(width = stroke))
                        if (progress > 0f) {
                            val start = (startLen + total * rot) % total
                            val segment = progressSegment(measure, start, total, progress)
                            if (!segment.isEmpty) {
                                drawPath(
                                    segment.asComposePath(),
                                    progressColor,
                                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                                )
                            }
                        }
                    }
                }
            }
        )
        // 扇贝键本体：图形裁剪出花瓣 + 自绘按压缩放（不依赖 Material 容器，长按/短按语义自持）
        //
        // ★ 2026-10-18 用户口径「中间的暂停按钮（实心）要跟着外圈的播放进度条转动（差位）」：
        //   外圈波浪环播放时整圈旋转（上面的 ringRotation），而按钮本体原先完全静止 ⇒ 两者的
        //   10 瓣相位每帧都在错开，读起来就是「环在转、按钮不跟、花瓣对不齐（差位）」。
        //   现在**花瓣实底与外圈共用同一个动画值、同相位旋转**（360° × rot）：
        //   相对相位恒定 ⇒ 波浪永远贴着按钮轮廓（上一轮「波浪贴合内圈按钮」的前提得以保持）。
        //   用户选定口径 B：**图标始终正立** —— 所以旋转只挂在「底色花瓣」那一层的
        //   graphicsLayer 上，图标是它上面的独立一层，不参与旋转。
        Box(
            Modifier
                .size(62.dp)
                .pressStretch()
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onLongClickLabel = "进度条",
                    onLongClick = { PlayerSeekOverlay.show() },
                    onClick = onToggle,
                ),
            contentAlignment = Alignment.Center,
        ) {
            // 旋转层：只画花瓣实底。rotationZ 在**绘制期**读 ringRotation 的 state，不触发重组；
            // 与外圈 Canvas 里的 rotate(360° × rot) 严格同相位（同一个动画值、同一时间点读取）。
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { rotationZ = 360f * ringRotation.floatValue }
                    .clip(scallopShape)
                    .background(scheme.primary),
            )
            // 图标层：不旋转，播放/暂停图标始终正立
            if (PlayerEngine.loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.5.dp,
                )
            } else {
                // 不加 lottie：播放/暂停图标用 160ms Crossfade 切换（用户已确认可接受）
                Crossfade(
                    targetState = playing,
                    animationSpec = tween(160),
                    label = "playPauseIcon",
                ) { isPlaying ->
                    Icon(
                        imageVector = if (isPlaying) NcmIcons.Pause else NcmIcons.Play,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        modifier = Modifier.size(30.dp),
                        tint = scheme.onPrimary,
                    )
                }
            }
        }
    }
}
