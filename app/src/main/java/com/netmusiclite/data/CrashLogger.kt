package com.netmusiclite.data

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 崩溃日志自动记录 + 导出到「下载」文件夹（★ 2026-10-18 用户口径
 * 「给我加个崩溃日志自动记录导出到 download」；同日加强版）。
 *
 * ## 五条采集通道
 *  1. **Java 崩溃**：[Thread.setDefaultUncaughtExceptionHandler] 捕获任何线程（主线程 / 协程 /
 *     播放器线程 / Coil 线程）的未捕获异常，先落报告再原样交回上一个 handler —— 系统原有
 *     崩溃处理、ROM 自己的错误收集、崩溃弹窗全部保持不变，这里只多写一个文件。
 *  2. **主线程卡死 / 疑似 ANR**：守护线程每 2s 往主线程消息队列投一个心跳，连续 6s 没被处理、
 *     且 App 仍在前台可见、屏幕点亮时，dump 主线程 + 全部线程栈。系统级 ANR 弹窗应用侧拿不到
 *     回调，这条通道负责把它之前的那段现场留住（真 ANR 被杀后还能靠第 3 条补上）。
 *  3. **进程退出原因**（API 30+，[ActivityManager.getHistoricalProcessExitReasons]）：
 *     Java 崩溃以外的死法 —— native crash（SIGSEGV 等）、ANR、LMK 低内存杀、资源超限、
 *     初始化失败、系统强杀 —— 下次启动时补写报告，并带上**上一次会话的面包屑**。
 *  4. **面包屑**：[step] 记录最近 120 条关键动作（启动 / 主题切换 / 导航 / M3E 转场），
 *     每次都是**同步追加**进 `breadcrumbs.txt`（native crash / 系统强杀不给异步落盘机会），
 *     超过 64KB 才在后台线程整文件压实；崩溃、卡死、退出报告都会带上它。
 *  5. **上次会话异常中断快照**（补 API < 30 的 native crash 盲区）：下次启动时，若上次
 *     面包屑停在「前台」（最后是 onResume/onCreate，没有随后的 onPause），且既没有 Java
 *     崩溃/卡死报告、也没有系统退出报告，就把上次面包屑导出成 `WMusic-prev-*.txt` ——
 *     那正是 native crash / SIGSEGV / 被系统强杀留下的最后现场。
 *
 * ## 报告去哪
 *  · 内部 `filesDir/crash/` 下的 `*.txt`：必定先写这里，不依赖任何权限，进程被杀也不丢；
 *  · 公共「下载」目录：API 29+ 走 `MediaStore.Downloads`（免权限，系统文件管理可见）；
 *    API 26~28 需要 `WRITE_EXTERNAL_STORAGE` 运行时授权（本 App 不在启动时索要，通常未授予），
 *    授予了才直写公共 `Download/`；
 *  · 兜底：公共目录不可写时，同步再写一份 `Android/data/<包名>/files/Download/`，
 *    文件管理器 / adb 都能取到；这次算「未导出」，下次启动 [init] 继续重试公共目录。
 *
 * ## 已知边界（刻意不做 / 做不到）
 *  · **API < 30 的 native crash**：系统不向应用提供退出原因，除非自己上 NDK signal handler，
 *    这里不做；那类机型只能靠卡死看门狗 + Java handler + 上一次面包屑。
 *  · 系统 ANR 判定本身拿不到回调（AMS 侧），看门狗是应用侧近似；
 *  · 不在崩溃路径上做网络 / 弹 UI / 大对象格式化，报告只是字符串拼接 + 文件写。
 */
