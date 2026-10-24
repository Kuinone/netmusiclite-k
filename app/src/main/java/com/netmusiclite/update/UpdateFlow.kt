package com.netmusiclite.update

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 更新流程状态机（2026-10-02 用户口径「下载完成自动拉起安装器 / 进入应用自动申请权限」）。
 *
 * 为什么从 UI 里搬出来：下载 → 校验 → 拉起安装器是跨页面、跨进程存活的动作 ——
 *  · 用户离开设置页、退出应用，系统 DownloadManager 仍在下（断点记录在 [UpdatePrefs]）；
 *  · 下载完成时若缺「允许来自此来源」权限，要跳系统设置授权，回来还得自动续装；
 *  · 「立即安装」要能复用已下好的包，而不是重下 19MB。
 * 所以状态机挂进程级单例，UI（播放页胶囊 / 关于页入口）只读 [state]。
 *
 * 换源重试：UpdateChecker.downloadSources 的源链逐个尝试，失败自动切下一个；
 * 全部失败才进入 [State.Failed]。
 */
object UpdateFlow {

    sealed class State {
        object Idle : State()
        data class Downloading(val percent: Int) : State()
        data class Installing(val versionName: String) : State()
        data class Failed(val reason: String) : State()
        /** 包已下载待装但缺「允许来自此来源」权限：UI 引导去系统设置页授权，授权回来自动续装。 */
        data class NeedPermission(val versionName: String) : State()
    }

    val state = MutableStateFlow<State>(State.Idle)
    /** 本地已有校验可装的包（弹窗把「下载更新」换成「立即安装」，避免重下）。 */
    val localPackageReady = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** 用户点「下载更新 / 重试」：从源链头开始（取消旧任务、清旧断点）。 */
    fun start(ctx: Context, info: UpdateInfo) {
        val app = ctx.applicationContext
        job?.cancel()
        job = scope.launch {
            UpdatePrefs.load(app)?.let { UpdateChecker.cancel(app, it.downloadId) }
            val sources = UpdateChecker.downloadSources(info)
            UpdatePrefs.save(app, info, sources, 0, -1L)
            localPackageReady.value = false
            runDownload(app, info, sources, 0, -1L)
        }
    }

    /** 用户点「立即安装」：复用已下载并校验过的包，直接拉起安装器。 */
    fun installLocal(ctx: Context) {
        val app = ctx.applicationContext
        if (job?.isActive == true) return
        val pending = UpdatePrefs.load(app) ?: return
        job = scope.launch { installAndFinish(app, pending.info) }
    }

    /** 应用回前台（MainActivity.onResume）：清尾已完成安装的残留 / 重挂下载 / 授权回来续装。 */
    fun onAppResume(ctx: Context) {
        val app = ctx.applicationContext
        if (job?.isActive == true) return
        job = scope.launch { resume(app) }
    }

    /** 用户点「去开启权限」：落「等授权」断点并跳系统设置；授权回来 onAppResume 自动续装。 */
    fun gotoInstallPermission(ctx: Context) {
        val app = ctx.applicationContext
        UpdatePrefs.setAwaitPermission(app, true)
        UpdateChecker.openInstallPermissionSettings(app)
    }

    private suspend fun resume(app: Context) {
        val pending = UpdatePrefs.load(app)
        if (pending == null) {
            localPackageReady.value = false
            if (state.value !is State.Idle) state.value = State.Idle
            return
        }
        // 已装上（或设备上已是更新版本）→ 清尾：删更新包 + 清断点
        if (UpdateChecker.installedVersionCode(app) >= pending.info.versionCode) {
            UpdateChecker.downloadFile(app).delete()
            UpdatePrefs.clear(app)
            localPackageReady.value = false
            state.value = State.Idle
            return
        }
        val sources = pending.sources.ifEmpty { UpdateChecker.downloadSources(pending.info) }
        val canInstall = UpdateChecker.canInstall(app)
        if (!localPackageReady.value) {
            localPackageReady.value = canInstall && UpdateChecker.verifyLocal(app, pending.info)
        }
        if (!canInstall) {
            // 等授权：标记断点；启动引导会跳系统设置页，授权回来 onAppResume 再次进来续装
            UpdatePrefs.setAwaitPermission(app, true)
            state.value = State.NeedPermission(pending.info.versionName)
            return
        }
        if (pending.awaitPermission) {
            // 刚授权回来 → 自动续装：下载好了就装，没下完接着下，任务丢了从当前源重开
            UpdatePrefs.setAwaitPermission(app, false)
            when (val st = UpdateChecker.query(app, pending.downloadId)) {
                UpdateChecker.DownloadState.Done -> installAndFinish(app, pending.info)
                is UpdateChecker.DownloadState.Running ->
                    runDownload(app, pending.info, sources, pending.index, pending.downloadId)
                is UpdateChecker.DownloadState.Failed ->
                    runDownload(app, pending.info, sources, pending.index, -1L)
            }
            return
        }
        when (UpdateChecker.query(app, pending.downloadId)) {
            // 下载在后台已经完成（进程被杀/后台下完）→ 自动补装（用户需求：下载完成自动拉起安装器）。
            // 已尝试过拉起（用户从安装器退回）就不再自动弹，交给胶囊/弹窗的「立即安装」。
            UpdateChecker.DownloadState.Done ->
                if (pending.installAttempted) state.value = State.Idle
                else installAndFinish(app, pending.info)
            // 普通启动：只把还在系统队列里跑的下载任务重挂上（完成仍会自动拉起安装器）
            is UpdateChecker.DownloadState.Running ->
                runDownload(app, pending.info, sources, pending.index, pending.downloadId)
            // 失败残留：不自动重试，胶囊/弹窗会引导重试
            is UpdateChecker.DownloadState.Failed -> state.value = State.Idle
        }
    }

