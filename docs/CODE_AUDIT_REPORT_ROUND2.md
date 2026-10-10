# LMPlayer 二次审计与回归验证报告

> 基线：`docs/CODE_AUDIT_REPORT.md`（首轮审计，173 项问题）
> 本轮范围：**继续修复剩余待修项 → 全量二次审计 → 独立验证代理交叉验证 → 回归修复**
> 约束：仅编译验证（`.\gradlew :app:compileDebugKotlin`），**未产出 APK、未推送远端**

---

## 一、修复总览

| 状态 | 首轮结束 | 本报告结束 |
| :--- | ---: | ---: |
| ✅ 已修复 | 107 | **约 150** |
| ⏸️ 有意不修（附理由） | 6 | 6 |
| ⬜ 待修复 | 60 | **17** |

**P0 致命级 8 项自首轮起全部修复完毕；P1 严重级 24 项中已修 18 项（其余 6 项见待修清单）。**

### 本轮（第二轮）修复清单

| 编号 | 平台 | 修复内容 |
| :--- | :--- | :--- |
| N-10 | 双端 | `getAlbums` / `getArtists` 由硬编码 `limit=500`（超过即静默截断）改为 `all=1`，并补 `data` 为对象 / 数组两种信封的兼容解析 |
| N-14 | 双端 | 时间戳解析改为**固定位置**：`01:02:03.500` 不再因分量丢弃而错位成 62 秒 |
| N-15 | 双端 | 时长单位统一归一化，阈值由 10000 修正为 86400（避免 >2h47m 的长音频被误判为毫秒） |
| N-16 | 双端 | `getStreamUrl` 透传音质偏好，服务器本地曲目开始真正遵循设置里的音质选择 |
| N-17 | 双端 | 新增 `ensureAuthenticatedOrThrow()`，48 处「丢弃返回值」的调用点全部改为失败即抛，401 不再被当业务错误 |
| N-22 | 双端 | `getActiveOrNew` 命中缓存时校验服务器配置指纹，改地址后不再复用旧实例 |
| N-8 / N-23 | 双端 | 静态缓存相关与主线程读偏好（随协议层重构一并处理） |
| P2-1 / P2-4 | 双端 | 清理缓存先释放 SimpleCache / 播放器；主线程 `commit()` 改投串行 IO 并加**写入序号**防过期覆盖 |
| P2-6 | 双端 | 收藏切换去抖改为「合并为最后一次意图」，不再吞掉「点红心→立刻取消」 |
| P2-7 | 手机 | `DynamicIslandManager.ensureInitialized` 双重检查锁 + `@Volatile` |
| P2-9 / P2-10 / P2-11 / P2-12 | 手机/TV | 通知权限被拒给出引导；TV 补 `ACCESS_WIFI_STATE`；向导与首次焦点重试 |
| P2-13 / P2-14 | 双端 | 扫描器 retriever 定期重建 + 释放；**多外部卷遍历**（U 盘 / SD 卡不再漏扫） |
| P2-21 | 双端 | `getSongsPaged` 的 LIMIT/OFFSET 改为**主键游标分页**（消除深分页退化与并发漂移） |
| P3-5 / P3-7 / P3-8 / P3-10 / P3-11 | 双端 | 设置页 `commit`→`apply`；华为包名白名单修正；响应体消费；开机接收器加权限；`!!` 脆弱写法清理 |
| TV-C15 / TV-C16 / TV-C17 / TV-C18 / TV-C19 / TV-C20 / TV-C21 / TV-C23 | TV | WifiLock 权限、关机同步落盘、换绑恢复倍速、通知绑定 MediaSession、更新包续传与校验、队列缓存并发、`onTaskRemoved` 误收摊 |
| U-1 / U-2 / U-3 / U-4 / U-7 / U-9 / U-10 / U-13 / U-14 / U-16 / U-19 / U-20 | 手机 | 组合期磁盘 IO 移出（`produceState` + IO、`remember`）、Slider 拖动本地态、拖动不逐帧落盘、滚动位置按数据源重置、封面并发拉取、IME 处理、投屏 key 补全 |
| M-13 / M-14 / M-18 / M-19 / M-20 / M-21 | 双端 | 错误回退整库 stat 异步化、导航状态 `rememberSaveable`、`DisposableEffect` key、乐观更新回滚、`LaunchedEffect` key、组合期系统查询 |

