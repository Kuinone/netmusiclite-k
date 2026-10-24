package com.netmusiclite.ui.m3e

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.Slider
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.netmusiclite.data.AppearancePrefs
import com.netmusiclite.data.M3eBackgroundStyle
import com.netmusiclite.data.M3ePlayerStyle
import com.netmusiclite.data.PRESET_ACCENTS
import com.netmusiclite.data.PRESET_ACCENT_NAMES
import com.netmusiclite.ui.components.pressStretch
import com.netmusiclite.ui.components.NcmIcons
import com.netmusiclite.ui.nav.navigateWithMotion
import com.netmusiclite.ui.theme.FONT_WEIGHT_LABELS
import kotlin.math.roundToInt

/** Native Wear settings, with an immediate route back to the original interface. */
@Composable
fun M3eThemeSettingsScreen(nav: NavHostController) {
    M3eList(title = "主题") {
        item(key = "interface_classic") {
            RadioButton(
                selected = AppearancePrefs.interfaceStyle == "classic",
                // ★ 2026-10-17：第一次切到经典主题也要强制看该主题的手势指南
                onSelect = {
                    AppearancePrefs.setInterfaceStyle("classic")
                    if (!com.netmusiclite.data.GuideStore.seen("classic")) {
                        nav.navigateWithMotion(
                            com.netmusiclite.ui.nav.Routes.GUIDE_QUICK,
                            com.netmusiclite.ui.nav.NavMotionSpec(com.netmusiclite.ui.nav.NavMotionKind.Directional),
                        )
                    }
                },
                modifier = Modifier.pressStretch().fillMaxWidth(),
                transformation = LocalItemTransformation.current,
                icon = { Icon(NcmIcons.Theme, null, Modifier.size(24.dp)) },
                secondaryLabel = { Text("原有界面与材质", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                label = { Text("经典", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
            )
        }
        item(key = "interface_m3e") {
            RadioButton(
                selected = AppearancePrefs.isM3e,
                onSelect = { AppearancePrefs.setInterfaceStyle("m3e") },
                modifier = Modifier.pressStretch().fillMaxWidth(),
                transformation = LocalItemTransformation.current,
                icon = { Icon(NcmIcons.Theme, null, Modifier.size(24.dp)) },
                secondaryLabel = { Text("Material 3 Expressive", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                label = { Text("M3E", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
            )
        }
        item(key = "background_title") {
            Text(
                "背景样式",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        item(key = "background_description") {
            Text("设置页面背后的背景，不改变功能卡片配色", Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
        }
        listOf(
            M3eBackgroundStyle.BLACK to "纯黑",
            M3eBackgroundStyle.AMBIENT to "取色",
            M3eBackgroundStyle.COVER_BLUR to "封面模糊",
        ).forEach { (mode, title) ->
            item(key = "m3e_background_$mode") {
                RadioButton(
                    selected = AppearancePrefs.m3eBackgroundStyle == mode,
                    onSelect = { AppearancePrefs.setM3eBackgroundStyle(mode) },
                    modifier = Modifier.pressStretch().fillMaxWidth(),
                    transformation = LocalItemTransformation.current,
                    icon = { Icon(NcmIcons.ImageIc, null, Modifier.size(24.dp)) },
                    label = { Text(title, Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                )
            }
        }
        // ★ 2026-10-18 用户口径「纯黑底色加中心柔光，加个开关默认打开」：
        //   出现条件 =「本页底色会是纯黑」的两种情形：
        //     · 背景样式 = 纯黑（全 App 黑底）；
        //     · 背景样式 = 取色 / 封面模糊，但开了「仅在播放器生效」—— 外围页（主页等）
        //       底色就是黑，所以那里也需要这抹柔光（同日补充口径）。
        if (AppearancePrefs.m3eBackgroundStyle == M3eBackgroundStyle.BLACK ||
            AppearancePrefs.m3eBackgroundPlayerOnly
        ) {
            item(key = "m3e_black_glow") {
                SwitchButton(
                    checked = com.netmusiclite.data.M3eGlowPrefs.enabled,
                    onCheckedChange = { com.netmusiclite.data.M3eGlowPrefs.enabled = it },
                    modifier = Modifier.pressStretch().fillMaxWidth(),
                    transformation = LocalItemTransformation.current,
                    icon = { Icon(NcmIcons.Play, null, Modifier.size(24.dp)) },
                    secondaryLabel = { Text("屏幕中心一抹主题色柔光，关掉即纯黑", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                    label = { Text("中心柔光", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                )
            }
        }
        if (AppearancePrefs.m3eBackgroundStyle != M3eBackgroundStyle.BLACK) {
            item(key = "m3e_background_player_only") {
                SwitchButton(
                    checked = AppearancePrefs.m3eBackgroundPlayerOnly,
                    onCheckedChange = { AppearancePrefs.setM3eBackgroundPlayerOnly(it) },
                    modifier = Modifier.pressStretch().fillMaxWidth(),
                    transformation = LocalItemTransformation.current,
                    icon = { Icon(NcmIcons.NowPlaying, null, Modifier.size(24.dp)) },
                    secondaryLabel = { Text("包含歌词与播放更多页面", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                    label = { Text("仅在播放器生效", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                )
            }
        }
        // ★ 2026-10-17 用户口径「当选择背景取色时在主题中给出一个播放器样式的栏目」；
        //   同日第三轮扩到「背景=取色 或 封面模糊」都显示（纯黑维持固定 Horologist、不显示栏目）。
        // ★ 2026-10-18 收窄回**只在封面模糊**显示：其余背景现在是强制 Horologist
        //   （见 AppearancePrefs.m3ePlayerStyleEffective），列出"原有样式"这一项只会误导用户
        //   —— 点了不生效（生效值仍是 Horologist），还会把显式选择写坏。
        if (AppearancePrefs.m3eBackgroundStyle == M3eBackgroundStyle.COVER_BLUR) {
            item(key = "player_style_title") {
                Text(
                    "播放器样式",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            item(key = "player_style_description") {
                Text(
                    if (AppearancePrefs.m3ePlayerStyleExplicit) {
                        "播放页的布局样式，不影响歌词页与更多页"
                    } else {
                        "默认：取色/纯黑用 Horologist，封面模糊用原有样式"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            listOf(
                Triple(M3ePlayerStyle.HOROLOGIST, "Horologist", "谷歌官方风格 · 文字信息 / 三键 / 操作行"),
                Triple(M3ePlayerStyle.LEGACY, "原有样式", "封面大按钮 · 右缘进度弧 · 底部胶囊"),
            ).forEach { (style, title, subtitle) ->
                item(key = "m3e_player_style_$style") {
                    RadioButton(
                        selected = AppearancePrefs.m3ePlayerStyleEffective == style,
                        onSelect = { AppearancePrefs.setM3ePlayerStyle(style) },
                        modifier = Modifier.pressStretch().fillMaxWidth(),
                        transformation = LocalItemTransformation.current,
                        icon = { Icon(NcmIcons.NowPlaying, null, Modifier.size(24.dp)) },
                        secondaryLabel = { Text(subtitle, Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                        label = { Text(title, Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                    )
                }
            }
        }
        item(key = "accent_title") {
            Text("主题色", Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall)
        }
        item(key = "accent_description") {
            Text("设置图标、按钮和选中状态的强调色", Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
        }
        item(key = "dynamic_color") {
            SwitchButton(
                checked = AppearancePrefs.dynamicColor,
                onCheckedChange = { AppearancePrefs.setDynamicColor(it) },
                modifier = Modifier.pressStretch().fillMaxWidth(),
                transformation = LocalItemTransformation.current,
                icon = { Icon(NcmIcons.ImageIc, null, Modifier.size(24.dp)) },
                secondaryLabel = {
                    Text(if (AppearancePrefs.dynamicColor) "图标与控件跟随封面配色" else "使用下方选择的主题色", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                },
                label = { Text("主题色自动取色", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
            )
        }
        PRESET_ACCENTS.forEachIndexed { index, accent ->
            item(key = "accent_$index") {
                RadioButton(
                    selected = !AppearancePrefs.dynamicColor && AppearancePrefs.accent == accent,
                    onSelect = {
                        AppearancePrefs.setDynamicColor(false)
                        AppearancePrefs.setAccent(accent)
                    },
                    modifier = Modifier.pressStretch().fillMaxWidth(),
                    transformation = LocalItemTransformation.current,
                    icon = { Icon(NcmIcons.Theme, null, Modifier.size(24.dp), tint = accent) },
                    label = { Text(PRESET_ACCENT_NAMES[index], Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                )
            }
        }
        item(key = "text_title") {
            Text("文字与歌词", Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleSmall)
        }
        item(key = "font_size") {
            // Slider has no transformation parameter in Wear 1.5.6; the native
            // Card carries the list transformation for the whole control group.
            Card(
                onClick = { AppearancePrefs.setFontScale(1f) },
                modifier = Modifier.pressStretch().fillMaxWidth(),
                transformation = LocalItemTransformation.current,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("字体大小", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${(AppearancePrefs.fontScale * 100).roundToInt()}%",
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = AppearancePrefs.fontScale,
                    onValueChange = { AppearancePrefs.setFontScale(it) },
                    valueRange = 0.8f..1.3f,
                    steps = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "点按卡片恢复 100%",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item(key = "font_weight") {
            Card(
                onClick = { AppearancePrefs.setFontWeightLevel(1) },
                modifier = Modifier.pressStretch().fillMaxWidth(),
                transformation = LocalItemTransformation.current,
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("字体粗细", Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall)
                    Text(
                        FONT_WEIGHT_LABELS.getOrElse(AppearancePrefs.fontWeightLevel) { "标准" },
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = AppearancePrefs.fontWeightLevel.toFloat(),
                    onValueChange = { AppearancePrefs.setFontWeightLevel(it.roundToInt()) },
                    valueRange = 0f..3f,
                    steps = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "点按卡片恢复标准",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!AppearancePrefs.lowPerformanceLyrics) {
            item(key = "lyric_sweep") {
                SwitchButton(
                    checked = AppearancePrefs.lyricSweep,
                    onCheckedChange = { AppearancePrefs.setLyricSweep(it) },
                    modifier = Modifier.pressStretch().fillMaxWidth(),
                    transformation = LocalItemTransformation.current,
                    icon = { Icon(NcmIcons.NowPlaying, null, Modifier.size(24.dp)) },
                    secondaryLabel = { Text("光线随当前歌词扫过", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                    label = { Text("歌词扫光", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                )
            }
        }
        item(key = "lyric_keep_on") {
            SwitchButton(
                checked = AppearancePrefs.lyricKeepScreenOn,
                onCheckedChange = { AppearancePrefs.setLyricKeepScreenOn(it) },
                modifier = Modifier.pressStretch().fillMaxWidth(),
                transformation = LocalItemTransformation.current,
                icon = { Icon(NcmIcons.Moon, null, Modifier.size(24.dp)) },
                secondaryLabel = { Text("仅停留在歌词页时保持屏幕常亮", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                label = { Text("歌词页屏幕常亮", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
            )
        }
        item(key = "low_performance_lyrics") {
            SwitchButton(
                checked = AppearancePrefs.lowPerformanceLyrics,
                onCheckedChange = { AppearancePrefs.setLowPerformanceLyrics(it) },
                modifier = Modifier.pressStretch().fillMaxWidth(),
                transformation = LocalItemTransformation.current,
                secondaryLabel = { Text("仅保留歌词基础缩放，降低渲染开销", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                label = { Text("低性能歌词", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
            )
        }
        // ★ 2026-10-17：M3E 设置此前没有「重看向导」入口（经典主题靠切换主题时自动跳），
        //   这里补一行「使用向导」：进 GUIDE_QUICK 看完 markDone 后自动返回本页（见 AppNav）。
        item(key = "guide_replay") {
            RadioButton(
                selected = false,
                onSelect = {
                    nav.navigateWithMotion(
                        com.netmusiclite.ui.nav.Routes.GUIDE_QUICK,
                        com.netmusiclite.ui.nav.NavMotionSpec(com.netmusiclite.ui.nav.NavMotionKind.Directional),
                    )
                },
                modifier = Modifier.pressStretch().fillMaxWidth(),
                transformation = LocalItemTransformation.current,
                icon = { Icon(NcmIcons.NowPlaying, null, Modifier.size(24.dp)) },
                secondaryLabel = { Text("查看当前主题的手势演示", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
                label = { Text("使用向导", Modifier.fillMaxWidth(), textAlign = TextAlign.Center) },
            )
        }
        item(key = "back") {
            Button(
                onClick = { nav.popBackStack() },
                modifier = Modifier.pressStretch().fillMaxWidth(),
                transformation = LocalItemTransformation.current,
            ) {
                Text("返回设置", Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }
}
