package com.netmusiclite.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * 震动（2026-10-01 重构，按用户要求）。
 *
 * ★ 一、按钮震动全删
 *   原 click / success / tick / fast 四个入口连同全工程 71 处调用点一并删除，
 *   应用内不再存在任何「点一下就震」的反馈（含设置项、列表项、播放页喜欢/更多、
 *   进度弧拖动刻度等）。
 *
 * ★ 二、唯一保留的震动通道 = 表冠刻度 [crownDetent]
 *   对齐 OPPO 手表音乐包的做法（见《OPPO手表音乐包_接入点分析.md》§3.4 / §4.2）：
 *   - 服务：`getSystemService("linearmotor")` → `LinearmotorVibrator`
 *   - 效果：`WaveformEffect.Builder().setEffectType(t).setEffectStrength(s)
 *           .setEffectLoop(false).build()` → `vibrate(effect)`
 *   - effectType 取 **302 = EFFECT_HIGHVOLTAGE_SYSTEM_FEEDBACK_SCROLL**
 *     （真值取自本机 ROM `framework.jar` 里 WaveformEffect 的静态常量表；
 *      正是 OPPO 自研音乐包播放页表冠反馈用的那一档）
 *
 * ★ 三、健壮性
 *   - `linearmotor` 是 OPPO ROM 私有服务，非 OPPO 机型返回 null；类
 *     `android.os.linearmotorvibrator.*` 也可能不存在 —— 全程走反射 + runCatching，
 *     任何一步失败都静默降级到系统 Vibrator 的 EFFECT_TICK，绝不抛异常。
 *   - 刻度当量取系统 `ViewConfiguration.scaledVerticalScrollFactor`（≈ 一个物理格的像素
 *     数），对增量做累积取整，所以「一格震一记」与事件被拆帧无关。
 *
 * ★ 四、华为通道（2026-10-03，华为 Watch 3 适配）
 *   华为官方音乐/控件走的是华为 ROM 私有震动类 `com.huawei.android.os.VibratorEx`
 *   （见《华为音乐扫描》与 uikit `HwVibrateUtil` 反编译）：`newInstance()` +
 *   `isSupportHwVibrator(name)` + `setHwVibrator(name)`，效果名取
 *   `watchhaptic.crown.strength1/2/3`（真值表：10/11/12；官方单格用 strength3、
 *   连转用 strength1 —— 与本文件 OPPO 通道「慢转 STRONG / 连转 LIGHT」同构）。
 *   同一份 `haptic_feedback_enabled` 系统开关也要尊重（关了就静默，不再落到系统 tick）。
 *
 *   分流（用户口径「2者不混淆」）：fire 顺序 = OPPO 马达 → 华为 VibratorEx → 系统 TICK。
 *   三条通道全部**按能力探测**、逐级降级 —— 任何机型只会有其中一条在位，无需厂商判断。
 */
object NcmHaptics {

    // ------------------------------------------------------------------ 手感参数

    /** 慢转单格强度：0=LIGHT / 1=MEDIUM / 2=STRONG（OPPO 自研音乐包用 2） */
    private const val CROWN_STRENGTH_SLOW = 2

    /** 快转连转强度：连转时降为 LIGHT，避免震成一团噪 */
    private const val CROWN_STRENGTH_FAST = 0

    /** 连转判定：相邻两次刻度间隔小于此值即视为快转（ms） */
    private const val CROWN_FAST_MS = 90L

    /** 刻度保险丝：同一格事件被 ROM 拆成多帧时，防止连震（ms） */
    private const val CROWN_FUSE_MS = 18L

    /** 表冠滚动效果类型 = WaveformEffect.EFFECT_HIGHVOLTAGE_SYSTEM_FEEDBACK_SCROLL */
    private const val CROWN_EFFECT_TYPE = 302

