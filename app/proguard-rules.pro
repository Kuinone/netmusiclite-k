# Default proguard rules (debug build does not minify)
#


# ★ 2026-10-16 卡片毛玻璃库（wasabeef/Blurry）整包保留：
#   库内 Blur / BlurFactor / BlurTask 都是**包私有**，整条调用链靠同包可见性串起来
#   （详见 data/GlassBlur.kt 的注释）；整个 aar 才 16KB，交给 R8 内联/裁剪的收益极小，
#   却要承担「某条分支被优化掉 → RenderScript 失败时库内兜底失效」的风险，不值得。
-keep class jp.wasabeef.blurry.** { *; }

# ★ 2026-10-18 崩溃报告可读性（CrashLogger 自动导出到 Download）：
#   release 走 R8 默认会改名，导出的栈会变成 a.b.c(...)，用户手里没有 mapping.txt 等于读不懂。
#   -keepnames 只禁止改名，**不阻止裁剪/优化**，体积影响很小；行号属性必须显式保留，
#   否则报告里只剩「at com.netmusiclite.xxx(Unknown Source)」，定位不到具体行。
-keepnames class com.netmusiclite.** { *; }
-keepattributes SourceFile,LineNumberTable
