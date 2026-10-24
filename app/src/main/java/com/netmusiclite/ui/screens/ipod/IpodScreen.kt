package com.netmusiclite.ui.screens.ipod

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.netmusiclite.data.Song
import com.netmusiclite.ui.components.CenterHint
import com.netmusiclite.ui.components.CoverImage
import com.netmusiclite.ui.components.rotaryList
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.NavMotionSpec
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.navigateWithMotion
import com.netmusiclite.ui.screens.ipod.coverflow.CoverFlow
import com.netmusiclite.ui.screens.ipod.coverflow.CoverFlowParams
import com.netmusiclite.ui.screens.ipod.coverflow.CoverFlowState
import com.netmusiclite.ui.screens.ipod.coverflow.rememberCoverFlowState
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 随机抽取（iPod CoverFlow）选歌屏（2026-10-17 用户口径第二版）：
 * · 长按主页四张大卡片 0.5 秒进入（主页侧实现），本页只负责抽取与直接起播；
 * · 左右滑动 / 转动表冠，随机排列的**单曲封面**流；上方不写字、下方标题小字号完整显示；
 * · 点居中封面 → 直接以随机顺序播放、从这首开始，并**直接进播放页**（无中间页）；
 * · 底部画一个向上箭头；上滑直接回主页。
 *
 * [onExitRequest]（2026-10-17 退出转场）：m3e 主题下由 AppNav 的 IpodExitHost 注入 ——
 * 先播「镜像前进方向」的整页缩小+淡出，播完才真正 pop。上滑返回与系统返回键都走它；
 * 右滑返回仍是 Wear 原生 swipe-dismiss（不需要经过这里）。经典主题传 null：那边由
 * NavHost 自带的 popExit 缩放转场收场，本页直接 pop 即可，不叠两层动画。
 *
 * ★ 2026-10-17 进页提速：不再用整屏 spinner 等网络 —— 先出骨架（占位封面 + 进度），
 *   数据拉取 / 上千首的 map + 洗牌全部在 Dispatchers.Default 完成，主线程只做一次赋值。
 */
@Composable
fun IpodCoverFlowScreen(
    nav: NavHostController,
    source: IpodSource,
    onExitRequest: (() -> Unit)? = null,
) {
    var picks by remember { mutableStateOf<List<IpodPick>>(emptyList()) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 系统返回键（实体返回 / 华为式系统级右滑派发的 back）：m3e 下先播退出动画再 pop。
    // ⚠ 只在本层拦截：onExitRequest == null（经典主题）时禁用，交给 NavHost 原生 popExit。
    androidx.activity.compose.BackHandler(enabled = onExitRequest != null) {
        onExitRequest?.invoke()
    }

    // 「随机抽取」：每次进入都重新洗牌。
    // 为什么整个拉取都放 Default：网络/缓存 suspend 返回后，liked 这类上千首的 map（每首都要
    // 解析封面来源）+ shuffle 会一次性压在主线程上；挪到 Default 后首帧不被占住。
    LaunchedEffect(source) {
        loading = true
        val loaded = runCatching {
            withContext(Dispatchers.Default) {
                val page = loadIpodPickPage(source)
                page to page.picks.shuffled()
            }
        }.getOrNull()
        // 三次写在同一段无挂起代码里 → 同帧生效，只触发一次重组
        picks = loaded?.second.orEmpty()
        songs = loaded?.first?.songs.orEmpty()
        loading = false
    }

    // 点封面：歌曲来源 → 直接用该来源全集随机播放；歌单来源 → 先拉歌单曲目再随机起播。
    // 两条路径都直接进播放页，没有中间页面（用户 2026-10-17 明确要求）。
    fun start(pick: IpodPick) {
        if (busy) return
        val direct = pick.song
        if (direct != null && songs.isNotEmpty()) {
            playIpodShuffled(songs, direct)
            nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Pill))
            return
        }
        busy = true
        scope.launch {
            val list = pick.resolvedSongs()
            busy = false
            if (list.isEmpty()) return@launch
            playIpodShuffled(list, list.random())
            nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Pill))
        }
    }

    Box(Modifier.fillMaxSize()) {
        when {
            // 数据未到：先出页面骨架（占位封面 + 进度），比整屏转圈体感快
            loading && picks.isEmpty() -> IpodLoadingSkeleton()
            picks.isEmpty() -> CenterHint(
                "暂时没有可抽取的封面\n检查登录态与网络后重进",
                Modifier.fillMaxSize(),
            )
            else -> IpodCoverFlowPage(
                source = source,
                picks = picks,
                onPick = ::start,
                // 上滑返回：m3e 先播退出转场再 pop；经典主题直接 pop（NavHost 自带 popExit）
                onBackHome = { if (onExitRequest != null) onExitRequest() else nav.popBackStack() },
            )
        }
        if (busy) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

/** 数据未到时的骨架：与真实封面同尺寸的占位块 + 居中进度，先让页面壳成型。 */
@Composable
private fun IpodLoadingSkeleton() {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val coverSize = minOf(maxWidth, maxHeight) * .5f
        Box(
            Modifier
                .size(coverSize)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
        )
        CircularProgressIndicator(Modifier.size(26.dp))
    }
}

