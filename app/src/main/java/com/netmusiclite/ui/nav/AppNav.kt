package com.netmusiclite.ui.nav

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.netmusiclite.R
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.first
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.SessionStore
import com.netmusiclite.ui.screens.detail.AlbumScreen
import com.netmusiclite.ui.screens.detail.PlaylistDetailScreen
import com.netmusiclite.ui.screens.friends.ChatScreen
import com.netmusiclite.ui.screens.friends.MessagesScreen
import com.netmusiclite.ui.screens.friends.SharePickScreen
import com.netmusiclite.ui.screens.friends.UserProfileScreen
import com.netmusiclite.ui.screens.guide.GuideDonateScreen
import com.netmusiclite.ui.screens.guide.GuideQqScreen
import com.netmusiclite.ui.screens.guide.GuideScreen
import com.netmusiclite.ui.screens.guide.GuideShapeScreen
import com.netmusiclite.ui.screens.guide.GuideThemeScreen
import com.netmusiclite.ui.screens.guide.SurveyScreen
import com.netmusiclite.ui.screens.history.HistoryScreen
import com.netmusiclite.ui.screens.gallery.GalleryScreen
import com.netmusiclite.ui.screens.local.DownloadManagerScreen
import com.netmusiclite.ui.screens.local.ImportLocalScreen
import com.netmusiclite.ui.screens.player.SongBaikeScreen
import com.netmusiclite.ui.screens.friends.FriendsScreen
import com.netmusiclite.ui.screens.home.HomeScreen
import com.netmusiclite.ui.screens.ipod.IpodCoverFlowScreen
import com.netmusiclite.ui.screens.ipod.IpodExitHost
import com.netmusiclite.ui.screens.ipod.IpodRoutes
import com.netmusiclite.ui.screens.ipod.IpodSource
import com.netmusiclite.ui.screens.library.DailyRecommendScreen
import com.netmusiclite.ui.screens.library.LikedSongsScreen
import com.netmusiclite.ui.screens.library.MyPlaylistsScreen
import com.netmusiclite.ui.screens.listen.ListenRoomScreen
import com.netmusiclite.ui.screens.listen.ListenTogetherScreen
import com.netmusiclite.ui.screens.local.LocalMusicScreen
import com.netmusiclite.ui.screens.login.LoginQrScreen
import com.netmusiclite.ui.screens.player.CollectScreen
import com.netmusiclite.ui.screens.player.LyricsScreen
import com.netmusiclite.ui.screens.player.MoreScreen
import com.netmusiclite.ui.screens.player.PlayerScreen
import com.netmusiclite.ui.screens.player.QueueScreen
import com.netmusiclite.ui.screens.recognize.MusicRecognizeScreen
import com.netmusiclite.ui.screens.roam.PrivateRoamScreen
import com.netmusiclite.ui.screens.search.ArtistScreen
import com.netmusiclite.ui.screens.search.SearchScreen
import com.netmusiclite.ui.screens.settings.AccountScreen
import com.netmusiclite.ui.screens.settings.AddAccountQrScreen
import com.netmusiclite.ui.screens.settings.CacheCleanScreen
import com.netmusiclite.ui.screens.settings.AboutScreen
import com.netmusiclite.ui.screens.settings.DisclaimerScreen
import com.netmusiclite.ui.screens.settings.GestureScreen
import com.netmusiclite.ui.screens.settings.QualityScreen
import com.netmusiclite.ui.screens.settings.SettingsScreen
import com.netmusiclite.ui.screens.settings.SwitchAccountScreen
import com.netmusiclite.ui.screens.settings.QqGroupScreen
import com.netmusiclite.ui.screens.settings.DonateScreen
import com.netmusiclite.ui.screens.settings.ThanksScreen
import com.netmusiclite.ui.theme.screenBg
import com.netmusiclite.ui.components.SwipeBackBox
import com.netmusiclite.ui.components.isRoundScreen

/**
 * 应用导航：未登录 → 扫码登录；已登录 → 主页。
 * 转场为轻缩放+淡入（iOS 观感）。
 * 返回统一走「全局左缘滑动」（SwipeBackBox）；
 * PLAYER/LYRICS/LISTEN_ROOM 有自定义手势语义，不包滑动返回。
 */