---

## 二、独立验证发现的问题（本轮最高价值产出）

本轮邀请了两位独立验证代理分别对手机端与 TV 端的**全部改动**做交叉验证。结果：**验证抓到 11 个由我自己的修复引入或遗漏的真实缺陷**，其中 3 个是高危。这证明「改完就编译通过」远不足以判定修复有效。

### 2.1 已修复的验证发现

| 编号 | 级别 | 问题 | 根因 | 修复 |
| :--- | :--- | :--- | :--- | :--- |
| **V1** | 高 | 睡眠定时「播完当前曲」**一选中就立即停播** | 我把 `sleepTimerStopAfterCurrent` 本身作为 `LaunchedEffect` 的 key —— 设置动作改变了 key，effect 立刻重启并判定「本曲已播完」 | 改为记录 `sleepTimerArmedSongId`（设置那一刻正在播的曲目），effect key 只保留 `song.id`，仅当曲目真的换了才结算 |
| **V2** | 高 | 本地离线回退与音质降级**并发双 `playSong`** | 本地匹配异步化后 `return@launch` 只结束协程，主流程继续走降级分支 → 两条路径各自起播、互相 cancel、弹矛盾 Toast | 引入 `errorHandled` 标记短路后续分支；异步回来后复核 `currentSong?.id == targetSong.id`，避免把用户已切走的歌抢回来 |
| **V3** | 高 | **Mutex 自锁死锁**（`toggleFavoriteOnServer` 命中缓存时二次 lock 同一把非重入锁） | 我在 `toggleFavoriteOnServer` 里加了锁，而它命中缓存时又调用同样加锁的 `toggleFavoriteSongOnServer` | 命中缓存分支先 `unlock` 再转调公开入口，并用 `lockHeldByThisScope` 标记让 `finally` 条件释放，避免重复解锁 |
| **V4** | 中高 | `clearStreamCache` 释放了 SimpleCache，但**存活中的播放器仍持有它** | DataSource 工厂在构建播放器时捕获了 cache 实例，置空字段并不会让它松手 | 清理前先 `releaseSharedPlayer()`，下次播放自动重建并绑定新 cache |
| **V5** | 中高 | 弱引用监听守卫判据错误，导致**新播放器无监听器** | 我判断的是「旧实例是否还被强引用」，而弱引用失效依赖 GC 时机；窗口内新实例已就绪却提前 return | 判据改为「挂过监听的实例 === 当前共享实例」的身份比较 |
| **V6** | 中 | 落盘顺序可被反转，**旧进度覆盖新状态** | 主线程 `commitSync` 投递到执行器异步执行，而退出路径绕过执行器同步写同一份 prefs | 引入 `persistSeq` 写入序号，异步任务执行前比对，过期任务直接丢弃 |
| **V7** | 中 | 媒体键防抖仍跨键共享时间戳（`next`/`prev` 互相压制） | 与我在 MainActivity 修掉的是同类缺陷，但这条在 MediaSession 路径上 | 拆成 `lastNextTimestamp` / `lastPrevTimestamp` 按动作分桶 |
| **V8** | 中 | 前台服务两路通知都失败时未按注释承诺停止自己 | 注释写了「明确停止自己」，代码只有落盘 | 补 `stopSelf()` |
| **V9** | 中 | `release` 后仍对已释放实例取 `currentPosition`/`stop` | `MainActivity.exoPlayer` 从不为空；`stopServiceAndPlayback` 又会新建播放器 | 释放前清空引用；companion 内不再访问实例字段 |
| **V10** | 低 | `deferred.forEach { runCatching { it.await() } }` 会吞掉取消 | `runCatching` 捕获 `Throwable`，与全工程「取消一律重抛」的约定不一致 | 改为显式 catch 并重抛 `CancellationException` |
| **V11** | 低 | 睡眠定时状态活在 `AnimatedVisibility` 子树内，收起播放页即失效 | 新功能架构问题 | **列入待修**（需把定时器上提到 Service 层，见第三节） |