@Composable
private fun IpodCoverFlowPage(
    source: IpodSource,
    picks: List<IpodPick>,
    onPick: (IpodPick) -> Unit,
    onBackHome: () -> Unit,
) {
    // engine 的 remember 闭包只建一次，回调里改读 rememberUpdatedState 的快照，避免捕获旧值
    val picksRef = rememberUpdatedState(picks)
    val pickRef = rememberUpdatedState(onPick)
    val centered = remember { mutableIntStateOf(0) }
    val stateRef = remember { mutableStateOf<CoverFlowState?>(null) }
    val context = LocalContext.current
    // 「随机出现」：进页落点在这里一次定好（每次 picks 变化重掷一次），
    // 既作为 LazyRow 的初始列表位置（首帧就靠近随机卡，不闪第 0 张），
    // 也作为后面像素级居中的目标（见 BoxWithConstraints 里的 jumpToItem）。
    // 不放在 LaunchedEffect 里再掷：避免「先画第 0 张、再跳」的一帧闪烁。
    val initialCard = remember(picks) {
        if (picks.size > 1) Random.nextInt(picks.size) else 0
    }

    val state = rememberCoverFlowState(
        onSelectHandler = { index -> if (index >= 0) centered.intValue = index },
        onItemClick = { index ->
            val list = picksRef.value
            if (index == centered.intValue) {
                list.getOrNull(index)?.let(pickRef.value)
            } else {
                stateRef.value?.scrollToItem(index.coerceIn(0, (list.size - 1).coerceAtLeast(0)))
            }
        },
        // 列表首尾各有一个 spacer（见 coverflow/CoverFlow.kt），列表下标 = 卡片下标 + 1
        initialIndex = initialCard + 1,
    )
    SideEffect { stateRef.value = state }

    // 表冠按设备分流（2026-10-17 第四轮，见 DeviceCompat.useNativeWearRotary）：
    // · 谷歌/三星表 → Wear 原生 rotaryScrollable（原有接法：M3eHome 已验证、真机可用）；
    // · OPPO/华为等其他设备 → 复用默认主题的平滑管道（rotaryList：前馈+位置伺服+惯性滑行
    //   + NcmHaptics 刻度震动 + 方向开关 + 华为 RotaryFallback 兜底）真正驱动 CoverFlow。
    val focusRequester = remember { FocusRequester() }
    val nativeRotary = com.netmusiclite.data.DeviceCompat.useNativeWearRotary
    val rotaryModifier: Modifier = if (nativeRotary) {
        val rotary = RotaryScrollableDefaults.behavior(state.lazyListState)
        Modifier
            .requestFocusOnHierarchyActive()
            .rotaryScrollable(behavior = rotary, focusRequester = focusRequester)
    } else {
        Modifier.rotaryList(state.lazyListState)
    }

    // 表冠/滑动停稳后吸附到最近一张（rotary behavior 只负责滚动，吸附在这里补）。
    // snapshotFlow 只在「滚动起停」时发事件，函数体本身不进组合；130ms 去抖后直接用
    // 上面已经算好的 item.index 定位，不再二次读 layoutInfo（去掉停稳路径的多余工作）。
    LaunchedEffect(state, picks) {
        snapshotFlow { state.lazyListState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) {
                delay(130)
                if (!state.lazyListState.isScrollInProgress) {
                    val info = state.lazyListState.layoutInfo
                    val center = info.viewportSize.width / 2f
                    val item = info.visibleItemsInfo.minByOrNull {
                        abs(it.offset + it.size / 2f - center)
                    }
                    val deviation = item?.let { abs(it.offset + it.size / 2f - center) } ?: 0f
                    if (item != null && deviation > 3f) {
                        // 列表首尾各有一个 spacer（见 coverflow/CoverFlow.kt），列表下标 - 1 = 卡片下标
                        state.scrollToItem((item.index - 1).coerceIn(0, (picks.size - 1).coerceAtLeast(0)))
                    }
                }
            }
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .then(rotaryModifier)
            // 「上滑动直接返回主页」：竖直上滑超过阈值松手即回（覆盖层图标见页面底部）
            .pointerInput(source) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragCancel = { total = 0f },
                    onVerticalDrag = { _, dy -> total += dy },
                    onDragEnd = { if (total < -56.dp.toPx()) onBackHome() },
                )
            },
    ) {
        val coverSize = minOf(maxWidth, maxHeight) * .5f
        // 首帧几何：BoxWithConstraints 已经给出容器尺寸，换算成 px 直接交给 CoverFlow ——
        // 否则要等「首测 → onGloballyPositioned 回写 → 重组」一轮才组合列表项，进页随机位置
        // 只能第二帧生效，会先闪一下第 0 张。
        val initialFlowSize = with(LocalDensity.current) {
            IntSize(maxWidth.roundToPx(), maxHeight.roundToPx())
        }

        // 「随机出现」的像素级居中：初始列表位置只把随机卡摆到视口左缘附近（首帧不闪第 0 张），
        // 这里等 LazyRow 首帧布局完成后再瞬时对齐到正中，并预热其后几张封面。
        // 旧实现用带弹簧的 scrollToItem：从第 0 张动画滚到随机第 N 张 = 连续组合/解码
        // 几百张封面（「我喜欢」上千首时的进页卡顿主因）；瞬时定位只组合最终视口内的几张。
        LaunchedEffect(picks, state) {
            if (picks.size > 1) {
                snapshotFlow { state.lazyListState.layoutInfo.totalItemsCount }.first { it > 2 }
                state.jumpToItem(initialCard)
                centered.intValue = initialCard
                // 预热「当前没在视口里、再滑一两格就会露出来」的封面（尺寸与 CoverImage 解码口径一致）
                prewarmIpodCovers(
                    context = context,
                    picks = picks,
                    centerIndex = initialCard,
                    requestSizePx = coverSize.value.toInt() * 2,
                )
            }
        }

        CoverFlow(
            modifier = Modifier.fillMaxSize(),
            state = state,
            params = CoverFlowParams(),
            initialSize = initialFlowSize,
        ) {
            items(picks.size) { index ->
                CoverImage(
                    picks[index].cover,
                    coverSize,
                    Modifier.fillMaxSize(),
                    RoundedCornerShape(10.dp),
                )
            }
        }

        // 底部：小字号标题（完整显示优先）+ 上滑返回图标
        IpodBottomBar(
            picks = picks,
            // 传 lambda 而不是当前值：centered 的读取发生在 IpodBottomBar 内部，
            // 滚动停稳换选中卡时只重组这一小段，不再带着整页（含 LazyRow）一起重组。
            centeredIndex = { centered.intValue },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** 底部标题区：当前卡片标题 / 副标题 + 上滑返回箭头。 */
@Composable
private fun IpodBottomBar(
    picks: List<IpodPick>,
    centeredIndex: () -> Int,
    modifier: Modifier = Modifier,
) {
    val current = picks.getOrNull(centeredIndex())
    Column(
        modifier = modifier.padding(bottom = 10.dp, start = 30.dp, end = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (current != null) {
            Text(
                current.title,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                current.subtitle,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        UpSwipeHint(
            Modifier.padding(top = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 简化「向上箭头」：两根 45° 线组成的 chevron，代表上滑返回主页。 */
@Composable
private fun UpSwipeHint(modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color) {
    Canvas(modifier.width(18.dp).height(10.dp)) {
        val stroke = 1.6.dp.toPx()
        val w = size.width
        val h = size.height
        val topY = h * .12f
        val bottomY = h * .88f
        val tipX = w / 2f
        drawLine(
            color = color.copy(alpha = .65f),
            start = Offset(w * .08f, bottomY),
            end = Offset(tipX, topY),
            strokeWidth = stroke, cap = StrokeCap.Round,
        )
        drawLine(
            color = color.copy(alpha = .65f),
            start = Offset(tipX, topY),
            end = Offset(w * .92f, bottomY),
            strokeWidth = stroke, cap = StrokeCap.Round,
        )
    }
}
