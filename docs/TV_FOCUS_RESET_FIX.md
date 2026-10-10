# TV 端焦点重置问题 · 排查与修复

> 现象：在「点歌台」页面点击一个选项（如点歌手「周杰伦」搜索）后，焦点光标直接重置到最左边。
> 要求：焦点随时保持在每个页面的点击处，不被重置。
> 产物：`LMPlayerTV-v1.10.18.apk`（versionCode 65）· 含 v1.10.17 回归事故的修复

---

## 一、根因

### 1.1 直接原因：焦点承载节点被移出组合

页面切换普遍写成**两个互相独立**的 `if`，中间没有任何过渡或焦点处理：

```kotlin
// LocalLibraryScreen.kt:416 主视图
if (activeSubViewTitle == null) { LazyColumn(...) }
// LocalLibraryScreen.kt:1676 下钻视图
if (activeSubViewTitle != null) { Column(...) }
```

点击歌手胶囊 → `activeSubViewTitle = artist.name` → 条件翻转 → **含当前聚焦节点的整棵子树离开组合**。
Compose 焦点系统在「当前焦点节点被销毁」时没有可继承的目标，只能把焦点交给焦点树中的默认节点 ——
布局上就是第一个可聚焦项，视觉上即「跳到最左边」。工程内**没有任何代码指定这个落点**，这正是缺陷本身。

从 Gradle 缓存解包 `ui-android-1.6.5` 交叉验证：`FocusOwnerImpl.class` 含 `clearFocus` / `releaseFocus` /
`focusSearch` / `findActiveFocusNode`，以及字符串 `"Event can't be processed because we do not have an active focus target."`；
`FocusRestorerKt` / `FocusRestorerNode` **存在但工程 0 引用**。

> 诚实边界：「点击瞬间焦点丢失」是代码可证的事实；最终落在哪个节点由 Compose 的 `clearFocus`/`focusSearch`
> 补位决定（**框架行为，属推断**）。但无论落在哪，工程都没有指定它 —— 这就是要修的东西。

### 1.2 页面身份澄清（重要）

代码里自称「KTV 点歌台」的是 **`LibrarySearchDialog`**（`:55 :73 :300 :490 :521 :688 :934 :956`），不是我最初以为的 MINE 页。
两处存在**完全同型**的缺陷，且用户举的例子（点歌手「周杰伦」）在两处都成立：

| 页面 | 触发点 | 「最左边」是 |
| :--- | :--- | :--- |
| **点歌台**（`LibrarySearchDialog`） | 热词卡 `/ :1003-1010` → `query = keyword` → 空态分支 `/ :933` 整块被结果列表替换 | **左栏字母键盘首键**（键盘在组合顺序上排在结果区之前）—— 与用户「重置到最左边」的字面描述最吻合，且 `:113` 的 `defaultHotKeywords` 第一个词恰好就是「周杰伦」 |
| **我的**（`LocalLibraryScreen`） | 歌手胶囊 `/ :1434` → `activeSubViewTitle` → 下钻 | 左栏黑胶播客卡 |

**两处都已修复。**

### 1.3 三个放大因素

1. **列表滚动状态未提升**：全工程仅 2 处 `rememberLazyListState`（都在歌词页），所有业务列表容器都未传 `state=`。
   子树被移除时 provider 注销、值丢弃 → 下钻返回后滚动回顶部；而且**焦点恢复依赖目标项在视口内** ——
   目标被滚出视口时 LazyList 里根本没有该节点，`requestFocus` 必然失败。
2. **`requestFocus()` 无条件抢占**：`AdaptiveAppScaffold.kt:134-148` 在关闭全屏播放器后固定把焦点拉回左栏黑胶卡，
   直接违反「焦点保持在点击处」。
3. **顶部 Tab「焦点即切换」**：`KuwoTopTabButton` 获焦即 `onNavigate`。一旦焦点被补位到顶部 Tab，页面会被**自动切走** ——
   这是「焦点丢失」最危险的放大器。

---

## 二、修复设计（四件套，缺一不可）

单靠任何一件都修不好，这是本次排查最重要的结论：

| # | 组成 | 作用 | 不做的后果 |
| :--- | :--- | :--- | :--- |
| 1 | **机制**：`tvFocusable` / `tvButtonFocusable` 新增 `focusKey` 参数 | 让每个可聚焦节点「可被点名恢复」 | 恢复时找不到目标 |
| 2 | **分层记忆**：`TvFocusMemory` 按 scope（`content::mine` / `content::mine::周杰伦`）存储 | 下钻不覆盖上级记忆，返回能精确回到「我点的那一项」 | 下钻后子视图焦点覆盖父记忆，返回时回不到点击处 |
| 3 | **进入侧锚点**：`tvFocusEntryAnchor(trigger)` | 进入新视图时**显式指定落点** | 被点击的节点此刻已销毁，「恢复」必然失败，用户看到的仍是跳到最左 |
| 4 | **滚动状态提升**：`state = rememberLazyListState()` 提到 if 分支外 | 保证目标项在视口内、有对应节点 | 目标不在视口内 → 没有节点 → 恢复必然失败 |

