import java.util.Properties

plugins {
    id("com.android.application")
    // AGP 9 内置 Kotlin：不再应用 org.jetbrains.kotlin.android；Compose 编译器改为 Kotlin 2.x 的独立插件
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.lm.player"
    // Compose 1.12（液态玻璃库 Kyant0 backdrop 依赖）要求 compileSdk 37；targetSdk 不变，运行行为不受影响
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.lm.player"
        minSdk = 23
        targetSdk = 34
        // 版本号唯一来源：代码中通过 BuildConfig.VERSION_NAME / VERSION_CODE 读取，禁止再在 Kotlin 里硬编码
        // 【版本号已锁定】按维护者要求固定为 1.7.33 (68)（2026-11-08 由 1.7.32 升级），编译出包时不要自动递增；如需变更请先征得维护者同意
        versionCode = 68
        versionName = "1.7.33"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
        }

        ndk {
            // x86_64 仅用于 BlueStacks/模拟器 ADB 验证 QuickJS 原生库
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            // S-01：签名文件统一放在上一级「项目根目录」（与 TV 端共用一个仓库根），
            // 找不到时再回落到本工程目录下的旧位置，兼容旧的编译环境。
            val signingRoot = project.rootProject.projectDir.parentFile
            val keystoreFile = signingRoot.resolve("LMPlayer-release.jks").takeIf { it.exists() }
                ?: project.rootProject.file("keystore.jks")
            val propsFile = signingRoot.resolve("LMPlayer-keystore.properties").takeIf { it.exists() }
                ?: project.rootProject.file("keystore.properties")
            if (!keystoreFile.exists()) {
                throw GradleException("Release keystore.jks is required; refusing to fall back to debug signing.")
            }
            val props = Properties().apply {
                if (propsFile.exists()) propsFile.inputStream().use { load(it) }
            }
            storeFile = keystoreFile
            storePassword = System.getenv("KEYSTORE_PASSWORD")
                ?: props.getProperty("storePassword")
                ?: throw GradleException("KEYSTORE_PASSWORD or keystore.properties is required.")
            keyAlias = System.getenv("KEY_ALIAS")
                ?: props.getProperty("keyAlias")
                ?: throw GradleException("KEY_ALIAS or keystore.properties is required.")
            keyPassword = System.getenv("KEY_PASSWORD")
                ?: props.getProperty("keyPassword")
                ?: throw GradleException("KEY_PASSWORD or keystore.properties is required.")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // 生成 BuildConfig.VERSION_NAME / VERSION_CODE，AppUpdateManager 由此读取版本号，避免与 defaultConfig 不一致
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.media3.common.util.UnstableApi",
            "-opt-in=kotlinx.coroutines.FlowPreview"
        )
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    val media3Version = "1.3.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")

    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.conscrypt:conscrypt-android:2.5.2")

    implementation("io.coil-kt:coil-compose:2.6.0")

    // 液态玻璃（Apache-2.0，https://github.com/Kyant0/AndroidLiquidGlass）：Compose 原生的背景采样 / 模糊 / 透镜折射
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("io.github.kyant0:shapes:1.2.1")
    implementation("androidx.palette:palette-ktx:1.0.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // 本地音源解析引擎；固定使用 Kotlin 1.9.x 兼容版本
    implementation("io.github.dokar3:quickjs-kt-android:1.0.0-alpha02")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
