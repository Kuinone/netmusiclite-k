package com.netmusiclite.ui.theme;

import android.content.Context;

import androidx.compose.ui.text.font.AndroidFontLoader;
import androidx.compose.ui.text.font.AndroidFontResolveInterceptor;
import androidx.compose.ui.text.font.FontFamily;
import androidx.compose.ui.text.font.FontFamilyResolverImpl;
import androidx.compose.ui.text.font.FontListFontFamilyTypefaceAdapter;
import androidx.compose.ui.text.font.FontWeightAdjustmentHelper;
import androidx.compose.ui.text.font.PlatformFontFamilyTypefaceAdapter;
import androidx.compose.ui.text.font.TypefaceRequestCache;

/**
 * 用 Java 而非 Kotlin 写这层桥：上述 ui-text 装配构件在 Kotlin 元数据里是 internal
 * （Kotlin 编译器直接拦），但字节码层全是 public —— javac 不看元数据，可原样装配
 * createFontFamilyResolver(context) 的同款 Resolver，只把字重调整量换成
 * 「系统粗体调整 + 应用档位」。R8 走引用重写，直接引用即可，无需 keep 规则。
 */
public final class FontWeightBridge {
    private FontWeightBridge() {
    }

    public static FontFamily.Resolver shiftedResolver(Context context, int userShift) {
        int systemAdjust = FontWeightAdjustmentHelper.INSTANCE.getFontWeightAdjustment(context);
        return new FontFamilyResolverImpl(
                new AndroidFontLoader(context),
                new AndroidFontResolveInterceptor(systemAdjust + userShift),
                new TypefaceRequestCache(),
                new FontListFontFamilyTypefaceAdapter(),
                new PlatformFontFamilyTypefaceAdapter());
    }
}
