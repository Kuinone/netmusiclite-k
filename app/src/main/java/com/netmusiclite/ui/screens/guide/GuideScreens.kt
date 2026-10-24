package com.netmusiclite.ui.screens.guide

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.netmusiclite.ui.m3e.AdaptiveLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netmusiclite.ui.components.CircularSideProgress
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.Pressable
import com.netmusiclite.ui.components.estimateProgress
import com.netmusiclite.ui.components.rotaryCustom
import com.netmusiclite.ui.components.rotaryList
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.OnAccent
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import com.netmusiclite.ui.theme.TextTertiary
import com.netmusiclite.ui.theme.screenBg
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 使用向导（2026-10-02 新增；2026-10-03 手势卡片化改版 + 追加捐赠页 + 追加点击卡；
 * 2026-10-17 按主题拆卡组）：
 * ① [GuideScreen] 十卡横滑图册（七张播放页卡 + 三张主页卡，两套主题卡数/手势/顺序一致）：
 *    转动表冠（纯文字卡）→ 表冠方向提示 → 下滑进评论 → 左划进歌词 → 右滑退回主页 →
 *    点歌名进百科 → 点艺人进主页 → 主页右滑进播放页 → 主页左滑开侧栏 → 长按「我喜欢」抽歌；
 *    卡内为手指演示动画（手势 = 截图滑动转场；点击 = 热点按压 + 溶解换页），
 *    到达即浮现「滑动看下一张」脉冲提示（末卡为「下一步」按钮）；
 *    表冠转动翻卡，右缘弧 = 卡片进度。
 *    M3E 主题下全部演示图换为用户实拍截图（assets/guide_m3e_*.jpg）。
 * ② [GuideQqScreen] 官方QQ群二维码页（assets/qq_group.png，缺图时显示占位）+「下一页」；
 * ③ [GuideDonateScreen] 捐赠作者页（微信/支付宝收款码竖向排列、可滚动 + 右缘进度条）+「开始使用」。
 * 仅首次登录后弹出（见 AppNav 的 LOGIN_QR 分流），走完由调用方标记 GuideStore.done。
 * 三页均为前进动线，不挂滑动返回。
 */

/** 「自定义主题更好看」提示色：固定网易云红（不跟主题强调色，用户口径「红色字体」） */
private val GuideRed = Color(0xFFEC4141)

/** 表冠翻卡阈值（raw px）：raw 口径一格表冠≈110px，100 保证单格必翻、不吞格 */
private const val ROTARY_PAGE_STEP = 100f

/**
 * 演示手势类型。
 * None = 无手势卡（表冠音量卡）；Tap = 点击卡（热点纵坐标见 [GuideCard.tapY]）；
 * RotaryHint = 表冠适配提示卡（纯文字，无演示图，2026-10-04 新增）。
 */
private enum class GuideGesture {
    None, Down, Left, Right, Tap, RotaryHint,

    /** M3E 主页专属手势（2026-10-17）：右滑进播放页 / 左滑开侧栏 / 长按我喜欢抽歌 */
    M3eHomeRight, M3eHomeLeft, M3eHomeLongPress,
}

/** 向导卡片：标题 + 演示截图（from→to）+ 手势方向；
 *  [tapY] 仅 Tap 卡用 = 点击热点纵坐标（演示区高度比例，按 guide_player.jpg 截图量得） */
private class GuideCard(
    val title: String,
    val fromAsset: String?,
    val toAsset: String?,
    val gesture: GuideGesture,
    val tapY: Float = 0.5f,
)

/**
 * 卡组（2026-10-17 按主题拆分）：
 * 经典主题 = [guidePlayerCards] + [guideClassicHomeCards]；
 * M3E 主题 = [guideM3ePlayerCards] + [guideM3eHomeCards]。
 * 两套卡数 / 手势 / 顺序完全一致，只有演示截图与主页专属手势不同
 * （2026-10-17 用户口径：M3E 主题的向导演示要用 M3E 实拍截图，不用手绘示意）。
 */
