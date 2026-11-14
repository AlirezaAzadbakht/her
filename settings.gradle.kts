// The mirrors are for local builds where Google's hosts are often unreachable. CI (GitHub runners set CI=true)
// goes straight to the official repositories: a mirror that answers 502 fails the build instead of falling through.

pluginManagement {
    repositories {
        gradlePluginPortal()
        if (System.getenv("CI") != "true") {
            maven("https://repo1.maven.org/maven2")
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/central")
            maven("https://cache-redirector.jetbrains.com/dl.google.com/dl/android/maven2")
        }
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI") != "true") {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://cache-redirector.jetbrains.com/dl.google.com/dl/android/maven2")
            maven("https://repo1.maven.org/maven2")
            maven("https://maven.aliyun.com/repository/central")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "Her"
include(":app")
