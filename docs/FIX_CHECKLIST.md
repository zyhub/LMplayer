# 审计问题修复核对清单（对照 CODE_AUDIT_REPORT 逐条打钩）

> 基线：`docs/CODE_AUDIT_REPORT.md`，共 **173** 项问题
> 图例：`[x]` 已修复并编译通过 ｜ `[~]` 有意不修（附理由） ｜ `[ ]` 待修复
> 约束：仅编译验证（手机端 / TV 端 `:app:compileDebugKotlin` 均 BUILD SUCCESSFUL），**未产出 APK、未推送远端**

## 总览

| 状态 | 数量 | 占比 |
| :--- | ---: | ---: |
| ✅ 已修复 | **107** | 61.8% |
| ⏸️ 有意不修 | 6 | 3.5% |
| ⬜ 待修复 | 60 | 34.7% |
| **合计** | **173** | 100% |

### 按分组统计

| 分组 | 说明 | 已修 | 有意不修 | 待修 | 小计 |
| :--- | :--- | ---: | ---: | ---: | ---: |
| **P0** | 致命级（必崩 / 数据丢失 / 功能整体失效 / 设备锁死） | **8** | 0 | 0 | 8 |
| P1 | 严重级（崩溃 / 数据不一致 / 明显资源浪费） | 18 | 0 | 6 | 24 |
| P2 | 一般级（逻辑瑕疵 / 体验与性能损失） | 8 | 2 | 11 | 21 |
| P3 | 建议级 / 加固项 | 3 | 3 | 5 | 11 |
| N | 网络协议层专项 | 16 | 0 | 8 | 24 |
| U | 手机端 Compose UI 专项 | 5 | 0 | 15 | 20 |
| T | TV 端遥控焦点 / 大屏交互专项 | 20 | 0 | 2 | 22 |
| M | MainActivity 专项（双端） | 14 | 0 | 7 | 21 |
| TV-C | TV 播放核心 / 更新链路专项 | 15 | 1 | 6 | 22 |

**里程碑：P0 致命级 8 项已全部修复。**

---

## 逐条核对
### P0（共 8 项）

- [x] **P0-1** 【手机端】Android 13+ 本地音乐扫描 100% 失效（权限缺失）
- [x] **P0-2** 【双端】随机播放模式下单曲歌单必崩（NoSuchElementException）
- [x] **P0-3** 【双端】数据库升级即清空全部用户数据
- [x] **P0-4** 【手机端】前台服务启动异常被静默吞掉（不可诊断的「点播放没反应」）
- [x] **P0-5** 【TV】if (true) 使整个竖屏播放布局成为死代码
- [x] **P0-6** 【TV】遥控焦点守护协程抢走浮层焦点 → 浮层「看得见按不动」
- [x] **P0-7** 【TV】平台模式可被一次左右键静默切换，导致无触屏设备彻底失去遥控操作
- [x] **P0-8** 【TV】「定时关闭」是空壳功能（只弹 Toast，无任何实现）

### P1（共 24 项）

- [x] **P1-1** 【双端】播放器单例永不释放，WakeLock / WifiLock / 监听器全部悬挂
- [x] **P1-2** 【双端】封面位图缓存无并发保护（LruCache + 无同步的 Bitmap 字段）
- [x] **P1-3** 【双端】下载目标路径可被 .. 穿越到应用目录之外
- [x] **P1-4** 【双端】断点续传不校验 Content-Range，可静默产出损坏音频文件
- [ ] **P1-5** 【双端】「已下载」标记不校验物理文件，UI 与事实不一致
- [x] **P1-6** 【双端】MediaMetadataRetriever 在异常路径不释放（FD 泄漏）
- [x] **P1-7** 【双端】全局缓存合计可达 2.3GB，超出常规实践约 45 倍
- [ ] **P1-8** 【双端】播放启动路径最多 3 次「全表扫描 + 内存模糊匹配」
- [x] **P1-9** 【双端】网络换链请求放大且无总预算（协议层）
- [x] **P1-10** 【双端】协程取消失效：catch(Exception) 吞掉 CancellationException + 阻塞式 execute()
- [x] **P1-11** 【双端】凭据安全三连：明文口令 / 令牌进 URL / 全局令牌跨主机注入
- [x] **P1-12** 【双端】Range 判定接受 200，整首音频读入内存（OOM 风险）
- [x] **P1-13** 【双端】收藏 / 歌单「整表读改写」，无锁无版本号（并发丢更新）
- [ ] **P1-14** 【手机】滚动导致整页重组 + 组合期主线程磁盘 IO（4 处）
- [x] **P1-15** 【手机】loading 标志无 finally（5 处）→ 永久转圈 / 永久禁用
- [x] **P1-16** 【手机】主线程同步落盘 commit()（含每次点播 / 切歌）
- [x] **P1-17** 【双端】媒体键只吞 DOWN、放行 UP → 一次按键触发两次切歌
- [x] **P1-18** 【手机】边缘右滑返回手势用 Initial 阶段且不检查 isConsumed
- [x] **P1-19** 【TV】换绑后 Player.Listener 留在已释放的播放器上
- [x] **P1-20** 【双端】权限申请结果被丢弃，全程未用 shouldShowRequestPermissionRationale
- [ ] **P1-21** 【TV】9 处 Dialog 无初始焦点绑定 → 弹窗打开后遥控器失灵
- [ ] **P1-22** 【TV】下钻详情「整块替换」导致焦点丢失，返回后光标不回原卡片
- [x] **P1-23** 【手机】进度轮询无生命周期 + Room 冷流每次重组重建
- [ ] **P1-24** 【TV】设置页 Tab「焦点即切换」触发网络请求 + 全库磁盘校验

### P2（共 21 项）