private val guidePlayerCards = listOf(
    GuideCard("转动表冠调整音量", null, null, GuideGesture.None),
    // ★ 2026-10-04 用户口径「向导界面加一个界面说如果遇到表冠不适配去设置调」：
    //   紧跟表冠音量卡 —— 用户第一次转表冠若方向不对，下一张正好告诉他去哪调。
    GuideCard("表冠方向不对？", null, null, GuideGesture.RotaryHint),
    GuideCard("下滑进入评论区", "guide_player.jpg", "guide_comments.jpg", GuideGesture.Down),
    GuideCard("左划进入歌词页", "guide_player.jpg", "guide_lyrics.jpg", GuideGesture.Left),
    GuideCard("右滑退回主页", "guide_player.jpg", "guide_home.jpg", GuideGesture.Right),
    // 点击卡（2026-10-03 用户口径「第二部新增播放页点击歌曲标题进百科、点艺人名进主页」）：
    // 热点纵坐标按 guide_player.jpg 逐行亮度实测 —— 歌名带 ≈0.15、艺人名带 ≈0.26（466px 截图）；
    // to 图用用户实拍截图（演示卡与该截图是同一首歌，转场后无缝衔接）
    GuideCard("点歌名进入百科", "guide_player.jpg", "guide_songbaike.jpg", GuideGesture.Tap, tapY = 0.15f),
    GuideCard("点艺人进入主页", "guide_player.jpg", "guide_artist.jpg", GuideGesture.Tap, tapY = 0.26f),
)

/** 经典主题主页卡组：保持原有内容与顺序 */
private val guideClassicHomeCards = listOf(
    GuideCard("主页左划进播放页", "guide_home.jpg", "guide_player.jpg", GuideGesture.Left),
    GuideCard("列表左划进播放页", "guide_fav.jpg", "guide_player.jpg", GuideGesture.Left),
)

/**
 * M3E 主题主页卡组（2026-10-17 用户口径）：
 * 右滑直接进播放页 / 左滑打开侧栏 / 长按「我喜欢」进抽歌页。
 * 三张卡全部用**用户实拍截图**做转场演示（不用手绘示意），素材与卡一一对应：
 *  · 主页 → 播放页：guide_m3e_home.jpg（主页卡片堆叠）→ guide_m3e_player.jpg（Horologist 播放页）；
 *  · 主页 → 侧栏：guide_m3e_home.jpg → guide_m3e_sidebar.jpg（侧栏展开态实拍）；
 *  · 我喜欢卡 → 抽歌页：guide_m3e_liked.jpg（我喜欢卡居中）→ guide_m3e_pick.jpg（封面流抽歌页）。
 */
private val guideM3eHomeCards = listOf(
    // 2026-10-17 用户口径：M3E 主页手势卡必须用**真实截图**做转场演示（不用手绘示意）：
    // 素材全部为用户实拍截图（assets/guide_m3e_*.jpg），缺图时卡内显示「截图待补充」。
    GuideCard("主页右滑进播放页", "guide_m3e_home.jpg", "guide_m3e_player.jpg", GuideGesture.M3eHomeRight),
    GuideCard("主页左滑开侧栏", "guide_m3e_home.jpg", "guide_m3e_sidebar.jpg", GuideGesture.M3eHomeLeft),
    GuideCard("长按我喜欢抽歌", "guide_m3e_liked.jpg", "guide_m3e_pick.jpg", GuideGesture.M3eHomeLongPress),
)

/**
 * M3E 主题播放页卡组（2026-10-17 用户口径「material 风格主题的向导要用这几张实拍图」）：
 * 手势与顺序与经典主题**完全一致**，只把演示截图的 from 换成 M3E 播放页实拍
 * （Horologist 播放页，assets/guide_m3e_player.jpg），右滑退回的目标换成 M3E 主页实拍。
 * 点击卡热点按 m3e 播放页 466px 实拍量得：歌名带 y≈71~97 → 中心 84 → 0.18；
 * 艺人带 y≈122~138 → 中心 130 → 0.28（经典版是 0.15 / 0.26，两套布局不同，勿混用）。
 * to 页（评论 / 歌词 / 百科 / 艺人）沿用原有截图 —— 本轮用户未提供 M3E 版目标页实拍。
 */
private val guideM3ePlayerCards = listOf(
    GuideCard("转动表冠调整音量", null, null, GuideGesture.None),
    GuideCard("表冠方向不对？", null, null, GuideGesture.RotaryHint),
    GuideCard("下滑进入评论区", "guide_m3e_player.jpg", "guide_comments.jpg", GuideGesture.Down),
    GuideCard("左划进入歌词页", "guide_m3e_player.jpg", "guide_lyrics.jpg", GuideGesture.Left),
    GuideCard("右滑退回主页", "guide_m3e_player.jpg", "guide_m3e_home.jpg", GuideGesture.Right),
    GuideCard("点歌名进入百科", "guide_m3e_player.jpg", "guide_songbaike.jpg", GuideGesture.Tap, tapY = 0.18f),
    GuideCard("点艺人进入主页", "guide_m3e_player.jpg", "guide_artist.jpg", GuideGesture.Tap, tapY = 0.28f),
)

