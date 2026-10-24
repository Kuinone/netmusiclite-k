package com.netmusiclite.data

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

enum class PlayMode(val label: String) {
    LOOP("循环播放"), SHUFFLE("随机播放"), SINGLE("单曲循环"), HEART("心动模式");
    fun next(): PlayMode = entries[(ordinal + 1) % entries.size]
}

/**
 * 全局播放器：Media3 ExoPlayer 封装 + 应用自有队列 + 红心镜像。
 * 播放地址实时拉取不缓存；已下载歌曲直接播本地文件。
 */
object PlayerEngine {
    private lateinit var appCtx: Context
    private var am: AudioManager? = null
    private var pm: android.os.PowerManager? = null
    private var focusReq: AudioFocusRequest? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * ★ queue/index 必须是 Compose 状态（2026-09-12 修「一起听歌单浮层加载不出来」）：
     * 旧版是普通 var，一起听房间的队列由远端同步/轮询在后台整体替换，
     * 界面读到的永远是打开浮层那一刻的快照 —— 歌单看着像「加载不出来」，
     * 必须关掉重开才能看到新歌。改成 mutableStateOf 后队列一变就重组。
     */
    var queue by mutableStateOf<List<Song>>(emptyList())
    var index by mutableStateOf(-1)
    var current by mutableStateOf<Song?>(null)
    var isPlaying by mutableStateOf(false)

    private var _positionMs by mutableStateOf(0L)

    /**
     * 播放进度（Compose 状态，播放中每 500ms 一次）。
     *
     * ★ 2026-10-18 耗电修复：写入时同步刷新 [positionSnapshot]。
     *   有些场景只需要「此刻的进度是多少」这一个**数值**（歌词页决定初始滚动到哪一行），
     *   在组合期读本属性会把读取登记进快照依赖 —— 于是每 500ms 整页重组一次。
     *   这类场景改读 [positionSnapshot]。
     */
    var positionMs: Long
        get() = _positionMs
        set(value) {
            _positionMs = value
            positionSnapshot = value
        }

    /**
     * 与 [positionMs] 实时同步的**普通字段**（不参与快照系统）：
     * 组合期读它不会登记重组依赖，适合「只取一次当前进度」的场景。
     * 精度与 positionMs 相同（同一个赋值动作里更新），不会落后。
     */
    @Volatile
    var positionSnapshot: Long = 0L
        private set

    var durationMs by mutableStateOf(0L)
    var loading by mutableStateOf(false)
    var errorMsg by mutableStateOf<String?>(null)
    var playMode by mutableStateOf(PlayMode.LOOP)
    private var pendingSeekMs = 0L
    var likedIds by mutableStateOf<Set<Long>>(emptySet())

    /**
     * 最近一次切歌方向：+1 = 下一首（含自动切歌/耳机按键/失败跳灰歌），-1 = 上一首。
     * 播放页封面滑动动画据此镜像方向（2026-10-02：此前下一首/上一首滑动方向相同）。
     * 普通 var 而非 Compose 状态：只在切歌瞬间被过渡动画读取一次，不参与重组。
     */
    var skipDirection: Int = 1
        private set

    /**
     * 本次本地切歌的上报口径（READY 时被 [ListenSession.reportSwitch] 消费）。
     *
     * 官方发送端（dex `Lyc0/a0;->m`）按动作区分命令：按下一首/上一首发 NEXT/PREVIOUS，
     * 从歌单点某首发 GOTO；`triggerType` 手动为 MANUAL、播完/失败自动推进为 AUTO。
     * 旧版一律发 GOTO 且 `formerSongId` 取的是切换**后**的新歌 —— 官方手机收到后只弹
     * 「对方切歌了」提示、不执行切歌（真机现象）。这里按官方口径逐项记录。
     */
    private var switchReportType: String? = null
    private var switchFormerId = 0L
    private var switchAuto = false

    /** 跨类切歌路径（一起听续流）标注命令类型；常规入口由 next/prev/playAt 自行标记 */
    internal fun markSwitchType(type: String) {
        switchReportType = type
    }

    private var player: ExoPlayer? = null
    private val loudnessAudioProcessor = LoudnessAudioProcessor()
    private var sleepGain = 1f
    private var activeMediaId: String? = null
    private var activeReadySeq = -1
    private var activeCompletionSeq = -1
    private var activeErrorSeq = -1
    private var initialized = false
    /** 上次播放状态是否已恢复（[preloadState] 幂等标记，与 initialized 分开：恢复要早于建播放器） */
    private var stateLoaded = false
    private var pauseAfterPrepare = false
    /**
     * 本次载歌 READY 之后**是否应该自动起播**（2026-10-23 修「暂停后自动开始播放」的结构性收紧）。
     *
     * ★ 旧实现把它隐含成「只要没被暂停要求，就一定 play」——[onPlayerReady] 的 else 分支无条件
     *   `exo.play()`。这意味着**任何一次 load** 都等于一次起播，包括那些用户并没有要求出声的
     *   载歌（一起听远端静默跟播、系统恢复队列等）。一旦这类 load 恰好发生在暂停态，
     *   就会表现为「暂停后过一会儿自己响」。
     *   现在改成显式意图：只有「用户点歌/切歌/下一首/显式播放」这类载歌才置 true；
     *   远端同步等场景按远端状态决定。默认 true，保证既有调用点行为不变（只收紧显式传 false 的路径）。
     */
    private var playAfterPrepare = true
    /**
     * 播放会话意图（前台服务保活依据）：起播置位，暂停/加载失败清除。
     * 切歌的取址+prepare 间隙保持置位 —— 后台自动切歌时前台服务全程不撤
     * （playWhenReady 在不同 media3 版本的 stop() 后语义不一，不能作保活依据）。
     */
    private var fgWanted = false

    /**
     * 最近一次暂停的挂钟时刻（elapsedRealtime）。
     * 诊断用：出现「暂停后放着突然自己响」时，logcat 的 PlayDiag 会带上距上次暂停的间隔，
     * 一眼看出是即时翻转（<1s，多为外部指令/手势重复投递）还是延迟起播（数秒~数十秒，
     * 多为载歌 READY 后才到）。
     */
    private var lastPauseRt = 0L

    // ---------- 屏幕状态闸门（2026-10-23 修「暂停/播放中熄屏，亮屏有概率自动开始播放」）----------
    /**
     * 当前是否处于「屏幕点亮且在用户手里」。
     *
     * ★ 为什么要在播放引擎里自己记屏幕状态：
     *   本 App 同时挂着**两条系统媒体通道**（平台 `android.media.session.MediaSession` +
     *   Media3 `MusicSessionService`），ColorOS Watch 的表盘音乐卡片 / 下拉媒体控制器在
     *   **亮屏瞬间**会重新 bind 会话，并可能直接下发一条 PLAY（ROM 的「恢复上次播放」行为，
     *   不经用户）。这条指令与「用户按了播放键」在我们代码里长得**完全一样**
     *   （都落到 [playAction]），引擎内部若不区分来源，就会把 ROM 的自动恢复当成用户意图 ——
     *   现象正是「暂停后息屏，再亮屏自己响」。
     *   因此这里自己盯屏幕状态：亮屏时刻、是否可交互，作为下面 [shouldBlockSystemResume]
     *   的判断依据。用持久字段而不是临时读 PowerManager：需要「刚亮屏多久」这个时间差。
     */
    private var screenOnRt = 0L

    /** 进程被拉起的时刻（elapsedRealtime）。用于「冷启动窗口内拒绝系统发起的起播」 */
    private var processStartRt = 0L

    /**
     * 亮屏后这段时间内，**非 UI 来源**的起播请求一律丢弃。
     * 800ms：ROM 恢复播放的指令都在 bind 后立刻发出（几十~几百 ms 内），
     * 而真人从「屏幕亮起来」到「手指/表冠真正落到应用上」远不止 800ms。
     */
    private const val SCREEN_ON_GATE_MS = 800L

    /**
     * 进程启动后的这段时间内，非 UI 来源的起播一律丢弃。
     * 3s：覆盖「息屏时进程被回收 → 亮屏系统媒体控制器把服务 bind 起来 → 立刻发 PLAY」
     * 这条冷启动恢复链；3s 之内真人不可能从「应用刚起来」完成一次媒体按键操作。
     */
    private const val COLD_START_GATE_MS = 3_000L

