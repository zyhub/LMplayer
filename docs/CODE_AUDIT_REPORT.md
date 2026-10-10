# LMPlayer 全量代码审计报告（手机端 + TV 端）

> 审计对象：`app/`（LMPlayer 手机/平板端 v1.7.19 / versionCode 54）与 `LMPlayerTV/`（LMPlayerTV v1.10.15 / versionCode 62）
> 审计范围：两端全部 Kotlin 源码（各 50 个文件，约 3.5 万行）+ AndroidManifest + Gradle 构建配置 + 资源
> 审计方式：核心链路逐行精读（播放 / 下载 / 数据 / 网络 / 灵动岛）+ 全量模式扫描（崩溃模式、线程模型、生命周期、权限、资源泄漏）+ 双端差异比对

---

## 一、总体结论

| 维度 | 手机端 | TV 端 | 说明 |
| :--- | :--- | :--- | :--- |
| 可复现崩溃 | 3 处 | 1 处 | 有明确触发条件的必崩缺陷 |
| 数据丢失风险 | **高** | **高** | 数据库升级策略会清空整个曲库 |
| 功能整体失效 | **高** | 低 | 手机端 Android 13+ 本地扫描全盘失效 |
| 线程安全 | 中 | 中 | 缓存 / 单例并发保护缺失 |
| 资源泄漏 | 中 | 中 | 播放器常驻、Socket 未关闭、Retriever 未释放 |
| 性能 | 中 | 中 | 播放启动路径多次全表扫描、组合期主线程 IO |
| 安全 | 中 | 中 | 明文流量 + 全局令牌注入 + 凭据可被备份 |
| 交互正确性 | 中 | **高** | TV 遥控焦点竞态、平台模式可锁死设备 |

**最严重的六条（建议立即修复）**

1. **手机端 Android 13+ 完全无法扫描本地音乐** —— Manifest 与运行时均未声明 / 申请 `READ_MEDIA_AUDIO`（P0-1）。
2. **单曲歌单 + 随机播放模式必崩** —— `PlaybackQueueManager.playNext/playPrevious` 对空列表调用 `random()`（P0-2）。
3. **任何一次数据库版本升级都会清空用户全部本地曲库与服务器配置** —— `fallbackToDestructiveMigration()` + 无迁移脚本 + 无 schema 导出（P0-3）。
4. **TV 换源 / 重试无限循环** —— 三个回退分支互相把对方当「新歌」，重试计数随 `song.id` 变化清零，形成永不停止的闭环（第八章 8.1）。
5. **TV 遥控焦点竞态使浮层「看得见按不动」** —— 歌词调节等浮层未纳入焦点守护白名单（P0-6）。
6. **平台模式可被一次左右键静默切换并让无触屏设备彻底失去遥控操作**（P0-7）。

**问题分布统计**

共记录 **162 项**问题：P0 致命 8 项、P1 严重 39 项、P2 一般 81 项、P3 建议 34 项。按平台归属：手机端主导 34 项、TV 端主导 38 项、双端同源 90 项（两端代码同源，双端同源问题需两端同时修复）。

| 级别 | 主要位置 | 项数 |
| :--- | :--- | :--- |
| **P0 致命** | 第二章（8 节，含 TV 专项 4 节） | **8** |
| **P1 严重** | 第三章（P1-1 ~ P1-24）+ 4.1 网络（7）+ 5.1 手机 UI（6）+ 6.2 TV UI（4）+ 7.2 MainActivity（5）+ 8.2 TV 核心（10）+ 5.2 手机 UI（2）+ 6.1 TV（5） | **39** |
| **P2 一般** | 第九章（21）+ 4.2 网络（13）+ 5.3 手机 UI（9）+ 6.3 TV UI（14）+ 7.3 MainActivity（11）+ 8.3 TV 核心（12）+ 第十一章一致性漂移（17，与前述部分重叠，按结构问题单列） | **81** |
| **P3 建议** | 第十章（11）+ 4.3 网络（4）+ 5.4 手机 UI（3）+ 6.4 TV UI（2）+ 7.3 MainActivity 低级别（11）+ 8.3 TV 核心（12）+ 工程加固（8） | **34** |

> 说明：章节内部条目存在跨级引用（例如 `U-5` 同时属于「严重」与「手机 UI 专项」），本表按**主导级别**归类，去重后口径一致；阅读时以各章节原始条目为准。

---

## 二、致命级缺陷（P0：必现崩溃 / 数据丢失 / 功能整体失效 / 设备锁死）

### P0-1【手机端】Android 13+ 本地音乐扫描 100% 失效（权限缺失）

```
app/src/main/AndroidManifest.xml:25-32            → 只有 READ_EXTERNAL_STORAGE(maxSdkVersion=32)，无 READ_MEDIA_AUDIO
app/src/main/java/com/lm/player/MainActivity.kt:3290-3314 → 只在 SDK_INT <= S_V2 时申请 READ_EXTERNAL_STORAGE
```

**问题**：`targetSdk = 34` 的应用在 Android 13(API 33) 及以上，`READ_EXTERNAL_STORAGE` 对媒体文件**完全失效**，必须使用 `READ_MEDIA_AUDIO`。手机端 Manifest 未声明该权限，运行时也只对 API ≤ 32 分支申请；而扫描实现依赖 `MediaStore.Audio` 查询（`LocalMediaScanner.kt:25-43`）与裸路径 `dir.listFiles()`（`:119-127`），设置页「一键扫描」直接调用（`SettingsScreen.kt:1016`）。

**实证对比**：TV 端两者都做对了 —— `LMPlayerTV/app/src/main/AndroidManifest.xml:39` 声明了该权限，`LMPlayerTV/.../MainActivity.kt:3615-3623` 在 API ≥ 33 分支申请，并在注释里明确记录了这个坑。**手机端漏掉了同一次修复。**

**影响**：Android 13+ 手机上「一键全盘扫描」「本地文件夹导入」「U 盘扫描」全部返回 0 首，MediaStore 查询返回空游标。叠加 M-7（权限结果被丢弃、无拒绝引导），该问题在线上长期无人察觉。

**修复**：
```xml
<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />
```
```kotlin
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
        != PackageManager.PERMISSION_GRANTED) {
        permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
    }
} else {
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
        != PackageManager.PERMISSION_GRANTED) {
        permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}
```

---

### P0-2【双端】随机播放模式下单曲歌单必崩（`NoSuchElementException`）

```
app/src/main/java/com/lm/player/core/media/PlaybackQueueManager.kt:705-707
    val candidates = if (list.size > 1) list.filter { it.id != current?.id } else list
    candidates.random()                    // ← 列表为空时抛 NoSuchElementException

app/src/main/java/com/lm/player/core/media/PlaybackQueueManager.kt:738-739
    val prevSong = if (list.size > 1 && isShuffle) list.filter { it.id != current?.id }.random()
```

**触发条件（极易复现）**：
1. 用户在「搜索结果 / 最近播放 / 单曲下载」中播放一首歌 → `playSong` 里 `_playlistFlow` 只有 1 首；
2. 用户此前开启过随机播放（`isShuffle = true`）；
3. 歌曲自然播完（`onPlaybackStateChanged → STATE_ENDED → playNext`）或点击「下一首」。

此时 `list.size == 1`，走 `else` 分支保留原列表 → `list.filter { it.id != current.id }` 得到**空列表** → `random()` 抛 `NoSuchElementException`。该回调运行在 **ExoPlayer 主线程**，异常直接导致进程崩溃。`playPrevious`（L738-739）同源，多了 `list.size > 1` 守卫，仅在多条目但全部同 id 时不安全。

**修复**：
```kotlin
val nextSong: UnifiedSong = if (isShuffle) {
    val candidates = list.filter { it.id != current?.id }.ifEmpty { list }
    candidates.randomOrNull() ?: return
} else { /* ... */ }
```

---

### P0-3【双端】数据库升级即清空全部用户数据

```
app/src/main/java/com/lm/player/core/database/ZdsDatabase.kt:17-27, 45-46
    @Database(..., version = 4, exportSchema = false)
    .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
    .fallbackToDestructiveMigration()          // ← 升级即 DROP ALL TABLES
app/build.gradle.kts:24
    arg("room.schemaLocation", "$projectDir/schemas")   // 但 schemas/ 目录并不存在
```

**实证**：
- `app/schemas` 与 `LMPlayerTV/app/schemas` **均不存在**，没有任何历史 schema 可比对，`exportSchema = false` 让 KSP 根本不生成 schema；
- 全工程无任何 `Migration` 对象；
- `fallbackToDestructiveMigration()` 在版本号变更且找不到迁移路径时执行**破坏性重建**。

**影响**：下次发版只要把 `version` 从 4 改成 5（增加一个字段就会），用户设备上的 `songs` / `downloads` / `playlists` / `servers` 表**全部清空**：本地曲库索引、已下载记录、自建歌单、服务器地址与令牌全部丢失，而磁盘上的音频文件变成「孤儿文件」永远不会再被识别。

**修复**：1) 打开 `exportSchema = true`，把 `schemas/` 纳入版本控制作为迁移基线；2) 每次改表结构写显式 `Migration(4, 5)` 并 `addMigrations(...)`；3) 短期兜底可改为 `fallbackToDestructiveMigrationOnDowngrade()` —— 只允许降级清库，**升级绝不清库**。

---

### P0-4【手机端】前台服务启动异常被静默吞掉（不可诊断的「点播放没反应」）

```
app/src/main/java/com/lm/player/core/media/PlaybackService.kt:207-484
    override fun onCreate() { ... try { ... startImmediateForeground() ... } catch (e: Throwable) { Log.e(...) } }
```

**问题**：`onCreate` 的整个初始化（含前台通知构建、MediaSession 建立）被一个 `catch (Throwable)` 包裹。一旦前台通知构建失败（厂商 ROM 限制、通知渠道被用户关闭、`startForeground` 抛 `ForegroundServiceStartNotAllowedException`），**异常被静默吞没，服务以「无前台状态」存活**，随后被系统在约 5 秒后以 `ForegroundServiceDidNotStartInTimeException` 杀掉，用户侧表现为「点播放没反应 / 播几秒就停」。

**修复**：准备一个**最小化降级通知**（无 MediaStyle 的纯文本通知）保证前台状态一定能建立；失败写入本地诊断日志（见 P3-2）并在 `onStartCommand` 重试一次。

---

### P0-5【TV】`if (true)` 使整个竖屏播放布局成为死代码

```kotlin
// FullscreenPlayerSheet.kt:144-149   驱动一个从不存在的 Pager
LaunchedEffect(isLyricsMode) {
    val targetPage = if (isLyricsMode) 1 else 0
    if (pagerState.currentPage != targetPage) { pagerState.animateScrollToPage(targetPage) }
}
// :558
if (true) {  … 横屏 / 网易云 TV 布局 …  } else {      // :2389 这一整段（2389-2857）永不执行
    Column { … HorizontalPager(state = pagerState, …) }   // :2515 唯一使用 pagerState 的地方
}
// :132  val isLandscape = … → 全工程仅此 1 处，已成死变量
```

**问题**：竖屏分支（约 470 行）永不进入组合，但 `LaunchedEffect(isLyricsMode)` 仍对其 `pagerState` 调 `animateScrollToPage`；同时 `LaunchedEffect(pagerState.currentPage)`（152-154）反向回调 `onToggleLyricsMode`，与 144 形成**双源同步环**。

**修复**：删除 `if (true)` 恢复 `if (isLandscape)`；pager 不存在时不要调用 `animateScrollToPage`；歌词模式收敛为单一数据源。

---

### P0-6【TV】遥控焦点守护协程抢走浮层焦点 → 浮层「看得见按不动」

```kotlin
// FullscreenPlayerSheet.kt:371 —— 漏掉歌词调节浮层 / 竖屏队列 / 竖屏两个菜单
val anyPopupExpanded = showLandscapeAddToPlaylistMenu || showLandscapeDownloadMenu ||
    showSleepTimerPanel || showAudioSpecsPanel || showPlayerThemeMenu
// :390-399
LaunchedEffect(sheetHasFocus, anyPopupExpanded, isNeteaseCoverMode, isNeteaseControlsVisible) {
    if (!sheetHasFocus && !anyPopupExpanded && ...) {
        kotlinx.coroutines.delay(120L)
        if (!sheetHasFocus && !anyPopupExpanded && ...) { playPauseFocusRequester.requestFocus() }
    }
}
// LyricsScrollingView.kt:601-608  浮层自己 60ms 后 requestFocus 到首项 → 120ms 时被上面抢走
```

**触发条件**：横屏点开歌词区右上角「歌词调节」（`showQueueSheet` / `showDownloadMenu` / `showPortraitAddToPlaylistMenu` 同样漏掉）。

**修复**：把所有浮层状态统一收敛为 `anyOverlayExpanded`，浮层打开期间完全禁用守护协程。

---

### P0-7【TV】平台模式可被一次左右键静默切换，导致无触屏设备彻底失去遥控操作

```kotlin
// SettingsScreen.kt:2862-2877   左右键直接改值，而不是移动焦点
onLeftKey  = { _ -> … onSelect(options[prevIdx]); true },
onRightKey = { _ -> … onSelect(options[nextIdx]); true },
// TvFocusModifiers.kt:97       车机模式下所有 tvFocusable 失去 canFocus
val focusEnabled = effectiveEnabled && LocalPlatformMode.current != PlatformMode.CAR
// MainActivity.kt:3562         车机模式直接吞掉整个 D-Pad（不做任何处理）
if (isCarPlatformActive && isTvNavKey(event.keyCode)) return true
// PlatformModeWizard.kt:74     向导永久吞掉返回键
BackHandler(enabled = true) { }
```

**问题**：遥控器在设置页误按一次左右键，就在**无二次确认**下切到 CAR；CAR 模式下所有 `tvFocusable` 的 `canFocus = false`，Activity 层又吞掉整个 D-Pad，而改回模式必须进设置页（同样依赖方向键）→ **设备级锁死**。向导文案自己都警告过这一点，而 `PlatformMode` 只有 TV / CAR 两态、没有逃生态。

**修复**：平台模式行禁用左右键改值（或需长按 OK + 对话框确认）；切 CAR 前明确提示；保留「长按返回 3 秒恢复 TV 模式」逃生通道；`onKeyEvent` 只消费 ACTION_DOWN。

---

### P0-8【TV】「定时关闭」是空壳功能（只弹 Toast，无任何实现）

```kotlin
// FullscreenPlayerSheet.kt:2121-2128   只改状态 + Toast，无任何调度
onSelectTimer = { min ->
    activeTimerMinutes = min
    if (min > 0) Toast.makeText(context, "已设置：${min}分钟后停止播放", Toast.LENGTH_SHORT).show()
}
// :169   var activeTimerMinutes by remember { mutableStateOf(0) }  ← 非 rememberSaveable，且无消费者
```

