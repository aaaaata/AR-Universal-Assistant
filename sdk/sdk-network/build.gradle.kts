plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.ai_assist.sdk.network"
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
    api(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    testImplementation(libs.junit)
}
