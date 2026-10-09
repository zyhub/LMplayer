# LMPlayer iOS 原生方案与实施计划

> **路线**：不迁移 Kotlin/Compose 代码，**以 Android 版柠檬音乐服务端的 API 核心结构为唯一契约**，用 Swift 6 + SwiftUI 从零构建原生 iOS App。
> **UI**：全量采用 Apple 官方 iOS 26 / iOS 27 Liquid Glass 设计语言，**零 `#available` 分支**，部署目标 **iOS 26.0**。
> **基线**：Android v1.7.14 —— 48 个 Kotlin 文件 / 36,739 行（本次实测）。
> **取代关系**：本文档**完全取代** `docs/ios-port-plan.md`（KMP + iOS 16 路线）与旧版 `docs/ios-native-plan.md`，后者的代码盘点结论已被本文第 1 章吸收。
> **复核提示**：文中 iOS 26/27 API 名称（`glassEffect` / `TabViewBottomAccessory` 等）来自 WWDC 公开资料，落地前须以实际安装的 Xcode SDK 头文件为准。

---

## 0. 结论摘要

| 维度 | 决策 |
| :--- | :--- |
| 部署目标 | **iOS 26.0**，全项目不出现 `#available` |
| 语言 / 并发 | Swift 6 严格并发 + `actor` + `async/await` |
| UI | SwiftUI，纯系统 Liquid Glass，无一处自绘玻璃 |
| 复用方式 | **复用 API 核心结构（43 个端点契约 + 模型 + 算法语义）**，不复用字节码 |
| 工程形态 | 主 App Target + Widget Extension（Live Activity / 锁屏 / 灵动岛） |
| 播放内核 | `AVQueuePlayer`（顺带补齐 Android 版缺失的 gapless） |
| 设计收益 | Android 约 **3,300+ 行**自研 UI/毛玻璃/3D/CoverFlow 被系统 API 消除 |
| 最大降级 | 1,671 行跨应用悬浮胶囊 → `ActivityKit` 系统模板 |
| 工期 | 单人全职 **约 21~24 周（5 个月）**；2 人 **约 3~3.5 个月** |

### 0.1 已锁定决策（本次确认）

| 决策项 | 结论 | 影响 |
| :--- | :--- | :--- |
| DLNA / UPnP 投屏 | **不保留** | 不申请 multicast entitlement；投屏能力仅保留系统 AirPlay |
| APE / DSD / WMA | **不引入 FFmpeg**，UI 明确标注不支持 | 包体与解码复杂度零增加 |
| 变速播放保持音调 | **不要求** | 播放内核维持 `AVPlayer`，不升级 `AVAudioEngine`（省 1~2 周） |
| 分发 | **本地编译测试**（Xcode 直连真机 / 免费签名） | 不配置 TestFlight / Ad Hoc；无签名与分发流水线 |
| 本地曲库 | **保留沙盒内导入**（默认，待最终确认） | 砍掉全盘扫描（沙盒下不可能）；导入成本约 3~5 天 |

> 唯一待定项：**本地曲库（`UIDocumentPicker` 导入）是否保留** —— 见 §9.2。

### 0.2 当前进度

| Phase | 状态 |
| :--- | :--- |
| Phase 0 工程骨架与设计系统 | **已完成，待 Mac 编译验证** —— 见 [../ios/](../ios/) |
| Phase 1 API 契约层 | 骨架已就位（Endpoint 43 端点 / actor 客户端 / Flexible 解码），待联调 |
| Phase 2 持久化 | 未开始 |
| Phase 3 播放内核 | 仅骨架（PlayerEngine） |
| Phase 4 主界面 UI | 仅占位页 |
| Phase 5 歌词 / 下载 / 标签 | 未开始 |
| Phase 6 打磨与本地构建 | 未开始 |

---

## 1. 基线勘察：API 核心结构（本方案的地基）

Android 版的价值集中在**服务端协议层**，而不是 UI。以下为实测结论，构成 iOS 侧必须 1:1 对齐的契约。

### 1.1 分层与可移植性

| 层 | 代表文件 | 行数 | 平台相关性 |
| :--- | :--- | ---: | :--- |
| **API 核心** | `LemonMusicProtocol.kt` | 3,512 | **仅 33 行 Log + 11 行 Connectivity 判定**，其余为纯业务逻辑 |
| 协议接口 | `NasMusicProtocol.kt` | 18 | 0 |
| 统一模型 | `Models.kt` | 328 | 0（`@Immutable` 为 Compose 注解，可丢弃） |
| 网络工厂 | `NetworkClientFactory.kt` | 116 | OkHttp / Conscrypt（iOS 无需 Conscrypt） |
| 歌词 | `LyricsManager.kt` + 内嵌提取 | 1,481 | 纯字节 / 正则算法 |
| 标签内嵌 | `AudioMetadataEmbedder.kt` | 595 | 纯字节算法，仅换 FileHandle |
| 同名匹配 | `SongMatchingResolver.kt` | 563 | 纯字符串算法 |
| 播放路由 | `PlaybackRouter.kt` | 256 | 依赖 Media3 类型，逻辑可照搬 |
| 队列 | `PlaybackQueueManager.kt` | 701 | 状态机，可照搬 |
| UI / 设计系统 | 约 20 个文件 | ~23,000 | **0%（全部重写）** |

> **一句话**：API 核心约 **6,500 行有效逻辑**是资产，UI 约 **23,000 行**是要被系统 API 替代的负债。

### 1.2 端点全表（43 个，已冻结）

所有请求统一带 `Authorization: Bearer <token>`（`/api/play/*`、`/api/tag/cover` 支持 `?token=` 查询参数形式）。

