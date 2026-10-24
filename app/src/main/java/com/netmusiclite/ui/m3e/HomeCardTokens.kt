package com.netmusiclite.ui.m3e

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.netmusiclite.ui.nav.Routes

// NO-NATIVE: 用户指定 Apple Music 参考的固定卡片配色，集中定义语义色板，不跟随动态主题取色。
//
// ★ 2026-10-23 第三轮（用户给了 4 张 Apple Music 参考海报，要求「理解这几个卡片的配色，
//   把 m3e 卡片左上角的小卡片内容换成这些」）：
//   · [colors]  = 大卡片底面的渐变（4 色，按参考图主色到高光排）；
//   · [glow]    = 该卡的中心柔光（CardDefaults.containerPainter 的 scrim）；
//   · [ink]     = 卡片上的文字/大图标颜色（浅色底用深墨、深色底用白墨，保证可读）；
//   · [tile]    = **左上角那张 66×59dp 小卡片的新底色**（对角线渐变，模仿参考海报的
//                 「色块 + 高光」两段式），替代原来单调的 ink 12% 半透明方块；
//   · [tileInk] = 小卡片上图标/星星的颜色（印在 tile 渐变上的颜色）。
//
// 参考图 → 入口卡映射（"favoritesong 对应我喜欢，其他你看着办"）：
//   图2 Favourite Songs（浅灰底 + 红星星）      → 我喜欢（LIKED_SONGS，图标换成红色五角星）
//   图3 New Music（玫红/粉渐变）                → 每日推荐（DAILY_RECOMMEND）
//   图4 放松歌单（深蓝 → 青绿渐变）              → 私人漫游（PRIVATE_ROAM）
//   图1 九月音乐回忆（黄橙 → 青绿对角渐变）       → 听歌排行（RECORD）
//   其余三张按同一套语言做**变体**（蓝 / 紫 / 青灰），保持整屏一眼同源：
//   New Music 蓝变体 → 播放列表；放松歌单紫变体 → 我的歌单；九月回忆青灰变体 → 最近播放；
//   浅灰（同 Favourite Songs 底）→ 设置 / 其它兜底。
/** Fixed semantic artwork colors; never derived from the current song or cover. */
@Immutable
internal data class HomeCardPalette(
    val colors: List<Color>,
    val glow: Color,
    val ink: Color,
    /** 左上角小卡片的底色渐变（默认沿用大卡配色，个别卡在 when 里显式覆盖） */
    val tile: List<Color> = colors,
    /** 小卡片上图标颜色（默认同 [ink]） */
    val tileInk: Color = ink,
)

/** 图2 Favourite Songs：近白浅灰底 + 红星星（颜色取自参考图的卡片底与星星） */
private val favoritePalette = HomeCardPalette(
    colors = listOf(Color(0xFFF7F8FA), Color(0xFFEDEEF1), Color(0xFFE1E3E8), Color(0xFFF2F3F6)),
    glow = Color(0xFFFFFFFF),
    ink = Color(0xFF1B1B1F),
    tile = listOf(Color(0xFFFFFFFF), Color(0xFFE8E9ED), Color(0xFFD3D6DC)),
    tileInk = Color(0xFFF4233F),
)
/** 图3 New Music：玫红 → 粉白的高饱和渐变 */
private val discoveryPalette = HomeCardPalette(
    colors = listOf(Color(0xFFE8004C), Color(0xFFFF3D6E), Color(0xFFFF8FAE), Color(0xFFFFC9D8)),
    glow = Color(0xFFFFA8C0),
    ink = Color(0xFFFFFFFF),
    tile = listOf(Color(0xFFD80048), Color(0xFFFF4D7D), Color(0xFFFFB3C9)),
    tileInk = Color(0xFFFFFFFF),
)
/** 图4 放松歌单：深蓝绿 → 青 → 亮青绿 */
private val roamPalette = HomeCardPalette(
    colors = listOf(Color(0xFF0A5C7A), Color(0xFF0E8FA8), Color(0xFF17C9C9), Color(0xFF3CE09A)),
    glow = Color(0xFF7FE8C8),
    ink = Color(0xFFFFFFFF),
    tile = listOf(Color(0xFF0A5C7A), Color(0xFF12B6B6), Color(0xFF3BE38B)),
    tileInk = Color(0xFFFFFFFF),
)
/** 图1 九月音乐回忆：黄橙 → 奶白 → 薄荷青的对角渐变 */
private val energyPalette = HomeCardPalette(
    colors = listOf(Color(0xFFF5C518), Color(0xFFEDD98A), Color(0xFFB7E3C0), Color(0xFF7FD6C2)),
    glow = Color(0xFFFFF0B8),
    ink = Color(0xFF3A2A12),
    tile = listOf(Color(0xFFF5C518), Color(0xFFC7E7BE), Color(0xFF8FDCD0)),
    tileInk = Color(0xFF3A2A12),
)
/** New Music 的蓝色变体（播放列表） */
private val queuePalette = HomeCardPalette(
    colors = listOf(Color(0xFF20306E), Color(0xFF3B5FC8), Color(0xFF6BA3EC), Color(0xFFA8D2F5)),
    glow = Color(0xFFB8D9FF),
    ink = Color(0xFFFFFFFF),
    tile = listOf(Color(0xFF20306E), Color(0xFF4C7BE0), Color(0xFF8FC8F5)),
    tileInk = Color(0xFFFFFFFF),
)
/** 放松歌单的紫色变体（我的歌单） */
private val playlistPalette = HomeCardPalette(
    colors = listOf(Color(0xFF4B2A78), Color(0xFF7E4FC4), Color(0xFFB583E8), Color(0xFFE0B8F5)),
    glow = Color(0xFFE8C8FF),
    ink = Color(0xFFFFFFFF),
    tile = listOf(Color(0xFF4B2A78), Color(0xFF9B6BE0), Color(0xFFD8B0F5)),
    tileInk = Color(0xFFFFFFFF),
)
/** 九月回忆的青灰变体（最近播放） */
private val historyPalette = HomeCardPalette(
    colors = listOf(Color(0xFF3E6E80), Color(0xFF6FA8B8), Color(0xFF9FD0D0), Color(0xFFC6E5E0)),
    glow = Color(0xFFD8F0E8),
    ink = Color(0xFF0F2E38),
    tile = listOf(Color(0xFF3E6E80), Color(0xFF7FBEBE), Color(0xFFBDE0D8)),
    tileInk = Color(0xFFFFFFFF),
)
/** 与 Favourite Songs 同底的浅灰（设置 / 本地音乐等兜底卡） */
private val settingsPalette = HomeCardPalette(
    colors = listOf(Color(0xFFEDEEF1), Color(0xFFDDE0E5), Color(0xFFCBD0D8), Color(0xFFE4E7EC)),
    glow = Color(0xFFFFFFFF),
    ink = Color(0xFF1E2126),
    tile = listOf(Color(0xFF9AA0AB), Color(0xFFBFC5CF), Color(0xFFDDE1E7)),
    tileInk = Color(0xFFFFFFFF),
)

internal fun homeCardPalette(route: String): HomeCardPalette = when (route) {
    Routes.LIKED_SONGS -> favoritePalette
    Routes.MY_PLAYLISTS -> playlistPalette
    Routes.DAILY_RECOMMEND -> discoveryPalette
    Routes.QUEUE -> queuePalette
    Routes.RECORD -> energyPalette
    Routes.PLAY_HISTORY -> historyPalette
    Routes.PRIVATE_ROAM, Routes.LOCAL_MUSIC -> roamPalette
    else -> settingsPalette
}
