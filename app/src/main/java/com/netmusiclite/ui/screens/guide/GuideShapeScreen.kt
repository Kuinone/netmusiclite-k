package com.netmusiclite.ui.screens.guide

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netmusiclite.data.ScreenShapePrefs
import com.netmusiclite.ui.components.Pressable
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.OnAccent
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import com.netmusiclite.ui.theme.TextTertiary
import com.netmusiclite.ui.theme.screenBg

/**
 * 向导第一页（2026-10-03 新增，用户口径「首次启动的时候向导第一个页面改成方表圆表选择，
 * svg 绘制，左右两个选项」）：**表盘形状选择**。
 *
 * ── 为什么需要用户手动选 ───────────────────────────────────────────────
 * 整套 UI 的几何全部按「圆表」标定：根布局 `clip(CircleShape)`、通宽控件按弧边内缩
 * （circleSafeInsetPx）、玻璃卡按圆形可视区收窄。原实现只靠系统 SCREENROUND 配置位分流，
 * 但该位在国产方表 ROM / 鸿蒙手表 / 魔改 Wear OS 上并不可靠 —— 方表被报成圆屏就四角裁空，
 * 圆表被报成方屏就弧边内容不再内缩、两端被切。这里让用户**目视指定**一次，此后一切
 * 形状判定以它为准（见 data/ScreenShapePrefs 与 components/Common.kt 的 isRoundScreen）。
 *
 * ── 图形为什么是 Canvas 而不是 PNG ─────────────────────────────────────
 * 用 Compose 矢量绘制（等价于 SVG）：① 不增加 drawable 资源与安装包体积；
 * ② 描边色跟随主题强调色，选中态即换色，无需两套位图；③ 任意分辨率下零锯齿。
 * 表盘示意 = 卡片形状本身（方形卡 / 圆形卡）+ 卡内三条示意横线（列表内容），
 * 用户一眼就能把「卡片形状」和「表壳形状」对应起来。
 *
 * ── 选择即时生效 ──────────────────────────────────────────────────────
 * 点击即在 [ScreenShapePrefs] 落盘（Compose 状态）→ `isRoundScreen()` 订阅它 →
 * **整棵树立刻按新形状重组**：选「方表」本页当场铺满四角、选「圆表」当场恢复圆形裁切。
 * 用户不用重启就能看到自己选得对不对。
 *
 * ⚠ 点「下一步/完成」时若用户一次都没点过选项，也会把**当前预选值**落盘 ——
 *   否则 ScreenShapePrefs 仍是 AUTO（= 未选择），下次启动会重复弹本页，形成死循环。
 */
@Composable
fun GuideShapeScreen(
    onNext: () -> Unit,
    /** 主按钮文案：向导流程用「下一步」；设置页入口复用本页时传「完成」（2026-10-03）。 */
    confirmText: String = "下一步",
) {
    val mode = ScreenShapePrefs.mode
    // 预选：已选过 → 回显用户的选择；没选过（首次启动）→ 预选系统判定结果。
    // 预选只是 UI 高亮，用户不点它就不落盘（点「下一步」时才落，见下方 onNext）。
    val sysRound = LocalConfiguration.current.isScreenRound
    var selectedRound by remember(sysRound) {
        mutableStateOf(
            when (mode) {
                ScreenShapePrefs.MODE_ROUND -> true
                ScreenShapePrefs.MODE_SQUARE -> false
                else -> sysRound
            },
        )
    }

    fun pick(round: Boolean) {
        selectedRound = round
        ScreenShapePrefs.setMode(
            if (round) ScreenShapePrefs.MODE_ROUND else ScreenShapePrefs.MODE_SQUARE,
        )
    }

    Box(
        Modifier.fillMaxSize().background(screenBg()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "选择表盘形状",
                fontSize = 12.sp, lineHeight = 15.sp,
                fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "影响界面边缘与圆角适配",
                fontSize = 8.5.sp, lineHeight = 11.sp,
                color = TextTertiary, maxLines = 1, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            // 左右两个选项（用户口径）：左 = 方表，右 = 圆表
            Row(
                horizontalArrangement = Arrangement.spacedBy(15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShapeOption(round = false, label = "方表", selected = !selectedRound) { pick(false) }
                ShapeOption(round = true, label = "圆表", selected = selectedRound) { pick(true) }
            }
            Spacer(Modifier.height(12.dp))
            Pressable(onClick = {
                // 没点过选项就直接确认 → 把预选值落盘（否则 mode 仍是 AUTO = 未选择，
                // 下次启动会重复弹本页）。已点过时 setMode 幂等，无副作用。
                if (!ScreenShapePrefs.chosen) {
                    ScreenShapePrefs.setMode(
                        if (selectedRound) ScreenShapePrefs.MODE_ROUND
                        else ScreenShapePrefs.MODE_SQUARE,
                    )
                }
                onNext()
            }) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Accent)
                        .padding(horizontal = 32.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(confirmText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OnAccent)
                }
            }
        }
    }
}