| # | 分组 | Method | Path | 关键参数 | 返回要点 |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 1 | 鉴权 | POST | `/api/auth/login` | `{username,password,remember:true}` | `{token}` / `{error}` |
| 2 | 鉴权 | GET | `/api/auth/me` | — | `{user}` / `{token}` 校验会话 |
| 3 | 曲库 | GET | `/api/library/tracks?all=1` | — | `{data:[{filePath,fileName,title,parsedTitle,artist,parsedArtist,album,duration,format,size,mtime,trackId,id}]}` |
| 4 | 曲库 | GET | `/api/library/tracks/count` | — | `{total}` |
| 5 | 曲库 | POST | `/api/library/tracks/by-paths` | `{paths:[...]}` | `{data:[Track]}` |
| 6 | 曲库 | GET | `/api/library/albums?page=1&limit=500` | — | `{data:[{name,artist,count,samplePath,coverUrl,publishTime/year}]}` |
| 7 | 曲库 | GET | `/api/library/artists?page=1&limit=500` | — | `{data:[{name,count,samplePath}]}` |
| 8 | 曲库 | GET | `/api/library/genres?page&limit` | — | `{data:[{id,name,trackCount,count,coverPath}]}` |
| 9 | 曲库 | GET | `/api/library/playlists` | — | `{data:[Playlist]}` |
| 10 | 曲库 | PUT | `/api/library/playlists` | `{playlists:[...]}` | 全量覆盖保存 |
| 11 | 曲库 | GET | `/api/library/user-data` | — | `{data:{favorites[],playlists[],recentPlays[]}}` |
| 12 | 曲库 | PUT | `/api/library/user-data` | `{favorites:[...]}` | 收藏写回 |
| 13 | 曲库 | POST | `/api/library/scan-start` | `{}` | 触发服务端重扫 |
| 14 | 曲库 | GET | `/api/library/scan-status` | — | `{scan:{scanning,current,pendingCount,total}}` |
| 15 | 曲库 | POST | `/api/library/scan-batch` | `{paths:[...]}` | 批量补刮削 |
| 16 | 下载 | GET | `/api/download/list` | — | `[{status,filePath,name,singer,album,interval,mtime}]` |
| 17 | 下载 | POST | `/api/download/add` | `{tasks:[...]}` | `{ids:[]}` |
| 18 | 发现 | GET | `/api/playlist/recommend?source&sort=hot&page&limit` | — | `{data:{list:[{id,name,cover,total,source}]}}` |
| 19 | 发现 | GET | `/api/playlist?source&url=<enc>` | — | 外部歌单/链接解析为曲目列表 |
| 20 | 发现 | GET | `/api/playlist/sources` | — | `{data:{sources:{kw,...}}}` |
| 21 | 发现 | GET | `/api/discover/toplists?source` | — | `{data:{list:[{id,name,cover,updateTime}]}}` |
| 22 | 发现 | GET | `/api/discover/toplist?source&id&limit` | — | 曲目列表 |
| 23 | 发现 | GET | `/api/discover/new-songs?source&region&limit` | — | 曲目列表 |
| 24 | 发现 | GET | `/api/discover/new-albums?source&region&page&limit` | — | `{data:{list:[{id,name,artist,cover,total,publishTime}]}}` |
| 25 | 播放 | GET | `/api/play/local?path&token[&quality]` | Range 206 | 服务端本地音频流 |
| 26 | 播放 | GET | `/api/play/local-ape?path&token` | Range 206 | .ape → 服务端 FFmpeg 转 WAV |
| 27 | 播放 | POST | `/api/play/url` | 见 §1.4 | `{url}` / 409 `{sourceFallbackOffer}` |
| 28 | 歌词 | POST | `/api/play/lyric` | 见 §1.4 | `{lxlyric,lyric,ylyric}` |
| 29 | 标签 | GET | `/api/tag/cover?path&token` | — | 内嵌封面字节流 |
| 30 | 标签 | POST | `/api/tag/read` | `{filePath,path}` | `{data:{lyric}}` |
| 31 | 搜索 | GET | `/api/search?keyword&source&page&limit` | — | `{data:{list:[Song],info}}` |
| 32 | 搜索 | GET | `/api/search/album?keyword&source&page&limit` | — | 专辑列表 |
| 33 | 搜索 | GET | `/api/album/search?...` | — | 专辑列表（备用端点） |
| 34 | 搜索 | GET | `/api/search/playlist?keyword&source&page&limit` | — | 歌单列表 |
| 35 | 搜索 | GET | `/api/album?source&id` | — | 专辑详情曲目 |
| 36 | 搜索 | GET | `/api/search/album/detail?source&id` | — | 专辑详情（#35 回退） |
| 37 | 音源 | GET | `/api/source/list` | — | 脚本列表（空则前端补 5 条内置） |
| 38 | 音源 | POST | `/api/source/import-url` | `{url}` | 远程导入脚本 |
| 39 | 音源 | POST | `/api/source/import` | `{content,fileName}` | 本地上传脚本 |
| 40 | 音源 | POST | `/api/source/activate/{id}` / `deactivate/{id}` | — | 启停 |
| 41 | 音源 | DELETE | `/api/source/{id}` | — | 删除 |
| 42 | 配置 | GET/PUT | `/api/paths`、`/api/paths/download` | `{dirPath,path}` | 服务端下载目录 |
| 43 | 配置 | GET/PUT | `/api/settings` | 任意键值（如 `download.maxDownloadNum`、`download.isDownloadLrc`、`source.fallbackMode`） | 服务端全局设置 |

### 1.3 ID 生成规则（必须逐字对齐，否则收藏/歌单会错位）

| 实体 | 规则 |
| :--- | :--- |
| 服务端本地曲目 | `"lemon_" + md5(filePath)`，`filePath` = 服务端绝对路径 |
| 在线曲目 | `"lemon_online_{source}_{rawId}"`，source ∈ {kw,wy,tx,kg,mg} |
| 歌手 | `"lemon_artist_" + md5(name)` |
| 专辑 | `"lemon_album_" + md5("$artist/$name")` |
| 发现新碟 | `"lemon_discover_album_{source}_{id}"` |
| 收藏智能歌单 | `"lemon_favorites"` |
| 推荐歌单 | `"lemon_rec_{source}_{id}"` |
| 榜单 | `"lemon_toplist_{source}_{id}"` |
| 自定义歌单 | 服务端 `id`；新建为 `"pl_{epochMillis}_{1000..9999}"` |
| serverId | 服务端曲库 `lemon_music` / 在线 `lemon_online` / 本地 `local_storage`、`local_folder`、`local_saf` |

### 1.4 五段关键算法契约（移植时最易产生分歧，需双端对照测试）

**(A) 音质逐档降级 + 跨平台同档补源** —— `resolveOnlineStreamWithQuality`

```
order        = [flac24bit, flac, 320k, 128k]
preferred    = Wi-Fi(非计费) 默认 "320k" ; 蜂窝 默认 "128k"
for q in order.drop(while { it != preferred }):
    1) 本平台本曲目直取链 (allowSearchFallback = false)
    2) 仍失败 -> 跨平台同档补源 (allowSearchFallback = true, skipDirectAttempt = true)
       · 关键词 = "title artist"，逐平台 searchOnline，用 isSongMatch 严格版本匹配，禁止取首个结果
    3) 本档所有平台均失败 -> 降一档重来
返回 ResolvedOnlineStream(url, qualityKey, format, bitRate, isDowngraded)
format 由 URL 扩展名反推（flac/wav/m4a/ogg/mp3），bitRate 按格式+档位复核
```

**(B) `POST /api/play/url` 载荷构造** —— 在线曲目取链