@Composable
fun AppRoot() {
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    // 起始路由同步计算（只是内存标记读取）：已登录默认进播放页（左划切主页）
    val dest = remember {
        // TEMP 验证钩子（本轮截图验证后立即删除）：adb am start --es ncm_debug_route lyrics
        val debugRoute = (appCtx as? android.app.Activity)?.intent?.getStringExtra("ncm_debug_route")
        val d = when {
            debugRoute == "lyrics" -> Routes.LYRICS
            // ★ 2026-10-03 表盘形状选择（用户口径「首次启动的时候向导第一个页面改成方表圆表选择」）：
            //   没选过形状 → 直接落到形状选择页，**排在同意页/登录页之前**。
            //   理由：形状决定「圆形裁切 + 弧边内缩」，是整棵树几何的开关；若放在同意页之后，
            //   用户第一眼看到的就是一张形状判错的界面（方表四角裁空 / 圆表两端被切）。
            //   选过一次后此分支永不命中（ScreenShapePrefs.chosen），存量用户升级后行为零变化。
            !com.netmusiclite.data.ScreenShapePrefs.chosen -> Routes.GUIDE_SHAPE
            !com.netmusiclite.data.ConsentStore.agreed -> Routes.CONSENT
            // ★ 2026-10-04 高级版公告问卷（用户口径「在当前版本的安装包首次启动时弹出问卷」）：
            //   已登录 = 老用户（升级上来的）→ 未确认过就先弹问卷，确认完再进应用。
            //   未登录 = 新用户 → 走 LOGIN_QR → 向导，问卷在**向导末尾**出现（见 GUIDE_DONATE）。
            SessionStore.loggedIn ->
                if (com.netmusiclite.data.SurveyStore.done) Routes.PLAYER else Routes.SURVEY
            // ★ 2026-10-06 访客模式（用户口径）：登录页「不登录进入音乐」后的精简主页态 ——
            //   不弹向导/问卷（那是登录用户的流程），直接进主页（只显示本地音乐等四卡）。
            SessionStore.guestMode -> Routes.HOME
            else -> Routes.LOGIN_QR
        }
        // 首帧即就位：登录态起步就是播放页（环境色底），全局背景层不能先闪一帧自定义图
        com.netmusiclite.ui.components.AmbientSurface.active = d in AMBIENT_SURFACE_ROUTES
        com.netmusiclite.ui.components.AmbientSurface.route = d
        d
    }
    // ★ 启动加载页实现为「不透明浮层」：先只组合浮层（首帧极快），
    //   重量级导航图在浮层遮挡下组合 + 首帧落屏后才淡出 —— 揭开即为满帧。
    var showSplash by remember { mutableStateOf(true) }
    /** 导航图（NcmBackground + NavHost）是否开始组合：见下面启动编排 R4 */
    var contentReady by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(Unit) {
        // ★ 冷启动专项 R3/R4（2026-09-30）：上次播放状态（队列/当前歌/进度）的恢复与首帧并行开跑，
        //   并且**在导航图组合之前**落地 —— 播放页一次成型（旧版先按空态组合、状态回来再整体重组，
        //   等于把最重的一页组合两遍）。同时修掉「封面预解码拿不到 current 一直空转」的老问题。
        com.netmusiclite.data.PlayerEngine.preloadState()
        // 最多等 400ms 让状态落地：这段时间屏幕上只有启动浮层，用户无感
        kotlinx.coroutines.withTimeoutOrNull(400L) {
            androidx.compose.runtime.snapshotFlow { com.netmusiclite.data.PlayerEngine.current }
                .first { it != null }
        }
        // 封面/头像预解码：splash 窗口内把冷启动首屏要用的图片读进 Coil 内存缓存
        //（播放页封面 62dp 档 + 主页头像 58dp 档），揭开时直接命中，
        // 省掉首次磁盘/网络读+解码+GPU 上传的一次小顿
        val coverPx = with(density) { 62.dp.roundToPx() }
        val avatarPx = with(density) { 58.dp.roundToPx() }
        val loader = coil.Coil.imageLoader(appCtx)
        // 与显示端同源解析：已下载歌预热落盘封面文件（零网络），未下载仍预热网络 URL
        com.netmusiclite.data.PlayerEngine.current?.let { s ->
            com.netmusiclite.ui.components.songCoverModel(s)?.let { model ->
                loader.enqueue(
                    coil.request.ImageRequest.Builder(appCtx)
                        .data(model)
                        .size(coverPx)
                        .allowHardware(true)
                        .build(),
                )
            }
        }
        com.netmusiclite.data.SessionStore.avatarUrl.takeIf { it.isNotEmpty() }?.let { url ->
            loader.enqueue(
                coil.request.ImageRequest.Builder(appCtx)
                    .data(url)
                    .size(avatarPx)
                    .allowHardware(true)
                    .build(),
            )
        }
        // ★ 2026-10-07 用户口径「封面模糊主题进入软件，完成取色后要渐变出现而不是突然闪出」：
        //   启动窗口内把封面模糊底图先算出来（NcmBackground 组合后才取就太晚了 —— 慢封面
        //   会落在揭示之后，明暗判据/底色当着用户的面换一次）。等它落库再揭开浮层，
        //   用户直接看到终态；慢封面（网络歌）最多等 300ms，等不到照常揭开，
        //   底图稍后由 Crossfade 淡入接住（见 CoverBlurBackdropLayer 的恒挂注释）。
        if (com.netmusiclite.ui.components.coverBackdropNeeded()) {
            com.netmusiclite.data.PlayerEngine.current?.let { s ->
                com.netmusiclite.ui.components.songCoverModel(s)?.let { model ->
                    kotlinx.coroutines.withTimeoutOrNull(300L) {
                        com.netmusiclite.data.CoverBackdrop.pull(
                            appCtx, model, com.netmusiclite.ui.theme.isLightTheme,
                        )
                    }
                }
            }
        }
        // 第一帧只画浮层：组合量极小 → 冷启动首帧从 ~790ms 降到 ~250ms 级
        androidx.compose.runtime.withFrameNanos { }
        // 开始组合重量级导航图（播放页 + 全局背景层）：这段主线程被占（实测 ~900ms），
        // 但整段都在浮层遮挡下完成，用户看不到任何掉帧
        contentReady = true
        // 等两帧：让导航图的组合/布局/首次绘制走完再揭开
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        kotlinx.coroutines.delay(120)
        showSplash = false
        // 冷启动优化 R2（2026-09-25）：warmUp 移出首帧窗口——ExoPlayer 后台构建虽不堵主线程，
        // 但在 W5 的 4 小核上与首帧渲染/首屏组合抢 CPU，正是「刚启动滑动掉帧」的帮凶。
        // ★ R3（2026-09-30）实测：揭示瞬间仍有 1s 级卡顿（Davey 1073ms / Skipped 62 frames），
        //   那一下正是「浮层撤掉 → 播放页首次真正绘制」的窗口，ExoPlayer 构建 + 解码器扫描 +
        //   MediaSession 注册 + 前台服务拉起全挤在里面抢 CPU。现把整段推迟到揭开之后，
        //   期间点歌由 ensurePlayer() 同步兜底，功能不受影响（实测这段仅 ~140ms）。
        kotlinx.coroutines.delay(1000)
        PlayerEngine.warmUp()
    }
    LaunchedEffect(Unit) {
        // 冷启动错峰：网络自愈/房间恢复/缓存预热全部延后 1.5s —— 这些回包回主线程重组，
        // 若在首帧立即执行，正好撞上用户冷启动后的第一轮滑动（W5 + JIT 冷态下重组代价放大数倍）
        kotlinx.coroutines.delay(1500)
        // ★ 启动自愈：登录态但 uid 缺失（登录时 account 恰好失败/多账号旧档）→ 补抓用户资料。
        //   旧条件 dest == Routes.HOME 永远为假（dest 只会是 CONSENT/PLAYER/LOGIN_QR），
        //   补抓从未执行过 → uid=0 的账号听歌排行/歌曲百科云端次数整个拿不到（百科退显本地计数）
        if (SessionStore.loggedIn && SessionStore.uid == 0L) {
            runCatching { com.netmusiclite.data.NcmApi.ensureUid() }
        }
        // 红心列表同步（原在 warmUp 预热期，错峰到此处）
        if (SessionStore.loggedIn) PlayerEngine.refreshLikes()
        // ★ 一起听断线恢复：服务端仍报在房（杀进程/掉线后重进）→ 直接复活房间会话，
        //   轮询/心跳/房间聊天全部接上 —— 「退出软件重进实际仍在歌房」
        if (SessionStore.loggedIn && !com.netmusiclite.data.ListenSession.active) {
            val restored = runCatching { com.netmusiclite.data.ListenSession.tryRestore() }.getOrDefault(false)
            if (restored) {
                android.widget.Toast.makeText(
                    appCtx, "一起听房间已恢复，可在「一起听」回到房间",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
        // 预加载：我喜欢/歌单/每日推荐后台预热，进页面秒开
        com.netmusiclite.data.PageCache.prefetch()
    }
    // ★ 更新通道（2026-10-02 用户口径「每次启动检测新版本，有则播放页弹胶囊」）：
    //   静默拉 update.json（失败不打扰）；发现新版本 → UpdateChecker.available 非空 →
    //   播放页顶部胶囊弹出，点它走下载/授权/自动安装。
    LaunchedEffect(Unit) {
        // 与首帧错峰：等启动浮层揭开、冷启动抖动过去再跑（同下方权限跳转的编排）
        kotlinx.coroutines.delay(1800)
        com.netmusiclite.update.UpdateChecker.checkQuietly(appCtx)
    }
    // ★ 安装权限（2026-10-02 用户口径「进入应用时自动申请允许次来源应用权限」）：
    //   登录态（=已过同意页、在正常使用）且未授权 → 跳系统「允许来自此来源」设置页。
    //   ★ 2026-10-17 用户口径「主动申请只跳一次，不管用户是否拒绝都不跳第二次」：
    //   是否申请过落盘在 ncm_update/install_perm_prompted，拒绝后不再每次启动弹系统页。
    //   用户同意后返回，MainActivity.onResume → UpdateFlow 自动续上停在权限这一步的更新。
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(2400)
        if (SessionStore.loggedIn) {
            com.netmusiclite.update.UpdateChecker.requestInstallPermissionOnce(appCtx)
        }
    }

    // 全局底层背景层：默认纯色，设置背景图后为高斯模糊图（玻璃材质的底）
    //
    // ★ 2026-10-02 方形设备适配（用户口径「检测到安装设备是方形的时候，填充空缺区域」）：
    //   圆形裁切只在**圆屏**上做。方屏（方形表 / 手机 / 模拟器）照旧裁圆的话，四角会被裁空，
    //   而 MainActivity 为了冷启动首帧优化把窗口背景设成了 null ⇒ 四角直接露黑，
    //   也就是用户看到的「空缺区域」。方屏改为不裁切：背景层与所有页面（含启动浮层）
    //   一起铺满整屏，四角由环境色/自定义背景图填满。
    //   判据见 components/Common.kt 的 isRoundScreen()（读系统 SCREENROUND 位）。
    Box(
        Modifier
            .fillMaxSize()
            .then(if (isRoundScreen()) Modifier.clip(CircleShape) else Modifier),
    ) {
        // ★ 冷启动专项 R4（2026-09-30）：导航图与全局背景层等 contentReady 才组合。
        //   实测首帧窗口主线程被占 ~900ms（Choreographer: Skipped 55 frames / Davey 936ms），
        //   全部来自「播放页首轮组合」。现在先只组合浮层（极轻，首帧 200ms 级就到），
        //   再在浮层遮挡下组合重量级导航图 —— 用户看到的是「秒开启动页 → 平滑揭开」，
        //   不再有揭开瞬间的 1s 大帧。同时 restoreState 提前到组合之前落地，
        //   播放页一次成型（旧版先按空态组合、状态回来后再整体重组，等于组合两遍）。
        if (contentReady) {
        com.netmusiclite.ui.components.PrepareNcmBackground()
        val m3e = com.netmusiclite.data.AppearancePrefs.isM3e
        val nav = rememberThemeNavController(m3e)
        // ★ 2026-10-17 用户口径「这个版本第一次打开时弹出一个公告：更新了新 Material 风格主题」：
        //   根层一次性公告（本版本限定，见 GuideStore 公告段；下次发版整段删除调用点）。
        //   放在根层而非某个页面：冷启动任何首屏（同意页 / 登录页）都能看到，与向导/设置入口解耦。
        var m3eLaunchAnnounce by remember {
            mutableStateOf(com.netmusiclite.data.GuideStore.shouldShowM3eAnnounce())
        }
        if (m3eLaunchAnnounce) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                com.netmusiclite.ui.screens.guide.M3eAnnounceDialog(
                    onGood = {
                        com.netmusiclite.data.GuideStore.markM3eAnnounced()
                        m3eLaunchAnnounce = false
                    },
                    onLeave = {
                        com.netmusiclite.data.GuideStore.markM3eAnnounced()
                        m3eLaunchAnnounce = false
                    },
                )
            }
        }
        if (com.netmusiclite.ui.components.GestureDonationGate.visible) {
            com.netmusiclite.ui.components.GestureDonationDialog(
                onAcknowledge = { com.netmusiclite.ui.components.GestureDonationGate.acknowledge() },
            )
        }
        // ★ 2026-10-17（本轮）默认主题转场回退到 netmusiclite-1.24-r2：全局背景层回到
        //   NavHost 外侧 —— 1.24-r2 的转场只缩放/淡入**页面内容**，背景（环境色 / 自定义壁纸 /
        //   模糊封面）静止不动；10-16/17 的 m3e 改造把背景改成 per-destination（跟着页面一起转场），
        //   默认主题也因此变成「背景跟着缩放」、退页淡出时露出黑边。
        //   这里只恢复默认主题；m3e 仍走 per-destination（见 ThemeNavHost/backable 的 isM3e 分支）。
        if (!m3e) com.netmusiclite.ui.components.NcmBackground(prepareResources = false)
        // 2026-10-01 晚（用户要求）：原来这里有一个「艺人页歌曲列表左划 → 专辑 Tab」的手势信号
        //   （待消费布尔 + onSwipeLeft 回调），现连同 Album Tab 的过渡动画一并移除，
        //   所以这个状态和它下面那串透传参数都不需要了。
        // 播放器组页面在栈顶 → 全局背景层切环境色（NcmBackground.AmbientSurface）：
        // 手势拖拽半透明/滑出淡出/转场缝隙透出的全是环境色，自定义图任何时刻都不会闪现。
        // ★ 路由必须在组合期读取（下面 currentRoute 这行）：只在 SideEffect lambda 里读
        //   是组合后执行、不订阅状态 —— 路由变化不会重组本层，active 会卡死在初值，
        //   表现为「所有页面的自定义背景全没了」（2026-09-26 首版就栽在这里）。
        val currentEntry by nav.currentBackStackEntryAsState()
        val currentRoute = currentEntry?.destination?.route
        androidx.compose.runtime.SideEffect {
            com.netmusiclite.ui.components.AmbientSurface.active = currentRoute in AMBIENT_SURFACE_ROUTES
            // 2026-10-06：封面模糊风格下播放页与歌词页的底图构图不同，全局背景层要按路由换图
            com.netmusiclite.ui.components.AmbientSurface.route = currentRoute
        }
        DisposableEffect(nav) {
            TransitionCoordinator.bind(nav)
            // 2026-10-03（华为 Watch 3）：系统 back（右滑）的导航语义注册 —— 详见 onSystemBack
            SystemBackRouter.navBack = { onSystemBack(nav) }
            onDispose {
                TransitionCoordinator.unbind(nav)
                SystemBackRouter.navBack = null
            }
        }
        ThemeNavHost(
        navController = nav,
        material = m3e,
        startDestination = dest!!,
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned(TransitionCoordinator::updateViewport)
            // ★ 2026-10-02 触点观察者（被动，绝不消费事件）：在 Initial pass 读按下位置，
            //   归一化后记进 TransitionCoordinator —— 点击导航时用作缩放转场的原点。
            //   requireUnconsumed=false 保证即使子控件（按钮/滑动返回）吃掉了事件也能读到；
            //   放在 NavHost 根上，一处覆盖全部点击入口（不用逐个调用点传位置）。
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (size.width > 0 && size.height > 0) {
                        TransitionCoordinator.noteTap(
                            down.position.x / size.width,
                            down.position.y / size.height,
                        )
                        // 松手时刷新新鲜度（位置仍是按下点）：慢点击（按住 >1s 才松手）也按
                        // 「本次点击」处理；异步跳转（扫码完成/登出回主页）距松手已久，超窗回退。
                        // 初始 pass 先于子控件（Main pass）的 onClick 收到 up ⇒ 刷新一定先于导航。
                        if (waitForUpOrCancellation(PointerEventPass.Initial) != null) {
                            TransitionCoordinator.markTapFresh()
                        }
                    }
                }
            },
        // ★★ 转场按来源分两套（2026-09-30 曲线统一 → 2026-10-01 按来源分派动效）
        //   ① 滑动来源（NavMotionKind.Slide）：标准传送带 —— 旧页整幅滑出、新页并排推入，
        //      两页硬拼接、全程零淡化。分界线把画面切成两半 ⇒ 全帧平均亮度就是归一化位移的
        //      直接读数（旧页《新建歌单6》195.90 / 新页《我的》238.10），据此反解出曲线，
        //      RMSE 0.0078，优于最好的内置缓动（FastOutSlowIn 0.0300）近 4 倍。
        //      用户明确要求「滑动切换不变」，这套一个字没动。
        //   ② 点击 / 自动来源（Pill / Circle / Directional）：统一缩放（用户要求「所有点击
        //      切换场景换成缩放动画」）。曲线与时长仍共用 ① 的 NavGlide / NAV_MS，
        //      只有位移换成了缩放 + 淡化，所以两套动效的「节奏」还是同一个。
        //   （旧守则「按目标页是否播放器组分支」已废弃：滑动对开时两页不重叠，
        //     入页本身不透明，既不会露自定义背景图，也不会有静止底衬的同屏感。）
        enterTransition = {
            // ★ 2026-10-01：栈底右滑（见 PlayerScreen）走的是「前进导航到主页」，但必须装成
            //   返回 —— 否则下层页会从右边滑入、被拖出去的页往左飞（用户报的「第一次右滑
            //   退出动画往左飘」）。武装了手势旗标时，入场页从左侧滑入，与 popEnter 同款。
            if (TransitionCoordinator.isGesturePop(initialState)) {
                // ★ 2026-10-01：右滑返回整条链路改为**缩放转场**（用户两次反馈「右滑不是缩放」）。
                //   退出页的缩小由它自己的 SwipeBackFader 完成（跟手缩到 0.55 → 松手收向 0.50），
                //   下层页照轻舟实测「不缩放、只在后半程淡入」，所以这里把原来的左侧滑入换成
                //   延迟淡入 —— 两页之间不再有任何横向位移，只剩缩放 + 淡化。
                val ms = TransitionCoordinator.gesturePopExitMs(initialState)
                fadeIn(
                    tween(
                        (ms * 0.75f).toInt().coerceAtLeast(60),
                        delayMillis = (ms * 0.25f).toInt(),
                        easing = LinearEasing,
                    ),
                )
            } else {
                val spec = TransitionCoordinator.read(targetState)
                if (spec.kind.isSwipe) {
                    // 滑动来源：传送带对开（退页左移滑出、入页同速从右滑入）—— 用户要求「滑动切换不变」；
                    // 竖向来源（SlideV，下滑进评论）同款曲线/时长，仅方向换成「从上方往下滑入」（2026-10-03）
                    if (spec.kind.isVertical) slideInVertically(navSpec()) { -it }
                    else slideInHorizontally(navSpec()) { it }
                } else {
                    // 点击/自动来源：轻舟「缩放」转场规格（见文件末尾 QZ_* 常量）。
                    // 入页 50% → 100%，带回弹（峰值 1.038 @ 62% 进度）；内容在总时长 ~32%
                    // 内淡入完毕（实测 α 0.30→1.00）。
                    // ★ 2026-10-02：缩放原点由**点击位置**决定（用户要求「打开中心由使用场景
                    //   点击位置而定」）—— spec 里的 origin 由 navigateWithMotion 用
                    //   NavHost 触点观察者捕获的手指触点覆写（异步跳转超窗则回退控件中心）。
                    // ★ 2026-10-17（本轮）用户口径「默认主题过渡动画同步为 netmusiclite-1.24-r2」：
                    //   打开方向恢复 1.24-r2 的 350ms / 内容淡入 112ms
                    //   （撤销同日早先「打开速度改为原来 0.9」的 389/124）。
                    scaleIn(
                        animationSpec = tween(QZ_MS, easing = QzZoom),
                        initialScale = QZ_FROM,
                        transformOrigin = TransformOrigin(spec.originX, spec.originY),
                    ) + fadeIn(tween(QZ_FADE_IN_MS, easing = QzFadeIn))
                }
            }
        },
        exitTransition = {
            // ★ 2026-10-01：同上 —— 武装了手势旗标的「前进导航」，被滑出页的位移完全由
            //   它自己的 SwipeBackFader 接续（手指方向，向右），NavHost 只负责淡出。
            //   （pop 场景不会走到这里 —— pop 用的是 popExit，所以这个分支只服务栈底右滑。）
            if (TransitionCoordinator.isGesturePop(initialState)) {
                // 淡出与 SwipeBackFader 的缩放**同长收束**：旧版是 ms*0.75，比缩放早结束，
                // 页面缩到一半就透明了，观感像"没缩完就消失"（用户反馈的"犹豫"来源之一）。
                val ms = TransitionCoordinator.gesturePopExitMs(initialState)
                fadeOut(tween(ms))
            } else {
                val spec = TransitionCoordinator.read(targetState)
                if (spec.kind.isSwipe) {
                    // 竖向来源：旧页与入页同向 —— 往下滑出（传送带下一格）
                    if (spec.kind.isVertical) slideOutVertically(navSpec()) { it }
                    else slideOutHorizontally(navSpec()) { -it }
                } else {
                    // 轻舟的退页**完全不缩放**（实测恒 1.000，模板匹配 NCC 0.996~0.999，四帧无漂移），
                    // 只是在总时长前约 46% 内淡出（实测亮度因子 0.951→0.562）。
                    // 这条是与旧版最大的差别：旧版退页反向放大到 108%，轻舟没有这个「掠镜」效果。
                    // ★ 2026-10-02 用户 bug「点击更多时播放页画面残留」：淡出不再线性，改 [QzFadeOut]
                    //   加速消隐（残留乘积峰值 0.49→0.28 的推导见 [QZ_FADE_OUT_MS] 注释）。
                    // ★ 2026-10-17（本轮）：默认主题转场回退到 1.24-r2 —— 旧页恢复 130ms 加速淡出；
                    //   背景层已回到 NavHost 外侧，退页淡出后露出的是静态背景，不再是黑边。
                    fadeOut(tween(QZ_FADE_OUT_MS, easing = QzFadeOut))
                }
            }
        },
        // 返回方向就是参考录屏里那条曲线本身：旧页整幅右移滑出、下层页从左侧并排滑入。
        // 两页不重叠，所以不再需要「下层页提前淡入盖住退页」的补丁（旧版 60-90ms 微淡入
        // 的观感是「下层页直接闪现」，2026-09-26 的注释已记录）。
        popEnterTransition = {
            // ★ 2026-10-17（本轮）：默认主题转场回退到 netmusiclite-1.24-r2 ——
            //   返回方向被揭示的下层页恢复「后半程 130ms 淡入」（窗口 [220,350]），
            //   撤销 2026-10-16「被揭示页从第一帧就完整可见」的系统式改动。
            if (TransitionCoordinator.isGesturePop(initialState)) {
                // ★ 2026-10-01：手势返回改为缩放转场，被揭示的下层页不缩放、
                //   只在后半程淡入（与轻舟实测一致），不再从左缘并排滑入。
                val ms = TransitionCoordinator.gesturePopExitMs(initialState)
                fadeIn(
                    tween(
                        (ms * 0.75f).toInt().coerceAtLeast(60),
                        delayMillis = (ms * 0.25f).toInt(),
                        easing = LinearEasing,
                    ),
                )
            } else {
                val spec = TransitionCoordinator.read(initialState)
                if (spec.kind.isSwipe) {
                    // 整幅不透明滑入，与 popExit 同速对开（返回传送带）；
                    // 竖向来源反向：被揭示页从下方滑回（2026-10-03）
                    if (spec.kind.isVertical) slideInVertically(navSpec()) { it }
                    else slideInHorizontally(navSpec()) { -it }
                } else {
                    // 轻舟返回时，被揭示的下层页同样**不缩放**（实测恒 1.000），
                    // 淡入发生在后半程 —— 前半程屏幕上只有正在缩小的上层页（见 popExit）。
                    // ★ 2026-10-02：窗口随 [QZ_FADE_OUT_MS] 收窄为 [220,350]，与 popExit 的
                    //   退页淡出窗口**完全重合**（同为 130ms 整段对开），修掉返回方向同类残留。
                    fadeIn(
                        tween(
                            QZ_FADE_OUT_MS,
                            delayMillis = QZ_MS - QZ_FADE_OUT_MS,
                            easing = LinearEasing,
                        ),
                    )
                }
            }
        },
        popExitTransition = {
            if (TransitionCoordinator.isGesturePop(initialState)) {
                // ★ 2026-10-01：手势返回的**缩放由退出页自己的 SwipeBackFader 负责**
                //   （跟手缩到 0.55 → 松手收向 0.50），NavHost 这里只做淡出。
                //   ⚠ 不要在这里再叠 scaleOut —— SwipeBackFader 已经在缩，两层缩放会相乘，
                //   页面会直接缩成一个小点。
                val ms = TransitionCoordinator.gesturePopExitMs(initialState)
                // 与 SwipeBackFader 的缩放同长收束（旧版 ms*0.75 会早于缩放结束，页面缩到
                // 一半就透明，显得"没缩完就没了"）。
                fadeOut(tween(ms))
            } else {
                val spec = TransitionCoordinator.read(initialState)
                if (spec.kind.isSwipe) {
                    // 竖向来源反向：评论页往上滑出（原路返回）
                    if (spec.kind.isVertical) slideOutVertically(navSpec()) { -it }
                    else slideOutHorizontally(navSpec()) { it }
                } else {
                    // 与入场共用 QzZoom，形成**同形镜像回弹**（2026-10-02 按用户要求补回弹）：
                    // scaleOut 算 1 + (target-1)·E(u)，E 峰值 1.077 @62% ⇒
                    // scale 先缩到 0.462（越过 0.50 的「过冲」）再回稳到 0.500 —— 收尾有回弹。
                    // 起点斜率 ≈ 7.6（快起步、无「先顿一下」）：
                    // 旧 QzZoomBack（时间反演，前 38% 反向放大）才是「犹豫」；QzExitMono
                    // （严格单调）则是「退出没有回弹」，两者都已被本次替换。
                    scaleOut(
                        animationSpec = tween(QZ_MS, easing = QzZoom),
                        targetScale = QZ_FROM,
                        // ★ 2026-10-02：返回也缩向**当初打开它的点击位置**（entry 上持久化的
                        //   origin，正是入场时的手指触点）—— 打开/返回构成同点镜像。
                        transformOrigin = TransformOrigin(spec.originX, spec.originY),
                    ) + fadeOut(
                        // ★ 2026-10-02：与 popEnter 被揭示页的淡入窗口对齐（同为 [220,350]），
                        //   缓动改 [QzFadeOut] 加速消隐 —— 修掉返回方向同类的「交叉溶合」残留：
                        //   旧窗口 [238,350] 里播放页标题逐渐显影时，更多页还半透明地缩着
                        //   （返回录屏逐帧实测：7.305→7.487s 标题亮度 83→22 渐显，与上层页重叠）。
                        tween(
                            QZ_FADE_OUT_MS,
                            delayMillis = QZ_MS - QZ_FADE_OUT_MS,
                            easing = QzFadeOut,
                        ),
                    )
                }
            }
        },
    ) {
        // 带滑动返回的页面
        fun backable(
            onSwipeLeft: (() -> Unit)? = null,
            content: @Composable (androidx.navigation.NavBackStackEntry) -> Unit,
        ): @Composable (androidx.navigation.NavBackStackEntry) -> Unit = { e ->
            SwipeBackBox(onBack = { nav.popBackStack() }, onSwipeLeft = onSwipeLeft, nativeNavigation = m3e) {
                Box(Modifier.fillMaxSize()) {
                    // 默认主题：背景由 AppRoot 全局层承担（1.24-r2 架构）；m3e 仍由页面自带。
                    if (m3e) {
                        com.netmusiclite.ui.components.NcmBackground(route = e.destination.route, prepareResources = false)
                    }
                    content(e)
                }
            }
        }

        // ★ 2026-10-03 引导流程第一屏：表盘形状选择（详见 Routes.GUIDE_SHAPE 与 GuideShapeScreen）
        themeComposable(m3e, Routes.GUIDE_SHAPE) {
            GuideShapeScreen(onNext = {
                // 选完形状按当前登录/同意状态分流（形状本身已在点击时落盘）
                val target = when {
                    !com.netmusiclite.data.ConsentStore.agreed -> Routes.CONSENT
                    SessionStore.loggedIn ->
                        if (com.netmusiclite.data.GuideStore.done) Routes.PLAYER else Routes.GUIDE
                    else -> Routes.LOGIN_QR
                }
                nav.navigateWithMotion(target, NavMotionSpec(NavMotionKind.Directional)) {
                    popUpTo(Routes.GUIDE_SHAPE) { inclusive = true }
                }
            })
        }
        themeComposable(m3e, Routes.CONSENT) {
            com.netmusiclite.ui.screens.settings.ConsentScreen(
                onAgree = {
                    com.netmusiclite.data.ConsentStore.agree() // 修复：此前漏存同意标记导致每次启动都弹
                    // 形状仍未选定（异常路径兜底）→ 先补选形状，再按登录态分流
                    val target = when {
                        !com.netmusiclite.data.ScreenShapePrefs.chosen -> Routes.GUIDE_SHAPE
                        SessionStore.loggedIn ->
                            if (com.netmusiclite.data.SurveyStore.done) Routes.PLAYER else Routes.SURVEY
                        else -> Routes.LOGIN_QR
                    }
                    nav.navigateWithMotion(target, NavMotionSpec(NavMotionKind.Directional)) {
                        popUpTo(Routes.CONSENT) { inclusive = true }
                    }
                },
                onDecline = {
                    (nav.context as? android.app.Activity)?.finishAffinity()
                },
            )
        }
        themeComposable(m3e, Routes.LOGIN_QR) {
            LoginQrScreen(
                onLoggedIn = {
                    // ★ 2026-10-02 使用向导（用户要求）：首次登录（从未看过）先弹向导，
                    //   走完「手势说明 → 官方QQ群 → 捐赠作者」再进播放页；看过一次后直接进播放页。
                    // ★ 2026-10-03：形状仍未选定（异常路径兜底）时先补选形状 —— 它决定圆形裁切
                    //   与弧边内缩，必须在使用界面前定下来。
                    val target = when {
                        !com.netmusiclite.data.ScreenShapePrefs.chosen -> Routes.GUIDE_SHAPE
                        // 2026-10-17：向导第一页改为「选择主题」（默认 / Material），
                        // 确认后进入对应主题的手势图册
                        !com.netmusiclite.data.GuideStore.done -> Routes.GUIDE_THEME
                        // 向导看过但问卷还没确认（登出重登等异常路径）→ 补弹问卷
                        !com.netmusiclite.data.SurveyStore.done -> Routes.SURVEY
                        else -> Routes.PLAYER
                    }
                    nav.navigateWithMotion(target, NavMotionSpec(NavMotionKind.Directional)) {
                        popUpTo(Routes.LOGIN_QR) { inclusive = true }
                    }
                },
                // ★ 2026-10-06 访客模式（用户口径「不登录进入音乐」）：落盘访客标记 →
                //   清栈进主页（HomeScreen 按登录态显示精简四卡）。登录页本身已在栈底，
                //   popUpTo(inclusive) 保证右滑不会回到登录页。
                onGuestEnter = {
                    com.netmusiclite.data.SessionStore.guestMode = true
                    nav.navigateWithMotion(Routes.HOME, NavMotionSpec(NavMotionKind.Directional)) {
                        popUpTo(Routes.LOGIN_QR) { inclusive = true }
                    }
                },
            )
        }
        themeComposable(m3e, Routes.GUIDE_THEME) {
            GuideThemeScreen(onNext = {
                nav.navigateWithMotion(Routes.GUIDE, NavMotionSpec(NavMotionKind.Directional))
            })
        }
        themeComposable(m3e, Routes.GUIDE) {
            GuideScreen(onNext = {
                nav.navigateWithMotion(Routes.GUIDE_QQ, NavMotionSpec(NavMotionKind.Directional))
            })
        }
        // 切换主题后的独立指南（2026-10-17）：只看对应主题的卡组，看完标记该主题已看并返回设置页
        themeComposable(m3e, Routes.GUIDE_QUICK) {
            GuideScreen(onNext = {
                com.netmusiclite.data.GuideStore.markDone(com.netmusiclite.data.AppearancePrefs.interfaceStyle)
                nav.popBackStack()
            })
        }
        themeComposable(m3e, Routes.GUIDE_QQ) {
            GuideQqScreen(onNext = {
                nav.navigateWithMotion(Routes.GUIDE_DONATE, NavMotionSpec(NavMotionKind.Directional))
            })
        }
        themeComposable(m3e, Routes.GUIDE_DONATE) {
            GuideDonateScreen(onDone = {
                com.netmusiclite.data.GuideStore.markDone(com.netmusiclite.data.AppearancePrefs.interfaceStyle)
                // ★ 2026-10-04：向导末尾不再直接进播放页，而是接**高级版公告问卷**
                //   （用户口径「如果之前没有登录过那就在向导末尾出现」）。
                //   清栈交给问卷确认后的那一步 —— 这里不 pop，返回键才能正常留在向导栈里。
                nav.navigateWithMotion(Routes.SURVEY, NavMotionSpec(NavMotionKind.Directional))
            })
        }
        // ★ 2026-10-04 高级版公告问卷：老用户首次启动 / 新用户向导末尾，两条路径共用本页
        themeComposable(m3e, Routes.SURVEY) {
            SurveyScreen(onConfirm = {
                com.netmusiclite.data.SurveyStore.markDone()
                // 确认完整体进应用：清空返回栈（向导各页已在进入时逐级移除）
                nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Directional)) {
                    popUpTo(0) { inclusive = true }
                }
            })
        }
        themeComposable(m3e, Routes.HOME, backgroundInContent = true) { HomeScreen(nav) }
        // ★ 2026-10-23 搜索域（项3）：搜索页统一走 SearchRoute（定义见本文件末尾），
        //   为了在「输入框有焦点」期间禁用左滑手势 —— 原因与实现见 SearchRoute 的 KDoc。
        themeComposable(m3e, Routes.SEARCH, backgroundInContent = true) { e ->
            SearchRoute(nav, e, initialQuery = null, m3e = m3e)
        }

        themeComposable(m3e,
            Routes.SEARCH_Q,
            arguments = listOf(navArgument("q") { type = NavType.StringType }),
            backgroundInContent = true,
        ) { e ->
            // 带预填关键词（听歌识曲结果直达）：预填即是一次「用户已提交的词」，
            // SearchScreen 首帧 kw 非空 → 组词感知防抖照常等 350ms 后搜一次。
            val q = e.arguments?.getString("q") ?: ""
            SearchRoute(nav, e, initialQuery = java.net.URLDecoder.decode(q, "UTF-8"), m3e = m3e)
        }
        themeComposable(m3e, Routes.DAILY_RECOMMEND, backgroundInContent = true, content = backable { DailyRecommendScreen(nav) })
        // 左划进播放页（与我喜欢的/歌单列表页，用户要求 2026-09-25）
        themeComposable(m3e, 
            Routes.LIKED_SONGS,
            backgroundInContent = true, content = backable(onSwipeLeft = {
                nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Slide))
            }) { LikedSongsScreen(nav) },
        )
        themeComposable(m3e, Routes.MY_PLAYLISTS, backgroundInContent = true, content = backable { MyPlaylistsScreen(nav) })
        themeComposable(m3e, Routes.LOCAL_MUSIC, backgroundInContent = true, content = backable { LocalMusicScreen(nav) })
        // 导入本地音乐（2026-10-03 用户口径「点击导入本地音乐后进入界面」）
        themeComposable(m3e, Routes.LOCAL_IMPORT, backgroundInContent = true, content = backable { ImportLocalScreen() })
        themeComposable(m3e, Routes.RECOGNIZE, backgroundInContent = true, content = backable { MusicRecognizeScreen(nav) })
        themeComposable(m3e, Routes.PRIVATE_ROAM, backgroundInContent = true, content = backable { PrivateRoamScreen(nav) })
        themeComposable(m3e, Routes.FRIENDS, backgroundInContent = true, content = backable { FriendsScreen(nav) })
        themeComposable(m3e, Routes.SETTINGS, backgroundInContent = true, content = backable { SettingsScreen(nav) })
        themeComposable(m3e, Routes.RECORD, backgroundInContent = true, content = backable {
            com.netmusiclite.ui.screens.record.RecordScreen(nav)
        })
        // ★ 2026-10-17 用户口径「长按 每日推荐 / 听歌排行 / 我的歌单 / 我喜欢 卡片进入 iPod 界面」：
        //   来源随路由参数传入，其余导航/返回语义（右滑返回、m3e WearOS 转场）与普通页面一致。
        //
        // ★★ 2026-10-17（第二轮）退出转场：m3e 的 Wear 原生 SwipeDismissableNavHost **只负责前进
        //   方向**的转场（新页 0.75→1 缩放 + 0.1→1 淡入），程序化 pop（本页上滑返回 / 系统返回键）
        //   是瞬间消失、没有任何动画。这里用 IpodExitHost 把含背景的**整页**包起来：退出时镜像
        //   前进方向播放「1→0.75 缩放 + 1→0.1 淡出」约 400ms，动画播完才真正 pop；
        //   右滑返回仍交给 Wear 原生 swipe-dismiss（跟手动画本来就是完整的，不在这里重复实现）。
        //   经典主题不包这一层 —— NavHost 自带 popExit 缩放转场，再叠页内动画会双重收场。
        themeComposable(m3e,
            IpodRoutes.PATTERN,
            arguments = listOf(navArgument(IpodRoutes.ARG) {
                type = NavType.StringType
                defaultValue = IpodSource.DAILY.name
            }),
            backgroundInContent = true,
        ) { e ->
            // 背景必须和抽取界面处在同一层退出动画里（否则缩小的只是内容、背景原地不动），
            // 所以这里不复用 backable（它的 NcmBackground 在动画层之外），手写等价结构。
            val ipodSource = IpodRoutes.parse(e.arguments?.getString(IpodRoutes.ARG))
            val ipodPage: @Composable (onExitRequest: (() -> Unit)?) -> Unit = { onExitRequest ->
                Box(Modifier.fillMaxSize()) {
                    // 默认主题背景由 AppRoot 全局层承担；m3e 需要背景与整页一起做退出动画。
                    if (m3e) {
                        com.netmusiclite.ui.components.NcmBackground(
                            route = e.destination.route,
                            prepareResources = false,
                        )
                    }
                    IpodCoverFlowScreen(nav, ipodSource, onExitRequest = onExitRequest)
                }
            }
            if (m3e) {
                IpodExitHost(
                    // 收尾兜底：动画期间若 Wear 原生右滑已经先 pop 掉本页，当前栈顶就不是 e 了，
                    // 这里不再二次 pop（避免误 pop 掉下层页面）。
                    onExitFinished = {
                        if (nav.currentBackStackEntry?.id == e.id) nav.popBackStack()
                    },
                ) { onExitRequest -> ipodPage(onExitRequest) }
            } else {
                // 经典主题：保持原 backable 的右滑返回语义（跟手缩放 + NavHost popExit 收场）
                SwipeBackBox(onBack = { nav.popBackStack() }) { ipodPage(null) }
            }
        }
        themeComposable(m3e, Routes.SLEEP_TIMER, backgroundInContent = true, content = backable {
            com.netmusiclite.ui.screens.player.SleepTimerScreen(nav)
        })

        themeComposable(m3e, 
            Routes.ALBUM_DETAIL,
            arguments = listOf(navArgument("albumId") { type = NavType.LongType }),
            backgroundInContent = true, content = backable { e -> AlbumScreen(nav, e.arguments?.getLong("albumId") ?: 0L) },
        )

        themeComposable(m3e, 
            Routes.PLAYLIST_DETAIL,
            arguments = listOf(navArgument("playlistId") { type = NavType.LongType }),
            backgroundInContent = true, content = backable(onSwipeLeft = {
                nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Slide))
            }) { e -> PlaylistDetailScreen(nav, e.arguments?.getLong("playlistId") ?: 0L) },
        )

        themeComposable(m3e, 
            Routes.ARTIST_DETAIL,
            arguments = listOf(navArgument("artistId") { type = NavType.LongType }),
            // 2026-10-01 晚（用户要求）：去掉「歌曲列表左划切到专辑 Tab」的手势 ——
            //   改回普通 backable，左划不再有动作（右划返回保留）。
            //   艺人页 Tab 切换同时去掉过渡动画，所以也不再需要向下透传手势状态。
            backgroundInContent = true, content = backable { e ->
                ArtistScreen(nav, e.arguments?.getLong("artistId") ?: 0L)
            },
        )

        themeComposable(m3e, 
            Routes.ARTIST_FANS,
            arguments = listOf(navArgument("artistId") { type = NavType.LongType }),
            backgroundInContent = true, content = backable { e ->
                com.netmusiclite.ui.screens.artist.ArtistFansScreen(
                    nav, e.arguments?.getLong("artistId") ?: 0L,
                )
            },
        )

        themeComposable(m3e, 
            Routes.FAN_POST,
            backgroundInContent = true, content = backable { _ ->
                com.netmusiclite.ui.screens.artist.PostDetailScreen(nav)
            },
        )

        themeComposable(m3e, 
            Routes.COMMENTS,
            arguments = listOf(navArgument("songId") { type = NavType.LongType }),
            backgroundInContent = true, content = backable { e ->
                com.netmusiclite.ui.screens.comments.CommentsScreen(
                    nav, e.arguments?.getLong("songId") ?: 0L,
                )
            },
        )

        themeComposable(m3e, Routes.PLAYER, backgroundInContent = true) { e ->
            // ★ 2026-10-01：栈底右滑走「前进导航到主页」后，这个 entry 没被 pop、仍留在栈里，
            //   手势旗标会一直挂着 —— 之后它自己被 pop 时会被误判成手势返回（页面原地淡出、
            //   不滑动）。所以每次重新入场要清一次。
            //   ⚠ 必须放 LaunchedEffect，不能写在组合期：组合期写导航状态会在**离场转场进行中**
            //   被反复执行（播放页每 500ms 因 positionMs 重组一次），正是「右滑退出后页面残留
            //   闪一下再消失」的一帧级怪象来源。离场转场期间旧内容的副作用不会重跑，旗标安全。
            androidx.compose.runtime.LaunchedEffect(e) { TransitionCoordinator.clearGesturePop(e) }
            PlayerScreen(nav)
        }
        themeComposable(m3e, Routes.LYRICS, backgroundInContent = true) { LyricsScreen(nav) }
        themeComposable(m3e, Routes.MORE, backgroundInContent = true, content = backable { MoreScreen(nav) })
        themeComposable(m3e, Routes.QUEUE, backgroundInContent = true, content = backable { QueueScreen(nav) })
        themeComposable(m3e, 
            Routes.SONG_BAIKE,
            arguments = listOf(navArgument("songId") { type = NavType.LongType }),
            backgroundInContent = true, content = backable { e -> SongBaikeScreen(nav, e.arguments?.getLong("songId") ?: 0L) },
        )
        themeComposable(m3e, Routes.PLAY_HISTORY, backgroundInContent = true, content = backable { HistoryScreen(nav) })
        themeComposable(m3e, Routes.DOWNLOAD_MGR, backgroundInContent = true, content = backable { DownloadManagerScreen(nav) })
        themeComposable(m3e, Routes.QUALITY, backgroundInContent = true, content = backable { QualityScreen(nav) })
        themeComposable(m3e, Routes.GESTURE, backgroundInContent = true, content = backable { GestureScreen() })
        themeComposable(m3e, 
            Routes.USER_DETAIL,
            arguments = listOf(navArgument("userId") { type = NavType.LongType }),
            backgroundInContent = true, content = backable { e -> UserProfileScreen(nav, e.arguments?.getLong("userId") ?: 0L) },
        )
        themeComposable(m3e, Routes.COLLECT, backgroundInContent = true, content = backable { CollectScreen(nav) })

        themeComposable(m3e, Routes.LISTEN_TOGETHER, backgroundInContent = true, content = backable { ListenTogetherScreen(nav) })
        themeComposable(m3e, Routes.LISTEN_ROOM, backgroundInContent = true) { ListenRoomScreen(nav) }

        themeComposable(m3e, 
            Routes.FRIEND_CHAT,
            arguments = listOf(navArgument("friendId") { type = NavType.LongType }),
            backgroundInContent = true, content = backable { e -> ChatScreen(nav, e.arguments?.getLong("friendId") ?: 0L) },
        )
        themeComposable(m3e, Routes.MESSAGES, backgroundInContent = true, content = backable { MessagesScreen(nav) })
        themeComposable(m3e, Routes.SHARE_PICK, backgroundInContent = true, content = backable { SharePickScreen(nav) })

        themeComposable(m3e, Routes.ACCOUNT, backgroundInContent = true, content = backable { AccountScreen(nav) })
        themeComposable(m3e, Routes.CACHE_CLEAN, backgroundInContent = true, content = backable { CacheCleanScreen(nav) })
        themeComposable(m3e, Routes.SWITCH_ACCOUNT, backgroundInContent = true, content = backable { SwitchAccountScreen(nav) })
        themeComposable(m3e, Routes.ADD_ACCOUNT_QR, backgroundInContent = true, content = backable { AddAccountQrScreen(nav) })

        themeComposable(m3e, Routes.THEME, backgroundInContent = true, content = backable {
            com.netmusiclite.ui.screens.settings.ThemeSettingsScreen(nav)
        })
        // 内置图片管理器（2026-10-03 用户口径：自定义壁纸按钮进入，自动扫描系统图片）
        themeComposable(m3e, Routes.GALLERY, backgroundInContent = true, content = backable { GalleryScreen(nav) })
        // ★ 2026-10-03 表盘形状（设置页入口）：复用向导第一页的选择界面，选完「完成」即返回
        themeComposable(m3e, Routes.SHAPE, backgroundInContent = true, content = backable {
            com.netmusiclite.ui.screens.guide.GuideShapeScreen(
                onNext = { nav.popBackStack() },
                confirmText = "完成",
            )
        })
        // ★ 2026-10-04 表冠适配（设置页入口，用户口径「设置里加一栏表冠适配」）：
        //   音量页 / 列表页方向各自「正 / 反」，点选即落盘并立即生效（见 data/RotaryTuning）
        themeComposable(m3e, Routes.ROTARY, backgroundInContent = true, content = backable {
            com.netmusiclite.ui.screens.settings.RotaryTuningScreen()
        })
        themeComposable(m3e, Routes.DISCLAIMER, backgroundInContent = true, content = backable { DisclaimerScreen(nav) })
        // ★ 2026-10-07 用户口径「设置免责声明上方添加特别鸣谢栏目」（入口见 SettingsScreens）：
        //   独立静态页，页内内容见 SupportScreens.kt 的 ThanksScreen
        themeComposable(m3e, Routes.THANKS, backgroundInContent = true, content = backable { ThanksScreen(nav) })
        themeComposable(m3e, Routes.ABOUT, backgroundInContent = true, content = backable { AboutScreen(nav) })
        // ★ 2026-10-03 用户口径「设置里添加加入官方QQ群/捐赠作者选项」：
        //   两个独立静态页，页内内容见 SupportScreens.kt
        themeComposable(m3e, Routes.QQ_GROUP, backgroundInContent = true, content = backable { QqGroupScreen(nav) })
        themeComposable(m3e, Routes.DONATE, backgroundInContent = true, content = backable { DonateScreen(nav) })
        }

        } // end if (contentReady)：重量级导航图组合结束（这段耗时被启动浮层遮住）
        // ★ 应用内音量条（2026-10-03）：导航图之上、启动浮层之下；
        //   仅非 ColorOS 机型显示（触发点按 DeviceCompat.useInAppVolume 分流，见 InAppVolume 头注）。
        com.netmusiclite.ui.components.VolumeHost()
        // 启动加载浮层：盖在目标页上方，目标页首帧落屏后才淡出（见上面的启动编排）
        androidx.compose.animation.AnimatedVisibility(
            visible = showSplash,
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(220)),
            modifier = Modifier.fillMaxSize(),
        ) {
            // ★ 2026-10-07 用户口径「进入软件完成取色后要渐变出现而不是突然闪出」：
            //   外观模式 = 封面模糊时，底图落库会让全 App 明暗翻转（CoverBackdrop.autoLight）
            //   —— 预取命中时这正落在浮层还挂着的窗口里，浮层底色/文字会硬切一次。
            //   这两个动画把翻转溶解成 320ms 的颜色过渡（浮层内容极轻，逐帧重组无成本）。
            val splashBg by androidx.compose.animation.animateColorAsState(
                com.netmusiclite.ui.theme.Bg,
                androidx.compose.animation.core.tween(320), label = "splashBg",
            )
            val splashInk by androidx.compose.animation.animateColorAsState(
                com.netmusiclite.ui.theme.TextPrimary,
                androidx.compose.animation.core.tween(320), label = "splashInk",
            )
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxSize()
                    // ★ 99.5% 不透明（2026-09-30 冷启动专项 R5）：完全不透明时，合成器会
                    //   跳过浮层下方内容的绘制 —— 于是「揭开那一帧」才第一次真正画播放页
                    //   （实测揭开瞬间仍有一帧 228ms：着色器首次编译 + 背景图纹理上传）。
                    //   留 0.5% 透光（肉眼与纯色底完全无异）强制下方内容在浮层期间就已绘制，
                    //   首绘成本落在被遮挡的窗口里，揭开变成纯 alpha 动画。
                    .background(splashBg.copy(alpha = 0.995f)),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                // ★ 2026-10-02 用户口径「进入加载时图标同步外部应用图标」：
                //   启动浮层图标从 Play 符号换成与桌面启动器一致的自适应图标复刻
                //   （白底圆 + ic_launcher_foreground 前景矢量 —— mipmap 的
                //   AdaptiveIconDrawable 无法被 painterResource 加载，与关于页同一套拼法）。
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFFFFF))
                        .border(1.dp, com.netmusiclite.ui.theme.TextTertiary.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(com.netmusiclite.R.drawable.ic_launcher_foreground),
                        contentDescription = "NetMusicLite",
                        modifier = Modifier.size(58.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.Text(
                    "NetMusicLite",
                    fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = splashInk,
                )
            }
        }
    }
}