/**
 * 按当前主题组装卡组：手势与顺序两套主题**完全一致**，只是演示截图按主题换成各自的实拍
 * （M3E 用 guide_m3e_*.jpg，见 [guideM3ePlayerCards] / [guideM3eHomeCards]）。
 */
private fun guideCardsFor(isM3e: Boolean): List<GuideCard> =
    (if (isM3e) guideM3ePlayerCards else guidePlayerCards) +
        (if (isM3e) guideM3eHomeCards else guideClassicHomeCards)

/**
 * 向导第一页（2026-10-17 用户口径「进入时向导第一个界面改为选择主题」）：
 * 默认主题 / Material 主题二选一，点「确认」应用并进入对应主题的手势图册。
 * 选 M3E 且是本次版本第一次切过去时，先弹一次性公告（[M3eAnnounceDialog]）。
 */
@Composable
fun GuideThemeScreen(onNext: () -> Unit) {
    var selectedM3e by remember { mutableStateOf(com.netmusiclite.data.AppearancePrefs.isM3e) }

    fun applyAndGo() {
        com.netmusiclite.data.AppearancePrefs.setInterfaceStyle(if (selectedM3e) "m3e" else "classic")
        onNext()
    }

    Box(Modifier.fillMaxSize().background(screenBg())) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(3.2f))
            Text("选择主题", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(10.dp))
            ThemeOptionCard("默认主题", "经典界面与材质", !selectedM3e) { selectedM3e = false }
            Spacer(Modifier.height(8.dp))
            ThemeOptionCard("Material 主题", "Material 3 Expressive", selectedM3e) { selectedM3e = true }
            Spacer(Modifier.height(14.dp))
            Pressable(onClick = { applyAndGo() }) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Accent)
                        .padding(horizontal = 34.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("确认", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OnAccent)
                }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}

/** 主题选项卡：选中态 = 强调色底 + 描边 + 右侧对勾 */
@Composable
private fun ThemeOptionCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Pressable(onClick = onClick) {
        Box(
            Modifier
                .width(196.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (selected) Accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.08f))
                .then(
                    if (selected) Modifier.border(1.5.dp, Accent, RoundedCornerShape(18.dp))
                    else Modifier,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, fontSize = 9.sp, color = TextSecondary)
                }
                if (selected) {
                    Icon(
                        NcmIcons.Check,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/**
 * M3E 新主题一次性公告（仅本次构建生效，见 GuideStore 里的公告段；下次发版删除）。
 * 手表圆屏不用系统 Dialog：整屏半透明遮罩 + 居中卡片 + 两个按钮。
 */
@Composable
fun M3eAnnounceDialog(onGood: () -> Unit, onLeave: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(200.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF2A2A32))
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("新主题上线", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(7.dp))
            Text(
                "作者制作了这个新主题，\n好用的话可以给作者捐赠吗喵？",
                fontSize = 10.sp, lineHeight = 14.sp, color = Color(0xFFD8D8DE), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(13.dp))
            Pressable(onClick = onGood, modifier = Modifier.fillMaxWidth()) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Accent)
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("好的！", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = OnAccent)
                }
            }
            Spacer(Modifier.height(7.dp))
            Pressable(onClick = onLeave, modifier = Modifier.fillMaxWidth()) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.14f))
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("忍痛离开", fontSize = 11.sp, color = Color(0xFFD8D8DE))
                }
            }
        }
    }
}

/**
 * 向导第一页：十卡横滑图册（表冠音量 + 表冠适配提示 + 五手势 + 两点击 + 三主页手势；
 * 经典 / M3E 两套主题卡数相同，演示图不同）。
 * 手指左右滑动或转动表冠翻卡；卡片进度走右缘弧（复用 [CircularSideProgress]）。
 * 截图在进入本页时一次性解码（经典 7 张 + M3E 5 张，共 12 张 466×466），翻卡不重复解码。
 */