```
payload = rawMetaJson 全字段展开
  - 移除 id / trackId / filePath / path / localPath   <- 否则服务端误判为本地曲目 -> 404 TRACK_NOT_FOUND
  + source      (为空或 "local" 时用真实平台)
  + songId      = meta.songId ?? meta.id ?? meta.songmid ?? meta.hash ?? meta.copyrightId ?? rawId
  + songmid     (tx/kw 且缺失时 = songId)
  + hash        (kg 且缺失时 = songId)
  + copyrightId (mg 且缺失时 = songId)
  + types[]     追加 {type: quality, size: ""}（缺失该档时）
  + _types      {[quality]: {size: ""}}
  + quality, type = quality
  + refresh     (true 时强制刷新)
  + sourceApiId (409 补源时携带 alternatives[].id)
响应 {url} -> 相对路径补 base；缺 token/ticket 时补 ?token=
409 SOURCE_FALLBACK_REQUIRED -> 遍历 sourceFallbackOffer.alternatives[].id 重试
```

**(C) 收藏写回快照** —— `buildTrackKeyAndSnapshot` + `PUT /api/library/user-data`

```
服务端本地曲目:
  key = "local:{absPath}"
  snapshot = { key, localPath, filePath, name, title, singer, artist, album,
               picUrl?, img?, interval?, duration?, source:"local" }

在线曲目:
  key = "{platform}:{rawId}"
  snapshot = rawMeta 全字段 + { key, source, platform, songId, id,
                                 name, title, singer, artist, album, picUrl?, img?, interval?, duration? }

去重匹配（取消收藏时）依次比较：
  key == targetKey
  || 归一化 localPath == cleanTargetPath
  || songId == targetSongId || "lemon_online_{source}_{songId}" == song.id
  || (name == title && artist == artist)   <- 大小写不敏感
命中则剔除，isFavorite 时把新快照插入数组首位。
```

**(D) 播放路由决策** —— `PlaybackRouter`

```
1. 本地命中（按优先级）：
   downloadDao 记录且文件存在 -> song.localFilePath 存在
   -> streamUrl 为本地绝对路径 -> 默认下载目录 {files}/music/{id}.{format}
   -> 本地库中 isSongMatch 同名同版本且文件存在
2. 在线/服务端曲目：
   已含 /api/play/local 或 isServerSong -> 取服务端路径刷新 GET /api/play/local
   否则 -> POST /api/play/url 换链；彻底失败时回退「库内同名同版本且已有 http 流」的曲目
3. 输出：file:// 或 https:// URI + 元数据（title/artist/album/artwork/extras）
```

**(E) 字段回退链 + 派生字段**（`FlexibleDecoding` 要固化这些顺序）

```
title    : title -> parsedTitle -> fileName(去扩展名) -> name -> "未知曲目"
artist   : artist -> parsedArtist -> singer -> "未知歌手"
album    : album -> albumName -> "未知专辑"
duration : interval("mm:ss"/"hh:mm:ss") -> duration(秒；>10000 视为毫秒) -> interval(数值)
cover    : cover -> img -> pic -> picUrl -> pic_url -> albumpic -> album_pic -> albumPic -> imgurl -> coverUrl
           （以 "//" 开头补 https:，以 "/" 开头补 base）
bitRate  : size*8/duration/1000 clamp [128,1411]；否则 flac/ape/wav -> 960，其余 -> 320
mtime    : >1e10 视为毫秒；否则 *1000；缺失则 0
文本清洗 : unescapeMusicText（HTML 实体 / Unicode 转义 / 常见乱码）
```

### 1.5 客户端级行为（需在 Swift 侧原样对齐）

| 项 | 值 |
| :--- | :--- |
| 连接池 | 32 连接 / 5 分钟空闲 |
| 并发 | 全局 64，单主机 16 |
| 超时 | connect 12s，read/write 60s |
| 磁盘缓存 | 64 MB；**`/api/play/*` 强制绕过缓存**（避免与 206 Range 冲突） |
| User-Agent | Chrome 120 桌面 UA（服务端按 UA 分流） |
| Token 共享 | 按 `base + username` 缓存，多组件复用，避免并发重复登录 |
| 发现页缓存 | 推荐歌单 / 榜单 / 新歌 / 新碟 **TTL 5 分钟** |
| 内存映射 | `songId -> 服务端绝对路径 / trackId / UnifiedSong` 跨实例共享 |
| 歌词读取顺序 | (1) `POST /api/tag/read` 内嵌标签与同名 .lrc -> (2) HTTP Range 拉前 384KB 本地提取 -> (3) `POST /api/play/lyric` |
| 乱码修复 | `repairMojibakeIfNeeded` + `looksSuspiciousOrGarbled`（latin1/GBK/UTF-16 误判） |

### 1.6 设置项清单（-> iOS `@AppStorage`，共 33 个有效键）

```
app_theme_mode  ui_scale_mode  player_theme_style  lyrics_theme_id
wifi_stream_quality  cellular_stream_quality  selected_source  source_fallback_enabled
enable_bottom_bar_anim  home_show_{recently_played,recently_added,albums,artists,favorites}
default_download_target  default_download_quality  max_concurrent  wifi_only  auto_tagging
download_embed_cover  download_embed_lyric  download_lrc_file_v2  custom_download_path
stream_cache_enabled_v2  auto_fallback_to_local  auto_play_on_startup  stop_playback_on_exit
playback_is_shuffle  playback_is_repeat  last_played_{song_id,queue_json,...}
```

> iOS 侧**删除**：`bg_island_overlay_prompted`、`hw_capsule_*`、`never_update`、`skip_version`（无对应能力）。
> iOS 侧**移除「毛玻璃透明度」滑杆**：改由系统「透明度 / 降低透明度」统一接管。

---

## 2. iOS 工程架构

### 2.1 目标与约束

- **Deployment Target = iOS 26.0**，Swift 6 language mode，严格并发检查开启。
- **零 `#available`**：这是本方案的核心收益，任何版本分支都视为架构违规。
- 主 Target + **Widget Extension**（Live Activity / 锁屏 / 灵动岛），App Group 共享 `NowPlayingState`。
- 图片：`AsyncImage` 不足以承载磁盘缓存与解码控制 -> 采用 **Nuke**（或 Kingfisher）。

### 2.2 目录结构

