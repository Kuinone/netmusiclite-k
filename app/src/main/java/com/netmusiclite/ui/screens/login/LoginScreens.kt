package com.netmusiclite.ui.screens.login

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.netmusiclite.data.AccountEntry
import com.netmusiclite.data.NcmApi
import com.netmusiclite.data.SessionStore
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.components.QrCodeImage
import com.netmusiclite.ui.components.frostedGlass
import com.netmusiclite.ui.theme.Bg
import com.netmusiclite.ui.theme.screenBg
import com.netmusiclite.ui.theme.Green
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import kotlinx.coroutines.delay

/**
 * 扫码登录（首启 / 退出后再登 / 添加账号共用）。
 * 流程：qr/key 拿 unikey → 二维码 → 每 1.8s 轮询 → 803 成功存 MUSIC_U。
 *
 * ★ 2026-10-06 「不登录进入音乐」入口（用户口径）：底部小胶囊，点击后以**访客模式**
 *  直接进主页（主页只显示 听本地音乐 / 听歌排行 / 最近播放 / 设置 四卡、无头像徽章）；
 *  之后在设置内添加账号即回到正常模式。添加账号页（[onGuestEnter] = null）不显示本入口
 *  —— 那个场景下「不登录」语义不通。
 */
@Composable
fun LoginQrScreen(
    onLoggedIn: () -> Unit,
    heading: String = "网易云扫码登录",
    onGuestEnter: (() -> Unit)? = null,
) {
    var qrContent by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("正在获取二维码…") }
    var scanned by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        outer@ while (true) {
            val key = NcmApi.qrKey()
            if (key == null) {
                status = "网络异常，正在重试…\n${NcmApi.error() ?: "无unikey"}"
                delay(2500)
                continue@outer
            }
            qrContent = "https://music.163.com/login?codekey=$key"
            status = "打开手机网易云扫一扫"
            scanned = false
            while (true) {
                delay(1800)
                val r = NcmApi.qrPoll(key)
                when (r.code) {
                    801 -> status = "等待扫码…"
                    802 -> { scanned = true; status = "已扫码，请在手机上确认" }
                    800 -> continue@outer // 过期 → 重新生成
                    8821 -> {
                        // 风控：换新 key 重试
                        status = "登录被风控，正在换新二维码…"
                        delay(1200)
                        continue@outer
                    }
                    803 -> {
                        val rawCookie = r.cookie ?: ""
                        val cookie = NcmApi.cookieFrom(rawCookie)
                        if (cookie != null) {
                            SessionStore.musicU = cookie
                            // __csrf 是点赞/发评论的 checkToken，登录时一并落盘
                            SessionStore.csrf = NcmApi.csrfFrom(rawCookie)
                            // account 失败重试 3 次：uid=0 落盘后，听歌排行/歌曲百科的
                            // 云端次数会整个失效（百科只能退显本地计数）
                            var profile = NcmApi.account()
                            for (retry in 1 until 3) {
                                if (profile != null) break
                                kotlinx.coroutines.delay(1200)
                                profile = runCatching { NcmApi.account() }.getOrNull()
                            }
                            if (profile != null) {
                                SessionStore.uid = profile.optLong("userId")
                                SessionStore.nickname = profile.optString("nickname")
                                SessionStore.avatarUrl = profile.optString("avatarUrl")
                                SessionStore.vipType = profile.optInt("vipType")
                            }
                            SessionStore.addAccount(
                                AccountEntry(
                                    cookie, SessionStore.uid, SessionStore.nickname,
                                    SessionStore.avatarUrl, SessionStore.csrf,
                                )
                            )
                            onLoggedIn()
                        } else {
                            status = "登录失败，重试中…"
                            delay(1200)
                            continue@outer
                        }
                        return@LaunchedEffect
                    }
                }
            }
        }
    }

    if (com.netmusiclite.data.AppearancePrefs.isM3e) {
        com.netmusiclite.ui.m3e.M3eLoginQr(heading, qrContent, status, scanned, onGuestEnter)
        return
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(screenBg()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // ★ 2026-10-06 底部「不登录进入音乐」入口占位：主内容整体上移 12dp，
            //   二维码在圆表上仍远在弦长安全区内（153.6dp 宽卡中心 y≈-70dp 处弦长 >176dp）
            modifier = Modifier.padding(bottom = if (onGuestEnter != null) 24.dp else 0.dp),
        ) {
            Text(heading, fontSize = 15.sp, color = TextPrimary, textAlign = TextAlign.Center, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            // ★ 2026-10-02 用户口径「登录二维码调大 20%」：128dp → 153.6dp（遮罩/对勾同步放大）。
            //   233dp 圆屏上 163.6dp 白卡的四角距圆心 115.7dp < 半径 116.5dp，不会被圆裁切；
            //   两处 Spacer 14→10dp 抵消增高，保证「网络异常」两行状态字时也不顶出屏幕。
            Spacer(Modifier.height(10.dp))
            Box(contentAlignment = Alignment.Center) {
                QrCodeImage(qrContent ?: "", 153.6.dp)
                if (scanned) {
                    Box(
                        Modifier.size(153.6.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.72f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(NcmIcons.Check, null, tint = Green, modifier = Modifier.size(52.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(status, fontSize = 10.sp, color = TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 44.dp))
        }
        // ★ 2026-10-06 访客入口：底部小胶囊（玻璃材质，与全站卡片观感一致）。
        //   位置压在屏幕下缘（bottom 10dp，此处圆弦宽约 112dp > 胶囊约 98dp，不会被圆裁切）。
        if (onGuestEnter != null) {
            val guestInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Text(
                "不登录进入音乐",
                fontSize = 10.sp,
                color = TextSecondary,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .frostedGlass(androidx.compose.foundation.shape.RoundedCornerShape(50))
                    .clickable(
                        interactionSource = guestInteraction,
                        indication = null,
                    ) { onGuestEnter() }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}
