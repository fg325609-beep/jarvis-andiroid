plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "uz.farhod.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "uz.farhod.jarvis"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") } // APK hajmini kamaytiradi
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "onnx" }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
