package com.netmusiclite.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 媒体库读取权限（内置图片管理器 / 导入本地音乐共用）。
 *
 * 版本分叉：API 33+ 用细分媒体权限（READ_MEDIA_IMAGES / READ_MEDIA_AUDIO），
 * API 26~32 两者都落在 READ_EXTERNAL_STORAGE（Manifest 里 maxSdkVersion=32）。
 *
 * 申请时机由调用方决定 —— 用户口径「在使用相关功能的时候获取权限」：
 * 进内置图片管理器即弹（页面进入自动扫描）、导入页在点「扫描本地音乐」那一刻弹。
 */
object MediaPermissions {
    fun images(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun audio(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun granted(ctx: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
}
