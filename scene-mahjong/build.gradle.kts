plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

fun gradleProperty(name: String, fallback: String): String =
    providers.gradleProperty(name).orNull?.trim()?.takeIf(String::isNotEmpty) ?: fallback

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.example.ai_assist.scene.mahjong"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 31
        buildConfigField(
            "String",
            "MAHJONG_CLOUD_SERVER_BASE_URL",
            gradleProperty("CLOUD_SERVER_BASE_URL", "http://39.96.67.192:8552/")
                .asBuildConfigString()
        )
    }

    buildFeatures {
        buildConfig = true
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
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation(libs.junit)
}