@Composable
fun GuideScreen(onNext: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 2026-10-17：卡组按主题分支（播放页一致，主页不同）；向导第一页已把主题选好
    val m3e = com.netmusiclite.data.AppearancePrefs.isM3e
    val cards = remember(m3e) { guideCardsFor(m3e) }
    val images = remember {
        listOf(
            "guide_player.jpg", "guide_home.jpg", "guide_comments.jpg", "guide_lyrics.jpg",
            "guide_fav.jpg", "guide_songbaike.jpg", "guide_artist.jpg",
            // 2026-10-17 M3E 主页三手势卡的真实截图（用户实拍；侧栏另有展开态实拍）
            "guide_m3e_home.jpg", "guide_m3e_player.jpg", "guide_m3e_pick.jpg", "guide_m3e_liked.jpg",
            "guide_m3e_sidebar.jpg",
        ).associateWith { loadAssetBitmap(ctx, it, 640) }
    }
    val pagerState = rememberPagerState(initialPage = 0) { cards.size }
    val scope = rememberCoroutineScope()
    var rotaryAcc by remember { mutableStateOf(0f) }
    Box(
        Modifier
            .fillMaxSize()
            .background(screenBg())
            // 表冠翻卡：累计一格翻一格（animateScrollToPage 内部 MutatorMutex 自带打断旧动画）
            // scope 保持默认 List —— 向导不是音量场景，方向跟随「列表页」开关
            .rotaryCustom { delta ->
                rotaryAcc += delta
                if (abs(rotaryAcc) >= ROTARY_PAGE_STEP) {
                    val step = if (rotaryAcc > 0f) 1 else -1
                    rotaryAcc = 0f
                    val target = (pagerState.currentPage + step).coerceIn(0, cards.lastIndex)
                    if (target != pagerState.currentPage) scope.launch { pagerState.animateScrollToPage(target) }
                }
            },
    ) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            GuideCardPage(
                card = cards[page],
                images = images,
                isCurrent = pagerState.currentPage == page,
                isLast = page == cards.lastIndex,
                onNext = onNext,
            )
        }
        CircularSideProgress(
            progress = {
                (pagerState.currentPage + pagerState.currentPageOffsetFraction) / (cards.size - 1).toFloat()
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 单卡：标题 + 演示区 + 提示区（2026-10-03 起演示区在屏幕几何正中，上下加权留白对齐）。
 * 提示（滑动引导 / 末卡按钮）到达卡片即显示（用户口径 2026-10-03「点开要立马出现」，
 * 原「等动画跑一轮」的 1.5s/3s 延迟已取消）；翻走时随退场动画淡出。
 */
@Composable
private fun GuideCardPage(
    card: GuideCard,
    images: Map<String, android.graphics.Bitmap?>,
    isCurrent: Boolean,
    isLast: Boolean,
    onNext: () -> Unit,
) {
    // 2026-10-03 用户口径「滑动看下一张的提示点开即出现」：到达卡片立即显示
    // （末卡为「下一步」按钮）；原「等演示动画跑一轮再浮现」的 1.5s/3s 延迟整体取消。
    val hintVisible = isCurrent
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 2026-10-03 用户口径「示意图放屏幕中央」：上下加权留白 3.24:1 ——
        // 上方标题区 19dp、下方提示区 38dp 不对称，按此配比后 140dp 演示区恰好落在屏幕几何中心。
        Spacer(Modifier.weight(3.24f))
        // 标题 ≤8 字（96dp）：下移后位于 y≈28dp，圆屏弧宽约 150dp，短标题一律无裁切风险
        Text(
            card.title,
            fontSize = 12.sp, lineHeight = 15.sp,
            fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1,
        )
        Spacer(Modifier.height(4.dp))
        when (card.gesture) {
            GuideGesture.RotaryHint -> GuideRotaryHintCard()
            GuideGesture.None -> GuideVolumeCard()
            GuideGesture.Tap -> GuideTapDemo(card, images)
            // M3E 主页三手势（2026-10-17）：真实截图转场 + 手指/长按引导
            GuideGesture.M3eHomeRight,
            GuideGesture.M3eHomeLeft,
            GuideGesture.M3eHomeLongPress -> GuideM3eHomeDemo(card, images)
            else -> GuideGestureDemo(card, images)
        }
        Spacer(Modifier.height(4.dp))
        // 34dp 固定提示区：AnimatedVisibility 直接挂在 Column 下（嵌套 Box 会触发
        // Compose 布局作用域标记冲突，编译不过）；未显示时高度照占，保住整卡布局
        AnimatedVisibility(
            visible = hintVisible,
            modifier = Modifier.fillMaxWidth().height(34.dp),
            enter = fadeIn(tween(240)) + scaleIn(initialScale = 0.85f, animationSpec = tween(240)),
            exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.85f, animationSpec = tween(140)),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (isLast) {
                    Pressable(onClick = onNext) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Accent)
                                .padding(horizontal = 38.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("下一步", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OnAccent)
                        }
                    }
                } else {
                    SwipeNextHint()
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

/** 第一卡（表冠音量）：纯文字描述（用户口径「仅文字描述」）+ 固定 140dp 与演示卡下缘对齐 */
/**
 * 表冠适配提示卡（2026-10-04 用户口径「向导界面加一个界面说如果遇到表冠不适配去设置调」）。
 *
 * 为什么值得单独占一页：各厂商 ROM 上报表冠的通道与符号都不一致（华为原始符号与 Wear 标准
 * 相反、OPPO 表冠甚至被声明成鼠标设备），应用无法自动判定，只能让用户按自己的手感去调。
 * 这里只讲「去哪调、调什么」—— 纯文字卡，画示意图反而说不清。
 */
@Composable
private fun GuideRotaryHintCard() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(150.dp),
    ) {
        Text(
            "设置 → 表冠适配",
            fontSize = 11.sp, lineHeight = 14.sp,
            fontWeight = FontWeight.SemiBold, color = Accent, maxLines = 1,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "音量页 / 列表页\n各可调「正 / 反」",
            fontSize = 9.sp, lineHeight = 13.sp,
            color = TextSecondary, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun GuideVolumeCard() {
    Column(
        modifier = Modifier.height(140.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(84.dp).clip(CircleShape).background(Accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(NcmIcons.Volume, contentDescription = null, tint = Accent, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(7.dp))
        Text("转动手表右侧的表冠", fontSize = 10.sp, lineHeight = 14.sp, color = TextSecondary)
        Spacer(Modifier.height(4.dp))
        Text(
            "自定义主题更好看",
            fontSize = 10.sp, lineHeight = 14.sp,
            fontWeight = FontWeight.Medium, color = GuideRed,
        )
    }
}

/**
 * 手势演示（纯 Compose 指针动画）：截图 from→to 沿手势方向滑出滑入，白色手指同步划动。
 * 循环 2.8s：静止 → 手指淡入 → 滑动 → 手指淡出 → 定格 → 眨眼重置
 * （衔接处整盒快速淡出淡入，避免 from/to 两帧硬切）。
 * 动画值全部在 graphicsLayer 绘制期读取：每帧只重绘，不重组、不重新布局。
 */
@Composable
private fun GuideGestureDemo(card: GuideCard, images: Map<String, android.graphics.Bitmap?>) {
    val demo = 140.dp
    val transition = rememberInfiniteTransition(label = "guideDemo")
    val tS = transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing)),
        label = "cycle",
    )
    val from = card.fromAsset?.let { images[it] }
    val to = card.toAsset?.let { images[it] }
    val dirX = when (card.gesture) { GuideGesture.Left -> -1f; GuideGesture.Right -> 1f; else -> 0f }
    val dirY = if (card.gesture == GuideGesture.Down) 1f else 0f
    Box(
        Modifier
            .size(demo)
            .graphicsLayer { alpha = blinkAt(tS.value) }
            .clip(CircleShape)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // to 图在下层（从反方向滑入），from 图在上层（沿手势方向滑出）
        if (to != null) {
            Image(
                to.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val e = easeAt(tS.value)
                    translationX = -dirX * demo.toPx() * (1f - e)
                    translationY = -dirY * demo.toPx() * (1f - e)
                },
            )
        }
        if (from != null) {
            Image(
                from.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    val e = easeAt(tS.value)
                    translationX = dirX * demo.toPx() * e
                    translationY = dirY * demo.toPx() * e
                },
            )
        }
        if (from == null || to == null) {
            Text("截图待补充", fontSize = 9.sp, color = TextTertiary)
        }
        // 手指：沿方向从 -20dp 划到 +20dp
        GuideFinger(
            modifier = Modifier.graphicsLayer {
                val e = easeAt(tS.value)
                val reach = e * 40f - 20f
                translationX = dirX * reach.dp.toPx()
                translationY = dirY * reach.dp.toPx()
                alpha = fingerAlphaAt(tS.value)
            },
        )
    }
}

