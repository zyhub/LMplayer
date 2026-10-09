# LMPlayer iOS 移植方案

> 目标：手机端 iOS 移植，UI 贴合 Apple 最新系统（iOS 26/27）的 Liquid Glass「水滴」设计语言，最低支持 iOS 16。
> 基线版本：Android v1.7.14（Kotlin 1.9.23 / Compose / Media3 1.3.1，48 个 Kotlin 文件，约 39,431 行）

---

## 0. 结论摘要

| 项目 | 结论 |
| :--- | :--- |
| **推荐路线** | **KMP 共享核心 + SwiftUI 原生 UI**（不是 Compose Multiplatform 复用 UI） |
| **核心复用率** | 网络/数据/歌词/标签/匹配算法 **约 80~85%** 可直接进 `commonMain` |
| **UI 复用率** | 0%（SwiftUI 重写），但设计规格 100% 可迁移（颜色/字号/圆角/CoverFlow 公式/流体路径公式均为纯数学） |
| **最大功能降级** | 悬浮灵动胶囊（1671 行跨应用悬浮窗）→ 只能降级为 Live Activities |
| **最大隐性风险** | App Store 审核上架基本无望，需走 TestFlight / 自签侧载 |
| **工期估算** | 单人全职 **7~10 个月**；2~3 人协作 **4~6 个月** |

---

## 1. 代码现状盘点

### 1.1 分层与可移植性

| 层 | 行数 | 可复用 | 说明 |
| :--- | ---: | ---: | :--- |
| 网络 + 数据 + 模型 | ~5,057 | **~85%** | 手写 OkHttp + org.json，无 Retrofit/Gson |
| 媒体业务逻辑 | ~4,000 | **~90%** | 歌词解析、标签内嵌、队列管理、匹配算法 |
| 媒体平台集成 | ~5,000 | ~10% | Media3/AudioManager/WindowManager，必须重写 |
| UI + 设计系统 | ~23,000 | 0%（SwiftUI 路线） | Compose 声明式，设计规格可迁移 |
| 其他（Application/更新/车机） | ~2,000 | ~20% | |

### 1.2 关键勘察发现（影响方案走向）

1. **无 ViewModel / Hilt / 导航库**。全部状态为 Composable 内 `remember { mutableStateOf }`，全局状态来自单例 `object` 的 `StateFlow`（`PlaybackQueueManager`），导航是自研 enum 状态机（`Screen { HOME, LIBRARY, DOWNLOADS, SETTINGS }`）+ 手写回退栈。
   → 这一点**对移植有利**：状态层没有 Android-ViewModel 依赖，结构简单，容易在 Swift 侧用 `@Observable` / `ObservableObject` 重建。
2. **Retrofit 2 与 Gson 是死依赖**。`build.gradle.kts` 声明了但全仓零使用（`grep` 0 命中）。实际是 46 处手写 `client.newCall(req).execute()` + `org.json` 手工取字段。
3. **`AdaptiveFrostedSurface`（唯一真实 GPU 模糊组件）是死代码**，全仓仅定义无调用。**全站所有"毛玻璃"实为半透明纯色**（`Color(0xFF222228).copy(alpha = blurAlpha)`，`blurAlpha` 默认 0.85，用户可在设置里拖 0.2~1.0）。
   → 意味着：**当前 App 并没有真正的背景模糊**。迁移到 iOS 用 Liquid Glass 是一次**视觉升级**，而不是等价替换。这反而是移植的最大产品价值点。
4. **`DynamicIslandCapsule.kt` 也是死代码**（实为"引导开启灵动岛"弹窗，零引用）。`androidx.palette` 同样零使用。
5. **没有任何音效链**：全仓无 `Equalizer`/`BassBoost`/`AudioProcessor`/`AudioEffect`。移植难度因此显著降低。
6. **播放列表是自研的**：未使用 ExoPlayer 原生 playlist，队列由 `PlaybackQueueManager` 管理，切换靠 `player.setMediaItem(单曲)`。
   → **当前没有 gapless 无缝播放**。iOS 用 `AVQueuePlayer` 可顺带补上这个体验。
7. **APE/DSD/WMA 实际播不了**：代码把它们当可播放格式（`PlaybackRouter.kt:125-127`、`LocalMediaScanner.kt:20`），但 ExoPlayer 1.3.1 无扩展 Renderer 时无法解码。**iOS 的 AVFoundation 同样不支持**——这是既有产品预期偏差，移植时应一并决策（砍掉，或引入 FFmpeg 软解）。

