<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="108" height="108" alt="LMPlayer Logo" />
</p>

<h1 align="center">🎵 LMPlayer (柠檬音乐客户端)</h1>

<p align="center">
  <strong>专为大屏pad与 Android 智能设备量身打造的高保真无损音乐播放器</strong>
</p>

<p align="center">
  <a href="https://github.com/zyhub/LMplayer/releases"><img src="https://img.shields.io/github/v/release/zyhub/LMplayer?color=FA2D48&label=%E6%9C%80%E6%96%B0%E7%89%88%E6%9C%AC" alt="Latest Release" /></a>
  <img src="https://img.shields.io/badge/Platform-Android%208.0%2B-blue?logo=android" alt="Android Platform" />
  <img src="https://img.shields.io/badge/Language-Kotlin%20%7C%20Jetpack%20Compose-purple?logo=kotlin" alt="Language" />
  <img src="https://img.shields.io/badge/Player-Media3%20ExoPlayer-brightgreen" alt="Media3" />
  <img src="https://img.shields.io/badge/License-MIT-orange" alt="License MIT" />
</p>

---

## 📖 项目简介

**LMPlayer** 是一款面向平板大屏与智能手机双端体验深度调优的高保真无损音乐播放器。

项目专属无缝对接 **柠檬音乐 (Lemon Music)** 服务端私有云曲库，并深度融合落雪与主流音源能力（支持 **酷我、网易云、QQ 音乐、酷狗、咪咕** 五大音源全网聚合搜索与试听）。具备本地曲库全盘高速扫描与智能比对、全屏沉浸式超大封面、原位动效歌词微调面板、轻奢级下拉菜单设置中心以及强大的本地已下载歌曲多选批量管理系统。