    /** 华为表冠效果名（uikit HwVibrateUtil 常量表：crown.strength1/2/3 = 10/11/12；
     *  单格 = strength3（强）、连转 = strength1（轻），与官方音乐/控件口径一致） */
    private const val HW_CROWN_SLOW = "watchhaptic.crown.strength3"
    private const val HW_CROWN_FAST = "watchhaptic.crown.strength1"

    // ------------------------------------------------------------------ 常量与反射句柄

    private const val SERVICE_LINEARMOTOR = "linearmotor"
    private const val CLS_LINEARMOTOR = "android.os.linearmotorvibrator.LinearmotorVibrator"
    private const val CLS_WAVEFORM_EFFECT = "android.os.linearmotorvibrator.WaveformEffect"
    private const val CLS_WAVEFORM_BUILDER = "android.os.linearmotorvibrator.WaveformEffect\$Builder"
    private const val CLS_HW_VIBRATOR = "com.huawei.android.os.VibratorEx"

    /** 系统 Vibrator（降级通道） */
    private var vibrator: Vibrator? = null
    private var cachedTickEffect: VibrationEffect? = null

    /** OPPO 线性马达（反射） */
    private var lmService: Any? = null
    private var lmVibrate: java.lang.reflect.Method? = null
    private var ebCtor: java.lang.reflect.Constructor<*>? = null
    private var ebSetType: java.lang.reflect.Method? = null
    private var ebSetStrength: java.lang.reflect.Method? = null
    private var ebSetLoop: java.lang.reflect.Method? = null
    private var ebBuild: java.lang.reflect.Method? = null

    /** 华为 VibratorEx（反射；非华为机型类不存在 → 通道自动不在位） */
    private var hwService: Any? = null
    private var hwSet: java.lang.reflect.Method? = null
    private val hwSupported = HashSet<String>()

    /** 读系统「触感反馈」开关用（华为通道尊重它，官方口径） */
    private var appCtx: Context? = null

    /** 一格表冠 ≈ scaledVerticalScrollFactor 像素 */
    private var detentPx = 64f
    private var accum = 0f
    private var lastFireMs = 0L
    private var lastDetentMs = 0L

    private var inited = false

    // ------------------------------------------------------------------ 初始化

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        appCtx = ctx.applicationContext

        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        runCatching { ViewConfiguration.get(ctx).scaledVerticalScrollFactor.toFloat() }
            .getOrDefault(0f)
            .let { if (it > 1f) detentPx = it }

