package com.netmusiclite.ui.components

import android.view.ViewConfiguration
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import com.netmusiclite.data.RotaryTuning
import com.netmusiclite.ui.m3e.adaptiveDispatchRawDelta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sign

/**
 * 表冠 → LazyList 动画滚动 + 惯性滑行 + 可选吸附。
 *
 * ColorOS 手表的表冠走标准 Android 旋入式输入（SOURCE_ROTARY_ENCODER /
 * MotionEvent.AXIS_SCROLL，Compose onRotaryScrollEvent 的 verticalScrollPixels
 * 已按系统 verticalScrollFactor 缩放），无 OPPO 专用 API。
 *
 * ★ 平滑管道（v4 速度域，2026-10-11。历史：v1 逐事件 dispatchRawDelta 跳变禁用 →
 *   v2 定长 80ms tween 段间速度不连续 → v3 位置趋近，停转交接/逐格台阶仍有断层）：
 * 1) 事件 → 冠速估计（EMA）+ 位置伺服（保总位移 1:1）→ 速度低通 → 逐帧积分 dispatch。
 *    速度曲线全程连续：转越快滚越快、停手平滑收尾；模型细节/调参见 RotaryPipeline KDoc；
 * 2) 双通道输入：RotaryScrollEvent（表冠标准通道）+ PointerEventType.Scroll
 *    （通用滚轮通道，按系统 scaledVerticalScrollFactor 换算像素）统一进管道，
 *    Initial 消费阶段先于 LazyColumn 内建滚轮处理；
 * 3) 触感（2026-10-01 重接）：两条输入通道在**乘灵敏度之前**把原始像素增量交给
 *    NcmHaptics.crownDetent()，由它按「一个物理格 = scaledVerticalScrollFactor 像素」
 *    累积取整，每格震一记。震动走 OPPO 私有线性马达（effectType 302，
 *    见 NcmHaptics.kt 头注），非 OPPO 机型自动降级为系统 EFFECT_TICK。
 *    ⚠ 只在输入侧调用一次，勿在 pipeline/fling/snap 里补震——惯性滑行不震；
 * 4) 惯性（2026-09-04 真机要求保留）：停手后 |冠速| ≥ 450px/s 才滑行，应用速度从
 *    **当前实际速度**指数衰减——起点连续无突跳（阈值/时长见 GLIDE_* 常量）；
 *    滑行中再次转冠立即打断，滑到列表尽头自动停；
 * 5) 吸附：snap=true 时惯性结束后自动吸附最近项居中（机制保留，当前调用点全 false）。
 *
 * 灵敏度：0.4/0.22 真机过快，0.05 初版偏慢，按要求调至 0.5。
 * （动画管道不改变每格总位移，只改平滑度）
 * 一键禁用：ROTARY_ENABLED 改回 false，走 rotaryDisabled() 空挂载，各调用点无需改动。
 *
 * ★ 华为（2026-10-03）：每格像素当量与 ColorOS 未必一致（华为 uikit 步长口径
 *   32.526×density，见 HwGenericEventDetector.setStepValue），单列 HUAWEI_ROTARY_SENSITIVITY；
 *   真机试完转冠快慢只改那一个常量，ColorOS 的 0.5 一个字不动。
 */
const val ROTARY_ENABLED = true
/** 0.5 → 0.55（2026-10-10 用户要求：表冠灵敏度 +10%） */
const val ROTARY_SENSITIVITY = 0.55f

/** 华为手表专用灵敏度：先与 ColorOS 同值出包，真机标定后只改这里（增大 = 每格转得更多）。
 *  生效点 = rotaryList 的默认参数（见 effectiveRotarySensitivity），机型判定集中在 DeviceCompat。 */
const val HUAWEI_ROTARY_SENSITIVITY = 0.5f

/** 当前机型生效灵敏度：华为手表 → 华为常量；其它 → 原 0.5（ColorOS 手感零变化） */
fun effectiveRotarySensitivity(): Float =
    if (com.netmusiclite.data.DeviceCompat.isHuaweiWatch) HUAWEI_ROTARY_SENSITIVITY
    else ROTARY_SENSITIVITY
private const val SNAP_DELAY_MS = 140L

/** v4 冠速估计 EMA 时间常数 ms（事件速率 → 冠速；越小越跟手、越大越平） */
private const val RATE_TAU_MS = 120f

