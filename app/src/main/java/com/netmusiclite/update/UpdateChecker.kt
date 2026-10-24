package com.netmusiclite.update

import android.app.DownloadManager
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Base64
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** GitHub 更新清单（WMusic-Release 仓库根目录 update.json）里的一条版本记录 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apk: String,
    val mirror: String?,
    val sha256: String,
    val changelog: String,
)

/**
 * 远程更新（2026-10-02 用户口径「GitHub 实现软件更新远程推送」）。
 *
 * 事实来源是 windzhou999/WMusic-Release 仓库根目录的 update.json，发新版 = 打 Release + 改清单。
 * 清单有三个源，全部读完再取 versionCode 最大的一份：
 *   1. jsDelivr（国内可达性最好，但对 @main 有 CDN 缓存，发新版后可能返回合法但过期的清单）；
 *   2. api.github.com contents（无 CDN 分支缓存，回源实时；未认证限 60 次/小时/IP，超限只需当作
 *      该源失败即可，不影响其它源）；
 *   3. raw.githubusercontent（曾被观察到间歇性直接断连，所以排最后）。
 *
 * ★ 2026-10-11：旧逻辑「任一源成功即止」，jsDelivr 的过期缓存一命中就 break，raw 兜底永远不触发——
 *   v1.23/v1.24 发布后实测复现：手表「检查更新」仍显示旧版最新。改为全读取最大值后，只要任一源
 *   新鲜就能发现新版。带 ?t= 时间戳参数试过无效：jsDelivr 忽略查询串，照样命中旧缓存。
 *   （运维口径：改完 main 后可用目录级 purge `https://purge.jsdelivr.net/gh/<repo>@main/` 催刷新，
 *   实测有效；只 purge 单个文件路径无效。）
 *
 * 下载交给系统 DownloadManager，落到应用外部私有目录（无需存储权限）；完成后先算 SHA-256
 * 与清单比对（防断流截断 —— 本项目发布时就真的遇过截断包），通过才拉起系统安装器。
 * 新 APK 必须与已装应用同签名（wmusic-release.jks），否则系统拒绝覆盖安装——
 * 这也是 versionCode 只能单调递增、签名一旦发布不能换的原因。
 */
object UpdateChecker {

    private val MANIFEST_SOURCES = listOf(
        "https://cdn.jsdelivr.net/gh/windzhou999/WMusic-Release@main/update.json",
        "https://api.github.com/repos/windzhou999/WMusic-Release/contents/update.json",
        "https://raw.githubusercontent.com/windzhou999/WMusic-Release/main/update.json",
    )

    /** contents API 返回 {"content": "<base64>"}，解出其中的 update.json 原文；其它源正文原样返回。 */
    private fun manifestText(url: String, body: String): String {
        if (!url.contains("api.github.com")) return body
        val b64 = JSONObject(body).optString("content").replace("\n", "")
        return String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    sealed class Outcome {
        data class Available(val info: UpdateInfo) : Outcome()
        object UpToDate : Outcome()
        data class Error(val message: String) : Outcome()
    }

    /** 有新版本时非空（启动静默检查 / 手动检查共用）—— 播放页胶囊据此显隐。 */
    val available = MutableStateFlow<UpdateInfo?>(null)

    /** 拉清单 → 与已装 versionCode 比对。所有源都读，取版本号最大的那份（防过期缓存挡住新版）。 */
    suspend fun check(ctx: Context): Outcome = withContext(Dispatchers.IO) {
        var best: UpdateInfo? = null
        var lastError: String? = null
        for (url in MANIFEST_SOURCES) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Cache-Control", "no-cache")
                    .build()
                val info = http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    parse(manifestText(url, resp.body?.string().orEmpty()))
                        ?: throw IllegalStateException("清单格式异常")
                }
                val current = best
                if (current == null || info.versionCode > current.versionCode) best = info
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        val latest = best
        when {
            latest == null -> Outcome.Error(lastError ?: "网络异常")
            isNewerThanInstalled(ctx, latest) -> Outcome.Available(latest)
            else -> Outcome.UpToDate
        }
    }

