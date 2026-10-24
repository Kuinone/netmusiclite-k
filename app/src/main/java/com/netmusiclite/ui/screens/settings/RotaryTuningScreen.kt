package com.netmusiclite.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netmusiclite.data.RotaryTuning
import com.netmusiclite.ui.components.Pressable
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import com.netmusiclite.ui.theme.TextTertiary
import com.netmusiclite.ui.theme.screenBg

/**
 * 表冠适配（2026-10-04 用户口径「设置里加一栏表冠适配：音量页一个选项、列表页一个选项，
 * 选项两个一个正一个反」）。
 *
 * 两个场景各一组「正 / 反」二选一：
 *  · 音量页 = 播放页转表冠调音量（`rotaryCustom(scope = Volume)`）
 *  · 列表页 = **除音量外的所有界面** —— 各类列表、主页卡片、向导翻卡
 *    （`rotaryList` 全部 + `rotaryCustom(scope = List)`）。用户口径 2026-10-04：
 *    「列表的反向是除了音量之外所有界面都生效」。
 * 点选即落盘（[RotaryTuning]）并**立即生效**：符号在表冠输入链的末端相乘，
 * 下一次转动就用新方向，无需重启、无需重进页面。
 *
 * 为什么值得做成开关：实测同一款应用在不同表上方向表现不同 ——
 * 华为表原始符号与 Wear 标准相反，而 MainActivity 的厂商归一化取反只在
 * 「事件冒泡到 Activity 兜底」时才生效；若事件被 Compose 消费，取反不执行、方向就反。
 * OPPO 表冠又是 SOURCE_MOUSE 通道。厂商与通道都无法穷举，交给用户一调最省事。
 */
@Composable
fun RotaryTuningScreen() {
    val volumeReversed = RotaryTuning.volumeReversed
    val listReversed = RotaryTuning.listReversed

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
                "表冠适配",
                fontSize = 12.sp, lineHeight = 15.sp,
                fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "转表冠方向不对就调这里，点选立即生效",
                fontSize = 8.sp, lineHeight = 11.sp,
                color = TextTertiary, maxLines = 1, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))

            DirectionGroup(
                title = "音量页",
                hint = "播放页转冠调音量",
                reversed = volumeReversed,
            ) { RotaryTuning.applyVolumeReversed(it) }

            Spacer(Modifier.height(9.dp))

            DirectionGroup(
                title = "列表页",
                hint = "除音量外所有界面",
                reversed = listReversed,
            ) { RotaryTuning.applyListReversed(it) }
        }
    }
}

/** 一组方向选项：标题 + 说明 + 「正 / 反」两张卡 */
@Composable
private fun DirectionGroup(
    title: String,
    hint: String,
    reversed: Boolean,
    onPick: (Boolean) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary, maxLines = 1,
            )
            Spacer(Modifier.size(5.dp))
            Text(hint, fontSize = 7.5.sp, color = TextTertiary, maxLines = 1)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            DirectionChip("正", selected = !reversed) { onPick(false) }
            DirectionChip("反", selected = reversed) { onPick(true) }
        }
    }
}

/**
 * 单个方向选项卡。选中态三处同时区分（沿用表盘形状页的标定）：
 * Accent 填充 + 2dp Accent 描边 + Accent 粗体字 —— 只靠填充色在低饱和取色下会分不清。
 */
@Composable
private fun DirectionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(13.dp)
    val borderW = if (selected) 2.dp else 1.dp
    val borderC = if (selected) Accent else TextTertiary.copy(alpha = 0.45f)
    val fill = if (selected) Accent.copy(alpha = 0.22f) else Color.Transparent
    Pressable(onClick = onClick) {
        Box(
            Modifier
                .size(width = 60.dp, height = 38.dp)
                .clip(shape)
                .background(fill)
                .border(borderW, borderC, shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) Accent else TextSecondary,
                maxLines = 1,
            )
        }
    }
}
