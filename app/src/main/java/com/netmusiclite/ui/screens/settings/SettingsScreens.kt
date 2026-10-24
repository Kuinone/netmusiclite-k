package com.netmusiclite.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.fillMaxSize
import com.netmusiclite.ui.theme.Bg
import com.netmusiclite.ui.m3e.AdaptiveLazyColumn as LazyColumn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.rememberLazyListState
import com.netmusiclite.ui.components.rotaryList
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.netmusiclite.ui.components.pressStretch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.SessionStore
import com.netmusiclite.data.LoudnessPrefs
import com.netmusiclite.ui.components.CoverImage
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.NcmListPage
import com.netmusiclite.ui.components.StackedCard
import com.netmusiclite.ui.components.StackedCardList
import com.netmusiclite.ui.components.StackedIconCard
import com.netmusiclite.ui.components.formatDate
import com.netmusiclite.ui.components.wallpaperShown
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.navigateWithMotion
import com.netmusiclite.data.AppearancePrefs
import com.netmusiclite.data.BackgroundStore
import com.netmusiclite.data.NcmApi
import com.netmusiclite.data.PRESET_ACCENTS
import com.netmusiclite.data.PlayPageStyle
import com.netmusiclite.data.QualityPrefs
import com.netmusiclite.R
import com.netmusiclite.data.ThemeMode
import com.netmusiclite.ui.screens.login.LoginQrScreen
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.FONT_WEIGHT_LABELS
import com.netmusiclite.ui.theme.SurfaceStrong
import com.netmusiclite.ui.theme.AccentSoft
import com.netmusiclite.ui.theme.Gold
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import com.netmusiclite.ui.theme.TextTertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import com.netmusiclite.update.UpdateChecker
import com.netmusiclite.update.UpdateFlow
import com.netmusiclite.update.UpdateInfo
import com.netmusiclite.ui.components.UpdateAvailableDialog
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 设置：我的账号 / 缓存清理（卡片堆叠风格） */
@Composable
fun SettingsScreen(nav: NavHostController) {
    if (AppearancePrefs.isM3e) {
        com.netmusiclite.ui.m3e.M3eMainSettingsScreen(nav)
        return
    }
    val loudnessEnabled = LoudnessPrefs.enabled
    StackedCardList(title = "设置", progressTotal = 15, horizontalInsetPx = 10) {
        item(key = "account") {
            StackedIconCard(
                title = "我的账号",
                subtitle = SessionStore.nickname.ifEmpty { "未登录" },
                icon = NcmIcons.Note,
                navMotionKind = NavMotionKind.Pill,
            ) { nav.navigateWithMotion(Routes.ACCOUNT) }
        }
        // 2026-10-01：原「背景与玻璃」独立入口已并入本项。
        // 理由：玻璃材质的观感直接受主题明暗与种子色影响（浅色主题下玻璃 alpha 是重新标定的一套），
        // 拆成两个入口只会让人不知道该去哪调。合并后一个入口统管全部观感设置，且无重复项。
        item(key = "theme") {
            StackedIconCard(
                title = "主题",
                subtitle = themeSummary(),
                icon = NcmIcons.Theme,
                navMotionKind = NavMotionKind.Pill,
            ) { nav.navigateWithMotion(Routes.THEME) }
        }
        // ★ 2026-10-11 用户口径「设置页主题下方添加在歌词页常亮屏幕开关」：
        //   开 = 停留在歌词页期间屏幕不按系统息屏时间熄灭（FLAG_KEEP_SCREEN_ON，
        //   挂/清在 LyricsScreen 的 DisposableEffect）；离开歌词页立即恢复系统息屏节奏。
        //   默认关（续航优先），主设置页直接给开关 —— 位置按用户指定：紧跟「主题」入口下方。
        //   卡片样式与下方「歌曲响度均衡」开关一致（主设置页的开关形态）。
        item(key = "lyric_keep_on") {
            val keepOn = AppearancePrefs.lyricKeepScreenOn
            StackedCard(onClick = { AppearancePrefs.setLyricKeepScreenOn(!keepOn) }) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (keepOn) Accent else SurfaceStrong),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        NcmIcons.Moon, contentDescription = null,
                        tint = if (keepOn) com.netmusiclite.ui.theme.OnAccent else TextPrimary,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("歌词页屏幕常亮", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Text(
                        if (keepOn) "已开启 · 停留在歌词页时屏幕不熄灭" else "已关闭 · 听歌看歌词时屏幕按系统节奏熄灭",
                        fontSize = 8.sp, color = TextSecondary, maxLines = 1,
                    )
                    Text("只作用于歌词页，离开立即恢复息屏", fontSize = 8.sp, color = TextTertiary, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .then(if (keepOn) Modifier.background(Accent) else Modifier.border(1.dp, TextTertiary, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (keepOn) {
                        Icon(NcmIcons.Check, contentDescription = "已开启", tint = com.netmusiclite.ui.theme.OnAccent, modifier = Modifier.size(11.dp))
                    }
                }
            }
        }
        // ★ 2026-10-03 表盘形状（用户口径「首次启动向导第一页改成方表圆表选择」）：
        //   向导里选过一次，这里给一个随时可改的出口 —— 形状决定全局圆形裁切与弧边内缩，
        //   选错会让整个界面长期变形，必须能改回来。复用向导第一页的选择界面（见 AppNav 的 Routes.SHAPE）。
        item(key = "shape") {
            StackedIconCard(
                title = "表盘形状",
                subtitle = "当前：${com.netmusiclite.data.ScreenShapePrefs.label()} · 边缘裁切适配",
                icon = NcmIcons.Watch,
                navMotionKind = NavMotionKind.Pill,
            ) { nav.navigateWithMotion(Routes.SHAPE) }
        }
        // ★ 2026-10-04 表冠适配（用户口径「设置里加一栏表冠适配：音量页一个选项、列表页一个选项」）：
        //   各厂商 ROM 上报的通道与符号都不同（华为原始符号与标准相反、OPPO 表冠声明成 SOURCE_MOUSE），
        //   归一化无法穷举厂商 —— 给用户两个「正 / 反」开关，点选立即生效（见 data/RotaryTuning）。
        item(key = "rotary") {
            StackedIconCard(
                title = "表冠适配",
                subtitle = com.netmusiclite.data.RotaryTuning.label(),
                icon = NcmIcons.Refresh,
                navMotionKind = NavMotionKind.Pill,
            ) { nav.navigateWithMotion(Routes.ROTARY) }
        }
        item(key = "quality") {
            StackedIconCard(
                title = "音质",
                subtitle = "当前：${QualityPrefs.label()}（播放与下载生效）",
                icon = NcmIcons.Note,
                navMotionKind = NavMotionKind.Pill,
            ) { nav.navigateWithMotion(Routes.QUALITY) }
        }
        item(key = "loudness") {
            StackedCard(onClick = {
                val enabled = !LoudnessPrefs.enabled
                LoudnessPrefs.enabled = enabled
                PlayerEngine.setLoudnessEnabled(enabled)
            }) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (loudnessEnabled) Accent else SurfaceStrong),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        NcmIcons.Volume, contentDescription = null,
                        tint = if (loudnessEnabled) com.netmusiclite.ui.theme.OnAccent else TextPrimary,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("歌曲响度均衡", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Text(
                        if (loudnessEnabled) "已开启 · 每首歌统一到同一响度" else "已关闭",
                        fontSize = 8.sp, color = TextSecondary, maxLines = 1,
                    )
                    Text("只对齐曲目间音量，保留每首歌自身动态", fontSize = 8.sp, color = TextTertiary, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .then(if (loudnessEnabled) Modifier.background(Accent) else Modifier.border(1.dp, TextTertiary, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (loudnessEnabled) {
                        Icon(NcmIcons.Check, contentDescription = "已开启", tint = com.netmusiclite.ui.theme.OnAccent, modifier = Modifier.size(11.dp))
                    }
                }
            }
        }
        // ★ 2026-10-18 用户口径「在设置里歌曲响度对齐下方加一个关闭播放页转动表冠调节音量的选项」：
        //   语义是「关闭」—— 默认关着（表冠照旧调音量），打开后播放页转表冠不再动音量。
        //   与上面响度项同款卡片形态（左图标 + 标题副标题 + 右侧状态点），保持列表观感一致。
        item(key = "crown_volume") {
            val crownOff = com.netmusiclite.data.CrownVolumePrefs.disabled
            StackedCard(onClick = {
                com.netmusiclite.data.CrownVolumePrefs.disabled = !crownOff
            }) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (crownOff) Accent else SurfaceStrong),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        NcmIcons.Watch, contentDescription = null,
                        tint = if (crownOff) com.netmusiclite.ui.theme.OnAccent else TextPrimary,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("关闭表冠调音量", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Text(
                        if (crownOff) "已关闭 · 播放页转表冠不调节音量"
                        else "已开启 · 播放页转表冠调节音量",
                        fontSize = 8.sp, color = TextSecondary, maxLines = 1,
                    )
                    Text("仅影响播放页，音量键与音量按钮不受影响", fontSize = 8.sp, color = TextTertiary, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .then(if (crownOff) Modifier.background(Accent) else Modifier.border(1.dp, TextTertiary, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (crownOff) {
                        Icon(NcmIcons.Check, contentDescription = "已关闭", tint = com.netmusiclite.ui.theme.OnAccent, modifier = Modifier.size(11.dp))
                    }
                }
            }
        }
        // ★ 2026-10-24 实验性手势操控：前台 ACC+GYRO 本地识别（非 ROM 桥接），默认关闭。
        //   与「关闭表冠调音量」同款卡片形态；开关本身由 GestureControl 同步传感器生命周期。
        item(key = "gesture") {
            val gestureOn = com.netmusiclite.data.GesturePrefs.enabled
            val ctx = androidx.compose.ui.platform.LocalContext.current
            StackedCard(onClick = {
                if (gestureOn) {
                    com.netmusiclite.data.GestureControl.setEnabled(ctx, false)
                } else {
                    com.netmusiclite.ui.components.GestureDonationGate.requestEnable {
                        com.netmusiclite.data.GestureControl.setEnabled(ctx, true)
                    }
                }
            }) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (gestureOn) Accent else SurfaceStrong),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        NcmIcons.Watch, contentDescription = null,
                        tint = if (gestureOn) com.netmusiclite.ui.theme.OnAccent else TextPrimary,
                        modifier = Modifier.size(19.dp),
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("手势操控（实验性）", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Text(
                        if (gestureOn) "已开启 · 仅本应用前台时识别"
                        else "已关闭 · 实验性功能，默认关闭",
                        fontSize = 8.sp, color = TextSecondary, maxLines = 1,
                    )
                    Text("双指捏一捏 · 手心朝上/下切歌 · 竖腕暂停", fontSize = 8.sp, color = TextTertiary, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .then(if (gestureOn) Modifier.background(Accent) else Modifier.border(1.dp, TextTertiary, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (gestureOn) {
                        Icon(NcmIcons.Check, contentDescription = "已开启", tint = com.netmusiclite.ui.theme.OnAccent, modifier = Modifier.size(11.dp))
                    }
                }
            }
        }
        item(key = "gesture_help") {
            StackedIconCard(
                title = "手势说明与灵敏度",
                subtitle = "双指捏一捏 · 上/下一首 · 暂停/继续",
                icon = NcmIcons.Info,
                navMotionKind = NavMotionKind.Pill,
            ) { nav.navigateWithMotion(Routes.GESTURE) }
        }
        item(key = "cache") {
            StackedIconCard(
                title = "缓存清理", subtitle = "清理图片与临时缓存", icon = NcmIcons.Refresh,
                navMotionKind = NavMotionKind.Pill,
            ) {
                nav.navigateWithMotion(Routes.CACHE_CLEAN)
            }
        }
        // ★ 2026-10-07 用户口径「在设置免责声明上方添加特别鸣谢栏目」：
        //   独立静态页（页内内容见 SupportScreens.kt 的 ThanksScreen）——
        //   名单顺序每次进入随机，页签入口固定在这里、与免责声明相邻。
        item(key = "thanks") {
            StackedIconCard(
                title = "特别鸣谢",
                subtitle = "感谢为本项目提供帮助的朋友",
                icon = NcmIcons.Star,
                iconTint = Gold,
                navMotionKind = NavMotionKind.Pill,
            ) { nav.navigateWithMotion(Routes.THANKS) }
        }
        item(key = "disclaimer") {
            StackedIconCard(
                title = "免责声明",
                subtitle = "第三方客户端使用协议",
                icon = NcmIcons.Bookmark,
                navMotionKind = NavMotionKind.Pill,
            ) { nav.navigateWithMotion(Routes.DISCLAIMER) }
        }
        // ★ 2026-10-02 用户口径「设置列表末尾加关于应用入口」
        item(key = "about") {
            StackedIconCard(
                title = "关于应用",
                subtitle = "版本 · 作者 · 项目地址",
                icon = NcmIcons.Info,
                navMotionKind = NavMotionKind.Pill,
            ) { nav.navigateWithMotion(Routes.ABOUT) }
        }
        // ★ 2026-10-03 用户口径「设置里添加加入官方QQ群/捐赠作者选项，qq群在上」：
        //   两条入口按用户指定顺序排列：QQ群 → 捐赠作者，各自独立成页
        //   （页内内容见 SupportScreens.kt）。
        item(key = "qq_group") {
            StackedIconCard(
                title = "加入官方QQ群",
                subtitle = "官方群 1127884054 · 扫码加入",
                icon = NcmIcons.Friends,
                navMotionKind = NavMotionKind.Pill,
            ) { nav.navigateWithMotion(Routes.QQ_GROUP) }
        }
        item(key = "donate") {
            StackedIconCard(
                title = "捐赠作者",
                subtitle = "微信 / 支付宝扫码支持作者",
                icon = NcmIcons.Heart,
                iconTint = Accent,
                navMotionKind = NavMotionKind.Pill,
            ) { nav.navigateWithMotion(Routes.DONATE) }
        }
        item(key = "credit") {
            Text(
                "作者：昼小风\n未经授权请勿转载",
                fontSize = 9.sp, color = TextTertiary, lineHeight = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
            )
        }
    }
}

/** 设置页「主题」入口副标题：明暗 · 配色来源 · 背景状态（读的都是 Compose 状态，会自动刷新） */
private fun themeSummary(): String {
    val color = if (AppearancePrefs.dynamicColor) "自动取色" else AppearancePrefs.accentLabel()
    // ★ 2026-10-07：背景状态按**实际在画的那层底**报 —— 封面模糊外观且「保留取色」关时，
    //   自定义壁纸全程不露面（外围页由模糊封面顶替），还报「自定义背景」就是骗人。
    val bg = when {
        AppearancePrefs.coverBlurOuter -> "封面模糊背景"
        BackgroundStore.ready -> "自定义背景"
        else -> "默认背景"
    }
    return "${AppearancePrefs.modeLabel()} · $color · $bg"
}

/** 我的账号：账号卡(头像+昵称+VIP) + 切换账号 / 退出账号 */
@Composable
fun AccountScreen(nav: NavHostController) {
    var vipExpire by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        val uid = SessionStore.uid
        if (uid > 0) {
            val t = NcmApiVipSafe(uid)
            vipExpire = t?.takeIf { it > System.currentTimeMillis() }
        }
    }

    StackedCardList(title = "我的账号", progressTotal = 3) {
        item(key = "avatar") {
            StackedCard {
                CoverImage(SessionStore.avatarUrl.ifEmpty { null }, 42.dp, shape = CircleShape)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        SessionStore.nickname, fontSize = 13.sp,
                        fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1,
                    )
                    // VIP 到期：拿到且有效才显示
                    vipExpire?.let {
                        Text("VIP 到期 ${formatDate(it)}", fontSize = 9.sp, color = Gold)
                    }
                }
            }
        }
        item(key = "switch") {
            StackedIconCard(
                title = "切换账号",
                subtitle = "已添加 ${SessionStore.accounts().size} 个账号",
                icon = NcmIcons.Friends,
                navMotionKind = NavMotionKind.Pill,
            ) { nav.navigateWithMotion(Routes.SWITCH_ACCOUNT) }
        }
        item(key = "logout") {
            StackedIconCard(
                title = "退出账号",
                subtitle = "退出后需重新扫码登录",
                icon = NcmIcons.Close,
                iconTint = Accent,
                navMotionKind = NavMotionKind.Pill,
            ) {
                SessionStore.logoutCurrent()
                nav.navigateWithMotion(Routes.LOGIN_QR) {
                    popUpTo(Routes.HOME) { inclusive = true }
                }
            }
        }
    }
}

private suspend fun NcmApiVipSafe(uid: Long): Long? = runCatching {
    com.netmusiclite.data.NcmApi.vipExpire(uid)
}.getOrNull()

/** 切换账号：已添加账号列表 + 添加账号（扫码） */
@Composable
fun SwitchAccountScreen(nav: NavHostController) {
    val accounts = SessionStore.accounts()

    StackedCardList(
        title = "切换账号",
        titleSubtitle = "当前账号排在最前",
        progressTotal = accounts.size + 1,
    ) {
        accounts.forEachIndexed { i, acc ->
            item(key = "acc_${acc.uid}_$i") {
                StackedCard(
                    onClick = {
                        SessionStore.applyAccount(acc)
                        PlayerEngine.refreshLikes()
                        nav.popBackStack()
                    },
                ) {
                    CoverImage(acc.avatarUrl?.ifEmpty { null }, 36.dp, shape = CircleShape)
                    Spacer(Modifier.width(11.dp))
                    Column {
                        Text(acc.nickname.ifEmpty { "账号 ${acc.uid}" }, fontSize = 12.sp, color = TextPrimary, maxLines = 1)
                        if (i == 0) Text("当前使用", fontSize = 9.sp, color = Accent)
                    }
                }
            }
        }
        item(key = "add") {
            StackedIconCard(
                title = "添加账号",
                subtitle = "扫码登录新账号",
                icon = NcmIcons.Plus,
                iconTint = Accent,
                navMotionKind = NavMotionKind.Pill,
            ) { nav.navigateWithMotion(Routes.ADD_ACCOUNT_QR) }
        }
    }
}

/** 添加账号 = 扫码登录（登录成功即回到切换页） */
@Composable
fun AddAccountQrScreen(nav: NavHostController) {
    LoginQrScreen(
        heading = "添加账号\n扫码登录",
        onLoggedIn = { nav.popBackStack() },
    )
}

/** 缓存清理：统计并清理应用缓存目录（不动已下载音乐） */
@Composable
fun CacheCleanScreen(nav: NavHostController) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var sizeText by remember { mutableStateOf("统计中…") }
    var result by remember { mutableStateOf<String?>(null) }

    suspend fun dirSize(d: File): Long = withContext(Dispatchers.IO) {
        d.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }

    LaunchedEffect(Unit) {
        val bytes = dirSize(ctx.cacheDir)
        sizeText = "%.1f MB".format(bytes / 1024f / 1024f)
    }

    NcmListPage(title = "缓存清理", onBack = { nav.popBackStack() }, progressTotal = 4) {
        item(key = "size") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(sizeText, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(Modifier.height(4.dp))
                Text("图片 / 网络临时缓存（不动已下载音乐）", fontSize = 9.sp, color = TextSecondary)
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Accent)
                        .pressStretch().clickable {
                            java.util.concurrent.Executors.newSingleThreadExecutor().execute {
                                ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                            }
                            result = "已清理"
                            sizeText = "0.0 MB"
                        }
                        .padding(horizontal = 26.dp, vertical = 10.dp),
                ) {
                    Text("立即清理", fontSize = 12.sp, color = com.netmusiclite.ui.theme.OnAccent, fontWeight = FontWeight.SemiBold)
                }
                result?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 10.sp, color = Accent)
                }
            }
        }
    }
}