    /** 下载主循环：轮询进度；当前源失败自动切下一个源；全部失败才报错。 */
    private suspend fun runDownload(
        app: Context,
        info: UpdateInfo,
        sources: List<String>,
        startIndex: Int,
        existingId: Long,
    ) {
        var index = startIndex.coerceIn(0, (sources.size - 1).coerceAtLeast(0))
        var id = existingId
        while (true) {
            if (id <= 0L) {
                val url = sources.getOrNull(index)
                if (url == null) {
                    fail("更新包下载失败，请稍后重试")
                    return
                }
                state.value = State.Downloading(0)
                id = UpdateChecker.enqueueDownload(app, info, url)
                if (id <= 0L) {
                    fail("无法开始下载")
                    return
                }
                UpdatePrefs.save(app, info, sources, index, id)
            }
            when (val st = UpdateChecker.query(app, id)) {
                is UpdateChecker.DownloadState.Running -> state.value = State.Downloading(st.percent)
                UpdateChecker.DownloadState.Done -> {
                    localPackageReady.value = true
                    installAndFinish(app, info)
                    return
                }
                is UpdateChecker.DownloadState.Failed -> {
                    if (index + 1 >= sources.size) {
                        localPackageReady.value = false
                        fail("下载失败：${st.reason}")
                        return
                    }
                    // 换下一个源：取消旧任务、清半截文件，下一轮循环重新入队
                    UpdateChecker.cancel(app, id)
                    UpdateChecker.downloadFile(app).delete()
                    index += 1
                    id = -1L
                }
            }
            delay(1500)
        }
    }

    private fun installAndFinish(app: Context, info: UpdateInfo) {
        state.value = State.Installing(info.versionName)
        val result = runCatching { UpdateChecker.install(app, info) }.getOrNull()
        when (result) {
            UpdateChecker.InstallResult.STARTED -> {
                // 断点保留到下次启动清尾（确认新版本装上后删包）；此刻不能删，安装器还在读
                UpdatePrefs.setInstallAttempted(app, true)
                state.value = State.Installing(info.versionName)
                toast(app, "校验通过，已拉起安装器")
            }
            UpdateChecker.InstallResult.NO_PERMISSION -> {
                UpdatePrefs.setAwaitPermission(app, true)
                state.value = State.NeedPermission(info.versionName)
                toast(app, "需要「允许来自此来源」权限，正在打开设置")
                UpdateChecker.openInstallPermissionSettings(app)
            }
            UpdateChecker.InstallResult.HASH_MISMATCH -> {
                localPackageReady.value = false
                UpdatePrefs.clear(app)
                state.value = State.Failed("更新包校验失败，已删除，请重新下载")
            }
            else -> {
                state.value = State.Failed("无法拉起安装器，可重试或改用 adb 更新")
            }
        }
    }

    private fun fail(reason: String) {
        state.value = State.Failed(reason)
    }

    private fun toast(ctx: Context, msg: String) {
        Handler(Looper.getMainLooper()).post {
            runCatching { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
        }
    }
}

/**
 * 断点持久化（2026-10-02）：跨进程记住「正在做的这次更新」——
 * 版本信息、源链、当前源下标、系统下载任务 id、是否在等安装权限。
 * 应用被杀/重启后 [UpdateFlow.onAppResume] 据此重挂、续装、清尾。
 */
internal object UpdatePrefs {

    private const val FILE = "ncm_update"
    private const val KEY_VER_CODE = "ver_code"
    private const val KEY_VER_NAME = "ver_name"
    private const val KEY_APK = "apk"
    private const val KEY_MIRROR = "mirror"
    private const val KEY_SHA = "sha256"
    private const val KEY_LOG = "changelog"
    private const val KEY_SOURCES = "sources"
    private const val KEY_INDEX = "src_index"
    private const val KEY_DL_ID = "dl_id"
    private const val KEY_AWAIT = "await_permission"
    private const val KEY_ATTEMPTED = "install_attempted"