/**
 * 点击演示（2026-10-03 新增，对应「第二部：播放页点歌名进百科 / 点艺人进主页」）：
 * 手指从热点下方滑到热点 → 按压缩放 + 白色涟漪扩散 → 整屏交叉溶解到目标页，
 * 定格片刻后眨眼重置。点击 = 原位换页，不做位移动画（与手势卡的滑出滑入区分开）。
 * 动画值全部在 graphicsLayer 绘制期读取：每帧只重绘，不重组、不重新布局。
 */
@Composable
private fun GuideTapDemo(card: GuideCard, images: Map<String, android.graphics.Bitmap?>) {
    val demo = 140.dp
    val transition = rememberInfiniteTransition(label = "guideTap")
    val tS = transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "cycle",
    )
    val from = card.fromAsset?.let { images[it] }
    val to = card.toAsset?.let { images[it] }
    Box(
        Modifier
            .size(demo)
            .graphicsLayer { alpha = blinkAt(tS.value) }
            .clip(CircleShape)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // to 图在下层：按压后交叉溶解浮现
        if (to != null) {
            Image(
                to.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = tapFadeAt(tS.value) },
            )
        }
        if (from != null) {
            Image(
                from.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - tapFadeAt(tS.value) },
            )
        }
        if (from == null || to == null) {
            Text("截图待补充", fontSize = 9.sp, color = TextTertiary)
        }
        // 热点涟漪：随手指出现，按压瞬间扩散淡出（圆心 = 热点坐标）
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    val e = pressRippleAt(tS.value)
                    translationY = demo.toPx() * card.tapY - size.height / 2f
                    val s = 0.72f + e * 1.1f
                    scaleX = s
                    scaleY = s
                    alpha = tapFingerAlphaAt(tS.value) * (1f - e) * 0.55f
                }
                .size(26.dp)
                .clip(CircleShape)
                .background(Color.White),
        )
        // 手指：从热点下方 26dp 滑到热点 → 下压回弹 → 淡出
        GuideFinger(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .graphicsLayer {
                    val arrive = fingerArriveAt(tS.value)
                    translationY = demo.toPx() * card.tapY - size.height / 2f +
                        (1f - arrive) * 26.dp.toPx() + pressDipDp(tS.value).dp.toPx()
                    alpha = tapFingerAlphaAt(tS.value)
                },
        )
    }
}