    /**
     * 屏幕开关/解锁广播接收器。
     * ★ 只在这里**记录状态与时刻**，绝不触发任何播放动作 —— 屏幕状态变化与播放状态完全解耦，
     *   这是本次修复的核心约束（设备屏幕状态变化不得导致自动开始播放）。
     * 注册在 [warmUp]（与耳机拔出接收器同一处，只注册一次）。
     */
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, i: android.content.Intent?) {
            when (i?.action) {
                android.content.Intent.ACTION_SCREEN_ON -> screenOnRt = android.os.SystemClock.elapsedRealtime()
                // 熄屏：窗口关闭（当前状态一律以 PowerManager 的 isInteractive 为准）
                android.content.Intent.ACTION_SCREEN_OFF -> screenOnRt = 0L
                android.content.Intent.ACTION_USER_PRESENT ->
                    // 解锁/抬手亮屏：部分 ROM 只发这一个，不补 SCREEN_ON。别覆盖更早的 SCREEN_ON 时刻
                    if (screenOnRt == 0L) screenOnRt = android.os.SystemClock.elapsedRealtime()
                else -> {}
            }
            android.util.Log.i(
                "PlayDiag",
                "screen action=${i?.action} interactive=${screenInteractive()} " +
                    "playing=$isPlaying pw=${player?.playWhenReady} loading=$loading",
            )
        }
    }

    private val exoPlayerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!isActiveMedia()) return
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    loading = true
                    isPlaying = false
                    updateMediaSession()
                }
                Player.STATE_READY -> onPlayerReady(loadSeq)
                Player.STATE_ENDED -> handleCompletion(loadSeq)
            }
        }

        override fun onIsPlayingChanged(isPlayingNow: Boolean) {
            if (!isActiveMedia()) return
            isPlaying = isPlayingNow
            updateMediaSession()
        }

        override fun onPlayerError(error: PlaybackException) {
            val seq = loadSeq
            if (!isActiveMedia() || activeErrorSeq == seq) return
            failLoad(seq, "播放失败(${error.errorCode})")
        }
    }
    private var ticker: Job? = null

    /**
     * ★ 2026-10-10 耗电优化：ticker 唤醒信号。
     *
     * 暂停/空闲时 ticker 走 10s 长延时（见 [warmUp] 里的 ticker 循环），
     * 起播时由 [syncForeground] 发一发信号把它立刻打断、恢复 500ms 高频刷新 ——
     * 既不牺牲「点播放立刻跟手」，又消掉待机期每 0.5s 一次的 CPU 唤醒。
     */
    private val tickerWake =
        kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var loadSeq = 0
    private var mediaSession: MediaSession? = null
    /** ★ 会话元数据缓存键（2026-10-10）：[updateMediaSession] 被播放/暂停/进度等 20+ 处高频调用，
     *  只在「歌或时长」变化时重建 MediaMetadata —— 否则每次都重新解析封面地址白耗电。 */
    private var sessionMetaKey: String? = null
    // 收听时长统计（对齐网易云）：ticker 按真实挂钟差累计"正在播放"的时长，
    // 结算点 = 切歌 / 播完（含单曲循环每遍）/ 长暂停兜底，整段会话汇总上报一条。
    // ★ 网易按 play 日志条数计听歌次数：一条日志 = 一次完整收听会话，绝不分片上报
    //   （旧版每 30s 分片上报导致听一次涨十几次）。同理不能"暂停即结算"——
    //   那会让同一首分两段听会计 2 次；也不能置永久标记只报一次——续听的时长会被丢弃。
    // ★ 暂停不结算：未上报秒数随 player_state.json 落盘，进程被杀后重启恢复同首续接，
    //   切歌时一次报出 —— 数据不丢、日志条数不增。
    private var listenAccSec = 0f
    private var lastTickRt = 0L   // 上次 ticker 的 elapsedRealtime：算真实增量用
    private var lastActiveRt = 0L // 上次处于播放态的 elapsedRealtime：长暂停判定用
    /** 上次进度落盘的 elapsedRealtime（2026-10-18：由「第 n 拍」改成挂钟驱动 ——
     *  熄屏降频后拍长会变，按拍数算会把落盘间隔一起拉长到 2 分钟） */
    private var lastPersistRt = 0L
    /** 本会话是否还需补发 startplay（load 置位、首次结算消费）：startplay 是「最近播放」的
     *  数据源，一次会话只发一条。旧版每次结算都补发 → 最近播放时间被结算时刻反复刷新 */
    private var startPending = false

    /** 暂停超过此时长视为本次收听会话结束，由 ticker 兜底结算（挂机/遗忘场景）。
     *  30min：日常接电话/临时离开不会把一次收听拆成两次计次（旧版 10min 偏激进） */
    private const val PAUSE_FLUSH_MS = 30 * 60_000L

    /** 连续播放失败计数：自动跳灰歌用，成功起播清零，连败 3 次停（防整条流都是灰歌时空转） */
    private var autoSkipStreak = 0

    /** 把当前歌累计的真实收听秒数一次性上报给网易（一段会话只报一条）。
     *  ★ 上报失败且未切歌时把秒数并回当前会话，下个结算点随下次一并报出（时长不丢） */
    private fun flushListen() {
        val song = current ?: return
        if (song.id <= 0) { listenAccSec = 0f; return } // 本地歌曲无云端统计
        val secs = listenAccSec.toInt()
        if (secs < 1) return
        listenAccSec = 0f
        val withStart = startPending
        startPending = false
        scope.launch {
            val ok = runCatching { NcmApi.scrobble(song.id, secs, withStart) }.getOrDefault(false)
            if (ok) {
                PageCache.invalidateRecord() // 刚听完的进排行缓存失效：进页即见最新次数
            } else if (current?.id == song.id) {
                listenAccSec += secs // 还账（切了歌就不还，避免把 A 的时长记到 B 头上）
                startPending = true
            }
        }
    }
    private val noisyReceiver = object : android.content.BroadcastReceiver() {
        // 耳机拔出 / 蓝牙音频断开 → 系统发 BECOMING_NOISY，对齐系统行为自动暂停
        override fun onReceive(ctx: Context?, i: android.content.Intent?) {
            pauseAction()
        }
    }

    /**
     * 轻量初始化（onCreate 调用）：只记 context / 读偏好开关，不建 ExoPlayer。
     * 冷启动关键路径：ExoPlayer 构建要扫描解码器列表（骁龙 W5 实测几十~一百多 ms），
     * MediaSession 创建同样偏重 —— 都挪到 [warmUp]，由首帧后（启动浮层 300ms 窗口内）触发。
     */
    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        // 2026-10-23：记进程启动时刻，供 [shouldBlockSystemResume] 的冷启动窗口使用
        if (processStartRt == 0L) processStartRt = android.os.SystemClock.elapsedRealtime()
        LoudnessPrefs.init(appCtx)
        loudnessAudioProcessor.setEnabled(LoudnessPrefs.enabled)
        am = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        pm = appCtx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        stateFile = java.io.File(appCtx.filesDir, "player_state.json")
    }

    /**
     * 屏幕是否可交互（熄屏 / AOD 恒 false）。
     * 取不到 PowerManager 时按「亮屏」处理 —— 保守方向：宁可多刷，不少刷。
     */
    private fun screenInteractive(): Boolean = pm?.isInteractive ?: true

    /**
     * 只恢复「上次播放状态」（2026-09-30 冷启动专项拆出来）：
     * IO 读一个小 json → 回主线程落地队列/当前歌/进度。轻量且与首帧并行，
     * 播放页揭开时内容已经就位；建播放器/媒体会话那套重活留给 [warmUp] 错峰做。幂等。
     */
    fun preloadState() {
        if (!::appCtx.isInitialized || stateLoaded) return
        stateLoaded = true
        // 启动窗口内不做任何磁盘 I/O：恢复状态移到后台线程解析，再回主线程应用
        scope.launch(Dispatchers.IO) { restoreState() }
    }

    /** 幂等预热：建播放器/媒体会话、注册耳机拔出接收器、恢复上次播放状态、启动心跳。首帧后调用 */
    fun warmUp() {
        if (initialized) return
        initialized = true
        appCtx.registerReceiver(
            noisyReceiver,
            android.content.IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
        )
        // ★ 2026-10-23 修「暂停后熄屏，亮屏有概率自动开始播放」：
        //   注册屏幕开关/解锁广播，**只记录时刻、不驱动播放**（见 [screenReceiver] / [shouldBlockSystemResume]）。
        //   API 33+ 用 RECEIVER_NOT_EXPORTED 显式声明「系统广播、无第三方可投递」；
        //   低版本走两参重载。注册失败（极少数 ROM 限制）不影响主流程，闸门退化为冷启动窗口一种判据。
        runCatching {
            val screenFilter = android.content.IntentFilter().apply {
                addAction(android.content.Intent.ACTION_SCREEN_ON)
                addAction(android.content.Intent.ACTION_SCREEN_OFF)
                addAction(android.content.Intent.ACTION_USER_PRESENT)
            }
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                appCtx.registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appCtx.registerReceiver(screenReceiver, screenFilter)
            }
        }
        preloadState() // 兜底：极端时序下 ensurePlayer 先于 AppNav 调到这里
        // ExoPlayer 构建（内部扫解码器列表，W5 上 50-150ms）移出主线程：预热发生在启动后 300ms
        // —— 正是用户开始滑动的窗口，主线程构建会直接堵死输入。显式 setLooper(Main) 保证
        // 构建在 Default 线程也合规（listener 回调全部投递主线程）；构建完成后回主线程
        // 赋值并建 MediaSession（MediaSession 必须在主线程构建）。
        scope.launch(Dispatchers.Default) {
            val p = buildExoPlayer()
            withContext(Dispatchers.Main) {
                if (player == null) {
                    configPlayer(p)
                    player = p
                    initMediaSession()
                } else {
                    p.release() // 极端时序：ensurePlayer 已同步补建
                }
            }
        }
        ticker = scope.launch {
            while (isActive) {
                // ★ 2026-10-10 耗电优化：只在**播放态**高频（500ms —— 进度条/通知要跟手）；
                //   暂停/空闲一律拉到 10s。长暂停兜底结算的精度需求只有分钟级
                //   （PAUSE_FLUSH_MS = 30min），10s 采样绰绰有余。
                //   旧版无条件 delay(500) ⇒ App 一起来就每 0.5s 唤醒一次，待机白耗电。
                //   起播由 tickerWake 立刻打断这段长延时，不会「点播放后干等 10s」。
                if (player?.isPlaying == true) {
                    // ★ 2026-10-18 耗电：熄屏（不可交互）时降到 2s。
                    //   屏幕不亮时没有任何观察者在看这个 500ms 的进度值（进度条/歌词都在
                    //   后台组合里），纯属白唤醒；亮屏瞬间最多滞后 2s，下一拍即修正。
                    //   熄屏听歌是手表的主要使用形态，这一档收益直接落在续航上。
                    delay(if (screenInteractive()) 500L else 2_000L)
                } else {
                    // 起播信号到了就立刻返回；否则等满 10s 再走一轮。
                    // ⚠ 别用 select{onTimeout}：新版协程里 onTimeout 已 deprecated（编译报 e:）。
                    kotlinx.coroutines.withTimeoutOrNull(10_000L) { tickerWake.receive() }
                }
                val p = player
                val now = android.os.SystemClock.elapsedRealtime()
                if (p == null) { lastTickRt = now; continue }
                runCatching {
                    if (p.isPlaying) {
                        positionMs = p.currentPosition
                        val d = p.duration
                        if (d > 0L && d != C.TIME_UNSET) durationMs = d
                        // ★ 2026-10-18 耗电：5s → 30s。写入点是「整队列 JSON 序列化 +
                        //   落盘」，播一小时原本 720 次；而暂停 / 切歌 / 载歌 / 退出都会
                        //   单独落盘（pauseInternal、load、persistState 调用点），这里只
                        //   兜「播放中被直接杀进程」的粒度，30s 足够。
                        if (now - lastPersistRt >= 30_000L) {
                            lastPersistRt = now
                            persistState()
                        }
                        // 真实收听秒数：按挂钟差累计（delay(500) 有调度/休眠漂移，固定 +0.5 会少计）；
                        // 单次增量夹 30s 上限防系统时钟跳变虚增
                        listenAccSec += (now - lastTickRt).coerceIn(0L, 30_000L) / 1000f
                    } else if (listenAccSec >= 1f && now - lastActiveRt >= PAUSE_FLUSH_MS) {
                        flushListen() // 长暂停兜底结算：挂机/遗忘后不结算就只剩落盘数据
                    }
                }
                if (p.isPlaying) lastActiveRt = now
                lastTickRt = now
            }
        }
        // 红心列表同步不在预热期拉：回包重组会撞上冷启动后的滑动窗口，由 AppRoot 启动任务错峰调用
    }

    /** 确保播放器就绪：warmUp 的后台构建尚未完成（预热 300ms 内极端时序点歌）时在主线程同步补建，
     *  语义与旧版同步构建等价；稍后后台构建完成会发现 player 非空并自行 release */
    private fun ensurePlayer(): ExoPlayer {
        warmUp()
        return player ?: buildExoPlayer().also { exo ->
            configPlayer(exo)
            player = exo
        }
    }

    /** 仅构建（重活：渲染器实例化+解码器扫描，50-150ms），可在后台线程调用——已显式 setLooper(Main)，
     *  但注意：build() 之后的一切 player 方法（配置/播放控制）仍必须在主线程调用 */
    private fun buildExoPlayer(): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(appCtx) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink? = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(loudnessAudioProcessor))
                .build()
        }
        return ExoPlayer.Builder(appCtx, renderersFactory)
            .setLooper(android.os.Looper.getMainLooper())
            .build()
    }

    /** 播放器配置（轻调用，必须主线程） */
    private fun configPlayer(exo: ExoPlayer) {
        val attributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        // Keep the existing requestFocus() path; ExoPlayer must not request focus a second time.
        exo.setAudioAttributes(attributes, false)
        // 播放期持有 CPU/WiFi 锁（配 WAKE_LOCK 权限）：手表熄屏流媒体不断流
        exo.setWakeMode(C.WAKE_MODE_NETWORK)
        exo.volume = sleepGain
        exo.addListener(exoPlayerListener)
    }

    // ---------- 播放状态持久化：重进 App 显示上次退出时正在播的歌 ----------
    private lateinit var stateFile: java.io.File

    private fun songJson(s: Song) = org.json.JSONObject()
        .put("id", s.id).put("t", s.title).put("ar", s.artist).put("arid", s.artistId)
        .put("al", s.album).put("alid", s.albumId).put("dt", s.durationMs)
        .put("cv", s.coverUrl ?: "")

    private fun songFrom(o: org.json.JSONObject) = Song(
        o.optLong("id"), o.optString("t"), o.optString("ar"), o.optLong("arid"),
        o.optString("al"), o.optLong("alid"), o.optInt("dt"),
        o.optString("cv").ifEmpty { null },
    )

    private val stateExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** 序列化+写盘全部在后台单线程执行，主线程只做不可变数据的手递（ Song 不可变，安全） */
    private fun persistState() {
        val cur = current ?: return
        val q = queue
        val idx = index
        val pos = positionMs
        val mode = playMode
        val stream = streamSource
        stateExecutor.execute {
            runCatching {
                val arr = org.json.JSONArray()
                q.forEach { arr.put(songJson(it)) }
                stateFile.writeText(
                    org.json.JSONObject()
                        .put("current", songJson(cur))
                        .put("queue", arr)
                        .put("index", idx)
                        .put("position", pos)
                        .put("mode", mode.name)
                        .put("stream", stream.name)
                        // 未上报的收听秒数随存档落盘：进程被杀后重启可续接，切歌时一次报出
                        .put("listenSong", if (cur.id > 0) cur.id else 0L)
                        .put("listenSec", listenAccSec.toDouble())
                        .toString()
                )
            }
        }
    }

    /** 只恢复界面状态（队列/当前歌/进度/模式），不自动出声；按播放键时从上次进度续播 */
    private fun restoreState() {
        // 在 Dispatchers.IO 上调用：解析完成后回主线程应用 Compose 状态
        runCatching {
            if (!stateFile.exists()) return
            val j = org.json.JSONObject(stateFile.readText())
            val cur = songFrom(j.getJSONObject("current"))
            val q = ArrayList<Song>()
            j.optJSONArray("queue")?.let { arr ->
                for (i in 0 until arr.length()) runCatching { q.add(songFrom(arr.getJSONObject(i))) }
            }
            val idx = j.optInt("index", 0)
            val pos = j.optLong("position", 0L)
            val mode = runCatching { PlayMode.valueOf(j.optString("mode", "LOOP")) }
                .getOrDefault(PlayMode.LOOP)
            // 队列来源一并恢复：重启后 FM/心动流继续能自动续取，不会退化成小队列原地循环
            val stream = runCatching { StreamSource.valueOf(j.optString("stream", "NONE")) }
                .getOrDefault(StreamSource.NONE)
                // 旧版本存档没有 stream 字段：HEART 模式的队列就是心动流，补上否则退化成普通循环
                .let { s -> if (s == StreamSource.NONE && mode == PlayMode.HEART) StreamSource.HEART else s }
            // 被杀进程前的未上报收听时长：同一首歌才续接（换歌/本地歌不续）
            val lsSong = j.optLong("listenSong", 0L)
            val lsSec = j.optDouble("listenSec", 0.0).toFloat()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                queue = q
                index = idx
                current = cur
                positionMs = pos
                durationMs = cur.durationMs.toLong()
                // ★ 这里【不要】写 pendingSeekMs（2026-09-27）：它属于「本次载歌的起播进度」，
                //   只由 load(startAtMs) 赋值。恢复存档时写进去，会让上次进度一直存活到下一首
                //   歌载入 —— 重启后点「下一首」或从搜索点新歌都会继承上一首的进度，越界时被
                //   结尾 15s 缓冲夹到歌尾就是「没播完自动跳下一首」。重启后的续播由 playInternal
                //   显式把 positionMs 传给 load 完成，不依赖这里。
                playMode = mode
                if (lsSong > 0 && cur.id == lsSong) listenAccSec = lsSec
                resetStream(stream, q)
            }
        }
    }

    /**
     * 在途的红心写入（歌曲 id）。
     *
     * [refreshLikes] 应用服务端结果时，这些 id 一律以**本地意图**为准 ——
     * 启动时的拉取可能早于用户刚点的红心落库，直接覆盖会把它抹掉
     * （表现为「刚收藏完，一转眼又变灰」）。
     */
    private val pendingLike = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    /**
     * 启动 / 切号后从服务端重建红心集合。
     *
     * ★ 2026-10-10 三处加固：
     *   ① 拉取失败 / 返回空时**不覆盖**本地（旧实现无条件赋值，一次断网就把整份红心清空）；
     *   ② 拉取回来前先吃 [OfflineCache] 本地快照，避免「一进 App 红心全灭」的观感；
     *   ③ 在途写入（[pendingLike]）以本地意图为准，防止被早到的旧列表覆盖。
     */
    fun refreshLikes() {
        val uid = SessionStore.uid
        if (uid <= 0) return
        scope.launch {
            if (likedIds.isEmpty()) {
                // 红心 id 独立快照优先（likeList 成功时落盘，全歌单红心标识断网可用）；
                // 旧版依赖「我喜欢列表快照」的 id 作兜底
                runCatching { OfflineCache.loadLikedIds() }
                    .getOrNull()?.takeIf { it.isNotEmpty() }
                    ?.let { likedIds = it }
                    ?: runCatching { OfflineCache.loadLiked() }
                        .getOrNull()?.map { it.id }?.toSet()?.takeIf { it.isNotEmpty() }
                        ?.let { likedIds = it }
            }
            val fresh = NcmApi.likeList(uid)
            if (fresh.isNotEmpty()) {
                val merged = fresh.toMutableSet()
                pendingLike.forEach { id -> if (id in likedIds) merged.add(id) else merged.remove(id) }
                likedIds = merged
                OfflineCache.saveLikedIds(merged)   // 红心 id 独立落盘：断网启动时全歌单红心标识可用
            }
        }
    }

    /**
     * 收藏 / 取消收藏（乐观更新，失败回滚）。
     * 未登录直接忽略：没有会话时服务端必然拒绝，白闪一下红心反而像卡了。
     */
    fun toggleLike(song: Song) {
        if (!SessionStore.loggedIn) return
        // ★ 2026-10-17 风控加固：同一首在途时忽略重复点击。
        //   旧实现每次点击都无条件发一条写请求，快速连点会并发产生 like=true / like=false
        //   两条交错写入：服务端按「高频 like/unlike」判异常，网络乱序时最终状态还可能
        //   与 UI 相反（用户侧表现就是「收藏了又没了」）。
        if (song.id in pendingLike) return
        val target = song.id !in likedIds
        likedIds = if (target) likedIds + song.id else likedIds - song.id
        pendingLike.add(song.id)
        PageCache.invalidateLiked() // 我喜欢页缓存失效（下次进入重新拉取）
        scope.launch {
            val ok = NcmApi.likeSong(song.id, target)
            if (!ok) {
                likedIds = if (target) likedIds - song.id else likedIds + song.id
                PageCache.invalidateLiked()
            }
            pendingLike.remove(song.id)
        }
    }

    fun isLiked(id: Long) = id in likedIds

    // ---------- 队列 ----------
    /**
     * 队列来源。FM / 心动模式是流式推荐：接口每次只推一小批（FM 实测约 3 首，服务端按调用次数推进），
     * 必须边播边续取。旧版把它当完整队列，播完只能在原地循环 —— 「只有三首重复」的根因。
     */
    enum class StreamSource { NONE, HEART, FM }

    var streamSource = StreamSource.NONE
        private set

    /**
     * 续流互斥（替代旧的 streamBusy 布尔量）：预取在途时 tryLock 直接跳过；
     * 队尾推进 waitBusy=true 排队等它做完再判定。旧布尔量把「正在取」误判成
     * 「取不到」，next() 于是回退回卷重播 —— 「私人电台出现重复歌曲」的根因之一。
     */
    private val streamMutex = Mutex()
    private var streamCooldownUntil = 0L

    /** 已进过队列的歌曲 id（环形，上限 500）：推荐流去重用，服务端推回重复批次时不再入队。
     *  500 覆盖长时电台会话：环太小时旧 id 被挤出，服务端隔几百首再推回来会被当成新歌 */
    private val recentIds = ArrayDeque<Long>()
    private val recentSet = HashSet<Long>()

    private fun remember(id: Long) {
        if (!recentSet.add(id)) return
        recentIds.addLast(id)
        if (recentIds.size > 500) recentSet.remove(recentIds.removeFirst())
    }

    private fun resetStream(src: StreamSource, songs: List<Song>) {
        streamSource = src
        streamCooldownUntil = 0
        recentIds.clear()
        recentSet.clear()
        songs.forEach { remember(it.id) }
    }

    fun playQueue(songs: List<Song>, startIndex: Int, stream: StreamSource = StreamSource.NONE) {
        // 只剔除 id=0 的解析残缺项；负 id 是本地音乐，必须保留
        val clean = songs.filter { it.id != 0L }
        if (clean.isEmpty()) return
        val target = startIndex.coerceIn(0, clean.lastIndex)
        // ★ 2026-10-23 修「列表点击正在播放的那首歌会从头重播」：
        //   同队列（逐项同 id）+ 同位置 + 当前曲确实是它 ⇒ 用户只是想回到播放页，
        //   **不动播放器**：不 setMediaItem、不 seekTo(0)、不改 playWhenReady。
        //   正在播的继续播（进度连续），已暂停的保持暂停（UI 侧照常导航到播放页）。
        //   判据里带 stream 相同：心动/FM 流重新进入时要重建队列（[enterHeartMode] 走的就是
        //   playQueue），不能被这条挡掉。
        //   语义边界（题面要求）：在**另一个**歌单里点同一首歌 ⇒ 列表与当前队列不同 ⇒
        //   判定不成立 ⇒ 照常切队列重播，「同一首歌但换队列」的意图不会被误伤。
        if (stream == streamSource && isTapOnCurrentItem(target, clean, sameQueueRequired = true)) {
            android.util.Log.i(
                "PlayDiag",
                "playQueue ignored (same queue & current item) index=$target song=${clean[target].id} " +
                    "playing=$isPlaying pw=${player?.playWhenReady}",
            )
            return
        }
        skipDirection = 1 // 新队列首曲按「前进」方向入场
        queue = clean
        resetStream(stream, clean)
        playedHistory.clear() // 新播放上下文：随机历史不跨队列携带
        playAt(target, recordHistory = false)
    }

    fun playOne(song: Song) = playQueue(listOf(song), 0)

    // ---------- 队列增删（2026-10-01：搜索长按「添加到列表 / 下一首播放」、房间歌单长按删除） ----------

    /**
     * 追加一首到队尾（搜索结果「添加到列表」）。
     * 队列为空时直接起播 —— 否则点了「添加到列表」屏幕上什么都没发生，像坏了一样。
     * 已存在的同 id 不重复追加；本地音乐 id < 0 允许重复（那是不同的文件）。
     */
    fun appendToQueue(song: Song) {
        if (song.id == 0L) return
        if (queue.isEmpty() || index < 0) { playOne(song); return }
        if (song.id > 0L && queue.any { it.id == song.id }) return
        queue = queue + song
        remember(song.id)
    }

    /**
     * 插到当前歌之后，成为「下一首」（搜索结果「下一首播放」）。
     *
     * ★ 2026-10-01 崩溃修复：旧版**无条件** `add(at, song)`，而搜索结果页点歌时
     *   `playQueue(songs, i)` 已经把**整页搜索结果**放进了队列 —— 再从同一页长按
     *   「下一首播放」，插进去的就是队列里已有的同一首（同一 id 出现两次）。
     *   随后打开「列表播放」（QueueScreen）用的是 `key = { _, s -> s.id }`，
     *   LazyColumn 遇到重复 key 直接抛 IllegalArgumentException 崩溃。
     *   现在：已在队列里 → **移动到当前歌之后**（而不是复制一份），
     *   本地音乐（id < 0，不同文件可同 id）保持原「直接插入」语义。
     */
    fun playNextInQueue(song: Song) = playNextInQueue(listOf(song))

    /**
     * 批量版「下一首播放」（2026-10-10 新增，我喜欢页多选「＋」用）。
     *
     * 按传入顺序连续插入到当前歌之后：
     * `[当前歌] → 选中第 1 首 → 选中第 2 首 → … → 原来的下一首`。
     * 已在队列里的同 id 远端歌一律**搬移**而非复制（重复 id 会让 QueueScreen 的
     * LazyColumn 抛重复 key 崩溃）；本地音乐（id < 0）保持直接插入语义。
     * 队列为空 / 无当前歌时退化成把这一批直接起播。
     *
     * ⚠ 这一批插队只活在「当前播放上下文」里 —— 用户去播另一个专辑/歌单（含「我喜欢」）
     *   走的是 [playQueue]，整个队列被目标列表**整体替换**，插队歌自然一并清掉，
     *   不需要（也不应该）额外记一份「插队列表」。
     */
    fun playNextInQueue(songs: List<Song>) {
        val clean = songs.filter { it.id != 0L }
        if (clean.isEmpty()) return
        if (queue.isEmpty() || index < 0) { playQueue(clean, 0); return }
        val list = queue.toMutableList()
        var cur = index
        var insertAt = (cur + 1).coerceIn(0, list.size)
        var added = 0
        for (song in clean) {
            val existing = if (song.id > 0L) list.indexOfFirst { it.id == song.id } else -1
            if (existing == cur) continue // 正在播的那首不搬（搬走会让 index 指向别的歌）
            if (existing >= 0) {
                list.removeAt(existing)
                // 移除点落在当前歌之前 → 当前歌与插入位一起前移；
                // 落在 [当前歌, 插入位) 之间 → 只把插入位前移
                if (existing < cur) { cur -= 1; insertAt -= 1 }
                else if (existing < insertAt) { insertAt -= 1 }
            }
            val at = insertAt.coerceIn(0, list.size)
            list.add(at, song)
            insertAt = at + 1
            added++
            remember(song.id)
        }
        if (added == 0) return
        index = cur
        queue = list
        // 随机模式的预排计划按**下标**存，插队后必须重抽，否则预加载目标与实播对不上
        if (playMode == PlayMode.SHUFFLE && queue.size > 1) drawShufflePlan(2)
    }

    /**
     * 从队列移除第 [i] 首，返回是否真的移除了。
     * - 删的是**正在播**那首 → 落到同一位置（即原下一首）并续播；队列被删空则停播。
     * - 删的是前面的 → index 前移一位，当前歌不受影响、不打断播放。
     */
    fun removeFromQueue(i: Int): Boolean {
        if (i !in queue.indices) return false
        val wasCurrent = i == index
        queue = queue.toMutableList().also { it.removeAt(i) }
        when {
            queue.isEmpty() -> {
                index = -1
                current = null
                pauseAction()
            }
            wasCurrent -> {
                index = i.coerceAtMost(queue.lastIndex)
                playAt(index)
            }
            i < index -> index -= 1
        }
        if (shufflePlan.isNotEmpty()) drawShufflePlan(2)
        return true
    }

    /**
     * 在队列第 [at] 个位置插入一首（2026-10-24 用户需求：推荐段播放中加歌要插到当前歌之后）。
     *
     * 与 [removeFromQueue] 同一套 index 语义：
     * - 插入点在当前歌之前 → [index] 后移一位（当前歌不变、不打断播放）；
     * - 插入点在当前歌之后 → [index] 不变。
     */
    fun insertAt(at: Int, song: Song) {
        if (song.id <= 0L) return
        if (queue.any { it.id == song.id }) return
        val pos = at.coerceIn(0, queue.size)
        queue = queue.toMutableList().also { it.add(pos, song) }
        if (index >= 0 && pos <= index) index += 1
        if (shufflePlan.isNotEmpty()) drawShufflePlan(2)
    }

    // ---------- 随机播放历史（2026-10-03 用户要求「下一首随机、上一首返回上一首」）----------
    /**
     * 「实际播放过的歌」栈（不含当前首）：每次前进切歌（下一首/自动切歌/失败跳歌/点歌）
     * 之前，把即将被替换的当前歌压栈；随机模式的 [prev] 弹栈回到它 —— 随机序下队列里
     * 的「前一首」只是任意一首，历史才是真正的「上一首」。
     * · 回退导航自身不压栈（否则连按上一首会在两首歌之间来回跳）；
     * · 换歌单（[playQueue]）清栈：历史不跨播放上下文携带；
     * · 弹栈时条目已不在队列（删歌/队列被替换）则继续往回找，找不到才回退队列顺序。
     */
    private val playedHistory = ArrayDeque<Song>()
    private const val PLAYED_HISTORY_MAX = 100

    private fun pushPlayedHistory(song: Song) {
        if (song.id == 0L) return
        playedHistory.addLast(song)
        while (playedHistory.size > PLAYED_HISTORY_MAX) playedHistory.removeFirst()
    }

    /**
     * 用户在「播放列表 / 队列页」里点子项（public 入口，供 UI 直接调用）。
     *
     * ★ 2026-10-23 修「列表点击正在播放的那首歌会重新开始播放」：
     *   同曲 + 同队列项 + 播放器里确实载着它 ⇒ 只当「定位到当前曲」，**不动播放器**
     *   （不 setMediaItem、不 seekTo(0)、不改 playWhenReady）—— 正在播的继续播、进度不跳；
     *   已暂停的保持暂停（UI 侧照常导航到播放页，用户自己按播放键才会出声）。
     *   语义边界：内部推进（[next]/[prev]/[advanceLocal]/删歌补位）一律走私有重载，
     *   不受本判定影响 —— 单曲队列播完自动重播等既有行为原样保留。
     */
    fun playAt(i: Int) {
        if (isTapOnCurrentItem(i)) {
            android.util.Log.i(
                "PlayDiag",
                "playAt ignored (same current item) index=$i song=${current?.id} playing=$isPlaying",
            )
            return
        }
        playAt(i, recordHistory = true)
    }

    /**
     * 「点的是不是正在播的那一首」判定（[playAt] 与 [playQueue] 共用）。
     *
     * @param at    列表里的下标
     * @param songs 传入的列表（[playQueue] 用）；null = 直接用当前 [queue]（[playAt] 用）
     * @param sameQueueRequired true 时还要求传入列表与当前队列**逐项同 id**，
     *   用来区分「同一个歌单里点当前曲」（返回 true，不重播）与
     *   「在另一个歌单里点同一首歌」（返回 false，按用户意图切队列重播）。
     */
    private fun isTapOnCurrentItem(
        at: Int,
        songs: List<Song>? = null,
        sameQueueRequired: Boolean = false,
    ): Boolean {
        val list = songs ?: queue
        if (at !in list.indices) return false
        // 同队列项：下标相同、当前曲也确实是它
        if (at != index) return false
        if (current?.id != list[at].id) return false
        if (sameQueueRequired && !isSameQueueIds(list)) return false
        // 正在载入这首：别再触发一次载歌（那等于从头缓冲），交给在途的 load 走完
        if (loading) return true
        val p = player ?: return false
        // 已经播到结尾（STATE_ENDED）：用户点它就是「想再听一遍」，放行重新载入
        if (p.playbackState == Player.STATE_ENDED) return false
        // 播放器里没有载入项（载入失败/切歌间隙被清空）：必须真正载一次，不能吞掉
        if (activeMediaId == null || p.currentMediaItem == null) return false
        return true
    }

    /** 两个歌曲列表是否逐项同 id（点歌时判断「是不是同一个队列」用；O(n) 纯 Long 比较，可忽略） */
    private fun isSameQueueIds(songs: List<Song>): Boolean {
        if (songs.size != queue.size) return false
        for (k in songs.indices) if (songs[k].id != queue[k].id) return false
        return true
    }

    private fun playAt(i: Int, recordHistory: Boolean) {
        if (queue.isEmpty()) return
        // 未被 next/prev/续流显式标记的载歌一律算「从歌单点歌」（官方发 GOTO）
        if (switchReportType == null) switchReportType = "GOTO"
        // 前进切歌前记历史；目标就是当前歌（重播）时不记，回退导航传 false
        if (recordHistory) {
            val target = queue.getOrNull(i)
            current?.takeIf { it != target }?.let { pushPlayedHistory(it) }
        }
        index = i
        // 随机计划以新 index 重抽维护（切歌与预加载同源）；非随机清空
        if (playMode == PlayMode.SHUFFLE && queue.size > 1) drawShufflePlan(2)
        else if (shufflePlan.isNotEmpty()) shufflePlan.clear()
        load(queue[i])
        maybePrefetch() // 提前补流：让「下一首」几乎总是即时的，不必等网络
    }

    fun toggle() {
        val p = player
        if (p?.isPlaying == true || p?.playWhenReady == true) pauseAction() else playAction("toggle")
    }

    /**
     * 播放（蓝牙耳机播放键同路径）：恢复态 = 重新加载上次的歌并 seek 到上次进度。
     * @param from 调用来源（ui / toggle / mediaSession / media3 / notification / gesture），
     *   只用于 PlayDiag 日志定位「暂停后自己响」是哪条通道拉起来的。
     */
    fun playAction(from: String = "ui") = playInternal(userIntent = true, from = from)

    /**
     * 非用户来源的起播请求过滤（2026-10-23 修「暂停/播放中熄屏，亮屏有概率自动开始播放」）。
     *
     * ## 根因链（代码可验证部分）
     * 本 App 挂着**两条**系统媒体通道：平台 `android.media.session.MediaSession`
     * （AVRCP/表盘控件）与 Media3 `MusicSessionService`（`androidx.media3.session.MediaSessionService`，
     * 被 ColorOS Watch 的表盘音乐卡片 / 「正在播放」页扫描绑定，见该服务 KDoc）。
     * 这两条通道的 PLAY 指令最终都落到 [playAction] —— 与用户在界面上点播放键**没有区别**。
     * 于是「息屏 → 亮屏」时系统/ROM 的任何一次恢复性下发（重新 bind 会话后补发 PLAY、
     * 进程被回收后重新 bind 服务并下发 PLAY），都会被我们当成用户意图而开始播放。
     * 这类请求的三个共同特征，正好可以当判据：
     *  ① 它发生在**屏幕刚点亮**之后（几百 ms 内）；
     *  ② 它发生在**进程刚被拉起**之后（息屏杀进程 → 亮屏被系统 bind 回来 → 立刻 PLAY）；
     *  ③ 它发生在**屏幕不可交互**（熄屏/AOD）时 —— 此时真人不可能看到界面并按键。
     *
     * ## 为什么不会误伤正常播放
     * UI / 列表点击（[from] = "ui"）与播放键（"toggle"）永远放行；
     * 蓝牙耳机媒体键 / 通知栏按键 / 表冠手势走的是系统通道，但它们都要求用户在亮屏后
     * 真正操作 —— 不可能落在「亮屏 800ms 内」或「进程刚起 3s 内」的时间窗里
     * （这两个窗口是 ROM 自动恢复的典型时序，见常量 [SCREEN_ON_GATE_MS] / [COLD_START_GATE_MS]）。
     * 极端情况下用户真的在这两个窗口内按了键：本次被丢弃并留 PlayDiag 日志，再按一次即可 ——
     * 这与「暂停后自己响」相比是明显更轻的失败方向。
     *
     * @return true = 丢弃这次起播请求（不改变任何播放状态）
     */
    private fun shouldBlockSystemResume(from: String): Boolean {
        // UI / 播放键：用户就在界面上，永远放行
        if (from == "ui" || from == "toggle") return false
        val now = android.os.SystemClock.elapsedRealtime()
        val blocked = when {
            // ① 冷启动窗口：息屏被杀后，系统 bind 服务即发 PLAY 的典型时序
            processStartRt > 0L && now - processStartRt < COLD_START_GATE_MS ->
                "cold-start(${now - processStartRt}ms)"
            // ② 刚亮屏窗口：ROM 在重新 bind 会话后「恢复上次播放」的典型时序
            screenOnRt > 0L && now - screenOnRt < SCREEN_ON_GATE_MS ->
                "just-screen-on(${now - screenOnRt}ms)"
            // ③ 屏幕不可交互（熄屏/AOD）：任何非 UI 来源都不该让它出声
            !screenInteractive() -> "screen-off"
            else -> null
        } ?: return false
        android.util.Log.i(
            "PlayDiag",
            "blocked auto-play from=$from reason=$blocked playing=$isPlaying " +
                "pw=${player?.playWhenReady} loading=$loading index=$index song=${current?.id}",
        )
        return true
    }

    private fun playInternal(userIntent: Boolean, from: String = "ui") {
        // ★ 2026-10-23：先过屏幕/冷启动闸门 —— 必须在清 pauseAfterPrepare 之前，
        //   否则一次被丢弃的系统 PLAY 会把「暂停意图」清掉，下一次 load 的 READY 反而自动出声。
        if (shouldBlockSystemResume(from)) {
            isPlaying = player?.isPlaying == true
            return
        }
        // ★ 2026-10-18：用户显式要播 = 之前任何在途的暂停意图作废。
        //   没有这一行的话，pauseInternal 落下的 pauseAfterPrepare 会在下一次载歌 READY
        //   时把刚点起来的播放又按回暂停（"点了播放没反应"）。
        pauseAfterPrepare = false
        playAfterPrepare = true
        android.util.Log.i(
            "PlayDiag",
            "play from=$from user=$userIntent gapMs=${android.os.SystemClock.elapsedRealtime() - lastPauseRt} " +
                "queue=${queue.size} index=$index playing=$isPlaying loading=$loading",
        )
        val p = player
        if (p == null) {
            current?.let { load(it, startAtMs = positionMs) }
            return
        }
        if (p.currentMediaItem == null || activeMediaId == null) {
            current?.let { load(it, startAtMs = positionMs) }
            return
        }
        runCatching {
            val wasEnded = p.playbackState == Player.STATE_ENDED
            if (wasEnded) {
                activeCompletionSeq = -1
                p.seekTo(0L)
            }
            if (!p.playWhenReady || wasEnded) {
                requestFocus()
                fgWanted = true
                p.play()
                isPlaying = p.isPlaying
                // 乐迷团房：用户手动恢复 = 解除本地暂停屏蔽，并让 FLT 轮询立刻追平房间进度
                if (userIntent && ListenSession.fltMode) ListenSession.onFltUserResume()
                // ★ 2026-10-24：只有**用户操作**才上报。applyListenSync 落远端状态时也走
                //   playInternal(userIntent=false)，旧版无条件上报 → 收到对方 PLAY 后又把
                //   一条 PLAY 回灌给房间（带本机进度），两端互相刷命令、意图窗互相屏蔽，
                //   多按几次暂停/播放就彻底不同步。
                if (userIntent) reportCmd("PLAY")
                updateMediaSession()
            }
        }
    }

    /** 暂停（蓝牙耳机暂停键同路径） */
    fun pauseAction() = pauseInternal(userIntent = true)

    private fun pauseInternal(userIntent: Boolean) {
        // ★ 2026-10-18 修「暂停后放着，过一会儿自己响」：
        //   旧实现只在 p.isPlaying || p.playWhenReady 为真时才落暂停。而 load() 会先
        //   releasePlayer() → player.stop()（这两个状态同时归 false）再 setMediaItem/prepare，
        //   这个窗口里按暂停 = if 不成立 = 暂停被**静默吞掉**，且没写 pauseAfterPrepare；
        //   稍后 prepare 完成，onPlayerReady 走 else 分支 exo.play() —— 音乐自己响起来。
        //   现在暂停意图无条件固化（哪怕 player 还没建好），READY 时由 pauseAfterPrepare 消费。
        pauseAfterPrepare = true
        // 2026-10-23：暂停同时把「下次载歌是否起播」也归零 —— 两个字段一起表达同一个意图，
        // 避免 pauseAfterPrepare 被 onPlayerReady 消费后 playAfterPrepare 残留 true 再自己响。
        playAfterPrepare = false
        lastPauseRt = android.os.SystemClock.elapsedRealtime()
        isPlaying = false
        fgWanted = false
        val p = player ?: return
        runCatching {
            if (p.isPlaying || p.playWhenReady) p.pause()
            // 乐迷团房：用户手动暂停 = 记住本地意图。服务端 playingSong.playing 恒为 true
            // （成员无暂停上报接口，房间由艺人/房主控制），不加屏蔽的话 5s 轮询会把
            // 刚按下的暂停顶回播放——「暂停点第一下会自动继续播放」就是它。
            if (userIntent && ListenSession.fltMode) ListenSession.fltLocalPause = true
            // ★ 2026-10-24：与 playInternal 同口径 —— 远端同步落地造成的暂停不回灌命令。
            //   否则对方 PAUSE(位置 A) → 本机 pause(位置 B) 又上报 PAUSE(B) → 对方被拽到 B，
            //   再回灌 PAUSE(A')… 多次暂停后进度越拽越偏、播放态互相顶。
            if (userIntent) reportCmd("PAUSE")
            persistState(); updateMediaSession()
            // 暂停不结算：未报秒数随 persistState 落盘，续播并入本次会话继续累计，
            // 长暂停（≥10min）由 ticker 兜底结算，切歌/播完时统一报出
        }
    }

    /**
     * 跳转播放位置。
     *
     * @param report 是否把这次 seek 作为「用户操作」上报到一起听房间。
     *   ★ 2026-10-23：同一起听同步内部的对齐 seek（[applyListenSync] 写入远端进度、
     *   [com.netmusiclite.data.ListenSession] 的单向进度追赶）**绝不能**上报——
     *   旧版无条件 `ListenSession.reportSeek`，会把「本机为了对齐房友而做的纠偏」
     *   当成一条 PROGRESS 命令广播给全房：服务端 serverSeq 增长 → 对端 apply →
     *   对端也可能追赶上报，形成两端互发 PROGRESS 的正反馈；同时它还会刷新
     *   listenSession 里的 5s「用户意图保护窗」，把真正的远端暂停/播放命令盾掉。
     *   只有 UI 进度条 / 系统媒体面板 / 播放服务的用户拖动才传 `report = true`。
     */
    fun seekTo(ms: Long, report: Boolean = true) {
        // Clamp before the end: seeking to/past duration can leave a completed item stuck.
        val d = durationMs
        val safe = if (d > 0L) ms.coerceIn(0L, (d - 1000L).coerceAtLeast(0L)) else ms
        runCatching { player?.seekTo(safe) }
        positionMs = safe
        if (report && ListenSession.active) ListenSession.reportSeek(positionMs)
        updateMediaSession() // 系统媒体面板进度条同步（2026-09-25）
    }

    fun next() {
        if (queue.isEmpty()) return
        skipDirection = 1 // 封面滑动：新封面从右进（自动切歌/失败跳歌同路径）
        // 流式推荐队列：走到队尾先补下一批（用户等一次网络）——补不到**绝不回卷重播**：
        // 旧版回退 advanceLocal() 的 (index+1)%queue.size 会把整队已播歌曲从头重放，
        // 这就是「私人电台出现重复歌曲」的根因。
        if (streamSource != StreamSource.NONE && index >= queue.size - 1) {
            scope.launch { advanceStreamTail() }
            return
        }
        // ★ 2026-10-24 用户需求：一起听房间歌单播到末尾**不要循环** ——
        //   房主交给 ListenSession 自动取一批推荐续在队尾（续上后从下一首继续播）；
        //   成员/续推不可用时停在末尾等房主推进。两种情况都不回卷重播。
        if (ListenSession.active && index >= queue.lastIndex) {
            if (!ListenSession.requestTailExtension()) {
                android.util.Log.i("PlayDiag", "room queue end: stop (no tail extension)")
            }
            pauseAction()
            isPlaying = false
            updateMediaSession()
            return
        }
        advanceLocal()
    }

    /**
     * 流式队列走到队尾：续流成功 → 播放新歌；失败（风控/重复批次/弱网）→ 有限重试，
     * 仍拿不到新歌就停在队尾 —— 宁停不重播（重复歌曲比戛然而止更伤电台体验）。
     * ★ waitBusy=true 是关键：预取恰好「在途」时排队等它完成再判定；旧版布尔量
     *   把在途直接判失败 → 回卷重播，哪怕新歌 600ms 后就到。
     * ★ 每轮先查 index+1 < queue.size：快速连按下一首时已有人补上就只消费一格，
     *   不会把刚起播的新歌再播一遍。
     */
    private suspend fun advanceStreamTail() {
        switchReportType = "NEXT"
        repeat(3) { attempt ->
            if (index + 1 < queue.size) { playAt(index + 1); return }
            val ok = extendStream(waitBusy = true)
            // 等待网络期间队列可能被替换（一起听同步），落在好位置就正常推进
            if (index + 1 < queue.size) { playAt(index + 1); return }
            if (ok) return // 加了歌但位置已被并发推进（一起听等），不抢
            if (attempt < 2) delay(if (attempt == 0) 2_000L else 5_000L)
        }
    }

    /** 本地队列推进（普通歌单/专辑/本地音乐的正常切歌路径） */
    private fun advanceLocal() {
        val n = when {
            // 随机排除当前首：走预抽计划（与预加载共用同一随机序，随机也能命中直链缓存）
            playMode == PlayMode.SHUFFLE && queue.size > 1 -> {
                if (shufflePlan.isEmpty() || shufflePlan.any { it >= queue.size }) drawShufflePlan(2)
                val p = shufflePlan.removeFirstOrNull()
                if (p == null || p == index) (queue.indices - index).random() else p
            }
            else -> (index + 1) % queue.size
        }
        switchReportType = "NEXT"
        playAt(n)
    }

    // ---------- 下两首预加载（2026-09-25）----------
    // 随机模式预抽计划：提前抽定后续随机序，advanceLocal 切歌与预加载判next共用，
    // 随机播放的「下一首」因此可预知、可预解析
    private val shufflePlan = mutableListOf<Int>()

    private fun drawShufflePlan(count: Int) {
        shufflePlan.clear()
        if (playMode != PlayMode.SHUFFLE || queue.size <= 1) return
        val pool = (queue.indices - index).toMutableList()
        repeat(count) {
            if (pool.isEmpty()) return@repeat
            val k = pool.random()
            shufflePlan += k
            pool.remove(k)
        }
    }

    /** 与 next()/advanceLocal() 同源的「接下来 n 首」id；流式续批不可预知时返回空 */
    private fun nextSongIds(n: Int): List<Long> {
        if (queue.isEmpty()) return emptyList()
        if (streamSource != StreamSource.NONE && index >= queue.size - 1) return emptyList()
        val out = ArrayList<Long>(n)
        if (playMode == PlayMode.SHUFFLE && queue.size > 1) {
            shufflePlan.take(n).forEach { if (it in queue.indices) out += queue[it].id }
        } else {
            var i = index
            repeat(n) { i = (i + 1) % queue.size; out += queue[i].id }
        }
        return out.distinct()
    }

    // 直链预解析缓存：id -> (https 直链, 失效时刻)。load() 命中免一次 songUrl 网络往返
    private val urlCache = java.util.concurrent.ConcurrentHashMap<Long, Pair<String, Long>>()
    private var preloadSeq = 0

    private fun cachedUrl(id: Long): String? {
        val e = urlCache[id] ?: return null
        return if (android.os.SystemClock.elapsedRealtime() < e.second) e.first
        else { urlCache.remove(id); null }
    }

    /** 歌曲就绪后预解析接下来最多 2 首直链（顺序/随机同源），切歌几乎零等待 */
    private fun schedulePreload() {
        val mySeq = ++preloadSeq
        scope.launch {
            for (id in nextSongIds(2)) {
                if (mySeq != preloadSeq) return@launch // 已切歌/换队列，目标过期
                if (cachedUrl(id) != null) continue
                if (DownloadStore.localFile(appCtx, id) != null) continue // 本地已有文件，无需预解析
                runCatching {
                    val u = withContext(Dispatchers.IO) { NcmApi.songUrl(id, QualityPrefs.level) }
                    if (u != null && mySeq == preloadSeq) {
                        val https = if (u.startsWith("http://")) u.replace("http://", "https://") else u
                        urlCache[id] = https to (android.os.SystemClock.elapsedRealtime() + 10 * 60_000L)
                    }
                }
            }
        }
    }

    /** 接近队尾时静默预取下一批；处于失败冷却期则跳过（避免弱网反复空跑） */
    private fun maybePrefetch() {
        if (streamSource == StreamSource.NONE) return
        if (index < queue.size - 3) return
        if (android.os.SystemClock.elapsedRealtime() < streamCooldownUntil) return
        scope.launch { extendStream() }
    }

    /**
     * 拉下一批推荐流、按 id 去重后追加到队尾，返回是否真的加到了新歌。
     * 按来源链依次尝试：同一首种子拉回重复批次时自动换种子、换来源；全部落空才进 10 秒冷却。
     * [waitBusy]：队尾推进传 true（排队等在途续流完成再判定）；预取用默认 false（在途即跳过）。
     */
    private suspend fun extendStream(waitBusy: Boolean = false): Boolean {
        if (streamSource == StreamSource.NONE) return false
        if (waitBusy) streamMutex.lock() else if (!streamMutex.tryLock()) return false
        try {
            // 等锁期间流可能已切换/清空（换歌单、退出电台）：锁后重读来源
            val src = streamSource
            if (src == StreamSource.NONE) return false
            val seed = current?.id ?: 0L
            val tail = queue.lastOrNull()?.id ?: seed
            val fm: suspend () -> List<Song> = { NcmApi.personalFM() }
            val daily: suspend () -> List<Song> = { NcmApi.dailyRecommend() }
            val chain: List<suspend () -> List<Song>> = when (src) {
                // FM 逐批推进；批被推重时用心动接口换种子
                StreamSource.FM -> listOf(fm, { NcmApi.heartModeList(tail, count = 30) }, daily)
                StreamSource.HEART -> listOf({ NcmApi.heartModeList(seed, count = 30) }, fm, daily)
                StreamSource.NONE -> emptyList()
            }
            for (fetch in chain) {
                // distinctBy：单批内部也去重（服务端偶发同批重复），入队前再兜一层
                val fresh = runCatching { fetch() }.getOrDefault(emptyList())
                    .filter { !recentSet.contains(it.id) }
                    .distinctBy { it.id }
                if (fresh.isNotEmpty()) {
                    appendToQueue(fresh)
                    android.util.Log.i("RoamDiag", "extend $src +${fresh.size} queue=${queue.size} idx=$index")
                    return true
                }
            }
            android.util.Log.i("RoamDiag", "extend $src 无新歌 queue=${queue.size}")
            streamCooldownUntil = android.os.SystemClock.elapsedRealtime() + 10_000
            return false
        } catch (e: Exception) {
            // 兜底：续流失败绝不能把异常抛进 scope，否则会崩掉应用
            android.util.Log.i("RoamDiag", "extend ${streamSource} 异常 ${e.message}")
            return false
        } finally {
            streamMutex.unlock()
        }
    }

    /** 追加到队尾；已播历史只保留最近 60 首（够 prev/回看），避免队列与存档无限膨胀 */
    private fun appendToQueue(add: List<Song>) {
        queue = queue + add
        add.forEach { remember(it.id) }
        val cut = index - 60
        if (cut > 0 && queue.size > 140) {
            queue = queue.subList(cut, queue.size).toList()
            index -= cut
        }
    }

    fun prev() {
        if (queue.isEmpty()) return
        skipDirection = -1 // 封面滑动：新封面从左进（与下一首镜像）
        switchReportType = "PREVIOUS"
        // 随机模式（2026-10-03 用户要求）：上一首 = 实际播放过的上一首（历史回退），
        // 不是队列里的前一首 —— 随机序下后者只是任意一首。失效条目跳过，一路往回。
        if (playMode == PlayMode.SHUFFLE) {
            while (playedHistory.isNotEmpty()) {
                val song = playedHistory.removeLast()
                val at = queue.indexOfFirst { it == song || (song.id > 0 && it.id == song.id) }
                if (at >= 0 && at != index) {
                    playAt(at, recordHistory = false)
                    return
                }
            }
        }
        // 无可用历史（会话刚开始/已倒完）或非随机模式：退回原「队列前一首（环绕）」
        playAt((index - 1 + queue.size) % queue.size, recordHistory = false)
    }

    /**
     * 切换播放模式。
     *
     * @param remote true = 由一起听远端下发的模式（同步落地）。
     *   ★ 2026-10-23：
     *   1. 远端不会下发 HEART（心动模式是本地续流专属），若收到则退化为 LOOP，
     *      避免 `enterHeartMode()` 把房间队列接上个人推荐续流；
     *   2. 远端应用后**不再回灌**上报（否则两端 playMode 互相覆盖）；
     *   3. 本地切换（remote = false）在房间内会上报一次队列命令
     *      （官方 `PlaylistCommand.playMode`，见 [com.netmusiclite.data.ListenSession.onLocalPlayModeChanged]）。
     */
    fun setMode(mode: PlayMode, remote: Boolean = false) {
        val target = if (remote && mode == PlayMode.HEART) PlayMode.LOOP else mode
        playMode = target
        drawShufflePlan(2) // 模式切换重抽随机计划，保证预加载目标与实际下一首一致
        // 心动模式真实现：切入即按当前歌重建推荐流并开始播放（不是空壳档位）
        if (target == PlayMode.HEART) enterHeartMode()
        if (!remote) ListenSession.onLocalPlayModeChanged()
    }

    private var heartBusy = false

    /** 心动模式：以当前歌为种子重建队列；拿不到相似流就退 FM（同样是流式，自动续取） */
    fun enterHeartMode() {
        if (heartBusy) return // 连点模式按钮防重入
        heartBusy = true
        scope.launch {
            try {
                val seed = current?.id ?: 0L
                val heart = if (seed > 0) NcmApi.heartModeList(seed, count = 30) else emptyList()
                val songs = heart.ifEmpty { NcmApi.fmBatch(2) }
                // 等待期间用户可能又切回别的模式 → 只有仍处于心动模式才落地，否则不抢
                if (songs.isNotEmpty() && playMode == PlayMode.HEART) {
                    playQueue(songs, 0, if (heart.isNotEmpty()) StreamSource.HEART else StreamSource.FM)
                }
            } finally {
                heartBusy = false
            }
        }
    }

    /** 私人电台 / 私人漫游入口：拉一批 FM 流起播并挂续流（每批仅几首，靠续流接上），回调在主线程 */
    fun startRoam(onDone: (Boolean) -> Unit = {}) {
        scope.launch {
            // FM 被风控/为空时退每日推荐，保证起播一定有歌
            val songs = NcmApi.fmBatch(3).ifEmpty { NcmApi.dailyRecommend() }
            if (songs.isNotEmpty()) playQueue(songs, 0, StreamSource.FM)
            onDone(songs.isNotEmpty())
        }
    }

    fun volume() = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
    fun maxVolume() = am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15

    /** flags：默认 0；自绘音量条传 FLAG_PLAY_SOUND（官方音乐口径），见 InAppVolume */
    fun setVolume(v: Int, flags: Int = 0) {
        val target = v.coerceIn(0, maxVolume())
        val mgr = am
        if (mgr != null && !mgr.isVolumeFixed) {
            mgr.setStreamVolume(AudioManager.STREAM_MUSIC, target, flags)
            return
        }
        // 固定音量设备（AudioManager.isVolumeFixed）：系统流音量被 ROM 锁死，
        // setStreamVolume 不生效 —— 改调播放器自身输出增益，音量条仍然可调
        // （Android 官方《处理音频输出变化·使用固定音量设备》口径）。
        player?.volume = (target.toFloat() / maxVolume().coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    /** 无极音量回读（0..1）：固定音量设备读播放器增益，其余读系统档位 */
    fun volumeFraction(): Float {
        val mgr = am
        if (mgr != null && !mgr.isVolumeFixed) {
            return (mgr.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                maxVolume().coerceAtLeast(1)).coerceIn(0f, 1f)
        }
        return (player?.volume ?: 0f).coerceIn(0f, 1f)
    }

    /**
     * 无极音量落地（0..1）：连续值只在**最后一步**量化成整数档。
     * STREAM_MUSIC 本身是整数档，所以「无极」是界面/手势层面的连续（见 InAppVolume.fraction）。
     */
    fun setVolumeFraction(f: Float, flags: Int = 0) {
        val max = maxVolume().coerceAtLeast(1)
        setVolume((f.coerceIn(0f, 1f) * max + 0.5f).toInt(), flags)
    }

    /** Toggle normalization on the active PCM pipeline without changing queue or position. */
    fun setLoudnessEnabled(enabled: Boolean) {
        loudnessAudioProcessor.setEnabled(enabled)
    }

    // ---------- 睡眠定时专用：播放器私有音量（不动系统音量） ----------
    /** Sleep timer gain only; normalization remains independent in the PCM processor. */
    fun setPlayerVolume(f: Float) {
        sleepGain = f.coerceIn(0f, 1f)
        runCatching { player?.volume = sleepGain }
    }

    /** Restore sleep attenuation only; do not alter the processor's normalization gain. */
    fun restorePlayerVolume() {
        sleepGain = 1f
        runCatching { player?.volume = sleepGain }
    }

    // ---------- 蓝牙耳机/AVRCP 媒体键（上一首/下一首/播放/暂停）----------
    // 注册系统 MediaSession：耳机线控、表盘播放控件走 AVRCP 标准按键，
    // 框架把 KEYCODE_MEDIA_NEXT/PREVIOUS/PLAY/PAUSE 翻译成对应回调；
    // 播放状态与歌曲元数据回写给会话，蓝牙设备 UI 与系统媒体面板同步显示。

    private fun initMediaSession() {
        val ms = MediaSession(appCtx, "NetMusicLite")
        // 说明（2026-10-10）：这里**不**设 FLAG_HANDLES_MEDIA_BUTTONS /
        //   FLAG_HANDLES_TRANSPORT_CONTROLS —— 这两位自 API 26 起被框架忽略（已 deprecated，
        //   一律视为「已注册 setCallback」），minSdk 26 下写它们就是死代码。
        ms.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = playAction("mediaSession")
            override fun onPause() = pauseAction()
            override fun onSkipToNext() { next(); updateMediaSession() }
            override fun onSkipToPrevious() { prev(); updateMediaSession() }
            // 对齐系统接口（2026-09-25）：表盘播放控件/系统媒体面板的进度条拖动与停止键
            override fun onSeekTo(pos: Long) { seekTo(pos); updateMediaSession() }
            override fun onStop() = pauseAction()
        })
        // ★ 会话入口（2026-10-10）：系统媒体卡片 / OPPO 手表音乐控制器点「打开应用」时拉起本应用。
        //   不设置时系统只画控制按钮、点击无响应（OPPO 示例包也漏了这步）。
        //   与 MusicSessionService（Media3 侧）配的同名 PendingIntent 语义一致。
        runCatching {
            val pi = android.app.PendingIntent.getActivity(
                appCtx,
                0,
                android.content.Intent(appCtx, com.netmusiclite.MainActivity::class.java)
                    .addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    ),
                android.app.PendingIntent.FLAG_IMMUTABLE or
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            ms.setSessionActivity(pi)
        }
        ms.isActive = true
        mediaSession = ms
        updateMediaSession()
    }

    /** 把当前播放状态+歌曲信息回写给 MediaSession（蓝牙 UI / 系统媒体面板读取） */
    fun updateMediaSession() {
        syncForeground()
        val ms = mediaSession ?: return
        val playing = isPlaying && !loading
        val state = when {
            current == null -> PlaybackState.STATE_NONE
            loading -> PlaybackState.STATE_BUFFERING
            playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        ms.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
                )
                .setState(state, positionMs, if (playing) 1f else 0f)
                .build()
        )
        current?.let { s ->
            // ★ 只有「歌或时长」变化才重建元数据（2026-10-10）：本方法被播放/暂停/进度同步等
            //   20+ 处高频调用，每次重建都会重新解析封面地址（[coverUriFor] 内部走 songCoverModel）。
            val key = "${s.id}|$durationMs"
            if (key != sessionMetaKey) {
                sessionMetaKey = key
                val b = MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist)
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
                // ★ 封面地址（2026-10-10 OPPO 手表手势适配）：系统媒体界面的封面**只**读这个键 ——
                //   表盘音乐卡片、下拉媒体控制、OPPO 手表音乐控制器（com.heytap.wearable.musiccontroller）
                //   都靠它。不写就永远是空白占位图，系统也可能因「媒体信息不完整」而不把这条会话
                //   当作可控制的播放界面。
                coverUriFor(s)?.let {
                    b.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, it.toString())
                }
                ms.setMetadata(b.build())
            }
        }
    }

    private fun isActiveMedia(): Boolean {
        val mediaId = activeMediaId ?: return false
        return player?.currentMediaItem?.mediaId == mediaId
    }

    // ---------- 播放保活前台服务（2026-09-26 修「播放中被杀后台」） ----------

    /** 前台服务判定：有播放意图（播放中 / 缓冲中 / 切歌加载间隙）即需要保活 */
    val playbackActive: Boolean
        get() = current != null && (isPlaying || fgWanted)

    /** 通知 MediaStyle 关联的会话 token（与表盘媒体控件/耳机线控同一会话） */
    val mediaSessionToken: android.media.session.MediaSession.Token?
        get() = mediaSession?.sessionToken

    // ---------- Media3 会话服务（系统媒体界面接入点，2026-10-01 对齐 OPPO 手表音乐包） ----------

    /**
     * 把内部 ExoPlayer 交给 [MusicSessionService] 建 Media3 会话。
     *
     * ★ 只交出**引用**，不交出所有权：生命周期、监听器、解码配置全归本对象，
     *   服务侧绝不可 release（否则等于把正在播的播放器拆了）。
     * ★ 与既有平台 MediaSession 共存：两条会话读同一个 Player、写同一套业务方法，
     *   系统选哪条行为都一致，不存在抢按键。
     * ★ 系统可能在 App 尚未预热时直接 bind 服务（例如冷启动后先点系统媒体卡片），
     *   此处用传入的 ctx 补一次 init + 同步建播放器，语义等价于「冷启动后直接点歌」。
     * ★ 必须在主线程调用：ExoPlayer 用主 Looper 构建，MediaSession 也要求同线程建。
     */
    fun sessionPlayerOrNull(ctx: Context): Player? {
        if (!::appCtx.isInitialized) init(ctx)
        return runCatching { ensurePlayer() }.getOrNull()
    }

    /**
     * 系统媒体界面用的封面地址。复用界面层的统一封面解析（本地 → 已下载落盘封面 → 网络 url），
     * 保证系统卡片上的封面与 App 内显示的是同一张。
     * ★ 已下载落盘封面是**应用私有目录**里的 File，系统 UI 进程读不到，
     *   所以这里优先还原成它当初的下载来源（song.coverUrl），取不到才退回 file://。
     */
    private fun coverUriFor(song: Song): Uri? {
        val model = runCatching { com.netmusiclite.ui.components.songCoverModel(song) }.getOrNull() ?: return null
        return when (model) {
            is Uri -> model
            is java.io.File -> song.coverUrl?.takeIf { it.startsWith("http") }?.let(Uri::parse)
                ?: Uri.fromFile(model)
            is String -> model.takeIf { it.isNotEmpty() && !it.startsWith("local://") }?.let(Uri::parse)
            else -> null
        }
    }

    /**
     * 前台服务同步：挂在 updateMediaSession 的最前面（它早于 mediaSession 判空返回，
     * 冷启动 300ms 窗口内点歌的路径也能及时拉起保护）。
     * 有播放意图 → 确保 mediaPlayback 前台服务在跑（已在跑则顺带刷新通知）；
     * 暂停/空闲 → 撤掉。播放/切歌都发生在用户前台操作时，无后台启动限制；
     * 蓝牙耳机后台唤起被系统拒绝的极端时序仅损失一次保护，不影响出声。
     */
    private fun syncForeground() {
        if (!::appCtx.isInitialized) return
        if (playbackActive) {
            PlaybackService.ensureStarted(appCtx)
            // ★ 2026-10-10：打断 ticker 的暂停态长延时 —— 起播立刻恢复 500ms 跟手刷新
            tickerWake.trySend(Unit)
        } else {
            PlaybackService.stop(appCtx)
        }
    }

    private fun onPlayerReady(seq: Int) {
        val exo = player ?: return
        if (seq != loadSeq || !isActiveMedia()) return
        val song = current ?: return
        loading = false
        if (activeReadySeq == seq) {
            isPlaying = exo.isPlaying
            updateMediaSession()
            return
        }
        activeReadySeq = seq
        autoSkipStreak = 0
        schedulePreload() // 本首就绪即预解析下两首直链（顺序/随机同源）
        val duration = exo.duration
        durationMs = if (duration > 0L && duration != C.TIME_UNSET) duration else song.durationMs.toLong()
        if (pendingSeekMs > 0L) {
            val target = pendingSeekMs
            pendingSeekMs = 0L
            // 自动恢复/远端跟播的 seek 夹紧留 15s 结尾缓冲：旧 dur-1000 会把越界的
            // 恢复进度变成「载完播 1 秒 → ENDED → 立刻切下一首」的连环跳歌
            val safe = if (durationMs > 0L) target.coerceAtMost((durationMs - 15_000L).coerceAtLeast(0L)) else target
            runCatching { exo.seekTo(safe) }
            positionMs = safe
        }
        if (pauseAfterPrepare || !playAfterPrepare) {
            // 两种情况都「载好但不出声」：
            //  ① pauseAfterPrepare：远端一起听状态本身是暂停（原口径，注释见 pauseInternal）；
            //  ② !playAfterPrepare（2026-10-23 新增）：本次载歌**本来就不该起播** ——
            //     例如一起听远端静默跟播、或暂停态下被系统/队列维护触发的载歌。
            //     ★ 旧实现在这里无条件 exo.play()，等于「任何一次 load 都会自己响」，
            //       是「暂停后过一会儿自己开始播放」的结构性来源；现在按显式起播意图决定。
            //     播放器在 prepare 期间已经 exo.pause() 过（见 load），这里不会先响一下再停。
            val heldByPause = pauseAfterPrepare
            pauseAfterPrepare = false
            exo.pause()
            isPlaying = exo.isPlaying
            android.util.Log.i(
                "PlayDiag",
                "ready-held seq=$seq song=${song.id} " +
                    "reason=${if (heldByPause) "pause-request" else "no-play-intent"}",
            )
        } else {
            android.util.Log.i(
                "PlayDiag",
                "auto-play onReady seq=$seq gapMs=${android.os.SystemClock.elapsedRealtime() - lastPauseRt} song=${song.id}",
            )
            requestFocus()
            fgWanted = true
            exo.volume = sleepGain
            exo.play()
            isPlaying = exo.isPlaying
            HistoryStore.record(song) // Count only after the item is actually ready to play.
        }
        updateMediaSession()
        if (ListenSession.active && !isRemoteLoad(seq)) {
            ListenSession.reportSwitch(
                switchReportType ?: "GOTO", song.id, positionMs, switchFormerId, switchAuto,
            )
            ListenSession.syncPlaylistIfHost(queue)
        }
        switchReportType = null
        switchFormerId = 0L
        switchAuto = false
    }

    private var remoteLoadSeq = -1

    private fun isRemoteLoad(seq: Int) = remoteLoadSeq == seq

    private fun handleCompletion(seq: Int) {
        if (seq != loadSeq || !isActiveMedia() || activeCompletionSeq == seq) return
        activeCompletionSeq = seq
        loading = false
        isPlaying = false
        val exo = player
        when {
            // Sleep timer completion takes precedence over all queue modes.
            SleepTimer.consumeEndOfSong() -> {
                pauseAction()
                isPlaying = false
                updateMediaSession()
            }
            PlayMode.SINGLE == playMode -> {
                flushListen()
                current?.let { HistoryStore.record(it) }
                startPending = true
                activeCompletionSeq = -1
                fgWanted = true // 单曲循环继续出声，保活不撤
                exo?.seekTo(0L)
                exo?.play()
                isPlaying = exo?.isPlaying == true
                updateMediaSession()
            }
            ListenSession.active -> {
                if (ListenSession.isHost) {
                    switchAuto = true // 播完自动推进：官方 triggerType=AUTO
                    next()
                    // 播完一首补一首到歌单末尾（按用户收听喜好；仅房主补，见 refillRoomQueueAsync）
                    // ★ 2026-10-24：已播到队列末尾时由 next() 触发「推荐续上」，这里不再补歌
                    if (index < queue.lastIndex) ListenSession.refillRoomQueueAsync()
                } else ListenSession.deferLocalAdvance()
                updateMediaSession()
            }
            else -> {
                next()
                updateMediaSession()
            }
        }
    }

    private fun failLoad(seq: Int, message: String) {
        if (seq != loadSeq || activeErrorSeq == seq) return
        activeErrorSeq = seq
        loading = false
        errorMsg = message
        fgWanted = false // 播放失败：撤前台保护（随后自动跳歌/停下）
        runCatching { player?.pause() }
        isPlaying = false
        updateMediaSession()
        skipOrStop()
    }

    // ---------- 加载 ----------
    /**
     * fromRemote：本次 load 由一起听远端同步（applyListenSync）触发。
     * ★ 远端载歌完成后【不上报】GOTO/队列——旧版在 prepared 里无条件 reportGoto(song,0)，
     *   成员按远端状态载完歌又把「进度 0」报回房间，房内其他人全被拽回歌首，
     *   即「反复卡住开始」。只有本地操作（切歌/点歌/恢复播放）产生的载歌才上报，
     *   且上报的是真实起播进度（旧版硬编码 0，恢复播放场景会把房间拉回歌首）。
     *
     * ★ startAtMs：本次载歌的【唯一】起播进度来源。
     *   换歌（playAt → load(song)）恒为 0 = 一律从头播；同一首歌续播（playInternal）传
     *   positionMs；一起听跟播（applyListenSync）传远端进度。绝不要再从外部写 pendingSeekMs。
     *
     * ★ autoPlay（2026-10-23 新增）：本次载歌 READY 之后是否应该自动起播。
     *   默认 true —— 用户点歌/切歌/下一首/显式播放这些**既有调用点行为完全不变**。
     *   只有「载歌不等于要出声」的场景才显式传 false（一起听远端静默跟播），
     *   避免旧实现「任何一次 load 都无条件 exo.play()」把暂停态顶回播放。
     */
    private fun load(song: Song, fromRemote: Boolean = false, startAtMs: Long = 0L, autoPlay: Boolean = true) {
        // 本地选歌 = 明确的播放意图：解除乐迷团房的本地暂停屏蔽（远端触发的 load 不算）
        if (!fromRemote && ListenSession.fltMode) ListenSession.fltLocalPause = false
        if (!fromRemote) pauseAfterPrepare = false
        // 切歌上报的 formerSongId：官方是「切换前那首」（旧版 READY 时才取 current，永远等于新歌）
        if (!fromRemote) switchFormerId = current?.id?.takeIf { it != song.id } ?: 0L
        // 2026-10-23：把「是否起播」显式记下来，由 onPlayerReady 决定（见 playAfterPrepare 说明）
        playAfterPrepare = autoPlay
        flushListen()          // 上一首的收听时长先结算（整段会话汇总一条）
        listenAccSec = 0f
        startPending = true    // 新会话：首次结算时补一条 startplay（进「最近播放」）
        val seq = ++loadSeq
        remoteLoadSeq = if (fromRemote) seq else -1
        activeReadySeq = -1
        activeCompletionSeq = -1
        activeErrorSeq = -1
        // ★ 起播进度只有 startAtMs 这一个来源，每次载歌无条件覆盖（2026-09-27 修「重启后切歌
        //   继承上一首进度」与「搜索点歌没播完就跳下一首」）：旧版把它放在共享字段 pendingSeekMs
        //   里、且只由 onPlayerReady 消费清零，于是 restoreState 写入的上次进度会一直活到
        //   【下一首】歌 READY —— 新歌从老歌进度起播；被结尾 15s 缓冲夹到歌尾时更直接变成
        //   「播十几秒 → ENDED → 跳下一首」。
        pendingSeekMs = startAtMs.coerceAtLeast(0L)
        loading = true
        errorMsg = null
        current = song
        // ★ 本地播放次数不在这里记：载歌 ≠ 听过。播放失败自动跳灰歌、一起听本地暂停期间
        //   房间远端连切 N 首（pauseAfterPrepare 载入但不起播）都会走到这里——旧版在这种
        //   场景下每首都 +1，本地统计与云端严重背离。改到 onPrepared 真正起播成功才记。
        updateMediaSession()
        positionMs = 0
        durationMs = song.durationMs.toLong()
        releasePlayer()
        persistState()
        scope.launch {
            val localUri = LocalMusic.localUri(song)
            var url = localUri?.toString() ?: DownloadStore.localFile(appCtx, song.id)?.absolutePath
            if (url == null) {
                url = withContext(Dispatchers.IO) { cachedUrl(song.id) ?: NcmApi.songUrl(song.id, QualityPrefs.level) }
                if (seq != loadSeq) return@launch
                if (url == null) {
                    val message = if (NcmApi.error()?.contains("404") == true || NcmApi.error()?.contains("460") == true)
                        "该歌曲暂无版权或需要 VIP" else "获取播放地址失败"
                    failLoad(seq, message)
                    return@launch
                }
            }
            val fetchedUrl = url ?: return@launch
            val sourceUrl = if (fetchedUrl.startsWith("http://")) fetchedUrl.replace("http://", "https://") else fetchedUrl
            withContext(Dispatchers.Main) {
                if (seq != loadSeq) return@withContext
                try {
                    val exo = ensurePlayer()
                    val mediaId = "$seq:${song.id}"
                    val uri = resolveMediaUri(sourceUrl)
                    activeMediaId = mediaId
                    loudnessAudioProcessor.resetForNewTrack()
                    exo.pause()
                    // ★ 2026-10-18 耗电：按音源选唤醒级别。
                    //   WAKE_MODE_NETWORK = CPU 锁 + **WiFi 锁**。播本地文件 / 已下载歌曲
                    //   时根本不用网卡，锁着它等于让 WiFi 常开 —— 离线听歌是手表最典型的
                    //   使用形态，所以这里按 sourceUrl 的 scheme 分流：
                    //   http(s) → NETWORK（流媒体不断流，与旧行为一致）；
                    //   其余（file / content）→ LOCAL（只保 CPU，网卡可以睡）。
                    exo.setWakeMode(
                        if (sourceUrl.startsWith("http")) C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL,
                    )
                    exo.volume = sleepGain
                    // ★ 媒体元数据是系统媒体界面（ColorOS Watch 表盘音乐卡片 / 「正在播放」页）
                    //   的**唯一**数据来源 —— Media3 会话上报的标题/歌手/封面全部取自
                    //   player.currentMediaItem.mediaMetadata（2026-10-01 对齐 OPPO 接入点时补）。
                    //   旧版只 setMediaId/setUri，系统界面即空标题空封面。
                    exo.setMediaItem(
                        MediaItem.Builder()
                            .setMediaId(mediaId)
                            .setUri(uri)
                            .setMediaMetadata(
                                androidx.media3.common.MediaMetadata.Builder()
                                    .setTitle(song.title)
                                    .setArtist(song.artist)
                                    .setAlbumTitle(song.album)
                                    .apply {
                                        if (durationMs > 0L) setDurationMs(durationMs)
                                        coverUriFor(song)?.let { setArtworkUri(it) }
                                    }
                                    .setIsBrowsable(false)
                                    .setIsPlayable(true)
                                    .build(),
                            )
                            .build(),
                    )
                    exo.prepare()
                } catch (e: Exception) {
                    failLoad(seq, "无法播放: ${e.message}")
                }
            }
        }
    }

    private fun resolveMediaUri(value: String): Uri {
        val uri = Uri.parse(value)
        return when (uri.scheme?.lowercase()) {
            "content", "file", "http", "https" -> uri
            else -> Uri.fromFile(java.io.File(value))
        }
    }

    /**
     * 播放失败兜底：自动跳下一首。VIP 也可能遇到「数字专辑未购买 / 版权下架 / 无音源」的歌，
     * 不跳就会永远停在那。连败 3 次停手，防止整条推荐流都是灰歌时反复空转刷请求。
     * 一起听成员不自行跳灰歌：等房主跳歌的 GOTO 跟随（与播完切歌同一套协调），超时由
     * checkPendingAdvance 兜底推进——否则两台设备各自跳歌、GOTO 互相踩，全房横跳。
     */
    private fun skipOrStop() {
        if (autoSkipStreak >= 3) { autoSkipStreak = 0; return }
        autoSkipStreak++
        if (ListenSession.active && !ListenSession.isHost) ListenSession.deferLocalAdvance()
        else scope.launch { switchAuto = true; next() }
    }

    private fun releasePlayer() {
        activeMediaId = null
        runCatching { player?.stop() }
        runCatching { player?.clearMediaItems() }
        isPlaying = false
    }

    @Suppress("DEPRECATION")
    private fun requestFocus() {
        val mgr = am ?: return
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            if (focusReq == null) {
                focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .build()
            }
            runCatching { mgr.requestAudioFocus(focusReq!!) }
        } else {
            runCatching { mgr.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) }
        }
    }

    private fun reportCmd(type: String) {
        if (ListenSession.active) {
            ListenSession.reportCommand(type, current?.id ?: 0, positionMs)
        }
    }

    // ---------- 一起听：应用远端播放状态（轮询同步用） ----------

    /**
     * 把服务器侧的房间播放状态落到本地播放器。
     * ★ 队列更新与「切歌」解耦：别人往房间加一首歌只更新队列，**不再打断你正在听的歌**
     *   （旧版整队列逐项比对，任何人加歌都会让全房间的人重新 load 当前歌）。
     * ★ seek 带 ±1.5s 容差：毫秒级漂移不值得跳进度。
     *
     * @param enforcePlay false = 用户本地已暂停（乐迷团房本地暂停屏蔽）：队列/歌曲照常跟随，
     *   但**不**把播放器顶回播放态、也不追进度（进度条冻结，恢复时由 onFltUserResume 追平）。
     */
    fun applyListenSync(songs: List<Song>, targetId: Long, progressMs: Long, play: Boolean, shouldSeek: Boolean,
                        enforcePlay: Boolean = true) {
        if (songs.isEmpty()) return
        val idx = songs.indexOfFirst { it.id == targetId }.takeIf { it >= 0 } ?: 0
        val songChanged = current?.id != targetId
        android.util.Log.i("LTDiag",
            "apply songs=${songs.size} target=$targetId songChanged=$songChanged " +
                "cur=${current?.id} pos=$progressMs play=$play seek=$shouldSeek shielded=${!enforcePlay}")
        queue = songs
        index = idx
        resetStream(StreamSource.NONE, songs) // 一起听队列是房间快照，不接推荐续流
        if (loading && isRemoteLoad(loadSeq)) {
            // Polls can update the desired room state while the current media item is still
            // resolving its URL or buffering. Let the latest remote state win at READY.
            pauseAfterPrepare = !play || !enforcePlay
        }
        if (songChanged) {
            pauseAfterPrepare = !play || !enforcePlay
            // 远端进度经入参传入（不再写共享字段：写进去会一直活到下一首歌载入才被消费）。
            // fromRemote = 远端触发：prepared 后不上报命令/队列（防进度回拽）
            val resumeAt = if (shouldSeek && progressMs > 0) progressMs else 0L
            // 2026-10-23：远端状态是暂停/本地屏蔽时，载完**不要出声** ——
            // 把起播意图显式传下去（旧实现靠 onPlayerReady 无条件 play，再被 pauseAfterPrepare
            // 反向拦住，语义依赖两处字段的时序，一起听静默跟播时容易漏成「自己响」）。
            load(songs[idx], fromRemote = true, startAtMs = resumeAt, autoPlay = play && enforcePlay)
        } else {
            val drift = kotlin.math.abs(positionMs - progressMs)
            // 自动追进度双保险（防「最后几十秒直接跳歌」）：
            // ① durationMs=0（远端载歌元数据未到）不追——seekTo 失去夹紧依据，越界目标会直落歌尾；
            // ② 目标必须离歌尾 ≥15s——任何逼近结尾的 target 都会被 seekTo 的 dur-1000
            //    夹紧变成「拽到最后一秒 → 播完即 ENDED → 切歌」。倒退跟随（房间回拖）不受限。
            val saneTarget = durationMs > 0L && progressMs <= durationMs - 15_000L
            // ★ 2026-10-23：同步内部的对齐 seek 不上报（report = false）——这是本机追远端，
            //   不是用户操作；上报会变成一条 PROGRESS 命令广播全房并刷新意图窗（详见 seekTo 注释）。
            if (enforcePlay && shouldSeek && progressMs > 0 && drift > 1500 && saneTarget) {
                seekTo(progressMs, report = false)
            }
            if (enforcePlay && play && !isPlaying) {
                // 播放器已在歌尾（本机播完等房间切歌）：远端 PLAY 若是歌曲重开（进度回到头部）
                // 才回零重播；否则不对已完成的播放器 start()（会在歌尾空转）
                if (durationMs > 0L && positionMs >= durationMs - 1000L && progressMs > 3000L) {
                    seekTo(0L, report = false)
                }
                playInternal(userIntent = false)
            }
            if (!play && (isPlaying || player?.playWhenReady == true)) pauseInternal(userIntent = false)
        }
    }
}