---

## 2. 三个硬约束（决定一切的前提）

### 2.1 约束一：Liquid Glass 是 iOS 26+ 的 API

- `.glassEffect()`、`GlassEffectContainer`、`.buttonStyle(.glass)` **仅 iOS 26 及以上存在**；无保护地调用会在旧系统崩溃。iOS 27 对 Liquid Glass 做了精修（系统级透明度滑杆、暗色描边 + 更强高光、滚动时统一工具栏），但 API 层面兼容。
- 你要求**最低 iOS 16**，因此 iOS 16~25 必须走 `.ultraThinMaterial` / `.regularMaterial` 降级。
- **纪律要求**：绝不允许在各业务页面散落 `#available(iOS 26, *)` 判断，必须收敛到统一的设计系统入口（见第 4 章）。
- **iOS 27 的两个新行为必须尊重**：① 用户可在系统设置里无级调节全局透明度；② 开启「降低透明度 / 提高对比度」后系统会自动钝化玻璃。App 不能硬编码不透明度去对抗系统设置。

### 2.2 约束二：iOS 系统能力边界（部分功能永久不可实现）

完整清单见第 5 章。摘要：

- ❌ 跨应用悬浮窗（`TYPE_APPLICATION_OVERLAY`）— iOS 无此能力
- ❌ 厂商灵动岛私有通道（小米超级岛 / OPPO 流体云 / vivo 原子岛 / 荣耀灵动胶囊）— iOS 生态不存在
- ❌ 开机自启 — iOS 不允许 App 随系统启动被拉起
- ❌ 应用内自更新安装 — iOS 禁止（只能 App Store / TestFlight）
- ❌ 全盘本地媒体扫描 — iOS 沙盒限制
- ⚠️ 任意物理按键映射（方向盘 / 耳机 keycode）— 只能用 `MPRemoteCommandCenter` 固定命令集
- ⚠️ 后台保活 WakeLock — 无对应物，只能靠 audio session

### 2.3 约束三：分发通道（**最需要你提前拍板的一点**）

Android 版走 GitHub Releases 直接发 APK。**iOS 没有等价路径**。且本 App 聚合五大音源（酷我/网易云/QQ/酷狗/咪咕）并提供下载，**几乎必然触发 App Store 审核条款 5.2.3 / 4.7 的版权与「未授权内容」红线**。

现实可选通道：

| 通道 | 成本 | 限制 | 适用性 |
| :--- | :--- | :--- | :--- |
| App Store | $99/年 | 需过审 | ⚠️ 本 App 大概率被拒 |
| TestFlight | $99/年 | 外部测试需过审、构建 90 天过期、上限 1 万人 | ✅ **最现实的公开分发方式** |
| Ad Hoc | $99/年 | 每年仅 100 台设备，需收集 UDID | ✅ 小范围/自用 |
| AltStore / SideStore 侧载 | $99/年 或免费账号 | 免费账号签名 **7 天过期**；需常驻中转 | ⚠️ 体验差 |
| 企业分发 | $299/年 | 需实体企业资质 + Apple 审批；公开分发违反协议 | ❌ 不建议 |

> **建议**：把 iOS 版定位为 **TestFlight 公开测试 + Ad Hoc 自用**，不要为过 App Store 而阉割音源聚合能力。
> **注意**：`com.apple.developer.networking.multicast`（DLNA 组播必需）需 Apple 单独审批，免费账号不可用——这决定了 DLNA 投屏是否保留。

---

## 3. 技术路线选择

### 路线对比

| | A. Compose Multiplatform | B. SwiftUI 全量重写 | **C. KMP 核心 + SwiftUI UI（推荐）** |
| :--- | :--- | :--- | :--- |
| UI 复用 | ~60~70% | 0% | 0% |
| 核心逻辑复用 | ~85% | 0%（需翻成 Swift） | **~85%** |
| Liquid Glass 贴合度 | ❌ **拿不到** | ✅ 完全 | ✅ 完全 |
| Live Activities / 灵动岛 | ❌ 无法实现（须 Swift + WidgetKit） | ✅ | ✅ |
| 与 Android 同步维护 | ✅ 单一 UI 代码库 | 双份 | 双份 UI，单一核心 |
| 上手成本 | 需 Kotlin 2.x + CMP 升级 | 需 Swift 团队 | 需 KMP 工程化能力 |

