# NetMusicLite

一块手表上的网易云音乐第三方客户端。Kotlin + Jetpack Compose 单模块 Android 工程，为 Wear OS 圆形表盘（466×466）深度适配并同时支持方形表盘，也可以在普通手机上运行。

> 本项目为非官方第三方客户端，与网易公司无任何隶属或合作关系，仅供个人学习、研究与技术服务商接口协议验证使用。使用前请阅读下方[免责声明](#免责声明)。

本源码树对应已发布的 **v1.34（versionCode 65）**。

## 功能特性

- **播放**：在线播放、歌单/每日推荐/私人漫游、收藏与歌单管理、最近播放与听歌排行、本地音乐扫描、离线下载与缓存、播放队列、睡眠定时、歌曲响度均衡（逐曲响度对齐）
- **歌词**：LRC 逐行同步与逐字高亮、翻译与罗马音同显、聚焦行提亮与逐行扫光动效（可关）、歌词页屏幕常亮开关、行距回弹、边缘模糊遮挡
- **双主题**：经典表盘主题与 Material 3 Expressive 主题（使用向导可切换）；封面主色取色、封面模糊、自定义壁纸、玻璃卡片材质与模糊强度、明暗对调，全部改动即时生效
- **社区**：歌曲评论与回复点赞、歌手页（专辑/粉丝/动态）、歌曲百科、热搜与列表内搜索、歌单/专辑一键收藏、私信与好友（私信支持长按复制与拖选）
- **一起听**：房间页与房内聊天界面、邀请卡识别与进房入口（房间协议实现不在本仓，见下方[本仓不包含](#本仓不包含)）
- **手表适配**：表冠滚动与转动曲线调校、圆形侧边进度环、方形屏直线进度条、按压缩放与双向回弹手势、异形屏安全区、本地传感器捏合手势控制（实验性开关，默认关闭）
- **应用内更新**：从远程版本清单检查新版本，下载后校验摘要并拉起系统安装器

## 系统要求

| 项目 | 要求 |
|---|---|
| 最低支持 | Android 8.0（API 26） |
| 目标平台 | API 35，圆形表盘优先适配 |
| 构建工具 | Android Studio（任意近期版本）+ JDK 17 |
| Gradle | 8.10.2（已带 wrapper，无需手动安装） |

## 本仓不包含

服务端接口与协议相关的实现有意不公开，本仓刻意剔除了以下内容：

- **接口层**：`data/NcmApi.kt`、`data/NcmCrypto.kt`（请求编排与加密封装）
- **听歌识曲**：`data/NcmAudioFingerprint.kt`、`com/netease/nesrsdk/**` SDK 绑定、`assets/ncm_identify_config.json` 及对应 native 库
- **一起听房间协议**：`data/ListenSession.kt`（房间内消息、命令上报与状态同步的实现）
- **二进制资源**：`app/src/main/assets/`（引导截图、打赏与群二维码等）与 `app/src/main/jniLibs/`（第三方 native 库）
- **基线性能配置文件**：`app/src/main/baseline-prof.txt`（它逐条列出完整工程的全部类与方法签名，含接口层，故不发布；可用 `tools/gen_baseline_profile.py` 从完整工程重新生成）
- **过程文档**：协议逆向笔记、发版流程与逐版本变更说明（`app/build.gradle.kts` 里的那段版本史注释同样只留在内部源码树）

因此 **本仓不能独立构建**：`data/`、`ui/` 下约 31 个文件仍引用上述被剔除的类，编译会停在未解析引用。本仓的用途是阅读与参考界面/播放/适配层实现，不是拿来即用的工程。构建完整应用请自行实现接口层并补齐这些调用。

仓库内不含任何账号凭据、Cookie 或用户数据；签名文件与口令也不入库（见 `.gitignore`）。

## 工程结构

```
app/src/main/java/com/netmusiclite/
├── data/              # 播放引擎、会话与缓存、各类偏好存储、封面取色
├── ui/
│   ├── components/    # 通用组件（列表页、图标、表冠滚动、玻璃与模糊、邀请卡）
│   ├── m3e/           # Material 3 Expressive 主题的页面与共享组件
│   ├── nav/           # 导航、转场协调
│   ├── screens/       # 各页面（播放/歌词/一起听/评论/设置/本地/搜索…）
│   └── theme/         # Material 3 主题与色板
├── update/            # 应用内更新通道（版本清单读取与下载校验）
├── MainActivity.kt
└── WmusicApp.kt       # Application：初始化与全局状态

app/src/main/java/com/google/android/material/color/utilities/
                      # 内置的 Material 色度学工具（Cam16/HCT/TonalPalette）
tools/                # 基线性能配置文件生成、SVG 转 VectorDrawable 脚本
```

## 构建

本工程不能独立构建（见上方说明）。若你补齐了自己的接口层与 `ListenSession`，可以按下面的常规方式出包：

```bash
./gradlew assembleDebug

# release 包：仓库不含签名文件时会自动回退 debug 签名并给出警告
./gradlew assembleRelease

# 产物位置
# app/build/outputs/apk/
```

用 Android Studio 打开工程根目录即可导入。需要正式签名时，自行创建 keystore，并在工程根目录提供 `keystore.properties`（含 `storeFile` / `storePassword` / `keyAlias` / `keyPassword` 四项），构建脚本会自动读取；`keystore.properties` 与 keystore 本体都已在 `.gitignore` 中，不会被提交。

## 免责声明

本软件是由音乐爱好者出于个人学习、研究、验证移动端技术与网络协议等非商业目的，利用业余时间独立开发的非官方第三方客户端。本软件基于公开可得的网络接口协议实现，目的在于让用户在智能手表等可穿戴设备上访问其本人已经依法享有的音乐服务权益。本软件并非网易云音乐官方客户端，与网易杭州网络游戏有限公司及网易公司各关联主体（以下统称"权利方"）之间不存在任何隶属、合作、授权、许可、代理、联营或投资关系。开发者亦非权利方的员工、代理人或者受托人。本软件的名称、界面与文档中出现的"网易云""NCM""网易云音乐"等字样与标识，仅为向用户客观描述本软件所适配的服务对象而作描述性使用，相关名称与标识的一切权利均归权利方所有，权利方随时可以要求开发者调整或停止此类使用。

## 开源协议

本项目以 [GPL-3.0](LICENSE) 协议开源。

```
NetMusicLite — Wear OS 网易云音乐第三方客户端
Copyright (C) 2026 昼小风

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
```

二次分发或修改后的版本必须同样以 GPL-3.0 协议开源。`com/google/android/material/color/utilities/` 下的色度学工具来自 Material Components for Android，按其原有的 Apache-2.0 许可使用。
