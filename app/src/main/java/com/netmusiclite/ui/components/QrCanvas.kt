package com.netmusiclite.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** ZXing 生成二维码（圆角白底卡片内呈现，扫码友好） */
@Composable
fun QrCodeImage(content: String, size: Dp, modifier: Modifier = Modifier) {
    // ★ 2026-10-02：生成分辨率跟随实际显示尺寸（1:1 像素）—— 原实现固定 66px，
    //   登录二维码放大到 153.6dp（466 屏 ≈ 307px）后会放大 4.6 倍发虚，影响扫码识别。
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
        .coerceIn(128, 512)
    val bitmap = remember(content, px) {
        runCatching {
            val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, px, px, hints)
            val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            // 逐行 setPixels（原逐像素 setPixel 在 300px 级会明显卡主线程）
            val row = IntArray(px)
            for (y in 0 until px) {
                for (x in 0 until px) {
                    row[x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
                bmp.setPixels(row, 0, px, 0, y, px, 1)
            }
            bmp
        }.getOrNull()
    }
    Box(
        modifier
            .size(size + 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = "二维码", modifier = Modifier.size(size))
        }
    }
}
