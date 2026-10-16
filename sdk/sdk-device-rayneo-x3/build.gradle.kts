plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.ai_assist.sdk.device.rayneo.x3"
    compileSdk { version = release(36) }

    defaultConfig { minSdk = 31 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    api(project(":sdk:sdk-core"))
    api(project(":sdk:sdk-platform-android"))
    compileOnly(files("libs/MercuryAndroidSDK-v0.2.3-20260104184638_9002bcc8.aar"))
}
