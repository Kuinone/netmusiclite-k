@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.netmusiclite.ui.m3e

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.style.TextAlign
import com.netmusiclite.ui.components.ListViewportRestorer
import com.netmusiclite.ui.components.RotaryScrollActivity
import com.netmusiclite.ui.components.pressStretch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.ScrollInfoProvider
import androidx.wear.compose.foundation.requestFocusOnHierarchyActive
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material3.*
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.netmusiclite.data.AppearancePrefs
import com.netmusiclite.data.Song
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.SongCover
import com.netmusiclite.ui.components.rotaryList
import java.util.WeakHashMap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

val LocalItemTransformation = compositionLocalOf<SurfaceTransformation?> { null }

/**
 * m3e 列表的两种形态（2026-10-17 用户口径新增）：
 *
 *  · [WEAR]（默认，既有行为一点不动）—— 沿用 Wear M3 的 transforming 形变：
 *    每个 item 用 `transformedHeight(this, spec)` 压缩占位高度，并把 [SurfaceTransformation]
 *    通过 [LocalItemTransformation] 交给 Material 组件（Card / Button / ListHeader）做缩放。
 *    这就是用户口中的**「堆叠」**：滚动时卡片一边缩小、一边把占位高度收掉，相邻条目互相挤压，
 *    自定义内容（BlurHeader 头图、普通 Column 评论行/帖子卡）不参与缩放却在被压缩的高度里溢出，
 *    于是出现「列表项盖住头图按钮」「评论行互相叠」这类问题。
 *
 *  · [SINK] —— **禁用堆叠、只保留下沉**：item 不再调用 `transformedHeight`（占位高度 = 真实高度，
 *    布局随内容走，条目永不互相遮盖），也不向子级提供 transformation（Material 组件不做形变）；
 *    下沉由页面自己声明 `Modifier.m3eSinkFromCenter()`（参数与经典主题 `sinkFromCenter` 同源，
 *    只改渲染层、不改布局 ⇒ 连续阅读流不会被切碎）。
 *
 *  「通宽」（头图铺满圆屏、顶到 y=0）与「某些 item 不参与形变」是独立开关：
 *  [edgeToEdge] 把横向/顶部安全内距置 0；[safeKeys] 里的 item 不做形变（占用真实高度）。
 *  艺人页就是这么组合的：头图通宽 + 头图/Tab 不形变，**歌曲与专辑列表仍保留形变（堆叠）**。
 */
enum class M3eListMode { WEAR, SINK }

/**
 * 下沉效果（m3e 版）：以**屏幕竖直中线**为基准，item 中心离中线越远 → 越小、越暗。
 * 参数与经典主题的 `StackedCardList.sinkFromCenter()` 完全一致（缩放最多 −12%、alpha 最低 0.64、
 * smoothstep 缓动），差异只在于：这里把基准从卡片自身高度换成整屏高度，且**不改变布局高度**。
 *
 * graphicsLayer 内延迟读取 centerY ⇒ 滚动中零重组。
 */
@Composable
fun Modifier.m3eSinkFromCenter(): Modifier {
    if (!AppearancePrefs.isM3e) return this
    val density = LocalDensity.current
    val half = with(density) { (com.netmusiclite.ui.components.STACKED_VIEWPORT / 2).toPx() }
    var centerY by remember { mutableFloatStateOf(half) }
    return this
        .onGloballyPositioned { ic ->
            centerY = ic.positionInRoot().y + ic.size.height / 2f
        }
        .graphicsLayer {
            val t = kotlin.math.abs((centerY - half) / half).coerceIn(0f, 1f)
            val eased = t * t * (3f - 2f * t)
            val sc = 1f - 0.12f * eased
            scaleX = sc
            scaleY = sc
            alpha = 1f - 0.36f * eased
        }
}

/**
 * Keep press deformation outside Wear's surface layer. SurfaceTransformation prepends an
 * offscreen container before the supplied Card/Button modifier, which otherwise clips any
 * press expansion at the original surface bounds, even when graphicsLayer.clip is false.
 */
@Composable
internal fun M3ePressSurface(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.pressStretch(enabled = enabled), content = content)
}

/** A compound lazy item must scale as one container to match its transformed height. */
@Composable
fun M3eTransformingItem(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val transformation = LocalItemTransformation.current
    Box(modifier.graphicsLayer {
        with(transformation ?: return@graphicsLayer) { applyContainerTransformation() }
    }) {
        // The parent now owns the transformation; nested Material surfaces must not apply it again.
        CompositionLocalProvider(LocalItemTransformation provides null) { content() }
    }
}

