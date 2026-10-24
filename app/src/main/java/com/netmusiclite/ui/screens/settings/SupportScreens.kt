package com.netmusiclite.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.netmusiclite.ui.components.NcmListPage
import com.netmusiclite.ui.components.StackedCard
import com.netmusiclite.ui.screens.guide.loadAssetBitmap
import com.netmusiclite.ui.theme.SurfaceStrong
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import com.netmusiclite.ui.theme.TextTertiary

/**
 * 设置 → 加入官方QQ群 / 捐赠作者（2026-10-03 用户口径「设置里添加加入官方QQ群/捐赠作者选项，
 * qq群在上，图片竖向排列」）。
 *
 * 两页都是纯静态页（列表卡片风格与关于应用页一致，返回走全局左缘滑动）：
 *  · [QqGroupScreen]   —— assets/qq_group.png 原图直出 + 群号文字；
 *  · [DonateScreen]    —— 微信、支付宝两张收款码**竖向排列**（微信在上、支付宝在下），
 *    原图直出不裁剪：卡片自带「推荐使用…」头部，裁掉反而不完整；
 *    图放在白色圆角底上，深浅主题下都能扫。
 */

/** 加入官方QQ群（设置入口在上、捐赠在下——用户指定顺序） */
@Composable
fun QqGroupScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    // 原图直出（带群名头像头的整卡设计），与使用向导的 GuideQqScreen 同一张图
    val qr = remember { loadAssetBitmap(ctx, "qq_group.png", 640) }
    NcmListPage(title = "加入官方QQ群") {
        item(key = "qr") {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
            ) {
                Box(
                    Modifier
                        .size(152.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    if (qr != null) {
                        Image(
                            qr.asImageBitmap(),
                            contentDescription = "官方QQ群二维码",
                            modifier = Modifier.size(142.dp),
                        )
                    } else {
                        Text(
                            "QQ群二维码\n待补充",
                            fontSize = 10.sp, color = Color(0xFF8A8A8E), textAlign = TextAlign.Center,
                        )
                    }
                }
                Spacer(Modifier.height(9.dp))
                Text(
                    "群号：1127884054",
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text("手机扫码，或在QQ中搜索群号加入", fontSize = 9.sp, color = TextSecondary)
            }
        }
    }
}

/** 捐赠作者：微信在上、支付宝在下（用户口径「图片竖向排列」） */
@Composable
fun DonateScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    // 收款码卡片整张直出（含卡片自身标题与二维码，用户提供的美化版），不做裁剪重排
    val wechat = remember { loadAssetBitmap(ctx, "donate_wechat.png", 640) }
    val alipay = remember { loadAssetBitmap(ctx, "donate_alipay.jpg", 640) }
    NcmListPage(title = "捐赠作者") {
        item(key = "wechat") { DonateCode(wechat, "微信收款码待补充") }
        item(key = "alipay") { DonateCode(alipay, "支付宝收款码待补充") }
        item(key = "thanks") {
            Text(
                "感谢你的支持，这是我更新的最大动力",
                fontSize = 9.sp, color = TextTertiary, lineHeight = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            )
        }
    }
}

/** 单张收款码：按原始宽高比缩放显示（宽度受圆屏限制），缺图时白底占位 */
@Composable
private fun DonateCode(bmp: android.graphics.Bitmap?, fallback: String) {
    Box(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .width(158.dp)
                    .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                    .clip(RoundedCornerShape(16.dp)),
            )
        } else {
            Box(
                Modifier
                    .size(158.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Text(fallback, fontSize = 10.sp, color = Color(0xFF8A8A8E), textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * 特别鸣谢（2026-10-07 用户口径「在设置免责声明上方添加特别鸣谢栏目，鸣谢名单用胶囊形状
 * 加上胶囊前半部分头像，适配界面主题，上方声明排名不分前后，由 AI 排名，每次进入顺序随机」）。
 *
 * 全静态页（返回走全局左缘滑动）：
 *  · 顶部声明一行：排名不分先后 · 由 AI 排名 · 每次进入顺序随机；
 *  · 名单每条是一枚胶囊（[StackedCard] 半径 = 高度/2 → 正圆头），左端一枚与之同心的圆形
 *    头像（≈胶囊高去内边距），右侧名字 —— 胶囊材质沿用玻璃件（[StackedCard] 内建
 *    frostGlass + 边缘高光），深浅主题/玻璃开关/自定义壁纸下自动适配，不用另外配色；
 *  · 顺序每次进入重新洗牌（`remember` 作用域 = 本页组合，重新进入即新一轮随机）。
 */
@Composable
fun ThanksScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    // 每次进入本页重新洗牌（用户口径「每次进入顺序随机」）：remember 与页面组合同生命周期
    val members = remember { THANKS_MEMBERS.shuffled() }
    NcmListPage(title = "特别鸣谢") {
        item(key = "note") {
            Text(
                "排名不分先后，由 AI 排名，每次进入顺序随机",
                fontSize = 9.sp, color = TextSecondary, lineHeight = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            )
        }
        members.forEach { m ->
            item(key = m.name) { ThanksPill(ctx, m) }
        }
    }
}

/** 鸣谢名单：资源名（assets/，用户提供头像缩至 160px）→ 展示名 */
private class ThanksMember(val asset: String, val name: String)

private val THANKS_MEMBERS = listOf(
    ThanksMember("thanks_nanthan.png", "nanthan"),
    ThanksMember("thanks_oride.png", "oride"),
    ThanksMember("thanks_yana.png", "yana"),
    ThanksMember("thanks_budetaole.png", "不得不逃了"),
    ThanksMember("thanks_mingtianbuzao.png", "明天不早"),
    // 捐赠者（2026-10-10 用户口径）：4 位，头像由用户提供（assets 内缩至 192px）
    ThanksMember("thanks_khadon.png", "ༀ"),
    ThanksMember("thanks_suange.png", "所爱隔山海"),
    ThanksMember("thanks_space.png", " "),
    ThanksMember("thanks_huaxie.png", "花谢之日"),
)

/** 单条鸣谢胶囊：左端圆形头像 + 右侧名字；不可点击（纯展示，无按压缩放） */
@Composable
private fun ThanksPill(ctx: android.content.Context, member: ThanksMember) {
    val bmp = remember(member) { loadAssetBitmap(ctx, member.asset, 192) }
    Box(Modifier.padding(vertical = 4.dp)) {
        StackedCard(
            height = 56.dp,
            cornerRadius = 28.dp,
            contentPadding = PaddingValues(start = 5.dp, end = 18.dp),
        ) {
            // 头像圆心 = 胶囊左端半圆圆心（同尺寸同心），直径 46dp = 胶囊高 56 − 内边距 2×5
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(SurfaceStrong),
                contentAlignment = Alignment.Center,
            ) {
                if (bmp != null) {
                    Image(
                        bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        member.name.take(1),
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = TextSecondary,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                member.name,
                fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
