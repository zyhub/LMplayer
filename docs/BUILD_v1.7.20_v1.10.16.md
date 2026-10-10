# LMPlayer 第三轮修复与出包记录

> 目标：修复剩余待修项 → 各自升小版本号 → **编译 APK 到根目录供测试** → 不推送 GitHub
> 产物：`LMPlayer-v1.7.20.apk`（versionCode 55）、`LMPlayerTV-v1.10.16.apk`（versionCode 63）

---

## 一、出包结果

| 项 | 手机端 | TV 端 |
| :--- | :--- | :--- |
| 版本号 | **1.7.20**（code **55**） | **1.10.16**（code **63**） |
| 包名 | `com.lm.player` | `com.lm.player.tv` |
| 根目录产物 | `LMPlayer-v1.7.20.apk`（8.3 MB） | `LMPlayerTV-v1.10.16.apk`（8.3 MB） |
| 构建命令 | `:app:assembleRelease` | `:app:assembleRelease` |
| 构建结果 | ✅ BUILD SUCCESSFUL | ✅ BUILD SUCCESSFUL |
| 签名 SHA-256 | `DF:D3:21:…:89:2C` | `59:D2:BB:…:EA:34` |
| 签名一致性 | 与 v1.7.19 **完全一致**（debug keystore，沿用既有发布方式） | 与 v1.10.15 **完全一致**（自有 `lmplayer-tv-release.jks`） |

签名一致意味着**可直接覆盖安装**、能正常读取旧版本的数据，无需卸载。

> 同时已同步 `AppUpdateManager` 里的兜底版本常量（手机 1.7.14/49 → 1.7.20/55；TV 1.10.8/55 → 1.10.16/63），
> 消除该常量与 `build.gradle.kts` 长期漂移的问题。

---

## 二、本轮完成的修复

| 编号 | 平台 | 修复内容 |
| :--- | :--- | :--- |
| **U-8** | 手机 | 「定位正在播放歌曲」偏移一行：`headerCount = 3` 漏算了**无条件存在**的歌单区（正确值 5）。已改为具名常量 `LOCAL_LIBRARY_SECTION_*`，并在注释中列出完整 item 顺序 |
| **P1-5** | 双端 | 「已下载」标记与物理文件不一致：把 `isLocalPathValidFast` 拆成**两种语义** —— 列表展示允许信任标记（保主线程流畅），播放路由必须实查磁盘。经核查 `PlaybackRouter` 本来就在播放前做 `File.exists()` 校验，因此本次把语义显式化并写清选择依据（避免后续误用） |
| **U-20** | 手机 | 投屏 `LaunchedEffect` 补 `activeCastDevice?.id` 作为 key（播放中途接入设备会补推当前曲目），并加 try/catch |
| **M-11** | TV | 自实现生命周期感知收集 `collectAsStateLifecycleAware()`（**不引入新依赖**，因离线环境无法拉取 `lifecycle-runtime-compose`），替换 3 处高频流 |
| **V12** | TV | 硬解回升**失败记忆 + 指数退避**（5/10/20/40 分钟）：原实现只看「buildPlayer 是否抛异常」，硬解真坏时每 3 首就 rebuild 一次（每次静音数秒） |
| **V14** | TV | 安装前检查「允许安装未知应用」（`canRequestPackageInstalls`），未授权时给出提示并跳转设置页；补 `ACTION_APPLICATION_DETAILS_SETTINGS` 兜底 |
| **V16** | TV | 歌词浮层状态加 `DisposableEffect { onDispose { onOverlayStateChanged(false) } }`：组件在浮层展开期间被移出组合时，`anyPopupExpanded` 不再永久卡在 true |

---

## 三、仍未修复（10 项）与原因

### 3.1 需要真机遥控 / 大屏回归（无法静态验证）

| 编号 | 内容 | 为什么没做 |
| :--- | :--- | :--- |
| P1-21 | TV 9 处 Dialog 无初始焦点绑定 | 焦点行为**只能真机验证**；改完若拿不到遥控器复验，风险高于收益。建议单独安排一次 TV 焦点专项（连同 P1-22 / U-17） |
| P1-22 | TV 下钻详情焦点丢失、返回不回原卡片 | 同上 |
| P1-24 | TV 设置页 Tab「焦点即切换」触发网络请求 + 全库磁盘校验 | 需重排副作用与缓存策略，且要真机确认切换手感 |
| U-17 | 设置页单 item 内 `forEach` 非惰性渲染 | 需把设置页卡片拆成顶层 `items`，改动面覆盖整页；必须真机验证滚动与 Tab 切换 |