/**
 * 系统级返回（华为 Watch 3 右滑：系统吃掉触摸后直接派发的 back）的导航语义，
 * 逐条镜像应用内右滑手势（分发链路见 SystemBackRouter）：
 * · 主页：不消费 → 落到主页的退出动画（收小+淡出 → moveTaskToBack）；
 * · 普通页：有上一页就应用内回退（与右滑返回同语义；系统 back 无跟手位移，走标准 pop 转场）；
 * · 栈底播放页：= 内部右滑的「前进导航到主页（装成返回）」，整段动作由 PlayerScreen 注册
 *   （含 fader 收场动画）；未注册时的兜底只切转场、无收场动画；
 * · 其余栈底页（登录/同意/向导）：消费但不动作 —— 屏蔽系统「右滑直接退出」。
 * 返回值：是否已消费系统 back。
 */
private fun onSystemBack(nav: NavHostController): Boolean {
    val route = nav.currentBackStackEntry?.destination?.route ?: return true
    if (route == Routes.HOME) return false
    if (nav.popBackStack()) return true
    if (route == Routes.PLAYER) {
        val rootExit = SystemBackRouter.playerRootExit
        if (rootExit != null) {
            rootExit()
        } else {
            TransitionCoordinator.withGesturePop(220) {
                nav.navigateWithMotion(Routes.HOME, NavMotionSpec(NavMotionKind.Slide))
            }
        }
        return true
    }
    return true
}

