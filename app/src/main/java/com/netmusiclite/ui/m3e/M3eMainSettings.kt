package com.netmusiclite.ui.m3e

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import com.netmusiclite.data.AppearancePrefs
import com.netmusiclite.data.GestureControl
import com.netmusiclite.data.GesturePrefs
import com.netmusiclite.data.LoudnessPrefs
import com.netmusiclite.data.PlayerEngine
import com.netmusiclite.data.QualityPrefs
import com.netmusiclite.data.RotaryTuning
import com.netmusiclite.data.ScreenShapePrefs
import com.netmusiclite.data.SessionStore
import com.netmusiclite.ui.components.pressStretch
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.nav.Routes
import com.netmusiclite.ui.nav.navigateWithMotion

/** The original settings destinations and live preference callbacks in native Wear controls. */
@Composable
fun M3eMainSettingsScreen(nav: NavHostController) {
    val context = LocalContext.current
    M3eList(title = "设置") {
        item(key = "account") {
            SettingsDestination(nav, "我的账号", SessionStore.nickname.ifEmpty { "未登录" }, NcmIcons.Note, Routes.ACCOUNT)
        }
        item(key = "theme") {
            SettingsDestination(
                nav, "主题",
                "M3E · ${if (AppearancePrefs.dynamicColor) "自动配色" else AppearancePrefs.accentLabel()}",
                NcmIcons.Theme, Routes.THEME,
            )
        }
        item(key = "lyric_keep_on") {
            SwitchButton(
                checked = AppearancePrefs.lyricKeepScreenOn,
                onCheckedChange = { AppearancePrefs.setLyricKeepScreenOn(it) },
                modifier = Modifier.fillMaxWidth().pressStretch(),
                transformation = LocalItemTransformation.current,
                icon = { Icon(NcmIcons.Moon, null, Modifier.size(24.dp)) },
                secondaryLabel = { Text(modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, text = "仅停留在歌词页时保持常亮，离开恢复息屏") },
                label = { Text(modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, text = "歌词页屏幕常亮") },
            )
        }
        item(key = "shape") {
            SettingsDestination(nav, "表盘形状", "当前：${ScreenShapePrefs.label()}", NcmIcons.Watch, Routes.SHAPE)
        }
        item(key = "rotary") {
            SettingsDestination(nav, "表冠适配", RotaryTuning.label(), NcmIcons.Refresh, Routes.ROTARY)
        }
        item(key = "quality") {
            SettingsDestination(nav, "音质", "${QualityPrefs.label()} · 播放与下载生效", NcmIcons.Note, Routes.QUALITY)
        }
        item(key = "loudness") {
            SwitchButton(
                checked = LoudnessPrefs.enabled,
                onCheckedChange = { enabled ->
                    LoudnessPrefs.enabled = enabled
                    PlayerEngine.setLoudnessEnabled(enabled)
                },
                modifier = Modifier.fillMaxWidth().pressStretch(),
                transformation = LocalItemTransformation.current,
                icon = { Icon(NcmIcons.Volume, null, Modifier.size(24.dp)) },
                secondaryLabel = { Text(modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, text = "对齐歌曲间响度，保留每首歌自身动态") },
                label = { Text(modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, text = "歌曲响度均衡") },
            )
        }
        item(key = "crown_volume") {
            // ★ 2026-10-18 与 classic 设置页同一个开关（语义 = 关闭）
            SwitchButton(
                checked = com.netmusiclite.data.CrownVolumePrefs.disabled,
                onCheckedChange = { com.netmusiclite.data.CrownVolumePrefs.disabled = it },
                modifier = Modifier.fillMaxWidth().pressStretch(),
                transformation = LocalItemTransformation.current,
                icon = { Icon(NcmIcons.Watch, null, Modifier.size(24.dp)) },
                secondaryLabel = { Text(modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, text = "关掉后播放页转表冠不再调节音量") },
                label = { Text(modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, text = "关闭表冠调音量") },
            )
        }
        item(key = "gesture") {
            // ★ 2026-10-24 实验性前台捏合识别（ACC+GYRO，本地规则判别；默认关闭）。
            //   旧版 McuManager 反射桥接已废弃，这里与系统/ROM 私有接口无关。
            SwitchButton(
                checked = GesturePrefs.enabled,
                onCheckedChange = { on ->
                    if (on) {
                        com.netmusiclite.ui.components.GestureDonationGate.requestEnable {
                            GestureControl.setEnabled(context, true)
                        }
                    } else {
                        GestureControl.setEnabled(context, false)
                    }
                },
                modifier = Modifier.fillMaxWidth().pressStretch(),
                transformation = LocalItemTransformation.current,
                icon = { Icon(NcmIcons.Watch, null, Modifier.size(24.dp)) },
                secondaryLabel = {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        text = "实验性 · 双指捏一捏\n手心朝上/下切歌 · 竖腕暂停",
                    )
                },
                label = {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        text = "手势操控（实验性）",
                    )
                },
            )
        }
        item(key = "gesture_help") {
            SettingsDestination(
                nav, "手势说明与灵敏度",
                "双指捏一捏 · 上/下一首 · 暂停/继续",
                NcmIcons.Info, Routes.GESTURE,
            )
        }
        item(key = "cache") {
            SettingsDestination(nav, "缓存清理", "清理图片与临时缓存", NcmIcons.Refresh, Routes.CACHE_CLEAN)
        }
        item(key = "thanks") {
            SettingsDestination(nav, "特别鸣谢", "感谢为本项目提供帮助的朋友", NcmIcons.Star, Routes.THANKS)
        }
        item(key = "disclaimer") {
            SettingsDestination(nav, "免责声明", "第三方客户端使用协议", NcmIcons.Bookmark, Routes.DISCLAIMER)
        }
        item(key = "about") {
            SettingsDestination(nav, "关于应用", "版本 · 更新 · 作者 · 项目地址", NcmIcons.Info, Routes.ABOUT)
        }
        item(key = "qq_group") {
            SettingsDestination(nav, "加入官方QQ群", "官方群 1127884054 · 扫码加入", NcmIcons.Friends, Routes.QQ_GROUP)
        }
        item(key = "donate") {
            SettingsDestination(nav, "捐赠作者", "微信 / 支付宝扫码支持作者", NcmIcons.Heart, Routes.DONATE)
        }
        item(key = "credit") {
            Text(
                "作者：昼小风\n未经授权请勿转载",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsDestination(
    nav: NavHostController,
    title: String,
    subtitle: String,
    icon: ImageVector,
    route: String,
) {
    Button(
        onClick = { nav.navigateWithMotion(route) },
        modifier = Modifier.fillMaxWidth().pressStretch(),
        colors = ButtonDefaults.filledTonalButtonColors(),
        transformation = LocalItemTransformation.current,
        icon = { Icon(icon, null, Modifier.size(24.dp)) },
        secondaryLabel = { Text(modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, text = subtitle) },
        label = { Text(modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, text = title) },
    )
}