// 2026-10-01：原 `BackgroundSettingsScreen`（背景与玻璃）已整体合并进下方 ThemeSettingsScreen。
// 主设置页不再保留独立入口，Routes.BG_SETTING 也已移除 —— 同一个功能不允许存在两套 UI。

/** 免责声明：第三方客户端使用协议全文（表冠滚动阅读） */
@Composable
fun DisclaimerScreen(nav: NavHostController) {
    val paragraphs = remember { DISCLAIMER_TEXT.trim().split("\n\n") }
    com.netmusiclite.ui.components.NcmListPage(title = "免责声明") {
        itemsIndexed(paragraphs, key = { i, _ -> i }) { _, para ->
            Text(
                para,
                fontSize = 9.sp,
                color = TextPrimary.copy(alpha = 0.9f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

private val DISCLAIMER_TEXT = """NetMusicLite 第三方客户端免责声明

欢迎您使用 NetMusicLite（以下简称"本软件"）。在下载、安装、登录或以任何方式使用本软件之前，请您务必仔细阅读并充分理解本免责声明的全部内容，特别是免除或者限制开发者责任的条款。本声明是您与开发者之间关于使用本软件的有效约定。您一旦实际安装或使用本软件，即视为您已经完整阅读、充分理解并同意接受本声明全部条款的约束；如您不同意本声明的任何内容，或者无法准确理解相关条款的含义，请立即停止使用本软件并将其从您的设备中卸载、删除。

一、软件性质声明
本软件是由音乐爱好者出于个人学习、研究、验证移动端技术与网络协议等非商业目的，利用业余时间独立开发的非官方第三方客户端。本软件基于公开可得的网络接口协议实现，目的在于让用户在智能手表等可穿戴设备上访问其本人已经依法享有的音乐服务权益。本软件并非网易云音乐官方客户端，与网易杭州网络游戏有限公司及网易公司各关联主体（以下统称"权利方"）之间不存在任何隶属、合作、授权、许可、代理、联营或投资关系。开发者亦非权利方的员工、代理人或者受托人。本软件的名称、界面与文档中出现的"网易云""NCM""网易云音乐"等字样与标识，仅为向用户客观描述本软件所适配的服务对象而作描述性使用，相关名称与标识的一切权利均归权利方所有，权利方随时可以要求开发者调整或停止此类使用。

二、非官方性与服务来源
您通过本软件访问、展示或播放的全部内容，包括但不限于歌曲音频、歌词文本、专辑与歌单封面图片、艺人照片、艺人介绍、歌单信息、用户评论、好友资料以及其他任何形式的数据与素材（以下合称"平台内容"），均来源于权利方或其他第三方平台的服务器。本软件自身不存储、不上传、不修改、不复制任何平台内容的原始数据，仅作为技术工具在您的设备与平台服务器之间转发您本人发起的请求，并将服务器返回的结果在您的设备屏幕上呈现。平台内容的可用性、完整性、准确性、时效性、音质效果以及版权授权状态，均由权利方或者相应的内容提供方单方决定，开发者对上述事项不作任何形式的明示或默示担保与承诺。

三、知识产权归属
本软件的程序代码、界面布局、图标与配套文档的著作权及其他合法权益归开发者依法享有（依据法律规定不享有权利的部分除外）。平台内容的一切知识产权以及相关权益，均归权利方或者相应的内容权利人所有，本软件不对平台内容主张任何权利，亦不因展示平台内容而获得对该内容的任何权利或许可。未经权利人事先书面许可，任何组织和个人不得以任何形式复制、摘编、转载、链接、转贴、传播、放映、出租、出售或者通过网络信息传播等方式使用通过本软件获取的平台内容，也不得将平台内容用于任何商业目的。

四、个人使用限定
本软件仅供您本人出于非商业目的，在中华人民共和国法律以及您所在地区法律允许的范围内，为个人学习、研究与欣赏的目的使用。您在此承诺并保证：不将本软件或者通过本软件获取的任何内容用于商业牟利、广告推广、公开传播、二次分发、汇编出版、营利性放映或者其他任何可能侵犯权利方或第三方知识产权及合法权益的用途；不对本软件进行反向工程、反编译、破解、篡改或者开发衍生作品后再分发；不利用本软件从事任何违反法律法规、危害网络安全、侵犯他人合法权益的活动。您因违反前述承诺而引发的一切法律责任与不利后果，均由您自行承担；因此给开发者或第三方造成损失的，您应当依法予以赔偿。

五、账号安全与使用风险
您理解并同意，使用任何非官方第三方客户端登录个人网络账号，客观上存在固有风险，包括但不限于：被平台安全风控系统临时限制部分功能、触发验证、会话异常掉线，极端情形下账号被冻结或者封禁的可能性。此类风险来源于平台自身的安全策略与算法判定，超出了开发者可以识别和控制的范围。是否使用本软件登录账号、登录哪一账号、何时登录，均系您自主独立作出的决定，由此产生的一切后果由您自行承担。开发者不会通过本软件收集、存储或者向任何服务器上传您的账号密码；您的登录凭证仅保存在您设备的本地存储中，仅用于维持您本人的登录状态，请您妥善保管设备，防止凭证被他人获取。

六、下载与离线功能限制
本软件提供的歌曲下载与离线播放功能，仅作为您在网络条件不佳场景下的个人使用补充手段，不构成对任何内容的授权或许可。您通过该功能获得的音频文件，仅限您本人在设备上离线欣赏，不得以任何方式向任何第三方提供、共享、传播、出售、公开表演或者用于任何盈利性活动。受版权保护的内容能否下载以及下载的音质档位，取决于权利方的授权策略与您本人账号的会员权益状态；下载功能的存在不代表开发者向您授予了任何超出您账号既有权益之外的权利。因下载内容被用于授权范围之外的用途而产生的一切法律责任，由实际使用人自行承担。

七、一起听与社交功能特别提示
本软件提供的"一起听"功能基于平台已有的房间机制实现，用于让您与好友同步欣赏音乐。您在使用该功能时应当遵守平台规则与法律法规，不得传播违法有害信息、骚扰他人或者从事任何破坏房间秩序的行为。受技术条件限制，该功能可能存在加入失败、同步延迟或中断等情形，开发者对该功能的可用性与同步效果不作任何保证。

八、服务可用性不保证
本软件所依赖的第三方接口可能随时因为平台版本升级、安全策略调整、接口协议变更、区域访问限制、服务器故障或者其他不可抗力因素而部分或者全部失效，进而导致登录、搜索、播放、下载、歌词、一起听等任何功能不可用或者体验下降。开发者有权根据实际情况，自行决定对本软件进行更新、修改、暂停或者终止开发与维护，而无需事先通知您，亦不因此对您承担任何形式的责任或赔偿义务。

九、免责条款
在法律允许的最大范围内，对于因下列情形直接或间接导致的一切损失（包括但不限于设备数据丢失、账号价值损失、设备故障或损坏、网络流量与电量消耗、时间成本、业务中断以及任何预期利益损失），开发者均不承担任何责任：（1）您安装、使用或者无法使用本软件的行为；（2）平台内容存在错误、延误、遗漏、侵权或者任何形式的瑕疵；（3）第三方接口变更、限制或者服务中断；（4）网络故障、设备兼容性问题、操作系统限制或者其他系统性故障；（5）您对软件的不当使用、超出声明范围的滥用，或者与其他不兼容软件共同使用；（6）黑客攻击、病毒侵入或者其他恶意行为；（7）不可抗力或者其他开发者无法合理控制的因素。

十、隐私与本地数据
本软件不会主动收集、上传您的个人身份信息，不包含任何广告、统计或者追踪组件。您在使用过程中产生的配置、缓存、下载文件、登录凭证等数据仅保存在您的设备本地存储中，您可以在系统设置或者本软件的设置页面中自行清除。您登录、播放、搜索等操作所必需的网络请求，属于实现软件功能的前提条件，其数据处理行为受相关平台隐私政策约束，不属于开发者的数据收集行为。

十一、第三方开源组件
本软件的开发与构建使用了若干开源社区成果（包括但不限于 Kotlin、Jetpack Compose、OkHttp、Coil、ZXing 等框架与工具库），谨向相关开源项目的作者与贡献者致谢。各开源组件按照其自身的开源协议提供，开发者不对上述组件的质量与安全性承担担保责任。

十二、未成年人使用提示
本软件面向具有完全民事行为能力的成年人设计。若您是未满十八周岁的未成年人，请在监护人的陪同与同意下使用本软件，并由监护人对您的使用行为与使用后果承担相应的监护责任。

十三、协议的变更与终止
开发者有权根据实际情况随时修改、补充本声明，并在软件内或者开源项目页面公布，修改后的声明自公布之时起生效。若您在声明变更后继续使用本软件，即视为您已经接受变更后的全部内容。您可以随时停止使用本软件并将其卸载；您的卸载行为不影响您在卸载之前已经发生的行为所应承担的责任与义务。

十四、适用法律与争议解决
本声明的订立、效力、解释、履行以及争议解决，均适用中华人民共和国法律（不含港澳台地区法律）。因本软件或者本声明引起的或者与之相关的任何争议，双方应当首先友好协商解决；若本声明的任何条款被有权机关认定无效或者不可执行，不影响其他条款的效力，其他条款仍然继续有效并约束双方。

十五、联系与致谢
如您对本声明的任何内容存在疑问，或者权利方认为本软件侵犯其合法权益，欢迎通过本软件的开源项目页面与开发者取得联系，并附上必要的证明材料。开发者将在核实后积极配合处理，包括在必要时停止本软件的开发、更新与分发。最后，感谢您选择并使用本软件，也恳请您在享受音乐的同时，尊重每一位音乐创作者与版权人的劳动成果，合理、合规、有节制地使用技术工具。音乐让生活更美好，尊重让音乐延续。

十六、流量与电量使用提示
本软件运行过程中需要进行网络数据传输（包括登录鉴权、内容检索、音频流播放、图片加载与文件下载等），可能消耗您的移动数据流量并占用一定的设备电量。通过手表设备在线播放音频时的流量消耗尤为明显，建议您在无线局域网环境下使用下载与播放功能，并留意运营商的流量套餐限制。因流量超出套餐、电量消耗或设备发热等造成的任何费用与损失，由您自行承担。

十七、多设备与多账号说明
本软件支持在同一设备上添加与切换多个已登录账号，该功能仅用于方便您管理本人名下的多个账号，不得用于批量操作、自动化任务或任何干扰平台正常服务的场景。您应当妥善保管每一个账号的登录凭证，因账号保管不善导致的任何损失由您自行负责。

十八、下架与停止分发
如权利方通过合法途径提出合理主张，或法律法规、监管政策要求，开发者保留随时停止本软件的更新、分发与技术服务，并从公开渠道下架安装包的权利，且无需对任何用户承担违约或赔偿责任。已经安装本软件的用户应当理解并支持前述安排。"""

/** 首启同意协议页：阅读完整协议，同意后才可进入登录/主页 */
@Composable
fun ConsentScreen(onAgree: () -> Unit, onDecline: () -> Unit) {
    val paragraphs = remember { DISCLAIMER_TEXT.trim().split("\n\n") }
    val listState = rememberLazyListState()
    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .rotaryList(listState)
    ) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.height(30.dp))
            Text(
                "欢迎使用 NetMusicLite",
                fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
            Text(
                "第三方客户端 · 使用前请阅读并同意以下协议",
                fontSize = 9.sp, color = TextSecondary,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(paragraphs, key = { i, _ -> i }) { _, para ->
                    Text(
                        para,
                        fontSize = 9.sp, color = TextPrimary.copy(alpha = 0.9f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "不同意并退出", fontSize = 10.sp, color = TextSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(SurfaceStrong)
                        .pressStretch().clickable { onDecline() }
                        .padding(horizontal = 13.dp, vertical = 8.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "同意并继续", fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    color = com.netmusiclite.ui.theme.OnAccent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Accent)
                        .pressStretch().clickable { onAgree() }
                        .padding(horizontal = 15.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * 主题设置（2026-10-01 新增；同日合并原「背景与玻璃」）：
 * 一个入口统管全部影响观感的设置 ——
 *   配色侧：外观模式（深色 / 浅色 / 封面模糊＝跟随封面自动明暗）/ 跟随壁纸取色 / M3 种子色板；
 *   材质侧：背景图（选图 · 压暗 · 模糊）/ 玻璃卡片（材质 · 强度）；
 *   页面侧：播放页面风格（取色 / 封面模糊 / 自定义，2026-10-06 新增，取代原「保留取色」开关）；
 *   歌词侧：歌词扫光开关（2026-10-02 新增）。
 *
 * 合并理由：玻璃材质的观感直接由主题明暗与种子色决定（浅色主题下玻璃 alpha 是另一套标定），
 * 拆成两个入口只会让人不知道该去哪调。合并后**无任何重复项**：
 *   · 「种子色」与「跟随壁纸取色」互斥 —— 自动取色开启时色板整体变淡且点击无效；
 *   · 「背景模糊」作用于底图、「卡片模糊」作用于玻璃取样，作用对象不同；
 *   · 「播放页面风格」管的是播放器组三页的底（取色 / 封面模糊 / 透壁纸），与上面两项互不重叠。
 * 全部项直接写 Compose 状态，改动立即全局生效（无需重启）。
 */
@Composable
fun ThemeSettingsScreen(nav: NavHostController) {
    if (AppearancePrefs.isM3e) {
        com.netmusiclite.ui.m3e.M3eThemeSettingsScreen(nav)
        return
    }
    val mode = AppearancePrefs.mode
    val dynamicOn = AppearancePrefs.dynamicColor
    val bgReady = BackgroundStore.ready
    // ★ 2026-10-07 用户 bug「选择自定义图片后，封面模糊主题浅色下玻璃卡片效果不生效」：
    //   玻璃开关 / 滑杆的可见性判据从「有没有壁纸」（bgReady）改成「壁纸**是否真的在画**」
    //   （bgLive = wallpaperShown()）—— 封面模糊顶掉壁纸时，壁纸在设置页身后也没露面，
    //   玻璃材质走的是「无图」分支，滑杆/开关必须按真实分支显示（与 frostedGlass 同判据）。
    //   ⚠ 「更换背景图」「恢复默认背景」仍用 bgReady：那两张卡管的是**库存**，不是显示。
    val bgLive = bgReady && wallpaperShown()
    // 播放页面风格：读的是**生效值**（自定义在没壁纸时已回落取色，选中环与副标题才不会骗人）
    val playStyle = AppearancePrefs.playStyle
    val customVisible = AppearancePrefs.playStyleCustomVisible

    // ★ 2026-10-17：M3E 新主题公告改为「本版本首次打开应用时」弹一次（见 AppNav 根层），
    //   这里的主题切换入口不再重复弹公告，只负责「首次使用该主题 → 强制看对应指南」。
    fun applyThemeStyle(style: String) {
        AppearancePrefs.setInterfaceStyle(style)
        // 第一次使用该主题 → 强制观看对应手势指南（看完自动返回本页）
        if (!com.netmusiclite.data.GuideStore.seen(style)) {
            nav.navigateWithMotion(
                Routes.GUIDE_QUICK,
                com.netmusiclite.ui.nav.NavMotionSpec(NavMotionKind.Directional),
            )
        }
    }

    fun selectThemeStyle(style: String) = applyThemeStyle(style)

    StackedCardList(title = "主题") {
        item(key = "interface_classic") {
            StackedIconCard(
                title = "经典",
                subtitle = "已选中",
                icon = NcmIcons.Check,
            ) { selectThemeStyle("classic") }
        }
        item(key = "interface_m3e") {
            StackedIconCard(
                title = "M3E",
                subtitle = "Material 3 Expressive",
                icon = NcmIcons.Theme,
            ) { selectThemeStyle("m3e") }
        }
        // ───────────────── 配色 ─────────────────
        item(key = "mode") {
            StackedCard(height = 92.dp) {
                Column(Modifier.weight(1f)) {
                    Text("外观模式", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        when (mode) {
                            // ★ 2026-10-07 用户口径「增加外观模式封面模糊」：全局自动明暗。
                            //   副标题报出**当前实际解析结果**（跟随封面现在是浅还是深），
                            //   否则选完之后没有地方能看到它此刻按封面判成了什么。
                            ThemeMode.COVER_BLUR ->
                                "跟随封面自动明暗 · 当前${if (AppearancePrefs.isLight) "浅色" else "深色"}"
                            ThemeMode.LIGHT -> "全站浅色"
                            else -> "全站深色"
                        },
                        fontSize = 9.sp, color = TextSecondary, maxLines = 1,
                    )
                    Spacer(Modifier.height(7.dp))
                    Row(Modifier.fillMaxWidth()) {
                        // ⚠ 只保留三个选项：原「跟随系统」已移除 —— 手表系统深浅恒定，
                        //   它与「深色」效果完全相同，是个点下去看不出区别的冗余项。
                        ModeChip("深色", mode == ThemeMode.DARK) {
                            AppearancePrefs.setMode(ThemeMode.DARK)
                        }
                        ModeChip("浅色", mode == ThemeMode.LIGHT) {
                            AppearancePrefs.setMode(ThemeMode.LIGHT)
                        }
                        // 封面模糊 = 全局自动明暗（2026-10-07）：盖在「播放页面风格 = 封面模糊」
                        // 之上不冲突 —— 那个管播放器组底图，这个管全 App 明暗。
                        ModeChip("封面模糊", mode == ThemeMode.COVER_BLUR) {
                            AppearancePrefs.setMode(ThemeMode.COVER_BLUR)
                        }
                    }
                }
            }
        }
        // ★ 2026-10-07 用户口径：选择「封面模糊」外观时才出现的「保留取色」开关 ——
        //   打开 = 外围页（主页等非播放器组页面）保留原有取色/壁纸机制，模糊只在播放页
        //   及其附属页生效；关闭（默认）= 外围页背景也用模糊封面（与播放页同一张底）。
        //   播放器组三页不受本开关影响，仍听「播放页面风格」。
        if (mode == ThemeMode.COVER_BLUR) {
            item(key = "cover_keep_tint") {
                StackedCard(height = 68.dp) {
                    Column(Modifier.weight(1f)) {
                        Text("保留取色", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                        Text(
                            if (AppearancePrefs.coverKeepTint) {
                                "外围页继续用取色/壁纸，模糊只在播放页生效"
                            } else {
                                "主页等外围页的背景也用模糊封面"
                            },
                            fontSize = 9.sp, color = TextSecondary, maxLines = 2,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = AppearancePrefs.coverKeepTint,
                        onCheckedChange = { AppearancePrefs.setCoverKeepTint(it) },
                    )
                }
            }
        }
        // ★ 2026-10-06 用户口径「设置主题栏目里新增播放页面风格：取色 / 封面模糊 / 自定义，
        //   当未设置自定义壁纸时不显示自定义选项」：只作用于播放器组三页
        //   （播放页 / 歌词页 / 更多页），其余页面照旧透出全局背景。
        //   本项取代原「播放页/歌词页保留取色」开关（存量值在 AppearancePrefs.init 里迁移）。
        item(key = "play_style") {
            StackedCard(height = 92.dp) {
                Column(Modifier.weight(1f)) {
                    Text("播放页面风格", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        when (playStyle) {
                            PlayPageStyle.COVER_BLUR -> "封面放大重模糊打底，歌词逐字点亮"
                            PlayPageStyle.CUSTOM -> "播放页透出自定义壁纸"
                            else -> "歌曲封面主色作底，与全站氛围一致"
                        },
                        fontSize = 9.sp, color = TextSecondary, maxLines = 1,
                    )
                    Spacer(Modifier.height(7.dp))
                    Row(Modifier.fillMaxWidth()) {
                        ModeChip("取色", playStyle == PlayPageStyle.AMBIENT) {
                            AppearancePrefs.setPlayStyle(PlayPageStyle.AMBIENT)
                        }
                        ModeChip("封面模糊", playStyle == PlayPageStyle.COVER_BLUR) {
                            AppearancePrefs.setPlayStyle(PlayPageStyle.COVER_BLUR)
                        }
                        // 没有壁纸就没有可透出的东西，这个选项不显示（存量选择也会回落取色）
                        if (customVisible) {
                            ModeChip("自定义", playStyle == PlayPageStyle.CUSTOM) {
                                AppearancePrefs.setPlayStyle(PlayPageStyle.CUSTOM)
                            }
                        }
                    }
                }
            }
        }
        // ★ 2026-10-07 用户口径「深色外观 + 播放页风格封面模糊时，下方出现『使用浅色封面模糊』开关；
        //   浅色外观 + 封面模糊时，出现『使用深色封面模糊』开关」：
        //   开 = 播放器组三页整体（底图明暗 + 卡片/文字/强调色）对调到另一种明暗；
        //   关（默认）= 跟随外观模式，升级后观感不变。
        //   外观模式选「封面模糊」时没有可对调的方向（本来就逐封面自动明暗），不显示本开关。
        if (playStyle == PlayPageStyle.COVER_BLUR &&
            (mode == ThemeMode.DARK || mode == ThemeMode.LIGHT)
        ) {
            val toLight = mode == ThemeMode.DARK
            item(key = "cover_blur_invert") {
                StackedCard(height = 68.dp) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (toLight) "使用浅色封面模糊" else "使用深色封面模糊",
                            fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (toLight) "播放页/歌词页改用浅色观感"
                            else "播放页/歌词页改用深色观感",
                            fontSize = 9.sp, color = TextSecondary, maxLines = 2,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = AppearancePrefs.coverBlurInvert,
                        onCheckedChange = { AppearancePrefs.setCoverBlurInvert(it) },
                    )
                }
            }
        }
        item(key = "dynamic") {
            StackedCard(height = 68.dp) {
                Column(Modifier.weight(1f)) {
                    Text("跟随壁纸取色", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                    Text(
                        AppearancePrefs.dynamicSourceLabel(bgLive),
                        fontSize = 9.sp, color = TextSecondary, maxLines = 2,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = dynamicOn,
                    onCheckedChange = { AppearancePrefs.setDynamicColor(it) },
                )
            }
        }
        item(key = "seed") {
            StackedCard(height = 142.dp) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("种子色", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                    Text(
                        if (dynamicOn) "已由上方自动取色接管，关闭后生效" else "Material 3 由种子色派生整套配色",
                        fontSize = 8.sp, color = TextTertiary, maxLines = 1,
                    )
                    Spacer(Modifier.height(6.dp))
                    // 自动取色开启时整块变淡且不可点 —— 消除「点了没反应」的重复感
                    Column(Modifier.alpha(if (dynamicOn) 0.35f else 1f)) {
                        PRESET_ACCENTS.chunked(4).forEach { rowColors ->
                            Row {
                                rowColors.forEach { c -> AccentSwatch(c, enabled = !dynamicOn) }
                            }
                        }
                    }
                }
            }
        }
        // ★ 2026-10-03 用户口径「设置主题里增加字体大小调整滑块以及字体粗细调整滑块」：
        //   字号覆写 LocalDensity.fontScale、字重平移 FontFamily.Resolver
        //   （实现见 Theme.kt / FontWeightShift.kt），拖动即时全局生效；
        //   右侧读数让档位可见（百分比 / 细·标准·加粗·特粗）。
        item(key = "font_size") {
            StackedCard(height = 88.dp) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("字体大小", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${(AppearancePrefs.fontScale * 100).roundToInt()}%",
                            fontSize = 10.sp, color = Accent, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    androidx.compose.material3.Slider(
                        value = AppearancePrefs.fontScale,
                        onValueChange = { AppearancePrefs.setFontScale(it) },
                        valueRange = 0.8f..1.3f, // 6 档：80% / 90% / 100% / 110% / 120% / 130%
                        steps = 4,
                    )
                }
            }
        }
        item(key = "font_weight") {
            StackedCard(height = 88.dp) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("字体粗细", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.weight(1f))
                        Text(
                            FONT_WEIGHT_LABELS.getOrElse(AppearancePrefs.fontWeightLevel) { "标准" },
                            fontSize = 10.sp, color = Accent, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    androidx.compose.material3.Slider(
                        value = AppearancePrefs.fontWeightLevel.toFloat(),
                        onValueChange = { AppearancePrefs.setFontWeightLevel(it.roundToInt()) },
                        valueRange = 0f..3f, // 4 档：细 / 标准 / 加粗 / 特粗
                        steps = 2,
                    )
                }
            }
        }
        // ───────────────── 背景与玻璃（原独立页，已合并到此） ─────────────────
        // ★ 2026-10-03 用户口径「增加一个系统内置管理器，在自定义壁纸的时候点击按钮后进入」：
        //   点击不再直接拉系统相册，先进内置图片管理器（自动扫描 2020 年后的系统图片，
        //   双列弧形布局点选即设背景；权限在那边进入时申请，拒绝时内置相册作兜底）。
        item(key = "pick") {
            StackedIconCard(
                title = if (bgReady) "更换背景图" else "选择背景图",
                subtitle = if (bgReady) "背景已启用，选择即覆盖" else "内置相册，扫描系统图片任选一张",
                icon = NcmIcons.ImageIc,
                navMotionKind = NavMotionKind.Pill,
                onClick = { nav.navigateWithMotion(Routes.GALLERY) },
            )
        }
        item(key = "dim") {
            StackedCard(height = 88.dp) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("背景压暗", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    // 系统默认 Slider 样式（M3 默认配色/高度/手势）
                    androidx.compose.material3.Slider(
                        value = BackgroundStore.dim,
                        onValueChange = { BackgroundStore.updateDim(it) },
                        valueRange = 0.2f..0.8f,
                    )
                }
            }
        }
        item(key = "bg_blur") {
            StackedCard(height = 88.dp) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("背景模糊强度", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.Slider(
                        value = BackgroundStore.bgBlur,
                        onValueChange = { BackgroundStore.updateBgBlur(it) },
                        valueRange = 0f..1f,
                    )
                }
            }
        }
        // ★ 2026-10-02 用户口径「浅色模式下未选择壁纸也可以设置为玻璃卡片」：
        //   开关出现条件从「已选背景图」放宽为「背景图真的在画 **或** 浅色主题」——
        //   浅色无图（或图被封面模糊顶掉）时 frostedGlass 走「透底轻玻璃」分支
        //   （白 72% + 高光描边），开关不再空转。
        //   （历史上这里还留着「深色无图固定黑色半透明兜底、开关继续隐藏」一条 ——
        //     2026-10-16 已被下面那条口径取代，见下。）
        // ★ 2026-10-07：判据用 bgLive（真的在画）而不是 bgReady（有没有）——
        //   封面模糊主题下壁纸虽在库里却全程不露面，开关/滑杆要按真实分支显示。
        // ★ 2026-10-16 用户口径「深色模式无壁纸的时候有默认壁纸取色当前的专辑封面底色或者
        //   专辑封面底色模糊」：深色 + 无壁纸时**不再固定纯色兜底** —— 取样底改用当前歌曲
        //   封面模糊成的底图（页面底本就是封面取色，封面即那台「默认壁纸」）。
        //   于是这个开关在这两种场合都有可见区别（开 = 封面模糊玻璃卡 / 关 = 纯色卡片），
        //   条件从「壁纸在画 或 浅色主题」放宽为**恒显示**，不再有「切了没区别所以藏起来」的场合。
        item(key = "card_style") {
            StackedCard(height = 66.dp) {
                Column(Modifier.weight(1f)) {
                    Text("玻璃卡片", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                    Text(
                        when {
                            !BackgroundStore.cardGlass -> if (bgLive) "纯黑半透明，更沉稳省电" else "纯色卡片，更沉稳省电"
                            bgLive -> "毛玻璃取样，透出背景"
                            AppearancePrefs.isLight -> "轻玻璃透出页面底色（浅色主题）"
                            else -> "毛玻璃取样，透出封面模糊"
                        },
                        fontSize = 9.sp, color = TextSecondary,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = BackgroundStore.cardGlass,
                    onCheckedChange = { BackgroundStore.updateCardGlass(it) },
                )
            }
        }
        // ★ 2026-10-02 用户口径「玻璃卡片没法调节透明度，一起加上」：
        //   滑杆只属于「浅色 + 壁纸没露面」的轻玻璃形态 —— 壁纸在画时的玻璃形态
        //   控制是「卡片模糊强度」。值越大越透（卡片越「轻」），往左拖 = 更实、文字对比更强。
        if (!bgLive && AppearancePrefs.isLight && BackgroundStore.cardGlass) {
            item(key = "card_transparency") {
                StackedCard(height = 88.dp) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text("玻璃透明度", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.Slider(
                            value = BackgroundStore.cardTransparency,
                            onValueChange = { BackgroundStore.updateCardTransparency(it) },
                            valueRange = 0.05f..0.7f,
                        )
                    }
                }
            }
        }
        // 卡片模糊滑杆只在「玻璃卡片 **且** 壁纸真的在画」时出现 ——
        // 条件与 frostedGlass() 的取样分支一致，没可调对象时不显示。
        if (bgLive && BackgroundStore.cardGlass) {
            item(key = "card_blur") {
                StackedCard(height = 88.dp) {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        Text("卡片模糊强度", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.Slider(
                            value = BackgroundStore.cardBlur,
                            onValueChange = { BackgroundStore.updateCardBlur(it) },
                            valueRange = 0f..1f,
                        )
                    }
                }
            }
        }
        if (bgReady) {
            // 原「播放页/歌词页保留取色」开关已被上方「播放页面风格」三选一取代：
            // 取色 = 开关打开（默认）；自定义 = 关掉开关、播放器组透出背景图。
            item(key = "reset") {
                StackedIconCard(
                    title = "恢复默认背景",
                    subtitle = "移除自定义背景图",
                    icon = NcmIcons.Refresh,
                    iconTint = Accent,
                ) {
                    BackgroundStore.clear()
                    // 壁纸没了「自定义」就无底可透：一并回落到取色，避免下次设了新壁纸时
                    // 播放页风格被静默改回自定义（用户会当成 bug）
                    if (AppearancePrefs.playStyle == PlayPageStyle.CUSTOM) {
                        AppearancePrefs.setPlayStyle(PlayPageStyle.AMBIENT)
                    }
                }
            }
        }
        // ★ 2026-10-02 用户口径「主题栏目中添加歌词扫光的开关」：
        //   不依赖背景图；低性能模式时隐藏，保留原扫光偏好。关闭只停掉移动光斑，
        //   聚焦行提亮（深色主题底噪）保留 —— 副标题如实写清，避免「关了还有效果」的困惑。
        if (!AppearancePrefs.lowPerformanceLyrics) {
            item(key = "lyric_sweep") {
                StackedCard(height = 66.dp) {
                    Column(Modifier.weight(1f)) {
                        Text("歌词扫光", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                        Text(
                            if (AppearancePrefs.lyricSweep) "白色光线随当前句扫过聚焦歌词"
                            else "已关闭扫光，聚焦行提亮保留",
                            fontSize = 9.sp, color = TextSecondary,
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = AppearancePrefs.lyricSweep,
                        onCheckedChange = { AppearancePrefs.setLyricSweep(it) },
                    )
                }
            }
        }
        item(key = "hint") {
            Text(
                "浅色模式会同步重标定玻璃材质与文字层级；所有改动立即生效，无需重启。",
                fontSize = 9.sp, color = TextTertiary, lineHeight = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
            )
        }
        item(key = "low_performance_lyrics") {
            StackedCard(height = 66.dp) {
                Column(Modifier.weight(1f)) {
                    Text("低性能歌词", fontSize = 12.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                    Text("仅保留歌词基础缩放，降低渲染开销", fontSize = 9.sp, color = TextSecondary)
                }
                androidx.compose.material3.Switch(
                    checked = AppearancePrefs.lowPerformanceLyrics,
                    onCheckedChange = { AppearancePrefs.setLowPerformanceLyrics(it) },
                )
            }
        }
    }
}

/** 明暗模式胶囊：选中态实底 + OnAccent 前景，未选中态弱色块（三项均分卡片宽度） */
@Composable
private fun androidx.compose.foundation.layout.RowScope.ModeChip(
    label: String, selected: Boolean, onClick: () -> Unit,
) {
    Box(
        Modifier
            .weight(1f)
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) Accent else SurfaceStrong)
            .pressStretch().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() }
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontSize = 10.sp, maxLines = 1,
            color = if (selected) com.netmusiclite.ui.theme.OnAccent else TextPrimary,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * 主题色圆点：点击即切换全局强调色，选中态描边 + 对勾（白色块用黑勾保证对比度）。
 * [enabled] = false 时不可点（自动取色开启期间种子色已被接管），变淡由调用方负责。
 */
@Composable
private fun AccentSwatch(color: Color, enabled: Boolean = true) {
    val selected = enabled && AppearancePrefs.accent == color
    val isLight = color.luminance() > 0.8f
    Box(
        Modifier
            .padding(end = 10.dp, bottom = 4.dp)
            .size(26.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = when {
                    selected && isLight -> Color.Black
                    selected -> TextPrimary
                    // 浅色块（白/亮色）在深浅两种底上本身就有对比，不需要描边；
                    // 深色块用跟随主题的弱描边（深色主题白 16% / 浅色主题黑 16%）
                    isLight -> Color.Transparent
                    else -> TextPrimary.copy(alpha = 0.16f)
                },
                shape = CircleShape,
            )
            .pressStretch().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
            ) {
                AppearancePrefs.setAccent(color)
            },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                NcmIcons.Check, null,
                tint = if (isLight) Color.Black else Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** 音质选择：标准/较高/无损。无损需 VIP（vipExpire 实时校验），选择即刻对播放与下载直链生效 */
@Composable
fun QualityScreen(nav: NavHostController) {
    var current by remember { mutableStateOf(QualityPrefs.level) }
    var vipValid by remember { mutableStateOf<Boolean?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        vipValid = runCatching { NcmApi.vipExpire(SessionStore.uid) }.getOrNull()
            ?.takeIf { it > System.currentTimeMillis() } != null
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2000); toast = null } }

    data class Opt(val id: String, val label: String, val desc: String)
    val opts = listOf(
        Opt("standard", "标准", "普通音质，最省流量"),
        Opt("exhigh", "较高", "320kbps，音质更好"),
        // ★ 2026-10-18 用户口径「下载无损的格式改为 FLAC」：说明里点明是 FLAC 无损压缩，
        //   用户下载后拿到的就是 .flac 文件（不再是有损 aac 冒充 mp3）。
        Opt("lossless", "无损", "FLAC 无损压缩，需 VIP"),
    )

    Box(Modifier.fillMaxSize()) {
        StackedCardList(title = "音质", progressTotal = opts.size) {
            opts.forEach { o ->
                item(key = o.id) {
                    val locked = o.id == "lossless" && vipValid == false
                    val selected = current == o.id
                    StackedCard(onClick = {
                        when {
                            locked -> { toast = "无损音质需要开通 VIP" }
                            current == o.id -> Unit
                            else -> { QualityPrefs.level = o.id; current = o.id }
                        }
                    }) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                o.label, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                color = if (selected) Accent else TextPrimary,
                            )
                            Text(
                                if (locked) "需开通 VIP（当前账号无有效会员）" else o.desc,
                                fontSize = 9.sp, color = TextSecondary,
                            )
                        }
                        if (selected) {
                            Icon(NcmIcons.Check, null, tint = Accent, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        toast?.let {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(it, fontSize = 11.sp, color = Color.White,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xCC2B2B33))
                        .padding(horizontal = 16.dp, vertical = 7.dp))
            }
        }
    }
}

/**
 * 关于应用（2026-10-02 用户口径「设置列表末尾加关于应用：最上方图标，下方版本号 / 作者 / 项目地址」）。
 *
 * 纯静态展示页。两个工程细节：
 *  · 版本号运行时从 PackageManager 读取而不是写死 —— 升版本只改 build.gradle.kts，这页零维护；
 *  · 应用图标 = 自适应图标的「复刻」：mipmap 里的 XML 是 AdaptiveIconDrawable，
 *    Compose 的 painterResource 不支持直接加载，所以取前景矢量层自己拼到白底圆上
 *    （描边用 TextTertiary 半透明，浅色主题下白圆才不至于和页面底色糊在一起）。
 *
 * 项目地址点击 → 直接复制到剪贴板 + Toast（2026-10-02 用户口径「点击自动复制项目地址」）。
 * 手表上没有可用的浏览器交互，复制之后粘贴到手机端打开是最稳的动线。
 */
@Composable
fun AboutScreen(nav: NavHostController) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val versionName = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "未知"
    }
    val githubUrl = "https://github.com/windzhou999/netmusiclite"
    NcmListPage(title = "关于应用") {
        item(key = "header") {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 4.dp),
            ) {
                Box(
                    Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFFFFF))
                        .border(1.dp, TextTertiary.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = "应用图标",
                        modifier = Modifier.size(58.dp),
                    )
                }
                Spacer(Modifier.height(7.dp))
                Text("NetMusicLite", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
        }
        item(key = "version") {
            Text(
                "版本 $versionName",
                fontSize = 10.sp, color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            )
        }
        item(key = "author") {
            Text(
                "作者 昼小风",
                fontSize = 10.sp, color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            )
        }
        item(key = "github") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(SurfaceStrong)
                    .pressStretch().clickable {
                        // ★ 2026-10-02 用户口径「点击 GitHub 地址自动复制项目地址」：
                        //   不再尝试拉起浏览器 —— 手表上既打不开网页、粘贴场景也在手机端，
                        //   复制是最稳的一步动线。Android 10+ 后台读剪贴板受限，但这里写剪贴板
                        //   是前台交互动作，不受影响。
                        val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("NetMusicLite", githubUrl))
                        android.widget.Toast.makeText(ctx, "已复制项目地址", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 13.dp, vertical = 8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("GitHub 项目地址", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Text(githubUrl, fontSize = 8.sp, color = TextSecondary, maxLines = 1)
                }
                Icon(NcmIcons.ChevronRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
            }
        }
        item(key = "update") {
            CheckUpdateItem()
        }
        item(key = "note") {
            Text(
                "本应用为非官方第三方客户端\n仅供学习交流 · GPL-3.0 开源",
                fontSize = 8.sp, color = TextTertiary, lineHeight = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

/**
 * 检查更新入口（2026-10-02 用户口径「关于应用里加个检查更新」）。
 *
 * 与播放页顶部更新胶囊共用同一套 [UpdateFlow] 状态机与 [UpdateAvailableDialog] 弹窗：
 * 空闲 → 检查中 →（已是最新 Toast ｜ 发现新版本弹窗 → 下载中 → 校验 + 自动拉起安装器）。
 * 下载/换源/授权续装全在状态机里跑，本行只读状态显示副标题。UI 样式复刻同页「GitHub 项目地址」行。
 */
@Composable
private fun CheckUpdateItem() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var checking by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<UpdateInfo?>(null) }
    val flowState by UpdateFlow.state.collectAsState()
    val installedVersionName = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "未知"
    }

    fun toast(msg: String) =
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()

    if (found != null) {
        val info = found ?: return
        UpdateAvailableDialog(
            info = info,
            installedVersionName = installedVersionName,
            onStart = { UpdateFlow.start(ctx, info) },
            onInstallNow = { UpdateFlow.installLocal(ctx) },
            onOpenPermission = { UpdateFlow.gotoInstallPermission(ctx) },
            onDismiss = { found = null },
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(SurfaceStrong)
            .pressStretch().clickable {
                if (checking) return@clickable
                // 已发现新版本（含下载中/待授权）：直接开弹窗，不重复检查
                val known = UpdateChecker.available.value
                if (known != null) {
                    found = known
                    return@clickable
                }
                checking = true
                scope.launch {
                    when (val r = UpdateChecker.checkPublish(ctx)) {
                        is UpdateChecker.Outcome.Available -> found = r.info
                        UpdateChecker.Outcome.UpToDate -> toast("已是最新版本 v$installedVersionName")
                        is UpdateChecker.Outcome.Error -> toast("检查失败：${r.message}")
                    }
                    checking = false
                }
            }
            .padding(horizontal = 13.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text("检查更新", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Text(
                when (val st = flowState) {
                    is UpdateFlow.State.Downloading -> "下载中 ${st.percent}%（失败自动换源）"
                    is UpdateFlow.State.Installing -> "正在安装 v${st.versionName}"
                    is UpdateFlow.State.NeedPermission -> "待授权 · 点此继续"
                    is UpdateFlow.State.Failed -> "更新失败 · 点此重试"
                    UpdateFlow.State.Idle -> if (checking) "正在检查…" else "通过 GitHub 获取新版本"
                },
                fontSize = 8.sp, color = TextSecondary, maxLines = 1,
            )
        }
        Icon(NcmIcons.ChevronRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
    }
}