/** 单个形状选项：卡片外形 = 表壳形状（方形卡 / 圆形卡），卡内三条示意横线代表界面内容。 */
@Composable
private fun ShapeOption(
    round: Boolean,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // 卡片形状即表盘形状：方形卡用圆角矩形、圆形卡用正圆 —— 用户不必读文字就能对上号
    val shape = if (round) CircleShape else RoundedCornerShape(22.dp)
    // ★ 选中/未选中的对比度（2026-10-03 真机截图复核后两次加强）：
    //   初版未选中也填 SurfaceStrong（浅色主题黑 6%），与选中态的 Accent 14% 填充几乎同色，
    //   两个选项看起来「都选中了」。现在未选中**不填充** + 描边压淡到 45%，
    //   选中态填 Accent 22% + 2dp Accent 描边 + 不透明 Accent 横线 + Accent 粗体标签。
    //   为什么强调色偏淡时也要能分辨：自动取色打开时 Accent 可能是低饱和的灰调
    //   （真机实测取到灰绿），只靠「填充有色/无色」不够 —— 描边粗细与标签字重是第二重保险。
    val borderW = if (selected) 2.dp else 1.dp
    val borderC = if (selected) Accent else TextTertiary.copy(alpha = 0.45f)
    val fill = if (selected) Accent.copy(alpha = 0.22f) else Color.Transparent
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Pressable(onClick = onClick) {
            Box(
                Modifier
                    .size(76.dp)
                    .clip(shape)
                    .background(fill)
                    .border(borderW, borderC, shape),
                contentAlignment = Alignment.Center,
            ) {
                // 矢量绘制（等价 SVG）：三条宽度递减的圆角横线，示意「一屏列表内容」。
                // 圆卡内横线按同一比例也会自然落在圆内（半径 42dp，最宽一条仅 45.8dp）。
                val bars = listOf(0.58f, 1f, 0.78f)
                val barColor = if (selected) Accent else TextTertiary
                if (com.netmusiclite.data.AppearancePrefs.isM3e) {
                    androidx.wear.compose.material3.Icon(
                        imageVector = Icons.Rounded.ViewAgenda,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = if (selected) androidx.wear.compose.material3.MaterialTheme.colorScheme.primary
                        else androidx.wear.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else Canvas(Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val barH = h * 0.062f
                    val gap = h * 0.115f
                    val maxW = w * 0.545f
                    bars.forEachIndexed { i, f ->
                        val bw = maxW * f
                        val cy = h / 2f + (i - 1) * gap
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset((w - bw) / 2f, cy - barH / 2f),
                            size = Size(bw, barH),
                            cornerRadius = CornerRadius(barH / 2f, barH / 2f),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            label,
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) Accent else TextSecondary,
            maxLines = 1,
        )
    }
}