/** Existing business list DSL, rendered by the actual Wear lazy layout. No nested lazy lists. */
private class WearListScope(
    private val target: TransformingLazyColumnScope,
    private val spec: androidx.wear.compose.material3.lazy.TransformationSpec,
    /** 列表形态：WEAR = 既有形变（堆叠）；SINK = 禁形变（条目永不互相挤压） */
    private val mode: M3eListMode,
    /** 这些 item key 在 WEAR 模式下也不参与形变（占用真实高度），用于头图/Tab 这类复合内容 */
    private val safeKeys: Set<Any?> = emptySet(),
) : LazyListScope {
    private val itemScope = object : LazyItemScope {
        override fun Modifier.fillParentMaxSize(fraction: Float) = fillMaxSize(fraction)
        override fun Modifier.fillParentMaxWidth(fraction: Float) = fillMaxWidth(fraction)
        override fun Modifier.fillParentMaxHeight(fraction: Float) = height(233.dp * fraction)
    }
    override fun item(key: Any?, contentType: Any?, content: @Composable LazyItemScope.() -> Unit) {
        target.item(key, contentType) {
            if (mode == M3eListMode.WEAR && !safeKeys.contains(key)) {
                val transformation = SurfaceTransformation(spec)
                Box(Modifier.fillMaxWidth().transformedHeight(this, spec)) {
                    CompositionLocalProvider(LocalItemTransformation provides transformation) { content(itemScope) }
                }
            } else {
                // 不压缩占位高度（否则自定义内容溢出、条目互相遮盖 = 用户报的「堆叠」），
                // 也不把 transformation 交给子级（Material 组件不做缩放形变）。
                // 下沉不在框架层做：SectionLabel / 加载提示 / footer 等非卡片条目不该一起缩放变暗，
                // 由页面给卡片自己声明 `Modifier.m3eSinkFromCenter()`。
                Box(Modifier.fillMaxWidth()) {
                    CompositionLocalProvider(LocalItemTransformation provides null) { content(itemScope) }
                }
            }
        }
    }
    override fun items(count: Int, key: ((Int) -> Any)?, contentType: (Int) -> Any?, itemContent: @Composable LazyItemScope.(Int) -> Unit) {
        target.items(count, key, contentType) { index ->
            if (mode == M3eListMode.WEAR) {
                val transformation = SurfaceTransformation(spec)
                Box(Modifier.fillMaxWidth().transformedHeight(this, spec)) {
                    CompositionLocalProvider(LocalItemTransformation provides transformation) { itemContent(itemScope, index) }
                }
            } else {
                Box(Modifier.fillMaxWidth()) {
                    CompositionLocalProvider(LocalItemTransformation provides null) { itemContent(itemScope, index) }
                }
            }
        }
    }
}

