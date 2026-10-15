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

    // RayNeo SDK AARs.
    implementation(fileTree("libs"))

    // Local AARs do not carry a Maven dependency graph; keep their AndroidX runtime requirements.
    implementation("androidx.activity:activity-ktx:1.3.0-alpha08")
    implementation("androidx.fragment:fragment-ktx:1.5.3")
    implementation("androidx.recyclerview:recyclerview:1.1.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
}
