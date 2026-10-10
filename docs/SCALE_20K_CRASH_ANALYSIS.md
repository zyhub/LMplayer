# 2 万首曲库崩溃风险排查报告

> 问题：服务器端 2 万首歌、或手机本机扫描 2 万首歌，会不会导致 LMPlayer 崩溃？
> 结论：**会崩，而且不是边界情况，是进入该量级后的常态路径。**

---

## 一、直接结论

| 场景 | 判定 | 主要崩溃机制 |
| :--- | :--- | :--- |
| **服务器曲库 2 万首** | ❌ **确定会崩**（低端/中端机必现） | ① 首次全量同步时整树 JSON 解析，峰值 **140~160MB**，未开 `largeHeap` → **立即 OOM**；② 即使内存侥幸过关，每次数据库失效都会在**主线程**重算 2 万首 + 最多 4 万次磁盘 stat → **必然 ANR**；③ 服务器改路径/重扫导致 ≥1000 首变孤儿时，未分块的 `IN (...)` 抛异常且**无 try/catch → 闪退** |
| **本机扫描 2 万首** | ⚠️ **不会立即 OOM，但会卡死到用户强杀** | ① 内存峰值仅 30~50MB，一般不崩；② 2 万次 `embeddedPicture` + 逐首写 JPEG ≈ **10~40 分钟**且无进度；③ **若同时配了服务器**，扫描结束会走 O(N²) 匹配（2万×2万 = 4×10⁸ 次正则比对）→ **永不返回**；④ `absolutePath.hashCode()` 作主键 → 2 万首约 **4.5% 概率静默丢歌**；⑤ 目录递归无界，软链接自环 → `StackOverflowError` 未被捕获 → 闪退 |

**一句话**：服务器 2 万首是「必崩」，本机 2 万首是「卡死 + 可能丢歌」；两者叠加更糟。

---

## 二、为什么会崩：内存账

### 2.1 服务器全量同步的峰值（最关键的一条）

`LemonMusicProtocol.getSongList()` 请求 `/api/library/tracks?all=1`（**无分页**），然后：

```kotlin
val body = resp.body?.string() ?: ""   // 整份响应读成一个 String
val json = JSONObject(body)            // org.json 整树解析
```

2 万首歌的 JSON 约 12~18MB，各层内存占用：

| 层 | 占用 |
| :--- | ---: |
| OkHttp body `byte[]` | ~15 MB |
| `String(body)`（UTF-16，每字符 2 字节） | ~30 MB |
| org.json 整树（膨胀 3~5 倍） | ~60~80 MB |
| `List<UnifiedSong>` 2 万条 | ~10 MB |
| 静态 `songIdToSongMap`（又存一份完整对象） | ~12 MB |
| MainActivity 再映射 `List<SongEntity>` | ~10 MB |
| **峰值合计** | **≈140~160 MB** |

`AndroidManifest.xml` **未声明 `android:largeHeap`**，堆上限取系统默认（低端机 128MB / 中端 192~256MB）。**128MB 设备立即 OOM，192MB 设备在同步与扫描并发时也极易 OOM。**

**最致命的一点**：外层是 `catch (e: Exception)`，而 `OutOfMemoryError` 属于 `Error` **不被捕获**，会一路逃到 `lifecycleScope.launch` → **进程直接被杀**，没有任何友好失败提示。用户看到的就是「同步到一半闪退」。

估算阈值：**约 8,000~12,000 首**（128MB 堆）/ **约 20,000 首**（192MB 堆）。

### 2.2 为什么即使不 OOM 也会 ANR

`MainActivity` 用 `invalidationTrackerFlow("songs")` 监听曲库表，**每次任何写入都会触发**：

```kotlin
LaunchedEffect(Unit) {                    // 默认 dispatcher = 主线程
    database.invalidationTrackerFlow("songs")
        .map { database.songDao().getAllSongsList() }   // 分页读全表
        .collect { ... }                                 // ★ collect 体在主线程
songList = mappedSongs                                   // 2 万条
PlaybackQueueManager.updateMetadata(mappedSongs)         // ★ 主线程重算
```

`updateMetadata` 内部：`songs.associateBy { it.id }`（建 2 万条 HashMap）+ `map`（2 万次 copy）+ `mergeSongMetadata`，而后者对**每首歌做 2 次 `File.exists()`** → **最多 4 万次磁盘 stat**。

导入/扫描期间数据库会被反复写入，于是这个 2 万首的重算被反复触发 → 主线程持续被占满 → ANR。估算阈值：**约 5,000 首**。

### 2.3 本机扫描的内存账（相对安全）

没有 150MB 级峰值，约 30~50MB：MediaStore 结果是分窗读取的（Cursor 不会因 2 万行崩），但 2 万条 `SongEntity` 全部驻留约 12MB，叠加逐首封面写盘（2 万张 JPEG ≈ 400MB~1GB 缓存且无清理）。