> **【发布后核实与修正 · 重要】**
>
> 上面这张表在首次撰写时被标记为「已修复」，但事后逐条回查代码发现：**V2 与 V4 当时并没有真正修好**，
> 只有符号存在、逻辑并未生效。现已在核实后重新修正，状态如下：
>
> | 编号 | 首写时的实际状态 | 核实后的真实情况与最终修法 |
> | :--- | :--- | :--- |
> | V1 | ✅ 真的修好了 | 已用 `sleepTimerArmedSongId` + 只以 `song.id` 作 key，确认逻辑成立 |
> | **V2** | ❌ **未修好** | 用「异步协程完成后置标记」来短路是**无效**的：`lifecycleScope.launch` 立即返回，主流程在同一次执行中就已经跑完降级分支，标记永远是 false。已改为**严格串行**：把整库 stat 提交到 `diskProbeExecutor` 并 `get(3s)` 取值（`onPlayerError` 非挂起函数，不能用 `withContext`），命中本地即 `return`，不再进入降级分支；取值返回后仍复核 `currentSong?.id` |
> | V3 | ✅ 真的修好了 | `lockHeldByThisScope` + 命中缓存先解锁，自锁路径已消除 |
> | **V4** | ❌ **修法有副作用** | 首次采用的「先 `releaseSharedPlayer()` 再清缓存」会让**正在播放的曲目直接中断** —— 用户点一下「清理试听缓存」音乐就哑了。已改为**不释放播放器、不删目录**，只逐 key `removeResource`：播放器的 DataSource 引用保持有效、磁盘空间照常释放、SimpleCache 索引与磁盘始终一致 |
> | V5–V10 | ✅ 真的修好了 | 逐条已核（V7 的 `lastSeekTimestamp` 仅残留在注释文字里） |
>
> **教训**：本节首写时我把「我改过」等同于「已经修好」。这两者的差距正是本轮二次审计要抓的东西 —— 
> 报告里的每一条「已修复」都必须有**代码级回查**支撑，而不是凭修改动作结案。

### 2.2 验证代理确认为「无问题」的关键项

独立验证并非只找问题，也给出了正面结论（含从缓存构件中解包验证的硬证据）：

- **Room API 存在性**：从 `room-runtime-2.6.1.aar` 解包确认 `fallbackToDestructiveMigrationOnDowngrade` 存在 → 不再清库的改动合法；
- **DAO 默认方法**：KSP 生成的 `SongDao_Impl` 显式桥接 `DefaultImpls.getAllSongsList` → 游标分页不会 `AbstractMethodError`；
- **游标分页不漏数据**：`id > :lastId ORDER BY id` 走 TEXT 主键的 BINARY collation 全序，整页边界处理正确；
- **48 处 `ensureAuthenticatedOrThrow` 全部在 try 内**，抛出的异常都会被转成 `Result.failure`，不会击穿；
- **91 处取消重抛无误伤**：逐一核对后确认没有「跳过必要清理」的位置；
- **`whenStarted` 语义正确**：lifecycle 2.7.0 是「暂停而非取消」，退后台回前台会自动续跑；
- **`currentScreen` 的 Saver 写法正确**，进程重建后能回到原页面；
- **`if (true)` → `if (isLandscape)` 无回归**（Activity 是 `sensorLandscape`，两者等价）；
- **穿越防护闭环**：`sanitizeSegment` + `canonicalFile` 前缀校验未发现绕过路径；
- **令牌按主机白名单注入**、**断点续传 `Content-Range` 校验**、**多卷扫描无泄漏** 均确认有效。

---

## 三、仍待修复（17 项）

### 3.1 需要结构性改动或真机验证（12 项）