/** 距上次事件超过此值 → 下一个事件按「新手势」直接取速率（久停后重新起手） */
private const val RATE_NEW_GESTURE_MS = 250L

/** 新手势速率的 dt 上限 ms：久停后第一格 dt 超大时避免初速趋 0、起步过软 */
private const val RATE_NEW_GESTURE_DT_MS = 500L

/** 转动段位置伺服时间常数 ms：把「未兑现位移」换成速度。只负责总位移 1:1，不制造台阶 */
private const val SERVO_TAU_MS = 120f

/** 收尾段伺服时间常数 ms（停手且不滑行时把差值平滑走完再停） */
private const val SETTLE_TAU_MS = 120f

/** 应用速度低通时间常数 ms —— 逐格速度台阶变平滑曲线的关键 */
private const val VEL_TAU_MS = 60f

/** 转动段伺服瞬时项限幅：vDes ≤ |冠速| + 每格像素×此值（≈每格 2.6 格/秒的额外冲量）。
 *  逐格"一冲一冲" → 调小；慢转响应发肉 → 调大 */
private const val SERVO_CAP_DETENTS_PER_SEC = 2.6f

/** 限幅下限 px/s（极低灵敏度/小像素当量机型兜底） */
private const val SERVO_CAP_MIN = 120f

/** 停手判定窗 ms：最后事件后超过此值未再进事件 → 进入滑行/收尾 */
private const val IDLE_AFTER_MS = 110L

/** 滑行门槛 px/s（作用在冠速估计上）：≥ 才滑行 —— 对齐 v3 实机有效门槛
 *  （v3 速度估计在慢/中速的判定窗里被清零过，实测只有事件间隔 ≲120ms 才滑，
 *  即 ≈450px/s；慢转/单格收尾即停、不滑，保手感不变） */
private const val GLIDE_MIN_VELOCITY = 450f

/** 滑行衰减时间常数 ms：滑行距离 ≈ 初速 × 此值（快转 95ms/格 ≈ 260px，随 v3 的 300px） */
private const val GLIDE_TAU_MS = 550f

/** 管道退出速度阈值 px/s：低于此值且差值走完 → 收尾退出 */
private const val STOP_VELOCITY = 12f

/** 吸附动画：短促缓出，贴合 ColorOS 卡片吸附手感 */
private val ROTARY_SNAP_SPEC = tween<Float>(durationMillis = 160, easing = FastOutSlowInEasing)

/** 屏蔽表冠：只吞表冠事件，不做任何事；触摸手势（点击/拖动）不受影响。
 *  供需要禁用表冠的页面显式挂载（如音量页）；rotaryList/rotaryCustom 在
 *  ROTARY_ENABLED=false 时也回退到它 */
fun Modifier.rotaryDisabled(): Modifier = this
    .onRotaryScrollEvent { true }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                if (e.type == PointerEventType.Scroll) e.changes.forEach { it.consume() }
            }
        }
    }

/**
 * 表冠兜底分发器（2026-10-03，华为 Watch 4 / 鸿蒙 4）。
 *
 * 背景（诊断包实测取证）：华为把表冠投成标准 `ACTION_SCROLL` + `SOURCE_ROTARY_ENCODER`
 * 的通用运动事件，依次命中 Root/Activity 的 `dispatchGenericMotionEvent` 与
 * `onGenericMotionEvent` —— 事件确实进了应用，但若**焦点链上没有旋入节点**（Compose 的
 * `onRotaryScrollEvent` 依赖焦点）就无人消费、冒泡到 `Activity.onGenericMotionEvent` 被丢弃，
 * 真机表现就是「所有界面转表冠都没反应」。
 *
 * 机制：屏幕上的 `rotaryList` / `rotaryCustom` 各自把「本组件」注册成当前界面的兜底处理器，
 * `MainActivity.onGenericMotionEvent` 收到**未被消费**的表冠事件后转发进来。因此各页面
 * 一行都不用改。
 *
 * ⚠ 对 ColorOS 等焦点正常的机型**零影响**：那里事件在 Compose 内已被消费，根本不会冒泡到
 * Activity，本分发器不会触发。后注册者生效（同一屏多个列表时以最后挂载的那个为准）。
 */
