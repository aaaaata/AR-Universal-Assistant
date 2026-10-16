plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.ai_assist.sdk.bundle"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 31
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    api(project(":sdk:sdk-core"))
    api(project(":sdk:sdk-cv"))
    api(project(":sdk:sdk-network"))
    api(project(":sdk:sdk-platform-android"))
    api(project(":sdk:sdk-device-rayneo-x3"))
}