### 为什么否掉 A（Compose Multiplatform）

CMP 的 iOS 端自 1.8.0（2025.05）起已 Stable，工程上完全可用。但它**结构性拿不到 Liquid Glass**：

1. `.glassEffect()` 是 SwiftUI API，CMP 无法调用。
2. CMP 自绘整套 UI，**不会继承系统 chrome**——TabBar、NavigationStack、Sheet、工具栏的玻璃材质、滚动边缘效果、morphing 过渡全部没有。
3. 系统级「透明度滑杆」「降低透明度」辅助功能无法参与。

你明确要求"贴合苹果最新的水滴元素"，用 CMP 只能画一个"看起来像"的玻璃，与系统原生质感始终有差异（尤其在材质折射、边缘高光、与系统组件的衔接上）。

**且更关键**：灵动岛替代方案（Live Activities）**必须**是 Swift + WidgetKit 扩展，CMP 完全无法实现。既然 iOS 侧无论如何都要有原生 Swift 层，UI 再用 CMP 就只剩"省人力"这一个理由了。

### 为什么推荐 C

核心层（网络协议 3512 行、歌词解析 1481 行、标签内嵌 682 行、同名歌曲匹配 666 行、下载引擎 1040 行）是这个项目最有价值的资产，且**绝大多数是平台无关的纯 Kotlin**——把它抽成 KMP 共享模块，Android 与 iOS 共用一份，是投入产出比最高的做法。UI 层用 SwiftUI 重写以拿到原生质感。

> 若团队**完全没有** Kotlin/Native 经验，且希望最快出可用版本，则退而求其次选 **B（SwiftUI 全量重写）**——放弃核心复用，换取工程结构最简单。以本项目核心层仅 8000 行有效逻辑计算，B 比 C 大约多 2~3 个月工作量。

---

## 4. 目标架构

### 4.1 模块划分

```
LMPlayer/
├── androidApp/              # 现有 app/（保持不变，逐步切到 shared）
├── shared/                  # KMP 共享模块（commonMain）
│   ├── model/               # UnifiedSong/Album/Artist/Playlist/ServerConfig…（Models.kt 直接搬）
│   ├── network/             # LemonMusicProtocol（Ktor 重写底座，业务逻辑照搬）
│   ├── database/            # SQLDelight（50 条 SQL 从 DAO 1:1 翻译）
│   ├── lyrics/              # LrcParser + SmartCharsetDecoder + EmbeddedLyricsExtractor
│   ├── tagwriter/           # AudioMetadataEmbedder（ID3v2.3 / FLAC / M4A 字节算法）
│   ├── matching/            # SongMatchingResolver（同名多版本防串歌）
│   └── queue/               # PlaybackQueueManager 的队列/持久化/最近播放匹配
├── iosApp/                  # SwiftUI（iOS 16+）
│   ├── DesignSystem/        # ★ 三档液滴设计系统（本方案核心）
│   ├── Playback/            # AVQueuePlayer + AVAudioSession + 播放调度
│   ├── NowPlaying/          # MPNowPlayingInfoCenter + MPRemoteCommandCenter
│   ├── LiveActivity/        # ★ WidgetKit 扩展（灵动岛 / 锁屏实时活动）
│   ├── Features/            # Home / Library / Downloads / Settings / Player
│   ├── Platform/            # 文件、下载、Network.framework、路由
│   └── Bridge/              # KMP 框架调用封装
└── LMPlayerTV/              # 现有 TV 模块（本次范围外）
```

### 4.2 核心层「换地基」清单（约 400~700 行）

| 现状 | 替换为 | 影响面 |
| :--- | :--- | :--- |
| OkHttp（46 处 `newCall().execute()`） | **Ktor Client**（协程原生，KMP 官方） | `NetworkClientFactory` 整体重写 |
| `org.json`（131 处 `JSONObject` + 53 处 `JSONArray`） | **kotlinx.serialization** | 全部响应解析点 |
| Room（5 实体 / 50 `@Query` / 返回已是 `Flow`） | **SQLDelight** | SQL 语句可 1:1 复制，只换注解语法 |
| `android.util.Log`（33 处） | expect/actual logger | 机械替换 |
| `Context` + `SharedPreferences` + `ConnectivityManager`（11 行） | `NWPathMonitor` + Multiplatform Settings | 仅 2 个静态方法 |
| `java.io.File` / `RandomAccessFile`（遍布） | **okio** 或 platform FileSystem | 影响面最广的一项 |
| `java.net.{Socket,DatagramSocket,ServerSocket}`（DLNA/投屏/内置 HTTP 服务） | `Network.framework`（`NWBrowser`/`NWListener`） | 见第 5 章 |
| `java.security.MessageDigest` | okio HashingSource 或平台 crypto | 局部 |
| `java.util.concurrent.ConcurrentHashMap` | 协程 + Mutex / kotlinx.atomicfu | 11 处缓存 |

