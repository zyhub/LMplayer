# Proguard rules for LMPlayer

# 1. 保持序列化与注解属性
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# 2. 核心数据模型 (Room 实体、网络传输实体与核心数据类)
-keep class com.lm.player.core.model.** { *; }
-keep class com.lm.player.core.database.entity.** { *; }
-keep class com.lm.player.core.database.dao.** { *; }
-keep class com.lm.player.core.database.** { *; }
-keep class com.lm.player.core.update.UpdateInfo { *; }
-keep class com.lm.player.core.network.** { *; }

# 3. Room 数据库混淆保护
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# 4. Gson 序列化与反射模型
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
    @com.google.gson.annotations.Expose <fields>;
}
-keep class com.google.gson.** { *; }

# 5. Retrofit & OkHttp
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# 6. Conscrypt TLS 引擎
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# 7. Media3 音频播放框架
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# 8. Coil 图像加载器
-keep class coil.** { *; }
-dontwarn coil.**

# 9. Kotlin 协程与元数据
-keepnames class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# 10. 保持全屏播放器与歌词相关 Compose 组件，避免 R8 优化合并产生 Dalvik/ART Verifier 异常 (Android 8/9 API 28 兼容)
-keep class com.lm.player.feature.player.** { *; }

# 11. 关闭 R8 代码优化（关键修复：Android 8/9 上的 VerifyError）
#
# 症状：打开全屏播放页立刻闪退，堆栈为
#   java.lang.VerifyError: Verifier rejected class
#     com.lm.player.feature.player.FullscreenPlayerSheetKt
#     register vNNN has type Integer but expected Reference: java.lang.Object
#
# 原因：FullscreenPlayerSheet 是一个**巨型 Composable**，在 TV 端实测其编译后
# 指令数达 24834 条（日志：Method exceeds compiler instruction limit）。
# R8 的优化/内联会把它改写成局部变量槽被不同类型复用的字节码；较新的 ART（Android 10+）
# 容忍这种写法，但 Android 8/9 的校验器会**直接拒绝加载整个类**，表现为一进播放页就闪退。
#
# 注意：第 10 条的 -keep 只阻止「裁剪与重命名」，**不阻止优化**，拦不住本问题。
# -dontoptimize 关闭 R8 优化阶段（仍保留压缩、混淆与资源裁剪）。
# 这是用「牺牲一点运行期优化」换取「Android 8/9 上不再崩溃」的取舍；
# 根治办法是把 FullscreenPlayerSheet 拆成多个小 Composable。
-dontoptimize