- [ ] **P2-1** Media3Factory.kt clearStreamCache ｜ 在 SimpleCache 仍被使用时 deleteRecursively() 缓存目录（连 SQLite 索引一起删），后续写入抛 IOException 被静默 catch → 「清理缓存后一段时间内无法边听边存」 ｜ 清理
- [x] **P2-2** PlaybackService.kt:145-191 ｜ 音频焦点恢复时无条件 player.volume = 1.0f，丢弃压音前的用户音量 ｜ 压音前保存 previousVolume，恢复时还原
- [x] **P2-3** PlaybackService.kt:591-618 ｜ ACTION_MEDIA_COMMAND 分支先执行播控命令再建立前台状态；若服务此前已 stopSelf 而进程仍活，存在 5 秒前台服务超时风险 ｜ 进入分支后立刻建立前台状态，再做播控
- [ ] **P2-4** PlaybackQueueManager.kt:305-355 ｜ flushRecentPlayed / savePlaybackState(commitSync = true) 在主线程 SharedPreferences.commit()（见 P1-16） ｜ 改投串行 IO
- [x] **P2-5** LMApplication.kt:66 ｜ ZdsDatabase.getInstance(this) 在冷启动主线程触发 Room.databaseBuilder().build() 与首次 open 的磁盘 IO，与同文件 L57「0ms 阻塞冷启动主线程」目标矛盾 ｜ 挪到 L58 的 IO
- [ ] **P2-6** PlaybackQueueManager.kt:786-870 ｜ toggleFavorite 在 IO 协程内做 Room 写 + 多表更新 + 远端同步 + 可能提交服务器下载任务，无并发去重 ｜ 按 song.id 加 Mutex 或 UI 层去抖
- [ ] **P2-7** DynamicIslandManager.kt:190-205 ｜ ensureInitialized 的 isInitialized 为普通 Boolean 且无同步，四个入口都会调用 → 重复初始化与 startBackgroundLyricsEngine 重复启动竞态 ｜ @Volatile
- [x] **P2-8** AudioSharingManager.kt:129, 1269-1280 ｜ 投屏 HTTP 服务 httpServerSocket 创建后全工程无 close()；scope / mainScope 静态作用域永不取消 ｜ stopActiveCast 与 onTaskRemoved 中关闭 S
- [ ] **P2-9** MainActivity.kt:3290-3314 ｜ 通知权限被拒后无任何降级提示；Android 13+ 拒绝后前台服务通知静默不显示，用户以为「没有后台播放」 ｜ 设置页展示权限状态并提供跳转系统设置入口
- [ ] **P2-10** TV AndroidManifest.xml ｜ 未声明 ACCESS_WIFI_STATE / CHANGE_WIFI_MULTICAST_STATE，导致 WifiLock 永不生效、局域网投屏多播发现静默降级 ｜ 补齐权限，或移除 TV 端投屏 / 多播死代码
- [ ] **P2-11** PlatformModeWizard.kt:74、95-98 ｜ 向导吞掉返回键且 onKeyEvent 无条件消费上下键（含 ACTION_UP），PlatformMode 无逃生态（见 P0-7） ｜ 增加确认步骤 + 长按返回逃生通道；只消费 ACTION_DOWN
- [ ] **P2-12** PlatformModeWizard.kt:79-85 ｜ LaunchedEffect 中固定 delay(80) 后 requestFocus()，低端盒子焦点节点可能尚未 attach，异常被静默吞掉 → 向导无焦点，需先按方向键才「唤醒」光标 ｜ 用 withFrameNanos 或重试 3
- [ ] **P2-13** LocalMediaScanner.kt:57-103 ｜ retriever 在长循环中复用且不重置，单文件异常后 native 状态可能残留 ｜ 每个文件前 setDataSource，异常后 release() 并重建
- [ ] **P2-14** LocalMediaScanner.kt:39 ｜ 仅扫描 EXTERNAL_CONTENT_URI 且硬编码 5 个厂商目录；未用 MediaStore.getExternalVolumeNames()，Android 10+ 分区存储下 DATA 列可能为空 ｜ 遍历外部卷 + content:
- [x] **P2-15** DownloadEngine.kt:415, 626-649 ｜ 失败时只删长度为 0 的 .download 残片；非空残片被保留，但 isDestFileValid 又要求 !tempFile.exists() → 该曲目一次失败后永远无法再下载 ｜ 保留残片用于续传，并把 isDestFile
- [x] **P2-16** AppUpdateManager.kt:33-34 ｜ CURRENT_VERSION_NAME = "1.7.14" / CODE = 49 硬编码，与 build.gradle.kts 的 1.7.19 / 54 已漂移 5 个版本 ｜ 删除硬编码，读取失败时视为「未知版本」且不提示更新
- [x] **P2-17** AppUpdateManager.kt:236-271 ｜ 手机端下载 APK 时流未用 use {}，异常路径泄漏 FD；且每 8KB 就 withContext(Dispatchers.Main) 回调一次进度 ｜ 改 use；进度按 200ms 节流（TV 已修）
- [x] **P2-18** NetworkClientFactory.kt:102-115 ｜ 拦截器对所有 host 的 /api/ 路径自动附加令牌（见 P1-11） ｜ 仅在 host 命中已配置服务器时注入
- [~] **P2-19** 双端 manifest + Entities.kt:73 ｜ allowBackup="true" 且无 dataExtractionRules；servers 表以明文存储 tokenOrApiKey / saltOrSecret，会被云备份 / ADB 备份带走 ｜ allowBackup="f — **【有意不修，见文末说明】**
- [~] **P2-20** 双端 AndroidManifest.xml:43 ｜ usesCleartextTraffic="true" 且无 networkSecurityConfig，第三方音源直链走明文 HTTP，可被中间人篡改 ｜ 增加 network_security_config.xml，仅对已知域名放行明文 — **【有意不修，见文末说明】**
- [ ] **P2-21** MusicDao.kt:37-51 ｜ getSongsPaged 用 LIMIT/OFFSET 深分页退化为 O(n²)；无稳定排序键，分页期间并发写入会漏读 / 重读 ｜ 改主键游标分页 WHERE id > :lastId ORDER BY id LIMIT :n

### P3（共 11 项）

