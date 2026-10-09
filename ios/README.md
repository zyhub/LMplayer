# LMPlayer iOS（Phase 0 骨架）

SwiftUI 原生实现，Deployment Target iOS 26.0，全项目零版本判断分支。
本目录是 ../docs/ios-native-plan.md 的 Phase 0 落地：工程骨架 + Liquid Glass 设计系统 + API 契约层骨架。

## 环境要求

- macOS + Xcode 26 或更高（必须包含 iOS 26 SDK）
- XcodeGen（brew install xcodegen）

## 生成并运行

    cd ios
    xcodegen generate        # 由 project.yml 生成 LMPlayer.xcodeproj
    open LMPlayer.xcodeproj

在 Xcode 中选择 Signing & Capabilities -> Team（免费 Apple ID 即可），插上真机或选模拟器，直接 Run。

## 不用 XcodeGen 的替代路径

1. Xcode -> File -> New -> Project -> iOS App，Product Name 填 LMPlayer，Interface 选 SwiftUI，Deployment 设为 26.0。
2. 删除自动生成的 ContentView.swift 与 LMPlayerApp.swift。
3. 把本目录的 LMPlayer/ 整个拖进工程（勾选 Create groups）。
4. 在 Target -> Info 中补上：
   - UIBackgroundModes = audio
   - NSSupportsLiveActivities = YES
   - NSAppTransportSecurity.NSAllowsLocalNetworking = YES

## 目录

    LMPlayer/
    ├── App/                  应用入口、根导航（TabView + tabViewBottomAccessory）
    ├── DesignSystem/         Liquid Glass 设计系统（全项目唯一 glassEffect 入口）
    ├── Core/
    │   ├── Model/            统一模型（对齐 Android UnifiedSong / AudioQuality）
    │   ├── Network/          43 端点契约 + URLSession actor + 回退链解码
    │   └── Playback/         播放内核骨架
    └── Features/             发现 / 资料库 / 下载 / 设置 / 播放器

## 已确认的产品决策

| 项 | 结论 |
| :--- | :--- |
| DLNA / UPnP | 不保留（仅 AirPlay） |
| APE / DSD / WMA | 不支持，不引入 FFmpeg |
| 变速播放保持音调 | 不要求 |
| 分发 | 本地编译测试（免费签名 / 真机） |
| 本地曲库 | 保留沙盒内导入，砍掉全盘扫描（默认，待最终确认） |

## Phase 0 已完成 / 待办

已完成：

- XcodeGen 工程定义（iOS 26.0、Swift 6、严格并发）
- 根导航：TabView + tabViewBottomAccessory + tabBarMinimizeBehavior
- Liquid Glass 设计系统：LMPalette / LMTypography / LMShapes / LMGlass / LMMotion
- 组件：ArtworkImage、MiniPlayerBar
- API 契约层：Endpoint（43 端点）、LemonMusicClient（actor）、Flexible 解码、DTO
- 播放内核骨架：PlayerEngine（AVQueuePlayer）

待办（Phase 1 起）：

- 真正的服务端联调（当前默认地址是占位值）
- 设置页保存后真正调用 configure / authenticate
- SwiftData 持久化、播放路由、Now Playing、Live Activity

## 编译验证提示

本项目生成于 Windows 环境，尚未在 Mac 上编译过。首次编译重点关注：

- iOS 26 的 SwiftUI API 名称与签名（glassEffect / GlassEffectContainer /
  tabViewBottomAccessory / tabBarMinimizeBehavior）需与本地 SDK 头文件核对。
- Glass 类型的成员方法（tint / interactive）如与 SDK 不符，只需改
  DesignSystem/LMGlass.swift 一处。
- Flexible 属性包装器的 Decodable 合成如报错，改为手写 init(from:) 即可。
