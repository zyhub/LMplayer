# LMPlayer 手机端工程迁移与更新指南

> 本目录为 **LMPlayer（柠檬音乐）手机端** 纯净独立工程，已剔除所有编译缓存（`.gradle`、`build/`）、旧版 APK 安装包与临时日志，代码体积由原来的 580MB+ 缩减至 **~2.9MB**，包含构建与发布所需的所有源码、资源配置、Room 架构定义及正式发布签名凭据。

---

## 一、 快速导入与编译指南

### 1. 导入 Android Studio
- 打开 Android Studio，点击 **Open**，直接选中本 `LMplayer` 文件夹即可导入。
- Android Studio 会自动根据当前电脑的 Android SDK 路径更新 `local.properties` 中的 `sdk.dir`。

### 2. 命令行一键编译
在当前根目录下执行：
- **Windows**:
  ```bat
  gradlew.bat assembleRelease
  ```
- **macOS / Linux**:
  ```bash
  chmod +x gradlew
  ./gradlew assembleRelease
  ```
- 编译生成的带正式签名的安装包位于：`app/build/outputs/apk/release/app-release.apk`。

---

## 二、 签名与密钥配置说明（防止换电脑失效）

本工程已内置完整自闭环的 Release 签名机制，**换电脑后无需配置任何系统环境变量即可直接正常打包与更新**：

| 配置项 | 文件位置 | 配置内容 / 凭据说明 |
| :--- | :--- | :--- |
| **密钥库文件 (Keystore)** | `keystore.jks` | 根目录官方正式签名密钥库 |
| **凭据配置文件** | `keystore.properties` | 根目录自动读取配置文件 |
| **Store Password** | 配置文件内读取 | `LmPlayerLocal!2026#9f3c7a2e` |
| **Key Alias** | 配置文件内读取 | `lmplayer` |
| **Key Password** | 配置文件内读取 | `LmPlayerLocal!2026#9f3c7a2e` |

> [!NOTE]
> `app/build.gradle.kts` 会优先从根目录 `keystore.properties` 中读取口令。只要保证 `keystore.jks` 与 `keystore.properties` 位于工程根目录，任何机器上均可无感构建正式签名包，保证版本升级时签名一致，不会出现“安装包签名冲突无法更新”的问题。

---

## 三、 工程核心目录结构

```
LMplayer/
├── app/                           # 手机端核心应用模块
│   ├── build.gradle.kts           # 模块级构建脚本（已配置正式签名与混淆规则）
│   ├── proguard-rules.pro         # R8 / ProGuard 混淆保护规则
│   ├── schemas/                   # Room 数据库版本持久化 Schema (v7)
│   └── src/
│       ├── main/                  # 核心源码 (69 个 Kotlin 文件 + Manifest + 资源)
│       └── test/                  # 核心算法单元测试用例
├── gradle/wrapper/                # Gradle Wrapper (8.7)
├── .gitignore                     # Git 忽略文件（已配置忽略临时 build 产物）
├── build.gradle.kts               # 顶层项目构建脚本
├── settings.gradle.kts            # 项目模块定义 (include(":app"))
├── gradle.properties              # JVM 与 AndroidX 配置
├── gradlew / gradlew.bat          # 跨平台 Gradle 包装器执行脚本
├── keystore.jks                   # 官方正式签名证书
├── keystore.properties            # 官方正式签名口令
├── local.properties               # 本机 SDK 路径配置
├── README.md                      # 项目说明文档
├── CHANGELOG.md                   # 版本更新日志
├── 手机端审计报告.md                # 深度代码审计与加固报告
└── 手机端下载与离线同步审计修复报告.md  # 下载与 NAS 同步修复报告
```
