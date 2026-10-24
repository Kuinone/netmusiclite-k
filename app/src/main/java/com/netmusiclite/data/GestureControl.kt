package com.netmusiclite.data

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.mutableStateOf

/**
 * 实验性手势操控：生命周期、动作映射与设置页展示状态。
 *
 * - 只有「开关打开 + 至少一个 Activity resumed」时才注册传感器；退后台/息屏立即注销。
 * - 触发固定为双击捏合；方向由 [PinchGestureDetector] 根据手心朝向给出。
 * - 上一首 / 下一首 / 暂停继续 都由双击触发，没有单击动作。
 */
object GestureControl {
    private const val TAG = "GestureControl"

    private var appContext: Context? = null
    private var detector: PinchGestureDetector? = null
    private var resumedActivities = 0

    private val runningState = mutableStateOf(false)
    private val lastGestureState = mutableStateOf("尚未识别到双指捏一捏")

    /** 设置页展示用：当前是否真的在采传感器 */
    val isRunning: Boolean get() = runningState.value

    /** 设置页展示用：最近一次识别结果（即使当前没有歌曲也会更新） */
    val lastGesture: String get() = lastGestureState.value

    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        if (app is Application) app.registerActivityLifecycleCallbacks(lifecycle)
        Log.i(
            TAG,
            "init, enabled=${GesturePrefs.enabled} sensitivity=${GesturePrefs.sensitivity}",
        )
    }

    /** 返回最终是否开启；传感器缺失或注册失败时会自动保持关闭。 */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (enabled && !PinchGestureDetector.hasSensors(context)) {
            Log.w(TAG, "accelerometer/gyroscope missing; keep disabled")
            GesturePrefs.setEnabled(false)
            sync()
            return false
        }
        GesturePrefs.setEnabled(enabled)
        sync()
        Log.i(TAG, "enabled=$enabled running=${detector != null}")
        return enabled
    }

    /** 切换灵敏度；识别器在跑时立即按新参数重建。 */
    fun setSensitivity(level: Int) {
        GesturePrefs.setSensitivity(level)
        if (detector != null) {
            detector?.stop()
            detector = null
            runningState.value = false
            if (GesturePrefs.enabled && resumedActivities > 0) startDetector()
        }
        Log.i(TAG, "sensitivity=${GesturePrefs.sensitivity}")
    }

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            resumedActivities++
            sync()
        }

        override fun onActivityPaused(activity: Activity) {
            resumedActivities = (resumedActivities - 1).coerceAtLeast(0)
            if (resumedActivities == 0) sync()
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    private fun sync() {
        val shouldRun = GesturePrefs.enabled && resumedActivities > 0
        if (shouldRun) {
            if (detector == null) startDetector()
        } else {
            detector?.stop()
            detector = null
            runningState.value = false
        }
    }

    private fun startDetector() {
        val ctx = appContext ?: return
        val d = PinchGestureDetector(ctx, GesturePrefs.sensitivity) { onDoublePinch(it) }
        if (d.start()) {
            detector = d
            runningState.value = true
            Log.i(TAG, "detector running (foreground)")
        } else {
            runningState.value = false
            Log.w(TAG, "detector start failed; switch off")
            GesturePrefs.setEnabled(false)
        }
    }

    private fun onDoublePinch(direction: PinchGestureDetector.Direction) {
        val label = when (direction) {
            PinchGestureDetector.Direction.PREVIOUS -> "上一首 · 手心朝上"
            PinchGestureDetector.Direction.NEXT -> "下一首 · 手心朝下"
            PinchGestureDetector.Direction.PAUSE -> "暂停/继续 · 手腕竖起"
        }
        val hasCurrent = PlayerEngine.current != null
        val hasQueue = PlayerEngine.queue.isNotEmpty()
        val hasPlayable = hasCurrent || hasQueue
        lastGestureState.value = if (hasPlayable) label else "$label · 当前无歌曲"
        Log.i(TAG, "gesture=$direction hasCurrent=$hasCurrent hasQueue=$hasQueue")

        if (!hasPlayable) return
        when (direction) {
            PinchGestureDetector.Direction.PREVIOUS -> {
                if (!hasQueue) return
                PlayerEngine.prev()
                Log.i(TAG, "previous")
            }
            PinchGestureDetector.Direction.NEXT -> {
                if (!hasQueue) return
                PlayerEngine.next()
                Log.i(TAG, "next")
            }
            PinchGestureDetector.Direction.PAUSE -> {
                if (!hasCurrent) return
                val wasPlaying = PlayerEngine.isPlaying
                if (wasPlaying) PlayerEngine.pauseAction() else PlayerEngine.playAction("gesture")
                Log.i(TAG, if (wasPlaying) "paused" else "playing")
            }
        }
    }
}