```
LMPlayer-iOS/
├── LMPlayer.xcodeproj
├── App/
│   ├── LMPlayerApp.swift              # @main，注入 SwiftData ModelContainer
│   └── RootTabView.swift              # TabView + .tabViewBottomAccessory
├── DesignSystem/
│   ├── LMGlass.swift                  # 全项目唯一 glassEffect 入口
│   ├── LMPalette.swift                # AppleRed 调色板
│   ├── LMTypography.swift             # 7 级语义字号
│   ├── LMShapes.swift                 # 16/12/16/20/28
│   ├── LMMotion.swift
│   └── Components/
│       ├── MiniPlayerBar.swift        # 底栏迷你播放器（玻璃）
│       ├── LiquidCard.swift           # Metal shader 液态光影
│       ├── ArtworkImage.swift         # 封面 + 主色提取
│       ├── MarqueeText.swift
│       └── QualityBadge.swift
├── Core/
│   ├── Network/
│   │   ├── Endpoint.swift             # 43 端点枚举（§3.1）
│   │   ├── LemonMusicClient.swift     # actor + URLSession
│   │   ├── FlexibleDecoding.swift     # @Flexible 回退链
│   │   ├── DTOs/                      # 对齐服务端字段
│   │   └── LemonErrors.swift
│   ├── Persistence/
│   │   ├── Models/                    # @Model ×5
│   │   └── LibraryStore.swift
│   ├── Playback/
│   │   ├── PlayerEngine.swift         # AVQueuePlayer
│   │   ├── AudioSessionManager.swift  # 中断 / 路由 / 后台
│   │   ├── PlaybackQueue.swift        # 队列 / 乱序 / 循环 / 最近播放
│   │   └── PlaybackRouter.swift       # §1.4(D) 的 Swift 版
│   ├── NowPlaying/NowPlayingCenter.swift
│   ├── Lyrics/{LRCParser,CharsetDetector,EmbeddedLyricsReader}.swift
│   ├── Tagging/AudioTagWriter.swift   # ID3v2.3 / FLAC / M4A
│   ├── Matching/SongMatchingResolver.swift
│   └── Download/DownloadManager.swift
├── Features/{Discover,Library,Downloads,Settings,Search,Player}/
└── Widgets/NowPlayingActivity.swift   # Live Activity
```

### 2.3 并发与状态

| Android 现状 | iOS 对应 |
| :--- | :--- |
| 无 ViewModel，`remember { mutableStateOf }` | `@Observable` 局部 Store + `@State` |
| 单例 `object` + `StateFlow`（PlaybackQueueManager） | `@Observable` `@MainActor` 单例 |
| 自研 `Screen` enum 导航状态机 | `TabView` + 每 Tab 独立 `NavigationStack` + `NavigationPath` |
| Kotlin 协程 `withContext(Dispatchers.IO)` | `actor` + `async/await` |
| `Result<T>` 返回值 | `throws` + Swift 6 类型化 `throws` |

---

## 3. API 核心结构的 Swift 落地

### 3.1 端点枚举（契约即代码）

```swift
// Core/Network/Endpoint.swift
enum HTTPMethod: String { case get = "GET", post = "POST", put = "PUT", delete = "DELETE" }

struct Endpoint: Sendable {
    var method: HTTPMethod = .get
    var path: String
    var query: [String: String] = [:]
    var body: Data? = nil
    var bypassCache: Bool = false
}

extension Endpoint {
    // 鉴权
    static func login(_ u: String, _ p: String) -> Self {
        .init(method: .post, path: "/api/auth/login",
              body: try? JSONSerialization.data(withJSONObject:
                  ["username": u, "password": p, "remember": true]))
    }
    static var me: Self { .init(path: "/api/auth/me") }

    // 曲库
    static var allTracks: Self   { .init(path: "/api/library/tracks", query: ["all": "1"]) }
    static var trackCount: Self  { .init(path: "/api/library/tracks/count") }
    static func tracksByPaths(_ paths: [String]) -> Self {
        .init(method: .post, path: "/api/library/tracks/by-paths",
              body: try? JSONSerialization.data(withJSONObject: ["paths": paths]))
    }
    static var albums: Self  { .init(path: "/api/library/albums",  query: ["page": "1", "limit": "500"]) }
    static var artists: Self { .init(path: "/api/library/artists", query: ["page": "1", "limit": "500"]) }
    static func genres(page: Int = 1, limit: Int = 100) -> Self {
        .init(path: "/api/library/genres", query: ["page": "\(page)", "limit": "\(limit)"])
    }
    static var playlists: Self      { .init(path: "/api/library/playlists") }
    static func savePlaylists(_ json: Data) -> Self { .init(method: .put, path: "/api/library/playlists", body: json) }
    static var userData: Self       { .init(path: "/api/library/user-data") }
    static func saveUserData(_ json: Data) -> Self { .init(method: .put, path: "/api/library/user-data", body: json) }
    static var scanStart: Self      { .init(method: .post, path: "/api/library/scan-start", body: Data("{}".utf8)) }
    static var scanStatus: Self     { .init(path: "/api/library/scan-status") }

    // 播放 / 标签（绕过缓存）
    static func streamLocal(path: String, token: String, quality: String? = nil) -> Self {
        let isApe = path.lowercased().hasSuffix(".ape")
        var q = ["path": path, "token": token]
        if let quality { q["quality"] = quality }
        return .init(path: isApe ? "/api/play/local-ape" : "/api/play/local", query: q, bypassCache: true)
    }
    static func cover(path: String, token: String) -> Self {
        .init(path: "/api/tag/cover", query: ["path": path, "token": token], bypassCache: true)
    }
    static func playURL(_ payload: Data) -> Self { .init(method: .post, path: "/api/play/url", body: payload) }
    static func lyric(_ payload: Data) -> Self   { .init(method: .post, path: "/api/play/lyric", body: payload) }

    // 发现 / 搜索
    static func recommend(source: String, page: Int, limit: Int) -> Self {
        .init(path: "/api/playlist/recommend", query: ["source": source, "sort": "hot", "page": "\(page)", "limit": "\(limit)"])
    }
    static func toplists(source: String) -> Self { .init(path: "/api/discover/toplists", query: ["source": source]) }
    static func toplist(source: String, id: String, limit: Int = 100) -> Self {
        .init(path: "/api/discover/toplist", query: ["source": source, "id": id, "limit": "\(limit)"])
    }
    static func newSongs(source: String, region: String, limit: Int) -> Self {
        .init(path: "/api/discover/new-songs", query: ["source": source, "region": region, "limit": "\(limit)"])
    }
    static func newAlbums(source: String, region: String, page: Int, limit: Int) -> Self {
        .init(path: "/api/discover/new-albums",
              query: ["source": source, "region": region, "page": "\(page)", "limit": "\(limit)"])
    }
    static func playlist(source: String, url: String) -> Self {
        .init(path: "/api/playlist", query: ["source": source, "url": url])
    }
    static func search(keyword: String, source: String, page: Int = 1, limit: Int = 30) -> Self {
        .init(path: "/api/search", query: ["keyword": keyword, "source": source, "page": "\(page)", "limit": "\(limit)"])
    }
    static func searchAlbums(keyword: String, source: String, page: Int = 1, limit: Int = 30) -> Self {
        .init(path: "/api/search/album", query: ["keyword": keyword, "source": source, "page": "\(page)", "limit": "\(limit)"])
    }
    static func albumDetail(source: String, id: String) -> Self {
        .init(path: "/api/album", query: ["source": source, "id": id])
    }
    // 下载 / 音源 / 配置：13 项，见 §1.2 全表，同理声明
}
```

### 3.2 网络客户端

