package com.netmusiclite.ui.theme

import android.content.Context
import androidx.compose.ui.text.font.FontFamily

/**
 * 字重档位（2026-10-03 用户口径「设置主题里增加字体粗细滑块」）。
 *
 * 实现原理：所有 Text 的 FontWeight 最终都经 [FontFamily.Resolver.resolve] 落到具体字体，
 * 这里自建一个整树共用的 Resolver（装配见 [FontWeightBridge]：官方拦截器对
 * 字重平移量在 1..1000 有钳制），再经 LocalFontFamilyResolver 提供给整棵组合树 ——
 * 一次生效、所有 Text 调用点零改动。
 *
 * ⚠ 为什么不「包装原 Resolver」：[FontFamily.Resolver] 是 sealed 接口，跨模块不可实现；
 *   而框架内部装配构件在 Kotlin 侧是 internal —— 故装配链落在 Java 桥里完成。
 */

/** 档位平移量（FontWeight 单位），索引即 AppearancePrefs.fontWeightLevel */
val FONT_WEIGHT_SHIFTS: IntArray = intArrayOf(-100, 0, 100, 200)

/** 档位中文名（设置页滑块右侧读数用，顺序与 [FONT_WEIGHT_SHIFTS] 一一对应） */
val FONT_WEIGHT_LABELS: List<String> = listOf("细", "标准", "加粗", "特粗")

/**
 * 构建「全局字重平移 shift」的字体解析器。
 *
 * 平移量 = 本应用档位 + 系统「粗体文本」调整（Android 12+ 的 fontWeightAdjustment）——
 * 自建链不读系统配置，桥里手动补上，免得开了系统粗体的用户升级后反而变细。
 */
fun shiftedFontFamilyResolver(context: Context, shift: Int): FontFamily.Resolver =
    FontWeightBridge.shiftedResolver(context.applicationContext, shift)