// Keep legacy state-based pagination, restoration and chat autoscroll connected to the native list.
//
// ★ 2026-10-18 用户 bug「m3e 主题的歌曲评论区加载一次后划到底不会继续加载」的根因就在这一行。
//   原来是 WeakHashMap：页面的派生状态（评论区的 endVisible）在**首次组合时**就要读它 —— 而
//   NativeList 是在 DisposableEffect 里才把 native 注册进来的，晚于派生状态求值，于是首次
//   读到 null、退回经典 LazyListState.layoutInfo（m3e 下经典 state 根本不被驱动，恒为 0 项）。
//   WeakHashMap 的 get 又**不是 snapshot 依赖** ⇒ derivedStateOf 把「看不到底」缓存下来永不重算，
//   翻页 Effect 再也不会触发（经典主题走原生 LazyColumn，没有这个问题）。
//   换成 mutableStateMapOf：native 注册/注销成为 snapshot 依赖，派生状态会重算并读到 native 的
//   真实 layoutInfo；之后滚动时 layoutInfo 变化照常让派生状态失效。
//   泄漏：NativeList 的 DisposableEffect onDispose 会 remove，与 WeakHashMap 语义等价。
private val nativeLists = mutableStateMapOf<LazyListState, TransformingLazyColumnState>()
fun listLayoutInfo(state: LazyListState): LazyListLayoutInfo {
    val native = if (AppearancePrefs.isM3e) nativeLists[state] else null
    val info = native?.layoutInfo ?: return state.layoutInfo
    return object : LazyListLayoutInfo {
        override val visibleItemsInfo = info.visibleItems.map { item -> object : LazyListItemInfo {
            override val index = item.index
            override val key = item.key
            override val offset = item.offset
            override val size = item.transformedHeight
            override val contentType = item.contentType
        } }
        override val viewportStartOffset = 0
        override val viewportEndOffset = info.viewportSize.height
        override val totalItemsCount = info.totalItemsCount
        override val viewportSize = info.viewportSize
        override val beforeContentPadding = info.beforeContentPadding
        override val afterContentPadding = info.afterContentPadding
    }
}
fun listFirstIndex(state: LazyListState) = if (AppearancePrefs.isM3e && nativeLists.containsKey(state)) listLayoutInfo(state).visibleItemsInfo.firstOrNull()?.index ?: 0 else state.firstVisibleItemIndex
fun listFirstOffset(state: LazyListState) = if (AppearancePrefs.isM3e && nativeLists.containsKey(state)) -(listLayoutInfo(state).visibleItemsInfo.firstOrNull()?.offset ?: 0) else state.firstVisibleItemScrollOffset
suspend fun LazyListState.adaptiveScrollToItem(index: Int, scrollOffset: Int = 0) {
    val native = if (AppearancePrefs.isM3e) nativeLists[this] else null
    if (native == null) scrollToItem(index, scrollOffset) else native.scrollToItem(index, scrollOffset)
}
suspend fun LazyListState.adaptiveAnimateScrollToItem(index: Int, scrollOffset: Int = 0) {
    val native = if (AppearancePrefs.isM3e) nativeLists[this] else null
    if (native == null) animateScrollToItem(index, scrollOffset) else native.animateScrollToItem(index, scrollOffset)
}
suspend fun LazyListState.adaptiveScrollBy(value: Float): Float {
    val target: androidx.compose.foundation.gestures.ScrollableState = if (AppearancePrefs.isM3e) nativeLists[this] ?: this else this
    return target.scrollBy(value)
}
fun LazyListState.adaptiveDispatchRawDelta(value: Float): Float {
    val target: androidx.compose.foundation.gestures.ScrollableState = if (AppearancePrefs.isM3e) nativeLists[this] ?: this else this
    return target.dispatchRawDelta(value)
}

@Composable
fun M3eList(
    title: String? = null,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    mode: M3eListMode = M3eListMode.WEAR,
    /**
     * ★ 2026-10-23：这些 key 的 item **不参与 Wear 列表形变**（占真实布局高度）。
     * 与 [AdaptiveLazyColumn] 的同名参数同义 —— 此前只顾了一个入口，导致「我的歌单」页
     * 直接调 M3eList 时没法把 tab 行排除在形变之外（形变压缩 item 高度会把按钮里的文字裁掉）。
     */
    safeKeys: Set<Any?> = emptySet(),
    content: LazyListScope.() -> Unit,
) {
    NativeList(modifier.fillMaxSize(), state, title = title, mode = mode, safeKeys = safeKeys, content = content)
}

/** Drop-in rendering bridge for screens with custom data and gestures. Classic path is unchanged. */
@Composable
fun AdaptiveLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical = if (!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    flingBehavior: FlingBehavior = ScrollableDefaults.flingBehavior(),
    userScrollEnabled: Boolean = true,
    /** m3e 下的列表形态（经典主题忽略此参数）：见 [M3eListMode] */
    mode: M3eListMode = M3eListMode.WEAR,
    /** 内容通宽（头图铺到圆屏边缘/顶部 y=0）；正文由页面自己补水平内距 */
    edgeToEdge: Boolean = false,
    /** WEAR 模式下这些 key 的 item 不做形变（占用真实高度），如头图 / Tab 行 */
    safeKeys: Set<Any?> = emptySet(),
    content: LazyListScope.() -> Unit,
) {
    if (!AppearancePrefs.isM3e) {
        // ★ 2026-10-23 修复项 10：经典主题的列表位置记忆 / 延迟恢复。
        //   放在这个桥接里而不是逐页改：所有用 `AdaptiveLazyColumn as LazyColumn` 的页面
        //   （歌手页 / 歌单详情 / 播放列表 / 搜索 / 设置 / 评论 / 私信…）以及 StackedCardList、
        //   NcmListPage 的经典路径都会经过这里，一处生效全站受益。
        //   （首页 M3eHome 用的是 LazyStaggeredGrid，不走这里，见交付报告。）
        ListViewportRestorer(state)
        LazyColumn(modifier, state, contentPadding, reverseLayout, verticalArrangement, horizontalAlignment, flingBehavior, userScrollEnabled, content = content)
    } else {
        NativeList(modifier, state, extraPadding = contentPadding, userScrollEnabled = userScrollEnabled,
            mode = mode, edgeToEdge = edgeToEdge, safeKeys = safeKeys, content = content)
    }
}