```swift
// Core/Network/LemonMusicClient.swift
actor LemonMusicClient {
    private let base: URL
    private let session: URLSession
    private var token: String?

    private static let discoverCache = NSCache<NSString, CacheBox>()   // TTL 5min
    private static let pathMap = PathRegistry()                        // songId -> 绝对路径/trackId

    init(base: URL) {
        let cfg = URLSessionConfiguration.default
        cfg.httpMaximumConnectionsPerHost = 32
        cfg.timeoutIntervalForRequest  = 12
        cfg.timeoutIntervalForResource = 60
        cfg.urlCache = URLCache(memoryCapacity: 16 << 20, diskCapacity: 64 << 20)
        cfg.requestCachePolicy = .useProtocolCachePolicy
        self.session = URLSession(configuration: cfg)
        self.base = base
    }

    func setToken(_ t: String) { token = t }

    func send(_ ep: Endpoint) async throws -> Data {
        var comps = URLComponents(url: base.appending(path: ep.path), resolvingAgainstBaseURL: false)!
        if !ep.query.isEmpty { comps.queryItems = ep.query.map { .init(name: $0.key, value: $0.value) } }
        var req = URLRequest(url: comps.url!)
        req.httpMethod = ep.method.rawValue
        req.httpBody = ep.body
        req.setValue(AppInfo.chromeUA, forHTTPHeaderField: "User-Agent")
        if let token { req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if ep.bypassCache { req.cachePolicy = .reloadIgnoringLocalCacheData }   // 对齐 /api/play/ 绕过缓存

        let (data, resp) = try await session.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw LMError.transport }
        guard (200..<300).contains(http.statusCode) else {
            throw LMError.server(status: http.statusCode, body: data)          // 409 单独处理
        }
        return data
    }
}
```

### 3.3 回退链解码

```swift
// Core/Network/FlexibleDecoding.swift
@propertyWrapper
struct Flexible<T: Decodable & Sendable>: Decodable, Sendable {
    var wrappedValue: T?
    init(from decoder: Decoder) throws {
        wrappedValue = try? decoder.singleValueContainer().decode(T.self)
    }
}

struct TrackDTO: Decodable, Sendable {
    @Flexible var filePath: String?
    @Flexible var fileName: String?
    @Flexible var title: String?
    @Flexible var parsedTitle: String?
    @Flexible var artist: String?
    @Flexible var parsedArtist: String?
    @Flexible var album: String?
    @Flexible var duration: Double?
    @Flexible var interval: String?     // "03:45"
    @Flexible var format: String?
    @Flexible var size: Int64?
    @Flexible var mtime: Int64?
    @Flexible var trackId: String?

    /// §1.4(E) 的 title 回退链
    var displayTitle: String {
        [title, parsedTitle, fileName.map { ($0 as NSString).deletingPathExtension }]
            .compactMap { $0 }.first { !$0.isEmpty } ?? "未知曲目"
    }
    /// §1.4(E) 的 duration 解析
    var durationMs: Int64 { DurationParser.millis(interval: interval, duration: duration) }
}
```

### 3.4 取链算法骨架（§1.4 A/B）

```swift
// Core/Network/StreamResolver.swift
enum Quality: String, CaseIterable, Sendable {
    case hires = "flac24bit", flac, k320 = "320k", k128 = "128k"
    var bitrate: Int {
        switch self { case .hires: 1411; case .flac: 960; case .k320: 320; case .k128: 128 }
    }
    static let ladder: [Quality] = [.hires, .flac, .k320, .k128]
    static func fallbacks(from preferred: Quality) -> [Quality] {
        guard let i = ladder.firstIndex(of: preferred) else { return [preferred, .k320, .k128] }
        return Array(ladder[i...])
    }
}

struct ResolvedStream: Sendable {
    let url: URL; let quality: Quality; let format: String; let bitRate: Int; let isDowngraded: Bool
}

extension LemonMusicClient {
    func resolveStream(song: UnifiedSong, preferred: Quality, refresh: Bool = false) async throws -> ResolvedStream {
        var lastError: Error?
        for (index, q) in Quality.fallbacks(from: preferred).enumerated() {
            // 1) 本平台本曲目直取
            if let s = try? await requestPlayURL(song: song, quality: q, refresh: refresh || index > 0,
                                                 searchFallback: false, skipDirect: false) {
                return detected(s, q, preferred)
            }
            // 2) 跨平台同档补源
            if let s = try? await requestPlayURL(song: song, quality: q, refresh: true,
                                                 searchFallback: true, skipDirect: true) {
                return detected(s, q, preferred)
            }
            lastError = LMError.noSource
        }
        throw lastError ?? LMError.noSource
    }
}
```

> **对照测试要求**：§1.4 的 A~E 五段逻辑必须建立 **Kotlin ↔ Swift 双端对照用例集**（见 §8）。
> 这是全项目唯一会导致「同一首歌两端表现不同」的风险源。

---

## 4. iOS 26 / 27 Liquid Glass 设计系统

### 4.1 设计纪律（先立规矩，否则会做出「像 26 但不对」的界面）

1. **玻璃只用于 chrome，不用于内容**。Apple 明确指引：Liquid Glass 面向导航栏 / 工具栏 / 浮动控件；内容密集区用材质或实色。
   -> Android 版「所有卡片上毛玻璃」的做法（首页 / 发现 / 资料库）**必须重新分层**。
2. **禁止玻璃叠玻璃**。层级严格三级：**玻璃（chrome）-> 材质（浮层）-> 实色（内容）**。
3. **`glassEffect` 必须是修饰符链最后一环**（frame / padding / 字号 / 颜色之后）。
4. **`.interactive()` 只加在可点击元素上**。
5. **不硬编码不透明度**。iOS 27 提供系统级透明度与「降低透明度 / 提高对比度」，App 不提供对冲设置。
6. **滚动边缘交给系统**（`scrollEdgeEffectStyle`），不手绘底衬。

### 4.2 唯一玻璃入口

```swift
// DesignSystem/LMGlass.swift
enum GlassRole { case chrome, floating, content }

extension View {
    func lmGlass(_ role: GlassRole = .chrome,
                 in shape: some Shape = .capsule,
                 tint: Color? = nil,
                 interactive: Bool = false) -> some View {
        modifier(LMGlass(role: role, shape: shape, tint: tint, interactive: interactive))
    }
}

private struct LMGlass<S: Shape>: ViewModifier {
    let role: GlassRole; let shape: S; let tint: Color?; let interactive: Bool

    func body(content: Content) -> some View {
        switch role {
        case .content:
            content.background(.background.secondary, in: shape)   // 内容层永不用玻璃
        case .chrome, .floating:
            content.glassEffect(glass, in: shape)                  // 必须最后调用
        }
    }
    private var glass: Glass {
        var g: Glass = .regular
        if let tint { g = g.tint(tint) }
        if interactive { g = g.interactive() }
        return g
    }
}
```

### 4.3 导航骨架（替代 850 行自研 morphing 底栏）

