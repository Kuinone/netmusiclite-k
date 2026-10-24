package com.netmusiclite.ui.screens.recognize

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import com.netmusiclite.data.NcmApi
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.NavMotionKind
import com.netmusiclite.ui.nav.NavMotionSpec
import com.netmusiclite.ui.nav.TransitionCoordinator
import com.netmusiclite.ui.nav.navMotionSource
import com.netmusiclite.ui.nav.navigateWithMotion
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.AccentSoft
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import kotlinx.coroutines.delay
import java.io.File

/**
 * 听歌识曲：录音 8s → 调用网易官方音频指纹接口识别。
 * 识别到 → 展示 歌名/艺人，一键带词跳搜索；未识别 → 保留手动搜索出口。
 */
@Composable
fun MusicRecognizeScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    var phase by remember { mutableStateOf("idle") } // idle / recording / identifying / found / done / denied
    var countdown by remember { mutableStateOf(8) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var foundArtist by remember { mutableStateOf("") }
    var foundTitle by remember { mutableStateOf("") }
    var recognizeButtonCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var resultMotion by remember { mutableStateOf(NavMotionSpec(NavMotionKind.Directional)) }
    // 2026-10-01：识别失败的具体原因（指纹提取 / 接口 code / 未匹配），直接显示给用户便于定位
    var failReason by remember { mutableStateOf("") }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording(ctx, { phase = it }, { countdown = it }, { rec -> recorder = rec })
        else phase = "denied"
    }

    fun begin() {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startRecording(ctx, { phase = it }, { countdown = it }, { rec -> recorder = rec })
        else permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // 录音计时 + 停止后进入识别
    LaunchedEffect(phase) {
        if (phase == "recording") {
            while (countdown > 0) {
                delay(1000)
                countdown -= 1
            }
            runCatching { recorder?.stop() }
            runCatching { recorder?.release() }
            recorder = null
            phase = "identifying"
        }
    }

    // 请求网易识曲接口；识别成功直接跳搜索结果列表
    // 2026-10-01 修复：接口改为安卓客户端口径（NcmApi.recognize 内部 eaFlt 主路），
    // 并把失败原因带到 UI（此前统一吞成 null，用户只看到「暂未识别到歌曲」无从判断）。
    LaunchedEffect(phase) {
        if (phase == "identifying") {
            when (val r = NcmApi.recognize(File(ctx.cacheDir, "recognize.m4a"), ctx)) {
                is NcmApi.RecognizeResult.Found ->
                    nav.navigateWithMotion(Routes.searchQ(r.artist + " " + r.title), resultMotion)
                is NcmApi.RecognizeResult.Failure -> {
                    failReason = r.reason
                    phase = "done"
                }
            }
        }
    }

    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "s",
    )

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("听歌识曲", fontSize = 15.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(22.dp))
            Box(
                Modifier
                    .size(120.dp)
                    .onGloballyPositioned { recognizeButtonCoordinates = it }
                    .scale(if (phase == "recording") scale else 1f)
                    .clip(CircleShape)
                    .background(if (phase == "recording") AccentSoft else Accent.copy(alpha = 0.18f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        if (phase != "recording" && phase != "identifying") {
                            resultMotion = TransitionCoordinator.specFromSource(
                                NavMotionKind.Circle,
                                recognizeButtonCoordinates,
                            )
                            begin()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(88.dp).clip(CircleShape).background(Accent.copy(alpha = 0.30f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(NcmIcons.Recognize, contentDescription = "识别",
                        tint = TextPrimary, modifier = Modifier.size(40.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                when (phase) {
                    "idle" -> "点击开始识别"
                    "recording" -> "正在聆听… $countdown s"
                    "identifying" -> "识别中，成功后自动进入结果"
                    "done" -> if (failReason.isNotEmpty()) "暂未识别到歌曲\n$failReason"
                              else "暂未识别到歌曲\n可重试或手动搜索"
                    "denied" -> "需要麦克风权限"
                    else -> ""
                },
                fontSize = 10.sp, color = if (phase == "found") TextPrimary else TextSecondary,
                textAlign = TextAlign.Center,
            )
            if (phase == "done" || phase == "denied") {
                Spacer(Modifier.height(12.dp))
                Text(
                    "去搜索", fontSize = 12.sp, color = Accent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Accent.copy(alpha = 0.14f))
                        .navMotionSource(
                            NavMotionKind.Pill,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { nav.navigateWithMotion(Routes.SEARCH) }
                        .padding(horizontal = 18.dp, vertical = 7.dp),
                )
            }
        }
    }
}

private fun startRecording(
    ctx: android.content.Context,
    setPhase: (String) -> Unit,
    setCountdown: (Int) -> Unit,
    setRecorder: (MediaRecorder?) -> Unit,
) {
    runCatching {
        val mr = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else
            @Suppress("DEPRECATION") MediaRecorder()
        mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        mr.setAudioSamplingRate(44100)
        mr.setAudioEncodingBitRate(128000)
        mr.setOutputFile(File(ctx.cacheDir, "recognize.m4a").absolutePath)
        mr.prepare()
        mr.start()
        setRecorder(mr)
        setCountdown(8)
        setPhase("recording")
    }.onFailure { setPhase("denied") }
}