    data class Pending(
        val info: UpdateInfo,
        val sources: List<String>,
        val index: Int,
        val downloadId: Long,
        val awaitPermission: Boolean,
        /** 下载完成后是否已尝试拉起过安装器（避免用户从安装器退回后每次启动又自动弹）。 */
        val installAttempted: Boolean,
    )

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun save(ctx: Context, info: UpdateInfo, sources: List<String>, index: Int, downloadId: Long) {
        sp(ctx).edit()
            .putInt(KEY_VER_CODE, info.versionCode)
            .putString(KEY_VER_NAME, info.versionName)
            .putString(KEY_APK, info.apk)
            .putString(KEY_MIRROR, info.mirror)
            .putString(KEY_SHA, info.sha256)
            .putString(KEY_LOG, info.changelog)
            .putString(KEY_SOURCES, sources.joinToString("\n"))
            .putInt(KEY_INDEX, index)
            .putLong(KEY_DL_ID, downloadId)
            .putBoolean(KEY_ATTEMPTED, false)
            .apply()
    }

    fun setAwaitPermission(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_AWAIT, value).apply()
    }

    fun setInstallAttempted(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_ATTEMPTED, value).apply()
    }

    fun load(ctx: Context): Pending? {
        val s = sp(ctx)
        val code = s.getInt(KEY_VER_CODE, 0)
        if (code <= 0) return null
        return Pending(
            info = UpdateInfo(
                versionCode = code,
                versionName = s.getString(KEY_VER_NAME, "").orEmpty(),
                apk = s.getString(KEY_APK, "").orEmpty(),
                mirror = s.getString(KEY_MIRROR, null)?.takeUnless { it.isEmpty() },
                sha256 = s.getString(KEY_SHA, "").orEmpty(),
                changelog = s.getString(KEY_LOG, "").orEmpty(),
            ),
            sources = s.getString(KEY_SOURCES, null)
                ?.split("\n")?.filter { it.isNotEmpty() }.orEmpty(),
            index = s.getInt(KEY_INDEX, 0),
            downloadId = s.getLong(KEY_DL_ID, -1L),
            awaitPermission = s.getBoolean(KEY_AWAIT, false),
            installAttempted = s.getBoolean(KEY_ATTEMPTED, false),
        )
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().clear().apply()
    }
}

/**
 * 更新提示胶囊的「同一版本只弹一次 + 点过即消失」记录（2026-10-03 用户口径）。
 *
 * 独立存储（ncm_update_prompt）而不搭 [UpdatePrefs]：那套断点是「这一次更新任务」的生命周期，
 * 装完/校验失败会整体 clear；提示记录要活得更久 —— 同一版本用户看过或点过就永不再弹，
 * 只有新的 versionCode 才有重新弹一次的机会。
 *
 * sessionShown 是进程内正在展示的版本：持久化只管「下次启动不再弹」，
 * 本次进程不因刚记下 prompted 就当场隐藏（否则胶囊闪一帧就没了）。
 */
internal object UpdatePrompt {
    private const val FILE = "ncm_update_prompt"
    private const val KEY_PROMPTED_CODE = "prompted_code"
    private const val KEY_CLICKED_CODE = "clicked_code"

    private var sessionShownCode = 0

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun promptedCode(ctx: Context): Int = sp(ctx).getInt(KEY_PROMPTED_CODE, 0)
    private fun clickedCode(ctx: Context): Int = sp(ctx).getInt(KEY_CLICKED_CODE, 0)

    /** 胶囊该不该显示（纯读，无副作用）。 */
    fun visible(ctx: Context, code: Int): Boolean = when {
        code <= 0 -> false
        clickedCode(ctx) == code -> false
        sessionShownCode == code -> true
        else -> promptedCode(ctx) != code
    }

    /** 胶囊首次真正上屏：记「已弹过」（只挡下次启动，本次保持显示）。 */
    fun markShown(ctx: Context, code: Int) {
        if (code <= 0 || sessionShownCode == code) return
        if (promptedCode(ctx) == code) return // 上次启动已弹过：本进程不再显示
        sessionShownCode = code
        sp(ctx).edit().putInt(KEY_PROMPTED_CODE, code).apply()
    }

    /** 点击胶囊：该版本永不再弹（持久化），当场隐藏。 */
    fun markClicked(ctx: Context, code: Int) {
        if (code <= 0) return
        if (sessionShownCode == code) sessionShownCode = 0
        sp(ctx).edit().putInt(KEY_CLICKED_CODE, code).apply()
    }
}