/** 供外部（如设置页登出后）拿到 nav 的辅助扩展 */
fun NavHostController.backToHome() {
    navigateWithMotion(Routes.HOME, NavMotionSpec(NavMotionKind.Directional)) {
        popUpTo(0) { inclusive = true }
    }
}

/**
 * 播放器组页面（2026-09-26）：自带不透明环境色底（ambientSurfaceColor），不透出全局背景层。
 * 只有播放页/歌词页/更多页三个 —— 其余页面 screenBg()=透明，直接透出全局自定义背景图。
 */
private val AMBIENT_SURFACE_ROUTES = setOf(Routes.PLAYER, Routes.LYRICS, Routes.MORE)

/**
 * 页面转场统一参数（2026-09-30 从参考录屏 SVID_20260930_221208_1.mp4 逐帧反解）
 *
 * 参考转场是标准传送带：旧页整幅右移滑出、新页从左侧并排推入，两页硬拼接、全程零淡化。
 * 分界线把画面切成两半 ⇒ **全帧平均亮度就是归一化位移的直接读数**
 * （旧页《新建歌单6》195.90 / 新页《我的》238.10，跨度 42.2），不需要模板匹配，
 * 对 H.264 的块效应也极稳（转场②④ 两组样本逐位一致，证明该动效可重复、非 spring 抖动）。
 *
 * 采样点（归一化时长 → 归一化位移）：
 *     10% →  5.4%      20% → 20.5%      30% → 41.9%
 *     40% → 61.4%      50% → 76.0%      60% → 86.1%
 *     70% → 92.9%      80% → 97.1%      90% → 99.3%
 * 峰值斜率 2.19 @ t=0.27；起点斜率 0.16（软起步）；终点斜率 0.00（长尾滑行）。
 * 拟合 RMSE 0.0078 —— 优于最好的内置缓动 FastOutSlowIn(0.0300) 近 4 倍，
 * 其余内置（easeOutCubic 0.157 / Linear 0.165 / M3Emphasized 0.147）差一个数量级。
 * 解析验证：x'(s) = 0.9 - 1.86s + 3.09s²，判别式 < 0 恒正；y'(s) 在 [0,1] 上恒正 ⇒ 零过冲。
 *
 * 时长 388ms 直接来自测量（22.5 帧 @ 58.04fps），取整 380ms，
 * 进入/退出/返回/手势返回全部共用 —— 这是「连贯」的关键：以前 200/160/210 三种时长混用。
 *
 * ★ 2026-10-01 按**来源**分成两套动效（见 [NavMotionKind.Slide]）：
 *   - 滑动来源（左划 / 下滑 / 栈底右滑）＝ 传送带对开，**只认 NavGlide / NAV_MS**，
 *     用户明确要求「滑动切换不变」，这条路径一个字节都不要动。
 *   - 点击 / 自动来源 ＝ 轻舟「缩放」转场，走下面 QZ_* 那一组常量。
 */
