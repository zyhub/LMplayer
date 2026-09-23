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
