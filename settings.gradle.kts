pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo1.maven.org/maven2")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://cache-redirector.jetbrains.com/dl.google.com/dl/android/maven2")
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://cache-redirector.jetbrains.com/dl.google.com/dl/android/maven2")
        maven("https://repo1.maven.org/maven2")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}

rootProject.name = "Her"
include(":app")
