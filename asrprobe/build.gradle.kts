plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * ASR Probe — 独立的 sherpa-onnx 性能验证工具。
 *
 * 与 BeyondLingo 主应用完全分离：独立包名、独立应用名、不共享任何业务代码。
 * 唯一复用物是 app 模块 assets 里的 ASR 模型（通过 srcDirs 引用，不复制）。
 */
android {
    namespace = "dev.beyonddev.asrprobe"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.beyonddev.asrprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        ndk {
            // arm64 用于真机，x86_64 用于模拟器验证
            abiFilters += listOf("arm64-v8a", "x86_64")
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

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // 复用 app 模块的 ASR 模型，避免第二份 24MB 拷贝
    sourceSets["main"].assets.srcDirs("../app/src/main/assets")
}

dependencies {
    implementation(files("../app/libs/sherpa-onnx-2.0.1.aar"))
}
