package com.netmusiclite.ui.screens.gallery

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.netmusiclite.ui.m3e.AdaptiveLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.netmusiclite.data.BackgroundStore
import com.netmusiclite.ui.components.CenterHint
import com.netmusiclite.ui.components.CircularSideProgress
import com.netmusiclite.ui.components.MediaPermissions
import com.netmusiclite.ui.components.frostedGlass
import com.netmusiclite.ui.components.rotaryList
import com.netmusiclite.ui.theme.Accent
import com.netmusiclite.ui.theme.OnAccent
import com.netmusiclite.ui.theme.TextPrimary
import com.netmusiclite.ui.theme.TextSecondary
import com.netmusiclite.ui.theme.screenBg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 系统图片条目：uri + 日期（DATE_TAKEN 缺失时回落 DATE_ADDED）毫秒 */
private data class SystemImage(val uri: Uri, val dateMs: Long)

/** 「日期为 2020 年后」的分界：2020-01-01 00:00（本地时区） */
private val CUTOFF_2020_MS: Long = java.util.Calendar.getInstance().apply {
    clear()
    set(2020, java.util.Calendar.JANUARY, 1, 0, 0, 0)
}.timeInMillis

/** 弧形映射最大倾角（度）：行中心每偏离视口中心一个「半视口」，转这么多角度。 */
private const val ARC_MAX_DEG = 50f
private val ARC_MAX_RAD: Float = Math.toRadians(ARC_MAX_DEG.toDouble()).toFloat()
private val ARC_SIN_MAX: Float = sin(ARC_MAX_RAD)
private val ARC_90_RAD: Float = (Math.PI / 2.0).toFloat()

private val TILE_GAP = 7.dp
private val ROW_GAP = 7.dp
private val SIDE_PAD = 10.dp

/**
 * 内置图片管理器（2026-10-03 用户口径「增加一个系统内置管理器，在自定义壁纸的时候
 * 点击按钮后进入，自动扫描整个系统的图片（日期为 2020 年后）」）：
 *
 * · **扫描**：进入即申请 READ_MEDIA_IMAGES（33+）/ READ_EXTERNAL_STORAGE（≤32）并后台
 *   查询 MediaStore.Images，保留 2020-01-01 之后的条目（DATE_TAKEN 优先，缺省回落
 *   DATE_ADDED），新的在前；拒绝授权时不空转 —— 给「重新申请」与免权限的
 *   「从系统相册选择」（旧 PickVisualMedia 通路，保留作兜底）。
 * · **双列弧形**：两列并排的正方形图块；行中心离视口中心越远，
 *   越沿圆柱内壁「下沉」—— 向中线收拢（行间距收缩）、变小（cos 系数，边缘 ~64%）、
 *   变淡（smoothstep，最低 72%）。变换全部走 graphicsLayer 延迟读取，滚动零重组。
 * · **滑动进度条**：复用右缘弧条 CircularSideProgress（主题色 + 随滚动推进），
 *   表冠 / 触摸滚动走 rotaryList（与全站列表同一套手感）。
 * · **背景**：页面自身透明（screenBg），透出全局背景层 —— 已设自定义图就是那张图，
 *   未设就是主题环境色（用户口径「背景色为自定义的图片或者选择的主题」）。
 * · 点击图块 = BackgroundStore.setFrom（复制进私有目录）→ 立即全局生效 → 返回设置页。
 */