---

## 三、会崩的完整清单（按严重度）

### 必崩级

| # | 位置 | 问题 | 触发条件 |
| :--- | :--- | :--- | :--- |
| 1 | `LemonMusicProtocol.kt:499/503/507` | `all=1` 整树解析，峰值 140~160MB，OOM 不被捕获 | **首次全量同步即触发** |
| 2 | `MainActivity.kt:926` → `PlaybackQueueManager.kt:524` | 主线程重算 2 万首 + 最多 4 万次 stat | 每次数据库失效 |
| 3 | `SongMatchingResolver.kt:652` / `LocalMediaScanner.kt:995` | `deleteSongsByIds` 未分块 → `too many SQL variables`，**调用链无 try/catch** | 服务器改根路径/重扫，≥1000 首变孤儿 |
| 4 | `MainActivity.kt:1414-1441` | 批量下载「本地」分支**未切 IO**，主线程循环 2 万次 + 每轮整体复制 Map（Σi = 2×10⁸） | 全选下载 |
| 5 | `MainActivity.kt:2199-2247` | 收藏页 O(收藏 × 曲库) 线性扫描在组合期主线程 | 收藏 ≥2000 首 |
| 6 | `LocalMediaScanner.kt:672/846/886` | 本机 × 服务器 O(N²) 匹配，每元素一次 stat | 本机与服务器各有千首以上 |

### 可能崩级

| # | 位置 | 问题 |
| :--- | :--- | :--- |
| 7 | `MainActivity.kt:1815` | `onPlayerError` 在主线程 `.get(3s)` —— ExoPlayer 未 `setLooper`，回调实际在**主线程**，每次播放错误冻结 3 秒；且超时的 Future 不取消，单线程池会堆积整库扫描 |
| 8 | `LemonMusicProtocol.kt:149/150/151` | 三张**无上限**静态 `ConcurrentHashMap`，`songIdToSongMap` 持有整库第二份副本，约 15~25MB **永久驻留**，与峰值叠加直接 OOM |
| 9 | `LocalMediaScanner.kt:189/265/391/498` | `absolutePath.hashCode()` 作主键 + `REPLACE` → 2 万首约 **4.5% 碰撞概率**，后一首**静默覆盖**前一首（「曲目总数对不上」的根因之一） |
| 10 | `LocalMediaScanner.kt:160-172/245-253/483-536/785-793` | 无界递归，软链接自环/超深目录 → `StackOverflowError`（`Error` 不被 `catch(Exception)` 捕获）→ 闪退 |
| 11 | `MainActivity.kt:678-691` | 收藏同步每个收藏 2 次独立 Room 事务 → 2 万收藏 = 4 万次 fsync，且每次触发上面第 2 条的整库重算 |

### 仅变慢（体验退化）

- `SongMatchingResolver.kt:116/122`：`extractEditionMarkers` 内 **每次调用都新建 Regex**（同文件注释明确说要预编译，这两处遗漏）—— 是上面多条慢到不可用的**直接放大因子**；
- `SongMatchingResolver.kt:93/167`：归一化缓存上限（8192/4096）**小于曲库规模**，2 万首必然「填满即整体 clear」→ 每次全库 pass 都要重跑正则管线；
- `LocalLibraryScreen.kt:736`：主线程 `sortedBy` 2 万条 + `remember` 键的 2 万元素深比较；
- `LibrarySearchDialog.kt:205`：本地搜索**无防抖**（在线分支有 `delay(280)`），每次按键 6 万次 `lowercase()` 分配；
- `SettingsScreen.kt:256-268`：进入设置页对全库逐首 stat（已在 IO 内，但 2 万次要数秒）；
- `LocalMediaScanner.kt:554-595`：封面 JPEG 无清理策略。

---

## 四、明确安全的路径（经核查不会因规模崩溃）

这两轮排查也确认了不少地方**已经做对了**，避免误改：