/**
 * M3E 主页三手势演示（2026-10-17 新增；同日按用户口径改为**真实截图**转场）。
 *
 * 素材全部为用户实拍截图（assets/guide_m3e_*.jpg），缺图时卡内显示「截图待补充」：
 *  · 主页 → 播放页：右滑，播放页实拍从右侧滑入；
 *  · 主页 → 侧栏：左滑，侧栏展开态实拍从右侧滑入（与真实实现同向：侧栏自右向左移入、
 *    主页内容同时缩小并左移，见 M3eHome.kt 的 reveal 动画）；
 *  · 我喜欢卡 → 抽歌页：长按 0.8s，按压脉冲后溶解到封面流抽歌页实拍。
 * 手指/方向/长按时序沿用播放页卡组的同一套动画函数。
 */
@Composable
private fun GuideM3eHomeDemo(card: GuideCard, images: Map<String, android.graphics.Bitmap?>) {
    val demo = 140.dp
    val transition = rememberInfiniteTransition(label = "guideM3eHome")
    val tS = transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing)),
        label = "cycle",
    )
    val from = card.fromAsset?.let { images[it] }
    val to = card.toAsset?.let { images[it] }
    Box(
        Modifier
            .size(demo)
            .graphicsLayer { alpha = blinkAt(tS.value) }
            .clip(CircleShape)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        // 主页实拍垫底（用户提供的 M3E 主页截图）
        if (from != null) {
            Image(
                from.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        when (card.gesture) {
            GuideGesture.M3eHomeRight -> {
                // 播放页实拍从右侧滑入（与真实右滑手势同向）
                val e = easeAt(tS.value)
                if (to != null) {
                    Image(
                        to.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            translationX = demo.toPx() * (1f - e)
                            alpha = e
                        },
                    )
                }
                GuideFinger(
                    modifier = Modifier.graphicsLayer {
                        translationX = (easeAt(tS.value) * 42f - 21f).dp.toPx()
                        alpha = fingerAlphaAt(tS.value)
                    },
                )
            }

            GuideGesture.M3eHomeLeft -> {
                // 侧栏展开态实拍从右侧滑入（真实实现：侧栏自右向左移入，主页内容缩小左移）
                val e = easeAt(tS.value)
                if (to != null) {
                    Image(
                        to.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            translationX = demo.toPx() * 0.30f * (1f - e)
                            alpha = e
                        },
                    )
                }
                GuideFinger(
                    modifier = Modifier.graphicsLayer {
                        translationX = -(easeAt(tS.value) * 42f - 21f).dp.toPx()
                        alpha = fingerAlphaAt(tS.value)
                    },
                )
            }

            else -> {
                // 长按：按压脉冲 + 溶解到抽歌页实拍（0.8s 长按成立 → 进入抽歌页）
                val ripple = pressRippleAt(tS.value)
                if (to != null) {
                    Image(
                        to.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = tapFadeAt(tS.value) },
                    )
                }
                Box(
                    Modifier.align(Alignment.Center).offset(y = 16.dp)
                        .size(56.dp)
                        .graphicsLayer {
                            val s = 0.9f + ripple * 1.4f
                            scaleX = s
                            scaleY = s
                            alpha = (1f - ripple) * 0.5f
                        }
                        .clip(CircleShape)
                        .background(Color.White),
                )
                GuideFinger(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = 16.dp)
                        .graphicsLayer { translationY = (1f - fingerArriveAt(tS.value)) * 26.dp.toPx() }
                        .graphicsLayer { alpha = tapFingerAlphaAt(tS.value) },
                )
            }
        }
    }
}