object RotaryFallback {
    private var token: Any? = null
    private var handler: ((Float) -> Boolean)? = null

    fun register(t: Any, h: (Float) -> Boolean) {
        token = t
        handler = h
    }

    /** 只在注册者仍是自己时清空，避免误清后注册的界面 */
    fun unregister(t: Any) {
        if (token === t) {
            token = null
            handler = null
        }
    }

    /** 转发一个表冠增量（像素口径）。返回 true = 已被当前界面消费 */
    fun deliver(deltaPx: Float): Boolean = handler?.invoke(deltaPx) ?: false
}

/**
 * 表冠增量管道 v4（速度域：冠速平滑跟随 + 位置伺服 + 连续惯性）。
 *
 * ★ v4 动机（2026-10-11 用户口径「优化表冠转动曲线，不要出现明显的断层感」）：
 *  v3 的「位置趋近」有两处结构性断层，真机可感、离线仿真复现：
 *  1) 转动中逐格速度台阶：每格 55px 跳进目标位置 → 应用速度瞬间从 ~250 跳 ~1000px/s，
 *     快转时速度曲线呈锯齿（仿真单帧 Δv 654~702）；
 *  2) 停转交接突跳：120ms 判定窗内趋近管道已把差值走完，实际速度降到 ~0.2×转速，
 *     窗口一到惯性却按「事件速度×0.9」起步（仿真交接 Δv 375~684）＝ 先几乎停住再猛窜。
 *
 * ★ v4 模型（速度全程连续，无交接突变）：
 *  · 转动（ACTIVE）：vDes = 冠速估计 vRate + 差值/SERVO_TAU（位置伺服，保 1:1 总位移）；
 *    vApp 以 VEL_TAU 低通逼近 vDes 后逐帧积分 dispatch —— 逐格输入不再形成速度台阶。
 *    伺服瞬时项限幅 CAP（≈每格 2.6 格/秒）：快转时冠速前馈主导、限幅不生效（平滑跟随）；
 *    慢转/单格时限幅压住冲量，避免每格一冲。
 *  · 停手（最后事件后 IDLE_AFTER_MS）：|vRate| ≥ GLIDE_MIN_VELOCITY → 滑行（GLIDE）：
 *    vApp 从**当前实际速度**指数衰减（起点天然连续，惯性无突跳）；低于门槛 → 收尾
 *    （SETTLE）：纯伺服把差值平滑走完再停（慢转/单格不滑行，与 v3 实机手感一致）。
 *  · 位移口径：ACTIVE/SETTLE 收敛到 target（转多少走多少，1:1）；GLIDE 按设计多走一段
 *    （惯性；到边自动停，见 consumed==0 处理）。
 *  · 调参旋钮（调手感只改常量，全部有注释）：SERVO_TAU_MS / VEL_TAU_MS（跟手↔绵滑）、
 *    SERVO_CAP_*（逐格脉冲感）、GLIDE_TAU_MS / GLIDE_MIN_VELOCITY（滑行长短与门槛）。
 *
 * 实测对照（离线仿真，同参数同事件流）：转动中单帧 Δv 654~702 → 67~97；
 * 停转交接 Δv 375~684 → 0；滑行距离同量级（快转 300→263px、超快 519→391px）；
 * 单格点击 v3 只走 ~82% 就急停、v4 走满 100%（~283ms 达 95%）。
 * （刻度震动在输入侧发出，本管道不触碰 NcmHaptics；滑行不震）
 */
/**
 * 表冠滚动活跃标志（2026-10-17 第五轮修复：m3e 滑动进度条「不认」表冠滚动、自动隐藏）。
 *
 * 为什么需要：Wear M3 的 ScreenScaffold 用 `ScrollInfoProvider.isScrollInProgress` 判断
 * 「是否在滚动」→ 据此显示滑动进度条、把 TimeText 卷走。Wear 原生 rotaryScrollable 走的是
 * scrollable 的 scroll 会话，`isScrollInProgress` 会为 true；而本文件的**自定义平滑管道**
 * 用非挂起的 `dispatchRawDelta` 逐帧推位移（没有 scroll 会话）⇒ `state.isScrollInProgress`
 * 恒 false ⇒ Scaffold 误判 Idle，进度条自动隐藏、时间文字也不卷走（真机反馈即此现象）。
 *
 * 这里维护一个全局「表冠最近在动」标志：管道在**第一帧输入 → 停稳收尾完成**期间置真；
 * m3e 的 ScrollInfoProvider 把它并进 `isScrollInProgress`（**只影响显示口径**，不参与滚动）。
 * 原生 rotaryScrollable 路径（谷歌/三星表）本来就会置真，不需要也不读它。
 * classic 主题的列表同样会更新这个标志，但 classic 的 provider 不读它 ⇒ 行为零变化。
 */