@Composable
private fun NativeList(
    modifier: Modifier,
    state: LazyListState,
    title: String? = null,
    extraPadding: PaddingValues = PaddingValues(0.dp),
    userScrollEnabled: Boolean = true,
    mode: M3eListMode = M3eListMode.WEAR,
    /** 内容通宽（横向安全内距 = 0、顶部内距 = 0）：头图类页面用，正文自行补内距 */
    edgeToEdge: Boolean = false,
    /** WEAR 模式下这些 key 的 item 不做形变（占用真实高度），用于头图 / Tab 这类复合内容 */
    safeKeys: Set<Any?> = emptySet(),
    content: LazyListScope.() -> Unit,
) {
    // ★ 2026-10-23 修复项 10：初始锚点只在**首次组合**取一次（remember 无 key）。
    //   不能直接把 `state.firstVisibleItemIndex` 传进 rememberTransformingLazyColumnState：
    //   它内部是 `rememberSaveable(inputs = [index, offset], saver = Saver)` —— inputs 一变就会
    //   丢掉当前原生滚动状态重新初始化。而下面 M3eListPositionKeeper 会把真实锚点回写进这个
    //   经典 state（正是为了让页面的「加载中先保视口」判断生效），若这里把 state 值当 inputs，
    //   每次滚动停止都会重置一次原生列表（位置/预取/形变全重来）。
    //   首次组合时 state 已由页面 rememberLazyListState 的 saveable 恢复完成，用 remember 固定即可。
    val initialAnchorIndex = remember { state.firstVisibleItemIndex }
    val initialAnchorOffset = remember { state.firstVisibleItemScrollOffset }
    val native = rememberTransformingLazyColumnState(initialAnchorIndex, initialAnchorOffset)
    // ★ 2026-10-23 修复项 10：m3e 列表的位置回写 / 记忆 / 延迟恢复（详见 M3eListPositionKeeper）。
    M3eListPositionKeeper(state, native)
    val spec = rememberTransformationSpec()
    DisposableEffect(state, native) {
        nativeLists[state] = native
        onDispose { if (nativeLists[state] === native) nativeLists.remove(state) }
    }
    // ★ 2026-10-17 第四轮（m3e 表冠按设备分流）：
    //   谷歌/三星 → Wear 原生 rotaryScrollable 直接驱动真正的 TransformingLazyColumnState
    //   （此前 m3e 的这些列表根本没有表冠输入）；
    //   OPPO/华为等 → 复用默认主题的平滑管道（rotaryList），它在 m3e 下的 dispatch 走
    //   adaptiveDispatchRawDelta ⇒ 映射到上面注册进 nativeLists 的原生状态。
    //   Material 组件（TransformingLazyColumn 的形变、ScreenScaffold 进度）只读同一个
    //   真实 state 做外观显示，所以不需要额外喂输入。
    val useNativeRotary = com.netmusiclite.data.DeviceCompat.useNativeWearRotary
    val focusRequester = remember { FocusRequester() }
    val rotaryModifier: Modifier = when {
        !userScrollEnabled -> Modifier
        useNativeRotary -> {
            val behavior = RotaryScrollableDefaults.behavior(native)
            Modifier
                .requestFocusOnHierarchyActive()
                .rotaryScrollable(behavior = behavior, focusRequester = focusRequester)
        }
        else -> Modifier.rotaryList(state)
    }
    // ★ 2026-10-17 第五轮（真机反馈「m3e 滑动进度条不认表冠滚动、会自动隐藏」）：
    //   Wear M3 的 ScreenScaffold 用 ScrollInfoProvider.isScrollInProgress 决定「是否在滚动」，
    //   据此显示滑动进度条、把 TimeText 卷走。原生 rotaryScrollable 走 scroll 会话 ⇒ 该值为 true；
    //   而 OPPO/华为等用的自定义平滑管道是逐帧 dispatchRawDelta（没有 scroll 会话）⇒ 恒 false，
    //   于是表冠滚动时 Scaffold 误判 Idle、进度条自动隐藏。这里包一层 provider：
    //   只把「表冠活跃」并入 isScrollInProgress（**纯显示口径**），滚动本身仍由真实 state 提供，
    //   Material 组件只读真实进度做外观（空挂载思路不变）。
    val baseProvider = remember(native) { ScrollInfoProvider(native) }
    val crownAwareProvider = remember(baseProvider) {
        object : ScrollInfoProvider {
            override val isScrollAwayValid: Boolean get() = baseProvider.isScrollAwayValid
            override val isScrollable: Boolean get() = baseProvider.isScrollable
            override val isScrollInProgress: Boolean
                get() = baseProvider.isScrollInProgress || RotaryScrollActivity.active
            override val anchorItemOffset: Float get() = baseProvider.anchorItemOffset
            override val lastItemOffset: Float get() = baseProvider.lastItemOffset
        }
    }
    ScreenScaffold(
        modifier = modifier,
        scrollInfoProvider = crownAwareProvider,
        // 与「ScreenScaffold(scrollState = native)」的默认值保持一致：显式重挂同款进度条。
        scrollIndicator = { ScrollIndicator(native) },
    ) { padding ->
        // 466px design: 18px left and 32px right keeps every card clear of the native arc.
        // ★ 2026-10-17（用户口径）「设置里关于应用/官方QQ群/捐赠作者的图片与文字不居中」：
        //   根因是这里的横向安全内距**左右不对称**（start=9dp / end=16dp，为避开右缘进度弧）。
        //   列表项通宽 = 屏宽 − 25dp，内容中心比屏幕中线偏左 3.5dp（466px 屏上 7px），
        //   居中排布的图标/二维码/标题就整体左偏。现在取两侧最大值后**对称**收边：
        //   右侧仍留 16dp 避开进度弧，左侧同宽 ⇒ 内容严格沿屏幕竖直中线对称。
        //   [edgeToEdge]（头图必须通宽覆盖的页面）横向内距置 0、顶部内距置 0，由页面自己补正文内距。
        val layoutDirection = LocalLayoutDirection.current
        val horizontalInset = if (edgeToEdge) 0.dp else maxOf(
            9.dp, 16.dp,
            extraPadding.calculateStartPadding(layoutDirection),
            extraPadding.calculateEndPadding(layoutDirection),
        )
        val safePadding = PaddingValues(
            start = horizontalInset,
            end = horizontalInset,
            top = if (edgeToEdge) 0.dp
            else maxOf(padding.calculateTopPadding(), extraPadding.calculateTopPadding()),
            bottom = maxOf(padding.calculateBottomPadding(), extraPadding.calculateBottomPadding()),
        )
        TransformingLazyColumn(state = native, modifier = Modifier.fillMaxSize().then(rotaryModifier), contentPadding = safePadding,
            verticalArrangement = Arrangement.spacedBy(5.dp), userScrollEnabled = userScrollEnabled) {
            if (!title.isNullOrBlank()) item(key = "m3e-heading") {
                if (mode != M3eListMode.WEAR || safeKeys.contains("m3e-heading")) {
                    // 禁形变模式：标题也不参与形变（否则列表头被压缩、顶部标题与首项贴死）
                    ListHeader(modifier = Modifier.fillMaxWidth(), transformation = null) { Text(title) }
                } else {
                    ListHeader(modifier = Modifier.fillMaxWidth().transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) { Text(title) }
                }
            }
            content(WearListScope(this, spec, mode, safeKeys))
        }
    }
}