internal const val NAV_MS = 380
internal val NavGlide = CubicBezierEasing(0.30f, 0.04f, 0.29f, 1.00f)

/* ==========================================================================================
 * 轻舟「缩放」转场规格（2026-10-01 真机录屏逐帧反解，非调参）
 * ------------------------------------------------------------------------------------------
 * 取证方式：手表 OWW261（466×466 圆屏 / API 30）录「更多 → 设置」与「设置 → 更多」两组
 * 入退栈转场。screenrecord 是**变帧率**容器，抽帧必须 `-fps_mode passthrough` 保真实时间轴，
 * 时间基准一律取 ffprobe 的 pts_time；缩放用「末帧为模板 + AFFINE 缩放 + 归一化互相关取峰」
 * 反解，并用「行块左右边缘跨度」几何量测交叉校验（两法同结论）。
 *
 * 轻舟 UI 里可直接读到的值：过渡动画 =「缩放」，动画时长 = 350ms（可调，拉到最大实测 494ms）。
 * 两次录制（T=350ms / T=494ms）归一化后曲线重合，证明曲线形状与时长无关。
 *
 * 实测结论：
 *   ① 缩放原点 = 屏幕正中。几何量测每一帧的放大中心恒为 (0.496, 0.496)，无一帧漂移。
 *      （2026-10-02 用户要求「所有缩放动画打开中心由使用场景点击位置而定」→ 本实现
 *        有意不照搬这一条：原点改为**手指触点**（见 TransitionCoordinator.noteTap /
 *        navigateWithMotion），无法取到新鲜触点时才回退这里的屏幕正中。曲线/时长/淡入
 *        仍全部按下方实测值。）
 *   ② 入页缩放 0.50 → 1.00，**带回弹**：峰值 1.038 @ 进度 62%，随后单调落回 1.00。
 *      （「Flutter 官方缩放是 0.85→1.00 单调不过冲」在这里对不上：官方
 *        ZoomPageTransitionsBuilder 的 _scaleCurveSequence 峰值恰为 1.000，不产生过冲；
 *        实测 1.038 且模板匹配 / 几何量测两法交叉确认，所以那套参数不能用。）
 *      拟合（T=350ms，20 点，RMS 0.0013）：begin 0.4994，Cubic(0.170, 1.012, 0.369, 1.215)
 *      拟合（T=494ms，26 点，RMS 0.0036）：begin 0.5193，Cubic(0.097, 0.966, 0.306, 1.232)
 *      取两组均值 → [QZ_FROM] / [QzZoom]。
 *   ③ 入页内容淡入：α 0.30(t=43ms) → 0.63 → 0.80 → 0.97 → 1.00，约在总时长 32% 处到位。
 *      （线性回归 frame ≈ α·模板 + β 给出 **β≈0**，说明新页是叠在**纯黑**上淡入，
 *        不是与旧页交叉溶合。）→ [QZ_FADE_IN_MS]
 *   ④ 退页**完全不缩放**：旧页模板在环形区域拟合恒为 1.000（NCC 0.996~0.999，四帧无漂移），
 *      只是变暗：亮度因子 0.951 → 0.777 → 0.718 → 0.643 → 0.562，外推总时长 ~46% 处归零。
 *      → 退页只 fadeOut，不加 scale。（淡出的时长/缓动 2026-10-02 因透底残留 bug 有微调，
 *        见 [QZ_FADE_OUT_MS] —— 轻舟这里能线性收尾是因为新页不透明能盖住旧页，本实现不能。）
 *   ⑤ 返回 = 入场曲线的**时间反演**：退栈 494ms 里前 38% 仍是 1.000→1.038→1.000 的回弹，
 *      之后才收向 0.50；被揭示的下层页同样不缩放，只在后半程淡入。
 *      （2026-10-02：实现不照搬时间反演 —— 那前 38% 的反向放大读起来就是「犹豫」。
 *        改用同形镜像 [QzZoom]：1.000 → 0.462@62% → 0.500，快起步 + 收尾回弹。）
 *
 * 与 NetMusicLite 上一版「点击缩放」的差异（有意为之，为对齐轻舟）：
 *   - 起点 0.84 → 0.50，曲线由零过冲的 NavGlide 换成带回弹的 QzZoom；
 *   - 缩放原点：轻舟录屏虽是固定正中，但 2026-10-02 起**改用手指触点**（用户要求
 *     「打开中心由使用场景点击位置而定」）；旧版取的是被点控件中心，粒度不够细；
 *   - 退页由「反向放大到 108% + 延迟淡出」改成**只淡出、不缩放**（返回缩放只加在
 *     popExit 的缩向触点分支上）。
 *   - 退页消隐曲线：轻舟的退页「只能」线性收尾，因为新页有不透明体垫纯黑（③ 的 β≈0）；
 *     本 App 播放器组页面透底（壁纸在全局层），线性淡出会让两页内容同屏混合成大块残留
 *     （2026-10-02 用户 bug：点击更多时播放页画面残留）—— 改为 [QzFadeOut] 二次加速消隐
 *     + 130ms，混合峰值 0.49→0.28，且返回方向 popEnter/popExit 两窗口对齐 [220,350]。
 * ========================================================================================== */

