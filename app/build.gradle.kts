plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.videoflows"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.videoflows"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.1"
        // ponytail: 64-bit only keeps the APK small; add "armeabi-v7a" (or ABI splits) for old 32-bit phones
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.videolan.android:libvlc-all:3.7.7")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
}
