package com.netmusiclite.ui.m3e

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.Song
import com.netmusiclite.ui.components.pressStretch
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.PlayerSeekOverlay
import com.netmusiclite.ui.components.PressMotion
import com.netmusiclite.ui.components.RotatingCover
import com.netmusiclite.ui.components.frostedGlass

/** Native controls fitted into the existing fixed-position player layout. */
@Composable
fun M3ePlayerControlButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    size: Dp = 44.dp,
    highlighted: Boolean = false,
) {
    if (com.netmusiclite.ui.components.playerCoverGlassMatchesBackground()) {
        // The outer spring supplies press feedback. A Material ripple/state-layer would add
        // a second bright overlay and invalidate the material's luminance bound while held.
        Box(
            Modifier.size(size).pressStretch()
                .frostedGlass(CircleShape, forceGlass = true)
                .border(0.6.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .16f), CircleShape)
                .clickable(interactionSource = remember { MutableInteractionSource() },
                    indication = null, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, description, Modifier.size(if (size < 40.dp) 20.dp else 26.dp),
                tint = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        }
        return
    }
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(size).pressStretch()
            .frostedGlass(CircleShape, forceGlass = true)
            .border(0.6.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .16f), CircleShape),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = Color.Transparent,
            contentColor = if (highlighted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Icon(icon, description, Modifier.size(if (size < 40.dp) 20.dp else 26.dp))
    }
}

/**
 * 中央封面键。
 *
 * ★ 2026-10-17 用户 bug「material 主题下播放页封面周围的一圈颜色有锯齿感」：
 *   旧实现是 FilledIconButton(68dp) 的原色容器 + 62dp 圆形封面 —— 一圈 3dp 的 primary 环
 *   实际上是**按钮容器的硬裁剪**（Modifier.clip(CircleShape) 走 canvas clipPath，不开
 *   抗锯齿），于是环的外沿是一圈像素台阶；封面自己被旋转图层裁剪的那个圆边压在环上，
 *   边缘同样是台阶。
 *
 * 现在改成「自绘环 + 被环盖住的封面」：
 *   · 封面按 64dp 圆形硬裁剪（半径 32dp），它产生的锯齿边**完全落在外圈描边下面**；
 *   · 环用 Canvas 的 drawCircle(style = Stroke) 画 —— Skia 描边是抗锯齿的，
 *     内外两条边都是渐变过渡，不再有台阶；
 *   · 描边中心半径 = 34 − 1.5 = 32.5dp，覆盖半径 31→34dp，正好压住封面边缘，
 *     可视封面半径仍是 31dp（与默认主题的 62dp 封面一致）。
 * 按压/长按改为自绘（detectTapGestures + 缩放，与默认主题 PauseButton 同一套参数），
 * 不再依赖按钮容器的裁剪，因此长按唤出进度条、短按暂停的语义原样保留。
 */
@Composable
fun M3ePlayerCoverButton(playing: Boolean, song: Song?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    var pressed by remember { mutableStateOf(false) }
    var longPressFired by remember { mutableStateOf(false) }
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) PressMotion.SCALE else 1f,
        animationSpec = PressMotion.spec(pressed),
        label = "m3eCoverPress",
    )
    Box(
        Modifier
            .size(68.dp)
            .graphicsLayer {
                scaleX = com.netmusiclite.ui.components.PressMotion.stretchX(pressScale)
                scaleY = com.netmusiclite.ui.components.PressMotion.stretchY(pressScale)
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        longPressFired = false
                        // Success(Unit) = 短按抬起；Success(null) = 按住满 500ms（长按成立）
                        val result = runCatching<Unit?> {
                            kotlinx.coroutines.withTimeoutOrNull(500L) { awaitRelease() }
                        }
                        pressed = false
                        if (result.isSuccess && result.getOrNull() == null) {
                            longPressFired = true
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            PlayerSeekOverlay.show()
                        }
                    },
                    onTap = { if (!longPressFired) onClick() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(64.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
            RotatingCover(song, 64.dp, playing)
            if (!playing && !PlayerEngine.loading) {
                Box(
                    Modifier.size(64.dp).background(scheme.background.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(NcmIcons.Play, "播放", Modifier.size(27.dp), tint = scheme.onSurface)
                }
            }
            if (PlayerEngine.loading) CircularProgressIndicator(Modifier.size(28.dp))
        }
        Canvas(Modifier.matchParentSize()) {
            val stroke = 3.dp.toPx()
            drawCircle(
                color = scheme.primary,
                radius = size.minDimension / 2f - stroke / 2f,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
    }
}