        initLinearMotor(ctx)
        initHuaweiVibrator()
    }

    /**
     * 取 OPPO 私有线性马达并把 WaveformEffect.Builder 的几个句柄一次性缓存下来。
     * 任何一步失败都只是 available 保持 false，不影响其他功能。
     */
    private fun initLinearMotor(ctx: Context) {
        runCatching {
            val svc = ctx.getSystemService(SERVICE_LINEARMOTOR) ?: return@runCatching

            // 服务实例的真实类（优先按 ROM 声明名取，取不到就用运行时类）
            val cls = runCatching { Class.forName(CLS_LINEARMOTOR) }.getOrNull()
                ?.takeIf { it.isInstance(svc) } ?: svc.javaClass

            // 有马达才继续；方法缺失时按「有」处理
            val has = runCatching {
                cls.getMethod("hasLinearMotorVibrator").invoke(svc) as? Boolean
            }.getOrNull()
            if (has == false) return@runCatching

            val effectCls = Class.forName(CLS_WAVEFORM_EFFECT)
            val builderCls = Class.forName(CLS_WAVEFORM_BUILDER)

            val vib = cls.getMethod("vibrate", effectCls)
            val ctor = builderCls.getConstructor()
            val setType = builderCls.getMethod("setEffectType", Integer.TYPE)
            val build = builderCls.getMethod("build")

            ebSetStrength = runCatching {
                builderCls.getMethod("setEffectStrength", Integer.TYPE)
            }.getOrNull()
            ebSetLoop = runCatching {
                builderCls.getMethod("setEffectLoop", java.lang.Boolean.TYPE)
            }.getOrNull()

            lmService = svc
            lmVibrate = vib
            ebCtor = ctor
            ebSetType = setType
            ebBuild = build
        }
    }

    /**
     * 华为 VibratorEx：类反射 + 实例 + `setHwVibrator(String)`，并对两个表冠效果名
     * 逐个做 `isSupportHwVibrator` 能力确认（照 uikit HwVibrateUtil 流程）。
     * 任何一步失败都只是通道不在位，fire 自动落到系统 TICK。
     */
    private fun initHuaweiVibrator() {
        runCatching {
            val cls = Class.forName(CLS_HW_VIBRATOR)
            val svc = cls.newInstance()
            val isSupport = cls.getMethod("isSupportHwVibrator", String::class.java)
            val set = cls.getMethod("setHwVibrator", String::class.java)
            for (name in listOf(HW_CROWN_SLOW, HW_CROWN_FAST)) {
                val ok = runCatching { isSupport.invoke(svc, name) as? Boolean }.getOrNull()
                if (ok == true) hwSupported += name
            }
            if (hwSupported.isEmpty()) return@runCatching
            hwService = svc
            hwSet = set
        }
    }

    // ------------------------------------------------------------------ 表冠刻度

    /**
     * 表冠刻度震动。由 Rotary.kt 的两条输入通道（RotaryScrollEvent / Scroll 指针事件）
     * 在**乘灵敏度之前**调用，deltaPx 为原始像素增量。
     *
     * 内部按 [detentPx] 累积取整：走满一个物理格 → 震一记。
     * 力度分两套口径（2026-10-03）：OPPO 线性马达 = 慢转 STRONG / 连转 LIGHT（原手感）；
     * 非 OPPO = 对齐华为音乐，逐格固定 strength3（详见函数内取证注释）。
     */
    fun crownDetent(deltaPx: Float) {
        if (deltaPx == 0f || !deltaPx.isFinite()) return

        accum += deltaPx
        val steps = (accum / detentPx).toInt()   // 截断，未满一格不震
        if (steps == 0) return

        val now = System.currentTimeMillis()
        // ★ 2026-10-03 非 OPPO 对齐华为音乐（用户口径「列表滑动表冠震动频率参考这个软件」）：
        //   反编译 com.huawei.music.wear 取证 ——
        //   · HwVibrateUtil 常量：HWVIBRATE_CROWN_STRENGTH1=10 / 2=11 / 3=12，
        //     对应 "watchhaptic.crown.strength1/2/3"；
        //   · HwRecyclerView.a(Z) → HwVibrateUtil.vibratorEx(this, z ? 12 : 10, 0)
        //     = 正常滚动用 **strength3（强）**、到列表尽头才用 strength1（轻）；
        //   · HwGenericEventDetector.<init>：stepValue 默认 0，SDK≥26 回退用
        //     ViewConfiguration.getScaledVerticalScrollFactor()（= 本文件 detentPx 同一当量）；
        //     sensitivityMode 默认 1 → sensitivity 1.0。
        //   ⇒ 官方口径 = 「一格一记、固定强度、不做快转降级」，且**没有**拆帧保险丝。
        //   本项目的「快转降 LIGHT + 18ms 保险丝」是 OPPO 手感调出来的，照搬到非 OPPO 上
        //   会让快转时掉格、连转变轻 —— 正是两边频率/力度不一致的来源。故非 OPPO 走官方口径：
        //   不用保险丝、不用快转降级，逐格一记 strength3。
        //   ⚠ OPPO 线性马达那条（isColorOsWatch=true）一个字不动。
        val alignHwMusic = !com.netmusiclite.data.DeviceCompat.isColorOsWatch
        if (!alignHwMusic && now - lastFireMs < CROWN_FUSE_MS) return

        accum -= steps * detentPx
        if (abs(accum) > detentPx * 4f) accum = 0f   // 异常抖动兜底

        val fast = !alignHwMusic && now - lastDetentMs < CROWN_FAST_MS
        lastFireMs = now
        lastDetentMs = now
        fire(if (fast) CROWN_STRENGTH_FAST else CROWN_STRENGTH_SLOW)
    }

    // ------------------------------------------------------------------ 触发

    /**
     * 列表「卡片冒头」一记震动（★ 2026-10-23 用户口径「震动强度不变，震动发生逻辑改成
     * 每个卡片冒头就震动」）。
     *
     * 力度**与表冠滚动完全同档**（[CROWN_STRENGTH_SLOW]：OPPO 线性马达走 EFFECT 302 +
     * STRENGTH_STRONG，华为走 watchhaptic.crown.strength3，其余落系统通道），所以观感上
     * 只是「什么时候震」变了，手感的轻重不变。
     *
     * 触发时机由调用方决定（主页瀑布流每张新卡片进入视口时调一次，见 M3eHomeFeed）。
     * 复用 [crownDetent] 的保险丝口径：18ms 内最多一记 —— 快速滑动时一帧可能连过好几张卡，
     * 不挡的话会变成一长串连珠炮，反而像卡顿。
     */
    fun cardReveal() {
        val now = System.currentTimeMillis()
        if (now - lastFireMs < CROWN_FUSE_MS) return
        lastFireMs = now
        lastDetentMs = now
        fire(CROWN_STRENGTH_SLOW)
    }

    private fun fire(strength: Int) {
        if (fireLinearMotor(strength)) return
        if (fireHuawei(strength)) return
        fireSystem()
    }

    /** 华为 VibratorEx：通道在位即接管（系统「触感反馈」关闭时静默不震，也不再落系统 TICK）；
     *  通道不在位 / 该效果名不支持 → false，交给系统降级 */
    private fun fireHuawei(strength: Int): Boolean {
        val svc = hwService ?: return false
        val set = hwSet ?: return false
        val name = if (strength == CROWN_STRENGTH_FAST) HW_CROWN_FAST else HW_CROWN_SLOW
        if (name !in hwSupported) return false
        if (!hwHapticFeedbackEnabled()) return true
        return runCatching { set.invoke(svc, name); true }.getOrDefault(false)
    }

    /** 系统「触感反馈」开关（官方 HwVibrateUtil 同款判据：关了就不震）；读不到按开启处理 */
    private fun hwHapticFeedbackEnabled(): Boolean {
        val c = appCtx ?: return true
        return runCatching {
            android.provider.Settings.System.getInt(
                c.contentResolver,
                android.provider.Settings.System.HAPTIC_FEEDBACK_ENABLED, 1,
            ) != 0
        }.getOrDefault(true)
    }

    /** OPPO 线性马达：成功返回 true，失败（无服务/反射异常）返回 false 交给降级 */
    private fun fireLinearMotor(strength: Int): Boolean {
        val svc = lmService ?: return false
        val ctor = ebCtor ?: return false
        val setType = ebSetType ?: return false
        val build = ebBuild ?: return false
        val vib = lmVibrate ?: return false
        return runCatching {
            var b: Any = ctor.newInstance()
            b = setType.invoke(b, CROWN_EFFECT_TYPE) ?: b
            ebSetStrength?.invoke(b, strength)
            ebSetLoop?.invoke(b, java.lang.Boolean.FALSE)
            val effect = build.invoke(b) ?: return@runCatching false
            vib.invoke(svc, effect)
            true
        }.getOrDefault(false)
    }

    /** 降级：普通转子马达的清脆一记（OPPO/华为通道都不在位的机型） */
    private fun fireSystem() {
        val v = vibrator ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val effect = cachedTickEffect
                    ?: VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                        .also { cachedTickEffect = it }
                v.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(12L)
            }
        }
    }
}