```swift
// App/RootTabView.swift
struct RootTabView: View {
    @State private var selection: Screen = .home

    var body: some View {
        TabView(selection: $selection) {
            Tab("发现",   systemImage: "sparkles",         value: Screen.home)      { DiscoverView() }
            Tab("资料库", systemImage: "square.stack",     value: Screen.library)   { LibraryView() }
            Tab("下载",   systemImage: "arrow.down.circle", value: Screen.downloads) { DownloadsView() }
            Tab("设置",   systemImage: "gearshape",        value: Screen.settings)  { SettingsView() }
        }
        .tabViewBottomAccessory { MiniPlayerBar() }          // 迷你播放器由系统接管
        .tabBarMinimizeBehavior(.onScrollDown)               // 滚动自动收底栏
    }
}
```

### 4.4 Android 自研 UI -> iOS 26 系统 API 对照（净减 3,300+ 行）

| Android 现状 | 行数 | iOS 26 方案 |
| :--- | ---: | :--- |
| `CompactMorphingBottomBar` + `ExpandedMorphingBottomBar` + `MorphingFloatingBottomBar` | ~850 | `TabView` + `.tabViewBottomAccessory` + `.tabBarMinimizeBehavior` |
| `NestedScrollConnection` 滚动收底栏 | ~20 | 系统内置 |
| `blurAlpha` 半透明纯色（全站） | 散落 | `.lmGlass(.chrome)` |
| `AdaptiveFrostedSurface`（死代码） | 70 | 删除 |
| `DynamicIslandCapsule`（死代码） | 108 | 删除 |
| 自绘展开/收起动画 | 散落 | `GlassEffectContainer` + `glassEffectID` + `.glassEffectTransition(.matchedGeometry)` |
| `DropdownMenu` / `ModalBottomSheet` | 散落 | 系统 `Menu` / `sheet` + `.presentationDetents` |
| 歌词浮层调节面板 | ~200 | 系统 `Menu` / popover |
| 状态栏透明处理 | ~15 | 删除 |
| 全屏播放器覆盖层切换 | 散落 | `matchedTransitionSource` + `NavigationTransition.zoom`（Apple Music 同款封面放大） |
| CoverFlow `graphicsLayer` 手算 3D | ~120 | `ScrollView(.horizontal)` + `.scrollTargetBehavior(.viewAligned)` + `.scrollTransition` |
| `buildOrganicLiquidPath` 阿米巴流体 | ~45 | Metal shader + `.layerEffect` |
| `DiscoverLiquidCard` 液态光影 | ~125 | `.visualEffect` + Metal shader + `TimelineView` |
| 歌词 5 行渐变遮罩 | ~25 | `.mask(LinearGradient(...))` |

### 4.5 设计 token 迁移表（纯数学，100% 可迁移）

| 类别 | Android 值 | iOS 常量 |
| :--- | :--- | :--- |
| 主色 | #FA2D48 / #D81E37 | `LMPalette.appleRed` / `.appleRedDark` |
| 浅色背景/卡片/药丸 | #F2F2F7 / #FFFFFF / #E5E5EA | 同名常量 |
| 深色背景/卡片 | #101014 / #1C1C20 | 同名常量 |
| 圆角 | 16 / 12 / 16 / 20 / 28 | `LMShape.{card,pill,medium,large,xlarge}` |
| 字号 7 级 | 26 / 18 / 16 / 15 / 13.5 / 12 / 10.5 sp | `.system(size:weight:design:.rounded)` 对应 7 个语义样式 |
| 封面尺寸 | 300 / 90 / 54 dp | 自适应：`min(w,h)*0.72` clamp 200...310 |
| 触控目标 | 68 / 48 / 36 dp | 保留（≥44pt 已满足 HIG） |
| 内边距 | 24 / 16 / 8 dp | 保留 |

> **可删除的系统**：`ui_scale_mode` 的 4 档自适应缩放机制。iOS 用系统 Dynamic Type + 原生布局即可，无需自研 density 体系。

### 4.6 页面级规格

| 页面 | iOS 26 实现要点 |
| :--- | :--- |
| **发现** | `ScrollView` + `LazyVStack`；顶部榜单横向 `ScrollView` + `.scrollTargetBehavior(.viewAligned)`；液态光影卡片 = Metal shader + `TimelineView`；内容区**实色**，仅顶部工具栏玻璃 |
| **资料库** | `NavigationStack` + `List`/`LazyVGrid`；分段控件 `Picker(.segmented)` 切换 歌单/歌手/专辑/歌曲/下载/收藏/文件夹；歌单四宫格封面用 `Grid` 自绘（内容层） |
| **全屏播放器** | `.fullScreenCover` + `matchedTransitionSource` 封面放大过渡；大封面 + 双列歌词（横屏）；控制条 `.lmGlass(.floating, interactive: true)`；CoverFlow 用 `scrollTransition` |
| **歌词** | `ScrollViewReader` 逐行滚动；当前行 `.scrollTransition` 放大/高亮；5 行渐变 `.mask`；调节面板 -> 系统 `Menu`（6 套 Apple Music 主题 -> `Color` 预设） |
| **设置** | `Form` + `.groups`；所有下拉 -> 系统 `Picker` / `Menu`；删除透明度滑杆与 UI 缩放档位 |
| **搜索** | `.searchable` + `.searchScopes`（歌曲/专辑/歌单）+ 音源 `Menu` |
| **下载管理** | `List` + `.swipeActions` 批量选择；存储看板卡片 |

### 4.7 iOS 27 需尊重的两点

1. **系统级透明度可调** -> App 不得硬编码 alpha 或用滑杆对冲。
2. **滚动统一工具栏** -> 使用 `scrollEdgeEffectStyle` / `.toolbarBackground`，不自绘底衬。

---

## 5. 平台能力边界与功能取舍

### 5.1 可完整对应

| Android | iOS |
| :--- | :--- |
| ExoPlayer 单曲切换 | `AVQueuePlayer`（**新增 gapless**） |
| MediaSession + MediaStyle 通知 | `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter` |
| 手写音频焦点 | `AVAudioSession` 中断 / 路由变化 / `.duckOthers` |
| 前台服务 + WakeLock | `UIBackgroundModes: [audio]` |
| 2GB LRU 缓存 + Range 断点下载 | `URLSession` 后台配置 + `Range` |
| Room 5 实体 / 50 查询 | SwiftData（复杂查询兜底 GRDB） |
| Coil 2 | Nuke |
| AirPlay 试探性 POST | `AVRoutePickerView`（系统原生，**为升级**） |
| LRC 解析 + 编码嗅探 | 纯算法，1:1 翻译 |
| ID3v2.3 / FLAC / M4A 标签内嵌 | 纯字节算法，换 `FileHandle`/`Data`，1:1 翻译 |

### 5.2 需降级