/**
 * m3e 列表的滚动位置：回写 + 记忆 + 延迟恢复（★ 2026-10-23 修复项 10）。
 *
 * 用户 bug：从歌单 / 播放列表 / 歌手页点歌进播放页，返回后列表直接回到顶部。
 *
 * 【根因（m3e 特有，比经典主题更隐蔽）】
 *   m3e 下 NativeList 只渲染 TransformingLazyColumn，**真正的滚动在 [TransformingLazyColumnState]
 *   上**，页面传进来的经典 [LazyListState] 是「空挂」的 —— 既不参与布局、也永远不会被滚动驱动，
 *   firstVisibleItemIndex/firstVisibleItemScrollOffset 恒为 0。于是：
 *     · 页面里「加载中先别渲染列表、把视口留给恢复」这类保护（M3eCollectionSongs 的
 *       `loading && state.firstVisibleItemIndex > 0`）在 m3e 下永远不成立 ⇒ 数据没回来就先渲染
 *       空列表 ⇒ 恢复出来的锚点被 clamp 到 0 ⇒ 数据到位后也不回滚 = 回到顶部；
 *     · 页面 rememberLazyListState 的 saveable 存的一直是 0，返回时也恢复不出位置。
 *
 * 【做法】
 *   ① 回写：把 native 的真实锚点写回经典 state（滚动停止后 + 离开组合时各一次）。
 *      滚动中不写 —— 读 state 的页面（如 M3eCollectionSongs 的 loading 判断）会被每帧重组。
 *   ② 记忆：同样只在滚动停止后、且只记用户自己滚出来的位置（userScrolled 之后），
 *      用 rememberSaveable 保存 ⇒ 页面重建后取回。数据未到位时 clamp 出来的 0 不会污染记忆。
 *   ③ 恢复：重建后若「记忆位置 > 0 且当前不在该位置」，等条目数长到能容纳它（数据到位）
 *      再 scrollToItem 一次；用户已接管滚动 / 2s 超时则放弃，绝不在常规重组里反复回滚。
 */
