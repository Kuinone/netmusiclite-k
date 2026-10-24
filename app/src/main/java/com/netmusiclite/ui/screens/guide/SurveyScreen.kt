package com.netmusiclite.ui.screens.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.Pressable
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.OnAccent
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import com.netmusiclite.ui.theme.screenBg

/**
 * 高级版公告问卷页（2026-10-04 新增，用户口径「首次启动弹问卷：打算搞六元高级付费版本、
 * 原有功能仍然可用、想要的功能加群联系群主，下方确认按钮」）。
 *
 * 两条触发路径共用本页（判定见 [com.netmusiclite.data.SurveyStore] 与 AppNav 的启动分流）：
 *  · 老用户（已登录、升级上来）—— 首次启动直接落在本页；
 *  · 新用户（没登录过）—— 走完向导后在**末尾**出现。
 *
 * 设计要点：
 *  - 只做「告知 + 确认」，不收集任何输入 —— 叫问卷但不需要填东西，用户点确认即过；
 *  - 不挂滑动返回（与向导各页一致）：这是必须确认的一屏，右滑不该跳过；
 *  - 版式按圆屏 233dp 标定：内容总高约 204dp，最宽一行（17 个 10sp 汉字 ≈170dp）在
 *    该高度处的圆内可用宽度内（≈224dp），四角不会被弧边切。
 */
@Composable
fun SurveyScreen(onConfirm: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(screenBg()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().padding(horizontal = 30.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    NcmIcons.Info, contentDescription = null,
                    tint = Accent, modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            // 2026-10-10 用户口径：整段公告替换为感谢向文案（原「拟推出 6 元付费版」语境全撤）
            Text(
                "支持作者谢谢喵，你们的支持是作者继续制作更好的软件的动力",
                fontSize = 10.5.sp, lineHeight = 15.sp,
                fontWeight = FontWeight.Medium, color = TextPrimary, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Pressable(onClick = onConfirm) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Accent)
                        .padding(horizontal = 34.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("确认", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OnAccent)
                }
            }
        }
    }
}
