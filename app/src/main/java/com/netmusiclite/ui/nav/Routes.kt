package com.netmusiclite.ui.nav

object Routes {
    const val LOGIN_QR = "login/qr"

    /**
     * 使用向导第一页（2026-10-17 用户口径「进入时向导第一个界面改为选择主题」）：
     * 默认主题 / Material 主题二选一，确认后进入对应主题的手势图册。
     */
    const val GUIDE_THEME = "guide/theme"

    /**
     * 切换主题后的「只看该主题指南」独立页（2026-10-17）：
     * 老用户第一次切到某主题时强制观看；看完直接返回设置页，不进 QQ/捐赠/问卷。
     */
    const val GUIDE_QUICK = "guide/quick"

    /** 使用向导（首次登录后弹一次：手势说明页 → 官方QQ群二维码页 → 捐赠作者页） */
    const val GUIDE = "guide"
    const val GUIDE_QQ = "guide/qq"
    const val GUIDE_DONATE = "guide/donate"
    /**
     * 表盘形状选择（2026-10-03 用户口径「首次启动的时候向导第一个页面改成方表圆表选择」）：
     * 整个引导流程的**第一屏**。没选过形状时，首次启动直接落到这里（在同意页/登录页之前
     * —— 形状决定圆形裁切与弧边内缩，越早定下来越好）；选完点「下一步」再按登录态分流。
     */
    const val GUIDE_SHAPE = "guide/shape"

    /**
     * 高级版公告问卷（2026-10-04 用户口径「首次启动弹问卷：打算搞六元高级付费版本、
     * 原有功能仍然可用、想要的功能加群联系群主」）。
     *
     * 两条触发路径共用本页，只认同一个 done 标记（[com.netmusiclite.data.SurveyStore]）：
     *  · 老用户（已登录、升级上来）→ AppNav 的 startDestination 直接落在本页；
     *  · 新用户（没登录过）→ 走完向导、在**末尾**（捐赠页之后）出现。
     */
    const val SURVEY = "survey"

    const val HOME = "home"
    const val SEARCH = "search"
    /** 带预填关键词的搜索（听歌识曲结果直达） */
    const val SEARCH_Q = "search?q={q}"
    fun searchQ(q: String): String {
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        return "search?q=$enc"
    }
    const val DAILY_RECOMMEND = "daily_recommend"
    const val LIKED_SONGS = "liked_songs"
    const val MY_PLAYLISTS = "my_playlists"
    const val LOCAL_MUSIC = "local_music"
    /** 导入本地音乐（2026-10-03 用户口径）：扫描设备音乐 → 单选/多选导入，仅在本页来源入口 */
    const val LOCAL_IMPORT = "local_music/import"
    const val RECOGNIZE = "recognize"
    const val PRIVATE_ROAM = "private_roam"
    const val FRIENDS = "friends"
    const val SETTINGS = "settings"
    const val PLAY_HISTORY = "play_history"
    const val DOWNLOAD_MGR = "download_manager"
    const val QUALITY = "settings/quality"
    /** 实验性手势操控：双指捏一捏的上/下一首、暂停规则与灵敏度 */
    const val GESTURE = "settings/gesture"

    const val USER_DETAIL = "user/{userId}"
    fun userPage(id: Long) = "user/$id"
    const val RECORD = "record"
    const val SLEEP_TIMER = "sleep_timer"

    const val ALBUM_DETAIL = "album/{albumId}"
    const val PLAYLIST_DETAIL = "playlist/{playlistId}"
    fun albumDetail(id: Long) = "album/$id"
    fun playlistDetail(id: Long) = "playlist/$id"

    const val ARTIST_DETAIL = "artist/{artistId}"

    /** 艺人乐迷团（帖子流：可点赞、可进帖子看评论/发评论） */
    const val ARTIST_FANS = "artist_fans/{artistId}"
    fun artistDetail(id: Long) = "artist/$id"

    /** 乐迷团帖子详情（帖子 + 评论 + 发评论）；帖子本体走 FanPostBus 内存交接，不带路由参数 */
    const val FAN_POST = "fan_post"

    const val COMMENTS = "comments/{songId}"
    fun comments(songId: Long) = "comments/$songId"

    const val PLAYER = "player"
    const val LYRICS = "lyrics"
    const val MORE = "more"
    const val QUEUE = "queue"
    const val SONG_BAIKE = "baike/{songId}"
    fun songBaikePage(songId: Long) = "baike/$songId"
    const val COLLECT = "collect"

    const val LISTEN_TOGETHER = "listen_together"
    const val LISTEN_ROOM = "listen_room"

    const val FRIEND_CHAT = "friend_chat/{friendId}"
    fun friendChat(id: Long) = "friend_chat/$id"

    /** 私信会话列表（最近联系人） */
    const val MESSAGES = "messages"

    /** 分享当前歌曲 → 好友选择 */
    const val SHARE_PICK = "share_pick"

    /** 主题设置：明暗模式 / 自动取色 / 种子色 / 背景与玻璃材质（2026-10-01 合并了原「背景与玻璃」页） */
    const val THEME = "settings/theme"
    /** 内置图片管理器（2026-10-03 用户口径）：自定义壁纸入口，自动扫描系统图片，双列弧形选择 */
    const val GALLERY = "settings/theme/gallery"
    /**
     * 表盘形状（设置页入口，2026-10-03）：复用向导第一页的选择界面（左右方/圆两选项），
     * 供用户选错后随时改正 —— 形状影响全局圆形裁切与弧边内缩，不给出口会很难受。
     */
    const val SHAPE = "settings/shape"
    /**
     * 表冠适配（设置入口，2026-10-04 用户口径）：音量页 / 列表页各自「正 / 反」二选一。
     * 各厂商 ROM 上报表冠的通道与符号都不一致（华为原始符号与 Wear 标准相反、OPPO 声明成
     * SOURCE_MOUSE），归一化无法穷举厂商，交给用户一调最省事。见 data/RotaryTuning。
     */
    const val ROTARY = "settings/rotary"
    const val DISCLAIMER = "settings/disclaimer"
    /** 特别鸣谢（设置入口，2026-10-07 用户口径「在免责声明上方添加特别鸣谢栏目」） */
    const val THANKS = "settings/thanks"
    const val ABOUT = "settings/about"
    /** 加入官方QQ群（设置入口；2026-10-03 用户口径） */
    const val QQ_GROUP = "settings/qq_group"
    /** 捐赠作者（设置入口；2026-10-03 用户口径） */
    const val DONATE = "settings/donate"
    const val CONSENT = "consent"
    const val ACCOUNT = "settings/account"
    const val CACHE_CLEAN = "settings/cache"
    const val SWITCH_ACCOUNT = "settings/switch_account"
    const val ADD_ACCOUNT_QR = "settings/add_account_qr"
}