**问题**：全工程 grep `activeTimerMinutes` 仅出现在本文件 UI 层，没有调度任务、没有播放器回调 —— 用户设置定时关闭后**永远不会生效**。

**修复**：定时器实现移到播放层（协程 `delay` + `PlaybackQueueManager` 暂停），状态用 `rememberSaveable` 或放到 Service，并显示倒计时。

---

## 三、严重级缺陷（P1：崩溃 / 数据不一致 / 明显资源浪费）

### P1-1【双端】播放器单例永不释放，WakeLock / WifiLock / 监听器全部悬挂

```
app/src/main/java/com/lm/player/core/media/Media3Factory.kt:31, 129-139
    @Volatile private var sharedExoPlayer: ExoPlayer? = null
    .setWakeMode(C.WAKE_MODE_NETWORK)      // 持有 PARTIAL_WAKE_LOCK + WifiLock
app/src/main/java/com/lm/player/core/media/PlaybackService.kt:710-733
    override fun onDestroy() { ...; exoPlayer = null; ... }   // 只清引用，从不 player.release()
```

**问题**：`sharedExoPlayer` 是进程级静态字段，**全工程没有任何一处调用 `release()`**（TV 端只在软解降级中释放旧实例）；`C.WAKE_MODE_NETWORK` 使 ExoPlayer 在「已 prepare」状态下持续持有 CPU WakeLock 与 WifiLock，**用户暂停播放后依然持有**；`onDestroy` 里 `exoPlayer = null` 只是丢了局部引用，播放器与 `PlaybackQueueManager.ensurePlayerListener` 注册的监听器（L555-620）继续存活。

**影响**：进程存活期间（从最近任务划掉后可能仍存活数分钟到数十分钟）设备无法进入深度睡眠，**息屏待机耗电显著上升**；TV / 车机长期插电场景会被厂商省电策略判定为异常耗电应用。

**修复**：在 `Media3Factory` 增加 `releaseSharedPlayer()`，于 `stopServiceAndPlayback()`、`onTaskRemoved`（`stopPlaybackOnExit == true`）、`ACTION_STOP_SERVICE` 分支中调用；同时 `PlaybackQueueManager` 需重挂监听并清空 `isListenerAttached`。

---

### P1-2【双端】封面位图缓存无并发保护（`LruCache` + 无同步的 Bitmap 字段）

```
app/src/main/java/com/lm/player/core/media/DynamicIslandManager.kt:106-113
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bitmapCache = LruCache<String, Bitmap>(24)
    private var fallbackCoverBitmap: Bitmap? = null        // ← 非 @Volatile、无同步
DynamicIslandManager.kt:508-582   loadSongArtworkBitmap() 在 IO 线程 decode 并 put
DynamicIslandManager.kt:636, 894   MediaNotification.Provider / 通知构建线程 get
BackgroundIslandOverlayController.kt:182  渲染线程 get 并把 Bitmap 交给 Canvas 绘制
```

**问题**：1) `LruCache` 内部方法同步，但 `get → decode → put` 是复合操作：`onPlayerError` 的快速换源重试、`PlaybackRouter.resolveMediaItem` 的 700ms 超时预热、`BackgroundIslandOverlayController.refreshVisibilityAndState` 可能**同时对同一首歌解码封面**；2) `fallbackCoverBitmap` 无任何同步，两个线程可同时判定为 null 并各建一份 256×256 ARGB_8888 位图（约 256KB），随后**同一张 Bitmap 被渲染线程与通知线程同时持有**。

**修复**：`fallbackCoverBitmap` 加 `@Volatile` + 双重检查；`loadSongArtworkBitmap` 用 `ConcurrentHashMap<String, Deferred<Bitmap>>` 做单飞，保证同一 `songId` 只解码一次。

---

### P1-3【双端】下载目标路径可被 `..` 穿越到应用目录之外

```
app/src/main/java/com/lm/player/core/media/DownloadEngine.kt:241-248
    fun sanitizeSegment(name: String): String {
        val cleaned = name.trim().replace(Regex("""[\\/:*?"<>|\r\n\t]"""), "_")
            .replace(Regex("""\s+"""), " ").trim('.', ' ')   // 只裁首尾的 '.'，中间 ".." 原样保留
DownloadEngine.kt:267-273   candidate!!.split('/', '\\').map { sanitizeSegment(it) }
DownloadEngine.kt:284-287   val parentDir = File(baseDir, subFolder); parentDir.mkdirs()
```

**问题**：整段恰为 `..` 时会被清空并丢弃；但形如 `Album/..` 或 `a/./../..` 的组合中内层 `..` 会被保留，而 `File(baseDir, "Album/../../x")` 会**解析到应用私有目录之外的任意路径**。

**数据来源**：`relativeFolderPath` 直接来自柠檬服务端返回的文件夹层级或数据库历史值 —— 即**服务端可控**。

**修复**：`if (cleaned == "." || cleaned == "..") "" else cleaned`；并在拼接后做 `canonicalFile` 前缀校验，确保结果仍在 `baseDir` 之内。

---

### P1-4【双端】断点续传不校验 `Content-Range`，可静默产出损坏音频文件

```
app/src/main/java/com/lm/player/core/media/DownloadEngine.kt:513-535
    var response = okHttpClient.newCall(buildDownloadRequest(existingBytes)).execute()
    response.use { resp ->
        val isRangeOk = resp.code == 206
        val append = isRangeOk && existingBytes > 0L
        val totalLength = if (isRangeOk) existingBytes + body.contentLength() else { ... }
```

**问题**：只判断状态码是否 206，**没有校验 `Content-Range: bytes X-Y/Z` 中的 X 是否等于 `existingBytes`**。若服务端或中间代理返回偏移不一致的 206，本地断点内容与新数据会被直接拼接，最终文件头部正常、中段错乱 —— 播放器能解码出时长但音频出现杂音 / 跳帧，而且**魔数检测依然判定为合法 flac/mp3**，用户与日志都难以发现。

**修复**：解析 `Content-Range` 起点，与 `existingBytes` 不一致时丢弃断点重下；完成后校验 `actualDestFile.length() == totalLength`。

---

### P1-5【双端】「已下载」标记不校验物理文件，UI 与事实不一致

```
app/src/main/java/com/lm/player/core/media/SongMatchingResolver.kt:270-278
    fun isLocalPathValidFast(path: String?, status: DownloadStatus): Boolean {
        if (path.startsWith("content://")) return true
        if (downloadDir == null && status == DownloadStatus.DOWNLOADED) return true   // ← 直接信任标记
        return validPathMemo.getOrPut(path) { File(path).let { it.exists() && it.length() > 0 } }
    }
```

**问题**：为避免主线程 `stat()` 而直接信任 `DOWNLOADED` 标记。当用户在系统文件管理器 / 第三方清理工具里删除音频文件、或下载目录被卸载（U 盘拔出、SD 卡移除）时，数据库中该曲目仍是 `DOWNLOADED` + 有效 `localFilePath`，UI 显示「已下载」并允许离线播放，实际点击后 `PlaybackRouter` 拼出 `file://` Uri 交给 ExoPlayer，**播放直接失败且无任何提示**。

**修复**：把存在性校验放到 IO 线程批量修正；`LocalMediaScanner.verifyAndSyncAllServerSongDownloadStatus` 已实现该逻辑，应在进入「已下载」列表与播放路由前各跑一次。

---

### P1-6【双端】`MediaMetadataRetriever` 在异常路径不释放（FD 泄漏）

```
app/src/main/java/com/lm/player/core/media/LyricsManager.kt:1150-1159
    val retriever = MediaMetadataRetriever()
    retriever.setDataSource(localFile.absolutePath)
    val metaLyrics = SmartCharsetDecoder.repairMojibakeIfNeeded(retriever.extractMetadata(1000))
    retriever.release()                    // ← setDataSource/extractMetadata 抛异常则永不执行
app/src/main/java/com/lm/player/core/media/LocalMediaScanner.kt:143-184
    try { retriever.release() } catch (_: Exception) { }   // 若 insertSongs 抛异常则跳过
```

**问题**：`MediaMetadataRetriever` 持有 native 解码器与文件描述符。歌词解析是**每首播放曲目**都会走的路径，泄漏会在长时间使用后耗尽 FD，表现为「随机某首歌无法读取封面 / 歌词 / 无法播放」这类极难排查的故障。

**修复**：统一改为 `try { ... } finally { runCatching { retriever.release() } }`；扫描循环中每 N 个文件 `release()` 并重建。

---

### P1-7【双端】全局缓存合计可达 2.3GB，超出常规实践约 45 倍

```
app/src/main/java/com/lm/player/core/media/Media3Factory.kt:41
    LeastRecentlyUsedCacheEvictor(2L * 1024 * 1024 * 1024)      // 2 GB 流媒体缓存
app/src/main/java/com/lm/player/LMApplication.kt:92-97
    DiskCache.Builder().maxSizeBytes(256L * 1024 * 1024)        // 256 MB 图片缓存
app/src/main/java/com/lm/player/core/network/NetworkClientFactory.kt:69
    okhttp3.Cache(cacheDir, 64L * 1024 * 1024)                  // 64 MB HTTP 缓存
```

**问题**：三者都在 `context.cacheDir` 下，合计上限约 **2.3GB**；行业常规（ExoPlayer 官方示例）流媒体缓存为 50–200MB。Android 只在存储空间告急时才回收缓存目录且不保证；TV 盒子 / 车机内置存储常为 8–16GB。

**修复**：`LeastRecentlyUsedCacheEvictor` 改为 200–300MB 并按可用空间动态取值（`StatFs`，取 `min(300MB, 可用空间/10)`）；图片缓存降至 64–128MB；HTTP 缓存 16MB 或移除。

---

### P1-8【双端】播放启动路径最多 3 次「全表扫描 + 内存模糊匹配」

```
app/src/main/java/com/lm/player/core/media/PlaybackRouter.kt:68-80     getAllSongsList()
app/src/main/java/com/lm/player/core/media/PlaybackRouter.kt:132-149   getAllSongsList()   （第二次）
app/src/main/java/com/lm/player/core/media/PlaybackRouter.kt:188-197   getAllSongsList()   （第三次）
app/src/main/java/com/lm/player/core/media/SongMatchingResolver.kt:485   getAllSongsList()
app/src/main/java/com/lm/player/feature/settings/SettingsScreen.kt:255   getAllSongsList()
app/src/main/java/com/lm/player/MainActivity.kt:815                     getAllSongsList()
```

**问题**：`getAllSongsList()` 分页把**整张 songs 表**读进内存（1 万首约 2–4MB 对象）。`PlaybackRouter.resolveMediaItem` 在**解析一首歌的播放地址时最多调用 3 次**，每次都要 `firstOrNull` 做 O(n) 的标题 / 艺人 / 时长模糊比对（含正则规范化）→ 1 万首曲库下每次点播额外做约 3 万次比较，表现为**点歌后 0.5–3 秒无响应**，且每次都发生。

**修复**：1) 同一次 `resolveMediaItem` 只取一次 `allSongs` 复用；2) 为 `songs` 增加 `normTitle` / `normArtist` 冗余列 + 索引，用 SQL 直查候选集；3) 引入内存 LRU（约 512 条）缓存最近匹配结果。

---

### P1-9【双端】网络换链请求放大且无总预算（协议层）

```kotlin
// LemonMusicProtocol.kt:2672   4 档音质 × 2 轮（本平台 + 跨平台） × 6 个候选源，全串行
for ((index, qKey) in candidateQualities.withIndex()) { … }
// :2546  每个 409 备选音源脚本再各发一次请求
for (altId in altIds) { val (altCode, altBody) = requestPlayUrlOnce(...) }
// :2575  val candidateSources = listOf(actualSource, "kw", "tx", "wy", "kg", "mg").distinct()
```

**问题**：串行尝试最多可达数十次网络请求，单次 `readTimeout = 60s`，无总超时预算。

**修复**：整条解析链包 `withTimeoutOrNull(15_000)`；`altIds` 去重并设上限；禁止「音质 × 音源」笛卡尔积。

---

### P1-10【双端】协程取消失效：`catch(Exception)` 吞掉 `CancellationException` + 阻塞式 `execute()`

```kotlin
// LemonMusicProtocol.kt:2609-2611（全文件 40+ 处同型）
} catch (e: Exception) {
    Result.failure(e)          // CancellationException 也被吞成失败
// :2082
} catch (_: Exception) { return@withContext null }
```

**问题**：全文件 51 处使用**阻塞式** `execute()`（非 `enqueue`）→ 协程取消既中止不了在途请求，也中止不了换链循环；而 `catch (Exception)` 又把 `CancellationException` 吞成 `Result.failure`。用户快速切歌时旧请求继续跑完并可能回写状态。

**修复**：catch 内先 `if (e is CancellationException) throw e`；或改 `suspendCancellableCoroutine + enqueue` 让 Call 真正可取消。

---

### P1-11【双端】凭据安全三连：明文口令 / 令牌进 URL / 全局令牌跨主机注入

```kotlin
// ① 口令明文 POST，且地址未写协议时被强制补 http（SettingsScreen.kt:2175-2183）
LemonMusicProtocol.kt:315   put("password", tokenOrPasswordPlain.ifBlank { config.tokenOrApiKey })
// ② 令牌未 URL 编码直接拼进 query（path 编码了，token 没有）
LemonMusicProtocol.kt:1846  return "$cleanBase$endpoint?path=$enc&token=$authToken$qParam"
// ③ 登录写全局静态令牌，共享拦截器对任意主机的 /api/ 自动注入 Bearer
LemonMusicProtocol.kt:244   NetworkClientFactory.setActiveAuthToken(authToken)
NetworkClientFactory.kt:112 else if (activeAuthToken.isNotBlank() && encodedPath.startsWith("/api/")) {
NetworkClientFactory.kt:113     reqBuilder.header("Authorization", "Bearer $activeAuthToken")  // 不校验 host
```

**影响**：① 用户填 `192.168.x.x:3000` 时口令与令牌在链路明文传输；② 令牌含 `&` / `+` / `=` / `#` / 空格时被截断或误解码（`+` 被解成空格），且令牌进入流地址 / 封面地址后落入系统日志与代理日志；③ 同时配置其它服务器（NAS / 第三方）时，其 `/api/` 路径会收到柠檬服务器令牌。

**修复**：默认 https 并对 http 场景显式告警；令牌改走 `Authorization` 头（必须放 query 时 `URLEncoder.encode`）；拦截器校验 `request.url.host` 与令牌绑定主机一致。

---