| 路径 | 结论 |
| :--- | :--- |
| `MusicDao.getAllSongsList()` | ✅ **最佳范式**。主键游标 `WHERE id > :lastId ORDER BY id LIMIT 200` 分页聚合，专为规避 CursorWindow 2MB 上限而写 |
| 全部 Compose 列表 | ✅ 全部 `LazyColumn/Row/Grid + itemsIndexed(key = { it.id }, contentType = ...)`，**未发现任何 `hashCode()` 作 key**，也未发现非惰性渲染大列表 |
| TV 焦点系统 | ✅ 无 `focusRestorer`/`focusGroup` 全树回溯，无 `List(n){FocusRequester()}` 数组，方向键逐节点失效 —— **焦点算法本身不会卡死** |
| 播放队列持久化 | ✅ `MAX_PERSISTED_QUEUE_SIZE = 120` + 窗口截断，**不会把 2 万首写进 SharedPreferences** |
| Coil 封面 | ✅ 全部 `.size(targetSize, targetSize)`、RGB_565、无 prefetch，只在可见项建请求 |
| 歌词 | ✅ LRU 上限 200、按需加载、仅预热下一首，**不是全量预解析** |
| OkHttp 超时 | ✅ `readTimeout 60s` 是 per-read 语义，20MB 响应只要持续有数据就不会超时 |
| `@Insert(List)` | ✅ Room 逐行 bind（每行 18 个参数），**不触 999 变量上限**（与 `IN (...)` 不同） |
| Media3 队列 | ✅ 只用 `setMediaItem` 单曲，无 `setMediaItems(2万)` |

---

## 五、本轮已修复（含出包）

| 修复 | 位置 | 效果 |
| :--- | :--- | :--- |
| **`insertSongs` 分块写入**（500 行/批） | `MusicDao`（双端） | 2 万行单事务 → 分批独立事务。原实现单事务插入 2 万行 × **8 个索引** = 16 万次索引更新，SQLite 脏页全在内存累积到 commit |
| **`deleteSongsByIds` 分块删除**（500 个/批） | `MusicDao`（双端） | 消除 `too many SQL variables` 的**未捕获异常闪退**，同时修复 `purgeLegacyResidualData` 里被 catch 吞掉导致的「一条都删不掉」 |
| **`updateMetadata` 移出主线程** | `PlaybackQueueManager`（手机端；TV 端原本已在后台线程） | 消除「每次数据库失效都在主线程重算 2 万首 + 4 万次 stat」的必然 ANR |
| **`onPlayerError` 去阻塞** | `MainActivity.kt:1815`（手机端） | 去掉 `.get(3s)` 主线程冻结，改为「异步查 + 回主线程复核后回调」 |

---

## 六、尚未修复（建议按此优先级继续）

| 优先级 | 修复项 | 说明 |
| :--- | :--- | :--- |
| **P0** | `all=1` 改分页拉取或 `JsonReader` 流式解析 | **这是唯一能消除「必崩 OOM」的修复**，也是服务器 2 万首场景的根因。同时应把同步链路的 `catch (Exception)` 改为 `catch (Throwable)` 或显式兜底 OOM |
| **P0** | `deleteSongsByIds` 调用链补 try/catch | 即使已分块，同步链路整体无异常兜底仍是隐患 |
| **P1** | `LocalMediaScanner` 的 672/846/886 改哈希桶索引 | 解除「本机 + 服务器」混合场景的永久挂起 |
| **P1** | 批量下载本地分支挪进 IO + `updateTask` 批量化 | 消除全选下载的必然 ANR |
| **P1** | 收藏页改 `associateBy` + 后台计算 | 消除收藏多时的 ANR |
| **P2** | 无界静态 Map 改 LRU 或换服务器时 `clear()` | 释放 15~25MB 常驻内存（电视盒子长期不关机时尤其重要） |
| **P2** | `absolutePath.hashCode()` → `md5(path)` 作主键 | 消除约 4.5% 的静默丢歌（需处理存量数据的 id 迁移） |
| **P2** | 递归改迭代 + 深度上限 | 消除 `StackOverflowError` 闪退 |
| **P2** | `extractEditionMarkers` 的 Regex 提到伴生对象 | 一处小改动，显著降低上述 O(N²) 路径的单次成本 |
| **P3** | 归一化缓存上限提到 ≥ 曲库规模；搜索加防抖；封面加清理 | 体验优化 |

---

## 七、给测试的建议

如果要在真机上验证当前风险边界，建议：

1. **先用 5,000~8,000 首的服务器曲库试**——可稳定复现「进入资料库页卡顿/ANR」，但通常不会 OOM；
2. **再用 2 万首试首次全量同步**，用 `adb logcat` 过滤 `OutOfMemoryError`、`lowmemorykiller`、`ANR in com.lm.player`。低端机上预期能直接复现闪退；
3. **本机 2 万首扫描**：观察「正在扫描…」是否超过 30 分钟无进度；若同时配了服务器，注意扫描结束后是否永久卡在扫描态（这是 O(N²) 匹配的典型表现）；
4. 记录 `adb shell dumpsys meminfo com.lm.player` 的峰值，与 `getMemoryClass()` 对比，确认距离堆上限的余量。

---

**报告结束。** 本报告结论来自对 `app/src` 与 `LMPlayerTV/app/src` 共 60+ 个 Kotlin 源文件的逐行静态排查，每条均附文件:行号与代码证据；内存与耗时数字为基于代码路径的静态估算（已在文中注明假设），非实测值。