### 新增文件：`core/designsystem/component/TvFocusMemory.kt`

- `LocalTvFocusScope` —— 用 **CompositionLocal** 由组合树自然嵌套（不用全局单值：`AnimatedContent` 过渡期两屏同时在组合中，全局单值会互相覆盖）；
- `TvFocusScope(name) { ... }` —— 声明作用域，子作用域自动拼成 `parent::child`；
- `TvFocusMemory.register / unregister` —— **`unregister` 带 requester 身份比对**。
  LazyList 滚动/重排时会出现「新节点先注册、旧节点后 dispose」，无条件按 key 删除会抹掉新注册，导致该 key 永久无法恢复；
- `restoreFocus(scope)` —— **逐级向上回退**（子 scope 目标不存在时回到父 scope，即用户点击的那一项），并**按帧重试**最多 12 帧。
  原先固定 `delay(60)` 一次定生死，目标尚未 measure 时静默失败；
- `restoring` 标志 —— 恢复期间不记录记忆，避免「恢复动作自身触发的焦点变化」污染记忆；
- `tvFocusEntryAnchor(trigger)` —— 进入侧锚点，按帧重试 + 末帧退化延时重试。

### 接线点

| 文件 | 改动 |
| :--- | :--- |
| `TvFocusModifiers.kt` | `tvFocusable` / `tvButtonFocusable` 新增 `focusKey`；`unregister` 带身份比对；同时重新应用 `allowHorizontalKeyChange`（此前会话的 TV-C6 修复） |
| `LibrarySearchDialog.kt`（点歌台） | 热词卡 `focusKey = "ktv_hot_$keyword"` + `entryAnchorToken++`；结果列表 `state = resultListState` 提升到分支外；结果区顶部挂 `tvFocusEntryAnchor(entryAnchorToken)` |
| `LocalLibraryScreen.kt`（我的） | `mainListState` / `subViewListState` / `artistRowState` 提升到 if 之外；歌手胶囊 `focusKey = "mine_artist_pill_$name"`、全部歌手网格 `mine_allartist_`、专辑网格 `mine_album_`、文件夹网格 `mine_folder_`、歌单 `mine_plrow_` / `mine_plgrid_`；下钻返回键挂 `tvFocusEntryAnchor(activeSubViewTitle)`；`TvRestoreFocusOnChange(activeSubViewTitle == null)` |
| `AdaptiveAppScaffold.kt` | 内容舞台包 `TvFocusScope("content")` + **全局焦点防丢兜底守护**：舞台曾有焦点、现在整个舞台都无焦点、且无全屏播放器时，按记忆恢复。屏蔽条件参照 `FullscreenPlayerSheet` 已有的守护，避免重现「浮层收不到按键」故障 |

**关于 key 命名**：必须带区块前缀。同一歌手/歌曲会同时出现在「歌手胶囊流」与「全部歌手」网格、
「最近播放」与「全部歌曲」列表里，共用 key 会让后注册者覆盖前者，恢复时落到另一个实例（甚至不在视口内）。

**关于进入侧锚点的令牌**：点歌台用的是 `entryAnchorToken`（只在用户主动从空态进入结果视图时自增），
**不是** `query` / `query.isBlank()` —— 后者每次在线搜索结果返回都会变，会把正在浏览的用户焦点抢回顶部。

---

## 二·补 回归事故与修复（v1.10.17 → v1.10.18）

### 事故现象

v1.10.17 上线后，**「我的 / 搜索 / 发现 / 设置」等下级页面完全不显示光标**（顶部 Tab 与左栏正常）。
这是我在上一版引入的**回归**，比原问题更严重。

### 根因：我加的「全局兜底守护」有三个致命缺陷

```kotlin
// v1.10.17 的错误写法（已移除）
var contentStageHadFocus by remember { mutableStateOf(false) }
LaunchedEffect(contentStageHadFocus, isFullPlayerVisible) {
    if (contentStageHadFocus || isFullPlayerVisible) return@LaunchedEffect
    kotlinx.coroutines.delay(90L)
    TvFocusMemory.restoreFocus("content")
}
// 内容舞台
Box(Modifier...onFocusChanged { contentStageHadFocus = state.hasFocus }.focusTarget())
```