### P1-12【双端】Range 判定接受 200，整首音频读入内存（OOM 风险）

```kotlin
// LemonMusicProtocol.kt:2058-2075
if (resp.isSuccessful || resp.code == 206) {      // 206 本就在 isSuccessful 内，等价于接受 200
    val bytes = resp.body?.bytes()                 // 服务端忽略 Range 时 = 整个音频文件
```

**修复**：只接受 206；读取前用 `Content-Length` 上限（≤512KB）判定，超限放弃该通道。

---

### P1-13【双端】收藏 / 歌单「整表读改写」，无锁无版本号（并发丢更新）

```kotlin
// LemonMusicProtocol.kt:3564-3606
val userDataRes = getLibraryUserData()                 // 整表 GET
for (i in 0 until favArr.length()) { … keptItems.add(item) }   // 本地过滤重排
val req = newAuthRequest("$cleanBase/api/library/user-data").put(body).build()   // 整表 PUT
```

**问题**：无锁、无版本号、无 `If-Match` → 连点收藏、收藏与歌单追加同时发生、双端同步时会互相覆盖（收藏丢失）。

**修复**：同账号写操作加 `Mutex` 串行化，或改用服务端单条 add / remove 接口。

---

### P1-14【手机】滚动导致整页重组 + 组合期主线程磁盘 IO（4 处）

```kotlin
// ① LemonDiscoverHomeScreen.kt:258-262  根作用域裸读 LazyListState（未用 derivedStateOf）
val isScrolledAway = (activeCollectionTitle != null && (collectionListState.firstVisibleItemIndex > 0 ||
    collectionListState.firstVisibleItemScrollOffset > 0)) || …
// ② LocalLibraryScreen.kt:279-289  remember 内逐首 File.exists()（全库 2000+ 首）
val finalDownloadedSongs = remember(allSongs, downloadedSongs) { … java.io.File(s.localFilePath!!).exists() … }
// ③ LocalMusicHomeScreen.kt:250-251, 322-324  列表行内 File.exists() 未 remember + FileInputStream 读魔数
// ④ FullscreenPlayerSheet.kt:432-433, 1300-1301  播放中每次进度回调都 File.exists()+length()
```

**影响**：① 滚动期间**每一帧**让整个发现页失效重组；②③④ 主线程磁盘 stat / 读取随滚动与播放进度持续发生 → 掉帧、点歌卡顿、ANR 风险。

**修复**：`derivedStateOf` 包裹；所有磁盘 IO 移出组合期（`produceState` / `LaunchedEffect` + `Dispatchers.IO`），存在性结果放进数据模型或后台预计算 Set。

---

### P1-15【手机】loading 标志无 `finally`（5 处）→ 永久转圈 / 永久禁用

```kotlin
// LocalLibraryScreen.kt:1411-1416（2030-2035 同构）；LemonDiscoverHomeScreen.kt:983-986 等 3 处
isLoadingSubView = true
coroutineScope.launch { activeSubViewSongs = onFetchPlaylistSongs(pl.id, pl.isOnline); isLoadingSubView = false }
// SettingsScreen.kt:387-400 / 615-625 / 1001-1029 / 1756-1765 四处 loading 标志同样无 finally
```

**影响**：网络抖动或回调抛异常时，`isLoadingSubView` 永久 true → 页面停在永久转圈；设置页「测试连通性」按钮永久禁用并显示「测试中...」、刷新脚本永久转圈、扫描比对永久禁用、清理遗留数据永久 true。用户只能杀进程重开。

**修复**：统一 `try/finally` 复位标志，异常走 Toast；下钻取数用 Job 取消旧请求或 `requestId` 只接受最后一次响应。

---

### P1-16【手机】主线程同步落盘 `commit()`（含每次点播 / 切歌）

```kotlin
// 手机 core/PlaybackQueueManager.kt:650-655（playSong 内，UI 线程调用链）
savePlaybackState(context = appCtx, song = targetSong, positionMs = …, commitSync = true)
// 内部 :327-356 走 editor.commit() 并序列化最多 120 首队列 JSON（MAX_PERSISTED_QUEUE_SIZE=120）
// TV core/PlaybackQueueManager.kt:427-431 已修：
if (commitSync && Looper.myLooper() == Looper.getMainLooper()) { ioExecutor.execute { … } }
```

**影响**：手机端在 `onPause / onStop / onDestroy / exitAppCompletely` 与**每次点播 / 切歌**都主线程 commit —— 连按方向盘切歌、大曲库、慢速 eMMC 上掉帧甚至 ANR。

**修复**：照抄 TV：`savePlaybackState` 内判主线程改投串行 IO；Activity 侧用 `persistScope`。

---

### P1-17【双端】媒体键只吞 DOWN、放行 UP → 一次按键触发两次切歌

```kotlin
// 手机 MainActivity.kt:3279-3283
override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_DOWN) { if (handleMediaKeyEvent(event.keyCode)) return true }
// TV MainActivity.kt:3586 已修：成对消费
if (pendingDownKeyCodes.remove(event.keyCode)) return true
```

**影响**：未被消费的 UP 进入 MediaSession，由 Media3 默认处理**再执行一次**「下一首 / 上一首」；Service 侧只处理 DOWN，防抖时间戳只在 DOWN 更新，挡不住。蓝牙 / 方向盘 / 遥控器按一下即双触发。

**修复**：照抄 TV，用 `pendingDownKeyCodes` 成对吞掉 DOWN/UP。

---

### P1-18【手机】边缘右滑返回手势用 `Initial` 阶段且不检查 `isConsumed`

```kotlin
// 手机 MainActivity.kt:1837, 1843
val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
val event = awaitPointerEvent(PointerEventPass.Initial)
// TV MainActivity.kt:2074 已修：
if (drag.isConsumed) break
```

**影响**：Initial 阶段父节点先于子节点收到、消费标记恒为 false，无法判断拖动是否已被子组件认领 → 在屏幕左缘按住播放进度条 / 横向滚动列表向右拖会误触发返回；清单又开了 `enableOnBackInvokedCallback="true"` 却没有 `PredictiveBackHandler`。

**修复**：照抄 TV，改 `PointerEventPass.Main` + `if (drag.isConsumed) break`。

---

### P1-19【TV】换绑后 `Player.Listener` 留在已释放的播放器上

```kotlin
// TV MainActivity.kt:102, 1952, 2035-2038
DisposableEffect(exoPlayer, currentSong, songList, autoFallbackToLocal) {
    exoPlayer?.addListener(listener)
    onDispose { exoPlayer?.removeListener(listener) }   // 摘的是新实例，旧实例上的没摘掉
}
```

**问题**：`DisposableEffect` 的 key 是普通字段 `exoPlayer`（非 Compose State）。`Media3Factory.rebuildWithSoftwareDecoding()` 会 `release()` 旧实例并回调换绑，但字段赋值不触发重组 → key 不变：监听器留在**已释放**的旧实例上，`onDispose` 又从新实例摘（摘不掉）。

**修复**：把播放器实例放进 `mutableStateOf`，或用可观察的 swap 版本号作为唯一 key。

---

### P1-20【双端】权限申请结果被丢弃，全程未用 `shouldShowRequestPermissionRationale`

```kotlin
// 手机 MainActivity.kt:121-123 / TV MainActivity.kt:124-126
private val permissionLauncher = registerForActivityResult(
    ActivityResultContracts.RequestMultiplePermissions()) { _ -> }   // 结果未使用
```

**问题**：用户点「拒绝」后没有任何说明或引导，也没有二次申请 —— 这正是 P0-1 能长期潜伏而无人察觉的原因。

**修复**：回调中判断 `granted`，被拒时给出说明并跳系统设置；再申请前先查 rationale。

---

### P1-21【TV】9 处 `Dialog` 无初始焦点绑定 → 弹窗打开后遥控器失灵

```kotlin
// SettingsScreen.kt:1871 / 2015 / 2148 / 2293 / 2536 / 2577；LocalLibraryScreen.kt:1729 / 2265 / 2368
// 项目在所有 DropdownMenu 里都用 delay(60)+requestFocus() 补偿（如 2847-2854、2967-2974），这些 Dialog 一个都没有
```

**影响**：TV 上弹窗打开后无节点持有焦点，方向键 / OK 落到弹窗外 —— 「添加 / 编辑服务器」「导入脚本」「服务器路径」「添加扫描目录」「新建歌单」等全部不可用。

**修复**：每个 Dialog 给首个可聚焦控件加 `FocusRequester` + `awaitFrame()` 后 `requestFocus()`；关闭时把焦点还给触发按钮。

---

### P1-22【TV】下钻详情「整块替换」导致焦点丢失，返回后光标不回原卡片

```kotlin
// LocalLibraryScreen.kt:1676-1700（+416、140-145）；LemonDiscoverHomeScreen.kt:921-943、116-133
// activeSubViewTitle == null 与 != null 互斥渲染 → 被点卡片连同焦点节点被移出组合
```

**修复**：详情页首项（返回键）加 `FocusRequester`；返回时按记录的来源卡片恢复焦点，或改用 `focusRestorer` + `focusGroup`。

---

### P1-23【手机】进度轮询无生命周期 + Room 冷流每次重组重建

```kotlin
// 手机 MainActivity.kt:2959-2982  无 repeatOnLifecycle，退后台仍每 400ms 读播放器并写 State
LaunchedEffect(isPlaying, song.id) { while (isActive && isPlaying) { … delay(400) } }
// 手机 MainActivity.kt:334  每次重组都是新 Flow 实例 → produceState 重启收集、重发 SQL
val allDownloads by database.downloadDao().getAllDownloadsFlow().collectAsState(initial = emptyList())
// TV MainActivity.kt:3132 / 406-411 已修
```

**影响**：退到后台仍持续轮询耗电；暂停时进度不刷新（暂停中 seek 后进度条停在旧位置）；Room 冷流高频重建失效观察者。

**修复**：照抄 TV，`repeatOnLifecycle(Lifecycle.State.STARTED)` 且不依赖 `isPlaying`；Flow 用 `remember { ... .distinctUntilChanged() }` 固定。

---

### P1-24【TV】设置页 Tab「焦点即切换」触发网络请求 + 全库磁盘校验

```kotlin
// SettingsScreen.kt:341-351
onFocusChange { if (focused) selectedTab = tab }
// :216-293  LaunchedEffect 每次发 fetchSourceList / getServerSettings / getServerPaths
//           并对全库执行 File.exists()+length() 与 freeSpace 统计
```

**影响**：遥控器从左扫过 5 个 Tab 就连发 5 组请求 + 全表磁盘校验；`selectedTab` 变化还会整块替换 LazyColumn 内容 → 内容区焦点丢失。

**修复**：焦点变化只做「预高亮」，切换放到 OK 键；副作用加缓存与防抖，统计移出主路径。

---

## 四、网络协议层专项审计（`LemonMusicProtocol.kt`，两端各约 3.6–3.8k 行）

> 该文件是两端最大的单文件，承担登录、曲库同步、在线搜索、换链、收藏与歌单同步。以下条目均经逐段通读确认。好消息：**未发现必崩缺陷**（5 处 `!!` 均有 `isNullOrEmpty` 前置守卫，下标访问均有 size 判断，`JSONException` 基本落在 `catch(Exception)` 内）；OkHttp 客户端确为全局复用单例，51 处 `execute()` 全部用 `use {}` 关闭，静态集合全部是 `ConcurrentHashMap`，无 CME 风险。

### 4.1 严重（已并入 P1-9 ~ P1-13）

| 编号 | 位置 | 问题 | 触发条件 | 修复建议 |
| :--- | :--- | :--- | :--- | :--- |
| N-1 | `:2672-2705`、`:2546`、`:2575` | 换链请求放大且无总预算（见 P1-9） | 目标曲目在多平台都缺该音质，或服务端返回多个 alternatives | 整链 `withTimeoutOrNull(15_000)`；`altIds` 去重限流 |
| N-2 | `:2609-2611`、`:341-344`、`:2082-2084`（全文件 40+ 处） | `catch (Exception)` 吞掉 `CancellationException` + 51 处阻塞 `execute()`（见 P1-10） | 用户切歌、退出播放、页面销毁 | 取消重抛；改 `enqueue` + `suspendCancellableCoroutine` |
| N-3 | `:313-321` + `AndroidManifest.xml:43` + `SettingsScreen.kt:2175-2183` | 口令明文 POST 且默认降级 http（见 P1-11） | 用户填 `192.168.x.x:3000` | 默认 https；http 目标明确告警并禁止记住密码 |
| N-4 | `:1841-1849`、`:1884-1891`、`:1921-1929` | 令牌未 URL 编码直接拼进 query（见 P1-11） | 令牌含特殊字符 | 改 `Authorization` 头或 `URLEncoder` |
| N-5 | `:244` + `NetworkClientFactory.kt:108-115` | 全局静态令牌跨主机注入（见 P1-11） | 同时配置其它服务器且路径以 `/api/` 开头 | 拦截器按 host 绑定令牌 |
| N-6 | `:3564-3606`、`:3623-3683`、`:3310-3328` | 收藏与歌单整表读改写，无锁无版本号（见 P1-13） | 同账号两次写操作并发 | 加 `Mutex` 或改单条接口 |
| N-7 | `:2058-2075` | Range 判定接受 200，整首音频入内存（见 P1-12） | 服务端或代理忽略 `Range` 头 | 只接受 206 + `Content-Length` 上限 |

### 4.2 一般（P2）