@Composable
private fun M3eListPositionKeeper(state: LazyListState, native: TransformingLazyColumnState) {
    val savedIndex = rememberSaveable { mutableIntStateOf(0) }
    val savedOffset = rememberSaveable { mutableIntStateOf(0) }
    var userScrolled by remember { mutableStateOf(false) }

    // ① 回写：进入组合时对齐一次（native 可能已从 saveable 恢复、经典 state 还是 0），
    //    离开组合时补最后一次（用户滚到某处直接点歌离开，滚动停止事件可能已过）。
    DisposableEffect(native, state) {
        state.requestScrollToItem(native.anchorItemIndex, native.anchorItemScrollOffset)
        onDispose {
            val index = native.anchorItemIndex
            // 离开前补记最后一次位置（rememberSaveable 的保存顺序不保证在 onDispose 之后，
            // 但 native 自己的 Saver 也存了同一锚点，两条路互为兜底）
            if (native.layoutInfo.totalItemsCount > index) {
                savedIndex.intValue = index
                savedOffset.intValue = native.anchorItemScrollOffset
            }
            state.requestScrollToItem(index, native.anchorItemScrollOffset)
        }
    }

    // ② 滚动停止后回写 + 记忆
    LaunchedEffect(native, state) {
        snapshotFlow { native.isScrollInProgress }.collect { scrolling ->
            if (scrolling) {
                userScrolled = true
            } else {
                state.requestScrollToItem(native.anchorItemIndex, native.anchorItemScrollOffset)
                if (userScrolled) {
                    val index = native.anchorItemIndex
                    if (native.layoutInfo.totalItemsCount > index) {
                        savedIndex.intValue = index
                        savedOffset.intValue = native.anchorItemScrollOffset
                    }
                }
            }
        }
    }

    // ③ 延迟恢复：只尝试一次
    LaunchedEffect(native) {
        val target = savedIndex.intValue
        val targetOffset = savedOffset.intValue
        if (target <= 0 && targetOffset <= 0) return@LaunchedEffect
        // native 自己已经从 saveable 恢复到同一位置（数据充足时常见）→ 无需再滚
        if (native.anchorItemIndex == target && native.anchorItemScrollOffset == targetOffset) {
            return@LaunchedEffect
        }
        val restored = withTimeoutOrNull(2000L) {
            snapshotFlow { native.layoutInfo.totalItemsCount to native.isScrollInProgress }
                .first { (total, scrolling) -> scrolling || total > target }
            !native.isScrollInProgress
        } ?: false
        if (restored) native.scrollToItem(target, targetOffset)
    }
}

@Composable
fun M3eRowCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    height: androidx.compose.ui.unit.Dp = 62.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 13.dp),
    content: @Composable RowScope.() -> Unit,
) {
    M3ePressSurface(modifier.fillMaxWidth(), enabled = onClick != null || onLongClick != null) {
    Card(onClick = onClick ?: {}, onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = height),
        contentPadding = contentPadding, shape = MaterialTheme.shapes.large,
        transformation = LocalItemTransformation.current) {
        Row(Modifier.fillMaxWidth().heightIn(min = height), verticalAlignment = Alignment.CenterVertically, content = content)
    }
    }
}

