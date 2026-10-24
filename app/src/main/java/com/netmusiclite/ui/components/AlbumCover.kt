package com.netmusiclite.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.netmusiclite.data.Song
import com.netmusiclite.data.LocalMusic
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.ui.theme.TextTertiary

/**
 * 歌曲封面数据源（SongCover / RotatingCover / 取色共用）：
 * 设备本地 Uri → 已下载落盘封面（断网/清缓存后仍可显示）→ 网络 URL（Coil 磁盘缓存兜底）。
 * 读 DownloadStore.states（可观察态）：下载完成的一瞬，在屏的封面即重组切到本地文件。
 * 普通函数（非 @Composable）：组合期调用会注册快照依赖；LaunchedEffect 里也能直接调。
 */
fun songCoverModel(song: Song?): Any? {
    song ?: return null
    val localAudioUri = LocalMusic.localUri(song)
    if (localAudioUri != null) {
        // 本地歌曲：coverUrl 字段装的是音频文件 Uri（不是封面地址）。
        // 封面从音频内嵌提取（LocalCoverStore 落盘缓存，可观察态就绪即重组显示）；
        // 无内嵌封面时返回 null → 走兜底生成封面。
        com.netmusiclite.data.LocalCoverStore.ensure(song.id, localAudioUri)
        com.netmusiclite.data.LocalCoverStore.coverFileIfReady(song.id)?.let { return it }
        return null
    }
    if (com.netmusiclite.data.DownloadStore.states[song.id] == 2) {
        com.netmusiclite.data.DownloadStore.coverFile(song.id)?.let { return it }
    }
    return song.coverUrl
}

/** 通用封面图：网络封面 / 本地文件或 Uri / 兜底生成封面 */
@Composable
fun CoverImage(url: Any?, size: Dp, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(10.dp)) {
    val str = url as? String
    val realUrl = str?.takeIf { it.isNotEmpty() && !it.startsWith("local://") }
    if (realUrl != null) {
        val ctx = LocalContext.current
        AsyncImage(
            // 快速滚动卡顿修复：请求级 crossfade(180) 会覆盖全局 crossfade(false)，
            // 每张新封面加载完都跑 180ms 淡入动画（每卡一个动画协程 + 每帧 alpha 重绘），
            // 快滚时几十个淡入并行。改为继承全局设置：直接显示，无逐帧动画。
            model = ImageRequest.Builder(ctx).data(realUrl).size(size.value.toInt() * 2).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )
    } else if (url is java.io.File || url is android.net.Uri) {
        // 本地文件/Uri：磁盘读取免网络，无需显式 size（按布局约束解码）
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )
    } else {
        val seed = (str ?: "ncm").hashCode()
        SongCoverArt(seed, size, modifier, shape)
    }
}

@Composable
fun SongCover(song: Song, size: Dp, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(10.dp)) {
    CoverImage(songCoverModel(song), size, modifier, shape)
}

/** 每歌固定配色的生成封面（无网络/无封面时兜底） */
@Composable
fun SongCoverArt(seed: Int, size: Dp, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(10.dp)) {
    if (com.netmusiclite.data.AppearancePrefs.isM3e) {
        Box(modifier.size(size).clip(shape).background(androidx.wear.compose.material3.MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            androidx.wear.compose.material3.Icon(NcmIcons.Note, null, Modifier.size(size / 3), tint = androidx.wear.compose.material3.MaterialTheme.colorScheme.onPrimaryContainer)
        }
        return
    }

    val hues = listOf(0xFF1C1C2E, 0xFF2E1C24, 0xFF14232E, 0xFF232E14, 0xFF2E2014)
    val accents = listOf(0xFF534AB7, 0xFFB74A5C, 0xFF3E7CB8, 0xFF7CB83E, 0xFFB8863E)
    val i = (seed % hues.size + hues.size) % hues.size
    val bg1 = Color(hues[i])
    val bg2 = Color(accents[i]).copy(alpha = 0.55f)
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Brush.linearGradient(listOf(bg2, bg1))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(NcmIcons.Note, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(size / 3))
    }
}

/** 旋转封面：播放时转动，暂停即停。
 *  W5 流畅度（2026-09-30 二次降帧）：30fps → 15fps —— 每帧合成 = 整棵 RenderNode 树重新提交，
 *  实测播放页静止动画帧中位 13ms（GPU 12ms），全页合成就是每帧成本的大头；62dp 小圆 15fps
 *  旋转肉眼无感（每 4 帧步进 0.4°，角速度不变 12°/s），静止合成负载再砍半。
 *  切歌过渡（2026-09-25）：旧封面沿屏幕 X 轴滑出、新封面从右侧滑入（350ms 同步滑动）。
 *  方向镜像（2026-10-02）：读 [PlayerEngine.skipDirection] —— 下一首新封面从右进、旧向右出；
 *  上一首则完全镜像（新从左进、旧向左出），不再上下首同向。
 *  旋转 graphicsLayer 放在滑动子项内部——滑动方向始终是屏幕坐标系，与当前旋转角无关；
 *  旋转角跨歌保持不重置；同专辑连播（封面相同）不触发动画 */
/** 旋转封面的视觉步进周期（≈15fps）。见 [RotatingCover] 里的耗电说明 */
private const val ROTATE_TICK_MS = 66L

@Composable
fun RotatingCover(song: Song?, size: Dp, playing: Boolean, modifier: Modifier = Modifier) {
    var angle by remember { mutableFloatStateOf(0f) }
    // ★ 2026-10-18 耗电：把「逐帧空转」换成「定时驱动」。
    //   旧实现是 `while(true){ withFrameNanos{}; if(frame%4==0) angle += 0.4f }`：
    //   视觉确实是 15fps，但帧回调是 60/90fps —— 播放期间应用每秒被唤醒 60~90 次去跑
    //   一个空 lambda，帧时钟永不空闲、CPU 进不了浅睡（页面看着静止，电流下不来）。
    //   改成 delay 后：唤醒 15 次/秒，且每次都是真要重绘的那一帧。
    //   角速度与旧版在 60Hz 屏上完全一致（60/4 = 15 次/秒 × 0.4° = 6°/s），观感零变化。
    androidx.compose.runtime.LaunchedEffect(song?.id, playing) {
        if (!playing) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(ROTATE_TICK_MS)
            angle = (angle + 0.4f) % 360f
        }
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        // 封面源随歌解析（已下载用落盘文件，未下载走网络 URL）；同专辑/同源不触发滑动动画
        val coverModel = songCoverModel(song)
        AnimatedContent(
            targetState = coverModel,
            transitionSpec = {
                // 方向镜像：next（+1）新封面自右侧滑入、旧封面向左退出（原行为）；
                // prev（-1）整体取反 —— 与新封面「来自上一首的方向」一致。
                if (PlayerEngine.skipDirection >= 0) {
                    slideInHorizontally(tween(350)) { it } togetherWith
                        slideOutHorizontally(tween(350)) { -it }
                } else {
                    slideInHorizontally(tween(350)) { -it } togetherWith
                        slideOutHorizontally(tween(350)) { it }
                }
            },
            label = "coverSlide",
        ) { model ->
            // 旋转只作用于封面内容本身：滑动过渡不受旋转角影响，始终沿屏幕 X 轴左出右进
            Box(Modifier.size(size).graphicsLayer { rotationZ = angle }) {
                CoverImage(model, size, Modifier, CircleShape)
            }
        }
    }
}