| 编号 | 位置 | 问题 | 修复建议 |
| :--- | :--- | :--- | :--- |
| N-8 | `:100-102`、`:452-475`、`:111-116` | 三个静态缓存只增不减（`clearDiscoverCache()` 也不清理），key 仅由路径 md5 决定、不含服务器标识 → 内存无限增长 + 切换服务器后命中别台服务器的旧路径 | 加 LRU 上限或按 `serverId` 分桶；切换服务器 / 登出时清空 |
| N-9 | `:405-420` | 忽略入参 `offset/limit`，固定 `?all=1` 把整个曲库 JSON 一次性 `string()` 读入并建树 | 尊重分页；用 `JsonReader` 流式解析 |
| N-10 | `:764-767`、`:828-831` | `getAlbums` / `getArtists` 同样忽略分页，硬编码 `limit=500` → 超过 500 条被**静默截断** | 循环分页或返回 `hasMore` |
| N-11 | `:297-310` | 会话校验分支的 `JSONObject(body)` 无独立 try，200 但非 JSON（反代门户页 / 空体）时异常直达外层 catch → **永远走不到账号密码登录** | `runCatching` 包住该次解析后继续密码登录 |
| N-12 | `:573-576`、`:3702-3703` | `optBoolean(name, default)` 返回非空，其后 `?:` 兜底分支全是**死代码** → 扫描状态 / 进度恒为 false / 0 | 先用 `has()` 判断或用无默认重载 |
| N-13 | `:2924-2967` | `fetchSourceList` 在循环体内对**每条**音源脚本都调一次 `getServerSettings()`（N 次重复请求） | settings 提到循环外取一次 |
| N-14 | `:1291-1302` | `split(":").mapNotNull { toLongOrNull() }` 丢弃失败分量导致错位：`"01:02:03.500"` → `[1,2]` → 返回 62_000ms（应为 3_723_500ms） | 固定位置解析，任一分量失败即整串弃用 |
| N-15 | `:433-434` vs `:1298-1302` | 同一 `duration` 字段两处单位假设不一致（一处无条件 ×1000，一处按 >10000 判毫秒）→ 服务端返回毫秒时曲目时长放大 1000 倍 | 统一解析函数 + 范围校验（1s–24h 之外视为异常） |
| N-16 | `:1911-1916`、`:1838-1849` | 接口声明的 `maxBitrate` 完全未使用，曲库曲目 `streamUrl` 构造时固化（`quality = null`）→ **设置里选的音质对服务器本地曲目不生效** | 传入 `getPreferredStreamQuality` 结果 |
| N-17 | `:407` 等 10+ 处 | `ensureAuthenticated()` 返回值**全部未接收**：鉴权失败仍发无 Authorization 的请求，401 被当业务错误；且此刻把不含令牌的 streamUrl 固化进 `UnifiedSong`，该曲目会长期 401 | 检查返回值并触发一次重新登录；streamUrl 改为播放时按需解析 |
| N-18 | `:44-45`、`:302`、`:358-377` | `authToken` 是普通 `var`，IO 线程写、任意线程（含 Main）读，无 `@Volatile` → 可见性无保证 | 加 `@Volatile` / `AtomicReference`，令牌变更后失效旧 URL |
| N-19 | `:3745-3755` | PUT 失败降级 POST 后，报错用的是第一个响应的 `resp.code` 而非 `postResp.code` | 使用 `postResp.code` |
| N-20 | `:3192-3198` | 失败路径返回 `Result.success(false)`，按 `isSuccess` 判断的调用方会误认为成功；`runCatching` 还吞掉网络异常 | 返回 `Result.failure` 并带状态码 |

### 4.3 建议（P3）

| 编号 | 位置 | 问题 | 修复建议 |
| :--- | :--- | :--- | :--- |
| N-21 | `:3225-3227` | catch 分支返回 `success(硬编码平台列表)`，把接口故障伪装成成功 | 返回 failure，由调用方决定默认值 |
| N-22 | `:47-70` | `activeInstance` 静态单例命中即返回，不校验 `serverUrl` / `username` 是否与当前 active server 一致 | 记录构建配置，不一致则重建 |
| N-23 | `:213-222` | `getPreferredStreamQuality` 在调用线程同步读 SharedPreferences，`isWifiOrUnmeteredConnected` 还走 ConnectivityManager binder；调用方多在主线程 | 预取缓存或挪到 IO |
| N-24 | `:290-294` vs `:241` / `:374` | 会话校验把 `tokenOrPasswordPlain` 当 Bearer 试发，长度阈值 20 与 32 不一致 → 20–31 位**口令被塞进 Authorization 头**发往 `/api/auth/me` | 统一阈值，仅令牌形态（`lemon-` / JWT）才试发 |

---

## 五、手机端 Compose UI 专项审计（4 个大型界面文件，共约 10.4k 行）

> 覆盖 `LocalLibraryScreen.kt`(3222) / `SettingsScreen.kt`(2763) / `LemonDiscoverHomeScreen.kt`(2193) / `FullscreenPlayerSheet.kt`(2189)。

### 5.1 严重（已并入 P1-14 / P1-15）

| 编号 | 位置 | 问题 | 触发条件 |
| :--- | :--- | :--- | :--- |
| U-1 | `LemonDiscoverHomeScreen.kt:258-262` | 滚动导致整页重组：根作用域裸读两个 `LazyListState`，未用 `derivedStateOf`（`LocalLibraryScreen.kt:202-210` 就是正确写法） | 手指滚动发现页任意列表 |
| U-2 | `LocalLibraryScreen.kt:279-289` | `remember` 内逐首 `File(path).exists()`（全库 2000+ 首），且 key 是整个 `List` → 每次重组对上千条 O(n) 深比较 | 进入资料库 / 曲库变化 |
| U-3 | `LocalLibraryScreen.kt:1699, 2245` → `LocalMusicHomeScreen.kt:250-251, 322-324` | 列表行在组合期做磁盘 IO：`File.exists()` 未 `remember`；`remember` 内 `resolveRealLocalFormatAndSize` 会 `FileInputStream` 读 12 字节魔数 | 滚动「全部歌曲 / 歌单详情 / 新歌首发」 |
| U-4 | `FullscreenPlayerSheet.kt:432-433, 1300-1301` | 播放期间持续主线程 stat：`hasPhysicalLocal` 在 composable 体内（非 `remember`）做 `File.exists()+length()`，而 `progressMs` 是入参 | 播放任意本地 / 在线歌曲 |
| U-5 | `LocalLibraryScreen.kt:1411-1416, 2030-2035`；`LemonDiscoverHomeScreen.kt:983-986, 1208-1211, 1293-1296` | 下钻取数协程无 `try/finally` 也无去重 → 异常时永久转圈；快速连点两个歌单会并发请求并被旧响应覆盖 | 网络抖动；快速连点不同歌单 |
| U-6 | `SettingsScreen.kt:387-400, 615-625, 1001-1029, 1756-1765` | 四处 loading 标志 set true 后无 `finally` → 按钮永久禁用 / 永久转圈 | 任一网络 / DB 调用抛异常 |

### 5.2 高（P2）

| 编号 | 位置 | 问题 | 修复建议 |
| :--- | :--- | :--- | :--- |
| U-7 | `LemonDiscoverHomeScreen.kt:277-312, 355-375` | `produceState` / `remember` 的 key 直接用整个大 `List` → 每次重组 O(n) 深比较，key 一变就全量重跑 `resolveSongList`（对上千首做匹配） | key 用 identity / 版本号 / 时间戳；重计算迁后台并缓存 |
| U-8 | `LocalLibraryScreen.kt:719-742` | **「定位正在播放歌曲」永远偏移一行**：实际 item 顺序为 0 Header、1 Bento、2 歌单区（无条件）、3 最近添加、4 本地文件夹、5 全部歌曲标题、6+ 歌曲，而代码 `headerCount = 3` 后只 +1/+1（应为 4） | 用 item key 计算或定义固定 offset 常量 |

### 5.3 中（P2）

| 编号 | 位置 | 问题 | 修复建议 |
| :--- | :--- | :--- | :--- |
| U-9 | `FullscreenPlayerSheet.kt:587-598, 1356-1367` | 进度 `Slider` 在 `onValueChange` 里逐像素调 `onSeekTo`，无本地拖动态、无 `onValueChangeFinished` → 拖动时每秒几十次 seek，拇指与真实进度互相拉扯回弹 | 增加 `draggingRatio` 本地状态，`onValueChangeFinished` 再提交 |
| U-10 | `FullscreenPlayerSheet.kt:810-827` | 拖动分隔条时**每帧** `lyricsPrefs.edit().putFloat(...).apply()` 落盘；同一 18dp 区域同时挂 `detectHorizontalDragGestures` 与 `clickable` → 手势互相干扰 | 拖动期间只改内存，`onDragEnd` 再落盘；拆分点击与拖动手势 |
| U-11 | `LocalLibraryScreen.kt:2306-2340` → `MainActivity.kt:2213-2216, 321` | 删除本地歌曲时在 `Dispatchers.IO` 回调 `onDeleteDownloadedSongs`，宿主直接写 Compose 快照状态 `songList`（**非主线程写状态**；同函数 2333 行自己已用 `withContext(Main)`，说明规范被漏用） | 回调包在 `withContext(Dispatchers.Main)` 中 |
| U-12 | `SettingsScreen.kt:1424-1426` vs `:155-157` | `stopPlaybackOnExit` **重复声明**：内层 `remember` 遮蔽外层，外层成死代码；两处默认值还相反（`true` vs `false`）→ 同一偏好项在两张卡片里读的是不同变量 | 删除内层重复声明，统一读 hoisted 状态 |
| U-13 | `LocalLibraryScreen.kt:761, 2209`；`SettingsScreen.kt:358` | 所有子视图共用一个 `rememberLazyListState` 且切换数据源时不重置 → 从滚动很深的歌单 A 切到歌单 B 会直接落在新列表中段；设置页各 Tab item 未加 key 同样保留旧滚动位置 | 每个子视图独立 state，或数据源变化时 `scrollToItem(0)` |
| U-14 | `SettingsScreen.kt:431-491, 659-831, 946-985` | `LazyColumn` 的**单个 item 内用 `forEach` 全量渲染列表**（servers / sourceScripts 含展开面板 / localMusicPaths）→ 失去虚拟化与回收 | 拆成 `items(list, key = { it.id })` |
| U-15 | `LemonDiscoverHomeScreen.kt:707, 2176`（`DownloadQualityChoiceDialog.kt:875-918`） | `NowPlayingWaveIndicator` 内 4 个 `infiniteRepeatable` 常驻动画（420/360/480/390ms），**不受 `isPlaying` 与前后台影响**；同项目液态卡动画都做了 `ON_PAUSE` / `ON_STOP` 门控，这里没有 → 退后台仍持续跑帧耗电 | 与 `isAppResumed` 联动，`isPlaying = false` 时不创建 infiniteTransition |
| U-16 | `LocalLibraryScreen.kt:414-553` | `produceState` 为每个在线歌单**顺序 await** 拉封面（N 个歌单串行 N 次请求）；key 为整个 `sortedPlaylists`，列表每次刷新都整段重跑 | 限制并发 / 只对缺封面的歌单请求 / 结果落缓存 |
| U-17 | `LemonDiscoverHomeScreen.kt:901-906` | 「私人漫游」点击瞬间在主线程做 `(resolvedNewSongs + allCachedSongs).distinctBy{it.id}.shuffled()`（整库拼接 + 去重 + 洗牌） | 放 `Dispatchers.Default` 或复用已缓存池 |

### 5.4 低（P3）

| 编号 | 位置 | 问题 | 修复建议 |
| :--- | :--- | :--- | :--- |
| U-18 | `FullscreenPlayerSheet.kt:485-494` | `Modifier` 顺序错误：`.clip().shadow()` → 阴影被裁掉，横屏封面阴影实际不生效（竖屏 1130-1138 写法正确） | 交换为 `.shadow(...).clip(...)` |
| U-19 | `LocalLibraryScreen.kt:2405-2412`；`SettingsScreen.kt:1978-1986, 2069-2084, 2198-2234` | 全部输入框无 IME 处理（`KeyboardOptions` / `imeAction` / `keyboardActions` / `imePadding` 命中 0 次）：键盘可能遮挡靠下输入框，回车不能提交，无自动聚焦 | 补 `keyboardOptions` / `keyboardActions` / `imePadding` |
| U-20 | `FullscreenPlayerSheet.kt:117-122` | `LaunchedEffect(song.id)` 内读取 `activeCastDevice` 但未作为 key；`castSongToDevice` 无 try/catch；播放中途接入投屏设备不会补推当前曲目 | 把 `activeCastDevice?.id` 加入 key，内部 try/catch |

---

## 六、TV 端专项审计（遥控焦点 / 大屏交互 / 功能真伪）

> 覆盖 TV `FullscreenPlayerSheet.kt`(3437)、`SettingsScreen.kt`(3009)、`LocalLibraryScreen.kt`(2793)、`AdaptiveAppScaffold.kt`(925)、`LemonDiscoverHomeScreen.kt`(1298)、`PlatformModeWizard.kt`(222)、`PlatformMode.kt`(24)，并交叉核对 `TvFocusModifiers.kt`、`LocalMusicHomeScreen.kt`、`LyricsScrollingView.kt`、`Media3Factory.kt`、`PlaybackService.kt`、`PlaybackWakeLockManager.kt`。

### 6.1 致命（已并入 P0-5 ~ P0-8）：T-1 / T-3 / T-4 / T-10

| 编号 | 位置 | 问题 | 对应 P0 |
| :--- | :--- | :--- | :--- |
| T-1 | `FullscreenPlayerSheet.kt:558 / 2389 / 2515 / 144-149 / 132` | `if (true)` 使竖屏分支（约 470 行）成为死代码，`HorizontalPager` 永不组合，且仍被 `LaunchedEffect` 驱动、形成 `isLyricsMode` 双源同步环；`isLandscape` 成死变量 | P0-5 |
| T-2 | `FullscreenPlayerSheet.kt:390-399`（+`371`、`1168-1171`、`2116-2119`） | 焦点守护协程与「菜单关闭回原按钮」抢焦点：`anyPopupExpanded` 变回 false 时该 effect 必然重启，120ms 后无条件把焦点拉到播放 / 暂停键 | P0-6（同源） |
| T-3 | `FullscreenPlayerSheet.kt:371` + `2289`；`LyricsScrollingView.kt:526/550/601-608` | 歌词调节浮层未纳入 `anyPopupExpanded` → 浮层可见但按不动 | P0-6 |
| T-4 | `SettingsScreen.kt:1548-1560` + `2862-2877`；`TvFocusModifiers.kt:97`；`MainActivity.kt:3562`；`PlatformModeWizard.kt:159` | 平台模式可被一次左右键静默切换并锁死设备 | P0-7 |
| T-10 | `FullscreenPlayerSheet.kt:169` + `2121-2128` + `2988-3044` | 「定时关闭」是空壳，全工程无消费者，状态为 `remember` | P0-8 |

### 6.2 高（P1）

| 编号 | 位置 | 问题 | 修复建议 |
| :--- | :--- | :--- | :--- |
| T-5 | `SettingsScreen.kt:341-351` + `216-293` | Tab「焦点即切换」叠加过重副作用（见 P1-24） | 焦点只做高亮，OK 才切换；副作用防抖 + 缓存 |
| T-6 | `SettingsScreen.kt:1871/2015/2148/2293/2536/2577`；`LocalLibraryScreen.kt:1729/2265/2368` | 9 处 Dialog 无初始焦点绑定（见 P1-21） | 每个 Dialog 首项加 FocusRequester；关闭还给触发按钮 |
| T-7 | `LocalLibraryScreen.kt:1676-1700`（+`416`、`140-145`）；`LemonDiscoverHomeScreen.kt:921-943`、`116-133` | 下钻视图整块替换导致焦点丢失（见 P1-22） | 详情页首项加 FocusRequester；返回按来源卡片恢复焦点 |
| T-17 | `LemonDiscoverHomeScreen.kt:706/746`；`LocalLibraryScreen.kt:1274/1409/1897/2020/2044/2107/2184` | 全工程 **0 处** `TvLazyRow/TvLazyColumn`（全部使用基础 `Lazy*`）→ 缺 `pivotOffscreenItemCount`（跨行上下移动时列位置不保持，光标跳到几何最近的卡片）与 `focusRestorer`（离开某行再回来只回到行首） | 引入 `androidx.tv:tv-material`，或手写 `focusGroup()` + `focusRestorer()` 按列记忆位置 |

