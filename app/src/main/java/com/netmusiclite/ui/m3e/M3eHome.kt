package com.netmusiclite.ui.m3e

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BrushPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import com.netmusiclite.ui.theme.LocalClockFontScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.material3.*
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import coil.compose.AsyncImage
import com.netmusiclite.data.NcmApi
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.SessionStore
import com.netmusiclite.data.Song
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.SystemBackRouter
import com.netmusiclite.ui.nav.OverlayBackHandler
import com.netmusiclite.ui.nav.navigateWithMotion
import com.netmusiclite.ui.components.pressStretch
import com.netmusiclite.ui.components.rotaryScrollableState
import com.netmusiclite.ui.screens.ipod.IpodRoutes
import com.netmusiclite.ui.screens.ipod.ipodSourceForHomeRoute
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt
import kotlin.math.roundToInt

private data class M3eHomeEntry(val title: String, val icon: ImageVector, val route: String)

/** The classic home is left intact. All surfaces and interactions here use Wear Material 3. */
@Composable
fun M3eHome(nav: NavHostController) {
    val epoch = SessionStore.epoch
    val loggedIn = epoch >= 0 && SessionStore.loggedIn
    var sidebarOpen by remember { mutableStateOf(false) }
    val reveal by animateFloatAsState(if (sidebarOpen) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(), label = "homeSidebarReveal")
    val context = LocalContext.current
    val onHomeBack by rememberUpdatedState {
        if (sidebarOpen) sidebarOpen = false
        else (context as? android.app.Activity)?.moveTaskToBack(true)
    }
    DisposableEffect(context) {
        SystemBackRouter.homeExit = {
            onHomeBack()
        }
        onDispose { SystemBackRouter.homeExit = null }
    }
    OverlayBackHandler(enabled = sidebarOpen) { sidebarOpen = false }
    androidx.activity.compose.BackHandler(enabled = sidebarOpen) { sidebarOpen = false }
    BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) {
        // 2026-10-17 用户 bug「material 主题下无法上下滑动列表」：
        //   旧实现只要横向累计位移 >2px 就 consume() 每一个 move 事件 —— 而 consume 发生在
        //   **Initial pass**（早于子节点），TransformingLazyColumn 的 scrollable 在
        //   awaitDragOrCancellation 里看到 change.isConsumed 就直接判定手势取消，
        //   于是竖滑永远起不来（手指稍微带一点横向抖动就 >2px）。列表卡死根因即此。
        // 现在改为「先判向、后消费」：只有证明是横滑（|dx| > touchSlop 且 |dx| > |dy|）
        // 才开始 consume；竖滑一律放行给列表，手势结束也不会误触发侧边栏。
        // 左滑打开侧栏，右滑进入播放页；侧栏打开时右滑关闭。
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val openThreshold = 28.dp.toPx()
            val closeThreshold = 20.dp.toPx()
            val slop = viewConfiguration.touchSlop
            var dx = 0f
            var dy = 0f
            var horizontal: Boolean? = null
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.positionChange()
                dx += delta.x
                dy += delta.y
                if (horizontal == null && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                    // 斜滑（两轴差距不足 1.5 倍）暂不判定，继续累积；判不出方向就什么都不做 ——
                    // 宁可放弃一次侧边栏，也不误吞一次竖滑
                    if (kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) horizontal = true
                    else if (kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.5f) horizontal = false
                }
                if (horizontal == true) change.consume()
            }
            if (horizontal == true) {
                if (sidebarOpen) {
                    if (dx >= closeThreshold) sidebarOpen = false
                } else if (dx <= -openThreshold) {
                    sidebarOpen = true
                } else if (dx >= openThreshold) {
                    nav.navigateWithMotion(Routes.PLAYER)
                }
            }
        }
    }) {
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight
        Box(Modifier.fillMaxSize().graphicsLayer {
            scaleX = 1f - reveal * .38f
            scaleY = 1f - reveal * .38f
            translationX = -size.width * .15f * reveal
            translationY = size.height * .04f * reveal
            alpha = 1f - reveal * .32f
            shape = CircleShape
            clip = reveal > .001f
        }.drawWithContent {
            drawContent()
            if (reveal > .001f) drawCircle(
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = .3f * reveal),
                radius = size.minDimension / 2f - 1.dp.toPx(),
                style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()),
            )
        }) {
            com.netmusiclite.ui.components.NcmBackground(route = Routes.HOME, prepareResources = false)
            M3eHomeFeed(nav, loggedIn, interactive = !sidebarOpen, viewportHeight = viewportHeight)
        }
        if (sidebarOpen || reveal > .001f) {
            Box(Modifier.fillMaxSize().graphicsLayer {
                alpha = reveal.coerceIn(0f, 1f)
                translationX = size.width * .2f * (1f - reveal)
            }) {
                M3eHomeSidebar(nav, loggedIn, viewportWidth, viewportHeight) { sidebarOpen = false }
            }
        }
    }
}

