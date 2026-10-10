# LMPlayer 修复进度与未修改项清单

> 修复范围：手机端 `app/` 与 TV 端 `LMPlayerTV/`
> 约束：仅做编译验证，**不产出 APK、不推送远端**

## 一、编译验证结果（已通过）

| 目标 | 命令 | 结果 |
| :--- | :--- | :--- |
| 手机端 | `gradlew :app:compileDebugKotlin --rerun-tasks` | ✅ **BUILD SUCCESSFUL**（1m42s，全量重编） |
| TV 端 | `gradlew :app:compileDebugKotlin --rerun-tasks` | ✅ **BUILD SUCCESSFUL**（1m43s，全量重编） |

- 环境：JDK 17（`C:/Users/ZHOU/AppData/Local/jdk-17`）+ 项目自带 Gradle 8.7 wrapper
- 只执行 `compileDebugKotlin`（含 KSP/Room 代码生成），**未执行任何 `assemble*`**，因此没有生成 APK
- 说明：PowerShell 里 `$LASTEXITCODE` 会被 javac 的 `-Xmx2048m` 警告污染为 1，判定以 gradle 输出的 `BUILD SUCCESSFUL` 为准

## 二、已完成修复

### P0 致命级（8/8 全部完成）

| 编号 | 平台 | 修复内容 | 文件 |
| :--- | :--- | :--- | :--- |
| P0-1 | 手机 | 声明 `READ_MEDIA_AUDIO` + API≥33 运行时申请（恢复 Android 13+ 本地扫描） | `AndroidManifest.xml`、`MainActivity.kt` |
| P0-2 | 双端 | `random()` 空列表崩溃 → `ifEmpty { list }` + `randomOrNull()` | 双端 `PlaybackQueueManager.kt` |
| P0-3 | 双端 | 破坏性迁移 → `fallbackToDestructiveMigrationOnDowngrade()`（**升级不再清库**） | 双端 `ZdsDatabase.kt` |
| P0-4 | 手机 | `startForeground` 提前到 `onCreate` 首行 + 最小化兜底通知 + 失败落盘可观测 | `PlaybackService.kt` |
| P0-5 | TV | `if (true)` → `if (isLandscape)`；pager 双向同步环按方向收敛 | TV `FullscreenPlayerSheet.kt` |
| P0-6 | TV | `anyPopupExpanded` 补齐全部浮层 + 歌词调节浮层状态上报回调 | TV `FullscreenPlayerSheet.kt`、`LyricsScrollingView.kt` |
| P0-7 | TV | 平台模式行禁用左右键改值 + **长按返回键 2 秒切回电视模式**逃生通道 | TV `SettingsScreen.kt`、`TvFocusModifiers.kt`、`MainActivity.kt` |
| P0-8 | 双端 | 「定时关闭」从空壳变为真正生效（到期停止 + 真实倒计时 + 「播完当前曲」） | 双端 `FullscreenPlayerSheet.kt` |
| TV 8.1 | TV | 换源/重试无限循环：`STATE_ENDED` 按 repeat 与队列长度收敛，单曲/末尾不再回绕 | TV `PlaybackQueueManager.kt` |

### P1 严重级（已完成 22 项）