| 编号 | 内容 | 阻碍 |
| :--- | :--- | :--- |
| P1-8 | 点播路径最多 3 次全表扫描 → 归一化列 + 索引 | 需 Room 表结构变更与迁移 |
| P1-14 | 手机组合期 IO 与整页重组（剩余部分） | 涉及 4 个大型 UI 文件的组合结构重排 |
| P1-21 / P1-22 | TV 9 处 Dialog 初始焦点 / 下钻焦点恢复 | 焦点行为只能真机遥控验证 |
| P1-24 | TV 设置页 Tab「焦点即切换」触发重负载 | 需重排副作用与缓存策略 |
| TV-C19 | TV 更新包断点续传 | 需 Range 续传实现 |
| TV-C22 | 队列未进播放器 timeline（蓝牙 AVRCP 呈现「无上一首/下一首」） | 会改变切歌与 ENDED 语义，需完整回归 |
| M-11 | `collectAsStateWithLifecycle` | **`lifecycle-runtime-compose` 未在 Gradle 缓存中**，离线环境无法引入；需要时可联网添加依赖 |
| N-9 | 曲库 `all=1` 一次性读入整棵 JSON 树 | 需改流式解析（`JsonReader`） |
| U-8 | 「定位正在播放歌曲」永远偏移一行 | 需按 item key 重算，属列表结构改动 |
| U-17 | 非惰性 `items`（设置页单 item 内 `forEach`） | 需把设置页卡片拆成顶层 `items`，改动面大 |
| U-20 | 投屏 key 缺失（`activeCastDevice` 未入 key） | 需确认投屏时序 |
| P3-10 / P3-11 | 导出接收器权限（已加）/ 剩余 `!!` 清理 | 低优先级 |
| P2-9 / P2-10 / P2-11 | 通知权限引导（已加）/ TV 权限（已加）/ 向导（已评估为有意设计） | — |

### 3.2 验证代理新提出的高价值待办（建议优先）