@Composable
fun M3eIconCard(title: String, icon: ImageVector, subtitle: String?, modifier: Modifier, onClick: () -> Unit) {
    M3ePressSurface(modifier.fillMaxWidth()) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.filledTonalButtonColors(), transformation = LocalItemTransformation.current,
        icon = { Icon(icon, null) }, secondaryLabel = subtitle?.let { { Text(it, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, maxLines = 2) } },
        label = { Text(modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, text = title, maxLines = 2) })
    }
}

@Composable
fun M3eSongCard(song: Song, onClick: () -> Unit, modifier: Modifier = Modifier, isCurrent: Boolean = false,
    onLongClick: (() -> Unit)? = null, selectMode: Boolean = false, selected: Boolean = false,
    aliasHint: String? = null, trailing: (@Composable () -> Unit)? = null) {
    // ★ 2026-10-23 需求④「m3e 一起听多选添加歌曲界面无法长按多选 / 无法主动添加歌曲」
    //
    // 【真因】本卡此前把长按交给 Wear M3 Button 的 `onLongClick` 参数。该参数确实存在、也确实
    //   传进了 combinedClickable（1.5.6 的 ButtonKt 字节码可证），但在真机的**组合环境**里不成立：
    //   这张卡同时处在 ① TransformingLazyColumn 的形变 item（transformedHeight + 双重
    //   SurfaceTransformation）、② AddSongOverlay 浮层外层手势层（根 Box 的横滑 + 点击关闭）、
    //   ③ M3ePressSurface 的按压观察器 之下，长按进不了多选态。
    //   连带后果就是用户说的「无法主动添加歌曲」：多选入口压根没出现 → 底部没有
    //   「添加 N 首到房间」胶囊，只剩单选点击。
    //
    // 【修法】改抄项目内**唯一被真机验证过**的长按写法（M3eHome 主页卡片 0.8s 长按）：
    //   · Initial pass 拿 down，**全程不消费** —— 点击、按压形变、列表滚动都不受影响；
    //   · Final pass 观察（所有消费已完成，是唯一能看到滚动消费的时机）；
    //   · 只认「触点位移 > touchSlop」或「提前松手」为取消 ——
    //     ⚠ 绝不能用 `change.isConsumed` 判取消：Button 的 clickable 在 Main pass 就消费 down，
    //       那样长按会**永远不成立**（M3eHome 注释记录的真机坑）；
    //   · 500ms 成立 → onLongClick()，并记 longPressFiredAt，让本次手势松手后 Button
    //     补发的 onClick 被吞掉（否则长按会顺带「加一首歌」）。
    val currentLongClick by rememberUpdatedState(onLongClick)
    var longPressFiredAt by remember { mutableStateOf(0L) }
    val longPressModifier = if (onLongClick == null) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            longPressFiredAt = 0L
            val slop = viewConfiguration.touchSlop
            val cancelled = withTimeoutOrNull(500L) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val dx = change.position.x - down.position.x
                    val dy = change.position.y - down.position.y
                    if (dx * dx + dy * dy > slop * slop) break
                }
                true
            }
            if (cancelled == null) {
                longPressFiredAt = android.os.SystemClock.uptimeMillis()
                currentLongClick?.invoke()
            }
        }
    }
    M3ePressSurface(modifier.fillMaxWidth().then(longPressModifier)) {
    Button(onClick = {
        // 长按已触发 → 吞掉本次手势松手后补发的 onClick（旗标在下一次按下时由观察器清零）
        if (longPressFiredAt == 0L) onClick()
    },
        onLongClick = null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainer.m3eSongSurface(),
            contentColor = MaterialTheme.colorScheme.onSurface),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        transformation = LocalItemTransformation.current) {
        SongCover(song, 40.dp, shape = MaterialTheme.shapes.small)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(song.artist, aliasHint).joinToString(" · "),
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selectMode) Icon(if (selected) NcmIcons.Check else NcmIcons.Plus, if (selected) "已选择" else "未选择", modifier = Modifier.size(16.dp))
        else if (isCurrent) Icon(NcmIcons.NowPlaying, "正在播放", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
        trailing?.invoke()
    }
    }
}
