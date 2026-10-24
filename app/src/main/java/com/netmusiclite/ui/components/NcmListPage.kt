package com.netmusiclite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.netmusiclite.ui.m3e.AdaptiveLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netmusiclite.ui.theme.Bg
import com.netmusiclite.ui.theme.screenBg
import com.netmusiclite.ui.theme.SurfaceGlass
import com.netmusiclite.ui.theme.TextPrimary

/**
 * 统一圆屏列表骨架：
 * - 标题随滚动轻微放大缩小（弧形观感）
 * - 左上返回胶囊按钮（可选）
 * - 右缘弯曲进度条（progressTotal 未传时自动按列表实际条目数计算，不会退化成定值）
 * - 表冠滚动 + 震动
 */
@Composable
fun NcmListPage(
    /** 页内顶部标题（随滚动轻微缩放）。传 null / 空串则不渲染标题，直接进内容
     *  （2026-10-01 歌曲百科页要求「删除标题只留卡片」）。 */
    title: String? = null,
    onBack: (() -> Unit)? = null,
    progressTotal: Int = -1,
    bottomPad: Int = 86,
    /** 卡片左右各收窄的像素（圆屏边缘出血微调；按用户口径直译 px，内部转 dp） */
    horizontalInsetPx: Int = 0,
    /** m3e 下的列表形态（见 M3eListMode）；歌曲百科这类纯内容页要求「禁用堆叠」，传 SINK */
    m3eMode: com.netmusiclite.ui.m3e.M3eListMode = com.netmusiclite.ui.m3e.M3eListMode.WEAR,
    content: LazyListScope.() -> Unit,
) {
    if (com.netmusiclite.data.AppearancePrefs.isM3e) {
        com.netmusiclite.ui.m3e.M3eList(title = title, mode = m3eMode, content = content)
        return
    }
    val listState = rememberLazyListState()
    val insetDp = with(LocalDensity.current) { horizontalInsetPx.toFloat().toDp() }
    // 2026-10-01：与 StackedCardList 同口径的额外横向收窄 —— 圆屏上下弧区会切掉通宽卡片的
    // 左右上角，列表越长越明显（歌曲百科卡片此前只收 inset，滚动到弧区即被裁）。
    // 收窄 2.3%/边后，卡片角始终落在圆内，任何长度都不会被屏幕裁切。
    // ⚠ 用 screenWidthUiDp（UI 坐标系屏宽）：密度被 NcmTheme 等比缩放后，
    //   LocalConfiguration.screenWidthDp 不再等于屏宽，收窄量会算错（见 Common.kt 注释）
    val screenWidthDp = screenWidthUiDp().value
    val widthShrink = (screenWidthDp * 0.023f).dp

    val titleScale by remember(listState) {
        derivedStateOf {
            val off = com.netmusiclite.ui.m3e.listFirstOffset(listState).coerceIn(0, 160)
            1f + (1f - off / 160f) * 0.14f
        }
    }
    // 流畅度：字号随滚动逐帧变会每帧重排版标题文本；改固定 16sp + graphicsLayer 缩放（渲染层零重排版）
    val titleLayer = Modifier.graphicsLayer {
        val s = titleScale
        scaleX = s
        scaleY = s
    }
    val progress by remember(listState, progressTotal) {
        derivedStateOf {
            // 分母口径 = LazyColumn 实际条目数（含首尾 spacer/标题），与显式传值一致（缓存清理 = 4）；
            // 未传 progressTotal 的页面若固定 0.15f，弧线不随滚动（歌曲百科/免责声明/关于应用曾中招）
            val total = if (progressTotal > 1) progressTotal else com.netmusiclite.ui.m3e.listLayoutInfo(listState).totalItemsCount
            if (total <= 1) 0.15f
            else {
                val info = com.netmusiclite.ui.m3e.listLayoutInfo(listState)
                val visible = info.visibleItemsInfo
                if (visible.isEmpty()) 0.15f
                else {
                    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
                    val idx = visible.firstOrNull { it.offset <= center && it.offset + it.size >= center }?.index
                        ?: visible.first().index
                    val r = idx.toFloat() / (total - 1).coerceAtLeast(1)
                    r.coerceIn(0f, 1f)
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(screenBg())
            .rotaryList(listState),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = insetDp + widthShrink),
        ) {
            item(key = "spacer") { Box(Modifier.fillMaxWidth().height(50.dp)) }
            if (title != null && title.isNotEmpty()) {
                item(key = "heading") {
                    Text(
                        title,
                        fontSize = 16.sp,
                        color = TextPrimary,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(titleLayer)
                            .padding(bottom = 10.dp),
                    )
                }
            }
            content()
            item(key = "bottom") { Spacer(Modifier.height(bottomPad.dp)) }
        }

        // 返回改为全局左缘滑动（SwipeBackBox），不再渲染左上角按钮

        CircularSideProgress(progress = { progress }, modifier = Modifier.fillMaxSize())
    }
}

/** 歌词页等自绘列表也能用的进度估算 */
fun estimateProgress(state: LazyListState, total: Int): Float {
    if (total <= 1) return 0.15f
    val info = com.netmusiclite.ui.m3e.listLayoutInfo(state)
    val visible = info.visibleItemsInfo
    if (visible.isEmpty()) return 0.15f
    val center = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    val idx = visible.firstOrNull { it.offset <= center && it.offset + it.size >= center }?.index
        ?: visible.first().index
    return (idx.toFloat() / (total - 1)).coerceIn(0f, 1f)
}