/*
 * ★ 2026-10-16 说明：本组常量曾短暂改成「系统打开应用」的规格（入页 0.20→1.00 / 180ms /
 *   单调无过冲 / 退页缩到 0.85），**已按用户要求回退** —— 用户口径「回弹效果要保留原来的，
 *   我要你改的是他（参照）的连贯的过渡动画」，即曲线/时长/回弹全部保持轻舟那套实测值不动。
 *   回退时的参照录屏分析结论（含 30fps 精度边界）已移入项目记忆，不再留在代码里误导。
 */

/** 轻舟默认「动画时长」350ms（其设置页可直接读到；可调量程实测约 200–500ms）。 */
internal const val QZ_MS = 350

/** 入页起始缩放。两次实测拟合 0.4994 / 0.5193，取 0.50。 */
private const val QZ_FROM = 0.50f

/** 入页缩放缓动：峰值 1.077（× 0.5 行程 ⇒ scale 峰值 1.038），峰值位于进度 0.62。 */
internal val QzZoom = CubicBezierEasing(0.13f, 0.99f, 0.34f, 1.22f)

/*
 * 退场缩放缓动（2026-10-02）：**直接复用 [QzZoom]**（同形镜像，收尾回弹）。
 * 两个旧方案均已删除：QzZoomBack（时间反演，前段反向放大 →「犹豫」）、
 * QzExitMono（严格单调 →「退出没有回弹」，用户反馈缺少与入场对称的回弹）。
 */