### 6.3 中（P2）

| 编号 | 位置 | 问题 | 修复建议 |
| :--- | :--- | :--- | :--- |
| T-8 | `LemonDiscoverHomeScreen.kt:140-146`（+143、149） | `activeDownloadTasks()` 在**组合体内直接调用**：注释声称「provider 不会整页重组」，但下载流仍读进发现页自身的重组作用域 → 下载期间每约 300ms 整页重组；`structuralMatchKey` 每次重组都重算整张任务表 | 用 `derivedStateOf` / `snapshotFlow`；`structuralMatchKey` 放进 `remember`；行级读取下沉到行内 |
| T-9 | `LocalLibraryScreen.kt:189-198` | `remember` 内对**每首歌**执行 `java.io.File(s.localFilePath!!).exists()`（主线程 stat），曲库数千首时每次 `allSongs` 变化打出数千次磁盘调用；同处使用 `!!` | 用后台预计算的存在性 Set；去掉 `!!` |
| T-11 | `LocalLibraryScreen.kt:2198-2235`（+ `LocalMusicHomeScreen.kt:305`） | 下载管理模式下每行**两层可聚焦节点**：外层 `Surface(...tvFocusable(onClick=切换选中))` 包住内层 `SongListItemRow`（内部同样 `.tvFocusable`）→ 一行两个焦点位 / 两个光环 / 需按两次方向键移出 | 删掉外层 `tvFocusable`，选择态传入行内统一处理 |
| T-12 | `SettingsScreen.kt:890-931` | `Row(tvFocusable(onClick=togglePlatform))` 内嵌 `Switch(onCheckedChange={togglePlatform()})`，Switch 自带 `toggleable` 焦点节点 → 双重焦点（同文件 `SettingSwitchRow` 用 `onCheckedChange=null` 正确规避） | 与 `SettingSwitchRow` 保持一致 |
| T-13 | `SettingsScreen.kt:167` + `1503-1505`；`Media3Factory.kt:283-294/299-312` | 主线程磁盘 IO：`getCacheSizeBytes` 用 `walkTopDown()` 全目录遍历且在**首次组合**执行；`clearStreamCache` 在 onClick 主线程做 `cache.keys.toSet()` + `deleteRecursively()` | 两者移入 `LaunchedEffect + Dispatchers.IO`，加 loading 态 |
| T-14 | `FullscreenPlayerSheet.kt:1553-1563 / 629-668 / 1643` | 240dp 封面 `targetSize=640`（2.7 倍过采样）；NETEASE 主题同时常驻两张 `targetSize=1080` 位图（各约 4.4MB），650ms Crossfade 期间新旧各一份 → 峰值约 17MB；240dp 卡片 `shadow(24.dp)` 位于连续缩放图层内，阴影随缩放重绘 | 封面 targetSize 降至 320；用渐隐遮罩替代大阴影 / 避免 Crossfade 双大图 |
| T-15 | `FullscreenPlayerSheet.kt:369`（对比注释 94-111） | `progressMsProvider()` 在页面**根组合作用域**读取 → 进度 3Hz 刷新时整个 3400 行播放页仍整体重组，注释声称的「订阅范围收窄」只对调用方成立 | 进度读取下沉到歌词视图 / 进度条内部 |
| T-16 | `LemonDiscoverHomeScreen.kt:712/752/825/1037` | 列表 key 把**下标**编进去（`"new_song_${index}_${item.id}"`）→「换一批」/ 刷新后同一首歌 key 全变，行状态与焦点位置重建，光标回到首项 | key 只用稳定 id（对比 `LocalLibraryScreen.kt:1627/2193` 的正确写法） |
| T-18 | `AdaptiveAppScaffold.kt:177`；`FullscreenPlayerSheet.kt:1318`；`SettingsScreen.kt:305`；`PlatformModeWizard.kt:102` | 无 overscan 安全边距：22/14dp、20/12-16dp、16/8dp，远低于电视 5% 安全区（1080p ≈ 48dp/27dp）→ 边缘 Tab、返回键、角标被电视边框裁掉 | 统一 48dp/27dp 或至少 32/24dp，配合 `WindowInsets.safeDrawing` |
| T-19 | `PlatformModeWizard.kt:74/95-98`；`PlatformMode.kt:12-14` | 向导不可逃生：`BackHandler(enabled=true){}` 永久吞返回键（连退出应用都不行），`onKeyEvent` 对上下键**无条件** true（含 ACTION_UP）；`PlatformMode` 只有 TV/CAR 无逃生态 | 增加确认步骤 + 「长按返回恢复 TV」通道；只消费 ACTION_DOWN |
| T-20 | `FullscreenPlayerSheet.kt:434-528` | `onPreviewKeyEvent` 语义缺陷：① `repeatCount==0` 时 `return false`，长按切歌前光标已被移动一次；② ACTION_UP 一律 `return false`（442 行），破坏 DOWN/UP 成对消费；③ `KEYCODE_MENU` 被无条件消费（456-463），使播放页所有 `tvFocusable(onMenuKey=…)` 永远收不到菜单键 | 首帧即消费 DOWN 并起延时任务；UP 与 DOWN 成对；MENU 仅在无人声明时拦截 |

### 6.4 低（P3）

| 编号 | 位置 | 问题 | 修复建议 |
| :--- | :--- | :--- | :--- |
| T-21 | `LemonDiscoverHomeScreen.kt:1067/1070`；`LocalLibraryScreen.kt:2336/2339、191` | `songForDownloadChoice!!` 在 `onConfirm` lambda 中**重新读取可变状态**，与 `onDismiss` 置空存在竞态 → NPE | 把值捕获进局部变量后再用 |
| T-22 | `MainActivity.kt:3448-3451/3459-3480` | TV「长按快进 / 快退」能力缺失：`repeatCount > 0` 的媒体键被显式丢弃，且 `MEDIA_FAST_FORWARD/REWIND` 被映射成上一首 / 下一首 | 映射为连续 seek，复用播放页 5s/15s 步进逻辑 |

### 6.5 TV 端已核对无问题（避免误报）

- **`PlaybackWakeLockManager`**：`setReferenceCounted(false)`、`!lock.isHeld` 守卫的幂等 acquire/release、错误回调解绑、`releaseIfNotPlaying` 语义均正确，无 over-lock / under-lock 或漏释放；
- **熄屏 / 休眠**：`PlaybackService.kt:594-645` 动态注册 `ACTION_SCREEN_OFF/ON/SHUTDOWN`，熄屏补前台与唤醒锁、熄火同步落盘，处理正确；
- **媒体键成对消费**：`MainActivity.kt:3569-3590` 用 `pendingDownKeyCodes` 保证 DOWN/UP 成对吞掉（手机端缺此修复，见 P1-17）；
- **协议 / 扫描耗时调用**：`fetchSourceList`、`getServerSettings`、`getServerPaths`、`LocalMediaScanner.scanCustomDirectory` 内部均为 `withContext(Dispatchers.IO)`；
- **缩放动画抖动**：`tvFocusable` 与黑胶旋转都在 `graphicsLayer` 内延迟读取动画值，未在组合期读动画。

---

## 七、MainActivity 专项审计（双端各约 3.4k / 3.7k 行）

> 两端是**各自独立 fork**（`app/.../core/` 与 `LMPlayerTV/.../core/` 各持一份），本轮大量问题由「TV 已修、手机未修」的差异直接确认。

### 7.1 严重（已并入 P0-1、P1-16 ~ P1-18、P0-7）

| 编号 | 平台 | 位置 | 问题 | 对应条目 |
| :--- | :--- | :--- | :--- | :--- |
| M-1 | 手机 | `MainActivity.kt:3290-3314`；`AndroidManifest.xml:25-32` | Android 13+ 完全没有本地音频读取权限（Activity 侧二次确认同一根因） | P0-1 |
| M-2 | 双端（手机重） | 手机 `MainActivity.kt:3094, 3257, 3263, 3321`；`PlaybackQueueManager.kt:327-356, 650-655` | 主线程同步落盘，且每次点播 / 切歌都走 `commitSync = true` | P1-16 |
| M-3 | 双端（手机） | 手机 `MainActivity.kt:3279-3284`；手机 `PlaybackService.kt:379-425` | 媒体键只吞 DOWN、放行 UP → 一次按键双触发 | P1-17 |
| M-4 | 手机 | `MainActivity.kt:1833-1855`；`AndroidManifest.xml:44` | 边缘右滑返回手势用 `PointerEventPass.Initial` 且不检查 `isConsumed` | P1-18 |
| M-5 | TV | `MainActivity.kt:3562`、`3374-3386` | 车机模式在 Activity 层吞掉全部方向 / 确定 / 菜单键且不做处理 | P0-7 |

### 7.2 高（P1）

| 编号 | 平台 | 位置 | 问题 | 修复建议 |
| :--- | :--- | :--- | :--- | :--- |
| M-6 | TV | `MainActivity.kt:102, 1952, 2035-2038`；TV `Media3Factory.kt:173-189` | 换绑后 `Player.Listener` 留在已释放的播放器上（见 P1-19） | 播放器实例放进 `mutableStateOf` 或用 swap 版本号作 key |
| M-7 | 双端 | 手机 `MainActivity.kt:121-123`；TV `MainActivity.kt:124-126` | 权限申请结果被完全丢弃（见 P1-20） | 判断 `granted`，被拒时引导到系统设置 |
| M-8 | 手机 | `MainActivity.kt:2959-2982` | 进度轮询无 `repeatOnLifecycle`，退后台仍轮询；暂停后进度不刷新 | 见 P1-23 |
| M-9 | 手机 | `MainActivity.kt:334` | 组合体内新建 Room 冷流 → 每次重组重建失效观察者 | 见 P1-23 |
| M-10 | 手机 | `MainActivity.kt:317, 1974-1975, 2054-2055, 2173-2174, 2424, 2900, 3000` | `activeDownloadTasks` 把每约 300ms 变化的 List 值直接传进各页面（还额外传 `.size`）→ 整页随下载进度高频重组 | 照抄 TV：传 `() -> List<DownloadTask>` provider + 结构流计数 |

### 7.3 中低（P2 / P3）

| 编号 | 平台 | 位置 | 问题 | 修复建议 |
| :--- | :--- | :--- | :--- | :--- |
| M-11 | 双端 | 手机 `:317-334, 390-394`；TV `:352-356, 394, 411, 493-497` | 全部 `collectAsState` 未使用生命周期感知版本 → Activity STOPPED 后仍持续收集并写 State | 改用 `collectAsStateWithLifecycle()` |
| M-12 | TV | `:393` | 组合期副作用 + 主线程读盘：`remember { PlaybackQueueManager.primeRecentPlayedSongs(...) }` 内部读 SharedPreferences 并解析 JSON | 移入 `LaunchedEffect + Dispatchers.IO`，或由 Application 预热 |
| M-13 | 双端 | 手机 `:1735, 1744-1751`；TV `:1952, 1961-1968` | `onPlayerError`（主线程）里对**整个曲库逐条 `File(...).exists()`**；且 DisposableEffect key 未含 `completedDownloadedSongs`，闭包可能用过期列表 | 匹配逻辑移到 IO 并缓存；依赖纳入 key 或用 `rememberUpdatedState` |
| M-14 | 双端 | 手机 `:266-267, 397, 423-424`；TV `:286-288, 500, 517` | 导航与播放页状态**全部只用 `remember`**（TV 端 0 处 `rememberSaveable`）：`currentScreen`、返回栈、`isFullPlayerVisible`、`hasAutoPlayedOnStartup` | 关键状态改 `rememberSaveable`（枚举配 Saver）或 SavedStateHandle |
| M-15 | 手机 | `:3125, 3159-3163` | 媒体键防抖使用**全局单一时间戳**，所有键共用 250ms 窗口 → 不同键互相压制 | 改为一键一记 `HashMap<Int, Long>`（TV 已修） |
| M-16 | 手机 | `:993, 410, 2666` | 自动播放 / 断点恢复也计入「用户手动点播」计数，而定位悬浮按钮要求 `>= 2` | 只在用户点击回调中计数（TV 已删除该计数） |
| M-17 | TV | `:2270, 3007-3052, 2095, 2157, 2214, 2218, 2306, 3031` | **搜索覆盖层是死代码**：`if (!isSearchDialogOpen)` 包裹整棵页面树，但 TV 端从未把该状态置 true（只有置 false）；一旦启用，`AnimatedContent` 会被整体移除，页面内 `remember`（滚动位置 / Tab / 焦点记忆）全部丢失。另 `LibrarySearchDialog` 在 TV 被实例化两次 | 删掉死分支，或改为覆盖层并保留底层内容 |
| M-18 | 双端 | 手机 `:1735-1822`；TV `:1952-2039` | `DisposableEffect(exoPlayer, currentSong, songList, autoFallbackToLocal)` 用**高频变化的 `songList` 作 key** → 曲库每次变更都注销并重注册 `Player.Listener` | key 只留 `exoPlayer`（可观察化）与设置项，其余用 `rememberUpdatedState` |
| M-19 | 双端 | 手机 `:1414-1440, 1448-1539`；TV `:1545-1570, 1579-1669` | 收藏 / 加入歌单**先改 UI**，DB 与服务器写入在异步协程中，**失败只打日志、不回滚** | 失败时回滚，或「等待写入结果再更新 UI」 |
| M-20 | 双端 | 手机 `:1870`；TV `:373` | `LaunchedEffect` 用**布尔表达式**作 key，而 `serversList` 每次同步都是新 List 实例 → 每次同步都重复拉取服务器收藏 | key 用 `currentScreen` 本体与服务器 `id` / `tokenOrApiKey` |
| M-21 | 手机 | `:225-232` | 组合期执行系统查询：`remember` 内调 `ensureInitialized` / `hasOverlayPermission`（binder）并做权限判断 | 挪到 `LaunchedEffect` 或 Application |