object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val DIR = "crash"
    private const val CRUMB_FILE = "breadcrumbs.txt"
    private const val CRUMB_PREV_FILE = "breadcrumbs.prev.txt"

    /** 内部目录最多留几份报告（防止反复崩溃把存储写满；面包屑文件不计入） */
    private const val MAX_KEEP = 30
    private const val MAX_CRUMBS = 120
    private const val MAX_CRUMB_CHARS = 12_000
    private const val MAX_CRUMB_FILE_BYTES = 64 * 1024
    private const val MAX_THREADS = 48
    private const val MAX_FRAMES = 40
    private const val MAX_TRACE_BYTES = 128 * 1024
    private const val FIRST_RUN_LOOKBACK_MS = 24L * 60L * 60L * 1000L

    private const val PREFS = "ncm_crash"
    private const val KEY_EXPORTED = "exported"
    private const val KEY_EXIT_SEEN = "exit_seen"
    private const val KEY_LAST_SEEN = "last_seen_ms"
    private const val KEY_PREV_SIGNATURE = "prev_signature"

    private const val WATCHDOG_WARMUP_MS = 15_000L
    private const val WATCHDOG_POLL_MS = 2_000L
    private const val WATCHDOG_TIMEOUT_MS = 6_000L
    private const val WATCHDOG_COOLDOWN_MS = 60_000L

    private var app: Context? = null
    private val recordLock = Any()
    private val crumbLock = Any()
    private val prefsLock = Any()
    private val crumbs = ArrayDeque<String>()
    @Volatile private var crumbsPrepared = false
    @Volatile private var prevCrumbText = ""
    @Volatile private var prevCrumbAt = 0L
    @Volatile private var crumbTrimScheduled = false

    /**
     * 补导出 / 面包屑压实 / 退出原因读取共用的后台线程。
     * 崩溃落盘和面包屑追加都在调用线程同步完成（那两条路径不能异步：进程随时会被杀），
     * 只有「上次没导出成功的补导」「启动时读退出原因」「面包屑超限压实」这类不紧急的事放这里。
     */
    private val ioPool = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "crash-io").apply { isDaemon = true }
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 在 Application.onCreate 最早调用一次（越早，越能兜住启动期的崩溃）。 */
    fun init(context: Context) {
        val ctx = context.applicationContext
        app = ctx
        // 只登记内存里的会话起点，不在这里做任何文件 IO（启动首帧预算紧张）
        synchronized(crumbLock) {
            crumbs.clear()
            crumbs.addLast("${clock()} ===== 会话开始 pid=${android.os.Process.myPid()} =====")
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(ctx, thread, error) }
                .onFailure { Log.e(TAG, "崩溃报告写入失败", it) }
            // 交回原 handler：系统/ROM 的崩溃处理与弹窗行为与改动前完全一致
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                // previous 为 null 时（少数由 native 侧创建的线程）按系统原语义结束进程
                runCatching {
                    android.os.Process.killProcess(android.os.Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
        }
        runCatching { startWatchdog(ctx) }
        runCatching {
            ioPool.execute {
                // 顺序不能换：先旋转/读取上一次面包屑，退出原因报告才拿得到上次现场
                runCatching { prepareCrumbs(ctx) }
                    .onFailure { Log.w(TAG, "面包屑初始化失败", it) }
                val exitReported = runCatching { captureExitReasons(ctx) }
                    .onFailure { Log.w(TAG, "退出原因读取失败", it) }
                    .getOrDefault(0)
                runCatching { maybeExportPrevSession(ctx, exitReported > 0) }
                    .onFailure { Log.w(TAG, "上次会话快照导出失败", it) }
                runCatching { exportPending(ctx) }
                    .onFailure { Log.w(TAG, "补导出失败", it) }
                runCatching { trimAppExternal(ctx) }
            }
        }
        Log.i(TAG, "uncaught handler armed → ${File(ctx.filesDir, DIR).absolutePath} + Download")
    }

    /** 记一条面包屑；任何线程、任何时机都可以调用，不会抛异常。 */
    fun step(message: String) {
        runCatching {
            val ctx = app ?: return
            // 上一次会话的面包屑先旋转到 prev；只有首次调用会真正做
            prepareCrumbs(ctx)
            val line = "${clock()} [${Thread.currentThread().name}] ${message.take(300)}"
            synchronized(crumbLock) {
                crumbs.addLast(line)
                while (crumbs.size > MAX_CRUMBS) crumbs.removeFirst()
                // ★ 同步 append（不是防抖整文件重写）：native crash / 系统强杀不会给 Kotlin
                //   任何收尾机会，只写内存或延迟落盘等于丢掉最关键的那条导航记录。
                //   单行 append 很小，且只发生在主题/导航/生命周期等用户操作上，不在每帧路径。
                File(dir(ctx), CRUMB_FILE).appendText("$line\n")
            }
            maybeTrimCrumbFile(ctx)
        }
    }

    /** 内部目录里所有报告（新→旧），供自检 / 手动导出使用；面包屑文件不在其中 */
    fun logs(): List<File> = app?.let { reportFiles(it) } ?: emptyList()

    // ── Java 崩溃 ────────────────────────────────────────────────────────────

    private fun record(ctx: Context, thread: Thread, error: Throwable) {
        synchronized(recordLock) {
            val now = System.currentTimeMillis()
            val stamp = stamp(now)
            val oom = error is OutOfMemoryError
            val text = runCatching {
                if (oom) buildOomReport(ctx, thread, error, now)
                else buildCrashReport(ctx, thread, error, now)
            }.getOrElse { e ->
                buildString {
                    appendLine("===== WMusic 崩溃报告（报告构建二次失败）=====")
                    appendLine("时间: ${dateTime(now)}")
                    appendLine("原异常: ${error.javaClass.name}: ${error.message}")
                    appendLine("构建失败: ${e.javaClass.name}: ${e.message}")
                }
            }
            markAbnormal("java crash", "${thread.name}: ${error.javaClass.name}")
            flushCrumbsSync(ctx)
            writeAndExport(ctx, "crash-$stamp.txt", "WMusic-crash-$stamp.txt", text)
            trim(dir(ctx))
        }
    }

    private fun buildCrashReport(ctx: Context, thread: Thread, error: Throwable, now: Long): String =
        buildString {
            append(buildFullHeader(ctx, "崩溃报告", now))
            appendLine("线程: ${thread.name} (id=${thread.id}) ${if (thread === Looper.getMainLooper().thread) "[主线程]" else ""}")
            appendLine()
            appendThrowableChain(this, error)
            appendLine()
            appendCrumbs(this)
            appendAllThreads(this)
            appendLine("===== 结束 =====")
        }

    /** OOM 路径：不做全线程 dump、不拼大字符串，尽量保证还能把报告写出去 */
    private fun buildOomReport(ctx: Context, thread: Thread, error: Throwable, now: Long): String =
        buildString {
            append(buildMinimalHeader(ctx, "OOM 崩溃报告", now))
            appendLine("线程: ${thread.name} (id=${thread.id})")
            appendLine()
            appendLine("----- 异常（仅前 12 帧，避免二次分配）-----")
            appendLine("${error.javaClass.name}: ${error.message}")
            error.stackTrace.take(12).forEach { appendLine("    at $it") }
            appendLine()
            appendCrumbs(this, synchronized(crumbLock) { crumbs.toList().takeLast(30).joinToString("\n") })
            appendLine("===== 结束 =====")
        }

    private fun appendThrowableChain(out: StringBuilder, error: Throwable) {
        out.appendLine("----- 异常 -----")
        appendThrowable(out, error)
        var cause = error.cause
        var depth = 0
        while (cause != null && depth < 5) {
            out.appendLine()
            out.appendLine("----- Caused by #${++depth} -----")
            appendThrowable(out, cause)
            cause = cause.cause
        }
    }

    private fun appendThrowable(out: StringBuilder, t: Throwable, maxFrames: Int = 80) {
        out.appendLine("${t.javaClass.name}: ${t.message}")
        val frames = t.stackTrace
        frames.take(maxFrames).forEach { out.appendLine("    at $it") }
        if (frames.size > maxFrames) out.appendLine("    ...(${frames.size - maxFrames} more)")
    }

    private fun appendCrumbs(out: StringBuilder, text: String? = null) {
        val body = text ?: synchronized(crumbLock) { crumbs.joinToString("\n") }
        out.appendLine("----- 面包屑（最近 $MAX_CRUMBS 条）-----")
        if (body.isBlank()) out.appendLine("(无)") else out.appendLine(body.takeLast(MAX_CRUMB_CHARS))
        out.appendLine()
    }

    private fun appendAllThreads(out: StringBuilder) {
        out.appendLine("----- 所有线程 -----")
        val main = Looper.getMainLooper().thread
        val traces = runCatching { Thread.getAllStackTraces() }.getOrNull() ?: return
        traces[main]?.let { appendThread(out, main, it, " [主线程]") }
        var others = 0
        for ((thread, stack) in traces) {
            if (thread === main) continue
            if (others++ >= MAX_THREADS) {
                out.appendLine()
                out.appendLine("...(其余线程省略)")
                break
            }
            appendThread(out, thread, stack)
        }
        out.appendLine()
    }

    private fun appendThread(
        out: StringBuilder,
        thread: Thread,
        stack: Array<StackTraceElement>,
        label: String = "",
    ) {
        out.appendLine()
        out.appendLine("\"${thread.name}\" id=${thread.id} state=${thread.state} daemon=${thread.isDaemon}$label")
        stack.take(MAX_FRAMES).forEach { out.appendLine("    at $it") }
        if (stack.size > MAX_FRAMES) out.appendLine("    ...(${stack.size - MAX_FRAMES} more)")
    }

    // ── 主线程卡死看门狗（疑似 ANR）───────────────────────────────────────────

    private fun startWatchdog(ctx: Context) {
        Thread({
            try {
                Thread.sleep(WATCHDOG_WARMUP_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            var lastHangAt = 0L
            while (true) {
                try {
                    Thread.sleep(WATCHDOG_POLL_MS)
                    if (!watchable(ctx)) continue
                    val latch = CountDownLatch(1)
                    if (!mainHandler.post { latch.countDown() }) continue
                    if (latch.await(WATCHDOG_TIMEOUT_MS, TimeUnit.MILLISECONDS)) continue
                    // 超时后复核一次：可能刚好在超时瞬间恢复，且已经切到后台
                    if (!watchable(ctx)) continue
                    val now = SystemClock.uptimeMillis()
                    if (now - lastHangAt < WATCHDOG_COOLDOWN_MS) continue
                    lastHangAt = now
                    Log.w(TAG, "主线程 ${WATCHDOG_TIMEOUT_MS}ms 未处理心跳，写卡死报告")
                    captureHang(ctx)
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (t: Throwable) {
                    Log.w(TAG, "watchdog loop", t)
                }
            }
        }, "crash-watchdog").apply { isDaemon = true }.start()
    }

    /** 只在「屏幕点亮 + 进程至少可见」时看门狗才生效，避免后台/息屏误报 */
    private fun watchable(ctx: Context): Boolean = runCatching {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return@runCatching false
        if (!pm.isInteractive) return@runCatching false
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }.getOrDefault(false)

    private fun captureHang(ctx: Context) {
        synchronized(recordLock) {
            val now = System.currentTimeMillis()
            val stamp = stamp(now)
            val text = runCatching {
                buildString {
                    append(buildFullHeader(ctx, "主线程无响应报告", now))
                    appendLine("心跳: 主线程连续 ${WATCHDOG_TIMEOUT_MS}ms 未处理消息（疑似 ANR / 死锁 / 转场绘制卡死）")
                    appendLine()
                    appendCrumbs(this)
                    appendAllThreads(this)
                    appendLine("===== 结束 =====")
                }
            }.getOrElse { e ->
                buildString {
                    append(buildMinimalHeader(ctx, "主线程无响应报告（构建失败）", now))
                    appendLine("构建失败: ${e.javaClass.name}: ${e.message}")
                }
            }
            markAbnormal("hang", "main thread ${WATCHDOG_TIMEOUT_MS}ms")
            flushCrumbsSync(ctx)
            writeAndExport(ctx, "hang-$stamp.txt", "WMusic-hang-$stamp.txt", text)
            trim(dir(ctx))
        }
    }

    // ── 进程退出原因（native crash / ANR / LMK，API 30+）──────────────────────

    /**
     * 读取系统保存的本包最近退出记录。API < 30 直接跳过（类不可用）。
     * 只对「非正常退出」落报告；正常划掉 / 用户强停 / 系统冻结等只记 seen，避免刷屏。
     *
     * ★ 刻意用反射而不是直接引用 `ApplicationExitInfo`：minSdk 26，老机器加载
     *   CrashLogger 时不该因为一个 API 30 才有的类型触发类校验问题。只在启动时跑一次，
     *   几个方法查找的成本可以忽略。
     */
    private fun captureExitReasons(ctx: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.i(TAG, "API ${Build.VERSION.SDK_INT} < 30，无 ApplicationExitInfo；native crash 只能靠看门狗/上次面包屑")
            return 0
        }
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
        val reasons: List<*> = runCatching {
            val method = ActivityManager::class.java.getMethod(
                "getHistoricalProcessExitReasons",
                String::class.java,
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )
            method.invoke(am, ctx.packageName, 0, 8) as? List<*>
        }.onFailure {
            Log.w(TAG, "getHistoricalProcessExitReasons 失败", it)
        }.getOrNull() ?: return 0

        val p = prefs(ctx)
        val seen = synchronized(prefsLock) {
            (p.getStringSet(KEY_EXIT_SEEN, emptySet()) ?: emptySet()).toMutableSet()
        }
        val lastSeen = synchronized(prefsLock) { p.getLong(KEY_LAST_SEEN, 0L) }
        val now = System.currentTimeMillis()
        val cutoff = if (lastSeen > 0L) lastSeen - 5_000L else now - FIRST_RUN_LOOKBACK_MS
        var reported = 0

        reasons.mapNotNull { raw ->
            val info = raw ?: return@mapNotNull null
            ExitRecord(
                raw = info,
                timestamp = invokeNumber(info, "getTimestamp")?.toLong() ?: 0L,
                reason = invokeNumber(info, "getReason")?.toInt() ?: -1,
                pid = invokeNumber(info, "getPid")?.toInt() ?: -1,
                processName = invokeString(info, "getProcessName"),
                status = invokeNumber(info, "getStatus")?.toInt() ?: -1,
                importance = invokeNumber(info, "getImportance")?.toInt() ?: -1,
                pss = invokeNumber(info, "getPss")?.toLong(),
                rss = invokeNumber(info, "getRss")?.toLong(),
                description = invokeString(info, "getDescription"),
            )
        }.sortedBy { it.timestamp }.forEach { info ->
            if (info.timestamp <= 0L || info.timestamp < cutoff) return@forEach
            val key = "${info.timestamp}:${info.reason}:${info.pid}"
            if (key in seen) return@forEach
            seen += key
            if (!interestingExit(info.reason, info.description)) return@forEach
            runCatching {
                val pss = info.pss?.let { "${it / 1048576}MB" } ?: "?"
                val rss = info.rss?.let { "${it / 1048576}MB" } ?: "?"
                val text = buildString {
                    append(buildExitHeader(ctx, "进程退出报告", info.timestamp, now))
                    appendLine("Reason: ${reasonName(info.reason)} (${info.reason})")
                    appendLine("进程: ${info.processName ?: "?"} pid=${info.pid}")
                    appendLine("状态: ${info.status}  importance=${info.importance}  pss=$pss rss=$rss")
                    appendLine("描述: ${info.description ?: "(无)"}")
                    appendLine()
                    appendLine("----- 上一次会话面包屑（可能不匹配更早的退出记录）-----")
                    val prev = prevCrumbText
                    if (prev.isBlank()) appendLine("(无)") else appendLine(prev)
                    appendLine()
                    appendLine("----- 系统退出 trace -----")
                    val trace = readExitTrace(info.raw)
                    if (trace.isNullOrBlank()) appendLine("(无)") else appendLine(trace)
                    appendLine("===== 结束 =====")
                }
                val exitStamp = stamp(info.timestamp)
                val reason = reasonName(info.reason).lowercase(Locale.US)
                val internalName = "exit-$exitStamp-$reason-${info.pid}.txt"
                writeAndExport(ctx, internalName, "WMusic-$internalName", text)
                reported++
            }.onFailure {
                Log.w(TAG, "生成退出报告失败 reason=${info.reason}", it)
            }
        }

        if (seen.size > 100) {
            val keep = seen.toList().takeLast(100).toSet()
            seen.clear()
            seen.addAll(keep)
        }
        synchronized(prefsLock) {
            p.edit()
                .putStringSet(KEY_EXIT_SEEN, seen)
                .putLong(KEY_LAST_SEEN, now)
                .commit()
        }
        if (reported > 0) trim(dir(ctx))
        return reported
    }

    private data class ExitRecord(
        val raw: Any,
        val timestamp: Long,
        val reason: Int,
        val pid: Int,
        val processName: String?,
        val status: Int,
        val importance: Int,
        val pss: Long?,
        val rss: Long?,
        val description: String?,
    )

    private fun invokeNumber(target: Any, method: String): Number? =
        runCatching { target.javaClass.getMethod(method).invoke(target) as? Number }.getOrNull()

    private fun invokeString(target: Any, method: String): String? =
        runCatching { target.javaClass.getMethod(method).invoke(target) as? String }.getOrNull()

    private fun readExitTrace(target: Any): String? = runCatching {
        val stream = target.javaClass.getMethod("getTraceInputStream").invoke(target) as? InputStream
            ?: return@runCatching null
        stream.use { readCapped(it) }
    }.getOrNull()

    private fun interestingExit(reason: Int, description: String?): Boolean = when (reason) {
        // SIGNALED / LOW_MEMORY / CRASH / CRASH_NATIVE / ANR /
        // INITIALIZATION_FAILURE / EXCESSIVE_RESOURCE_USAGE / DEPENDENCY_DIED
        2, 3, 4, 5, 6, 7, 9, 12 -> true
        // UNKNOWN / OTHER：只有描述像崩溃/强杀时才落报告，避免 OEM 正常回收刷屏
        0, 13 -> {
            val d = description?.lowercase(Locale.US).orEmpty()
            d.contains("crash") || d.contains("signal") || d.contains("fatal") ||
                d.contains("abort") || d.contains("anr") || d.contains("kill")
        }
        // 1 EXIT_SELF / 8 PERMISSION_CHANGE / 10 USER_REQUESTED / 11 USER_STOPPED /
        // 14 FREEZER / 15 PACKAGE_STATE_CHANGE / 16 PACKAGE_UPDATED 都不算崩溃
        else -> false
    }

    private fun reasonName(reason: Int): String = when (reason) {
        0 -> "UNKNOWN"
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "UNKNOWN($reason)"
    }

    private fun readCapped(input: InputStream, maxBytes: Int = MAX_TRACE_BYTES): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < maxBytes) {
            val read = input.read(buf, 0, minOf(buf.size, maxBytes - total))
            if (read <= 0) break
            out.write(buf, 0, read)
            total += read
        }
        val text = out.toString("UTF-8")
        return if (total >= maxBytes) "$text\n...(trace 已截断，上限 $maxBytes 字节)" else text
    }

    // ── 面包屑持久化 ─────────────────────────────────────────────────────────

    private fun prepareCrumbs(ctx: Context) {
        if (crumbsPrepared) return
        ensureCrumbsPrepared(ctx)
        flushCrumbsSync(ctx)
    }

    /**
     * 把上一次会话的 breadcrumbs.txt 复制成 breadcrumbs.prev.txt 并读进内存，
     * 供本次启动的退出原因报告使用。只在第一次真正需要落盘时执行一次。
     */
    private fun ensureCrumbsPrepared(ctx: Context) {
        if (crumbsPrepared) return
        synchronized(crumbLock) {
            if (crumbsPrepared) return
            val d = dir(ctx)
            val current = File(d, CRUMB_FILE)
            val previous = File(d, CRUMB_PREV_FILE)
            prevCrumbText = runCatching {
                if (current.exists()) {
                    prevCrumbAt = current.lastModified()
                    current.copyTo(previous, overwrite = true)
                    current.readText()
                } else if (previous.exists()) {
                    prevCrumbAt = previous.lastModified()
                    previous.readText()
                } else {
                    ""
                }
            }.getOrDefault("").takeLast(MAX_CRUMB_CHARS)
            crumbsPrepared = true
        }
    }

    private fun flushCrumbsSync(ctx: Context) {
        runCatching {
            ensureCrumbsPrepared(ctx)
            // 读内存和写文件放在同一把锁里：避免与 step 的 append 交错写出半行。
            // postfix 必须带换行：下一次 step 是直接 appendText，否则会粘在最后一行尾巴上。
            synchronized(crumbLock) {
                File(dir(ctx), CRUMB_FILE).writeText(crumbs.joinToString("\n", postfix = "\n"))
            }
        }
    }

    /** 在面包屑末尾留下「本次会话已产出报告」标记，下次启动不再重复导出异常中断快照 */
    private fun markAbnormal(kind: String, detail: String) {
        runCatching {
            val line = "${clock()} [${Thread.currentThread().name}] !! $kind recorded: ${detail.take(160)}"
            synchronized(crumbLock) {
                crumbs.addLast(line)
                while (crumbs.size > MAX_CRUMBS) crumbs.removeFirst()
            }
        }
    }

    /** 面包屑文件超过阈值时，后台收敛回内存里的最近 120 条（步骤本身永远同步 append） */
    private fun maybeTrimCrumbFile(ctx: Context) {
        if (crumbTrimScheduled) return
        val file = File(dir(ctx), CRUMB_FILE)
        if (file.length() <= MAX_CRUMB_FILE_BYTES) return
        crumbTrimScheduled = true
        runCatching {
            ioPool.execute {
                crumbTrimScheduled = false
                flushCrumbsSync(ctx)
            }
        }.onFailure {
            crumbTrimScheduled = false
        }
    }

    // ── 上次会话异常中断快照（API < 30 native crash 的补偿）───────────────────

    /**
     * 上次会话如果停在「前台」且没有留下任何报告，就把最后的面包屑导出成一份快照。
     * 专补 API < 30 的 native crash：系统不给退出原因，Java handler 也接不到 SIGSEGV，
     * 这段面包屑往往就是唯一能看出「死在哪个页面 / 哪次转场」的证据。
     *
     * 判据刻意保守：只看最后一条生命周期 —— onResume/onCreate 之后没有 onPause 才算异常中断；
     * 正常划掉 / 回表盘 / 息屏都会先走 onPause，不会刷屏。快照按内容签名去重，
     * 同一段面包屑只导出一次；导出失败的文件留在内部目录，由 [exportPending] 下次重试。
     */
    private fun maybeExportPrevSession(ctx: Context, exitReported: Boolean) {
        if (exitReported) return
        val prev = prevCrumbText.trim()
        if (prev.isBlank()) return
        val lines = prev.lineSequence().filter { it.isNotBlank() }.toList()
        if (lines.isEmpty()) return
        // 已写出 Java 崩溃/卡死报告时不再重复导出
        if (lines.last().contains("!!")) return
        val lastForeground = lines.indexOfLast {
            it.contains("MainActivity.onResume") || it.contains("MainActivity.onCreate")
        }
        if (lastForeground < 0) return
        val lastPause = lines.indexOfLast { it.contains("MainActivity.onPause") }
        if (lastPause > lastForeground) return
        val at = if (prevCrumbAt > 0L) prevCrumbAt else System.currentTimeMillis()
        // 上一次会话已经写出过报告（旧版本没有 !! 标记）时不再重复导出；
        // prev-*.txt 是快照本身，不能拿它当「已有报告」的证据
        if (reportFiles(ctx).any {
                !it.name.startsWith("prev-") && kotlin.math.abs(it.lastModified() - at) < 60_000L
            }) return

        val signature = prev.hashCode().toString(16) + "-" + lines.size
        val p = prefs(ctx)
        if (synchronized(prefsLock) { p.getString(KEY_PREV_SIGNATURE, null) } == signature) return

        val now = System.currentTimeMillis()
        val text = buildString {
            append(buildExitHeader(ctx, "上次会话异常中断快照", at, now))
            appendLine("判定: 上次会话最后停在「前台」（onResume/onCreate 之后没有 onPause），")
            appendLine("      且没有写出 Java 崩溃/卡死报告、系统也没有给出可解释的退出记录。")
            appendLine("      如果是 native crash（SIGSEGV 等）/ 被系统强杀，这里就是它最后的现场。")
            appendLine("注意: 本快照只有应用侧面包屑；native 的系统 trace 只存在于 API 30+ 的退出报告。")
            appendLine()
            appendLine("----- 上次会话面包屑 -----")
            appendLine(prev)
            appendLine("===== 结束 =====")
        }
        val internalName = "prev-${stamp(at)}.txt"
        writeAndExport(ctx, internalName, "WMusic-$internalName", text)
        synchronized(prefsLock) {
            p.edit().putString(KEY_PREV_SIGNATURE, signature).commit()
        }
        trim(dir(ctx))
    }

    // ── 导出 ────────────────────────────────────────────────────────────────

    /**
     * 写内部报告 + 尝试导出到公共 Download。
     * 返回 true = 已落到公共 Download；false = 只写了内部/应用专属外部，下次启动会重试。
     */
    private fun writeAndExport(
        ctx: Context,
        internalName: String,
        exportName: String,
        text: String,
    ): Boolean {
        val file = File(dir(ctx), internalName)
        runCatching { file.writeText(text) }
            .onFailure { Log.e(TAG, "内部报告写入失败: ${file.absolutePath}", it) }
        val ok = export(ctx, exportName, text)
        if (ok) markExported(ctx, internalName)
        return ok
    }

    private fun export(ctx: Context, name: String, text: String): Boolean = runCatching {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val publicOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportToMediaStore(ctx, name, bytes)
        } else {
            exportToLegacyPublic(ctx, name, bytes)
        }
        if (!publicOk) writeAppExternal(ctx, name, bytes)
        publicOk
    }.getOrDefault(false)

    private fun exportToMediaStore(ctx: Context, name: String, bytes: ByteArray): Boolean =
        runCatching {
            val resolver = ctx.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            // 已存在同名且已完成写入的条目 = 上次已经导出成功，不重复写
            val exists = runCatching {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.IS_PENDING}=0",
                    arrayOf(name),
                    null,
                )?.use { it.count > 0 } ?: false
            }.getOrDefault(false)
            if (exists) return@runCatching true

            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return@runCatching false
            val written = runCatching {
                resolver.openOutputStream(uri)?.use { it.write(bytes) } != null
            }.getOrDefault(false)
            if (!written) {
                runCatching { resolver.delete(uri, null, null) }
                return@runCatching false
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            val finalized = runCatching { resolver.update(uri, values, null, null) }.isSuccess
            if (!finalized) {
                // 半成品条目必须删掉，否则遗留的 IS_PENDING=1 空壳会让下一次同名查询误判
                runCatching { resolver.delete(uri, null, null) }
                return@runCatching false
            }
            true
        }.getOrDefault(false)

    private fun exportToLegacyPublic(ctx: Context, name: String, bytes: ByteArray): Boolean {
        val granted = ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.i(TAG, "API ${Build.VERSION.SDK_INT} 未授予 WRITE_EXTERNAL_STORAGE，公共 Download 不可直写")
            return false
        }
        return runCatching {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists() && !dir.mkdirs()) return@runCatching false
            File(dir, name).outputStream().use { it.write(bytes) }
            true
        }.getOrDefault(false)
    }

    /** 公共目录写不进去时的兜底：应用专属外部 Download，返回 false 让下次启动继续重试公共目录 */
    private fun writeAppExternal(ctx: Context, name: String, bytes: ByteArray) {
        runCatching {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (dir == null) {
                Log.w(TAG, "应用专属 Download 不可用（外部存储未挂载？）")
                return@runCatching
            }
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "应用专属 Download 创建失败: ${dir.absolutePath}")
                return@runCatching
            }
            val file = File(dir, name)
            file.outputStream().use { it.write(bytes) }
            Log.i(TAG, "公共 Download 不可写，已兜底写 ${file.absolutePath}")
        }.onFailure { Log.w(TAG, "兜底写应用专属 Download 失败", it) }
    }

    /** 启动时补导出：内部目录里存在、但从未成功导出到公共 Download 的报告 */
    private fun exportPending(ctx: Context) {
        val all = reportFiles(ctx)
        if (all.isEmpty()) return
        val done = synchronized(prefsLock) {
            (prefs(ctx).getStringSet(KEY_EXPORTED, emptySet()) ?: emptySet()).toMutableSet()
        }
        // 报告已被 trim 掉的名字没有保留价值
        done.retainAll(all.map { it.name }.toSet())
        val todo = all.filter { it.name !in done }.take(6)
        for (f in todo) {
            val ok = runCatching { export(ctx, "WMusic-${f.name}", f.readText()) }.getOrDefault(false)
            if (ok) done += f.name
        }
        synchronized(prefsLock) {
            prefs(ctx).edit().putStringSet(KEY_EXPORTED, done).commit()
        }
    }

    private fun markExported(ctx: Context, name: String) {
        runCatching {
            synchronized(prefsLock) {
                val p = prefs(ctx)
                val set = (p.getStringSet(KEY_EXPORTED, emptySet()) ?: emptySet()).toMutableSet()
                set += name
                // 崩溃路径上进程随时会被杀：必须 commit（同步落盘），apply() 来不及
                p.edit().putStringSet(KEY_EXPORTED, set).commit()
            }
        }
    }

    private fun trimAppExternal(ctx: Context) {
        runCatching {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return@runCatching
            val files = dir.listFiles()?.filter {
                it.isFile && it.name.startsWith("WMusic-")
            } ?: return@runCatching
            files.sortedByDescending { it.lastModified() }.drop(MAX_KEEP).forEach { it.delete() }
        }
    }

    // ── 报告头 / 设备信息 ────────────────────────────────────────────────────

    private fun buildFullHeader(ctx: Context, title: String, now: Long): String = buildString {
        appendLine("===== WMusic $title =====")
        appendLine("时间: ${dateTime(now)}")
        appendLine("应用: ${ctx.packageName} ${versionName(ctx)} (${versionCode(ctx)})")
        appendLine("机型: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("设备: ${Build.DEVICE} / ${Build.PRODUCT} / ${Build.HARDWARE} / ${Build.BOARD}")
        appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) patch=${Build.VERSION.SECURITY_PATCH}")
        appendLine("指纹: ${Build.FINGERPRINT}")
        appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("屏幕: ${screenSummary(ctx)}")
        appendLine("语言: ${Locale.getDefault()} fontScale=${ctx.resources.configuration.fontScale}")
        appendLine("内存: ${memorySummary(ctx)}")
        appendLine("进程: pid=${android.os.Process.myPid()} uptime=${processUptimeMs()}ms " +
            "importance=${importanceName()}")
        appendLine("主题: ${themeSummary()}")
        appendLine("分流: ${compatSummary()}")
        appendLine()
    }

    private fun buildMinimalHeader(ctx: Context, title: String, now: Long): String = buildString {
        appendLine("===== WMusic $title =====")
        appendLine("时间: ${dateTime(now)}")
        appendLine("应用: ${ctx.packageName} ${versionName(ctx)} (${versionCode(ctx)})")
        appendLine("机型: ${Build.MANUFACTURER} ${Build.MODEL} / ${Build.DEVICE} / " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("主题: ${themeSummary()}")
        appendLine()
    }

    /**
     * 退出报告在下次启动时生成，此时 AppearancePrefs 可能还没 init —— 不能读当前主题
     * （会读到默认值 classic），上次会话主题以上一次面包屑里的 appearance ready / theme → 行为准。
     */
    private fun buildExitHeader(ctx: Context, title: String, exitAt: Long, capturedAt: Long): String = buildString {
        appendLine("===== WMusic $title =====")
        appendLine("退出时间: ${dateTime(exitAt)} ($exitAt)")
        appendLine("记录时间: ${dateTime(capturedAt)}")
        appendLine("应用: ${ctx.packageName} ${versionName(ctx)} (${versionCode(ctx)})")
        appendLine("机型: ${Build.MANUFACTURER} ${Build.MODEL} / ${Build.DEVICE} / " +
            "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("上次会话主题: 见下方面包屑的 appearance ready / theme → 行")
        appendLine()
    }

    private fun screenSummary(ctx: Context): String {
        val dm = ctx.resources.displayMetrics
        val cfg = ctx.resources.configuration
        return "${dm.widthPixels}x${dm.heightPixels} @${dm.densityDpi}dpi " +
            "(${cfg.screenWidthDp}x${cfg.screenHeightDp}dp)"
    }

    private fun memorySummary(ctx: Context): String = runCatching {
        val rt = Runtime.getRuntime()
        val javaUsed = (rt.totalMemory() - rt.freeMemory()) / 1048576
        val javaMax = rt.maxMemory() / 1048576
        val nativeAlloc = Debug.getNativeHeapAllocatedSize() / 1048576
        val nativeSize = Debug.getNativeHeapSize() / 1048576
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am == null) {
            "java ${javaUsed}MB/${javaMax}MB native ${nativeAlloc}MB/${nativeSize}MB"
        } else {
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            "java ${javaUsed}MB/${javaMax}MB  native ${nativeAlloc}MB/${nativeSize}MB  " +
                "system ${mi.availMem / 1048576}MB free / ${mi.totalMem / 1048576}MB  lowRam=${am.isLowRamDevice}"
        }
    }.getOrDefault("?")

    private fun themeSummary(): String = runCatching {
        val mode = AppearancePrefs.mode
        if (!AppearancePrefs.isM3e) {
            return@runCatching "interface=classic mode=$mode dynamic=${AppearancePrefs.dynamicColor}"
        }
        val bg = when (AppearancePrefs.m3eBackgroundStyle) {
            M3eBackgroundStyle.AMBIENT -> "ambient"
            M3eBackgroundStyle.COVER_BLUR -> "cover_blur"
            else -> "black"
        }
        val player = when (AppearancePrefs.m3ePlayerStyleEffective) {
            M3ePlayerStyle.HOROLOGIST -> "horologist"
            else -> "legacy"
        }
        "interface=m3e bg=$bg player=$player explicit=${AppearancePrefs.m3ePlayerStyleExplicit} " +
            "playerOnlyBg=${AppearancePrefs.m3eBackgroundPlayerOnly} mode=$mode dynamic=${AppearancePrefs.dynamicColor}"
    }.getOrDefault("?")

    private fun compatSummary(): String = runCatching {
        "huawei=${DeviceCompat.isHuaweiWatch} coloros=${DeviceCompat.isColorOsWatch} " +
            "nativeRotary=${DeviceCompat.useNativeWearRotary} inAppVolume=${DeviceCompat.useInAppVolume}"
    }.getOrDefault("?")

    private fun importanceName(): String = runCatching {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        when {
            info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ->
                "FOREGROUND(${info.importance})"
            info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE ->
                "FOREGROUND_SERVICE(${info.importance})"
            info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE ->
                "VISIBLE(${info.importance})"
            else -> "BACKGROUND(${info.importance})"
        }
    }.getOrDefault("?")

    // ── 内部工具 ────────────────────────────────────────────────────────────

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, DIR).apply { if (!exists()) mkdirs() }

    private fun reportFiles(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter {
            it.isFile && it.name != CRUMB_FILE && it.name != CRUMB_PREV_FILE
        }?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun trim(directory: File) {
        runCatching {
            val reports = directory.listFiles()?.filter {
                it.isFile && it.name != CRUMB_FILE && it.name != CRUMB_PREV_FILE
            }?.sortedByDescending { it.lastModified() } ?: return@runCatching
            reports.drop(MAX_KEEP).forEach { it.delete() }
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun versionName(ctx: Context): String = runCatching {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0)?.versionName
    }.getOrNull() ?: "?"

    private fun versionCode(ctx: Context): Long = runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrDefault(-1L)

    private fun processUptimeMs(): Long =
        (SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()).coerceAtLeast(0L)

    private fun dateTime(ts: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date(ts))

    private fun stamp(ts: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(ts))

    private fun clock(): String {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        return "[$time +${processUptimeMs()}ms]"
    }
}