    /**
     * 是否该提示更新（2026-10-03 用户口径）：
     *  · versionCode 必须更大（升级语义，同 code 覆盖装不上）；
     *  · versionName 必须不同 —— 只动了 code 没动版本名的包不互相提示，
     *    消灭「已装 1.0.8、胶囊却提示发现新版本 v1.0.8」这种同号提示。
     */
    private fun isNewerThanInstalled(ctx: Context, remote: UpdateInfo): Boolean =
        remote.versionCode > installedVersionCode(ctx) &&
            remote.versionName != installedVersionName(ctx)

    /** 每次启动的静默检查：结果只更新 [available]，失败也静默（不打扰、无吐司）。 */
    suspend fun checkQuietly(ctx: Context) {
        runCatching { checkPublish(ctx) }
    }

    /** 手动检查（设置页入口）：结果同步进 [available]（与播放页胶囊共用同一事实源）。 */
    suspend fun checkPublish(ctx: Context): Outcome {
        val r = check(ctx)
        available.value = (r as? Outcome.Available)?.info
        return r
    }

    fun installedVersionCode(ctx: Context): Int {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pi.versionCode
    }

    fun installedVersionName(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
    }.getOrNull().orEmpty()

    private fun parse(json: String): UpdateInfo? = runCatching {
        val o = JSONObject(json)
        UpdateInfo(
            versionCode = o.getInt("versionCode"),
            versionName = o.getString("versionName"),
            apk = o.getString("apk"),
            mirror = o.optString("mirror").takeUnless { it.isNullOrEmpty() },
            sha256 = o.optString("sha256").lowercase(),
            changelog = o.optString("changelog"),
        )
    }.getOrNull()

    // ---- 下载与安装 ----

    /**
     * 下载源兜底链（2026-10-02 用户口径「加加速镜像兜底」）：
     * jsDelivr 镜像 → ghfast.top → gh-proxy.com → GitHub Release 直链。
     *
     * 手表日志实锤（2026-10-02 20:23）：19MB 的更新包走 jsDelivr 会 301 回
     * raw.githubusercontent，国内网络拉到一半就 HTTP_DATA_ERROR: timeout，
     * 三次下载全部没完成 —— 大包必须有后继源。两个加速镜像同日实测 200
     * 且 Content-Length 与正式包一致。
     */
    private val PROXY_PREFIXES = listOf("https://ghfast.top/", "https://gh-proxy.com/")

    fun downloadSources(info: UpdateInfo): List<String> = buildList {
        info.mirror?.let(::add)
        PROXY_PREFIXES.forEach { add(it + info.apk) }
        add(info.apk)
    }

    enum class InstallResult { STARTED, HASH_MISMATCH, FILE_MISSING, NO_PERMISSION }

