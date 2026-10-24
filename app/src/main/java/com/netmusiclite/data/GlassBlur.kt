package com.netmusiclite.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView

/**
 * 卡片毛玻璃的**真高斯**模糊引擎（★ 2026-10-16 新增）。
 *
 * 用户口径：「更改深色模式的玻璃卡片效果为现有的高斯模糊库（github），API 不要超过安卓 8」。
 *
 * 库 = **wasabeef/Blurry**（https://github.com/wasabeef/Blurry，`jp.wasabeef:blurry:4.0.0`）：
 *  · `minSdk 21` —— 低于本工程 minSdk 26（安卓 8.0），满足「不超过安卓 8」的硬要求；
 *  · 底层是 `android.renderscript.ScriptIntrinsicBlur` —— **真高斯卷积**，不是本工程原来
 *    那种「三轮盒式叠加」的近似（盒式会把相邻色块和成一片平色，观感是「蒙了层灰」而不是
 *    「玻璃后面透出彩色」，这也是「不够通透」的来源）；
 *  · 库内自带兜底：`Blur.of()` 捕获 `RSRuntimeException` 后回落到自写的 Java stack blur，
 *    所以将来某台设备/RenderScript 被裁掉时只会变慢，不会糊不了；
 *  · **全程不碰 GPU 的 RenderEffect** —— 这正是本次要根治的三星表「模糊范围异常」来源
 *    （见 LyricsScreen / BlurHeader 的改动说明）。
 *
 * ── 为什么走 `into(ImageView)` 这条看着绕的路 ────────────────────────────────
 * 反编译 `blurry-4.0.0.aar` 后确认，Blurry 的公开出口只有三条：
 *   · `Composer.onto(ViewGroup)`   —— 往 View 容器里塞一个 ImageView（View 体系，Compose 用不上）；
 *   · `ImageComposer.get()`        —— 但它必须先 `capture(View)`，而库内走的是
 *     `View.getDrawingCache()`（API 28 起对硬件加速的 View 直接返回 null，不可靠）；
 *   · `BitmapComposer.into(ImageView)` —— **不调 `.async()` 时是同步执行**：
 *     `Blur.of(ctx, bitmap, factor)` → `setImageDrawable(BitmapDrawable(…))`，一气呵成。
 * 所以「造一个游离的 ImageView 接住结果、再从 BitmapDrawable 里取出位图」是**只用公开 API
 * 就能同步拿到 Bitmap** 的唯一路子。ImageView 不参与布局、不 attach 到窗口，只当结果容器。
 * （`Blur` / `BlurFactor` / `BlurTask` 都是包私有，反射或同包桥接都更脆，故不采用。）
 *
 * ⚠ radius 必须夹在 1..25：`ScriptIntrinsicBlur.setRadius` 的硬上限是 25，超了抛的是
 *   `IllegalArgumentException`（**不是** RSRuntimeException，Blurry 不兜底）→ 直接崩。
 * ⚠ sampling 会把源图等比缩小后再糊。本工程要的是「1:1 屏幕坐标的底图，供卡片按自身
 *   屏幕位置取样」，所以默认 `sampling = 1`；源图本身已经是 466 级别的小图，开销可接受。
 */
object GlassBlur {

    /** ScriptIntrinsicBlur 的半径硬上限（超过会抛 IllegalArgumentException） */
    const val MAX_RADIUS = 25

    /**
     * 对 [src] 做真高斯模糊并**同步**返回结果位图（可在任意后台线程调用，不要在主线程序列里跑）。
     *
     * 返回的位图是 Blurry 新分配的 ARGB_8888，调用方持有所有权。
     * 失败（源图退化 / RenderScript 与 stack 兜底同时失败）返回 null，调用方自行降级。
     *
     * @param radius  模糊半径，内部夹到 `1..MAX_RADIUS`
     * @param sampling 降采样倍数，内部夹到 `1..min(w,h)`（Blurry 在缩到 0 边时会直接返回 null）
     */
    fun blur(ctx: Context, src: Bitmap, radius: Int = MAX_RADIUS, sampling: Int = 1): Bitmap? {
        if (src.isRecycled || src.width <= 0 || src.height <= 0) return null
        val r = radius.coerceIn(1, MAX_RADIUS)
        val s = sampling.coerceIn(1, minOf(src.width, src.height))
        return runCatching {
            val holder = ImageView(ctx)
            jp.wasabeef.blurry.Blurry.with(ctx)
                .radius(r)
                .sampling(s)
                .from(src)
                .into(holder)          // 未调 .async() ⇒ 同步执行
            (holder.drawable as? BitmapDrawable)?.bitmap
        }.getOrNull()
    }
}