| 功能 | iOS 方案 | 降级程度 |
| :--- | :--- | :--- |
| 悬浮灵动胶囊（1,671 行自绘跨应用悬浮窗） | Live Activity + Dynamic Island | **严重**：仅系统模板；ActivityKit 更新节流，黑胶旋转/逐字卡拉OK/四柱频谱大概率不可用 |
| 厂商灵动岛私有通道 | 无对应 | 完全作废 |
| 全盘本地扫描 | `UIDocumentPicker` + security-scoped bookmark | 严重：不能扫描网易云/QQ/酷狗下载目录 |
| DLNA/UPnP | **已砍（不保留）** | 不申请 multicast entitlement；投屏仅保留系统 AirPlay |
| 物理按键映射 | `MPRemoteCommandCenter` 固定命令集 | 中 |
| APE/DSD/WMA | **已决策：不引入 FFmpeg** | UI 明确标注不支持，不做软解 |
| 应用内自更新 | TestFlight / App Store | 中 |

### 5.3 无对应

跨应用悬浮窗、厂商灵动岛、开机自启、应用内静默更新安装、全盘媒体扫描、沙盒外任意写入、WakeLock 常驻保活。

### 5.4 Entitlement / Info.plist

| 能力 | 配置 |
| :--- | :--- |
| 后台播放 | `UIBackgroundModes: audio` |
| Live Activity | `NSSupportsLiveActivities = YES` |
| 局域网（DLNA/mDNS） | `NSLocalNetworkUsageDescription` + `com.apple.developer.networking.multicast`（需 Apple 审批） |
| 明文 HTTP（NAS 场景） | `NSAppTransportSecurity` 按需 `NSAllowsLocalNetworking` |
| CarPlay（可选） | CarPlay Audio entitlement（需审批） |

> **分发定位（已定）**：**本地编译测试** —— Xcode 直连真机或模拟器，用免费 Apple ID 签名即可，不申请任何分发通道。
> 好处：无需为审核阉割音源聚合能力，也不必维护 TestFlight 流水线；代价是无法公开分发。

---

## 6. 数据持久化

| Android 实体 | iOS `@Model` | 备注 |
| :--- | :--- | :--- |
| `SongEntity` | `Song` | 8 个索引：serverId / downloadStatus / isFavorite / lastPlayed / added / localFilePath / albumId / artistId |
| `DownloadEntity` | `Download` | 3 索引 |
| `ServerEntity` | `ServerConfigModel` | `isCurrentActive` |
| `PlaylistEntity` | `Playlist` | 复合唯一 |
| `PlaylistSongEntity` | `PlaylistItem` | `#Unique<PlaylistItem>([\.playlistId, \.songId])` |

**SQL 迁移风险**：50 条 DAO 查询含 `INNER JOIN`、聚合子查询、`LIKE 'discover_%'`、布尔赋值式更新。
-> SwiftData 能覆盖约 80%；若某几条表达困难，**用 GRDB 承载该模块**，不要把复杂查询硬塞进 SwiftData。

**其他存储**：33 个设置键 -> `@AppStorage`；token -> **Keychain**（Android 用 SharedPreferences，iOS 应升级为 Keychain）。

---

## 7. 实施计划

> 估算基准：单人全职、熟悉 Swift/SwiftUI、首次接触本服务端协议。2 人并行时总工期约 x0.6。
> 每个 Phase 的「验收」都是**可演示的硬指标**，不是进度描述。

### Phase 0 · 工程骨架与设计系统（1.5 周）

| # | 任务 | 产出 |
| :--- | :--- | :--- |
| 0.1 | Xcode 工程，Deployment Target 26.0，Swift 6，开启严格并发 | 可编译 |
| 0.2 | `RootTabView` + `.tabViewBottomAccessory` + `.tabBarMinimizeBehavior` | 玻璃底栏 + 迷你播放器占位 |
| 0.3 | `LMPalette` / `LMTypography` / `LMShapes` / `LMGlass` | 设计系统 v0 |
| 0.4 | `GlassEffectContainer` morphing 原型（展开/收起播控） | 动效 PoC |
| **验收** | 真机可见完整 Liquid Glass 骨架；滚动自动收底栏；无一处 `#available` | |

### Phase 1 · API 契约层（3 周）

| # | 任务 | 产出 |
| :--- | :--- | :--- |
| 1.1 | `Endpoint.swift` 43 端点枚举 + 单元测试（URL 拼装快照） | 契约冻结 |
| 1.2 | `LemonMusicClient`（actor / 超时 / 缓存 / UA / Bearer / 绕缓存） | 网络层 |
| 1.3 | DTO + `@Flexible` 回退链（§1.4 E 全部字段） | 解码层 + 用例 |
| 1.4 | 鉴权（`/auth/me` -> `/auth/login`，token 共享缓存，Keychain 持久化） | 登录闭环 |
| 1.5 | 曲库/专辑/歌手/流派/歌单/收藏/最近播放拉取 | 数据拉取全通 |
| 1.6 | 发现（推荐/榜单/新歌/新碟）+ 5 分钟 TTL 缓存 | 发现数据层 |
| 1.7 | 在线搜索（歌曲/专辑/歌单）+ 音源列表 | 搜索数据层 |
| **验收** | 能登录并拉取真实服务端的曲库/歌单/推荐/搜索；DTO 回退链通过 §8 对照用例 | |

### Phase 2 · 持久化与状态（2 周）

| # | 任务 |
| :--- | :--- |
| 2.1 | 5 个 `@Model` + `ModelContainer` + 迁移策略 |
| 2.2 | `LibraryStore`（曲库/歌单/下载/收藏 CRUD） |
| 2.3 | 33 个设置键 `@AppStorage` + `SettingsStore` |
| 2.4 | 服务端 user-data 收藏双向同步（§1.4 C） |
| **验收** | 冷启动从本地库秒开；收藏两端口径一致 |

### Phase 3 · 播放内核与系统集成（3.5 周）

| # | 任务 |
| :--- | :--- |
| 3.1 | `PlayerEngine`（`AVQueuePlayer`）+ `PlaybackRouter`（§1.4 D） |
| 3.2 | `StreamResolver`（§1.4 A/B，含 409 补源、types/_types、逐档降级） |
| 3.3 | `AudioSessionManager`（后台 / 中断 / 路由变化 / 拔耳机） |
| 3.4 | `NowPlayingCenter`（锁屏 / 控制中心 / 耳机线控 / 封面） |
| 3.5 | `PlaybackQueue`（队列 / 乱序 / 循环 / 最近播放 / 续播恢复） |
| 3.6 | Widget Extension + Live Activity（锁屏卡片 + 灵动岛） |
| **验收** | 后台连续播放 ≥1 小时；锁屏可控；断网->联网自动恢复；上岛可见 |

### Phase 4 · 主界面 UI（6 周）