/** 新页内容淡入时长：实测在总时长 ~32% 处到 1.00。 */
private const val QZ_FADE_IN_MS = 112

/**
 * 退页淡出时长：轻舟实测在总时长 ~46% 处归零（线性）。
 *
 * ★ 2026-10-02 用户 bug「点击更多时播放页画面残留」→ 真机录屏取证：本 App 的播放器组
 * 页面（PLAYER/LYRICS/MORE）在「自定义壁纸 + 保留取色关」下是**透底**的（页面自身
 * 全透明，壁纸垫在全局背景层），新页没有能盖住旧页的不透明体内 —— 旧页 α 0.60 时
 * 新页内容已 α 0.82（t≈64ms，帧里能直接看到播放页封面/播放键从「更多」的毛玻璃圆
 * 后面透出来），线性 160ms 淡出的「同屏混合乘积」峰值 0.49，肉眼就是残留。
 * 修复：收到 130ms + [QzFadeOut] 加速消隐 —— 峰值 0.28@35ms，t≥64ms 后 ≤0.21，
 * 112ms 新页到位时旧页只剩 0.019（≈不可见）。
 * （不改成 0ms 瞬切：残留是没了，但旧页会「啪」地消失，丢掉缩放转场的连贯。）
 * 同一常量此后也服务 popEnter 的被揭示页淡入与 popExit 的退页淡出 —— 两窗口同为 [220,350]。
 */
