package com.netmusiclite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.Bg
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import com.netmusiclite.ui.nav.NavMotionKind

/**
 * 大图头部：照片裁满 + 从透明渐变到主题底色。
 * 用于艺人页/歌单/专辑。
 *
 * ★ 2026-10-16 移除头图的高斯模糊（用户口径「模糊范围异常，三星表整屏发糊」）：
 *   原实现是 `Modifier.blur(26.dp)` —— 走 GPU 的 RenderEffect，只有 API 31+ 才真生效
 *   （本机 ColorOS Watch 是 API 30，那一行一直是空操作）。三星 One UI Watch（API 31+）
 *   上 RenderEffect 的模糊范围/离屏合成不按节点边界工作，模糊会溢出到相邻区域，
 *   且残留的 RenderEffect 图层会被系统的 AOD 合成一起带走 —— 一并移除，头图恢复清晰原图。
 *   底部的渐隐（DstIn 擦除）与钟形压暗层原样保留，文字可读性不受影响。
 *   （类名保留 `BlurHeader` 不动：全站多处调用点，改名只有噪声没有收益。）
 */
@Composable
fun BlurHeader(
    imageUrl: String?,
    title: String,
    subtitle: String? = null,
    height: Dp = 150.dp,
    avatarUrl: String? = null,
    avatarSize: Dp = 56.dp,
    actions: (@Composable () -> Unit)? = null,
) {
    Box(Modifier.fillMaxWidth().height(height)) {
        // ★ 2026-10-17 修「艺人主页渐变头图割裂」（用户口径）：**所有主题统一渐隐到透明**。
        //   旧实现在「无壁纸且非封面模糊外观」时走纯色模式：渐变末端 = SongAmbient.color
        //   （当前歌曲环境色，不透明）。但艺人页 / 歌单 / 专辑的页面底是全局背景
        //   （classic=Bg、m3e=纯黑 / 取色 / 封面模糊），SongAmbient 只对播放器组页面
        //   （AMBIENT_SURFACE_ROUTES）才是真实底色 —— 于是头图底边与页面底之间必然出现
        //   一道横向色界：浅色主题（深环境色 vs 浅底）与 m3e 默认纯黑背景最明显。
        //   现在统一走「DstIn 沿高度擦除到透明 + 钟形压暗」：头图底边完全透明，
        //   下方全局背景自然透出，任何主题下都不再有硬边。
        // 图像层（offscreen）：用 DstIn 沿高度擦除图像 alpha = 真渐隐
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Black,
                            0.5f to Color.Black.copy(alpha = 0.75f),
                            1f to Color.Transparent,
                        ),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                }
        ) {
            if (imageUrl != null) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                AsyncImage(
                    // 限定解码尺寸：头图最大 ~466px 宽，避免整张原封面进内存造成卡顿
                    model = coil.request.ImageRequest.Builder(ctx).data(imageUrl).size(520).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    // ★ 2026-10-16 不再对头图做 GPU 模糊（理由见上方 KDoc）：清晰原图 +
                    //   底部的 DstIn 渐隐 + 钟形压暗，观感与「重模糊」相比只是少了一层糊。
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // 无图占位：低对比渐变 + 尾部透明（旧版末端是不透明的 SongAmbient，
                // 正是纯色模式割裂的来源之一）
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF23233A).copy(alpha = 0.9f), Color.Transparent),
                        )
                    )
                )
            }
        }
        // ★ 钟形压暗（原渐隐分支口径，现全主题共用）：只压在**文字所在的中段**
        //   最暗（0.62 处 ~30%），向下到 1f 重新回到全透明 —— 头图底边与下方页面同亮度，
        //   不再出现一道深色横带/硬边；上端从 0.35 才开始渐入，也不会在图片上部留痕。
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Transparent,
                        0.35f to Color.Transparent,
                        0.62f to Color.Black.copy(alpha = 0.30f),
                        0.86f to Color.Black.copy(alpha = 0.12f),
                        1.00f to Color.Transparent,
                    )
                )
        )
        Column(
            Modifier.align(Alignment.BottomCenter).padding(horizontal = 24.dp).padding(bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (avatarUrl != null) {
                    CoverImage(avatarUrl, avatarSize, Modifier, CircleShape)
                    Spacer(Modifier.width(10.dp))
                }
                Column(horizontalAlignment = if (avatarUrl != null) Alignment.Start else Alignment.CenterHorizontally) {
                    Text(
                        title,
                        fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                        textAlign = TextAlign.Center, maxLines = 1,
                        // 轻投影：任何底图上都不糊（1dp 偏移 + 6px 模糊，肉眼只感到字更实）
                        style = HEADER_TEXT_SHADOW,
                    )
                    if (subtitle != null) {
                        Text(
                            subtitle, fontSize = 10.sp,
                            // 副标题从 70% 白提到 92% 白 + 同款投影：小字号抗锯齿后不再发灰
                            color = TextPrimary.copy(alpha = 0.92f), maxLines = 1,
                            style = HEADER_TEXT_SHADOW,
                        )
                    }
                }
            }
            if (actions != null) {
                Spacer(Modifier.height(8.dp))
                actions()
            }
        }
    }
}

private val BgElevatedBrush = Brush.verticalGradient(listOf(Color(0xFF23233A), Bg))

/** 头图文字投影（静态复用，避免每帧新建 TextStyle）：
 *  1dp 下偏移 + 6px 模糊 + 55% 黑，底图再亮也能压出字缘，视觉上只觉字更实、不显阴影 */
private val HEADER_TEXT_SHADOW = TextStyle(
    shadow = Shadow(
        color = Color.Black.copy(alpha = 0.55f),
        offset = Offset(0f, 1f),
        blurRadius = 6f,
    )
)

/** 艺人页 SVG 圆形操作按钮（无文字）：关注 / 加入一起听 */
@Composable
fun CircleSvgButton(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 21.dp,
    container: Color = Color.White.copy(alpha = 0.14f),
    tint: Color = TextPrimary,
    contentDesc: String = "",
    navMotionKind: NavMotionKind? = null,
    onClick: () -> Unit,
) {
    Pressable(onClick = onClick, modifier = modifier, navMotionKind = navMotionKind) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDesc, tint = tint, modifier = Modifier.size(iconSize))
        }
    }
}

/** 已关注态的实心红底 */
@Composable
fun CircleSvgButtonFilled(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = com.netmusiclite.ui.theme.OnAccent,
    navMotionKind: NavMotionKind? = null,
    onClick: () -> Unit,
) = CircleSvgButton(icon = icon, modifier = modifier, size = size,
    container = Accent, tint = tint, navMotionKind = navMotionKind, onClick = onClick)
