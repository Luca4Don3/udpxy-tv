plugins {
    id("com.android.application")
}

android {
    namespace = "com.company.udpxytv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.company.udpxytv"
        minSdk = 24
        targetSdk = 34
        versionCode = 245
        versionName = "2.12.1"
        // 不设 abiFilters：libvlc-all 含全架构，保持完整以确保兼容性
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { viewBinding = true }

    // 按 ABI 拆分：设备只下载自己架构的 so。
    // arm64 手机约 95MB、32 位约 92MB，比 185MB 的胖包小一半。
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    // LibVLC - 与 VLC 官方 App 同源解码器，兼容 MP2 等冷门编码
    implementation("org.videolan.android:libvlc-all:3.7.6")

    // 纯 JVM 单测：只覆盖 StreamUrlBuilder 这类无 Android 依赖的纯函数
    testImplementation("junit:junit:4.13.2")
}