@Composable
fun GalleryScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberLazyListState()
    val imagePerm = remember { MediaPermissions.images() }
    var granted by remember { mutableStateOf(MediaPermissions.granted(ctx, imagePerm)) }
    var images by remember { mutableStateOf<List<SystemImage>?>(null) } // null = 尚未扫描完
    var scanning by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    // 设为背景：复制 + 生成毛玻璃/主色（BackgroundStore 内部自带后台执行），成功即返回 ——
    // 下层设置页背景已经换好，这就是最直接的反馈
    suspend fun apply(img: SystemImage) {
        val ok = withContext(Dispatchers.IO) { BackgroundStore.setFrom(img.uri, ctx.contentResolver) }
        if (ok) nav.popBackStack() else toast = "这张图片读取失败，换一张试试"
    }

    suspend fun doScan() {
        scanning = true
        images = scanSystemImages(ctx)
        scanning = false
    }

    // 免权限兜底（沿旧通路）：系统相册单选，选完直接设为背景
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { apply(SystemImage(uri, 0L)) }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        granted = ok
        if (ok) scope.launch { doScan() }
    }

    // 进入即自动扫描：未授权先弹系统权限对话框（「使用该功能时才申请」的时机就在这里）
    LaunchedEffect(Unit) {
        if (granted) doScan() else permLauncher.launch(imagePerm)
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2600); toast = null } }

    // ⚠ screenWidthUiDp = UI 坐标系屏宽：密度被等比缩放后 LocalConfiguration.screenWidthDp
    //   不再等于屏宽，用它算瓦片尺寸会让列宽在非 233dp 屏上偏小/偏大（见 Common.kt 注释）
    val screenW = com.netmusiclite.ui.components.screenWidthUiDp()
    val tileSize = (screenW - SIDE_PAD * 2 - TILE_GAP) / 2
    val pitchPx = with(LocalDensity.current) { (tileSize + ROW_GAP).toPx() }
    val rows = remember(images) { images.orEmpty().chunked(2) }
    // 与全站堆叠列表同口径：行索引 + 行内滚动比例（/行距），滚动中进度连续推进
    val progress by remember(state, rows.size, pitchPx) {
        derivedStateOf {
            if (rows.size <= 1) 0.15f
            else {
                val first = com.netmusiclite.ui.m3e.listFirstIndex(state)
                val part = com.netmusiclite.ui.m3e.listFirstOffset(state) / pitchPx.coerceAtLeast(1f)
                ((first + part) / (rows.size - 1f)).coerceIn(0.15f, 1f)
            }
        }
    }

    Box(Modifier.fillMaxSize().background(screenBg())) {
        when {
            !granted && images == null -> PermissionPanel(
                onRequest = { permLauncher.launch(imagePerm) },
                onSystemPicker = {
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )

            scanning && images == null -> CenterHint("扫描中…", Modifier.fillMaxSize())

            images != null && images!!.isEmpty() -> EmptyPanel(
                onSystemPicker = {
                    pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            )

            else -> {
                val list = images ?: emptyList()
                Box(Modifier.fillMaxSize().rotaryList(state)) {
                    LazyColumn(
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                        // 上下各留 (视口-行高)/2：首/末行也能滚到正中（与全站堆叠列表一致）
                        // 视口高取 screenHeightUiDp（UI 坐标系），非正方形屏也正确
                        contentPadding = PaddingValues(
                            horizontal = SIDE_PAD,
                            vertical = (com.netmusiclite.ui.components.screenHeightUiDp() - tileSize) / 2,
                        ),
                        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
                    ) {
                        itemsIndexed(rows, key = { _, r -> r.first().uri.toString() }) { rowIndex, row ->
                            Row(
                                Modifier.fillMaxWidth().arcRow(state, rowIndex),
                                horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
                            ) {
                                row.forEach { img ->
                                    GalleryTile(img, Modifier.weight(1f)) { scope.launch { apply(img) } }
                                }
                                if (row.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (list.size > 1) {
                    CircularSideProgress(progress = { progress }, modifier = Modifier.fillMaxSize())
                }
            }
        }

        // ★ 2026-10-03 用户口径：顶部悬浮「选择背景图」标题胶囊已删除 ——
        //   图片列表本身就是界面主体，顶部不再挂胶囊。toast 保留（设为背景失败时才出现）。

        if (toast != null) {
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 46.dp)) {
                Text(
                    toast!!, fontSize = 11.sp, color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xCC2B2B33))
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/**
 * 圆柱内壁映射（行级弧形变换）：
 * 行中心相对视口中心的归一化偏移 u → 角度 θ=min(|u|·ARC_MAX, 90°)；
 * 屏幕位置 y' = 半视口 · sin(θ)/sin(ARC_MAX)（中段被轻微拉开、两端被压向
 * 视口边缘 = 「间距缩小」）；缩放 cos(θ)（中间最大 100%、边缘 ~64% = 「中间的图片大」）；
 * alpha 按 smoothstep 收暗（「下沉」的既有语汇，同 StackedCardList.sinkFromCenter）。
 * 全部在 graphicsLayer 里读 layoutInfo —— 滚动期零重组。
 *
 * ★ 2026-10-03 修「不允许重叠」（用户真机报「图片管理器有重叠」）：
 *   旧实现把 |u| 直接 coerce 到 1 —— 视口外的行全被压到同一条视口边缘线上，
 *   与刚滚出去的那一行视觉叠在一起（行距≈0.94 半视口，边缘处必然两行同时在）。
 *   现在 θ 一路上探到 90° 才封顶：y' 最远 ≈1.31·halfVp，行在完全滚出视口
 *   （θ≈67° 后即不可见）之前不会停住；映射在可见区间**严格单调**，
 *   任意两行的可视盒子不会相交 —— 重叠被结构性排除。
 */
private fun Modifier.arcRow(state: LazyListState, rowIndex: Int): Modifier = if (com.netmusiclite.data.AppearancePrefs.isM3e) this else graphicsLayer {
    val info = com.netmusiclite.ui.m3e.listLayoutInfo(state)
    val item = info.visibleItemsInfo.firstOrNull { it.index == rowIndex } ?: return@graphicsLayer
    val vpMid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
    val halfVp = (info.viewportEndOffset - info.viewportStartOffset) / 2f
    if (halfVp <= 0f) return@graphicsLayer
    val d = item.offset + item.size / 2f - vpMid
    val u = d / halfVp
    val angle = (abs(u) * ARC_MAX_RAD).coerceAtMost(ARC_90_RAD)
    val y = halfVp * (sin(angle) / ARC_SIN_MAX)
    translationY = (if (u < 0f) -y else y) - d
    val sc = cos(angle)
    scaleX = sc
    scaleY = sc
    val t = abs(u).coerceAtMost(1f)
    alpha = 1f - 0.28f * (t * t * (3f - 2f * t))
}

/** 图块：方形圆角，Coil 按 content:// 解码（自带采样/缓存），点击即设为背景 */
@Composable
private fun GalleryTile(img: SystemImage, modifier: Modifier, onClick: () -> Unit) {
    val ctx = LocalContext.current
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onClick() },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(ctx)
                .data(img.uri)
                .size(320)
                .crossfade(false)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** 权限被拒：给出「重新申请」与免权限的系统相册兜底，不空转 */
@Composable
private fun PermissionPanel(onRequest: () -> Unit, onSystemPicker: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "需要读取相册权限\n才能扫描系统图片",
            fontSize = 12.sp, color = TextSecondary, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        CapsuleAction("申请权限", primary = true, onClick = onRequest)
        Spacer(Modifier.height(9.dp))
        CapsuleAction("从系统相册选择", primary = false, onClick = onSystemPicker)
    }
}

/** 2020 年后无图：兜底走系统相册（可能选到更早的图，由用户自决） */
@Composable
private fun EmptyPanel(onSystemPicker: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "没有找到 2020 年后的图片",
            fontSize = 12.sp, color = TextSecondary, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        CapsuleAction("从系统相册选择", primary = false, onClick = onSystemPicker)
    }
}

/** 胶囊按钮（强调 = 主题色实心；次要 = 玻璃） */
@Composable
private fun CapsuleAction(text: String, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .then(if (primary) Modifier.background(Accent) else Modifier.frostedGlass(shape))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (primary) OnAccent else TextPrimary,
        )
    }
}

/** MediaStore 图片扫描（IO 线程）：2020 年后、新在前；DATE_TAKEN 缺失回落 DATE_ADDED */
private suspend fun scanSystemImages(ctx: Context): List<SystemImage> = withContext(Dispatchers.IO) {
    val collection: Uri = if (Build.VERSION.SDK_INT >= 29)
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val cutoffSec = CUTOFF_2020_MS / 1000
    val sel = "(${MediaStore.Images.Media.DATE_TAKEN} >= $CUTOFF_2020_MS)" +
        " OR ((${MediaStore.Images.Media.DATE_TAKEN} IS NULL OR ${MediaStore.Images.Media.DATE_TAKEN} = 0)" +
        " AND ${MediaStore.Images.Media.DATE_ADDED} >= $cutoffSec)"
    val out = mutableListOf<SystemImage>()
    runCatching {
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
        )
        ctx.contentResolver.query(collection, proj, sel, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val taken = c.getLong(iTaken)
                val dateMs = if (taken > 0) taken else c.getLong(iAdded) * 1000L
                // 二次校验：个别 provider 对 selection 支持不完整，这里兜一层
                if (dateMs < CUTOFF_2020_MS) continue
                out += SystemImage(ContentUris.withAppendedId(collection, c.getLong(iId)), dateMs)
            }
        }
    }
    out.sortedByDescending { it.dateMs }
}
