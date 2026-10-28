import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing, from keystore.properties (git-ignored, kept next to the key outside the repo).
// Without it, release builds come out unsigned.
val keystore = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f -> Properties().apply { f.inputStream().use(::load) } }

android {
    namespace = "com.iurika.videoflows"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.iurika.videoflows"
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "0.0.7"
        // ponytail: 64-bit only keeps the APK small; add "armeabi-v7a" (or ABI splits) for old 32-bit phones
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = file(keystore.getProperty("storeFile"))
            storePassword = keystore.getProperty("storePassword")
            keyAlias = keystore.getProperty("keyAlias")
            keyPassword = keystore.getProperty("keyPassword")
        }
    }
    buildTypes {
        release { signingConfig = signingConfigs.findByName("release") }
    }
    // F-Droid and reproducible builds: no Google-only blobs in the APK
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
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