object RotaryScrollActivity {
    private val _active = mutableStateOf(false)

    /** 表冠是否正在驱动滚动（含停稳收尾阶段）。供 ScrollInfoProvider 的显示口径使用。 */
    val active: Boolean get() = _active.value

    internal fun begin() { _active.value = true }
    internal fun end() { _active.value = false }
}
private class RotaryPipeline(
    private val scope: CoroutineScope,
    /**
     * 把一帧位移应用到真实滚动状态，返回实际消费量（0 = 到边）。
     *
     * ★ 2026-10-17 第四轮：从「直接持有 LazyListState」泛化成回调 —— classic 传
     * `state.dispatchRawDelta`；m3e 传 `state.adaptiveDispatchRawDelta`（映射到真正的
     * TransformingLazyColumnState），瀑布流则直接传 gridState.dispatchRawDelta。
     * 管道内部逻辑（速度域模型、惯性、收尾）一字未动。
     */
    private val dispatch: (Float) -> Float,
    private val onSettled: (suspend () -> Unit)?,
) {
    private enum class Phase { ACTIVE, GLIDE, SETTLE }

    private var target = 0f    // 累计目标位移 px（输入需求；GLIDE 中随 applied 同步）
    private var applied = 0f   // 已应用位移 px（虚拟坐标；ACTIVE/SETTLE 收敛到 target）
    private var vApp = 0f      // 当前应用速度 px/s —— 跨阶段共享的连续状态（交接无突跳的根基）
    private var vRate = 0f     // 冠速估计 px/s（事件速率 EMA）
    private var eventMag = 55f // 每格像素当量（EMA，伺服限幅用）
    private var lastEmitMs = 0L
    private var phase = Phase.ACTIVE
    private var job: Job? = null
    private var snapJob: Job? = null

    fun emit(deltaPx: Float) {
        if (deltaPx == 0f) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            val dtE = if (lastEmitMs == 0L) Long.MAX_VALUE else now - lastEmitMs
            target += deltaPx
            vRate = if (dtE > RATE_NEW_GESTURE_MS) {
                // 久停后重新起手（含本管道首个事件）：直接取本次速率；dt 封顶避免初速趋 0
                deltaPx / (minOf(dtE, RATE_NEW_GESTURE_DT_MS).toFloat() / 1000f)
            } else {
                val dtc = dtE.coerceAtLeast(1L).toFloat()
                val inst = deltaPx / dtc * 1000f
                vRate + (inst - vRate) * (1f - exp(-dtc / RATE_TAU_MS))
            }
            eventMag += (abs(deltaPx) - eventMag) * 0.3f
            lastEmitMs = now
            phase = Phase.ACTIVE  // 滑行/收尾中接到新事件：立即切回转动（vApp 连续，无跳变）
        }
        snapJob?.cancel()
        snapJob = null
        if (job?.isActive != true) {
            job = scope.launch { runLoop() }
        }
    }

    private suspend fun runLoop() {
        var lastFrameMs = 0L
        while (true) {
            withFrameNanos { }  // 帧同步：每帧推进一次（帧率无关，dt 实测）
            val nowMs = System.currentTimeMillis()
            val dtMs = if (lastFrameMs == 0L) 16f else (nowMs - lastFrameMs).coerceIn(1L, 120L).toFloat()
            lastFrameMs = nowMs

            var step = 0f
            var finished = false
            synchronized(this@RotaryPipeline) {
                if (phase == Phase.ACTIVE && nowMs - lastEmitMs >= IDLE_AFTER_MS) {
                    phase = if (abs(vRate) >= GLIDE_MIN_VELOCITY) Phase.GLIDE else Phase.SETTLE
                }
                when (phase) {
                    Phase.ACTIVE -> {
                        // 冠速前馈 + 位置伺服（差值只负责 1:1，不制造速度台阶）；瞬时项限幅
                        val diff = target - applied
                        val vDes = vRate + diff / (SERVO_TAU_MS / 1000f)
                        val cap = maxOf(eventMag * SERVO_CAP_DETENTS_PER_SEC, SERVO_CAP_MIN)
                        val vLim = if (abs(vDes) > abs(vRate) + cap) {
                            sign(vDes) * (abs(vRate) + cap)
                        } else {
                            vDes
                        }
                        vApp += (vLim - vApp) * (1f - exp(-dtMs / VEL_TAU_MS))
                        step = vApp * dtMs / 1000f
                        applied += step
                    }
                    Phase.SETTLE -> {
                        // 慢转/单格停手：纯伺服把剩余差值走完再停（不滑行）
                        val vDes = (target - applied) / (SETTLE_TAU_MS / 1000f)
                        vApp += (vDes - vApp) * (1f - exp(-dtMs / VEL_TAU_MS))
                        step = vApp * dtMs / 1000f
                        applied += step
                        if (abs(target - applied) < 0.6f && abs(vApp) < STOP_VELOCITY) {
                            applied = target
                            vApp = 0f
                            finished = true
                        }
                    }
                    Phase.GLIDE -> {
                        // 惯性：从当前实际速度指数衰减（起点与停手瞬间连续）
                        vApp *= exp(-dtMs / GLIDE_TAU_MS)
                        step = vApp * dtMs / 1000f
                        applied += step
                        target = applied  // 滑行多走的部分并入需求，转冠恢复后 1:1 从当前位置继续
                        if (abs(vApp) < STOP_VELOCITY) {
                            vApp = 0f
                            finished = true
                        }
                    }
                }
            }
            if (abs(step) > 0.01f) {
                val consumed = dispatch(step)
                if (abs(step) > 1f && consumed == 0f) {
                    // 到边：丢弃未兑现位移并封印速度，避免反方向死区与滑行空转
                    synchronized(this@RotaryPipeline) {
                        applied = target
                        vApp = 0f
                        if (phase != Phase.ACTIVE) finished = true
                    }
                }
            }
            if (finished) {
                // 收尾瞬间若恰有新事件进来（emit 见 job 仍活、未重启），续跑一轮消化它
                val pending = synchronized(this@RotaryPipeline) { phase == Phase.ACTIVE }
                if (!pending) break
                finished = false
            }
        }
        onSettled?.let { settle ->
            snapJob = scope.launch {
                delay(SNAP_DELAY_MS)
                settle()
            }
        }
    }
}

