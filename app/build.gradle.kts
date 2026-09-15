import java.time.Duration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

fun envOrProp(name: String): String? =
    System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() }
        ?: (project.findProperty(name) as String?)?.trim()?.takeIf { it.isNotEmpty() }

android {
    namespace = "com.her"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.her"
        minSdk = 29
        targetSdk = 36
        versionCode = envOrProp("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = envOrProp("VERSION_NAME") ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val releaseStoreFilePath = envOrProp("RELEASE_STORE_FILE")
    if (releaseStoreFilePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFilePath)
                storePassword = envOrProp("RELEASE_STORE_PASSWORD")
                keyAlias = envOrProp("RELEASE_KEY_ALIAS")
                keyPassword = envOrProp("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseStoreFilePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "proguard-debug.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.systemProperty("java.net.preferIPv4Stack", "true")
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty(
                "robolectric.dependency.dir",
                layout.buildDirectory.dir("robolectric-jars").get().asFile.absolutePath,
            )
            it.filter.excludeTestsMatching("com.her.scenario.ScenarioEngineTest")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.security.crypto)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.play.services.auth)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.room.testing)
}

val robolectricRuntime = configurations.create("robolectricRuntime")
dependencies {
    add(robolectricRuntime.name, libs.robolectric.android.all.instrumented)
}

val prefetchRobolectricJars = tasks.register<Copy>("prefetchRobolectricJars") {
    from(robolectricRuntime)
    into(layout.buildDirectory.dir("robolectric-jars"))
}

tasks.withType<Test>().configureEach {
    dependsOn(prefetchRobolectricJars)
    systemProperty("her.repoRoot", rootProject.projectDir.absolutePath)
}

afterEvaluate {
    val unitTest = tasks.named<Test>("testDebugUnitTest")
    unitTest.configure {
        filter {
            excludeTestsMatching("com.her.scenario.ScenarioEngineTest")
        }
    }

    tasks.register<Test>("scenarioTest") {
        group = "verification"
        description = "Runs the scenario pool against the live LLM from .env (slow, manual)."
        val source = unitTest.get()
        testClassesDirs = source.testClassesDirs
        classpath = source.classpath
        setDependsOn(source.dependsOn)
        systemProperty("java.net.preferIPv4Stack", "true")
        systemProperty("robolectric.offline", "true")
        systemProperty(
            "robolectric.dependency.dir",
            layout.buildDirectory.dir("robolectric-jars").get().asFile.absolutePath,
        )
        systemProperty("her.repoRoot", rootProject.projectDir.absolutePath)
        listOf("scenario.only", "scenario.filter", "scenario.attempts", "scenario.parallel", "scenario.mode").forEach { key ->
            val value = project.findProperty(key)?.toString().orEmpty()
            if (value.isNotEmpty()) {
                systemProperty(key, value)
            }
        }
        filter {
            includeTestsMatching("com.her.scenario.ScenarioEngineTest")
        }
        outputs.upToDateWhen { false }
        testLogging {
            showStandardStreams = true
            events("passed", "skipped", "failed", "standardOut", "standardError")
        }
        timeout.set(Duration.ofMinutes(45))
    }
}
