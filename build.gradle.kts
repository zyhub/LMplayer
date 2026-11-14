plugins {
    // 2026-11-10 升级：液态玻璃改用 Kyant0/AndroidLiquidGlass（io.github.kyant0:backdrop），
    // 它基于 Compose 1.12（要求 compileSdk 37、AGP ≥ 9.1）与 Kotlin 2.4 编译。
    id("com.android.application") version "9.3.2" apply false
    // AGP 9 内置 Kotlin 支持，app 模块不再应用 kotlin-android 插件；这里只把 KGP 2.4.10 放进构建类路径，
    // 让内置 Kotlin 使用 2.4.10 编译器（能读取 backdrop 库的 2.4 元数据）
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("com.google.devtools.ksp") version "2.3.13" apply false
}