/** 白色手指图形（无现成图标，Canvas 自绘：指尖圆 + 圆角掌身，浅描边保证亮底可见） */
@Composable
private fun GuideFinger(modifier: Modifier = Modifier, height: Dp = 28.dp) {
    if (com.netmusiclite.data.AppearancePrefs.isM3e) {
        androidx.wear.compose.material3.Icon(
            imageVector = Icons.Rounded.TouchApp,
            contentDescription = null,
            modifier = modifier.size(width = height * 0.74f, height = height),
            tint = androidx.wear.compose.material3.MaterialTheme.colorScheme.onSurface,
        )
        return
    }
    Canvas(modifier.size(width = height * 0.74f, height = height)) {
        val cx = size.width / 2f
        val r = size.height * 0.30f
        val path = Path().apply {
            addOval(Rect(cx - r, 0f, cx + r, r * 2f))
            addRoundRect(
                RoundRect(
                    left = cx - r * 1.05f, top = r * 1.45f,
                    right = cx + r * 1.05f, bottom = size.height,
                    radiusX = r * 0.85f, radiusY = r * 0.85f,
                ),
            )
        }
        translate(1.2f, 2.4f) { drawPath(path, Color.Black.copy(alpha = 0.25f)) }
        drawPath(path, Color.White)
        drawPath(path, Color(0xFF9A9AA0), style = Stroke(1.1f))
    }
}