/** 表冠滚动停稳后，把中心线最近的可见项吸附到视口中央 */
private suspend fun snapToNearestCenter(state: LazyListState) {
    val info = com.netmusiclite.ui.m3e.listLayoutInfo(state)
    val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    val target = info.visibleItemsInfo.minByOrNull {
        abs(it.offset + it.size / 2f - mid)
    } ?: return
    val delta = target.offset + target.size / 2f - mid
    if (abs(delta) > 1f) state.animateScrollBy(delta, ROTARY_SNAP_SPEC)
}

/** 系统滚轮刻度换算的像素系数（API 26+，本项目 minSdk 26 直取） */
private fun wheelScalePx(context: android.content.Context): Float =
    ViewConfiguration.get(context).scaledVerticalScrollFactor

/** onUserInput：任何非零表冠增量进管道前回调（歌词页用它暂停自动跟随），不参与 pipeline remember key */
fun Modifier.rotaryList(
    state: LazyListState,
    sensitivity: Float = effectiveRotarySensitivity(),
    snap: Boolean = false,
    onUserInput: (() -> Unit)? = null,
): Modifier {
    // ★ 2026-10-17 第四轮（m3e 表冠按设备分流）：m3e 主题下只有「谷歌/三星表」继续由调用方
    //   挂 Wear 原生 rotaryScrollable（那两家的标准旋入链路真机验证可用）；其他厂商
    //   （OPPO/华为等）**不再整体禁用**，而是复用本条平滑管道真正驱动滚动。
    //   classic 行为零变化：isM3e=false ⇒ 本判据恒 false，后面走的就是原有逻辑。
    if (com.netmusiclite.data.AppearancePrefs.isM3e &&
        com.netmusiclite.data.DeviceCompat.useNativeWearRotary
    ) {
        return this
    }
    return rotaryPipeline(
        state = state,
        sensitivity = sensitivity,
        onUserInput = onUserInput,
        onSettled = if (snap) {
            { snapToNearestCenter(state) }
        } else null,
    )
}

