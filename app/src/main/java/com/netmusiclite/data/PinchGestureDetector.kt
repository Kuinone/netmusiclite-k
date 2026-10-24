package com.netmusiclite.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 前台版「双击捏合」识别（实验性）。
 *
 * 触发条件固定为**两次快速捏合**；方向由当时的手心朝向决定：
 * - 手心朝下（手表屏幕朝上）→ [Direction.NEXT]
 * - 手心朝上（手表屏幕朝下）→ [Direction.PREVIOUS]
 * - 手腕竖起 / 屏幕朝侧面 → [Direction.PAUSE]
 *
 * ## 处理链
 *
 * ```
 * ACC + GYRO 100Hz ──► 重力低通（判手心朝向）
 *                   └─► 运动分数 = |gyro| + k·|linearAcc|
 *                             │
 *                     双 burst 状态机（自适应基线）
 *                             │
 *              两个 burst 80–650ms 内 → 双击
 *              单个 burst 内含两个峰 → 双击（兜底）
 * ```
 *
 * 上一版识别率过低的原因是把门槛压在「完整腕部旋转段」上：主轴占比、首尾姿态、
 * 积分转角任何一项不过都丢弃。这一版只把 burst 当作候选，真正的判据是**双峰**
 * 和**手心朝向**，因此对轻捏、半程动作的容忍度高得多。
 */