### 3.2 需要结构性改动（含 Room 迁移）

| 编号 | 内容 | 为什么没做 |
| :--- | :--- | :--- |
| P1-8 | 点播路径最多 3 次全表扫描 | 需要给 `songs` 增加归一化列（`normTitle`/`normArtist`）+ 索引，**属数据库结构变更**，必须配套 Migration 与全链路回归。当前 `exportSchema = false`，贸然改表风险高 |
| P1-14 | 手机组合期 IO 与整页重组（剩余部分） | 已修掉其中 4 处磁盘 IO；剩余部分是 `derivedStateOf` 重构与大型 UI 的组合结构调整，需真机验证滚动正确性 |
| TV-C19 | TV 更新包断点续传 | 需实现 Range 续传；当前已有「大小 + SHA-256 + 签名」三道校验，续传只影响弱网体验 |
| TV-C22 | 队列未进播放器 timeline | 会改变现有切歌与 ENDED 语义，必须完整回归（蓝牙 AVRCP / 电视媒体卡片） |
| N-9 | 曲库 `all=1` 一次性读入整棵 JSON 树 | 需改流式解析（`JsonReader`），属协议层重构 |

### 3.3 本轮尝试后回退的

| 编号 | 内容 | 回退原因 |
| :--- | :--- | :--- |
| M-11（手机端） | 手机端同样替换 `collectAsState` | 替换后 `MainActivity` 出现类型推断冲突（`allDownloads` 的 Room Flow 与泛型辅助函数签名不匹配），**已回退以保住可出包状态**。TV 端同类替换编译通过并保留。手机端如需，建议随 P1-14 的组合式重构一起做 |

---

## 四、验证状态

| 项 | 结果 |
| :--- | :--- |
| 手机端 `:app:compileDebugKotlin` | ✅ BUILD SUCCESSFUL（M-11 回退后复验） |
| TV 端 `:app:compileDebugKotlin` | ✅ BUILD SUCCESSFUL |
| 手机端 `:app:assembleRelease` | ✅ BUILD SUCCESSFUL（1m36s） |
| TV 端 `:app:assembleRelease` | ✅ BUILD SUCCESSFUL（4m41s） |
| APK 版本元数据 | 已用 `aapt2 dump badging` 核实：55/1.7.20 与 63/1.10.16 |
| APK 签名 | 已用 `keytool -printcert` 核实与上一版一致 |
| GitHub 推送 | **未推送**（`git log` 仍停在 `d329fbb`，仅工作区改动 31 项） |

---

## 五、测试建议（按优先级）

### 必测（本轮修复的直接目标）

1. **手机端 Android 13+**：「一键全盘扫描」能否扫到本地音乐（P0-1）；
2. **随机播放 + 单曲歌单**：等自然播完不崩（P0-2）；
3. **快速连点两次红心**：两次都生效、界面不卡死（V3 Mutex 自锁已消除）；
4. **TV 长按返回 2 秒**：车机模式能否切回电视并恢复遥控（P0-7）；
5. **TV 歌词调节浮层**：遥控器按键是否落在浮层内（P0-6 + V16）；
6. **TV 睡眠定时**：选「播完当前曲」不再立刻停播（V1）；设 15 分钟确认到点停止；
7. **在线断流**：不再出现两条矛盾 Toast / 连续两次重启（V2）。

### 回归（改动波及面）

8. 清理试听缓存后继续播放下一首，无异常且播放**不中断**（V4）；
9. 彻底退出→立刻重进→播放，播放/暂停状态与自动续播正常（V5）；
10. 资料库「定位正在播放歌曲」是否准确跳到该曲（U-8）；
11. 服务器曲库专辑/歌手数量是否完整（N-10 的 `all=1` 改动）；
12. TV 更新流程：损坏/被替换的安装包被拒绝；正常包能调起安装器或给出明确引导（TV-C10/C11/V14）。

### 已知限制（测试时请留意）

- TV 硬解真坏的盒子上，回升尝试会按 5/10/20/40 分钟退避（V12），首轮仍会有一次约数秒的重建；
- 手机端 `collectAsState` 仍是非生命周期感知版本（M-11 手机端回退），后台收集开销依旧存在；
- 睡眠定时状态仍活在全屏播放页子树内，**收起播放页定时器会失效**（V11，本轮未修）。

---

**记录结束。** 逐条打钩清单见 `docs/FIX_CHECKLIST.md`，二次审计报告见 `docs/CODE_AUDIT_REPORT_ROUND2.md`。
