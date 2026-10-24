package com.netmusiclite.ui.m3e

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BrushPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.CardDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.Text
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.navigateWithMotion

/**
 * m3e 首页「每日推荐」下方的**满宽渐变卡**（★ 2026-10-18 用户口径
 * 「参考截图的配色添加，并且适配本地音乐卡片：功能要和经典模式一样，
 * 风格在 m3e 主题下要适配 m3e 组件」）。
 *
 * ## 功能（与经典模式那张完全一致）
 * 点击 → [Routes.LOCAL_MUSIC]。本地音乐页自己负责运行时权限与媒体库扫描，
 * 这张卡不读数据、不做预加载，所以「功能一样」= 同一个路由、同一个入口语义。
 *
 * ## 为什么用 m3e 的 Card + containerPainter
 * 参考图是一张满宽渐变大卡：左暖橙 → 明黄 → 右侧青绿，左下三行大字，底部标题 + 副文案。
 * m3e 的 `Card` 支持 `containerPainter(image, scrim)`（两层 Brush），首页上面那几张入口卡
 * 用的是同一套机制 —— 圆角 / 按压 / 滚动变换（`SurfaceTransformation`）/ 明暗 token
 * 全部继承 m3e 组件，不需要任何自绘图形。
 */
private val LocalCardGradient = listOf(
    Color(0xFFF7A62B), // 左：暖橙（参考图左上/左下）
    Color(0xFFF2C94C), // 中：明黄
    Color(0xFF7FD6A9), // 中右：薄荷
    Color(0xFF4FC8C0), // 右：青（参考图右半部分）
)
private val LocalCardTitleWarm = Color(0xFFFFF3C4)

/** 卡片在网格里的 key：滚动变换靠它反查位置，必须与 item(key = …) 一致 */
internal const val M3E_LOCAL_CARD_KEY = "home_local_card"

/**
 * @param modifier 由调用处传（在 LazyStaggeredGrid 的 item 作用域里才拿得到 `animateItem()`）
 */
@Composable
internal fun M3eLocalMusicCard(
    nav: NavHostController,
    interactive: Boolean,
    gridState: LazyStaggeredGridState,
    modifier: Modifier = Modifier,
) {
    val spec = rememberTransformationSpec()
    val transformation: SurfaceTransformation = m3eLocalCardTransformation(gridState, spec)
    val image = remember {
        BrushPainter(
            Brush.linearGradient(
                colors = LocalCardGradient,
                start = Offset.Zero,
                end = Offset.Infinite,
            ),
        )
    }
    // 顶部微光（白 12% → 透明）：压出参考图上半部分的雾面感，同时保住亮黄区域上白字的对比度
    val scrim = remember {
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = .12f),
            0.55f to Color.Transparent,
        )
    }
    // containerPainter 是 @Composable（内部读 MaterialTheme 的 token），不能塞进 remember
    val painter = CardDefaults.containerPainter(image = image, scrim = scrim)
    val ink = Color.White

    M3ePressSurface(modifier.fillMaxWidth(), enabled = interactive) {
        Card(
            onClick = { nav.navigateWithMotion(Routes.LOCAL_MUSIC) },
            containerPainter = painter,
            enabled = interactive,
            modifier = Modifier.fillMaxWidth().height(152.dp),
            colors = CardDefaults.cardWithContainerPainterColors(contentColor = ink, titleColor = ink),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
            transformation = transformation,
        ) {
            Text(
                "你的",
                fontSize = 15.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = LocalCardTitleWarm,
                maxLines = 1,
            )
            Text(
                "本地音乐",
                fontSize = 18.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = ink,
                maxLines = 1,
            )
            Text(
                "都在这里",
                fontSize = 18.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = ink.copy(alpha = .82f),
                maxLines = 1,
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "本地音乐",
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.Bold,
                color = ink,
                maxLines = 1,
            )
            Text(
                "扫描手表里的歌曲，离线也能听。",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 9.5.sp,
                lineHeight = 12.sp,
                color = ink.copy(alpha = .86f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 与首页其它卡片同一套滚动变换（key 固定成 [M3E_LOCAL_CARD_KEY]） */
@Composable
private fun m3eLocalCardTransformation(
    gridState: LazyStaggeredGridState,
    spec: TransformationSpec,
): SurfaceTransformation = rememberHomeGridTransformation(gridState, M3E_LOCAL_CARD_KEY, spec)