class PinchGestureDetector(
    context: Context,
    private val sensitivity: Int,
    private val onDoublePinch: (Direction) -> Unit,
) : SensorEventListener {

    enum class Direction { PREVIOUS, NEXT, PAUSE }

    companion object {
        private const val TAG = "PinchGesture"

        /** 100 Hz：双击捏合的单次动作可能只有几十毫秒，50Hz 会丢峰 */
        private const val SAMPLE_PERIOD_US = 10_000
        private const val BUFFER_NS = 2_500_000_000L

        /** 低于触发阈值后再安静这么久，认为一个 burst 结束 */
        private const val RELEASE_QUIET_NS = 60_000_000L
        private const val WARMUP_NS = 400_000_000L
        private const val ACC_FRESH_NS = 60_000_000L
        private const val GAP_RESET_NS = 120_000_000L
        private const val MIN_PEAK_SEPARATION_NS = 80_000_000L
        private const val ORIENTATION_LOG_NS = 10_000_000_000L

        /** |gz|/|g| 超过该值判为手心朝上/朝下，否则算手腕竖起 */
        private const val PALM_ZONE = 0.50f

        fun hasSensors(context: Context): Boolean {
            val sm = context.applicationContext
                .getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
            return sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null &&
                sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null
        }
    }

    private data class Params(
        val triggerFloor: Float,
        val baselineFactor: Float,
        val releaseRatio: Float,
        val minBurstMs: Long,
        val maxBurstMs: Long,
        val minGapMs: Long,
        val maxGapMs: Long,
        val refractoryMs: Long,
        val accWeight: Float,
        val gateEnabled: Boolean = false,
        val gatePeak: Float = 0f,
        val gateDurMs: Long = 0,
        val gateTa40Ms: Long = 0,
        val gateN40: Int = 0,
        val gatePs40Ms: Long = 0,
        val internalSpanMs: Long = 0,
    )

    private data class Sample(val t: Long, val x: Float, val y: Float, val z: Float)
    private data class ScoreSample(val t: Long, val value: Float)

    private data class Burst(
        val startNs: Long,
        val endNs: Long,
        val peak: Float,
        val gyroPeak: Float,
        val linPeak: Float,
        val gz: Float,
        val direction: Direction,
    )

    private data class Shape(val ta40Ms: Long, val n40: Int, val ps40Ms: Long)

    private val params: Params = when (sensitivity) {
        GesturePrefs.SENSITIVITY_STANDARD -> Params(
            triggerFloor = 0.80f, baselineFactor = 4.0f, releaseRatio = 0.55f,
            minBurstMs = 35, maxBurstMs = 800, minGapMs = 100, maxGapMs = 600,
            refractoryMs = 700, accWeight = 0.55f,
            gateEnabled = true, gatePeak = 1.6f, gateDurMs = 100,
            gateTa40Ms = 250, gateN40 = 2, gatePs40Ms = 100, internalSpanMs = 200,
        )
        GesturePrefs.SENSITIVITY_EXTREME -> Params(
            triggerFloor = 0.22f, baselineFactor = 2.1f, releaseRatio = 0.60f,
            minBurstMs = 15, maxBurstMs = 1000, minGapMs = 70, maxGapMs = 700,
            refractoryMs = 800, accWeight = 0.85f,
        )
        else -> Params(
            // 2026-10-24 用户口径：灵敏档门控阈值在上一版基础上再下调 5%；触发线保持 0.42。
            triggerFloor = 0.42f, baselineFactor = 2.8f, releaseRatio = 0.58f,
            minBurstMs = 25, maxBurstMs = 900, minGapMs = 80, maxGapMs = 650,
            refractoryMs = 750, accWeight = 0.70f,
            gateEnabled = true, gatePeak = 1.235f, gateDurMs = 68,
            gateTa40Ms = 123, gateN40 = 2, gatePs40Ms = 68, internalSpanMs = 205,
        )
    }

    private val sensorManager = context.applicationContext
        .getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val main = Handler(Looper.getMainLooper())

    private val acc = ArrayDeque<Sample>()
    private val gyro = ArrayDeque<Sample>()
    private val scores = ArrayDeque<ScoreSample>()

    private var running = false
    private var startNs = 0L
    private var lastGyroNs = 0L
    private var lastAccNs = 0L
    private var lastLin = 0f
    private var lastOrientationLogNs = 0L

    private var gx = 0f
    private var gy = 0f
    private var gz = 0f
    private var gravityReady = false
    private var baseline = 0.08f

    private var active = false
    private var burstStartNs = 0L
    private var quietSinceNs = 0L
    private var burstPeak = 0f
    private var burstGyroPeak = 0f
    private var burstLinPeak = 0f
    private var burstGz = 0f
    private var burstDirection = Direction.PAUSE

    private var pendingBurst: Burst? = null
    private var refractoryUntilNs = 0L

    fun start(): Boolean {
        if (running) return true
        val sm = sensorManager ?: return false
        val acc = accSensor ?: return false
        val gyr = gyroSensor ?: return false
        val okAcc = sm.registerListener(this, acc, SAMPLE_PERIOD_US)
        val okGyr = sm.registerListener(this, gyr, SAMPLE_PERIOD_US)
        if (!okAcc || !okGyr) {
            sm.unregisterListener(this)
            Log.w(TAG, "sensor register failed acc=$okAcc gyro=$okGyr")
            return false
        }
        reset()
        running = true
        Log.i(TAG, "listening @100Hz, sensitivity=$sensitivity trigger=${params.triggerFloor}")
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager?.unregisterListener(this)
        reset()
        Log.i(TAG, "stopped")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        val s = Sample(event.timestamp, event.values[0], event.values[1], event.values[2])
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                acc.addLast(s)
                trim(s.t)
                lastAccNs = s.t
                updateGravity(s)
                lastLin = vectorMag(s.x - gx, s.y - gy, s.z - gz)
            }
            Sensor.TYPE_GYROSCOPE -> {
                gyro.addLast(s)
                trim(s.t)
                if (startNs == 0L) startNs = s.t
                val gyroMag = vectorMag(s.x, s.y, s.z)
                val lin = if (lastAccNs != 0L && s.t - lastAccNs <= ACC_FRESH_NS) lastLin else 0f
                val score = gyroMag + params.accWeight * lin
                scores.addLast(ScoreSample(s.t, score))
                step(s.t, score, gyroMag)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun updateGravity(s: Sample) {
        if (!gravityReady) {
            gx = s.x
            gy = s.y
            gz = s.z
            gravityReady = true
            return
        }
        // 动作期间放慢更新：burst 开始时的朝向才是用户摆好的手心方向
        val alpha = if (active) 0.008f else 0.03f
        gx += alpha * (s.x - gx)
        gy += alpha * (s.y - gy)
        gz += alpha * (s.z - gz)
    }

    private fun step(now: Long, score: Float, gyroMag: Float) {
        if (lastGyroNs != 0L && now - lastGyroNs > GAP_RESET_NS) {
            if (active) Log.d(TAG, "gap reset ${(now - lastGyroNs) / 1_000_000}ms")
            active = false
            pendingBurst = null
            quietSinceNs = 0L
            scores.clear()
        }
        lastGyroNs = now

        if (now - startNs < WARMUP_NS) {
            updateBaseline(score)
            return
        }

        val pending = pendingBurst
        if (pending != null && now - pending.endNs > (params.maxGapMs + 250) * 1_000_000L) {
            pendingBurst = null
        }

        if (!active) {
            updateBaseline(score)
            val trigger = triggerValue()
            if (now >= refractoryUntilNs && score >= trigger) {
                active = true
                burstStartNs = now
                quietSinceNs = 0L
                burstPeak = score
                burstGyroPeak = gyroMag
                burstLinPeak = if (lastAccNs != 0L) lastLin else 0f
                burstGz = gravityZNorm()
                burstDirection = directionOf(burstGz)
                Log.d(
                    TAG,
                    "burst start score=${f(score)} trigger=${f(trigger)} " +
                        "gz=${f(burstGz)} dir=$burstDirection",
                )
            }
            logOrientation(now)
            return
        }

        if (score > burstPeak) burstPeak = score
        if (gyroMag > burstGyroPeak) burstGyroPeak = gyroMag
        if (lastAccNs != 0L && lastLin > burstLinPeak) burstLinPeak = lastLin

        val release = triggerValue() * params.releaseRatio
        if (score < release) {
            if (quietSinceNs == 0L) quietSinceNs = now
            if (now - quietSinceNs >= RELEASE_QUIET_NS) {
                active = false
                finishBurst(quietSinceNs)
                return
            }
        } else {
            quietSinceNs = 0L
        }

        if (now - burstStartNs > params.maxBurstMs * 1_000_000L) {
            active = false
            finishBurst(now)
        }
    }

    private fun finishBurst(endNs: Long) {
        val durationMs = (endNs - burstStartNs) / 1_000_000L
        if (durationMs < params.minBurstMs) {
            Log.d(TAG, "drop short ${durationMs}ms peak=${f(burstPeak)}")
            return
        }
        if (durationMs > params.maxBurstMs) {
            Log.d(TAG, "drop long ${durationMs}ms peak=${f(burstPeak)}")
            return
        }

        val burst = Burst(
            startNs = burstStartNs,
            endNs = endNs,
            peak = burstPeak,
            gyroPeak = burstGyroPeak,
            linPeak = burstLinPeak,
            gz = burstGz,
            direction = burstDirection,
        )
        Log.i(
            TAG,
            "burst dur=${durationMs}ms peak=${f(burst.peak)} gyro=${f(burst.gyroPeak)} " +
                "lin=${f(burst.linPeak)} gz=${f(burst.gz)} dir=${burst.direction}",
        )

        val shape = shapeMetrics(burst.startNs, burst.endNs, burst.peak)
        if (params.gateEnabled) {
            val gateOk = durationMs >= params.gateDurMs &&
                burst.peak >= params.gatePeak &&
                shape.ta40Ms >= params.gateTa40Ms &&
                shape.n40 >= params.gateN40 &&
                shape.ps40Ms >= params.gatePs40Ms
            if (!gateOk) {
                Log.d(
                    TAG,
                    "gate reject peak=${f(burst.peak)} ta40=${shape.ta40Ms}ms " +
                        "n40=${shape.n40} ps40=${shape.ps40Ms}ms",
                )
                return
            }
        }
        val internal = if (params.gateEnabled) {
            shape.n40 >= 2 && shape.ps40Ms >= params.internalSpanMs
        } else {
            countInternalPeaks(burst.startNs, burst.endNs) >= 2
        }
        if (internal) {
            Log.i(
                TAG,
                "double inside one burst: n40=${shape.n40} ps40=${shape.ps40Ms}ms " +
                    "ta40=${shape.ta40Ms}ms",
            )
            fire(burst.direction, burst.gz)
            return
        }

        val first = pendingBurst
        if (first == null) {
            pendingBurst = burst
            return
        }

        val gapMs = (burst.startNs - first.endNs) / 1_000_000L
        val spanMs = (burst.endNs - first.startNs) / 1_000_000L
        if (gapMs in params.minGapMs..params.maxGapMs &&
            spanMs <= params.maxBurstMs + params.maxGapMs
        ) {
            // 两次朝向一致就以第一次为准；不一致时取动作更明显的那次，避免把调整手腕的
            // 小动作当成用户摆好的方向。
            val useFirst = first.direction == burst.direction || first.peak >= burst.peak
            val dir = if (useFirst) first.direction else burst.direction
            val z = if (useFirst) first.gz else burst.gz
            Log.i(
                TAG,
                "DOUBLE gap=${gapMs}ms span=${spanMs}ms " +
                    "dir=${first.direction}(first)/${burst.direction}(second) -> $dir",
            )
            fire(dir, z)
        } else {
            Log.d(TAG, "pair rejected gap=${gapMs}ms span=${spanMs}ms")
            pendingBurst = burst
        }
    }

    private fun shapeMetrics(startNs: Long, endNs: Long, peak: Float): Shape {
        val seg = scores.filter { it.t in startNs..endNs }
        if (seg.size < 5 || peak <= 0f) return Shape(0, 0, 0)
        val threshold = peak * 0.40f
        val ta40Ms = seg.count { it.value >= threshold } * (SAMPLE_PERIOD_US / 1000L)
        val smoothed = FloatArray(seg.size) { i ->
            val a = seg[max(0, i - 1)].value
            val b = seg[i].value
            val c = seg[min(seg.lastIndex, i + 1)].value
            (a + b + c) / 3f
        }
        val merged = ArrayList<Pair<Long, Float>>()
        for (i in 1 until seg.size - 1) {
            val v = smoothed[i]
            if (v < threshold) continue
            if (v < smoothed[i - 1] || v < smoothed[i + 1]) continue
            val at = seg[i].t
            if (merged.isNotEmpty() && at - merged.last().first < 50_000_000L) {
                if (v > merged.last().second) merged[merged.lastIndex] = at to v
            } else {
                merged.add(at to v)
            }
        }
        val spanMs = if (merged.size >= 2) {
            (merged.last().first - merged.first().first) / 1_000_000L
        } else {
            0L
        }
        return Shape(ta40Ms, merged.size, spanMs)
    }

    /**
     * 一个 burst 内部是否已经包含两个峰。快速连捏时两次动作可能没有完全回落到
     * release 阈值，状态机只会产出一个 burst —— 这里按峰间回落幅度再兜一层。
     */
    private fun countInternalPeaks(startNs: Long, endNs: Long): Int {
        val seg = scores.filter { it.t in startNs..endNs }
        if (seg.size < 6) return 0

        val smoothed = FloatArray(seg.size)
        for (i in seg.indices) {
            val a = seg[max(0, i - 1)].value
            val b = seg[i].value
            val c = seg[min(seg.lastIndex, i + 1)].value
            smoothed[i] = (a + b + c) / 3f
        }

        val minPeak = max(params.triggerFloor * 1.05f, params.triggerFloor + 0.03f)
        var count = 0
        var lastPeakAt = 0L
        var lastPeakValue = 0f

        for (i in 1 until seg.size - 1) {
            val v = smoothed[i]
            if (v < minPeak) continue
            if (v < smoothed[i - 1] || v < smoothed[i + 1]) continue

            val at = seg[i].t
            if (count > 0 && at - lastPeakAt < MIN_PEAK_SEPARATION_NS) {
                if (v > lastPeakValue) {
                    lastPeakValue = v
                    lastPeakAt = at
                }
                continue
            }

            if (count == 0) {
                count = 1
                lastPeakValue = v
                lastPeakAt = at
                continue
            }

            var dip = Float.MAX_VALUE
            for (j in seg.indices) {
                if (seg[j].t in lastPeakAt..at) dip = min(dip, smoothed[j])
            }
            if (dip <= min(lastPeakValue, v) * 0.72f) {
                count++
                lastPeakValue = v
                lastPeakAt = at
            } else if (v > lastPeakValue) {
                lastPeakValue = v
            }
        }
        return count
    }

    private fun fire(direction: Direction, gzNorm: Float) {
        active = false
        quietSinceNs = 0L
        pendingBurst = null
        refractoryUntilNs = lastGyroNs + params.refractoryMs * 1_000_000L
        Log.i(TAG, "FIRE dir=$direction gz=${f(gzNorm)}")
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onDoublePinch(direction)
        } else {
            main.post { onDoublePinch(direction) }
        }
    }

    private fun updateBaseline(score: Float) {
        val ceiling = params.triggerFloor * 2.2f
        if (score < ceiling) baseline += 0.02f * (score - baseline)
        baseline = baseline.coerceIn(0.01f, ceiling)
    }

    private fun triggerValue(): Float =
        max(params.triggerFloor, min(baseline * params.baselineFactor, params.triggerFloor * 4f))

    private fun gravityZNorm(): Float {
        if (!gravityReady) return 0f
        val m = vectorMag(gx, gy, gz)
        return if (m < 1f) 0f else gz / m
    }

    private fun directionOf(zNorm: Float): Direction = when {
        // 屏幕朝上 = 手心朝下；屏幕朝下 = 手心朝上（Android 加速度计静止读数为 +g）
        zNorm >= PALM_ZONE -> Direction.NEXT
        zNorm <= -PALM_ZONE -> Direction.PREVIOUS
        else -> Direction.PAUSE
    }

    private fun logOrientation(now: Long) {
        if (now - lastOrientationLogNs < ORIENTATION_LOG_NS) return
        lastOrientationLogNs = now
        val z = gravityZNorm()
        Log.d(TAG, "orientation gz=${f(z)} dir=${directionOf(z)}")
    }

    private fun reset() {
        acc.clear()
        gyro.clear()
        scores.clear()
        startNs = 0L
        lastGyroNs = 0L
        lastAccNs = 0L
        lastLin = 0f
        lastOrientationLogNs = 0L
        gravityReady = false
        gx = 0f
        gy = 0f
        gz = 0f
        baseline = 0.08f
        active = false
        burstStartNs = 0L
        quietSinceNs = 0L
        burstPeak = 0f
        burstGyroPeak = 0f
        burstLinPeak = 0f
        burstGz = 0f
        burstDirection = Direction.PAUSE
        pendingBurst = null
        refractoryUntilNs = 0L
    }

    private fun trim(now: Long) {
        val cut = now - BUFFER_NS
        while (acc.isNotEmpty() && acc.first().t < cut) acc.removeFirst()
        while (gyro.isNotEmpty() && gyro.first().t < cut) gyro.removeFirst()
        while (scores.isNotEmpty() && scores.first().t < cut) scores.removeFirst()
    }

    private fun vectorMag(x: Float, y: Float, z: Float): Float =
        sqrt(x * x + y * y + z * z)

    private fun f(v: Float): String = ((v * 100f).roundToInt() / 100f).toString()
}