/**
 * 通用滚动容器的表冠平滑管道（2026-10-17 第四轮新增）。
 *
 * 与 [rotaryList] 同一套输入/震动/方向开关/RotaryFallback 兜底，只是没有 LazyListState
 * 专属的吸附（瀑布流等不需要），且 dispatch 直接落到传入的 [ScrollableState]：
 * · m3e 主页瀑布流：传 `LazyStaggeredGridState` ⇒ 管道真正驱动 waterfall 滚动，
 *   Material 的弧形进度条只读同一个 gridState 的真实进度做外观（空挂载，不喂假输入）；
 * · 谷歌/三星表不走这条（调用方仍挂 Wear 原生 rotaryScrollable）。
 */
fun Modifier.rotaryScrollableState(
    state: ScrollableState,
    sensitivity: Float = effectiveRotarySensitivity(),
    onUserInput: (() -> Unit)? = null,
    /**
     * 是否由本管道负责「每格一记」的表冠震动（默认 true，既有页面行为不变）。
     * ★ 2026-10-23 传 false 的场景：m3e 主页（用户口径「把现在主页滑动时的震动改为该页面
     *   表冠转动的震动，滑动时不再有震动」）—— 主页的震动改由页面自己按「卡片冒头」触发
     *   （NcmHaptics.cardReveal，且只在表冠输入窗口内），这里必须关掉逐格震动，否则会双震。
     */
    haptic: Boolean = true,
): Modifier {
    if (com.netmusiclite.data.AppearancePrefs.isM3e &&
        com.netmusiclite.data.DeviceCompat.useNativeWearRotary
    ) {
        return this
    }
    return rotaryPipeline(
        state = state,
        sensitivity = sensitivity,
        onUserInput = onUserInput,
        onSettled = null,
        haptic = haptic,
    )
}

/**
 * 表冠平滑管道本体（[rotaryList] / [rotaryScrollableState] 共用）。
 *
 * dispatch 口径：LazyListState 走 [com.netmusiclite.ui.m3e.adaptiveDispatchRawDelta] ——
 * classic 下等价于 `dispatchRawDelta`（行为一字不变）；m3e 下自动映射到真正的
 * TransformingLazyColumnState（见 M3eShared 的 nativeLists 弱映射），所以 OPPO/华为表上
 * m3e 的 Material 列表也能被默认主题这套平滑管道真正驱动。
 */
private fun Modifier.rotaryPipeline(
    state: ScrollableState,
    sensitivity: Float,
    onUserInput: (() -> Unit)?,
    onSettled: (suspend () -> Unit)?,
    /** 见 [rotaryScrollableState] 的同名参数：false = 本管道不再逐格震（调用方自己按业务时机震） */
    haptic: Boolean = true,
): Modifier {
    if (!ROTARY_ENABLED) return rotaryDisabled()
    return composed {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val wheelPx = remember(context) { wheelScalePx(context) }
        val inputCallback = rememberUpdatedState(onUserInput)
        val pipeline = remember(state, sensitivity) {
            RotaryPipeline(
                scope = scope,
                dispatch = { delta ->
                    if (state is LazyListState) {
                        state.adaptiveDispatchRawDelta(delta)
                    } else {
                        state.dispatchRawDelta(delta)
                    }
                },
                // 停在收尾完成（含 SNAP_DELAY）后才熄灭「表冠活跃」标志：
                // 期间 ScreenScaffold 保持 Scrolling（进度条不隐藏、TimeText 卷走）。
                onSettled = {
                    RotaryScrollActivity.end()
                    onSettled?.invoke()
                },
            )
        }
        // 兜底：焦点链拿不到表冠的机型（华为）由 Activity 把未消费的事件转发进来
        val fbToken = remember(pipeline) { Any() }
        DisposableEffect(fbToken) {
            RotaryFallback.register(fbToken) { raw ->
                if (raw == 0f) {
                    false
                } else {
                    RotaryScrollActivity.begin()
                    inputCallback.value?.invoke()
                    if (haptic) NcmHaptics.crownDetent(raw)
                    // ★ 列表页方向开关（设置 → 表冠适配）：在所有输入通道之后相乘，
                    //   故 Compose 通道与 Activity 兜底通道一并覆盖（震动不受符号影响）
                    pipeline.emit(raw * sensitivity * RotaryTuning.listSign)
                    true
                }
            }
            onDispose {
                RotaryFallback.unregister(fbToken)
                RotaryScrollActivity.end()
            }
        }
        this
            .onRotaryScrollEvent { e ->
                if (e.verticalScrollPixels != 0f) {
                    RotaryScrollActivity.begin()
                    inputCallback.value?.invoke()
                    if (haptic) NcmHaptics.crownDetent(e.verticalScrollPixels)
                }
                pipeline.emit(e.verticalScrollPixels * sensitivity * RotaryTuning.listSign)
                true
            }
            .pointerInput(pipeline) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Scroll) {
                            val dy = e.changes.fold(0f) { a, c -> a + c.scrollDelta.y }
                            if (dy != 0f) {
                                e.changes.forEach { it.consume() }
                                RotaryScrollActivity.begin()
                                inputCallback.value?.invoke()
                                val raw = dy * wheelPx
                                if (haptic) NcmHaptics.crownDetent(raw)
                                pipeline.emit(raw * sensitivity * RotaryTuning.listSign)
                            }
                        }
                    }
                }
            }
    }
}

