plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val packagedSceneModules = rootProject.subprojects
    .filter { it.path.startsWith(":scene-") }
    .sortedBy { it.path }

android {
    namespace = "com.example.ai_assist"
    compileSdk {
        version = release(36)
    }
    buildToolsVersion = "36.0.0"

    buildFeatures {
        viewBinding = true
    }

    defaultConfig {
        applicationId = "com.example.ai_assist"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".refactored.hufix"
            versionNameSuffix = "-refactored"
        }
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
    implementation(project(":sdk:sdk-bundle"))
    packagedSceneModules.forEach { sceneModule ->
        implementation(project(sceneModule.path))
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation("com.google.zxing:core:3.5.3")
    implementation(files("../sdk/sdk-device-rayneo-x3/libs/MercuryAndroidSDK-v0.2.3-20260104184638_9002bcc8.aar"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
}