> 说明：`LemonMusicProtocol.kt` 3512 行中，**只有 33 行 Log + 11 行 Context 是平台相关的**，其余 98.7% 是纯 Kotlin 业务逻辑。这是整个方案最重要的乐观依据。

---

## 5. 水滴设计系统（本方案核心）

### 5.1 三档降级架构

```
Tier 1 ─ iOS 26 / 27+        → 原生 Liquid Glass
Tier 2 ─ iOS 16 ~ 25         → .ultraThinMaterial / .regularMaterial 近似
Tier 3 ─ 降低透明度 / 提高对比度开启 → 实色降级（无透明材质）
```

**Tier 判定与统一入口**（业务代码永不写 `#available`）：

```swift
// DesignSystem/Glass/GlassTier.swift
import SwiftUI

enum GlassTier {
    case liquid      // iOS 26+
    case material    // iOS 16–25
    case solid       // 辅助功能降级

    static var current: GlassTier {
        if UIAccessibility.isReduceTransparencyEnabled { return .solid }
        if #available(iOS 26.0, *) { return .liquid }
        return .material
    }
}
```

```swift
// DesignSystem/Glass/AdaptiveGlass.swift
// 唯一的玻璃入口：全项目只此一处出现 #available
struct AdaptiveGlass<S: Shape>: ViewModifier {
    var role: GlassRole          // .chrome | .floating | .content
    var shape: S
    var interactive: Bool = false

    func body(content: Content) -> some View {
        switch GlassTier.current {
        case .liquid:
            if #available(iOS 26.0, *) {
                content.glassEffect(interactive ? .regular.interactive() : .regular, in: shape)
            } else {
                material(content)
            }
        case .material:
            material(content)                      // 材质 + 1px 高光描边，模拟折射
        case .solid:
            content.background(.background.secondary, in: shape)
                   .overlay(shape.strokeBorder(.separator, lineWidth: 0.5))
        }
    }

    @ViewBuilder private func material(_ content: Content) -> some View {
        // 对齐 iOS 27 的「暗色描边 + 更强高光」，让三档观感统一
        switch role {
        case .chrome:   content.background(.ultraThinMaterial, in: shape)
        case .floating: content.background(.regularMaterial,   in: shape)
        case .content:  content.background(.background.secondary, in: shape)
        }
    }
}

extension View {
    func adaptiveGlass(_ role: GlassRole = .chrome,
                       in shape: some Shape = .capsule,
                       interactive: Bool = false) -> some View {
        modifier(AdaptiveGlass(role: role, shape: shape, interactive: interactive))
    }
}
```

**iOS 26+ 独有增强**（用 `if #available` 包裹在组件内部，不外泄）：

```swift
// 多个玻璃元素合并 + morphing 过渡（仅 iOS 26+）
@available(iOS 26.0, *)
struct MorphingGlassBar: View {
    @Namespace private var ns
    var body: some View {
        GlassEffectContainer {
            HStack { /* 播控按钮 */ }
                .glassEffectID("controls", in: ns)
        }
    }
}
```

### 5.2 组件映射（Android → iOS）

| Android 现状 | iOS Tier 1（26+） | iOS Tier 2（16–25） |
| :--- | :--- | :--- |
| `blurAlpha` 半透明纯色（全站） | `.glassEffect(.regular)` | `.regularMaterial` + 高光描边 |
| `CompactMorphingBottomBar` 胶囊底栏 | `.glassEffect(.regular, in: .capsule)` + `.buttonStyle(.glass)` | `.ultraThinMaterial` 胶囊 |
| `FloatingControls` 悬浮控件 | 同上，`interactive: true` | `.regularMaterial` |
| 全屏播放器（`FullscreenPlayerSheet`） | 全屏 `sheet` + 系统玻璃工具栏 | `presentationDetents` + 材质 |
| 设置页 `DropdownMenu` | 系统 `Menu`（自动玻璃） | 系统 `Menu` |
| 待播队列 `ModalBottomSheet` | 系统 `sheet`（自动玻璃） | 系统 `sheet` |
| `DiscoverLiquidCard` 流体卡片 | **内容层 → 不用玻璃**，见 5.3 | 实色 + 渐变 |
| 歌词浮层调节面板 | 系统 `Menu` / popover | 材质 popover |
| 状态栏透明处理（`Theme.kt:162-174`） | 删除，交给系统 | 删除 |

