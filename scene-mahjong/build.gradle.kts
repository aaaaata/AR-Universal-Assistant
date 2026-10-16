import java.net.URI

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

fun gradleProperty(name: String): String? =
    providers.gradleProperty(name).orNull?.trim()?.takeIf(String::isNotEmpty)

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val serverEnvironment = gradleProperty("ASSIST_SERVER_ENV")?.lowercase() ?: "cloud"
require(serverEnvironment == "cloud" || serverEnvironment == "local") {
    "ASSIST_SERVER_ENV must be either 'cloud' or 'local'"
}

val cloudServerBaseUrl = gradleProperty("CLOUD_SERVER_BASE_URL")
    ?: gradleProperty("CLOUD_BASE_URL")
    ?: "http://39.96.67.192:8552/"
val localServerBaseUrl = gradleProperty("LOCAL_SERVER_BASE_URL")
    ?: "http://10.0.2.2:8000/"
val serverBaseUrl = gradleProperty("ASSIST_SERVER_BASE_URL") ?: when (serverEnvironment) {
    "local" -> localServerBaseUrl
    else -> cloudServerBaseUrl
}
require(serverBaseUrl.endsWith('/')) {
    "The selected server base URL must end with '/': $serverBaseUrl"
}
val serverUri = runCatching { URI(serverBaseUrl) }
    .getOrElse { throw GradleException("Invalid server base URL: $serverBaseUrl", it) }
require(serverUri.scheme in setOf("http", "https") && !serverUri.host.isNullOrBlank()) {
    "The selected server base URL must be an absolute HTTP(S) URL: $serverBaseUrl"
}

val assistApiKey = gradleProperty("ASSIST_API_KEY")
    ?: when (serverEnvironment) {
        "local" -> gradleProperty("LOCAL_API_KEY")
        else -> gradleProperty("CLOUD_API_KEY")
    }
    ?: "local-token"
val cloudApiKey = gradleProperty("CLOUD_API_KEY") ?: assistApiKey
val localApiKey = gradleProperty("LOCAL_API_KEY") ?: "local-token"

android {
    namespace = "com.example.ai_assist.scene.mahjong"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "ASSIST_SERVER_ENV", serverEnvironment.asBuildConfigString())
        buildConfigField("String", "ASSIST_SERVER_BASE_URL", serverBaseUrl.asBuildConfigString())
        buildConfigField("String", "ASSIST_API_KEY", assistApiKey.asBuildConfigString())
        buildConfigField("String", "CLOUD_SERVER_BASE_URL", cloudServerBaseUrl.asBuildConfigString())
        buildConfigField("String", "LOCAL_SERVER_BASE_URL", localServerBaseUrl.asBuildConfigString())
        buildConfigField("String", "CLOUD_API_KEY", cloudApiKey.asBuildConfigString())
        buildConfigField("String", "LOCAL_API_KEY", localApiKey.asBuildConfigString())
    }

    buildFeatures {
        viewBinding = true
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
    api(project(":sdk:sdk-cv"))
    implementation(project(":sdk:sdk-network"))
    implementation(libs.gson)
    testImplementation(libs.junit)
}
