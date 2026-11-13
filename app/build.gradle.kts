import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/**
 * Release 签名配置。
 *
 * 密码与 keystore 路径存放在项目根目录的 keystore.properties 中，
 * 该文件已被 .gitignore 排除，不会进入版本库。
 *
 * 若文件不存在（例如他人克隆仓库后尚未配置），release 构建会跳过签名，
 * 此时产物为 unsigned APK，不影响 debug 构建。
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasSigningConfig = keystoreProps.getProperty("storeFile") != null

android {
    namespace = "dev.beyonddev.beyondlingo"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.beyonddev.beyondlingo"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.5.0"

        vectorDrawables { useSupportLibrary = true }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ABI 按变体分别声明（见下方 buildTypes）：
        // - defaultConfig 里写 abiFilters 会作用于所有变体，
        //   buildType 再写就是「追加」而非「替换」，x86_64 去不掉（实测过）。
        // - sherpa-onnx AAR 携带 4 个 ABI（合计 ~137MB），必须裁剪。
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 开启代码压缩与混淆，显著减小体积
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }

            // release 只保留 arm64-v8a。
            // x86_64 那套 so 约 65MB（onnxruntime 24.4 + avcodec 13.2 + mlkit 11.1 …），
            // 只为模拟器调试而存在；真机发布不需要，省下约 65MB。
            // 需要在 x86_64 模拟器上测 release 时，把它加回来即可。
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"

            // debug 保留 x86_64 以便在模拟器上跑
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")

    // SavedState (ComposeView 在 Service 中需要)
    implementation("androidx.savedstate:savedstate-ktx:1.3.3")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.5")

    // Room
    implementation("androidx.room:room-runtime:2.8.2")
    implementation("androidx.room:room-ktx:2.8.2")
    ksp("androidx.room:room-compiler:2.8.2")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // Network
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // OCR —— ML Kit 文字识别（拉丁文字模型，随 APK 打包，离线可用）
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // 图片加载（显示待识别图片）
    implementation("io.coil-kt:coil-compose:2.7.0")

    // 图片选择
    implementation("androidx.activity:activity-ktx:1.11.0")

    // ASR —— sherpa-onnx（AAR 来自 GitHub Release，不在 Maven Central）
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))

    // 视频翻译：抽音频（FFmpegKit 官方 2025-01 退役，用社区维护的 drop-in）
    // 只引 audio 变体，避免 full 变体的数十 MB 体积
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.7")

    // 视频播放 + 外挂字幕
    implementation("androidx.media3:media3-exoplayer:1.9.0")
    implementation("androidx.media3:media3-ui:1.9.0")

    // 仪器化测试：ASR 基准验证
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")

    // 单元测试：简繁转换表等纯逻辑
    testImplementation("junit:junit:4.13.2")
    // Android SDK 自带的 org.json 只是 stub（方法全部抛"not mocked"），
    // 单元测试里要用真实实现才能验证 manifest 解析
    testImplementation("org.json:json:20231013")
}