**崩溃类排除结论**：两个 MainActivity 中未发现真实崩溃点。`!!` 仅 3 处（手机 3039/3044、TV 3235），均有同一行的非空判断前置；无 `list[0]` / `lateinit` 未初始化访问；无 `as` 强制转型（均为 `as?`）；`BroadcastReceiver` 已配对 `unregisterReceiver`；无 DataStore / SharedPreferences 类型错配。TV `MainActivity.kt:3140-3145` 的 `currentQueueList[nextIndex]` 有 `songList.isNotEmpty()` 前置守卫。

---

## 八、TV 播放核心专项审计（`PlaybackService` / `PlaybackQueueManager` / `Media3Factory` / `PlaybackRouter` / `AppUpdateManager`）

> 本轮为 5 个 TV 核心文件全文通读 + 与手机端逐项对照 + 全部调用点核对，共 23 条有代码证据的问题。

### 8.1 严重：无限「换源 / 重试」循环（TV）

```kotlin
// TV PlaybackQueueManager.kt:706-708   onPlayerError 只处理解码错误，网络类错误既不重试也不上报
// TV MainActivity.kt:1952-2032         真正的重试逻辑：本地回退 → 音质降级 ×3 → 服务器回退
// 三个分支互相把对方当「新歌」（本地版 / 服务器版 id 不同），
// 而 lastStreamRetryQualityIdx 只在 song.id 变化时清零
```

**问题**：本地损坏文件 + 服务器副本同样播不出时形成闭环 —— 本地 → 降级 3 次 → 服务器 → 本地 → … 每轮 5 次 `playSong` + 网络解析 + Toast，**永不停止**。TV 端缺少手机端的 12 秒 + songId 重试守卫（手机 `PlaybackQueueManager.kt:603-618`）。

**修复**：per-song 重试计数器 + 最小间隔；把同一首的本地 / 在线 / 服务器变体视为**同一重试单元**；达上限即停并提示用户。

### 8.2 中：TV 端其余 10 条（建议按 P1 处理）

| 编号 | 位置 | 问题 | 修复建议 |
| :--- | :--- | :--- | :--- |
| TV-C2 | `PlaybackService.kt:391` + `408-410` | `startImmediateForeground()` 位于 `onCreate` 的 try **末尾**：前面（ExoPlayer / MediaSession / 通知构建）任一异常被 `catch(Throwable)` 吞掉后，服务从不 `startForeground` → 约 5 秒后 `ForegroundServiceDidNotStartInTimeException` 崩溃（与 390 行注释「彻底杜绝 5 秒启动超时闪退」矛盾） | 提到 try 第一行，或在 catch 中兜底 |
| TV-C3 | `Media3Factory.kt:76-83/172-197/205/225-227` + `PlaybackQueueManager.kt:724-737` | `forceSoftwareDecoding` 置位后**整个进程不再复位**，且判定集合含 `AUDIO_TRACK_INIT_FAILED` / `WRITE_FAILED` / `DECODING_FAILED` 等与硬解能力无关的错误 → 一次音频设备或单个损坏文件错误，就让此后**所有歌曲（含 MP3/AAC）永久软解**（性能与耗电双输） | 只认解码器能力类错误码；连续 N 首成功后复位 |
| TV-C4 | `PlaybackService.kt:141-144/269-272/349-355` + `PlaybackQueueManager.kt:951-960` | **暂停判定用 `Player.isPlaying`**（= STATE_READY + playWhenReady + 无抑制）：切歌缓冲期 `isPlaying = false` → 遥控器 / 媒体键 / 界面的「暂停」全是**空操作** | 改判 `playWhenReady` |
| TV-C5 | `PlaybackQueueManager.kt:696-704` + `913-918` | **STATE_ENDED 与 repeat 语义错位**：`repeat = off` 时列表末尾仍 `list.first()` 回绕（队列永不完结），单曲队列无论如何都无限循环 → **盒子永不停播** | `repeat = off` 且已到末尾应停在 ENDED |
| TV-C6 | `PlaybackQueueManager.kt:638-651/839/851` | 周期落盘把「**新歌名**」与「**旧歌位置**」拼在一起：`playSong` 先改 `_currentSongFlow`，异步解析（网络）期间播放器还在放旧歌，5 秒任务把旧歌位置写到新歌名下 | 位置写入前校验 `player.currentMediaItem.mediaId` 与当前曲目一致 |
| TV-C7 | `Media3Factory.kt:259` | `setHandleAudioBecomingNoisy(true)`：手机端 `Media3Factory.kt:135` 专门改成 `false` 并注明「避免车机蓝牙 / 通道切换 `ACTION_AUDIO_BECOMING_NOISY` 误停后台播放」，TV（含车机形态）反向退回 `true`，且看门狗因 `playWhenReady = false` 不会救场 | 与手机端对齐为 `false` |
| TV-C8 | `PlaybackQueueManager.kt:574-602` | `updateMetadata` 丢掉手机端有的「最近播放」元数据合并（手机 `486-492`）→ 下载完成后最近播放条目仍无 `localFilePath`，离线点开必然解析失败 | 补齐最近播放的元数据合并 |
| TV-C9 | `Media3Factory.kt:299-311` + `96-108` | `clearStreamCache` 在 `SimpleCache` 实例（进程级单例，全工程无一处 release）存活时对缓存目录 `deleteRecursively()`，把它的 SQLite 索引一起删掉 → 索引与磁盘不一致、缓存命中失效 / 随时抛 IO 异常；SimpleCache 与 DB 句柄永久泄漏 | 清理前 release 并置 null |
| TV-C10 | `AppUpdateManager.kt:229-288` + `115` + `AndroidManifest.xml:54` | **更新包零完整性校验**：不看 `apkSizeBytes`、无 hash、无签名比对；允许 `http://` 自定义接口且 manifest 开了 `usesCleartextTraffic` → **MITM 可下发任意 APK** | 强制 https + sha256 / 资产 size 校验 + 安装前校验签名 |
| TV-C11 | `AppUpdateManager.kt:235-238/293-303` + `MainActivity.kt:3260` | `installApk` 只弹 Toast 不安装，APK 落在 `getExternalFilesDir(DIRECTORY_DOWNLOADS)`（Android 11+ 文件管理器基本不可达），文案却写「正在调起安装 / 请通过文件管理器安装」→ **更新流程实际不可用**，文件永不清理 | 走 FileProvider 安装链路（手机端实现正确） |

### 8.3 低：TV 端其余 12 条（P2 / P3）

| 编号 | 位置 | 问题 | 修复建议 |
| :--- | :--- | :--- | :--- |
| TV-C12 | `PlaybackService.kt:170-185` | `startService` + `stopService` 在同一 try：Android 8+ 后台 `startService` 抛 `IllegalStateException` 被 182 行捕获后，`stopService` **永不执行** → 服务 / 通知 / 唤醒锁清理全部不生效 | 拆成两个独立 try |
| TV-C13 | `PlaybackQueueManager.kt:909-911/934-936` | shuffle 下 `candidates.random()`：队列存在**重复 id** 且 `size > 1` 时 `filter` 得空列表 → `NoSuchElementException` 崩溃（`setQueue` / `updatePlaylist` 均不去重） | `ifEmpty { list }` + 入口去重（与 P0-2 同源，TV 额外多一条重复 id 触发路径） |
| TV-C14 | `PlaybackService.kt:56-64/739-746` | 缺手机端的 `isExplicitStopping` 守卫（手机 682/697）：播放器是进程单例，彻底关闭后残留回调仍 `notify` 同一 `NOTIFICATION_ID` → 媒体通知可被**重新贴回** | 移植手机端守卫 |
| TV-C15 | `PlaybackWakeLockManager.kt:126-143` + TV manifest 缺 `ACCESS_WIFI_STATE`（手机 manifest:8 有） | WifiLock 永不生效，`setWakeMode(C.WAKE_MODE_NETWORK)` 附带的 WifiLock 同样被静默降级 | 补 `ACCESS_WIFI_STATE`（同 P2-10） |
| TV-C16 | `PlaybackQueueManager.kt:427-431` vs `PlaybackService.kt:625-636` | 注释要求 `ACTION_SHUTDOWN` **同步落盘**（onReceive 返回前写完），但主线程 `commitSync` 一律改投单线程 `ioExecutor`，还会排在 `updateMetadata`（120 首 `File.exists`）之后 → 关机拔电即丢（5 秒周期落盘把损失限制在约 5 秒内） | 关机路径走独立同步写或专用优先级队列 |
| TV-C17 | `Media3Factory.kt:172-197` + `MainActivity.kt:184/3196` | 换绑不恢复 `playbackParameters` → 软解重建后用户设置的**倍速静默回落到 1.0** | 换绑后回写倍速 |
| TV-C18 | `PlaybackService.kt:701-723` | 通知既无 MediaStyle 也未绑定 MediaSession（全工程 0 处），无任何 action，与注释「保证通知栏控制不失效」不符 | 改用 `DefaultMediaNotificationProvider` |
| TV-C19 | `AppUpdateManager.kt:237-238/246/260-280` | 无断点续传（每次先 delete 已下载文件）、无 `Content-Range`；短包（chunked / 被服务端提前关闭）照样 `Result.success`；`totalLength <= 0` 时进度恒 0、收尾上报把 `downloadedBytes` 传成 `totalLength`（-1 被 coerce 成 0） | 补续传与长度校验 |
| TV-C20 | `AppUpdateManager.kt:171-175` | 自定义接口分支 `hasUpdate` **未校验 `downloadUrl` 非空**（GitHub 分支 154 行校验了）→ 弹更新框但点下载无反应 | 与 GitHub 分支一致校验 |
| TV-C21 | `PlaybackQueueManager.kt:156-170` | `cachedQueueJson` / `cachedQueueKey` 非 volatile、非原子成对写；main 线程与 ioExecutor 线程并发 → 可能返回**另一份队列的 JSON** 落到 prefs（重启后队列错乱） | `@Volatile` + 同步块，或缓存只允许 `ioExecutor` 访问 |
| TV-C22 | `PlaybackService.kt:81-110` + `PlaybackQueueManager.kt:884-888` | 队列只存在内存（`_playlistFlow`），播放器 timeline 永远只有 1 条且未覆写 `hasNextMediaItem` / `hasPreviousMediaItem`（恒 false），却硬加了 NEXT/PREVIOUS 命令 → 蓝牙 AVRCP / 电视系统媒体卡片呈现「没有上一首 / 下一首」 | 覆写一致性方法，或改用 `setMediaItems(队列)` 让 Media3 自己管 ENDED / 切歌 |
| TV-C23 | `PlaybackService.kt:488-500` | `onTaskRemoved` 在 `playbackState == STATE_IDLE` 时直接 `stopForeground + stopSelf + 放锁`；若用户点歌后立刻回桌面而异步解析尚未落地（弱网），随后 `setMediaItem/play` 时前台服务已不存在 → 退化为**无通知后台播放** | 结合「是否有待解析播放意图」判断 |

### 8.4 TV 播放核心已核对无问题

- 所有 `getSharedExoPlayer` 调用点都在主线程（MainActivity onCreate / dispatchKeyEvent、PlaybackService 回调、PlaybackQueueManager 的 Main 作用域），未发现 ExoPlayer 跨线程访问；
- 4 处 `setMediaItem` 后都紧跟 `prepare()`；
- 两处 `!!`（`PlaybackQueueManager.kt:699/957`）因全仓无 `_currentSongFlow.value = null` 写入点而不可达。

### 8.5 双向修复漂移（本轮最值得注意的结构性问题）

| 方向 | 内容 |
| :--- | :--- |
| **TV 已修、手机未修** | 解析失败不再返回假 `MediaItem`（`PlaybackRouter.kt:264-269` + Toast）；下载流 `use {}` 修 FD 泄漏；进度回调节流 + 收尾补报；Activity 侧监听换绑；按键广播不再双重执行；播放足迹单一写入源与串行 IO executor |
| **手机已修、TV 反向缺失** | 后台断流容灾重试（手机 `PlaybackQueueManager.kt:580-619`）；`isExplicitStopping`（手机 `PlaybackService.kt:682/697`）；`setHandleAudioBecomingNoisy(false)`；`ACCESS_WIFI_STATE`；FileProvider 安装链路；`updateMetadata` 的最近播放合并；`playNext/Previous` 的 400ms 防抖（手机 687-693） |

> **结论**：两端各有约 7 处对方已有的修复缺失。若继续各自演进，同类问题会持续以「这边好了、那边坏了」的形式反复出现 —— 这是本报告第十章工程加固建议的第一优先级依据。

---

## 九、一般级缺陷汇总（P2：逻辑瑕疵 / 体验与性能损失）