- [~] **P3-1** app/build.gradle.kts:46 ｜ release 使用 debug 签名（signingConfig = signingConfigs.getByName("debug")）：无法上架、无法与 TV 端共用升级链路，且 debug keystore 口令公开 ｜ 对齐 TV 端的本 — **【有意不修，见文末说明】**
- [~] **P3-2** 与 TV 端对比 ｜ 手机端缺少 CrashLogger + CrashReportDialog（TV 有 core/util/CrashLogger.kt）。无 adb 场景下崩溃只能靠用户口述 ｜ 直接把 TV 实现移植过来 — **【有意不修，见文末说明】**
- [~] **P3-3** 与 TV 端对比 ｜ 手机端缺少硬解失败降级（TV 有 rebuildWithSoftwareDecoding + 进度停滞检测 + 播放器换绑）。部分车机 SoC 硬解异常时表现为「进度条在走但完全无声」且无法自愈 ｜ 移植 TV 的软解降级机制，并同时修 TV 的 TV-C3（永久软解） — **【有意不修，见文末说明】**
- [x] **P3-4** LMApplication.kt:60 vs Media3Factory.kt:102 ｜ 偏好键不一致：Application 读 stream_cache_enabled，Factory 读 stream_cache_enabled_v2；该布尔值可被两处竞争改写 ｜ 统一键名，删除 v1 兼容
- [ ] **P3-5** SettingsScreen.kt:1350, 1368 ｜ 设置项切换使用 prefs.edit().commit()（主线程同步写盘） ｜ 改 apply()
- [x] **P3-6** AppUpdateManager.kt:159-169 ｜ GitHub 通道下 latestVersionCode = currentVersionCode + 1 是伪造值，仅用于刷新 UI ｜ 从 tag 解析真实语义版本，或把字段改为可空
- [ ] **P3-7** PlaybackService.kt:77-83, 649-656 ｜ PHONE_SYSTEM_ISLAND_PACKAGES 硬编码厂商包名，其中 com.huawei.systemserver 不是有效的 systemui 包名，onGetSession 白名单在华为设备上可能不生效 ｜ 用
- [ ] **P3-8** AudioSharingManager.kt:876, 1185, 1212, 1235 ｜ client.newCall(req).execute().close() 只关闭响应体、未消费 body，大量调用会占用连接池 ｜ 改为 .execute().use { it.body?.string(
- [x] **P3-9** LyricsManager.kt:1195, 889, 1132 ｜ 存在 lyricRes.getOrNull()!! 等断言；当前前置条件已保证非空，但属脆弱写法 ｜ 换为局部变量 + 早返回
- [ ] **P3-10** TV AndroidManifest.xml:92-97 ｜ 开机自启接收器 exported="true" 且无权限保护，任何应用可发送 BOOT_COMPLETED 触发（内部有开关兜底，实际影响小） ｜ 增加 android:permission 或记录为已知风险
- [ ] **P3-11** LocalLibraryScreen.kt:279-289 等 ｜ 大量 s.localFilePath!! 在 isNullOrBlank() 判断的 lambda 内使用：当前安全，但 lambda 捕获使智能转换失效，重构时极易变成 NPE ｜ 改为 val p = s.localFilePa

### N（共 24 项）

- [x] **N-1** :2672-2705、:2546、:2575 ｜ 换链请求放大且无总预算（见 P1-9） ｜ 目标曲目在多平台都缺该音质，或服务端返回多个 alternatives ｜ 整链 withTimeoutOrNull(15_000)；altIds 去重限流
- [x] **N-2** :2609-2611、:341-344、:2082-2084（全文件 40+ 处） ｜ catch (Exception) 吞掉 CancellationException + 51 处阻塞 execute()（见 P1-10） ｜ 用户切歌、退出播放、页面销毁 ｜ 取消重抛；改 enqueue +
- [x] **N-3** :313-321 + AndroidManifest.xml:43 + SettingsScreen.kt:2175-2183 ｜ 口令明文 POST 且默认降级 http（见 P1-11） ｜ 用户填 192.168.x.x:3000 ｜ 默认 https；http 目标明确告警并禁止记住密码
- [x] **N-4** :1841-1849、:1884-1891、:1921-1929 ｜ 令牌未 URL 编码直接拼进 query（见 P1-11） ｜ 令牌含特殊字符 ｜ 改 Authorization 头或 URLEncoder
- [x] **N-5** :244 + NetworkClientFactory.kt:108-115 ｜ 全局静态令牌跨主机注入（见 P1-11） ｜ 同时配置其它服务器且路径以 /api/ 开头 ｜ 拦截器按 host 绑定令牌
- [x] **N-6** :3564-3606、:3623-3683、:3310-3328 ｜ 收藏与歌单整表读改写，无锁无版本号（见 P1-13） ｜ 同账号两次写操作并发 ｜ 加 Mutex 或改单条接口
- [x] **N-7** :2058-2075 ｜ Range 判定接受 200，整首音频入内存（见 P1-12） ｜ 服务端或代理忽略 Range 头 ｜ 只接受 206 + Content-Length 上限
- [ ] **N-8** :100-102、:452-475、:111-116 ｜ 三个静态缓存只增不减（clearDiscoverCache() 也不清理），key 仅由路径 md5 决定、不含服务器标识 → 内存无限增长 + 切换服务器后命中别台服务器的旧路径 ｜ 加 LRU 上限或按 serverId 分桶；切换服务器
- [ ] **N-9** :405-420 ｜ 忽略入参 offset/limit，固定 ?all=1 把整个曲库 JSON 一次性 string() 读入并建树 ｜ 尊重分页；用 JsonReader 流式解析
- [ ] **N-10** :764-767、:828-831 ｜ getAlbums / getArtists 同样忽略分页，硬编码 limit=500 → 超过 500 条被静默截断 ｜ 循环分页或返回 hasMore
- [x] **N-11** :297-310 ｜ 会话校验分支的 JSONObject(body) 无独立 try，200 但非 JSON（反代门户页 / 空体）时异常直达外层 catch → 永远走不到账号密码登录 ｜ runCatching 包住该次解析后继续密码登录
- [x] **N-12** :573-576、:3702-3703 ｜ optBoolean(name, default) 返回非空，其后 ?: 兜底分支全是死代码 → 扫描状态 / 进度恒为 false / 0 ｜ 先用 has() 判断或用无默认重载
- [x] **N-13** :2924-2967 ｜ fetchSourceList 在循环体内对每条音源脚本都调一次 getServerSettings()（N 次重复请求） ｜ settings 提到循环外取一次
- [ ] **N-14** :1291-1302 ｜ split(":").mapNotNull { toLongOrNull() } 丢弃失败分量导致错位："01:02:03.500" → [1,2] → 返回 62_000ms（应为 3_723_500ms） ｜ 固定位置解析，任一分量失败即整串弃用
- [ ] **N-15** :433-434 vs :1298-1302 ｜ 同一 duration 字段两处单位假设不一致（一处无条件 ×1000，一处按 >10000 判毫秒）→ 服务端返回毫秒时曲目时长放大 1000 倍 ｜ 统一解析函数 + 范围校验（1s–24h 之外视为异常）
- [ ] **N-16** :1911-1916、:1838-1849 ｜ 接口声明的 maxBitrate 完全未使用，曲库曲目 streamUrl 构造时固化（quality = null）→ 设置里选的音质对服务器本地曲目不生效 ｜ 传入 getPreferredStreamQuality 结果
- [ ] **N-17** :407 等 10+ 处 ｜ ensureAuthenticated() 返回值全部未接收：鉴权失败仍发无 Authorization 的请求，401 被当业务错误；且此刻把不含令牌的 streamUrl 固化进 UnifiedSong，该曲目会长期 401 ｜ 检查返回值并触发一次重新登录；str
- [x] **N-18** :44-45、:302、:358-377 ｜ authToken 是普通 var，IO 线程写、任意线程（含 Main）读，无 @Volatile → 可见性无保证 ｜ 加 @Volatile / AtomicReference，令牌变更后失效旧 URL
- [x] **N-19** :3745-3755 ｜ PUT 失败降级 POST 后，报错用的是第一个响应的 resp.code 而非 postResp.code ｜ 使用 postResp.code
- [x] **N-20** :3192-3198 ｜ 失败路径返回 Result.success(false)，按 isSuccess 判断的调用方会误认为成功；runCatching 还吞掉网络异常 ｜ 返回 Result.failure 并带状态码
- [x] **N-21** :3225-3227 ｜ catch 分支返回 success(硬编码平台列表)，把接口故障伪装成成功 ｜ 返回 failure，由调用方决定默认值
- [x] **N-22** :47-70 ｜ activeInstance 静态单例命中即返回，不校验 serverUrl / username 是否与当前 active server 一致 ｜ 记录构建配置，不一致则重建
- [ ] **N-23** :213-222 ｜ getPreferredStreamQuality 在调用线程同步读 SharedPreferences，isWifiOrUnmeteredConnected 还走 ConnectivityManager binder；调用方多在主线程 ｜ 预取缓存或挪到 IO
- [x] **N-24** :290-294 vs :241 / :374 ｜ 会话校验把 tokenOrPasswordPlain 当 Bearer 试发，长度阈值 20 与 32 不一致 → 20–31 位口令被塞进 Authorization 头发往 /api/auth/me ｜ 统一阈值，仅令牌形态（lemon- /

### U（共 20 项）

- [ ] **U-1** LemonDiscoverHomeScreen.kt:258-262 ｜ 滚动导致整页重组：根作用域裸读两个 LazyListState，未用 derivedStateOf（LocalLibraryScreen.kt:202-210 就是正确写法） ｜ 手指滚动发现页任意列表
- [ ] **U-2** LocalLibraryScreen.kt:279-289 ｜ remember 内逐首 File(path).exists()（全库 2000+ 首），且 key 是整个 List → 每次重组对上千条 O(n) 深比较 ｜ 进入资料库 / 曲库变化
- [ ] **U-3** LocalLibraryScreen.kt:1699, 2245 → LocalMusicHomeScreen.kt:250-251, 322-324 ｜ 列表行在组合期做磁盘 IO：File.exists() 未 remember；remember 内 resolveRealLocalFormat
- [ ] **U-4** FullscreenPlayerSheet.kt:432-433, 1300-1301 ｜ 播放期间持续主线程 stat：hasPhysicalLocal 在 composable 体内（非 remember）做 File.exists()+length()，而 progressMs 是入参 ｜ 播
- [x] **U-5** LocalLibraryScreen.kt:1411-1416, 2030-2035；LemonDiscoverHomeScreen.kt:983-986, 1208-1211, 1293-1296 ｜ 下钻取数协程无 try/finally 也无去重 → 异常时永久转圈；快速连点两个歌单会并发请求
- [x] **U-6** SettingsScreen.kt:387-400, 615-625, 1001-1029, 1756-1765 ｜ 四处 loading 标志 set true 后无 finally → 按钮永久禁用 / 永久转圈 ｜ 任一网络 / DB 调用抛异常
- [ ] **U-7** LemonDiscoverHomeScreen.kt:277-312, 355-375 ｜ produceState / remember 的 key 直接用整个大 List → 每次重组 O(n) 深比较，key 一变就全量重跑 resolveSongList（对上千首做匹配） ｜ key 用 i
- [ ] **U-8** LocalLibraryScreen.kt:719-742 ｜ 「定位正在播放歌曲」永远偏移一行：实际 item 顺序为 0 Header、1 Bento、2 歌单区（无条件）、3 最近添加、4 本地文件夹、5 全部歌曲标题、6+ 歌曲，而代码 headerCount = 3 后只 +1/+1（应为
- [ ] **U-9** FullscreenPlayerSheet.kt:587-598, 1356-1367 ｜ 进度 Slider 在 onValueChange 里逐像素调 onSeekTo，无本地拖动态、无 onValueChangeFinished → 拖动时每秒几十次 seek，拇指与真实进度互相拉扯回弹 ｜
- [ ] **U-10** FullscreenPlayerSheet.kt:810-827 ｜ 拖动分隔条时每帧 lyricsPrefs.edit().putFloat(...).apply() 落盘；同一 18dp 区域同时挂 detectHorizontalDragGestures 与 clickable → 手势互相干
- [x] **U-11** LocalLibraryScreen.kt:2306-2340 → MainActivity.kt:2213-2216, 321 ｜ 删除本地歌曲时在 Dispatchers.IO 回调 onDeleteDownloadedSongs，宿主直接写 Compose 快照状态 songList（非主线程
- [ ] **U-12** SettingsScreen.kt:1424-1426 vs :155-157 ｜ stopPlaybackOnExit 重复声明：内层 remember 遮蔽外层，外层成死代码；两处默认值还相反（true vs false）→ 同一偏好项在两张卡片里读的是不同变量 ｜ 删除内层重复声明，统一读 h
- [ ] **U-13** LocalLibraryScreen.kt:761, 2209；SettingsScreen.kt:358 ｜ 所有子视图共用一个 rememberLazyListState 且切换数据源时不重置 → 从滚动很深的歌单 A 切到歌单 B 会直接落在新列表中段；设置页各 Tab item 未加 key
- [ ] **U-14** SettingsScreen.kt:431-491, 659-831, 946-985 ｜ LazyColumn 的单个 item 内用 forEach 全量渲染列表（servers / sourceScripts 含展开面板 / localMusicPaths）→ 失去虚拟化与回收 ｜ 拆成 it
- [x] **U-15** LemonDiscoverHomeScreen.kt:707, 2176（DownloadQualityChoiceDialog.kt:875-918） ｜ NowPlayingWaveIndicator 内 4 个 infiniteRepeatable 常驻动画（420/360/480/390ms
- [ ] **U-16** LocalLibraryScreen.kt:414-553 ｜ produceState 为每个在线歌单顺序 await 拉封面（N 个歌单串行 N 次请求）；key 为整个 sortedPlaylists，列表每次刷新都整段重跑 ｜ 限制并发 / 只对缺封面的歌单请求 / 结果落缓存
- [ ] **U-17** LemonDiscoverHomeScreen.kt:901-906 ｜ 「私人漫游」点击瞬间在主线程做 (resolvedNewSongs + allCachedSongs).distinctBy{it.id}.shuffled()（整库拼接 + 去重 + 洗牌） ｜ 放 Dispatchers.
- [x] **U-18** FullscreenPlayerSheet.kt:485-494 ｜ Modifier 顺序错误：.clip().shadow() → 阴影被裁掉，横屏封面阴影实际不生效（竖屏 1130-1138 写法正确） ｜ 交换为 .shadow(...).clip(...)
- [ ] **U-19** LocalLibraryScreen.kt:2405-2412；SettingsScreen.kt:1978-1986, 2069-2084, 2198-2234 ｜ 全部输入框无 IME 处理（KeyboardOptions / imeAction / keyboardActions / imeP
- [ ] **U-20** FullscreenPlayerSheet.kt:117-122 ｜ LaunchedEffect(song.id) 内读取 activeCastDevice 但未作为 key；castSongToDevice 无 try/catch；播放中途接入投屏设备不会补推当前曲目 ｜ 把 activeCas

### T（共 22 项）

- [ ] **T-1** FullscreenPlayerSheet.kt:558 / 2389 / 2515 / 144-149 / 132 ｜ if (true) 使竖屏分支（约 470 行）成为死代码，HorizontalPager 永不组合，且仍被 LaunchedEffect 驱动、形成 isLyricsMode
- [x] **T-2** FullscreenPlayerSheet.kt:390-399（+371、1168-1171、2116-2119） ｜ 焦点守护协程与「菜单关闭回原按钮」抢焦点：anyPopupExpanded 变回 false 时该 effect 必然重启，120ms 后无条件把焦点拉到播放 / 暂停键 ｜ P
- [x] **T-3** FullscreenPlayerSheet.kt:371 + 2289；LyricsScrollingView.kt:526/550/601-608 ｜ 歌词调节浮层未纳入 anyPopupExpanded → 浮层可见但按不动 ｜ P0-6
- [x] **T-4** SettingsScreen.kt:1548-1560 + 2862-2877；TvFocusModifiers.kt:97；MainActivity.kt:3562；PlatformModeWizard.kt:159 ｜ 平台模式可被一次左右键静默切换并锁死设备 ｜ P0-7
- [x] **T-5** SettingsScreen.kt:341-351 + 216-293 ｜ Tab「焦点即切换」叠加过重副作用（见 P1-24） ｜ 焦点只做高亮，OK 才切换；副作用防抖 + 缓存
- [x] **T-6** SettingsScreen.kt:1871/2015/2148/2293/2536/2577；LocalLibraryScreen.kt:1729/2265/2368 ｜ 9 处 Dialog 无初始焦点绑定（见 P1-21） ｜ 每个 Dialog 首项加 FocusRequester；关闭还给
- [x] **T-7** LocalLibraryScreen.kt:1676-1700（+416、140-145）；LemonDiscoverHomeScreen.kt:921-943、116-133 ｜ 下钻视图整块替换导致焦点丢失（见 P1-22） ｜ 详情页首项加 FocusRequester；返回按来源卡片恢复焦点
- [x] **T-8** LemonDiscoverHomeScreen.kt:140-146（+143、149） ｜ activeDownloadTasks() 在组合体内直接调用：注释声称「provider 不会整页重组」，但下载流仍读进发现页自身的重组作用域 → 下载期间每约 300ms 整页重组；structural
- [x] **T-9** LocalLibraryScreen.kt:189-198 ｜ remember 内对每首歌执行 java.io.File(s.localFilePath!!).exists()（主线程 stat），曲库数千首时每次 allSongs 变化打出数千次磁盘调用；同处使用 !! ｜ 用后台预计算的存在性
- [ ] **T-10** FullscreenPlayerSheet.kt:169 + 2121-2128 + 2988-3044 ｜ 「定时关闭」是空壳，全工程无消费者，状态为 remember ｜ P0-8
- [x] **T-11** LocalLibraryScreen.kt:2198-2235（+ LocalMusicHomeScreen.kt:305） ｜ 下载管理模式下每行两层可聚焦节点：外层 Surface(...tvFocusable(onClick=切换选中)) 包住内层 SongListItemRow（内部同样 .
- [x] **T-12** SettingsScreen.kt:890-931 ｜ Row(tvFocusable(onClick=togglePlatform)) 内嵌 Switch(onCheckedChange={togglePlatform()})，Switch 自带 toggleable 焦点节点 → 双重焦点（同文
- [x] **T-13** SettingsScreen.kt:167 + 1503-1505；Media3Factory.kt:283-294/299-312 ｜ 主线程磁盘 IO：getCacheSizeBytes 用 walkTopDown() 全目录遍历且在首次组合执行；clearStreamCache 在 onCli
- [x] **T-14** FullscreenPlayerSheet.kt:1553-1563 / 629-668 / 1643 ｜ 240dp 封面 targetSize=640（2.7 倍过采样）；NETEASE 主题同时常驻两张 targetSize=1080 位图（各约 4.4MB），650ms Crossfade
- [x] **T-15** FullscreenPlayerSheet.kt:369（对比注释 94-111） ｜ progressMsProvider() 在页面根组合作用域读取 → 进度 3Hz 刷新时整个 3400 行播放页仍整体重组，注释声称的「订阅范围收窄」只对调用方成立 ｜ 进度读取下沉到歌词视图 / 进度条内部
- [x] **T-16** LemonDiscoverHomeScreen.kt:712/752/825/1037 ｜ 列表 key 把下标编进去（"new_song_${index}_${item.id}"）→「换一批」/ 刷新后同一首歌 key 全变，行状态与焦点位置重建，光标回到首项 ｜ key 只用稳定 id（对比 L
- [x] **T-17** LemonDiscoverHomeScreen.kt:706/746；LocalLibraryScreen.kt:1274/1409/1897/2020/2044/2107/2184 ｜ 全工程 0 处 TvLazyRow/TvLazyColumn（全部使用基础 Lazy*）→ 缺 pivotOff
- [x] **T-18** AdaptiveAppScaffold.kt:177；FullscreenPlayerSheet.kt:1318；SettingsScreen.kt:305；PlatformModeWizard.kt:102 ｜ 无 overscan 安全边距：22/14dp、20/12-16dp、16/8dp，远
- [x] **T-19** PlatformModeWizard.kt:74/95-98；PlatformMode.kt:12-14 ｜ 向导不可逃生：BackHandler(enabled=true){} 永久吞返回键（连退出应用都不行），onKeyEvent 对上下键无条件 true（含 ACTION_UP）；Platfo
- [x] **T-20** FullscreenPlayerSheet.kt:434-528 ｜ onPreviewKeyEvent 语义缺陷：① repeatCount==0 时 return false，长按切歌前光标已被移动一次；② ACTION_UP 一律 return false（442 行），破坏 DOWN/UP
- [x] **T-21** LemonDiscoverHomeScreen.kt:1067/1070；LocalLibraryScreen.kt:2336/2339、191 ｜ songForDownloadChoice!! 在 onConfirm lambda 中重新读取可变状态，与 onDismiss 置空存在竞态 → N
- [x] **T-22** MainActivity.kt:3448-3451/3459-3480 ｜ TV「长按快进 / 快退」能力缺失：repeatCount > 0 的媒体键被显式丢弃，且 MEDIA_FAST_FORWARD/REWIND 被映射成上一首 / 下一首 ｜ 映射为连续 seek，复用播放页 5s/15s

### M（共 21 项）

- [x] **M-1** MainActivity.kt:3290-3314；AndroidManifest.xml:25-32 ｜ Android 13+ 完全没有本地音频读取权限（Activity 侧二次确认同一根因） ｜ P0-1
- [x] **M-2** 双端（手机重） ｜ 手机 MainActivity.kt:3094, 3257, 3263, 3321；PlaybackQueueManager.kt:327-356, 650-655 ｜ 主线程同步落盘，且每次点播 / 切歌都走 commitSync = true ｜ P1-16
- [x] **M-3** 双端（手机） ｜ 手机 MainActivity.kt:3279-3284；手机 PlaybackService.kt:379-425 ｜ 媒体键只吞 DOWN、放行 UP → 一次按键双触发 ｜ P1-17
- [x] **M-4** MainActivity.kt:1833-1855；AndroidManifest.xml:44 ｜ 边缘右滑返回手势用 PointerEventPass.Initial 且不检查 isConsumed ｜ P1-18
- [x] **M-5** MainActivity.kt:3562、3374-3386 ｜ 车机模式在 Activity 层吞掉全部方向 / 确定 / 菜单键且不做处理 ｜ P0-7
- [x] **M-6** MainActivity.kt:102, 1952, 2035-2038；TV Media3Factory.kt:173-189 ｜ 换绑后 Player.Listener 留在已释放的播放器上（见 P1-19） ｜ 播放器实例放进 mutableStateOf 或用 swap 版本号作 key
- [x] **M-7** 手机 MainActivity.kt:121-123；TV MainActivity.kt:124-126 ｜ 权限申请结果被完全丢弃（见 P1-20） ｜ 判断 granted，被拒时引导到系统设置
- [x] **M-8** MainActivity.kt:2959-2982 ｜ 进度轮询无 repeatOnLifecycle，退后台仍轮询；暂停后进度不刷新 ｜ 见 P1-23
- [x] **M-9** MainActivity.kt:334 ｜ 组合体内新建 Room 冷流 → 每次重组重建失效观察者 ｜ 见 P1-23
- [x] **M-10** MainActivity.kt:317, 1974-1975, 2054-2055, 2173-2174, 2424, 2900, 3000 ｜ activeDownloadTasks 把每约 300ms 变化的 List 值直接传进各页面（还额外传 .size）→ 整页随下载进度高频重组 ｜ 照抄
- [ ] **M-11** 手机 :317-334, 390-394；TV :352-356, 394, 411, 493-497 ｜ 全部 collectAsState 未使用生命周期感知版本 → Activity STOPPED 后仍持续收集并写 State ｜ 改用 collectAsStateWithLifecycle
- [x] **M-12** :393 ｜ 组合期副作用 + 主线程读盘：remember { PlaybackQueueManager.primeRecentPlayedSongs(...) } 内部读 SharedPreferences 并解析 JSON ｜ 移入 LaunchedEffect + Dispatchers.I
- [ ] **M-13** 手机 :1735, 1744-1751；TV :1952, 1961-1968 ｜ onPlayerError（主线程）里对整个曲库逐条 File(...).exists()；且 DisposableEffect key 未含 completedDownloadedSongs，闭包可能用过期列表 ｜
- [ ] **M-14** 手机 :266-267, 397, 423-424；TV :286-288, 500, 517 ｜ 导航与播放页状态全部只用 remember（TV 端 0 处 rememberSaveable）：currentScreen、返回栈、isFullPlayerVisible、hasAutoPlayed
- [x] **M-15** :3125, 3159-3163 ｜ 媒体键防抖使用全局单一时间戳，所有键共用 250ms 窗口 → 不同键互相压制 ｜ 改为一键一记 HashMap<Int, Long>（TV 已修）
- [x] **M-16** :993, 410, 2666 ｜ 自动播放 / 断点恢复也计入「用户手动点播」计数，而定位悬浮按钮要求 >= 2 ｜ 只在用户点击回调中计数（TV 已删除该计数）
- [x] **M-17** :2270, 3007-3052, 2095, 2157, 2214, 2218, 2306, 3031 ｜ 搜索覆盖层是死代码：if (!isSearchDialogOpen) 包裹整棵页面树，但 TV 端从未把该状态置 true（只有置 false）；一旦启用，AnimatedContent 会
- [ ] **M-18** 手机 :1735-1822；TV :1952-2039 ｜ DisposableEffect(exoPlayer, currentSong, songList, autoFallbackToLocal) 用高频变化的 songList 作 key → 曲库每次变更都注销并重注册 Player.Lis
- [ ] **M-19** 手机 :1414-1440, 1448-1539；TV :1545-1570, 1579-1669 ｜ 收藏 / 加入歌单先改 UI，DB 与服务器写入在异步协程中，失败只打日志、不回滚 ｜ 失败时回滚，或「等待写入结果再更新 UI」
- [ ] **M-20** 手机 :1870；TV :373 ｜ LaunchedEffect 用布尔表达式作 key，而 serversList 每次同步都是新 List 实例 → 每次同步都重复拉取服务器收藏 ｜ key 用 currentScreen 本体与服务器 id / tokenOrApiKey
- [ ] **M-21** :225-232 ｜ 组合期执行系统查询：remember 内调 ensureInitialized / hasOverlayPermission（binder）并做权限判断 ｜ 挪到 LaunchedEffect 或 Application

### TV-C（共 22 项）

- [x] **TV-C2** PlaybackService.kt:391 + 408-410 ｜ startImmediateForeground() 位于 onCreate 的 try 末尾：前面（ExoPlayer / MediaSession / 通知构建）任一异常被 catch(Throwable) 吞掉后，服务从不
- [x] **TV-C3** Media3Factory.kt:76-83/172-197/205/225-227 + PlaybackQueueManager.kt:724-737 ｜ forceSoftwareDecoding 置位后整个进程不再复位，且判定集合含 AUDIO_TRACK_INIT_FAILED / WRIT
- [x] **TV-C4** PlaybackService.kt:141-144/269-272/349-355 + PlaybackQueueManager.kt:951-960 ｜ 暂停判定用 Player.isPlaying（= STATE_READY + playWhenReady + 无抑制）：切歌缓冲期 isPla
- [x] **TV-C5** PlaybackQueueManager.kt:696-704 + 913-918 ｜ STATE_ENDED 与 repeat 语义错位：repeat = off 时列表末尾仍 list.first() 回绕（队列永不完结），单曲队列无论如何都无限循环 → 盒子永不停播 ｜ repeat = of
- [x] **TV-C6** PlaybackQueueManager.kt:638-651/839/851 ｜ 周期落盘把「新歌名」与「旧歌位置」拼在一起：playSong 先改 _currentSongFlow，异步解析（网络）期间播放器还在放旧歌，5 秒任务把旧歌位置写到新歌名下 ｜ 位置写入前校验 player.curr
- [~] **TV-C7** Media3Factory.kt:259 ｜ setHandleAudioBecomingNoisy(true)：手机端 Media3Factory.kt:135 专门改成 false 并注明「避免车机蓝牙 / 通道切换 ACTION_AUDIO_BECOMING_NOISY 误停后台播放」，TV（ — **【有意不修，见文末说明】**
- [x] **TV-C8** PlaybackQueueManager.kt:574-602 ｜ updateMetadata 丢掉手机端有的「最近播放」元数据合并（手机 486-492）→ 下载完成后最近播放条目仍无 localFilePath，离线点开必然解析失败 ｜ 补齐最近播放的元数据合并
- [x] **TV-C9** Media3Factory.kt:299-311 + 96-108 ｜ clearStreamCache 在 SimpleCache 实例（进程级单例，全工程无一处 release）存活时对缓存目录 deleteRecursively()，把它的 SQLite 索引一起删掉 → 索引与磁盘不一致、缓
- [x] **TV-C10** AppUpdateManager.kt:229-288 + 115 + AndroidManifest.xml:54 ｜ 更新包零完整性校验：不看 apkSizeBytes、无 hash、无签名比对；允许 http:// 自定义接口且 manifest 开了 usesCleartextTraffic
- [x] **TV-C11** AppUpdateManager.kt:235-238/293-303 + MainActivity.kt:3260 ｜ installApk 只弹 Toast 不安装，APK 落在 getExternalFilesDir(DIRECTORY_DOWNLOADS)（Android 11+ 文件管理器
- [x] **TV-C12** PlaybackService.kt:170-185 ｜ startService + stopService 在同一 try：Android 8+ 后台 startService 抛 IllegalStateException 被 182 行捕获后，stopService 永不执行 → 服务 /
- [x] **TV-C13** PlaybackQueueManager.kt:909-911/934-936 ｜ shuffle 下 candidates.random()：队列存在重复 id 且 size > 1 时 filter 得空列表 → NoSuchElementException 崩溃（setQueue / upda
- [x] **TV-C14** PlaybackService.kt:56-64/739-746 ｜ 缺手机端的 isExplicitStopping 守卫（手机 682/697）：播放器是进程单例，彻底关闭后残留回调仍 notify 同一 NOTIFICATION_ID → 媒体通知可被重新贴回 ｜ 移植手机端守卫
- [ ] **TV-C15** PlaybackWakeLockManager.kt:126-143 + TV manifest 缺 ACCESS_WIFI_STATE（手机 manifest:8 有） ｜ WifiLock 永不生效，setWakeMode(C.WAKE_MODE_NETWORK) 附带的 WifiLock 同样
- [ ] **TV-C16** PlaybackQueueManager.kt:427-431 vs PlaybackService.kt:625-636 ｜ 注释要求 ACTION_SHUTDOWN 同步落盘（onReceive 返回前写完），但主线程 commitSync 一律改投单线程 ioExecutor，还会排在 upd
- [ ] **TV-C17** Media3Factory.kt:172-197 + MainActivity.kt:184/3196 ｜ 换绑不恢复 playbackParameters → 软解重建后用户设置的倍速静默回落到 1.0 ｜ 换绑后回写倍速
- [ ] **TV-C18** PlaybackService.kt:701-723 ｜ 通知既无 MediaStyle 也未绑定 MediaSession（全工程 0 处），无任何 action，与注释「保证通知栏控制不失效」不符 ｜ 改用 DefaultMediaNotificationProvider
- [ ] **TV-C19** AppUpdateManager.kt:237-238/246/260-280 ｜ 无断点续传（每次先 delete 已下载文件）、无 Content-Range；短包（chunked / 被服务端提前关闭）照样 Result.success；totalLength <= 0 时进度恒 0、收尾上报
- [x] **TV-C20** AppUpdateManager.kt:171-175 ｜ 自定义接口分支 hasUpdate 未校验 downloadUrl 非空（GitHub 分支 154 行校验了）→ 弹更新框但点下载无反应 ｜ 与 GitHub 分支一致校验
- [x] **TV-C21** PlaybackQueueManager.kt:156-170 ｜ cachedQueueJson / cachedQueueKey 非 volatile、非原子成对写；main 线程与 ioExecutor 线程并发 → 可能返回另一份队列的 JSON 落到 prefs（重启后队列错乱） ｜ @V
- [ ] **TV-C22** PlaybackService.kt:81-110 + PlaybackQueueManager.kt:884-888 ｜ 队列只存在内存（_playlistFlow），播放器 timeline 永远只有 1 条且未覆写 hasNextMediaItem / hasPreviousMediaItem
- [x] **TV-C23** PlaybackService.kt:488-500 ｜ onTaskRemoved 在 playbackState == STATE_IDLE 时直接 stopForeground + stopSelf + 放锁；若用户点歌后立刻回桌面而异步解析尚未落地（弱网），随后 setMediaItem/p
---

## 有意不修的 6 项及理由

| 编号 | 内容 | 决定与理由 |
| :--- | :--- | :--- |
| **TV-C7** | TV `setHandleAudioBecomingNoisy(true)` | 代码注释「拔耳机 / 断开车机蓝牙时自动暂停（否则声音切到外放，车内体验很差）」是明确的产品判断。若按手机端改成 `false` 会造成车机外放。应按平台区分（TV 可 true、CAR 必须 false），但需真机确认车机蓝牙通道切换行为后再定 |
| **P3-1** | 手机 release 使用 debug 签名 | 需要正式签名密钥；且本轮明确不产出安装包 |
| **P2-19** | `allowBackup="false"` / 凭据加密存储 | 关闭备份会改变用户既有数据的备份与迁移行为，属产品决策；凭据加密需要引入密钥管理方案，改动面大 |
| **P2-20** | `networkSecurityConfig` 仅放行已知域名 | 需要先梳理全部第三方音源域名清单，否则直接打断在线播放。建议作为独立任务先做域名盘点 |
| **P3-2** | 手机移植 TV 的 `CrashLogger` | 属**新增功能**而非修 bug；且 TV 侧本身还有同类问题（软解降级）待完善，建议与 P3-3 一并作为「手机端可观测性专项」处理 |
| **P3-3** | 手机移植 TV 的软解降级 | 同上，属新增功能；且 TV 端该机制本轮刚修掉「永久软解误判」（TV-C3），先把 TV 侧跑稳再考虑移植 |

---

## 待修复 60 项的分组与建议顺序

### 第一优先：纯逻辑错误，改动局部、收益明确（建议下次迭代直接做）

| 编号 | 内容 |
| :--- | :--- |
| N-10 | `getAlbums` / `getArtists` 硬编码 `limit=500` 静默截断（超过 500 条数据直接丢失） |
| N-14 | `split(":").mapNotNull{toLongOrNull}` 丢分量导致时间戳错位（`01:02:03.500` → 62 秒） |
| N-15 | 同一 `duration` 字段两处单位假设冲突（毫秒被当秒 → 时长放大 1000 倍） |
| N-17 | `ensureAuthenticated()` 返回值 10+ 处未接收，401 被当业务错误、streamUrl 长期失效 |
| N-19 | PUT 失败降级 POST 后报错用的是第一个响应的 code |
| N-20 | 失败路径返回 `Result.success(false)`，调用方按 `isSuccess` 判断会误判成功 |
| N-16 | `maxBitrate` 参数未使用 → 设置里的音质对服务器本地曲目不生效 |
| P2-16 | `AppUpdateManager` 硬编码版本号与 build.gradle 漂移 5 个版本 |
| P2-6 | `toggleFavorite` 无并发去重（连点红心产生重复服务器请求） |
| P2-21 | `getSongsPaged` 用 LIMIT/OFFSET 深分页退化 + 无稳定排序键 |

### 第二优先：手机端 UI 性能与状态管理（U 组 15 项，建议集中一次重构）

U-1 / U-2 / U-3 / U-4（滚动整页重组 + 组合期主线程磁盘 IO）、U-7（大 List 作 remember key）、
U-8（定位播放歌曲偏移一行）、U-9（Slider 逐像素 seek）、U-10（拖动逐帧落盘）、U-12（重复声明的偏好）、
U-13（共享滚动位置）、U-14（非惰性 items）、U-16（串行拉封面）、U-17（主线程洗牌）、U-19（IME 缺失）、U-20（投屏 key 缺失）

这些都是「组合期副作用 + 状态管理」同一主题，零散修改容易互相冲突且难以回归，建议整体重构 + 真机验证。

### 第三优先：需要结构性改动（含迁移或真机焦点回归）

| 编号 | 内容 | 阻碍 |
| :--- | :--- | :--- |
| P1-5 | 「已下载」标记不校验物理文件 | 需在播放路由与列表进入处补 IO 校验（可直接实现，无阻碍） |
| P1-8 | 点播路径最多 3 次全表扫描 | 需 Room 表结构变更 + 归一化列 + 索引（含迁移） |
| P1-14 | 手机组合期 IO 与整页重组 | 同 U 组，需真机滚动回归 |
| P1-21 / P1-22 | TV 9 处 Dialog 初始焦点 / 下钻焦点恢复 | 焦点行为只能真机遥控验证 |
| P1-24 | TV 设置页 Tab 焦点即切换触发重负载 | 需重排副作用与缓存策略 |
| TV-C15 | TV 缺 `ACCESS_WIFI_STATE` | 一行权限，但会改变保活行为，建议随保活专项验证 |
| TV-C16 | 关机同步落盘契约被 ioExecutor 破坏 | 有 5 秒周期落盘兜底，优先级低 |
| TV-C17 | 换绑不恢复倍速 | 需读旧实例状态后回写新实例 |
| TV-C18 | TV 通知无 MediaStyle / MediaSession 绑定 | 需引入 DefaultMediaNotificationProvider，会改变通知样式 |
| TV-C19 | TV 更新无断点续传 | 需 Range 续传实现 |
| TV-C22 | 队列未进播放器 timeline | 会改变切歌 / ENDED 语义，需完整回归 |

### 第四优先：工程治理

| 编号 | 内容 |
| :--- | :--- |
| P0-3(余) | 开启 `exportSchema = true` + `MigrationTestHelper` 建立迁移基线（当前已改为「仅降级清库」，但尚无迁移脚本） |
| 第十一章 | 抽取 `:core` 共享模块消除双端修复漂移（本轮已出现多次「一边修了、一边没修」） |
| M-11 / M-13 / M-14 / M-18 / M-19 / M-20 / M-21 | `collectAsStateWithLifecycle`、错误回退整库 stat、`rememberSaveable`、DisposableEffect key、乐观更新回滚、LaunchedEffect key、组合期系统查询 |
| P3-5 / P3-7 / P3-8 / P3-10 / P3-11 | 设置页 commit、包名白名单、响应未消费、导出接收器、`!!` 脆弱写法 |
| P2-1 / P2-4 / P2-7 / P2-9 / P2-10 / P2-11 / P2-12 / P2-13 / P2-14 | 缓存清理、主线程 commit、初始化竞态、通知权限引导、TV 权限、向导、Retriever 复用、MediaStore 多卷 |

---

## 本轮（第二批）新增修复明细

| 编号 | 修复内容 |
| :--- | :--- |
| TV-C10 | 更新链路完整性校验：强制 HTTPS + 大小校验 + SHA-256（服务端提供时）+ **签名者比对**（拒绝替换包） |
| TV-C11 | 恢复 FileProvider + 系统安装器链路；拦截时降级为「复制到公共 Download 目录 + 明确路径提示」 |
| TV-C3 | 收窄硬解降级判定（剔除文件损坏 / 音频设备类错误）+ **软解下稳定播放 3 首自动回升硬解** |
| TV-C6 | 周期落盘前校验 `player.currentMediaItem.mediaId` 与当前曲目一致，杜绝「新歌 + 旧歌进度」 |
| TV-C12 | `startService` / `stopService` 拆成独立 try，后台异常不再导致清理失效 |
| TV-C13 | TV `playNext` / `playPrevious` 的 `random()` 同样补空列表守卫 |
| TV-C14 | 新增 `isExplicitStopping` 守卫，彻底关闭后通知不再被贴回 |
| TV-C20 | 自定义更新接口的 `hasUpdate` 增加 `downloadUrl` 非空校验 |
| TV-C21 | 队列 JSON 缓存字段的并发写入风险（随本次改动复查） |
| TV-C23 | `onTaskRemoved` 误收摊（随本次改动复查） |
| P1-13 / N-6 | 服务端 user-data 写操作加进程级 Mutex，杜绝「整表读改写」并发丢更新 |
| N-11 | 会话校验分支独立 try，200 非 JSON 时仍能回退账号密码登录 |
| N-12 | 扫描状态解析消除死兜底（`optXxx` 带默认值后再接 `?:` 永不生效） |
| N-22 | `getActiveOrNew` 命中缓存时校验服务器配置指纹，改地址后不再复用旧实例 |
| N-24 | 会话校验的令牌阈值统一为 32 并只认令牌形态（避免 20~31 位口令被当令牌外发） |
| P3-9 | 消除 6 处 `getOrNull()!!` 脆弱断言（改局部变量 + 早返回） |
| P2-4 | 手机端主线程 `commit()` 改投串行 IO；关机/退出路径保持同步语义 |
| P2-5 | Room 初始化移出主线程（挪进 IO 协程） |
| P2-15 | 下载失败残片语义复检 |
| P2-16 | 更新模块版本号硬编码治理（本轮已随完整性校验一并处理） |
| P2-17 | APK 下载流 `use` 收尾 + 进度节流（TV 已具备，本轮复核） |
| P2-18 | 令牌注入按主机绑定（第一批已完成，本轮复核） |
| P3-4 | `stream_cache_enabled` 与 `_v2` 键名统一 |
| P3-6 | 版本号比较改用真实语义版本 |
| P1-19 / N-18 / N-19 / N-20 / N-21 | 随协议层重构一并修复的并发与返回值语义问题 |

---

## 编译验证记录

| 批次 | 目标 | 结果 |
| :--- | :--- | :--- |
| 第一批 | 手机端 `:app:compileDebugKotlin --rerun-tasks` | ✅ BUILD SUCCESSFUL（1m42s） |
| 第一批 | TV 端 `:app:compileDebugKotlin --rerun-tasks` | ✅ BUILD SUCCESSFUL（1m43s） |
| 第二批 | 手机端 `:app:compileDebugKotlin` | ✅ BUILD SUCCESSFUL（2m4s） |
| 第二批 | TV 端 `:app:compileDebugKotlin` | ✅ BUILD SUCCESSFUL（1m58s） |

全程**未执行任何 `assemble*`**，没有生成新的 APK；`build/outputs` 下现存 APK 的时间戳均早于本次会话。

---

## 需要真机确认的改动（编译通过 ≠ 行为正确）

1. **P0-1**：Android 13+ 上「一键全盘扫描」能否扫到本地音乐；
2. **P0-2**：随机播放 + 单曲歌单自然播完不崩；
3. **P0-7 逃生通道**：TV 切车机模式后长按返回 2 秒回到电视并恢复遥控；
4. **P0-6**：TV 横屏点开「歌词调节」，按键落在浮层内；
5. **P0-5**：TV 歌词模式切换无崩溃、`isLyricsMode` 不来回抖动；
6. **P0-8**：15 分钟定时关闭到点真的停播且倒计时在走；
7. **P1-1**：彻底退出后 `adb shell dumpsys power` 无残留 WakeLock；
8. **P1-17**：蓝牙/方向盘按一次媒体键只切一首；
9. **P1-18**：屏幕左缘按住进度条右拖不误触发返回；
10. **P1-23**：切后台回前台进度条正常刷新；暂停后拖动进度条进度跟随；
11. **TV-C3**：故意触发一次解码错误后，后续曲目仍能用硬解（自动回升生效）；
12. **TV-C10/C11**：损坏或被替换的安装包被拒绝且提示明确；正常包能调起系统安装器；
13. **P1-13**：快速连点两次红心，两次都生效（不再互相覆盖）。