| # | 任务 |
| :--- | :--- |
| 4.1 | 发现页 + Metal 液态光影卡片 |
| 4.2 | 资料库（7 分类）+ 歌单四宫格 |
| 4.3 | 全屏播放器（封面 + zoom 过渡 + 控制条） |
| 4.4 | 歌词视图（滚动 / 高亮 / 5 行渐变 / 6 套主题 / 调节 Menu） |
| 4.5 | CoverFlow（`scrollTransition` 声明式 3D） |
| 4.6 | 下载管理 + 设置中心 + 全局搜索 |
| 4.7 | iPad / 横屏 / 分屏布局 |
| **验收** | 功能对齐 Android 版核心体验；滚动稳定 120Hz ProMotion |

### Phase 5 · 歌词 / 下载 / 标签（4 周）

| # | 任务 |
| :--- | :--- |
| 5.1 | `LRCParser` + `CharsetDetector`（BOM/UTF-16/UTF-8/GB18030）+ 内嵌歌词读取 |
| 5.2 | 歌词三通道（tag/read -> Range 384KB 本地提取 -> play/lyric） |
| 5.3 | `DownloadManager`（后台 `URLSession` + Range 续传 + 并发上限 + Wi-Fi 限制） |
| 5.4 | `AudioTagWriter`（ID3v2.3 / FLAC Vorbis / M4A ilst）+ `.lrc` 导出 |
| 5.5 | `SongMatchingResolver` 端口 + 双端行为对照 |
| 5.6 | 本地文件导入（`UIDocumentPicker` + bookmark 持久化） |
| **验收** | 下载->标签内嵌->离线播放闭环；同名多版本不串歌 |

### Phase 6 · 打磨与本地构建（2 周）

| # | 任务 |
| :--- | :--- |
| 6.1 | 辅助功能：VoiceOver / Dynamic Type / 降低透明度 / 提高对比度 全链路 |
| 6.2 | 性能：大曲库 10k+ 加载、封面解码、内存水位 |
| 6.3 | 服务端设置同步（`/api/settings`、`/api/paths`） |
| 6.4 | 真机调试配置（免费签名 / 开发者模式）、构建脚本与 README |
| **验收** | 真机可安装运行；辅助功能全项通过；一条命令可产出 .app |

### 里程碑与依赖

```
W1-2    Phase 0  骨架 + 设计系统
W3-5    Phase 1  API 契约层      <- 依赖 0
W6-7    Phase 2  持久化          <- 依赖 1
W8-11   Phase 3  播放内核        <- 依赖 1、2
W9-14   Phase 4  主 UI           <- 依赖 0、2（可与 3 并行）
W15-18  Phase 5  歌词/下载/标签  <- 依赖 2、3
W19-20  Phase 6  打磨与本地构建
--------------------------------
合计 21~24 周（单人）
```

**关键路径**：Phase 0 -> 1 -> 3 -> 5。Phase 4 是最大工作量但可并行。

---

## 8. 测试策略（双端一致性是重中之重）

| 层 | 方式 | 覆盖 |
| :--- | :--- | :--- |
| 契约 | URL 拼装快照测试 | 43 端点 |
| 解码 | 固定 JSON 夹具 -> DTO 断言 | §1.4(E) 全回退链 |
| **双端一致性** | **Kotlin 与 Swift 跑同一组输入，比对输出** | ID 生成、音质降级序列、快照结构、`isSongMatch` |
| 播放路由 | 表驱动（本地/服务端/在线 × 存在/缺失） | §1.4(D) 全分支 |
| 网络 | `URLProtocol` Mock（含 409 / 206 / 超时） | 取链与容灾 |
| 歌词 | 编码夹具（UTF-8/BOM/GBK/UTF-16/乱码） | 解析与修复 |
| UI | SwiftUI Preview + 快照 | 明/暗、动态字体、降低透明度 |

> **强制要求**：`SongMatchingResolver`（Live/演唱会/黑胶/伴奏区分）与 §1.4(A) 音质降级序列
> **必须**做到双端同输入同输出。这两处是唯一会造成「同一首歌两端不同」的根因。

---

## 9. 风险与决策点

### 9.1 风险

| 风险 | 影响 | 缓解 |
| :--- | :--- | :--- |
| `tabViewBottomAccessory` 已知缺陷（placement 环境值不可靠、折叠阈值异常） | 迷你播放器布局 | 不把关键逻辑建立在 `placement` 上；准备自定义 fallback |
| Live Activity 动效受限 | 品牌视觉卖点缩水 | 前台用 App 内自绘胶囊承担动效，系统层只做基础播控 |
| 字段回退链/匹配算法分歧 | 两端元数据或版本不一致 | §8 双端对照用例，CI 阻断 |
| SwiftData 表达力 | 50 条复杂查询迁移受阻 | 预留 GRDB 兜底 |
| App Store 审核 | 分发受限 | TestFlight + Ad Hoc |
| multicast entitlement 审批 | DLNA 不可用 | 优先 AirPlay |
| iOS 26 API 名称/签名变动 | 编译失败 | Phase 0 即以 SDK 头文件复核全部 API |

### 9.2 决策结果

| # | 问题 | 结论 |
| :--- | :--- | :--- |
| 1 | 本地曲库是否需要 | **待定**（见下） |
| 2 | DLNA 是否保留 | **不保留** |
| 3 | APE/DSD/WMA 是否引入 FFmpeg | **不引入** |
| 4 | 变速播放是否保持音调 | **不要求** |
| 5 | 分发定位 | **本地编译测试** |

**唯一待定：本地曲库。** 建议采用「**保留沙盒内导入，砍掉全盘扫描**」的折中：

- 全盘扫描（网易云/QQ/酷狗下载目录）在 iOS 沙盒下本就不可能，这部分必然砍掉。
- 但 `UIDocumentPicker` + security-scoped bookmark 的沙盒内导入成本很低（约 3~5 天），且是「下载后离线播放」闭环的兜底路径。
- 若确认 iOS 版纯粹是「柠檬服务端客户端」，可一并砍掉，再省约 1 周。

> 默认按「保留导入」排期（上表 21~24 周已含）；若要砍掉，Phase 5 减 1 周。

---

## 10. 与旧文档的关系

| 文档 | 处置 |
| :--- | :--- |
| `docs/ios-port-plan.md` | **作废**（KMP + iOS 16 路线，与「零低版本兼容」要求冲突）。其第 1 章代码盘点与第 5 章能力边界已被本文吸收 |
| 旧版 `ios-native-plan.md` 草稿 | 由本文（同名文件的新版）取代 |

---

*本文档基于 v1.7.14 仓库实测生成：48 个 Kotlin 文件 / 36,739 行；端点表来自 LemonMusicProtocol.kt 全量 grep（43 个唯一路径）；算法契约来自 §1.4 所列函数逐行核对；设计 token 来自 Theme.kt / AppDimensions.kt；设置键来自全仓 SharedPreferences 键扫描（33 个有效键）。*