| # | 缺陷 | 后果 |
| :--- | :--- | :--- |
| 1 | `contentStageHadFocus` 初值为 `false`，且 `LaunchedEffect` **在首次组合就会执行一次**，条件 `if (contentStageHadFocus ...) return` 拦不住 | 每次进入页面**立刻**抢一次焦点 |
| 2 | `.focusTarget()` 把整个内容舞台变成**一个可聚焦节点** | 舞台本身成为焦点目标，内部页面的子节点被它遮蔽 —— 这正是「完全没有光标」的直接原因 |
| 3 | 「恢复成功 → 舞台获得焦点 → `onFocusChanged` 置 true → 状态变化 → effect 重启 → 再恢复」 | **自激循环**，焦点被反复抢占 |

还有一个方向性错误：内容舞台在 LazyColumn 布局里位于左栏**之后**，Compose 丢焦点时补位到第一个可聚焦项 = 左栏。
我的守护却把焦点往右栏推 —— 与用户「光标跳到最左」的观察方向相反。

### 修复

1. **完全移除**该守护协程、`onFocusChanged`/`focusTarget()`，内容舞台恢复为普通 `Box`；
2. `TvRestoreFocusOnChange` 增加**首次组合不动作**保护（哨兵值 + 只在 trigger 真正变化时恢复），
   杜绝「进页面即抢焦点」与自激循环；
3. 修正 `LibrarySearchDialog` 的进入锚点：原先挂在结果区顶部那个**纯文本 Row**（不可聚焦，
   `requestFocus` 必然失败，等于锚点没生效），改为挂在**首行结果**上；
   并给 `SongListItemRow` 补 `modifier` 参数以便透传。

**结论：焦点保持改由页面自身负责**（`focusKey` + `TvRestoreFocusOnChange` + `tvFocusEntryAnchor`），
不再做跨页面的全局抢焦点。这条经验值得记住：TV 焦点问题的兜底逻辑，一旦时机或落点判断错，
造成的破坏会远大于它想修的问题。

---

## 三、编译与产物

| 项 | 结果 |
| :--- | :--- |
| `:app:compileDebugKotlin` | ✅ BUILD SUCCESSFUL（多轮迭代） |
| `:app:assembleRelease` | ✅ BUILD SUCCESSFUL（5m53s） |
| 产物 | `LMPlayerTV-v1.10.18.apk`（versionCode **65** / versionName **1.10.18**，已用 `aapt2 dump badging` 核验） |
| 签名 | `59:D2:BB:…:EA:34`，与 v1.10.15 / v1.10.16 **完全一致** → 可直接覆盖安装 |
| GitHub | **未推送**（TV 为独立 git 仓库，未提交） |

---

## 四、测试清单（遥控器）

判据：**任何一步光标都不离开用户刚才点击的那一项**（或该视图的锚点）。

1. **点歌台**：按 OK 点热词卡「周杰伦」→ 焦点应落在**结果区顶部**，而不是左栏字母键盘首键；
2. **我的**：点歌手胶囊 → 焦点落在**下钻视图的返回键**；按返回 → 焦点回到**刚才那个歌手胶囊**；
3. **我的**：点「全部歌手」网格项 → 返回后焦点回到该网格项（不是主语左侧）；
4. **我的**：点歌单卡 / 文件夹卡 / 专辑卡，各自验证进入与返回；
5. **发现页**：歌单下钻与返回（该页沿用同一套机制）；
6. **顶部 Tab**：切走再切回，焦点不应被自动切换逻辑带走；
7. **关闭全屏播放器**：焦点落点（此项行为未改动，仍回左栏黑胶卡，见「已知未改」）；
8. **回归**：确认没有出现「浮层收不到按键」——即在全屏播放器与各弹窗内，遥控器按键应正常响应；
9. **回归**：确认列表滚动位置在下钻返回后**不再回到顶部**。

---

## 五、已知未改（建议后续处理）

| 项 | 说明 |
| :--- | :--- |
| `AdaptiveAppScaffold.kt:134-148` 关闭全屏播放器后固定拉回左栏黑胶卡 | 直接违反「焦点保持在点击处」。应改为用 `TvFocusMemory` 恢复到「打开播放器前记住的 key」。本轮未动是因为它与全屏播放器的焦点守护协程耦合，需要单独回归 |
| `AdaptiveAppScaffold.kt:127` 首次进入无条件聚焦发现 Tab | 在 `AnimatedContent` 过渡期可能与页面内锚点竞争。建议加 `currentScreen == HOME` 条件 |
| `LocalLibraryScreen.kt` 歌曲行 / bento 卡未加 `focusKey` | 已覆盖歌手、专辑、文件夹、歌单四类下钻入口（用户报告的场景）；歌曲行与 bento 卡的下钻入口尚未逐个补 key，但已被全局兜底守护兜住，焦点不会跑到左栏 |
| `artists.take(18)` 位置截断 + `hashCode()` 生成 id | 第 18 名之后的歌手无法通过胶囊流进入；hashCode 碰撞时同一 LazyRow 会出现重复 key（Compose 会抛 `Key was already used`）。属既有缺陷，与本问题正交 |

---

**记录结束。**