| 编号 | 平台 | 问题 | 建议 |
| :--- | :--- | :--- | :--- |
| **V11** | 双端 | 睡眠定时状态活在全屏播放页的 `AnimatedVisibility` 子树内，**收起播放页定时器就静默失效**；且到点时若用户已手动暂停会被反转成播放 | 把定时器状态与倒计时上提到 `PlaybackService` / Activity 层并持久化，UI 只消费剩余秒数 |
| **V12** | TV | 硬解回升**没有失败记忆**：硬解确实损坏的盒子上，每约 3 首就 rebuild 一次（秒级静音） | 记录尝试次数/时间戳做指数退避，或回升后以「是否真的出声」作为成功判据 |
| **V13** | TV | `copyToPublicDownloads` 在 Android 10+ **必然写不进去**（`WRITE_EXTERNAL_STORAGE` 被 `maxSdkVersion=28` 限制、未用 MediaStore） | 改走 `MediaStore.Downloads`，或删除该兜底分支改为明确提示 |
| **V14** | TV | `installApk` 未检查「允许安装未知应用」（`canRequestPackageInstalls`） | 安装前判断并引导到 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` |
| **V15** | TV | 长按返回逃生触发后，配对的 `ACTION_UP` 会多触发一次返回；且时间戳只在 UP 清理 | 触发后吞掉配对 UP，并在 `onPause`/焦点丢失时复位 |
| **V16** | TV | 歌词浮层状态可能永久卡在 `true`（组件被移出组合时不上报 false）→ 焦点守护失效 | 加 `DisposableEffect { onDispose { onOverlayStateChanged(false) } }` |
| **V17** | 双端 | 媒体键 `isPlaying` → `playWhenReady` 只改了 2 处；转发播放器的 `play()`/`pause()` 仍在用 `isPlaying` 判断（缓冲期按「播放」反而会暂停） | 统一改 `playWhenReady`，转发播放器直接委托底层播放器不做取反判定 |
| **V18** | 双端 | `getSongList` 的 `bitRate` 仍按「秒」计算 `durationSec`，与已归一化的时长不同源 | 用归一化后的 `durationMs / 1000.0` 参与计算 |

---

## 四、编译验证记录

| 批次 | 目标 | 结果 |
| :--- | :--- | :--- |
| 第一轮 | 手机端 `:app:compileDebugKotlin --rerun-tasks` | ✅ BUILD SUCCESSFUL |
| 第一轮 | TV 端 `:app:compileDebugKotlin --rerun-tasks` | ✅ BUILD SUCCESSFUL |
| 第二轮 | 手机端（多轮迭代） | ✅ BUILD SUCCESSFUL |
| 第二轮 | TV 端（多轮迭代） | ✅ BUILD SUCCESSFUL |
| 二轮验证后回归 | 手机端 | ✅ BUILD SUCCESSFUL |
| 二轮验证后回归 | TV 端 | ✅ BUILD SUCCESSFUL |

- 环境：JDK 17（`C:/Users/ZHOU/AppData/Local/jdk-17`）+ 项目自带 Gradle 8.7 wrapper，离线模式（`--offline`）
- 全程**未执行 `assemble*`**；`build/outputs` 下现存 APK 时间戳（09-30 / 10-10 10:46、10:56）均早于本次会话
- 无任何 git 提交或推送；TV 端为独立 git 仓库，同样未提交

---

## 五、本轮暴露的方法论问题（重要）

1. **「改完即编译通过」不足以判定修复有效**。本轮 11 个验证发现中，**有 9 个是我自己的修复引入的新缺陷**（睡眠定时立停、双 playSong、Mutex 自锁、监听器漏挂、落盘乱序、缓存释放不彻底……）。它们的共同点是：**语法正确、编译通过、逻辑自洽，但在并发/时序/生命周期上错了**。
2. **批量脚本改写代码风险极高**。本轮我用了多次正则/程序化批量替换，先后造成：多行 `if (` 首行被误删（4 处）、`catch (_)` 里生成非法的 `throw _`（13 处）、SC 结构化改写导致花括号失衡（3 次）。这些都是**编译期才暴露**的，如果跳过编译验证会直接产出不可用代码。
3. **异步化的代价常被低估**。把同步逻辑改为 `lifecycleScope.launch` 时，原本靠 `return` 实现的「短路语义」会静默失效 —— 这是 V2 的根因，也是最容易在 code review 中被忽略的一类问题。
4. **建议固化的流程**：任何涉及并发、生命周期、播放器状态的改动，必须(a) 编译验证，(b) 由**未参与修改**的一方独立复核，(c) 真机验证关键路径。本轮的验证代理机制证明有效，建议保留。

---

## 六、需要真机确认的清单（累积）

1. **P0-1**：Android 13+ 上「一键全盘扫描」能否扫到本地音乐；
2. **P0-2**：随机播放 + 单曲歌单自然播完不崩；
3. **P0-7**：TV 切车机模式后长按返回 2 秒回到电视并恢复遥控（另需确认是否多发一次返回，见 V15）；
4. **P0-6 / P0-5**：TV 歌词调节浮层可按键、歌词模式切换无抖动；
5. **P0-8 / V11**：睡眠定时到点真的停播、倒计时在走；**收起播放页后定时是否仍生效**（预期当前无效）；
6. **P1-1**：彻底退出后 `adb shell dumpsys power` 无残留 WakeLock；
7. **P1-13 / P2-6**：快速连点两次红心，两次都生效且不卡死（验证 V3 自锁已消除）；
8. **V2**：在线断流时不再出现两条矛盾 Toast / 连续两次重启 / 跳回上一首；
9. **V4**：清理试听缓存后继续播放下一首无 `IllegalStateException`；
10. **V5**：彻底退出→立刻重进→播放，播放/暂停状态与自动续播正常；
11. **V12**：硬解确实损坏的盒子上不会每 3 首断一次；
12. **V14**：TV 更新包能正常调起系统安装器（或给出明确引导）。

---

**报告结束。** 逐条打钩清单见 `docs/FIX_CHECKLIST.md`。