| 编号 | 平台 | 位置 | 问题 | 修复建议 |
| :--- | :--- | :--- | :--- | :--- |
| P2-1 | 双端 | `Media3Factory.kt` `clearStreamCache` | 在 `SimpleCache` 仍被使用时 `deleteRecursively()` 缓存目录（连 SQLite 索引一起删），后续写入抛 `IOException` 被静默 catch → 「清理缓存后一段时间内无法边听边存」 | 清理前 `simpleCacheInstance?.release()` 并置 null 再删目录重建 |
| P2-2 | 双端 | `PlaybackService.kt:145-191` | 音频焦点恢复时无条件 `player.volume = 1.0f`，丢弃压音前的用户音量 | 压音前保存 `previousVolume`，恢复时还原 |
| P2-3 | 双端 | `PlaybackService.kt:591-618` | `ACTION_MEDIA_COMMAND` 分支先执行播控命令再建立前台状态；若服务此前已 `stopSelf` 而进程仍活，存在 5 秒前台服务超时风险 | 进入分支后立刻建立前台状态，再做播控 |
| P2-4 | 双端 | `PlaybackQueueManager.kt:305-355` | `flushRecentPlayed` / `savePlaybackState(commitSync = true)` 在主线程 `SharedPreferences.commit()`（见 P1-16） | 改投串行 IO |
| P2-5 | 双端 | `LMApplication.kt:66` | `ZdsDatabase.getInstance(this)` 在冷启动主线程触发 `Room.databaseBuilder().build()` 与首次 open 的磁盘 IO，与同文件 L57「0ms 阻塞冷启动主线程」目标矛盾 | 挪到 L58 的 IO 协程内 |
| P2-6 | 双端 | `PlaybackQueueManager.kt:786-870` | `toggleFavorite` 在 IO 协程内做 Room 写 + 多表更新 + 远端同步 + 可能提交服务器下载任务，无并发去重 | 按 `song.id` 加 `Mutex` 或 UI 层去抖 |
| P2-7 | 双端 | `DynamicIslandManager.kt:190-205` | `ensureInitialized` 的 `isInitialized` 为普通 Boolean 且无同步，四个入口都会调用 → 重复初始化与 `startBackgroundLyricsEngine` 重复启动竞态 | `@Volatile` + `synchronized` 或 `AtomicBoolean.compareAndSet` |
| P2-8 | 双端 | `AudioSharingManager.kt:129, 1269-1280` | 投屏 HTTP 服务 `httpServerSocket` 创建后**全工程无 `close()`**；`scope` / `mainScope` 静态作用域永不取消 | `stopActiveCast` 与 `onTaskRemoved` 中关闭 Socket，并提供 `release()` |
| P2-9 | 手机 | `MainActivity.kt:3290-3314` | 通知权限被拒后无任何降级提示；Android 13+ 拒绝后前台服务通知静默不显示，用户以为「没有后台播放」 | 设置页展示权限状态并提供跳转系统设置入口 |
| P2-10 | TV | TV `AndroidManifest.xml` | 未声明 `ACCESS_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE`，导致 WifiLock 永不生效、局域网投屏多播发现静默降级 | 补齐权限，或移除 TV 端投屏 / 多播死代码 |
| P2-11 | TV | `PlatformModeWizard.kt:74`、`95-98` | 向导吞掉返回键且 `onKeyEvent` 无条件消费上下键（含 ACTION_UP），`PlatformMode` 无逃生态（见 P0-7） | 增加确认步骤 + 长按返回逃生通道；只消费 ACTION_DOWN |
| P2-12 | TV | `PlatformModeWizard.kt:79-85` | `LaunchedEffect` 中固定 `delay(80)` 后 `requestFocus()`，低端盒子焦点节点可能尚未 attach，异常被静默吞掉 → 向导无焦点，需先按方向键才「唤醒」光标 | 用 `withFrameNanos` 或重试 3 次；失败回退到首个 `tvFocusable` |
| P2-13 | 双端 | `LocalMediaScanner.kt:57-103` | `retriever` 在长循环中复用且不重置，单文件异常后 native 状态可能残留 | 每个文件前 `setDataSource`，异常后 `release()` 并重建 |
| P2-14 | 双端 | `LocalMediaScanner.kt:39` | 仅扫描 `EXTERNAL_CONTENT_URI` 且硬编码 5 个厂商目录；未用 `MediaStore.getExternalVolumeNames()`，Android 10+ 分区存储下 `DATA` 列可能为空 | 遍历外部卷 + `content://` 直读兜底 |
| P2-15 | 双端 | `DownloadEngine.kt:415, 626-649` | 失败时只删**长度为 0** 的 `.download` 残片；非空残片被保留，但 `isDestFileValid` 又要求 `!tempFile.exists()` → 该曲目**一次失败后永远无法再下载** | 保留残片用于续传，并把 `isDestFileValid` 改为「残片可续传」语义 |
| P2-16 | 双端 | `AppUpdateManager.kt:33-34` | `CURRENT_VERSION_NAME = "1.7.14"` / `CODE = 49` 硬编码，与 `build.gradle.kts` 的 1.7.19 / 54 **已漂移 5 个版本** | 删除硬编码，读取失败时视为「未知版本」且不提示更新 |
| P2-17 | 双端 | `AppUpdateManager.kt:236-271` | 手机端下载 APK 时流未用 `use {}`，异常路径泄漏 FD；且每 8KB 就 `withContext(Dispatchers.Main)` 回调一次进度 | 改 `use`；进度按 200ms 节流（TV 已修） |
| P2-18 | 双端 | `NetworkClientFactory.kt:102-115` | 拦截器对**所有 host** 的 `/api/` 路径自动附加令牌（见 P1-11） | 仅在 host 命中已配置服务器时注入 |
| P2-19 | 双端 | 双端 manifest + `Entities.kt:73` | `allowBackup="true"` 且无 `dataExtractionRules`；`servers` 表以**明文**存储 `tokenOrApiKey` / `saltOrSecret`，会被云备份 / ADB 备份带走 | `allowBackup="false"` 或排除数据库；凭据改用加密存储 |
| P2-20 | 双端 | 双端 `AndroidManifest.xml:43` | `usesCleartextTraffic="true"` 且无 `networkSecurityConfig`，第三方音源直链走明文 HTTP，可被中间人篡改 | 增加 `network_security_config.xml`，仅对已知域名放行明文 |
| P2-21 | 双端 | `MusicDao.kt:37-51` | `getSongsPaged` 用 `LIMIT/OFFSET` 深分页退化为 O(n²)；无稳定排序键，分页期间并发写入会漏读 / 重读 | 改主键游标分页 `WHERE id > :lastId ORDER BY id LIMIT :n` |

---

## 十、建议级 / 加固项（P3）

| 编号 | 平台 | 位置 | 说明 | 修复建议 |
| :--- | :--- | :--- | :--- | :--- |
| P3-1 | 手机 | `app/build.gradle.kts:46` | **release 使用 debug 签名**（`signingConfig = signingConfigs.getByName("debug")`）：无法上架、无法与 TV 端共用升级链路，且 debug keystore 口令公开 | 对齐 TV 端的本地密钥 / 环境变量方案 |
| P3-2 | 手机 | 与 TV 端对比 | 手机端**缺少 `CrashLogger` + `CrashReportDialog`**（TV 有 `core/util/CrashLogger.kt`）。无 adb 场景下崩溃只能靠用户口述 | 直接把 TV 实现移植过来 |
| P3-3 | 手机 | 与 TV 端对比 | 手机端**缺少硬解失败降级**（TV 有 `rebuildWithSoftwareDecoding` + 进度停滞检测 + 播放器换绑）。部分车机 SoC 硬解异常时表现为「进度条在走但完全无声」且无法自愈 | 移植 TV 的软解降级机制，并同时修 TV 的 TV-C3（永久软解） |
| P3-4 | 双端 | `LMApplication.kt:60` vs `Media3Factory.kt:102` | 偏好键不一致：Application 读 `stream_cache_enabled`，Factory 读 `stream_cache_enabled_v2`；该布尔值可被两处竞争改写 | 统一键名，删除 v1 兼容读取 |
| P3-5 | 双端 | `SettingsScreen.kt:1350, 1368` | 设置项切换使用 `prefs.edit().commit()`（主线程同步写盘） | 改 `apply()` |
| P3-6 | 双端 | `AppUpdateManager.kt:159-169` | GitHub 通道下 `latestVersionCode = currentVersionCode + 1` 是**伪造值**，仅用于刷新 UI | 从 tag 解析真实语义版本，或把字段改为可空 |
| P3-7 | 双端 | `PlaybackService.kt:77-83, 649-656` | `PHONE_SYSTEM_ISLAND_PACKAGES` 硬编码厂商包名，其中 `com.huawei.systemserver` 不是有效的 systemui 包名，`onGetSession` 白名单在华为设备上可能不生效 | 用 `PackageManager` 校验包名存在性，或改为「除本应用外一律允许」 |
| P3-8 | 双端 | `AudioSharingManager.kt:876, 1185, 1212, 1235` | `client.newCall(req).execute().close()` 只关闭响应体、未消费 body，大量调用会占用连接池 | 改为 `.execute().use { it.body?.string() }` |
| P3-9 | 双端 | `LyricsManager.kt:1195, 889, 1132` | 存在 `lyricRes.getOrNull()!!` 等断言；当前前置条件已保证非空，但属脆弱写法 | 换为局部变量 + 早返回 |
| P3-10 | TV | TV `AndroidManifest.xml:92-97` | 开机自启接收器 `exported="true"` 且无权限保护，任何应用可发送 `BOOT_COMPLETED` 触发（内部有开关兜底，实际影响小） | 增加 `android:permission` 或记录为已知风险 |
| P3-11 | 双端 | `LocalLibraryScreen.kt:279-289` 等 | 大量 `s.localFilePath!!` 在 `isNullOrBlank()` 判断的 lambda 内使用：当前安全，但 lambda 捕获使智能转换失效，重构时极易变成 NPE | 改为 `val p = s.localFilePath ?: return@filter false` 的局部变量写法 |

---

## 十一、双端一致性差异清单（同源代码的修复漂移）

这两端代码高度同源，但修复没有同步，形成「一边修好、一边带病」的格局，是持续产生 BUG 的结构性原因：

| 能力 | 手机端 | TV 端 | 风险 |
| :--- | :--- | :--- | :--- |
| `READ_MEDIA_AUDIO` 声明 + 运行时申请 | ❌ 缺失 | ✅ 完整 | 手机端 Android 13+ 扫不到本地音乐（P0-1） |
| 崩溃日志与自诊断弹窗 | ❌ 无 | ✅ `CrashLogger` + `CrashReportDialog` | 手机端线上崩溃无法定位（P3-2） |
| 硬解失败软解降级 | ❌ 无 | ⚠️ 有但会永久误判（TV-C3） | 手机端无声故障不自愈；TV 端一次误判永久软解 |
| 播放唤醒锁管理层 | ❌ 无（仅 ExoPlayer 内建） | ⚠️ 有但 WiFi 锁不生效（TV-C15） | 两端均受 P1-1 影响 |
| Release 签名方案 | ❌ debug 签名 | ✅ 本地密钥 / 环境变量 | 手机端无法正常分发升级（P3-1） |
| 权限请求分支 | 只到 API 32 | API ≤32 与 ≥33 双分支 | P0-1 |
| 主线程 `commitSync` 落盘 | ❌ 主线程直接 commit | ✅ 改投串行 IO | 手机端切歌 ANR；TV 端引入「关机同步落盘失效」（TV-C16） |
| 媒体键 DOWN/UP 成对消费 | ❌ 只吞 DOWN | ✅ `pendingDownKeyCodes` | 手机端一次按键双触发（P1-17） |
| 进度轮询生命周期 | ❌ 无 `repeatOnLifecycle` | ✅ 有 | 手机端退后台仍轮询（P1-23） |
| Room Flow 复用 | ❌ 组合期新建 Flow | ✅ `remember` + `distinctUntilChanged` | 手机端失效观察者反复重建（P1-23） |
| 后台断流容灾重试 | ✅ 12s + songId 守卫 | ❌ 无限循环（8.1） | TV 端可能永不停止换源 |
| `isExplicitStopping` 守卫 | ✅ 有 | ❌ 无 | TV 端退出后通知被贴回（TV-C14） |
| `setHandleAudioBecomingNoisy` | ✅ `false` | ❌ `true` | TV / 车机通道切换误停播（TV-C7） |
| `ACCESS_WIFI_STATE` | ✅ 有 | ❌ 无 | TV WifiLock 静默失效（TV-C15） |
| APK 安装链路（FileProvider） | ✅ 完整 | ❌ 只弹 Toast | TV 更新流程实际不可用（TV-C11） |
| `updateMetadata` 最近播放合并 | ✅ 有 | ❌ 无 | TV 下载完成后最近播放仍无本地路径（TV-C8） |
| `playNext/Previous` 400ms 防抖 | ✅ 有 | ❌ 无 | TV 遥控连按易重复切歌 |

**结构性建议**：把 `core/`（model、database、network 与 media 中的纯逻辑部分）抽成 `:core` 共享模块，仅在 `app/` 与 `LMPlayerTV/` 保留 UI 与平台差异；或至少建立「双端同步检查清单」，任何 `core/` 改动必须两端同时落地。

---

## 十二、优化意见表（按优先级排序，可直接作为任务清单）