/**
 * 表冠方向开关的作用域（见 data/RotaryTuning 的「设置 → 表冠适配」）：
 *  · [Volume] = 播放页转冠调音量 —— 用「音量页」那个开关；
 *  · [List]   = **除音量外的所有界面** —— 各类列表、向导翻卡等，用「列表页」那个开关。
 * 用户口径（2026-10-04）：「列表的反向是除了音量之外所有界面都生效」。
 */
enum class RotaryScope { List, Volume }

/**
 * 自由表冠回调（音量 / 向导翻卡等非列表场景）。
 * onDelta 拿原始像素增量（双通道已统一为像素口径）；震动由调用方按速度分级。
 *
 * @param scope 方向开关用哪一个 —— 音量页传 [RotaryScope.Volume]，其余界面保持默认。
 */
fun Modifier.rotaryCustom(
    scope: RotaryScope = RotaryScope.List,
    onDelta: (Float) -> Unit,
): Modifier {
    if (!ROTARY_ENABLED) return rotaryDisabled()
    return composed {
        val context = LocalContext.current
        val wheelPx = remember(context) { wheelScalePx(context) }
        val deltaState = rememberUpdatedState(onDelta)
        // 符号在**事件发生时**现读（而不是组合期捕获一次）：设置里改完，下一次转动立刻生效，
        // 也不必把 sign 塞进 pointerInput / DisposableEffect 的 key 去重建整条输入链
        val signNow = {
            if (scope == RotaryScope.Volume) RotaryTuning.volumeSign else RotaryTuning.listSign
        }
        // 兜底：同 rotaryList —— 焦点链拿不到表冠时由 Activity 转发（不参与 pointerInput key）
        val fbToken = remember { Any() }
        DisposableEffect(fbToken) {
            RotaryFallback.register(fbToken) { raw ->
                if (raw == 0f) {
                    false
                } else {
                    NcmHaptics.crownDetent(raw)
                    // ★ 方向开关（设置 → 表冠适配）：按 scope 取音量页或列表页符号
                    deltaState.value(raw * signNow())
                    true
                }
            }
            onDispose {
                RotaryFallback.unregister(fbToken)
                RotaryScrollActivity.end()
            }
        }
        this
            .onRotaryScrollEvent { e ->
                if (e.verticalScrollPixels != 0f) NcmHaptics.crownDetent(e.verticalScrollPixels)
                onDelta(e.verticalScrollPixels * signNow())
                true
            }
            .pointerInput(onDelta) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.type == PointerEventType.Scroll) {
                            val dy = e.changes.fold(0f) { a, c -> a + c.scrollDelta.y }
                            if (dy != 0f) {
                                e.changes.forEach { it.consume() }
                                val raw = dy * wheelPx
                                NcmHaptics.crownDetent(raw)
                                onDelta(raw * signNow())
                            }
                        }
                    }
                }
            }
    }
}