private const val QZ_FADE_OUT_MS = 130

/** 淡入用的减速缓动（≈ easeOutQuad），与实测 α 序列吻合。 */
private val QzFadeIn = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)

/**
 * 退页消隐缓动：与 [QzFadeIn] 是同一条曲线（easeOutQuad）—— 作为 fadeOut 的 easing
 * 传入时 α = 1 - E(u) = (1-u)²，即**二次加速消隐**（u=25% 时 α 只剩 0.56，线性是 0.75），
 * 把旧页的存在感压进起手 2~3 帧，实测数据见 [QZ_FADE_OUT_MS]。
 */
private val QzFadeOut = CubicBezierEasing(0.25f, 0.46f, 0.45f, 0.94f)

private fun <T> navSpec(ms: Int = NAV_MS) = tween<T>(ms, easing = NavGlide)

/**
 * 搜索页路由内容（★ 2026-10-23 搜索域，项3 修复的配套改动）。
 *
 * 为什么单独抽出来：搜索页原来直接用 `backable(onSwipeLeft = …)`，而 m3e 主题的
 * `m3eSwipeLeft` 手势挂在**页面根**、在 Main pass 主动 `consume()` 横向事件 —— 它会和
 * 输入框内的横向拖动（选光标 / 滑动输入）抢事件，严重时把正在打字的人直接带去播放页，
 * 也就是用户反馈里的「还没输完就自动跳转」。
 *
 * 这里给搜索页加一层守卫：**输入框有焦点期间（正在输入）左滑不生效**，输入结束
 * （键盘收起 / 点别处）后恢复「左滑进播放页」。除守卫外结构与 `backable` 完全一致：
 * onBack = popBackStack；m3e 的目的地背景由本页自己画（backgroundInContent）。
 *
 * 守卫状态只在本页 composable 内 `remember`，不跨上下文传 State；`SearchScreen` 的
 * SearchField 在失焦 / 退出组合时会补发 false，避免残留 true 挡住下次左滑。
 */
@Composable
private fun SearchRoute(
    nav: NavHostController,
    entry: androidx.navigation.NavBackStackEntry,
    initialQuery: String?,
    m3e: Boolean,
) {
    var inputFocused by remember { mutableStateOf(false) }
    SwipeBackBox(
        onBack = { nav.popBackStack() },
        onSwipeLeft = {
            if (!inputFocused) {
                nav.navigateWithMotion(Routes.PLAYER, NavMotionSpec(NavMotionKind.Slide))
            }
        },
        nativeNavigation = m3e,
    ) {
        Box(Modifier.fillMaxSize()) {
            if (m3e) {
                com.netmusiclite.ui.components.NcmBackground(
                    route = entry.destination.route, prepareResources = false,
                )
            }
            SearchScreen(
                nav,
                initialQuery = initialQuery,
                onInputFocusChange = { inputFocused = it },
            )
        }
    }
}