### 5.3 设计纪律（必须遵守，否则会做"像 iOS 26 但不对"的界面）

1. **内容层去玻璃化**。Apple 的明确指引：Liquid Glass 用于 **chrome / 导航 / 浮动控件**，不用于**内容密集表面**。当前 App 几乎给所有卡片都上了毛玻璃（`LocalMusicHomeScreen.kt:497`、`LemonDiscoverHomeScreen.kt:114`、`LocalLibraryScreen.kt:138`）。移植时必须重新划分：
   - ✅ 用玻璃：底栏、浮动播控、工具栏、手势胶囊、迷你播放器
   - ❌ 不用玻璃：歌曲列表、专辑网格、歌词正文、设置项、下载列表
   > 这正好呼应 iOS 27 的「可读性修复」——Apple 自己也在给玻璃加暗色描边来救文字可读性。
2. **不叠玻璃**。玻璃上不要再放玻璃，会浑浊。层级用「玻璃（chrome）→ 材质（浮层）→ 实色（内容）」三级。
3. **不硬编码不透明度**。iOS 27 有系统级透明度滑杆，且在「降低透明度」下自动钝化。App 层设置里的"毛玻璃特效透明度"滑杆（`SettingsScreen.kt:1517-1519`）在 iOS 上应**移除**，改为尊重系统设置。
4. **滚动边缘交给系统**。iOS 27 会在内容滚到浮动栏下方时自动加统一工具栏底衬——用 `.toolbarBackground` / scroll edge effect，不要自己画。
5. **动效优先用系统组件**。TabBar / Sheet / Menu 的 morphing 过渡由系统免费提供，自绘会失去连贯性。

### 5.4 可 100% 迁移的设计规格（纯数学，与平台无关）

- 调色板 hex：`AppleRed = 0xFFFA2D48` 及明暗背景/卡片色（`Theme.kt:33-60`）
- 圆角表：`AppShapes` 16/12/16/20/28dp（`Theme.kt:63-69`）
- 7 级语义字号 + 4 档缩放（`AppDimensions.kt:29-64`）
- **CoverFlow 3D 公式**：`rotationY` ±65°/±60°、`translationX` 分段（中心 0.52W / 侧翼 0.22W）、`scaleX/Y` 景深、倒影 `scaleY = -1, alpha = 0.35`（`RetroCoverFlowPlayerView.kt:370-409, 943-982`）
- **流体有机路径公式**：8 点谐波 + `quadraticBezierTo` 8 秒循环（`LocalLibraryScreen.kt:2744-2786`）
- 歌词 5 行渐变遮罩（`LyricsScrollingView.kt:683-706`）

---

## 6. 功能映射与降级清单

### ✅ 可 1:1 对应（工作量小）

| Android | iOS |
| :--- | :--- |
| ExoPlayer / Media3 | `AVQueuePlayer`（顺带补 gapless） |
| MediaSession + MediaStyle 通知 | `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter` |
| 手写音频焦点（压音 / 来电暂停续播） | `AVAudioSession` interruption / routeChange / `.duckOthers` |
| 前台服务保活 | `UIBackgroundModes: [audio]` |
| 2GB LRU 缓存 + 断点续传下载 | `URLSession`（含 `Range`、`background` 配置） |
| Room | SQLDelight / GRDB.swift |
| Coil 2（仅 2 文件使用） | Nuke / Kingfisher |
| AirPlay 投屏（当前是试探性 POST，基本不可靠） | 系统原生 `AVRoutePickerView` — **大幅简化且真正可用** |
| 耳机线控 / 锁屏播控 | `MPRemoteCommandCenter` |
| LRC 解析 + 编码嗅探（GB18030/GBK/BOM） | 纯 Kotlin，直接复用 |
| ID3v2.3 / FLAC / M4A 标签内嵌 | 算法 1:1 翻译（仅换 `FileHandle`/`Data`） |
| 歌词在线源（酷狗 / LRCLIB / 网易云 / QQ） | OkHttp → Ktor 后直搬 |

