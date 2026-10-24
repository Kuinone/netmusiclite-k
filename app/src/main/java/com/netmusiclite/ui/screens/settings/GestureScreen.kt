package com.netmusiclite.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netmusiclite.data.GestureControl
import com.netmusiclite.data.GesturePrefs
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.StackedCard
import com.netmusiclite.ui.components.StackedCardList
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.OnAccent
import com.netmusiclite.ui.theme.SurfaceStrong
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import com.netmusiclite.ui.theme.TextTertiary

/**
 * 手势操控说明与灵敏度（2026-10-24 用户口径）：
 * 三个动作全部由**双指捏一捏**触发，掌心朝向决定动作。
 */
@Composable
fun GestureScreen() {
    val context = LocalContext.current
    val enabled = GesturePrefs.enabled
    val sensitivity = GesturePrefs.sensitivity
    val running = GestureControl.isRunning
    val last = GestureControl.lastGesture

    StackedCardList(title = "手势操控", progressTotal = 9) {
        item(key = "note") {
            StackedCard(height = 96.dp) {
                Column(Modifier.weight(1f)) {
                    Text("说明", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Text(
                        "使用方法：双指捏一捏",
                        fontSize = 8.sp,
                        color = TextPrimary,
                        maxLines = 2,
                    )
                    Text(
                        "只在应用前台生效，退后台或息屏立即停止。识别由手表加速度计与陀螺仪在本地完成，可能被日常动作误触发。",
                        fontSize = 8.sp,
                        color = TextSecondary,
                        maxLines = 4,
                    )
                }
            }
        }

        item(key = "master") {
            StackedCard(
                onClick = {
                    if (enabled) {
                        GestureControl.setEnabled(context, false)
                    } else {
                        com.netmusiclite.ui.components.GestureDonationGate.requestEnable {
                            GestureControl.setEnabled(context, true)
                        }
                    }
                },
                height = 64.dp,
            ) {
                IconBubble(NcmIcons.Watch, enabled)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "手势操控（实验性）",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary,
                    )
                    Text(
                        if (enabled) "已开启 · 双指捏一捏触发" else "已关闭 · 实验性功能，默认关闭",
                        fontSize = 8.sp,
                        color = TextSecondary,
                        maxLines = 1,
                    )
                    Text(
                        "仅本应用前台时读取传感器",
                        fontSize = 8.sp,
                        color = TextTertiary,
                        maxLines = 1,
                    )
                }
                Spacer(Modifier.width(8.dp))
                ToggleDot(enabled)
            }
        }

        item(key = "status") {
            StackedCard(height = 64.dp) {
                Column(Modifier.weight(1f)) {
                    Text("识别状态", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                    Text(
                        when {
                            !enabled -> "已关闭 · 不读取传感器"
                            running -> "正在前台识别 · ${GesturePrefs.sensitivityLabel(sensitivity)}"
                            else -> "等待回到前台"
                        },
                        fontSize = 8.sp,
                        color = TextSecondary,
                        maxLines = 1,
                    )
                    Text("最近：$last", fontSize = 8.sp, color = TextTertiary, maxLines = 1)
                }
            }
        }

        item(key = "prev") {
            GestureRule(NcmIcons.Prev, "上一首", "双指捏一捏 · 手心朝上")
        }
        item(key = "next") {
            GestureRule(NcmIcons.Next, "下一首", "双指捏一捏 · 手心朝下")
        }
        item(key = "pause") {
            GestureRule(NcmIcons.Pause, "暂停 / 继续", "双指捏一捏 · 手腕竖起（屏幕朝侧面）")
        }

        val options = listOf(
            Triple(GesturePrefs.SENSITIVITY_STANDARD, "标准", GesturePrefs.sensitivityHint(GesturePrefs.SENSITIVITY_STANDARD)),
            Triple(GesturePrefs.SENSITIVITY_HIGH, "灵敏（推荐）", GesturePrefs.sensitivityHint(GesturePrefs.SENSITIVITY_HIGH)),
            Triple(GesturePrefs.SENSITIVITY_EXTREME, "很灵敏", GesturePrefs.sensitivityHint(GesturePrefs.SENSITIVITY_EXTREME)),
        )
        options.forEach { (level, label, hint) ->
            item(key = "sensitivity_$level") {
                val selected = sensitivity == level
                StackedCard(
                    onClick = { GestureControl.setSensitivity(level) },
                    height = 54.dp,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "灵敏度 · $label",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (selected) Accent else TextPrimary,
                        )
                        Text(hint, fontSize = 8.sp, color = TextSecondary, maxLines = 1)
                    }
                    if (selected) {
                        Icon(NcmIcons.Check, null, tint = Accent, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

    }
}

@Composable
private fun GestureRule(icon: ImageVector, title: String, subtitle: String) {
    StackedCard(height = 58.dp) {
        IconBubble(icon, on = false)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Text(subtitle, fontSize = 8.sp, color = TextSecondary, maxLines = 1)
        }
    }
}

@Composable
private fun IconBubble(icon: ImageVector, on: Boolean) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (on) Accent else SurfaceStrong),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (on) OnAccent else TextPrimary,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun ToggleDot(on: Boolean) {
    Box(
        Modifier
            .size(18.dp)
            .clip(CircleShape)
            .then(
                if (on) Modifier.background(Accent)
                else Modifier.border(1.dp, TextTertiary, CircleShape),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (on) {
            Icon(
                NcmIcons.Check,
                contentDescription = "已开启",
                tint = OnAccent,
                modifier = Modifier.size(11.dp),
            )
        }
    }
}