/** 「滑动看下一张」脉冲提示：文字 + 指引箭头/指尖组左右往复（箭头在前，指向翻页方向） */
@Composable
private fun SwipeNextHint() {
    val transition = rememberInfiniteTransition(label = "swipeHint")
    val dxS = transition.animateFloat(
        initialValue = 7f, targetValue = -7f,
        animationSpec = infiniteRepeatable(tween(760, easing = LinearEasing), RepeatMode.Reverse),
        label = "dx",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("滑动看下一张", fontSize = 9.sp, color = TextSecondary)
        Spacer(Modifier.width(7.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.graphicsLayer { translationX = dxS.value.dp.toPx() },
        ) {
            Icon(NcmIcons.ChevronLeft, contentDescription = null, tint = Accent, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(2.dp))
            GuideFinger(height = 14.dp)
        }
    }
}

/** 平滑插值（0→1 两端速度为零） */
private fun smoothStep(x: Float): Float = x * x * (3f - 2f * x)

/** 演示循环进度：t 15% 起手、60% 到位 */
private fun easeAt(t: Float): Float = smoothStep(((t - 0.15f) / 0.45f).coerceIn(0f, 1f))

/** 循环衔接的「眨眼」：首尾各 ~100ms 淡入淡出，遮住 from/to 硬切 */
private fun blinkAt(t: Float): Float =
    (t / 0.035f).coerceAtMost(1f) * ((1f - t) / 0.035f).coerceAtMost(1f)

/** 手指透明度：滑动窗口内可见，两端淡入淡出 */
private fun fingerAlphaAt(t: Float): Float = when {
    t < 0.10f -> 0f
    t < 0.16f -> (t - 0.10f) / 0.06f
    t < 0.54f -> 1f
    else -> ((0.62f - t) / 0.08f).coerceAtLeast(0f)
}

/** 点击循环：手指到位进度（12% 起手 → 38% 抵达热点） */
private fun fingerArriveAt(t: Float): Float = smoothStep(((t - 0.12f) / 0.26f).coerceIn(0f, 1f))

/** 点击循环：按压涟漪进度（38% 按下 → 60% 扩散完） */
private fun pressRippleAt(t: Float): Float = smoothStep(((t - 0.38f) / 0.22f).coerceIn(0f, 1f))

/** 点击循环：按压下压量（dp，按下 → 回弹，窗口 38%–58%） */
private fun pressDipDp(t: Float): Float = when {
    t < 0.38f -> 0f
    t < 0.48f -> (t - 0.38f) / 0.10f * 2.5f
    t < 0.58f -> (1f - (t - 0.48f) / 0.10f) * 2.5f
    else -> 0f
}

/** 点击循环：页面溶解（46%–64%，压在按压窗口内完成，形成"按下就换页"的因果感） */
private fun tapFadeAt(t: Float): Float = smoothStep(((t - 0.46f) / 0.18f).coerceIn(0f, 1f))

/** 点击循环的手指透明度：随到位淡入，定格后淡出 */
private fun tapFingerAlphaAt(t: Float): Float = when {
    t < 0.10f -> 0f
    t < 0.16f -> (t - 0.10f) / 0.06f
    t < 0.68f -> 1f
    else -> ((0.78f - t) / 0.10f).coerceAtLeast(0f)
}

@Composable
fun GuideQqScreen(onNext: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 原图直出（QQ群二维码多为彩色带背景设计，不重绘）：assets 里放什么就显示什么
    val qr = remember { loadAssetBitmap(ctx, "qq_group.png", 640) }
    Box(
        Modifier.fillMaxSize().background(screenBg()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp),
        ) {
            Text("加入官方QQ群", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .size(142.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                if (qr != null) {
                    Image(
                        qr.asImageBitmap(),
                        contentDescription = "官方QQ群二维码",
                        modifier = Modifier.size(132.dp),
                    )
                } else {
                    Text(
                        "QQ群二维码\n待补充",
                        fontSize = 10.sp, color = Color(0xFF8A8A8E), textAlign = TextAlign.Center,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Pressable(onClick = onNext) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Accent)
                        .padding(horizontal = 32.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("下一页", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OnAccent)
                }
            }
        }
    }
}

/**
 * 向导第三页：捐赠作者（2026-10-03 用户口径「QQ群页之后加捐赠页，最下方同样放按钮」）。
 *
 * 微信、支付宝两张收款码与设置页捐赠页同图（整卡直出），竖向排列放不进一屏，
 * 故内容区可滚动（表冠 + 手指），右缘弯曲进度条复用 [CircularSideProgress]；
 * 底部「开始使用」固定，点完走完向导进应用。
 */
@Composable
fun GuideDonateScreen(onDone: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val wechat = remember { loadAssetBitmap(ctx, "donate_wechat.png", 640) }
    val alipay = remember { loadAssetBitmap(ctx, "donate_alipay.jpg", 640) }
    val listState = rememberLazyListState()
    val progress by remember(listState) {
        derivedStateOf { estimateProgress(listState, com.netmusiclite.ui.m3e.listLayoutInfo(listState).totalItemsCount) }
    }
    Box(
        Modifier.fillMaxSize().background(screenBg()).rotaryList(listState),
    ) {
        LazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 70.dp),
        ) {
            item(key = "top") { Spacer(Modifier.height(30.dp)) }
            item(key = "title") {
                Text("捐赠作者", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
            item(key = "wechat") { GuideDonateCode(wechat, "微信收款码待补充") }
            item(key = "alipay") { GuideDonateCode(alipay, "支付宝收款码待补充") }
            item(key = "thanks") {
                Text(
                    "感谢你的支持，这是我更新的最大动力",
                    fontSize = 9.sp, color = TextTertiary, lineHeight = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                )
            }
        }
        Pressable(
            onClick = onDone,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp),
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Accent)
                    .padding(horizontal = 32.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("开始使用", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OnAccent)
            }
        }
        CircularSideProgress(progress = { progress }, modifier = Modifier.fillMaxSize())
    }
}

/** 收款码整卡直出（158dp 宽等比，同设置页捐赠页口径），缺图时白底占位 */
@Composable
private fun GuideDonateCode(bmp: android.graphics.Bitmap?, fallback: String) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .width(158.dp)
                    .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                    .clip(RoundedCornerShape(16.dp)),
            )
        } else {
            Box(
                Modifier
                    .size(158.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Text(fallback, fontSize = 10.sp, color = Color(0xFF8A8A8E), textAlign = TextAlign.Center)
            }
        }
    }
}

/** 读取 assets 图片（按最大边降采样，手表内存小）；缺失/损坏返回 null 由调用方占位。
 *  internal：设置页的 QQ 群 / 捐赠页（SupportScreens.kt）复用同一实现 */
internal fun loadAssetBitmap(
    ctx: android.content.Context,
    name: String,
    maxDim: Int,
): android.graphics.Bitmap? = runCatching {
    val bytes = ctx.assets.open(name).use { it.readBytes() }
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim) {
        sample *= 2
    }
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}.getOrNull()