    /** 交给系统 DownloadManager 下载指定源，返回下载 id（<=0 视为入队失败）。 */
    fun enqueueDownload(ctx: Context, info: UpdateInfo, url: String): Long {
        // 同名文件 DownloadManager 不覆盖而是加 -1 后缀，先清残留
        downloadFile(ctx).delete()
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("NetMusicLite ${info.versionName}")
            .setDescription("更新包下载中")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, null, "update/netmusiclite_update.apk")
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return try {
            dm.enqueue(request)
        } catch (e: Exception) {
            -1L
        }
    }

    /** 取消下载任务（换源重试前清掉旧任务，避免两个任务抢写同一个目标文件）。 */
    fun cancel(ctx: Context, id: Long) {
        if (id <= 0L) return
        runCatching {
            (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id)
        }
    }

    sealed class DownloadState {
        data class Running(val percent: Int) : DownloadState()
        object Done : DownloadState()
        data class Failed(val reason: String) : DownloadState()
    }

    fun query(ctx: Context, id: Long): DownloadState {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return DownloadState.Failed("下载任务已丢失")
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else 0
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadState.Done
                DownloadManager.STATUS_FAILED -> DownloadState.Failed(
                    "错误码 " + c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                )
                else -> DownloadState.Running(percent) // RUNNING / PENDING / PAUSED 都按进行中展示
            }
        }
    }

    fun downloadFile(ctx: Context): File =
        File(ctx.getExternalFilesDir(null), "update/netmusiclite_update.apk")

    /** 本地已下载文件是否与清单哈希一致（缺失/半截/被篡改都返回 false，不删文件）。 */
    fun verifyLocal(ctx: Context, info: UpdateInfo): Boolean {
        val f = downloadFile(ctx)
        if (!f.exists() || f.length() == 0L) return false
        return info.sha256.isEmpty() || f.sha256() == info.sha256
    }

    /**
     * SHA-256 校验通过后拉起系统安装器。
     *  · STARTED —— 已发起安装
     *  · HASH_MISMATCH —— 与清单哈希不符，文件已删除（防截断/劫持包进安装器）
     *  · FILE_MISSING —— 本地文件不存在或为空
     *  · NO_PERMISSION —— 缺「允许来自此来源」权限，UI 应引导去系统设置页开启
     */
    fun install(ctx: Context, info: UpdateInfo): InstallResult {
        val file = downloadFile(ctx)
        if (!file.exists() || file.length() == 0L) return InstallResult.FILE_MISSING
        if (info.sha256.isNotEmpty() && file.sha256() != info.sha256) {
            file.delete()
            return InstallResult.HASH_MISMATCH
        }
        // ★ 2026-10-07 用户口径「更改应用内更新的 apk 下载路径为默认下载文件夹」：
        //   哈希校验通过后往系统「下载」文件夹发布一份（只发布校验过的包，用户拿到的
        //   永远是完整文件）。发布失败不影响安装主链路 —— 不依赖这一步能不能装上；
        //   放在权限检查之前，缺「允许来自此来源」时用户也能从下载文件夹手动安装。
        runCatching { publishToDownloads(ctx, info) }
        if (!canInstall(ctx)) return InstallResult.NO_PERMISSION
        val uri = if (Build.VERSION.SDK_INT >= 24) {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.updatefileprovider", file)
        } else {
            @Suppress("DEPRECATION")
            Uri.fromFile(file)
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // ★ 2026-10-02 手表实测（OWW261）：content:// APK 意图有三家平权认领 ——
        //   com.android.packageinstaller / in.mfile 文件管理器 / 其它装机应用，不显式指定
        //   要么弹「打开方式」选择框、要么被文件管理器接走，观感就是「没拉起安装器」。
        //   显式指到包名含 packageinstaller 的系统安装器；找不到时回退默认解析（选择框兜底）。
        ctx.packageManager.queryIntentActivities(intent, 0)
            .firstOrNull { it.activityInfo.packageName.contains("packageinstaller") }
            ?.let { intent.component = ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
        ctx.startActivity(intent)
        return InstallResult.STARTED
    }

    /** 是否已获「允许来自此来源」安装权限（minSdk 26，canRequestPackageInstalls 直接可用）。 */
    fun canInstall(ctx: Context): Boolean = runCatching {
        ctx.packageManager.canRequestPackageInstalls()
    }.getOrDefault(false)
    // ★ 2026-10-18 崩溃日志实测（Download/WMusic-crash-20261018-075740.txt）：
    //   清单里**没声明** REQUEST_INSTALL_PACKAGES 时，canRequestPackageInstalls() 不是返回 false
    //   而是**直接抛 SecurityException**（Parcel 层校验权限）。debug 变体（com.netmusiclite.motiontest）
    //   出于测试稳定性主动 tools:node="remove" 掉了这个权限 ⇒ 登录态启动 2.4s 后必崩
    //   （AppNav.kt:259 的 LaunchedEffect 不在任何 try 里）。
    //   现在统一降级为「未授权」：没有该权限的构建只是不弹系统设置页，更新流程照旧走用户主动点。 

    // ★ 2026-10-17 用户口径「应用安装权限主动申请只跳出申请一次，不管用户是否拒绝都不跳出第二次」：
    //   启动时的自动申请改了语义：只有在「未授权且从未申请过」时才跳一次系统设置页；
    //   用户拒绝后标记永久落盘，之后每次启动都不再主动弹（更新流程里用户主动点更新时仍按需引导，不受影响）。
    private const val PREF_INSTALL_PROMPTED = "install_perm_prompted"

    private fun installPromptPrefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("ncm_update", Context.MODE_PRIVATE)

    fun hasPromptedInstallPermission(ctx: Context): Boolean =
        installPromptPrefs(ctx).getBoolean(PREF_INSTALL_PROMPTED, false)

    /** 主动申请一次：已授权 / 已申请过 → 什么都不做；否则跳设置页并落标记。返回是否实际发起。 */
    fun requestInstallPermissionOnce(ctx: Context): Boolean {
        if (canInstall(ctx)) return false
        if (hasPromptedInstallPermission(ctx)) return false
        installPromptPrefs(ctx).edit().putBoolean(PREF_INSTALL_PROMPTED, true).apply()
        return openInstallPermissionSettings(ctx)
    }

    /**
     * 把已下载并校验过的更新包**复制一份**到系统「下载」文件夹（2026-10-07 用户口径）。
     *
     * 走 MediaStore（API 29+）：免存储权限，条目归本应用所有 —— 重下/重试时先把
     * 自己创建的旧同名文件删掉，避免系统自动加 `-1` 后缀堆文件。低于 Q 没有
     * MediaStore.Downloads，跳过（私有目录那份照旧，更新流程不受影响）。
     * 文件名带版本号，用户一眼能认出是哪个版本；私有目录那份仍在，安装主链路不变
     * （FileProvider + 显式包名安装器，本机实测过的路径不动）。
     * 调用方已 runCatching：这里失败一律静默，绝不让「发布副本」影响能不能装上。
     */
    private fun publishToDownloads(ctx: Context, info: UpdateInfo) {
        if (Build.VERSION.SDK_INT < 29) return
        val src = downloadFile(ctx)
        if (!src.exists() || src.length() == 0L) return
        val resolver = ctx.contentResolver
        val name = "WMusic-${info.versionName}.apk"
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        // 同名旧文件先删（重复下载/重试场景）——先收集 id 再删，避免边遍历边删搞乱游标；
        // 删得掉的是本应用创建的，别人的同名文件删不动（留给系统自动加后缀）
        val oldIds = runCatching {
            buildList {
                resolver.query(
                    collection, arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                    arrayOf(name, Environment.DIRECTORY_DOWNLOADS + "/"), null,
                )?.use { c -> while (c.moveToNext()) add(c.getLong(0)) }
            }
        }.getOrDefault(emptyList())
        oldIds.forEach { id ->
            runCatching { resolver.delete(ContentUris.withAppendedId(collection, id), null, null) }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: return
        runCatching {
            resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                ?: error("openOutputStream returned null")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }.onFailure {
            // 拷贝中途失败：删掉半截条目，别让用户在下载夹里看到一个坏包
            runCatching { resolver.delete(uri, null, null) }
        }
    }

    /** 跳系统「允许来自此来源」设置页（未授权时的引导入口）；成功发起返回 true。 */
    fun openInstallPermissionSettings(ctx: Context): Boolean {
        val app = ctx.applicationContext
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")),
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(app.packageManager) != null) {
                return runCatching { app.startActivity(intent); true }.getOrDefault(false)
            }
        }
        return false
    }

    private fun File.sha256(): String {
        val md = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