| 编号 | 平台 | 修复内容 | 文件 |
| :--- | :--- | :--- | :--- |
| P1-1 | 手机 | 新增 `releaseSharedPlayer()` 并在退出路径释放；监听器守卫改弱引用（释放后可自动重挂） | `Media3Factory.kt`、`PlaybackService.kt`、`PlaybackQueueManager.kt` |
| P1-2 | 手机 | 兜底封面 Bitmap 双重检查锁 + `@Volatile` | `DynamicIslandManager.kt` |
| P1-3 | 双端 | 下载目录穿越防护：段净化 + `safeChildFile` 的 `canonicalFile` 前缀校验 | 双端 `DownloadEngine.kt` |
| P1-4 | 双端 | 断点续传校验 `Content-Range` 起始偏移，不匹配即丢弃残片重下 | 双端 `DownloadEngine.kt` |
| P1-6 | 双端 | `MediaMetadataRetriever` 改 `try/finally` 释放（消除 FD 泄漏） | 双端 `LyricsManager.kt` |
| P1-9 | 双端 | Range 探测只接受 206 且限制 512KB（消除整首音频入内存 OOM） | 双端 `LemonMusicProtocol.kt` |
| P1-10 | 双端 | 全工程 `catch` 块注入 `CancellationException` 重抛（协程取消不再被吞） | 9 个文件共 166 处 |
| P1-11 | 手机 | 令牌注入按**主机绑定**（不再向第三方 `/api/` 外发令牌）；令牌字段 `@Volatile` | `NetworkClientFactory.kt`、`LemonMusicProtocol.kt` |
| P1-15 | 手机 | 下钻/扫描/测试连接/脚本加载/清理数据等 loading 补 `try/finally`（消除永久转圈） | `LocalLibraryScreen.kt`、`LemonDiscoverHomeScreen.kt`、`SettingsScreen.kt` |
| P1-17 | 手机 | 媒体键 DOWN/UP 成对消费（一次按键不再双触发切歌） | `MainActivity.kt` |
| P1-18 | 手机 | 边缘右滑返回改 `PointerEventPass.Main` + `isConsumed` 让行 | `MainActivity.kt` |
| P1-23 | 手机 | 进度轮询加 `lifecycle.whenStarted` 门控且不再依赖 `isPlaying`；Room 冷流 `remember` + `distinctUntilChanged` | `MainActivity.kt` |
| M-15 | 手机 | 媒体键防抖改为**按键级**（不同按键不再互相压制） | `MainActivity.kt` |
| P2-2 | 手机 | 压音前记录用户音量，恢复时还原（不再硬置 1.0f） | `PlaybackService.kt` |
| P2-8 | 双端 | `clearStreamCache` 先 release SimpleCache 再删目录 | 双端 `Media3Factory.kt` |
| TV-C2 | TV | `startForeground` 提前到 `onCreate` 首行 | TV `PlaybackService.kt` |
| TV-C4 | TV | 暂停判定由 `isPlaying` 改 `playWhenReady`（缓冲期暂停不再失效） | TV `PlaybackQueueManager.kt` |
| TV-C5 | TV | `STATE_ENDED` 按 repeat + 队列长度收敛（电视不再「永远在响」） | TV `PlaybackQueueManager.kt` |
| TV-C8 | TV | `updateMetadata` 补回「最近播放」元数据合并 | TV `PlaybackQueueManager.kt` |
| TV-C9 | TV | `clearStreamCache` 先 release 再删目录 | TV `Media3Factory.kt` |

## 三、未修改项（按原因分类）

### A. 需架构级改动或大量真机验证（建议单独立项）

| 编号 | 平台 | 内容 | 未修原因 |
| :--- | :--- | :--- | :--- |
| P1-8 | 双端 | 点播路径最多 3 次全表扫描 → 归一化列 + 索引 | 需要 Room 表结构变更（迁移）与全链路回归，属架构级重构 |
| P1-14 | 手机 | `derivedStateOf` 重构 + 组合期磁盘 IO 全部移出组合 | 涉及 4 个大型 UI 文件的组合结构重排，必须真机验证滚动正确性 |
| P1-21 / P1-22 | TV | 9 处 Dialog 初始焦点 + 下钻焦点恢复 | 焦点行为只能真机验证，改动覆盖全部弹窗 |
| T-17 | TV | 引入 `androidx.tv:tv-material` 的 TvLazyRow/TvLazyColumn | 新增依赖 + 全量列表组件替换，需真机焦点回归 |
| TV-C22 | TV | 队列进播放器 timeline / 覆写 hasNext/Previous | 会改变现有切歌与 ENDED 语义，需完整回归 |
| P0-3(余) | 双端 | 开启 `exportSchema = true` 建立迁移基线 | 需生成并入库 schema JSON + `MigrationTestHelper`；当前 version 仍为 4，无迁移需求 |
| 第十一章 | 双端 | 抽取 `:core` 共享模块消除双端漂移 | 工程结构级改造 |
| P2-20 | 双端 | `networkSecurityConfig` 只放行已知域名 | 需先梳理全部第三方音源域名，否则会直接打断在线播放 |

### B. 本轮仅完成手机端，TV 端待同步

| 编号 | 内容 |
| :--- | :--- |
| P1-1 | TV 的 `releaseSharedPlayer()` 接线（TV 播放器有软解重建换绑链路，需更谨慎） |
| P1-2 | TV 兜底 Bitmap 并发保护 |
| P1-11 | TV 令牌主机绑定（TV `LemonMusicProtocol` 结构同源，可照搬） |
| P1-15 | TV 各页面 loading `try/finally` |

### C. TV 端待处理（播放核心与更新链路）