### ⚠️ 需重写 / 有降级

| 功能 | iOS 方案 | 降级程度 |
| :--- | :--- | :--- |
| **悬浮灵动胶囊**（`BackgroundIslandOverlayController` 1671 行自绘 Canvas 跨应用悬浮窗） | Live Activities + Dynamic Island 模板 | **严重**：只能系统模板，不能自绘黑胶旋转/逐字卡拉OK/频谱；更新频率受 ActivityKit 节流，连续动画大概率不可用 |
| 厂商灵动岛（小米/OPPO/vivo/荣耀 extras） | 无对应 | **完全作废**，iOS 只有一种"岛" |
| 本地曲库扫描（MediaStore + `/storage/emulated/0/**` 递归 + SAF） | 沙盒 + `UIDocumentPicker`（security-scoped bookmark）+ `MPMediaLibrary` | **严重**：无法全盘扫描，硬编码的网易云/QQ音乐/酷狗目录全部作废 |
| DLNA/UPnP 投屏（手写 SSDP + SOAP + `ServerSocket`） | `NWBrowser` + `NWListener` | 中：需 multicast entitlement（Apple 审批） |
| 方向盘 / 耳机物理键大量 keycode 映射 | `MPRemoteCommandCenter` 固定命令集 | 中：无法任意映射 |
| 内置局域网 HTTP Range 服务 | `NWListener` 重写 | 低：逻辑可照搬 |
| 图片主色提取 | `CIAreaAverage` / vImage | 低（且当前 `androidx.palette` 是死依赖） |
| APE / DSD / WMA 播放 | 两端都不支持 | **既有偏差**，建议明确砍掉或引入 FFmpeg |
| 应用自更新 | TestFlight / App Store | 中 |

### ❌ iOS 上无法实现

1. 跨应用悬浮窗（`SYSTEM_ALERT_WINDOW` + `TYPE_APPLICATION_OVERLAY`）
2. 厂商灵动岛私有通道
3. 开机自启（`BootCompletedReceiver`）
4. 应用内自更新安装（`AppUpdateManager` + `REQUEST_INSTALL_PACKAGES`）
5. 全盘/任意目录媒体扫描
6. 沙盒外任意文件写入（`.lrc` / `cover.jpg` 同级写入需改为沙盒内）
7. 任意物理按键映射
8. WakeLock / WifiLock 常驻保活

### 需申请的 entitlement

| 能力 | 要求 |
| :--- | :--- |
| 后台播放 | `UIBackgroundModes: audio` |
| Live Activity | `NSSupportsLiveActivities`（iOS 16.1+），用户需授权 |
| 局域网（DLNA / mDNS / 组播） | `NSLocalNetworkUsageDescription` + **`com.apple.developer.networking.multicast`（需 Apple 审批）** |
| Apple Music 资料库 | `NSAppleMusicUsageDescription` |
| 文件访问 | `UIDocumentPicker` + security-scoped bookmark |
| CarPlay 车机 | CarPlay Audio entitlement（需 Apple 审批）；仅蓝牙 AVRCP 免审批 |
| AirPlay | 无需权限 |

---

## 7. 分阶段路线图

### Phase 0 — 技术验证（2~3 周）
- 抽出 `shared/` KMP 骨架，验证 Android 与 iOS 双向编译通过
- 用 Ktor + kotlinx.serialization 迁移 `LemonMusicProtocol` 的 3~5 个接口做打通验证
- **搭建三档液滴设计系统原型**（`AdaptiveGlass`）并在模拟器上跑 iOS 16 / 26 / 27 三套观感对比
- 产出：可运行的双端最小骨架 + 玻璃组件库 v0

### Phase 1 — 共享核心层（6~8 周）
- `commonMain`：Models / Entities（直接搬）
- Ktor 客户端封装 + 46 处请求点迁移
- SQLDelight：50 条 SQL 从 DAO 翻译
- 歌词模块（`LrcParser` / `SmartCharsetDecoder` / `EmbeddedLyricsExtractor`）整体搬入
- `SongMatchingResolver` / `PlaybackQueueManager` 匹配逻辑搬入
- `AudioMetadataEmbedder` 标签算法搬入（IO 抽到 expect/actual）
- 产出：两端共用、有单测覆盖的核心库

