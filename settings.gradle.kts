pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ARMahjongAssist"
include(
    ":app",
    ":sdk:sdk-bundle",
    ":sdk:sdk-core",
    ":sdk:sdk-cv",
    ":sdk:sdk-network",
    ":sdk:sdk-platform-android",
    ":sdk:sdk-device-rayneo-x3"
)

// 场景模块按统一命名约定接入，宿主不枚举任何具体业务场景。
rootDir.listFiles()
    .orEmpty()
    .filter { directory ->
        directory.isDirectory &&
            directory.name.startsWith("scene-") &&
            directory.resolve("build.gradle.kts").isFile
    }
    .sortedBy { it.name }
    .forEach { include(":${it.name}") }