| 优先级 | 编号 | 平台 | 问题摘要 | 业务影响 | 预估工时 | 建议动作 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **P0** | P0-1 / M-1 | 手机 | 缺 `READ_MEDIA_AUDIO` 权限声明与申请 | Android 13+ 本地扫描 100% 失效 | 0.5h | Manifest 加权限 + 权限分支对齐 TV 端 |
| **P0** | P0-2 / TV-C13 | 双端 | 随机播放 + 单曲歌单（或重复 id 队列）→ `random()` 空列表崩溃 | 必崩（主线程未捕获异常） | 0.5h | `filter{}.ifEmpty { list }.randomOrNull() ?: return` + 队列入口去重 |
| **P0** | P0-3 | 双端 | `fallbackToDestructiveMigration` + 无迁移无 schema | **升级清空全部用户数据** | 2–4h | 开启 `exportSchema`、写 `Migration`，至少改 `OnDowngrade` |
| **P0** | P0-4 / TV-C2 | 双端 | 前台服务启动异常被静默吞掉 / `startForeground` 位于可能抛异常的代码之后 | 「点播放没反应」类不可诊断故障 | 2h | 提前 `startForeground` + 降级通知 + 失败可观测 |
| **P0** | P0-5 | TV | `if (true)` 使竖屏分支（约 470 行）成为死代码，pager 永不组合 | 竖屏 / 部分盒子下歌词模式行为不可预期 | 1–2h | 恢复 `if (isLandscape)`；歌词模式单一数据源 |
| **P0** | P0-6 / T-2 / T-3 | TV | 焦点守护协程抢走浮层焦点 → 浮层「看得见按不动」 | TV 核心交互（歌词调节 / 队列 / 下载菜单）失效 | 2h | 统一 `anyOverlayExpanded`，浮层打开期间禁用守护协程 |
| **P0** | P0-7 / T-4 / M-5 / T-19 | TV | 平台模式可被一次左右键静默切到 CAR，且无逃生通道 → 无触屏设备锁死 | 用户设备变砖式体验 | 2–3h | 该行禁用左右键改值 + 二次确认 + 长按返回逃生 |
| **P0** | P0-8 / T-10 | TV | 「定时关闭」是空壳功能 | 用户设置后永不生效（假功能） | 2h | 定时器落到播放层 + 状态持久化 + 倒计时 |
| **P0** | 8.1 | TV | 换源 / 重试**无限循环**（无节流无上限） | 极耗流量与电量、Toast 刷屏、发热 | 3h | per-song 重试计数器 + 最小间隔；本地 / 在线 / 服务器变体归为同一重试单元 |
| **P1** | P1-1 | 双端 | ExoPlayer 单例永不 release，WakeLock / WifiLock 悬挂 | 息屏待机耗电、厂商省电误判 | 3h | 增加 `releaseSharedPlayer()` 并在退出路径调用 |
| **P1** | P1-2 | 双端 | 封面 `LruCache` 与 Bitmap 字段无并发保护 | 重复解码、Bitmap 跨线程使用 | 2h | 单飞加载 + `@Volatile` / 同步 |
| **P1** | P1-3 | 双端 | 下载路径 `..` 穿越 | 可写出应用目录，服务端可控 | 1h | 段净化 + `canonicalFile` 前缀校验 |
| **P1** | P1-4 | 双端 | 断点续传不校验 `Content-Range` | 静默产出损坏音频 | 1.5h | 校验 range 起点 + 完成长度校验 |
| **P1** | P1-5 | 双端 | 「已下载」标记不校验物理文件 | 显示已下载但播放失败 | 2h | 复用 `verifyAndSyncAllServerSongDownloadStatus` |
| **P1** | P1-6 | 双端 | `MediaMetadataRetriever` 异常路径泄漏 | 长时间使用后 FD 耗尽 | 1h | 统一 `try/finally` |
| **P1** | P1-7 | 双端 | 缓存上限合计 2.3GB | 存储占用投诉、低端设备空间告急 | 1h | 降至 200–300MB 并按可用空间动态取值 |
| **P1** | P1-8 | 双端 | 点播路径最多 3 次全表扫描 | 大曲库点歌卡顿 0.5–3s | 4–8h | 单次查询复用 + 归一化列索引 + 内存 LRU |
| **P1** | P1-9 ~ P1-13 | 双端 | 网络层五连：换链放大、取消失效、凭据安全、Range OOM、整表读改写 | 弱网耗时、令牌外泄、OOM、收藏丢失 | 各 1–4h | 见第四章逐条修复建议 |
| **P1** | P1-14 ~ P1-15 | 手机 | 滚动整页重组 + 组合期主线程磁盘 IO（4 处）；loading 无 `finally`（5 处） | 掉帧 / 卡顿 / 永久转圈 | 6h | `derivedStateOf` + IO 移出组合期 + `try/finally` |
| **P1** | P1-16 ~ P1-18 | 手机 | 主线程 `commit()`、媒体键双触发、边缘手势 | ANR / 双切歌 / 误返回 | 4h | 逐条对齐 TV 端实现 |
| **P1** | P1-19 | TV | 换绑后监听器留在已释放播放器上 | 软解降级后播放状态失联 | 2h | 播放器实例放入 `mutableStateOf` 或用 swap 版本号作 key |
| **P1** | P1-20 | 双端 | 权限结果被丢弃、无 rationale | 权限问题长期无人察觉 | 1h | 判断 `granted` + 系统设置引导 |
| **P1** | P1-21 / P1-22 | TV | 9 处 Dialog 无初始焦点；下钻详情焦点丢失且返回不回原卡片 | TV 主要交互路径断裂 | 5h | FocusRequester 补偿 + focusRestorer |
| **P1** | P1-23 / P1-24 | 手机 / TV | 进度轮询无生命周期 + Room Flow 重建；设置 Tab 焦点即切换触发重负载 | 耗电 / 失效观察者重建 / 遥控扫 Tab 卡顿 | 4h | 对齐 TV / 加防抖与缓存 |
| **P1** | TV-C3 ~ TV-C11 | TV | 永久软解误判、缓冲期暂停失效、队列永不完结、新旧歌落盘错位、becoming-noisy 误停播、最近播放未合并、清缓存删活索引、更新包零校验、更新流程不可用 | TV 稳定性与安全 | 各 1–4h | 见 8.2 逐条修复建议 |
| **P1** | T-17 | TV | 无 `TvLazyRow/TvLazyColumn`，缺 pivot 与 focusRestorer | 遥控跨行移动体验断裂 | 1–2 天 | 引入 `androidx.tv:tv-material` 或手写 |
| **P2** | P2-1 ~ P2-21 | 双端 | 见第九章（缓存清理、音频焦点音量、主线程 commit、DB 主线程初始化、收藏并发、初始化竞态、Socket 未关闭、TV 权限、向导逃生、失败残片锁死、版本号硬编码、APK 下载 FD、令牌外发、allowBackup、明文流量、深分页） | 体验 / 性能 / 安全 | 各 0.5–2h | 按表中逐条处理 |
| **P2** | N-8 ~ N-20 | 双端 | 见 4.2（缓存无限增长、忽略分页、静默截断 500、死兜底、N+1 请求、时长错位、音质参数失效、鉴权结果丢弃、token 非 volatile 等） | 功能正确性与资源占用 | 各 0.5–2h | 按表中逐条处理 |
| **P2** | U-7 ~ U-17 | 手机 | 见 5.2 / 5.3（大 List 作 key、定位偏移一行、Slider 逐像素 seek、逐帧落盘、IO 线程写状态、重复声明的偏好、共享滚动位置、非惰性 items、动画未门控、串行拉封面、主线程洗牌） | 交互体验与耗电 | 各 0.5–2h | 按表中逐条处理 |
| **P2** | T-5, T-8, T-9, T-11 ~ T-16, T-18, T-20 | TV | 见 6.3（Tab 副作用、组合体读下载流、主线程 stat、双重焦点、主线程缓存遍历、位图过采样、根作用域读数、key 含下标、无 overscan、按键消费语义） | 遥控体验与内存 | 各 0.5–2h | 按表中逐条处理 |
| **P2** | TV-C12, TV-C14 ~ TV-C23 | TV | 见 8.3（清理不生效、通知被贴回、WiFi 锁失效、关机落盘失效、倍速丢失、通知无 MediaStyle、更新无续传、hasUpdate 未校验、队列缓存竞争、timeline 无队列、onTaskRemoved 误收摊） | TV 稳定性与体验 | 各 0.5–3h | 按表中逐条处理 |
| **P3** | P3-1 ~ P3-11 / N-21 ~ N-24 / U-18 ~ U-20 / T-21 ~ T-22 / M-11 ~ M-21 | 双端 | debug 签名、缺崩溃日志、缺软解降级、偏好键不一致、设置同步写盘、伪 versionCode、包名白名单、响应未消费、脆弱断言、导出接收器、死代码兜底、单例不校验配置、主线程读 prefs、阈值不一致、Modifier 顺序、IME 缺失、投屏 key 缺失、导航状态未 saveable、乐观更新不回滚、错误 key 等 | 工程质量与可维护性 | 各 0.5–3h | 建立双端同步修复机制 |

**投入产出比最高的五项**

1. **P0-1**（0.5h）—— 换回 Android 13+ 全体用户的本地曲库能力；
2. **P0-2**（0.5h）—— 消除一个必崩；
3. **P0-3**（2–4h）—— 避免一次灾难性数据丢失；
4. **TV 8.1 无限换源循环 + P0-6 焦点抢占**（约 5h）—— TV 用户最直观的两类「卡住 / 停不下来」；
5. **P1-8 + P1-14**（1–2 天）—— 直接决定大曲库用户的点歌与滚动流畅度。

---

## 十三、修复路线图建议（分批落地）

| 批次 | 内容 | 目标 | 建议周期 |
| :--- | :--- | :--- | :--- |
| **第 1 批（当天）** | P0-1 权限、P0-2 `random()` 崩溃、P0-5/TV 焦点三连（P0-6、P0-7）、P0-8 定时关闭、TV 8.1 无限重试、P0-4 前台服务 | 消除必崩、消除设备级锁死、恢复 Android 13+ 本地曲库 | 1–2 天 |
| **第 2 批（本周）** | P0-3 数据库迁移策略、P1-16 主线程落盘、P1-17 媒体键、P1-18 边缘手势、P1-9 ~ P1-13 网络层、TV-C3 ~ TV-C11 | 消除数据丢失与安全风险 | 3–5 天 |
| **第 3 批（两周）** | 其余 P1 全部（播放器释放与功耗、缓存并发、路径穿越、断点续传、FD 泄漏、缓存上限、点播全表扫描、P1-14/P1-15 手机构图与 loading、P1-21/P1-22 TV 焦点、T-17 TvLazy） | 性能、稳定性与体验主线 | 2 周 |
| **第 4 批（持续）** | P2 / P3 全部 + 第十四章工程加固 | 建立不再重复产生同类问题的机制 | 持续 |

**验收口径建议**：

1. P0-2、P0-5、P0-6、P0-7、TV 8.1 必须有可复现步骤与修复前后对比（这类问题只能靠复现验证）；
2. P0-3 必须补一个 `MigrationTestHelper` 测试，否则不允许再动 `version`；
3. P1-8、P1-14 需给出「1 万首曲库」下的点歌耗时与滚动帧率前后数据；
4. 所有 `core/` 修复必须双端同步，CI 增加双端一致性校验。

---

## 十四、工程加固建议

1. **建立双端同步机制**：抽出 `:core` 共享模块；或在 CI 中对 `core/` 目录做双端内容一致性校验。第十一章列出的 17 处漂移说明这已是当前最大的系统性风险来源。
2. **接入静态检查**：开启 Android Lint（`abortOnError = true`）与 detekt，把 `!!`、空 `catch`、`GlobalScope`、`runBlocking`、主线程 IO、`Modifier.clickable` 与 `focusable` 混用设为 error 级别 —— 本次发现的 44 处 `!!` 与 14 处空 catch 大部分可被自动拦住。
3. **补齐测试**：当前 `test/` 与 `androidTest/` 无有效用例。优先为 `SongMatchingResolver`（版本 / 时长匹配规则）、`DownloadEngine.resolveFilesystemPath` 与 `sanitizeSegment`、`PlaybackQueueManager` 队列推进（单曲 / 乱序 / 重复 id / 循环边界）、`LemonMusicProtocol` 的时长与扫描状态解析写纯 JVM 单元测试 —— 这正是 P0-2、P1-3、N-12、N-14 的所在。
4. **统一错误可观测性**：把 TV 的 `CrashLogger` 推广到双端，并为「播放失败 / 下载失败 / 权限缺失 / 前台服务启动失败」增加结构化本地诊断日志，支持一键导出。
5. **数据库治理**：`exportSchema = true` + schema 入库 + `MigrationTestHelper` 迁移测试，禁止再出现无迁移的版本升级。
6. **依赖与构建**：手机端改用 TV 端签名方案（本地密钥 / 环境变量，不入库）；统一 `packaging.resources.excludes`；评估恢复 Room / KSP 的增量编译（当前 `kotlin.incremental=false`、`ksp.incremental=false` 会显著拖慢增量构建）。
7. **权限矩阵**：把「API 版本 × 权限」整理成一张表并在两端共用（P0-1、P2-10、TV-C15 都属于漏项）。
8. **清理死代码与假功能**：TV 的 `if (true)` 竖屏分支（约 470 行）、搜索覆盖层（M-17）、定时关闭（P0-8）说明「看起来能用、实际无实现」的功能已经出现，建议对看板 / 开关类功能做一次真伪巡检。

---

## 十五、审计覆盖清单

| 模块 | 文件 | 状态 |
| :--- | :--- | :--- |
| 播放核心 | 双端 `PlaybackService` / `PlaybackQueueManager` / `PlaybackRouter` / `Media3Factory`、TV `PlaybackWakeLockManager` | ✅ 逐行精读 + 专项报告（第八、十一章） |
| 数据层 | `ZdsDatabase`、`Entities`、`MusicDao`、`Models` | ✅ 逐行精读 |
| 网络层 | `NetworkClientFactory`、`NasMusicProtocol`、`LemonMusicProtocol`（两端各约 3.6–3.8k 行） | ✅ 逐段通读（第四章） |
| 下载与元数据 | `DownloadEngine`、`AudioMetadataEmbedder`、`DownloadRequestPlanner` | ✅ 逐行精读 |
| 媒体扫描 | `LocalMediaScanner`（880 / 977 行） | ✅ 逐行精读 |
| 歌词 | `LyricsManager`（1339 / 1348 行） | ✅ 关键路径精读 |
| 灵动岛 / 悬浮层 | `DynamicIslandManager`、`BackgroundIslandOverlayController` | ✅ 关键路径精读 |
| 投屏 | `AudioSharingManager`（1552 行） | ✅ 生命周期与并发审查 |
| 更新 | `AppUpdateManager`、`AppUpdateDialog` | ✅ 逐行精读 + TV 专项（8.2） |
| 手机 UI | `LocalLibraryScreen`、`SettingsScreen`、`LemonDiscoverHomeScreen`、`FullscreenPlayerSheet`、`MainActivity`、`LocalMusicHomeScreen` | ✅ 全文读完 + 专项报告（第五、七章） |
| TV UI 与遥控 | `FullscreenPlayerSheet`、`SettingsScreen`、`LocalLibraryScreen`、`AdaptiveAppScaffold`、`LemonDiscoverHomeScreen`、`PlatformModeWizard`、`TvFocusModifiers`、`MainActivity` | ✅ 全文读完 + 专项报告（第六、七章） |
| 构建与清单 | 两个 `build.gradle.kts`、两个 `AndroidManifest.xml`、`gradle.properties` | ✅ 逐行精读 |

**未审计 / 未覆盖**：`ios/` 目录（与本次请求无关）、`AudioMetadataEmbedder` 的二进制标签写入字节级正确性、`AudioSharingManager` 的 DLNA / AirPlay 协议细节、`LyricsScrollingView` 的逐字歌词插值算法、`docs/` 与 APK 产物、`SongMatchingResolver` 的版本识别规则穷举验证。

---

## 十六、审计方法与局限性说明

**方法**：
1. 结构侦察：统计两端全部 Kotlin 文件行数与分布，识别高风险大文件（最大 3.8k 行）；
2. 核心链路逐行精读：播放 / 队列 / 路由 / 数据层 / 下载 / 扫描 / 更新 / 构建清单；
3. 全量模式扫描（两端分别执行，避免路径作用域遗漏）：`!!`、`first()/last()/random()`、`runBlocking`、`GlobalScope`、生命周期配对、`commit()`、`registerReceiver`、`Thread.sleep`、权限常量、并发原语、`MediaMetadataRetriever` 生命周期等；
4. 双端差异比对：对同名文件做逐行 diff，识别「一边修了、一边没修」的漂移；
5. 大文件分片深读：对 11 个超大文件（双端网络协议、双端 MainActivity、手机 4 个 UI 文件、TV 5 个 UI 文件、TV 5 个播放核心文件）分别独立通读并交叉验证；
6. 交叉复核：对已报告结论做二次核对（如 `PlaybackWakeLockManager`、`NetworkClientFactory` 单例、`setMediaItem` 后 `prepare()`、ExoPlayer 线程归属等），并在报告中列出「已核对无问题」项以避免误报。

**局限性**：
- 本报告为**纯静态审计**，未在真机 / 模拟器运行验证；标注为「必崩」「必现」的条目均已通过完整代码路径推演（触发条件 → 调用链 → 异常类型 → 线程归属），但仍建议修复前先复现；
- 焦点时序类问题（T-2 / T-3 / T-6）最终表现依赖 Compose 焦点系统与厂商 ROM 的窗口焦点行为，需真机确认；
- 行号以本次审计时的工作区版本为准（部分文件存在未提交改动）；同一文件在两端行号不同，引用时请以所在平台的文件为准；
- 部分大文件（如 `FullscreenPlayerSheet`、`LemonMusicProtocol`）存在多个审计方独立给出的行号，若出现 ±数行差异属正常，以代码片段为准。

---

**报告结束。** 全部问题条目均带文件行号与代码证据，可直接作为修复任务清单使用。