@Composable
internal fun M3eHomeFeed(
    nav: NavHostController,
    loggedIn: Boolean,
    interactive: Boolean,
    viewportHeight: Dp,
    // Kept only so existing diagnostic callers compile; production scroll uses the grid state.
    @Suppress("UNUSED_PARAMETER") state: TransformingLazyColumnState? = null,
    recommendationState: MutableState<List<Song>> = rememberHomeRecommendations(loggedIn),
    gridState: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
) {
    val spec = rememberTransformationSpec()
    val scrollInfo = rememberHomeGridScrollInfo(gridState)
    val focusRequester = remember { FocusRequester() }
    // ★ 2026-10-23 第二轮（用户口径「把现在主页滑动时的震动改为该页面表冠转动的震动，
    //   该页面滑动时不再有震动」）：
    //   上一版把「卡片冒头震动」挂在可见区尾部的变化上 —— 手指滑动、惯性滑行、表冠转动
    //   全都会震。现在**只保留表冠来源**：
    //   · 表冠输入进管道时记一个时间戳（onUserInput 回调，见下方 rotaryScrollableState）；
    //   · 只有「距上次表冠输入 < CROWN_HAPTIC_WINDOW_MS」时，卡片冒头才震；
    //   · 手指滑动（以及它带出的惯性）跟这个时间戳无关 ⇒ 完全不震。
    //   同时把管道自带的「每格一记」关掉（haptic = false），否则表冠转动会双震。
    //   震动力度仍是表冠那一档（NcmHaptics.cardReveal），符合"震动强度不变"。
    val crownAtMs = remember { longArrayOf(0L) }
    LaunchedEffect(gridState) {
        // ★ 2026-10-23 修「m3e 主题反向转动表冠没有震动」：
        //   旧判据只认「可见尾部 index 变大」，那只覆盖"向下滚 = 新卡从下方露出来"；
        //   反向转表冠时列表是往回滚，尾部 index 不变或变小 ⇒ 永远不震（用户体感：
        //   正转有震动、反转完全没反应）。
        //   改成**双向冒头判定**：
        //     · 向下滚（正转表冠）→ 尾部 index 变大（新卡从下方露出）；
        //     · 向上滚（反转表冠）→ 首部 index 变小（新卡从上方露出）。
        //   两者任一成立、且这次滚动处于表冠输入窗口内 → 震一记；手指滑动仍不震。
        var lastBottom = gridState.layoutInfo.visibleItemsInfo.maxOfOrNull { it.index } ?: -1
        var lastTop = gridState.layoutInfo.visibleItemsInfo.minOfOrNull { it.index } ?: -1
        snapshotFlow {
            val info = gridState.layoutInfo.visibleItemsInfo
            (info.maxOfOrNull { it.index } ?: -1) to (info.minOfOrNull { it.index } ?: -1)
        }.collect { (bottom, top) ->
            val fromCrown = android.os.SystemClock.uptimeMillis() - crownAtMs[0] <
                CROWN_HAPTIC_WINDOW_MS
            val revealed = bottom > lastBottom || top < lastTop
            if (revealed && fromCrown) {
                com.netmusiclite.ui.components.NcmHaptics.cardReveal()
            }
            lastBottom = bottom
            lastTop = top
        }
    }
    // ★ 2026-10-17 第四轮（m3e 表冠按设备分流，见 DeviceCompat.useNativeWearRotary）：
    //   谷歌/三星 → Wear 原生 rotaryScrollable；其他设备（OPPO/华为等）→ 默认主题平滑管道。
    val nativeRotary = com.netmusiclite.data.DeviceCompat.useNativeWearRotary
    val recommendationTextInset = with(LocalDensity.current) { 5f.toDp() }
    // ★ 2026-10-17 用户口径「把 material 主题下主页卡片的图标以及名称往右移动 30 个像素（只是大卡片）」：
    //   只作用于上方 7/4 张大卡片（entries），下方推荐双列小卡使用对称的 8dp 内边距。
    //   30 为物理像素 → 按当前 density 折算，与项目其它「N 像素」口径一致（如播放页 20px=10dp）。
    val entryStartPadding = with(LocalDensity.current) { 38f.toDp() }
    val entries = remember(loggedIn) {
        if (loggedIn) listOf(
            M3eHomeEntry("播放列表", Icons.Rounded.QueueMusic, Routes.QUEUE),
            M3eHomeEntry("我喜欢", Icons.Rounded.Favorite, Routes.LIKED_SONGS),
            M3eHomeEntry("我的歌单", Icons.Rounded.LibraryMusic, Routes.MY_PLAYLISTS),
            M3eHomeEntry("每日推荐", Icons.Rounded.Today, Routes.DAILY_RECOMMEND),
            M3eHomeEntry("听歌排行", Icons.Rounded.BarChart, Routes.RECORD),
            M3eHomeEntry("最近播放", Icons.Rounded.History, Routes.PLAY_HISTORY),
            M3eHomeEntry("私人漫游", Icons.Rounded.Explore, Routes.PRIVATE_ROAM),
        ) else listOf(
            M3eHomeEntry("听本地音乐", Icons.Rounded.MusicNote, Routes.LOCAL_MUSIC),
            M3eHomeEntry("听歌排行", Icons.Rounded.BarChart, Routes.RECORD),
            M3eHomeEntry("最近播放", Icons.Rounded.History, Routes.PLAY_HISTORY),
            M3eHomeEntry("设置", Icons.Rounded.Settings, Routes.SETTINGS),
        )
    }
    var recommendations by recommendationState
    var retryGeneration by remember(loggedIn) { mutableIntStateOf(0) }
    var loading by remember(loggedIn) { mutableStateOf(false) }
    var manualRetryAvailable by remember(loggedIn) { mutableStateOf(false) }
    val requestMutex = remember { Mutex() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val tailVisible by remember(gridState) {
        derivedStateOf {
            val layout = gridState.layoutInfo
            layout.visibleItemsInfo.any {
                it.key == "recommendation_tail" && it.offset.y < layout.viewportEndOffset &&
                    it.offset.y + it.size.height > layout.viewportStartOffset
            }
        }
    }
    LaunchedEffect(loggedIn, recommendationState, interactive, tailVisible, recommendations.size, retryGeneration, lifecycle) {
        if (!loggedIn || !interactive || !tailVisible) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // An old blocking IO call may finish cancellation late. Keep the next load serial.
            requestMutex.withLock {
                retryRecommendationsUntilProgress(
                    fetch = {
                        val batch = if (recommendations.isEmpty()) {
                            val daily = try {
                                NcmApi.dailyRecommend()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                emptyList()
                            }
                            currentCoroutineContext().ensureActive()
                            daily.ifEmpty { NcmApi.personalFM() }
                        } else NcmApi.personalFM()
                        currentCoroutineContext().ensureActive()
                        val previous = recommendations.mapTo(HashSet()) { it.id }
                        val fresh = batch.filter { it.id > 0L && it.id !in previous }.distinctBy { it.id }
                        if (fresh.isNotEmpty()) recommendations = recommendations + fresh
                        fresh.isNotEmpty()
                    },
                    onLoading = { loading = it },
                    onManualRetryAvailable = { manualRetryAvailable = it },
                )
            }
        }
    }
    // The grid starts item 0 after this padding. Native saveable per-lane positions restore
    // both waterfall columns when returning, alongside the saved recommendation pages.
    val searchTopPadding = ((viewportHeight - 45.dp) / 2f).coerceAtLeast(0.dp)
    ScreenScaffold(scrollInfoProvider = scrollInfo, contentPadding = PaddingValues(
        top = searchTopPadding,
        bottom = ScreenScaffoldDefaults.contentPadding.calculateBottomPadding(),
    ),
        // ★ 2026-10-17（第三轮用户口径）「主页不显示 m3e 原生滑动进度条了请你添加」：
        //   这个「自定义 ScrollInfoProvider」重载的 scrollIndicator 默认是 null（Wear M3 没有
        //   瀑布流版的 ScrollIndicator），所以主页一直没有指示条。这里显式挂上按官方几何复刻的
        //   弧形指示条（见 HomeScrollIndicator.kt）：圆屏沿右缘画弧、不会被表盘弧度裁切；
        //   只占最右 ~7.5dp，卡片 end 内边距 16dp ⇒ 不和卡片重叠。
        scrollIndicator = { M3eHomeScrollIndicator(gridState) },
    ) { padding ->
        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            state = gridState,
            modifier = Modifier.fillMaxSize().testTag("m3e_home_list").then(
                when {
                    !interactive -> Modifier
                    // 谷歌/三星：Wear 原生 rotaryScrollable（真机验证可用的标准旋入链路）
                    nativeRotary -> {
                        val rotary = RotaryScrollableDefaults.behavior(gridState)
                        Modifier.requestFocusOnHierarchyActive()
                            .rotaryScrollable(behavior = rotary, focusRequester = focusRequester)
                    }
                    // 其他设备（OPPO/华为等）：复用默认主题的平滑管道**真正驱动**瀑布流滚动
                    //（前馈+位置伺服+惯性滑行+NcmHaptics 刻度+方向开关+华为 RotaryFallback 兜底）。
                    // 主页的弧形滚动进度条/Material 形变读的都是同一个 gridState 的真实进度，
                    // 只做外观显示、不接任何输入（即用户口径的「空挂载」）。
                    else -> Modifier.requestFocusOnHierarchyActive()
                        .rotaryScrollableState(
                            gridState,
                            // 每次表冠输入都记时间戳：冒头震动据此判断"这次滚动是不是表冠引起的"
                            onUserInput = { crownAtMs[0] = android.os.SystemClock.uptimeMillis() },
                            // 关掉管道自带的逐格震动（改由页面按表冠输入的卡片冒头震，避免双震）
                            haptic = false,
                        )
                },
            ),
            contentPadding = PaddingValues(start = 9.dp, end = 16.dp,
                top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            // ★ 2026-10-17（第二轮用户口径）「以 m3e 主页大卡片之间的竖直间隙为标准统一上下缝隙」：
            //   全列表只有一档竖直间距 = 8dp。搜索/播放按钮行**不能再额外加 15 物理像素**
            //   （旧的 searchPlaylistExtraGap ≈7.5dp，让它与第一张大卡片之间变成 15.5dp，
            //   明显比卡片之间的 8dp 宽）—— 现在整行保持原高度，与下方统一为 8dp。
            //   注意首屏定位靠上面的 searchTopPadding（(屏高−45dp)/2 把首项放到垂直正中），
            //   那是滚动锚不是缝隙；删除额外内边距不影响它：item 高度回到 45dp 后
            //   (屏高−45)/2 + 45/2 仍 = 屏高/2，搜索行依旧居中。
            verticalItemSpacing = 8.dp,
            userScrollEnabled = interactive,
        ) {
            item(key = "search_play", span = StaggeredGridItemSpan.FullLine) {
                // Balance the asymmetric card insets so the search baseline stays screen-centered.
                Box(Modifier.fillMaxWidth().offset(x = 3.5.dp),
                    contentAlignment = Alignment.Center) {
                ButtonGroup(
                    modifier = Modifier.width(141.dp).testTag("m3e_home_search_row"),
                    contentPadding = PaddingValues(0.dp),
                    spacing = 6.dp,
                ) {
                    val searchInteraction = remember { MutableInteractionSource() }
                    val playerInteraction = remember { MutableInteractionSource() }
                    Button(
                        onClick = { nav.navigateWithMotion(if (loggedIn) Routes.SEARCH else Routes.LOCAL_MUSIC) },
                        enabled = interactive,
                        modifier = Modifier.weight(2f).animateWidth(searchInteraction).height(45.dp),
                        interactionSource = searchInteraction,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Search, "搜索", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(5.dp))
                            Text(if (loggedIn) "搜索" else "本地", maxLines = 1, textAlign = TextAlign.Center)
                        }
                    }
                    FilledIconButton(
                        onClick = { nav.navigateWithMotion(Routes.PLAYER) },
                        enabled = interactive,
                        modifier = Modifier.weight(1f).animateWidth(playerInteraction).height(45.dp),
                        interactionSource = playerInteraction,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) { Icon(Icons.Rounded.GraphicEq, "正在播放") }
                }
                }
            }
            entries.forEach { entry ->
                item(key = entry.route, span = StaggeredGridItemSpan.FullLine) {
                    val palette = homeCardPalette(entry.route)
                    // ★ 2026-10-23 用户口径「左上角那张小卡片外面那圈大卡片的底色，在不影响原来
                    //   图片显示的前提下蒙一层半透明的黑色」：
                    //   ——只压暗**大卡底面那一层渐变**：把每段色按 22% 混黑（等价于叠一层纯黑
                    //   alpha 0.22 的蒙版，但不额外多一次绘制、也不会盖住任何内容）。
                    //   小卡片那张迷你海报（palette.tile）、图标颜色的 ink/tileInk、文字全部不动，
                    //   所以"原来的图片显示"一点不受影响，只是卡底不再那么亮/艳。
                    val gradient = remember(palette) {
                        BrushPainter(
                            Brush.linearGradient(
                                palette.colors.map {
                                    androidx.compose.ui.graphics.lerp(it, Color.Black, CARD_BLACK_VEIL)
                                },
                            ),
                        )
                    }
                    val glow = remember(palette) {
                        Brush.radialGradient(listOf(palette.glow.copy(alpha = .45f), palette.glow.copy(alpha = 0f)))
                    }
                    val painter = CardDefaults.containerPainter(image = gradient, scrim = glow)
                    val ink = palette.ink
                    val transformation = rememberHomeGridTransformation(gridState, entry.route, spec)
                    // ★★ 2026-10-17（第二轮用户口径）：
                    //   ①「长按主题卡片进入随机选择页面的时长改成 0.8 秒」；
                    //   ② 修 bug「在带长按的卡片上按住并上下滑动列表，会直接进入抽取歌曲界面」。
                    //
                    //   旧实现用 waitForUpOrCancellation(PointerEventPass.Initial) 等 500ms：
                    //   LazyStaggeredGrid 的 scrollable 在 **Main pass** 消费位移，而 Initial pass
                    //   早于 Main —— 观察器永远看不到 isConsumed，于是按住滑动时 500ms 超时照常
                    //   成立、直接误触发导航。根因是「观察 pass 选错」，不是超时时长。
                    //
                    //   现在改为在 **Final pass**（所有消费都已完成、也是唯一能看到滚动消费的时机）
                    //   观察本次手势，并直接比较触点相对按下点的位移：累计位移超过 touchSlop 就
                    //   判定「这是滑动不是长按」，立即取消；早于 800ms 松手同样取消。
                    //   观察器全程不消费事件，点击 / 按压形变 / 列表滚动都不受影响。
                    val ipodSource = ipodSourceForHomeRoute(entry.route)
                    // 用「触发时刻」而不是残留布尔记录长按：长按跳转后，本次手势松手时 Card 仍会
                    // 补发一次 onClick —— 只要还有触发时刻就吞掉；下一次按下（awaitFirstDown）
                    // 会清零，所以从抽取页回到主页后的第一次点击不会被误吞。
                    var longPressFiredAt by remember { mutableStateOf(0L) }
                    M3ePressSurface(
                        Modifier.fillMaxWidth().animateItem().then(
                            if (ipodSource != null && interactive) Modifier.pointerInput(ipodSource) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                    longPressFiredAt = 0L
                                    val slop = viewConfiguration.touchSlop
                                    // true = 松手/滑动 → 取消；null = 800ms 超时 → 长按成立
                                    val cancelled = withTimeoutOrNull(800L) {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Final)
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) break
                                            // ⚠ 不能用 change.isConsumed 判取消：Card 的 clickable 在 Main pass
                                            //   就会消费 down 事件 —— 据此取消会让长按**永远不成立**（真机实测
                                            //   踩到：长按全部失效）。这里只认「触点位移超过 touchSlop」：
                                            //   滚动列表必然先越过它，长按则一定不越过它。
                                            val dx = change.position.x - down.position.x
                                            val dy = change.position.y - down.position.y
                                            if (dx * dx + dy * dy > slop * slop) break
                                        }
                                        true
                                    }
                                    if (cancelled == null) {
                                        longPressFiredAt = android.os.SystemClock.uptimeMillis()
                                        nav.navigateWithMotion(IpodRoutes.of(ipodSource))
                                    }
                                }
                            } else Modifier,
                        ),
                        enabled = interactive,
                    ) {
                    Card(
                        onClick = {
                            // 长按已触发 → 本次手势松手补发的 onClick 吞掉（旗标由下一次按下清零）
                            if (longPressFiredAt == 0L) nav.navigateWithMotion(entry.route)
                        },
                        containerPainter = painter,
                        enabled = interactive,
                        modifier = Modifier.fillMaxWidth().height(135.dp),
                        colors = CardDefaults.cardWithContainerPainterColors(contentColor = ink, titleColor = ink),
                        contentPadding = PaddingValues(start = entryStartPadding, end = 16.dp, top = 12.dp, bottom = 12.dp),
                        transformation = transformation,
                    ) {
                        // ★ 2026-10-23 第三轮（用户给了 Apple Music 参考海报）：
                        //   左上角那张小卡片从「ink 12% 半透明方块 + 单色图标」改成
                        //   **迷你 Apple Music 封面**：对角线渐变色块（palette.tile，按参考图取色）
                        //   + 半透明白色微描边 + 印在色块上的图标（palette.tileInk）。
                        //   按用户口径「Favourite Songs 对应我喜欢」：这张卡的小卡片用参考图 2 的方案
                        //   （浅灰白底 + **红色五角星**），图标从心形换成 Star。
                        val tileBrush = remember(palette) { Brush.linearGradient(palette.tile) }
                        Box(
                            modifier = Modifier
                                .size(66.dp, 59.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .background(tileBrush)
                                .border(
                                    0.8.dp,
                                    Color.White.copy(alpha = 0.22f),
                                    MaterialTheme.shapes.medium,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (entry.route == Routes.LIKED_SONGS) Icons.Rounded.Star else entry.icon,
                                null,
                                Modifier.size(34.dp),
                                tint = palette.tileInk,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        val count = when (entry.route) {
                            Routes.QUEUE -> "${PlayerEngine.queue.size} 首歌曲"
                            Routes.LIKED_SONGS -> PlayerEngine.likedIds.takeIf { it.isNotEmpty() }?.let { "${it.size} 首歌曲" }
                            else -> null
                        }
                        count?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                    }
                    }
                }
                // ★ 2026-10-18 用户口径「每日推荐下方」：满宽渐变卡紧跟在「每日推荐」入口卡下面
                //   （不是塞到推荐双列的最底下 —— 第一版放那里把「重试加载推荐」的尾巴挡住了）。
                //   功能 = 经典模式那张「本地音乐」入口（同一个 Routes.LOCAL_MUSIC），
                //   视觉走 m3e 的 Card + containerPainter（见 M3eHomeLocalCard.kt）。
                if (entry.route == Routes.DAILY_RECOMMEND && loggedIn) {
                    item(key = M3E_LOCAL_CARD_KEY, span = StaggeredGridItemSpan.FullLine) {
                        M3eLocalMusicCard(
                            nav = nav,
                            interactive = interactive,
                            gridState = gridState,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
            if (loggedIn) {
                items(recommendations, key = { "recommended_${it.id}" }, contentType = { "recommendation" }) { song ->
                    val transformation = rememberHomeGridTransformation(gridState, "recommended_${song.id}", spec)
                    val container = MaterialTheme.colorScheme.surfaceContainer
                    M3ePressSurface(Modifier.fillMaxWidth().animateItem(), enabled = interactive) {
                    Card(
                        onClick = {
                            PlayerEngine.playQueue(recommendations, recommendations.indexOf(song))
                            nav.navigateWithMotion(Routes.PLAYER)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = interactive,
                        colors = CardDefaults.cardColors(
                            containerColor = container.copy(alpha = (container.alpha - .12f).coerceAtLeast(0f)).m3eSongSurface(.05f),
                        ),
                        contentPadding = PaddingValues(8.dp),
                        transformation = transformation,
                    ) {
                        AsyncImage(song.coverUrl, null, Modifier.fillMaxWidth().aspectRatio(1f)
                            .clip(MaterialTheme.shapes.medium), contentScale = ContentScale.Crop)
                        Spacer(Modifier.height(5.dp))
                        val titleDensity = LocalDensity.current
                        CompositionLocalProvider(LocalDensity provides Density(titleDensity.density, LocalClockFontScale.current)) {
                            Text(song.title, modifier = Modifier.fillMaxWidth().padding(start = recommendationTextInset),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp, lineHeight = 12.sp,
                                maxLines = Int.MAX_VALUE, softWrap = true, overflow = TextOverflow.Clip)
                        }
                        Text(song.artist, modifier = Modifier.padding(start = recommendationTextInset),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    }
                }
                item(key = "recommendation_tail", span = StaggeredGridItemSpan.FullLine) {
                    Box(
                        Modifier.fillMaxWidth().height(52.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (manualRetryAvailable) {
                            Button(
                                onClick = { retryGeneration += 1 },
                                enabled = interactive && !loading,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp)
                                    .pressStretch(enabled = interactive && !loading),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                Text(
                                    if (loading) "正在加载推荐" else "重试加载推荐",
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                )
                            }
                        } else {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun M3eHomeSidebar(nav: NavHostController, loggedIn: Boolean,
    viewportWidth: androidx.compose.ui.unit.Dp, viewportHeight: androidx.compose.ui.unit.Dp, onClose: () -> Unit) {
    val entries = remember(loggedIn) {
        if (loggedIn) listOf(
            M3eHomeEntry("播放列表", Icons.Rounded.QueueMusic, Routes.QUEUE),
            M3eHomeEntry("好友私信", Icons.Rounded.Forum, Routes.MESSAGES),
            M3eHomeEntry("一起听", Icons.Rounded.Headphones, Routes.LISTEN_TOGETHER),
            M3eHomeEntry("设置", Icons.Rounded.Settings, Routes.SETTINGS),
        ) else listOf(
            M3eHomeEntry("播放列表", Icons.Rounded.QueueMusic, Routes.QUEUE),
            M3eHomeEntry("本地音乐", Icons.Rounded.MusicNote, Routes.LOCAL_MUSIC),
            M3eHomeEntry("最近播放", Icons.Rounded.History, Routes.PLAY_HISTORY),
            M3eHomeEntry("设置", Icons.Rounded.Settings, Routes.SETTINGS),
        )
    }
    val density = LocalDensity.current
    val radiusPx = with(density) { minOf(viewportWidth, viewportHeight).toPx() / 2f - 24.dp.toPx() - 4f }
    val stepPx = with(density) { minOf(48.dp, (viewportHeight - 64.dp) / 3f).toPx() }
    // ★ 2026-10-24 用户口径「好友私信与一起听之间 +5px」：只拉开这一对，
    //   上下两半各让 2.5dp，其余相邻间距与整体重心不变。
    val listenIdx = entries.indexOfFirst { it.route == Routes.LISTEN_TOGETHER }
    val pairGapHalfPx = with(density) { 2.5.dp.toPx() }
    // NO-NATIVE: Wear has no arc-positioned drawer. Native Cards use symmetric fixed positions
    // around the horizontal screen axis; no scrolling transformation shifts the endpoints.
    Box(Modifier.fillMaxSize()) {
        entries.forEachIndexed { index, entry ->
            val splitShift = when {
                listenIdx < 0 -> 0f
                index < listenIdx -> -pairGapHalfPx
                else -> pairGapHalfPx
            }
            val dy = (index - (entries.size - 1) / 2f) * stepPx + splitShift
            val dx = sqrt((radiusPx * radiusPx - dy * dy).coerceAtLeast(0f))
            Box(Modifier.align(Alignment.Center).offset {
                IntOffset(dx.roundToInt(), dy.roundToInt())
            }.size(48.dp)) {
                Card(
                    onClick = { onClose(); nav.navigateWithMotion(entry.route) },
                    modifier = Modifier.fillMaxSize().pressStretch(),
                    shape = IconButtonDefaults.shape,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(entry.icon, entry.title, Modifier.size(26.dp))
                    }
                }
            }
        }
    }
}

/**
 * 大卡底面「半透明黑色蒙层」的等效混色比例（★ 2026-10-23 用户口径：
 * 「左上角那张小卡片外面那圈大卡片的底色，在不影响原来图片显示的前提下蒙一层半透明的黑色」）。
 *
 * 实现上不是真叠一层黑：把渐变的每段色 `lerp(color, Black, CARD_BLACK_VEIL)`，
 * 视觉与「黑色 alpha 0.22 蒙层」等价，但只改既有渐变里的颜色 —— 不额外多一层绘制，
 * 也不会盖住卡片上的迷你海报、图标与文字（所以「原来的图片显示」完全不受影响）。
 * 想更暗/更亮只改这一个数：0.30 = 明显压暗，0.12 = 轻微压暗。
 */
private const val CARD_BLACK_VEIL = 0.22f

/**
 * 「这次滚动算不算表冠引起的」判定窗口（★ 2026-10-23 用户口径「滑动时不再有震动」）。
 * 表冠输入后 [CROWN_HAPTIC_WINDOW_MS] 内发生的卡片冒头才震；手指滑动不写这个时间戳，
 * 所以怎么滑都不会震。取 1.2s 是为了覆盖管道的前馈/惯性收尾（表冠松手后仍有惯性滑行），
 * 但短于"手指滑两下再停"的典型间隔，不会把手指滑动误判成表冠。
 */
private const val CROWN_HAPTIC_WINDOW_MS = 1200L
