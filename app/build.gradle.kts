plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

fun gradleProperty(name: String, fallback: String): String =
    providers.gradleProperty(name).orNull?.trim()?.takeIf(String::isNotEmpty) ?: fallback

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.example.ai_assist"
    compileSdk {
        version = release(36)
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.example.ai_assist.refactor"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField(
            "String",
            "CLOUD_SERVER_BASE_URL",
            gradleProperty("CLOUD_SERVER_BASE_URL", "http://39.96.67.192:8552/")
                .asBuildConfigString()
        )
        buildConfigField(
            "String",
            "CLOUD_API_KEY",
            gradleProperty("CLOUD_API_KEY", "local-token").asBuildConfigString()
        )
        buildConfigField(
            "String",
            "LOCAL_API_KEY",
            gradleProperty("LOCAL_API_KEY", "local-token").asBuildConfigString()
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
    implementation(project(":sdk:sdk-core"))
    implementation(project(":scene-mahjong"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // 引入 libs 目录下的所�?aar 文件
    implementation(fileTree("libs"))

    // 引入rayneo文档要求的其他必要依赖项 [cite: 12]
    implementation("androidx.core:core-ktx:1.8.0")
    implementation("androidx.appcompat:appcompat:1.3.0")
    implementation("androidx.fragment:fragment-ktx:1.5.3")
    implementation("androidx.activity:activity-ktx:1.3.0-alpha08")
    implementation("androidx.recyclerview:recyclerview:1.1.0")

    // Kotlin 协程相关 [cite: 12]
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.1")

    // Network
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")
    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")

    // Image Loading
    implementation("io.coil-kt:coil:2.4.0")

    // CameraX
    val camerax_version = "1.3.0"
    implementation("androidx.camera:camera-core:${camerax_version}")
    implementation("androidx.camera:camera-camera2:${camerax_version}")
    implementation("androidx.camera:camera-lifecycle:${camerax_version}")
    implementation("androidx.camera:camera-view:${camerax_version}")
}