> 🍋 **配套柠檬音乐服务端仓库**：
> - GitHub 官方仓库：[https://github.com/jia070310/lemon-muisc](https://github.com/jia070310/lemon-muisc)
> - 介绍：专为飞牛 NAS / Linux 原生打造的轻量级音乐后端工具，支持多用户、落雪音源集中托管、在线全网检索与云端私有曲库缓存。LMPlayer 与其 API 深度互通，实现全链路音乐流媒体闭环。

---

## 📸 应用界面截图 (Screenshots)

### 📱 竖屏界面预览 (Portrait UI)

<div align="center">

| 1. 发现 · 在线云端推荐 | 2. 播放器 · 超大高清封面 |
| :---: | :---: |
| <img src="docs/screenshots/01_discover_home.png" width="360" /> | <img src="docs/screenshots/02_player_cover.png" width="360" /> |
| **3. 歌词 · 浮层原位调节** | **4. 资料库 · 云端与本地曲库** |
| <img src="docs/screenshots/03_lyrics_adjust.png" width="360" /> | <img src="docs/screenshots/04_library_screen.png" width="360" /> |
| **5. 设置中心 · 轻奢下拉选择** | |
| <img src="docs/screenshots/05_settings_screen.png" width="360" /> | |

</div>

### 🖥️ 平板大屏与横屏沉浸体验 (Landscape & Tablet UI)

<div align="center">

| 1. 横屏 · 在线发现推荐与权威官方榜单 |
| :---: |
| <img src="docs/screenshots/landscape_discover.png" width="750" /> |

| 2. 横屏 · 沉浸式高保真播放器与实时双列歌词 |
| :---: |
| <img src="docs/screenshots/landscape_player.png" width="750" /> |

| 3. 横屏 · 资料库聚合、歌单与最近添加 |
| :---: |
| <img src="docs/screenshots/landscape_library.png" width="750" /> |

</div>

---

## ✨ 核心特性

### 1. 🍋 柠檬音乐云端专属对接
- **私有云互联**：深度对接 [lemon-muisc](https://github.com/jia070310/lemon-muisc) 柠檬服务端，同时兼容 Subsonic、Navidrome 等协议；
- **云端歌单与收藏同步**：实时同步个人专属歌单、历史播放记录与红心喜欢曲目；
- **服务器保存路径控制**：支持动态读取并切换服务端存储与下载目录。

### 2. 🌐 五大在线音源融合与无损试听
- **全网多源聚拢**：融合集成 **酷我 (KUWO)、网易云 (NETEASE)、QQ 音乐 (QQ)、酷狗 (KUGOU)、咪咕 (MIGU)**；
- **分网试听音质**：独立支持 Wi-Fi 与移动流量状态下的试听音质偏好（128K、320K、FLAC、Hi-Res）；
- **智能容灾换链**：在线播放遇到音源限制时自动尝试备用源，保障流畅无感切歌。

### 3. 🏷️ 本地下载音频标签、高清封面与同步歌词内嵌 (v1.5.0 新增)
- **ID3v2.3 规范内嵌 (MP3)**：写入标准 TIT2 歌名、TPE1 歌手、TALB 专辑、APIC 高清封面图以及 USLT 同步/非同步 LRC 滚动歌词；
- **FLAC 官方规范内嵌**：注入标准 VORBIS_COMMENT (`TITLE`, `ARTIST`, `ALBUM`, `LYRICS`) 与 METADATA_BLOCK_PICTURE 封面图；
- **伴随 .lrc 独立歌词文件生成**：下载时自动在歌曲同级目录输出同名 `.lrc` 文件，删除歌曲时联动清理；
- **存量历史歌曲一键补全**：在下载管理中提供单曲「重新内嵌封面与歌词」以及看板「补全全部标签」快捷操作，断网离线、平板、U盘与第三方播放器完美兼容！

### 4. 📥 本地已下载歌曲深度管理
- **本地下载存储看板**：直观展示已下载歌曲总数、音频占用磁盘大小及设备剩余可用空间；
- **多选批量管理**：支持一键进入批量编辑模式，快速全选、反选，实时统计选中大小并支持二次确认批量物理删除；
- **存储详情与路径复制**：查看本地绝对存储路径并支持一键复制到剪贴板，详尽展示文件大小、格式与码率规格。

### 5. 🎵 极致播放与交互美学
- **超大专辑封面展示**：竖屏自适应满幅大图，横屏双列对称视效，高精度纹理采样；
- **喜欢按钮 0ms 反馈**：点按喜欢按钮瞬间点亮红心，双向同步 Room 数据库与远程服务端；
- **歌词调节浮层展出**：告别全屏阻塞弹窗，以原生 DropdownMenu 浮层卡片调节字号、时间同步与 6 套 Apple Music 灵动主题；
- **轻奢级全量下拉选择**：设置中心所有配置（音源、音质、目标、外观）全面升级为精致下拉菜单。

### 6. 🏝️ 全品牌安卓灵动岛与实时歌词上岛 (v1.5.6 新增)
- **主流厂商原生上岛适配**：深度适配 **小米澎湃 OS (HyperOS 超级岛/焦点通知)**、**OPPO ColorOS (流体云 Fluid Cloud)**、**vivo OriginOS (原子岛 Atomic Island)**、**荣耀 MagicOS (灵动胶囊 Magic Capsule)** 及 **魅族 Flyme 状态栏歌词**；
- **高清封面与进度直推**：后台自动提取本地音频内嵌封面或云端专辑图注入 `MediaSession` 与 `MediaStyle` 通知，确保系统灵动岛 100% 呈现专辑彩图与进度条；
- **内置交互式灵动岛胶囊**：支持紧凑胶囊态（黑胶旋转封面 + 实时同步歌词翻滚 + 四柱音频频谱律动）与展开播控大卡（双行同步歌词、Hi-Res 徽章、进度条、喜欢与切歌操控），支持左右滑动胶囊快速切歌。

### 7. 🔄 账号与服务原生在线更新
- **GitHub 自动通道**：设置页「账号与服务」直通 GitHub 官方 Releases 仓库；
- **一键静默检测与升级**：在线解析最新版本与更新日志，内置断点下载进度条并自动唤起系统安装器。

---

## 🛠 技术架构与选型

| 模块 | 技术选型 | 说明 |
| :--- | :--- | :--- |
| **开发语言** | Kotlin 1.9+ | 现代、简洁、空安全 |
| **UI 框架** | Jetpack Compose + Material 3 | 声明式响应式界面架构 |
| **音频引擎** | AndroidX Media3 ExoPlayer | 高保真硬件加速解码，支持本地/网络双流缓冲 |
| **元数据引擎** | 纯 Kotlin 原生标签处理器 | 零外部依赖解析并内嵌 ID3v2.3、FLAC Vorbis Comment 及 MP4 ilst |
| **网络通信** | OkHttp 4 + Retrofit 2 | 连接池复用、SSL 双向兼容、流式断点续传 |
| **本地持久化** | Jetpack Room (SQLite) | 结构化存储歌曲元数据、歌单、下载任务与服务配置 |
| **图片加载** | Coil Compose | 异步内存三级缓存、圆形裁切、高斯模糊特效渲染 |

---

## 📦 下载与安装

您可以从 GitHub 官方 Releases 页面获取最新版预编译 APK：

👉 **[前往下载最新发布版 APK](https://github.com/zyhub/LMplayer/releases)**

- 最新版本：`v1.7.14`
- 架构支持：`armeabi-v7a`, `arm64-v8a` (主流 32/64 位移动与平板架构)
- 最低系统要求：Android 8.0 (API Level 26) 及以上的平板或智能手机

---

## 💻 本地源码构建指南

### 环境要求
- **Android Studio**：Ladybug (2024.2.1) 或更高版本
- **JDK**：OpenJDK 17
- **Gradle**：8.7+

### 构建步骤
```bash
# 1. 克隆代码仓库
git clone https://github.com/zyhub/LMplayer.git
cd LMplayer

# 2. 编译 Release 正式安装包
./gradlew assembleRelease

# 3. 输出产物位于:
# app/build/outputs/apk/release/app-release.apk
```

---

---

## 免责声明

### 词语约定
本协议中的“本项目”指 LMPlayer（柠檬音乐客户端）移动端与平板端开源项目及其代码实现；“使用者”指下载、安装、编译、运行或以任何形式使用本项目的个人或组织；“自建服务端”指由使用者个人自行部署、配置并授权连接的私有音乐服务（包括但不限于柠檬音乐服务端 lemon-muisc、Subsonic、Navidrome 等协议服务）；“第三方音乐平台”指客户端或服务端通过公开网络接口进行检索或引用之外部平台的统称；“版权数据”指包括但不限于音频文件、封面图片、歌词文本、歌曲及艺术家元数据等由相关权利人拥有合法著作权或版权的数字内容。

### 一、数据与音频来源
1.1 本项目定位为纯粹的本地及流媒体播放器客户端工具，客户端本身不内置任何中心化音乐曲库，不提供、不制作、不存储、亦不主动分发任何音频、歌词与封面等版权资源。

1.2 本项目中展示的在线曲库、个人歌单、收藏列表及播放历史等数据，均直接拉取自使用者自建并连接的私有云服务端或使用者本地存储设备。本项目对使用者私有服务端或本地存储内所包含内容的合法性、合规性及准确性不承担任何审核义务或连带责任。

1.3 本项目的在线检索、榜单及试听功能，其技术原理为将使用者的检索请求转发至其配置的自建服务端或公开发布的网络接口，并仅对返回的公开网络流媒体链接进行流式解码与播放展示。本项目无法亦无权对第三方接口返回内容的合法性与准确性进行校验，对于可能出现的音画不对应、链接失效或内容偏差不承担任何保证责任。

### 二、版权数据与合理使用
2.1 使用本项目播放、解析或本地缓存过程中所涉及的各类版权数据，其知识产权与所有权均完整归属于原版权方、唱片公司、原作者或合法权利人所有。

2.2 使用者通过本客户端试听或临时缓存的任何音频资源，仅供个人技术研究、播放测试与学习交流使用。为了避免潜在侵权风险，使用者务必在体验后 24 小时内自行彻底清除所产生的缓存与离线文件。

### 三、音乐平台名称与标识
3.1 本项目界面中出现的第三方音乐平台名称或代称，仅用于向使用者客观说明数据检索与接口解析的技术来源分类，不包含任何恶意，亦不代表本项目与相关平台存在任何官方合作、授权或商业关联。若相关平台认为该称呼或标注不妥，可联系本项目进行调整或移除。

### 四、界面资源使用
4.1 本项目内使用的开源字体、矢量图标及示例素材等资源均来源于互联网公开发布的开源或免费商用渠道。若存在权利冲突，权利人可联系本项目，我们将第一时间予以核实并移除。

### 五、免责条款
5.1 本项目按“现状”（AS-IS）提供，不附带任何形式的明示或暗示担保。由于使用、无法使用本项目、连接不受信任的服务端、或网络传输异常所引起的任何直接、间接、偶然、特殊或继发性损害（包括但不限于设备故障、数据丢失、流量超额、商誉损失等），均由使用者自行承担，本项目及开发者不承担赔偿责任。

### 六、使用限制与合规义务
6.1 本项目完全免费且开源发布于 GitHub，旨在面向全球开发者交流探索 Android 系统架构、Jetpack Compose 现代化界面以及 AndroidX Media3 音频引擎的技术实现。

6.2 禁止在违反使用者所在国家及地区法律法规的前提下使用本项目。严禁任何个人或组织将本项目用于商业运营、商业分发、收费打包、二次转售或任何侵犯他人合法知识产权的违法行为。凡因使用者违规操作所导致的任何纠纷、指控与法律责任，完全由使用者自行承担，本项目不承担任何由此产生的直接或间接法律责任。

### 七、版权保护与支持正版
7.1 音乐创作与正版平台维护不易。本项目强烈倡导并呼吁所有使用者尊重版权，支持正版音乐事业，通过官方合法授权渠道购买正版专辑及订阅官方音乐会员服务。

### 八、非商业性声明
8.1 本项目为纯粹的业余技术探索与学术研究作品，不接受任何形式的商业合作、付费广告、内购收费或商业性质资助。

### 九、协议生效与接受
9.1 凡下载、安装、编译、运行或以任何形式使用本项目的行为，即视为使用者已完整阅读、完全理解并自愿接受本协议的全部条款与约束。若您不同意本协议的任一条款，请立即停止使用并彻底删除本项目的所有相关程序及代码文件。

---

## 📄 开源许可证

本项目基于 [MIT License](LICENSE) 开源，欢迎提交 Issue 与 Pull Request 共同完善 LMPlayer。