| 编号 | 内容 | 风险 |
| :--- | :--- | :--- |
| TV-C3 | `forceSoftwareDecoding` 置位后进程内不复位，且判定集合含与硬解无关的错误码 | 高（一次误判永久软解） |
| TV-C6 | 周期落盘把新歌名 + 旧歌位置写在一起 | 中 |
| TV-C10 | 更新包零完整性校验（无 size/hash/签名比对，允许 http 接口） | 高（MITM 可下发任意 APK） |
| TV-C11 | `installApk` 只弹 Toast 不安装，更新流程实际不可用 | 高（功能不可用） |
| TV-C12 | `startService` + `stopService` 共用 try，后台抛异常后 stopService 不执行 | 中 |
| TV-C14 | 缺 `isExplicitStopping` 守卫，退出后通知可能被贴回 | 中 |
| TV-C16 | 关机同步落盘契约被 ioExecutor 破坏 | 低 |
| TV-C17 | 换绑不恢复倍速 | 低 |
| TV-C18 | 通知无 MediaStyle/MediaSession 绑定 | 中 |
| TV-C19 / TV-C20 | 更新无断点续传、`hasUpdate` 未校验 downloadUrl | 低 |
| TV-C21 | 队列 JSON 缓存字段非 volatile、非原子成对写 | 低 |
| TV-C23 | `onTaskRemoved` 在解析未落地时误收摊 | 低 |

### D. 有意不修改（改动风险大于收益，现状经复核合理）

| 编号 | 内容 | 决定与理由 |
| :--- | :--- | :--- |
| TV-C7 | TV `setHandleAudioBecomingNoisy(true)` | **有意保留**：代码注释「拔耳机 / 断开车机蓝牙时自动暂停（否则声音切到外放，车内体验很差）」是明确的产品判断；若按手机端改 false 会造成车机外放。如需按平台区分，应先真机确认车机蓝牙通道切换行为 |
| P3-1 | 手机 release 使用 debug 签名 | 需用户提供正式签名密钥；且用户要求不产出安装包 |
| P2-19 | `allowBackup="false"` | 会改变用户既有数据的备份/迁移行为，建议与产品确认后再改 |
| P3-2 / P3-3 | 手机移植 TV 的 `CrashLogger` 与软解降级 | 属新增功能，且 TV 侧本身还有 TV-C3 缺陷待修，建议与 TV-C3 一并处理 |

## 四、修复过程中发现并一并处理的问题

1. **`AudioSharingManager` 无法真正重抛取消**：原代码 `catch (_: Exception)` 内写成 `if (_ is CancellationException) throw _`，而 `_` 不可被引用。批量规范化时已改为具名 catch 并补上正确的重抛。
2. **两处多行 `if (` 条件被批量脚本误伤**：注入取消重抛时按行匹配误删了多行 `if (` 的首行（手机 MainActivity 1 处、TV MainActivity 1 处、双端 BootCompletedReceiver 各 1 处），已在编译阶段发现并全部恢复。
3. **`Media3Factory` 缺 `TAG` 常量**：新增的 `releaseSharedPlayer()` 引用了不存在的 TAG，已补齐。
4. **`PlaybackService` 变量遮蔽**：兜底通知里的局部 `flags` 遮蔽外层 `val flags`，触发「val 不可重新赋值」编译错误，已重命名为 `pendingFlags`。

## 五、后续建议顺序

1. **TV-C3 + TV-C10 + TV-C11**（软解误判 / 更新包校验 / 更新流程不可用）—— 影响面大且相互独立；
2. **TV 端同步手机已修项**（P1-1 / P1-2 / P1-11 / P1-15）—— 工作量小、收益直接；
3. **P1-14 + P1-8**（组合期 IO 与点播全表扫描）—— 体验收益最大，但需要单独的回归计划；
4. **P1-21/P1-22 + T-17**（TV 焦点体系）—— 需要真机遥控回归，建议集中一次做；
5. **P0-3(余) + 第十一章**（schema 基线与 `:core` 抽离）—— 工程治理，建议中长期排期。

## 六、真机验证建议清单

编译通过不等于行为正确，以下改动**必须真机复验**：

1. **P0-1**：Android 13+ 设备上「一键全盘扫描」能否扫到本地音乐；
2. **P0-2**：随机播放 + 单曲歌单，等待自然播完，确认不再崩溃；
3. **P0-7 + 逃生通道**：TV 切到车机模式后，长按返回键 2 秒能否切回电视模式并恢复遥控；
4. **P0-6**：TV 横屏点开「歌词调节」浮层，确认遥控器按键落在浮层内；
5. **P0-5**：TV 横竖屏切换歌词模式，确认无崩溃且状态不互相打架；
6. **P0-8**：设置 15 分钟定时关闭，确认到点真的停止播放且倒计时在走；
7. **P1-1**：彻底退出后确认进程不再持有 WakeLock（可用 adb shell dumpsys power 观察）；
8. **P1-17**：蓝牙/方向盘按一次媒体键，确认只切一首；
9. **P1-18**：在屏幕左缘按住进度条向右拖，确认不会误触发返回；
10. **P1-23**：播放中切后台再回前台确认进度条正常刷新；暂停后拖动进度条确认进度跟随。