### Phase 2 — iOS 播放内核（5~6 周）
- `AVQueuePlayer` + `AVAudioSession`（后台播放、中断处理、路由变化）
- `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter`
- 缓存与预加载（`AVAssetResourceLoaderDelegate` + `URLCache` 或自研）
- **Live Activities + Dynamic Island**（WidgetKit 扩展）
- 产出：能后台播放、锁屏可控、上岛可用的播放内核

### Phase 3 — SwiftUI UI 全量重建（10~14 周）
- 设计系统：调色板 / 字号 / 圆角 / 三档玻璃组件库
- 五个主页面：Home（在线发现 + 本地）、Library、Downloads、Settings
- 全屏播放器（含 CoverFlow 3D 与歌词视图）
- 动效迁移：流体卡片、歌词渐变遮罩、拖拽手势、Pager
- 产出：功能完整的 iPhone 版本

### Phase 4 — 平台能力补齐（4~5 周）
- 本地文件导入（`UIDocumentPicker` + bookmark 持久化）
- 下载引擎（`URLSession` background）
- 标签内嵌 + `.lrc` 导出
- 分享 / 投屏（`AVRoutePickerView`；DLNA 视 entitlement 审批结果决定）
- 产出：本地曲库闭环

### Phase 5 — 打磨与分发（3~4 周）
- iPad 适配、横屏、分屏
- 辅助功能（VoiceOver、动态字体、降低透明度全链路验证）
- iOS 16 真机回归（重点：降级档观感）
- CarPlay（可选）
- TestFlight 配置与分发
- 产出：可发布构建

---

## 8. 风险与工作量

| 风险 | 影响 | 缓解 |
| :--- | :--- | :--- |
| **悬浮灵动岛降级** | 产品的最大卖点在 iOS 上缩水 | 提前决策：接受 Live Activities 降级，或把"沉浸式胶囊"改为 App 内悬浮（前台时）|
| **App Store 无法上架** | 分发受限 | 定位 TestFlight + Ad Hoc，不做审核阉割 |
| **DLNA 组播 entitlement 审批** | 投屏功能可能砍掉 | 优先做 AirPlay（系统原生，体验更好）|
| **iOS 16 适配成本** | 需 `ObservableObject` 替代 `@Observable`（17+）、Core Data/GRDB 替代 SwiftData（17+）、多个 API shim | 若接受 iOS 17 底线可省约 2~3 周；建议先按 16 做，用真实用户占比数据复核 |
| **CoverFlow 3D 保真** | SwiftUI 无 `beyondBoundsPageCount` 等价物 | 用 `TabView(.page)` 或自定义 `Layout`，需预留调优时间 |
| **KMP 工程化门槛** | 团队无经验则进度风险高 | 若无人熟悉 Kotlin/Native，改走路线 B |

**工作量汇总**

| 路线 | 单人全职 | 2~3 人 |
| :--- | ---: | ---: |
| C（推荐） | 7~10 个月 | 4~6 个月 |
| B（全量 SwiftUI 重写） | 9~12 个月 | 5~7 个月 |
| A（CMP） | 5~7 个月 | 3~4 个月（但拿不到原生液滴） |

---

## 9. 需要你决策的四个问题

1. **最低版本**：坚持 iOS 16（覆盖 iPhone 8/X），还是提到 iOS 17 换取约 2~3 周工程简化？
2. **灵动岛降级方案**：接受 Live Activities 系统模板（放弃自绘黑胶/频谱/逐字卡拉 OK），还是保留 App 内沉浸胶囊、锁屏侧只做基础播控？
3. **分发定位**：TestFlight（保留音源聚合）/ Ad Hoc（自用）/ 仍尝试 App Store（需阉割功能）？
4. **技术路线**：确认走 C（KMP 核心 + SwiftUI UI）？团队是否有 Kotlin/Native 经验？

---

*本文档基于 v1.7.14 代码勘察生成。相关事实依据：`grep` 全仓验证（Retrofit/Gson/palette/`AdaptiveFrostedSurface`/`DynamicIslandCapsule` 均零使用）；Liquid Glass API 可用性见 Apple WWDC 2025/2026；Compose Multiplatform iOS 稳定性见 JetBrains 官方文档。*
