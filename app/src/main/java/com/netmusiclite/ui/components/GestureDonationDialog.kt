package com.netmusiclite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netmusiclite.data.GesturePrefs
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.BgElevated
import com.netmusiclite.ui.theme.OnAccent
import com.netmusiclite.ui.theme.SurfaceStrong
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import com.netmusiclite.ui.theme.isLightTheme

/**
 * 第一次开启手势控制前的一次性捐赠提示。
 * 点“已经捐赠”或“下次一定”后都继续开启手势，并永久记下已提示。
 */
object GestureDonationGate {
    var visible by mutableStateOf(false)
        private set

    private var pendingEnable: (() -> Unit)? = null

    fun requestEnable(onEnable: () -> Unit) {
        if (GesturePrefs.donationPromptShown) {
            onEnable()
            return
        }
        pendingEnable = onEnable
        visible = true
    }

    fun acknowledge() {
        GesturePrefs.markDonationPromptShown()
        visible = false
        pendingEnable?.invoke()
        pendingEnable = null
    }
}

@Composable
fun GestureDonationDialog(onAcknowledge: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = { },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = if (isLightTheme) 0.32f else 0.72f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .width(200.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(BgElevated)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "支持手势算法",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "手势识别还在持续完善。如果它对你有帮助，欢迎捐赠 3 元及以上，帮助作者继续完善算法。",
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                Pressable(onClick = onAcknowledge, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(50))
                            .background(Accent)
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "已经捐赠",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OnAccent,
                        )
                    }
                }
                Spacer(Modifier.height(7.dp))
                Pressable(onClick = onAcknowledge, modifier = Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(50))
                            .background(SurfaceStrong)
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("下次一定", fontSize = 11.sp, color = TextPrimary)
                    }
                }
            }
        }
    }
}
