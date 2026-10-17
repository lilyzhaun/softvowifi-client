import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation(libs.androidx.room3.runtime)
    testImplementation(libs.androidx.sqlite.framework)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.serialization.json)
}

val clientVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}

android {
    namespace = "com.vowifi.sw"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vowifi.sw"
        minSdk = 33
        targetSdk = 36
        versionCode = clientVersion.getProperty("versionCode").toInt()
        versionName = clientVersion.getProperty("versionName")
    }

    sourceSets.getByName("main") {
        java.srcDir("../../observer/src")
        res.srcDir("../../observer/res")
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    testOptions.unitTests.isIncludeAndroidResources = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin.compilerOptions.jvmTarget = JvmTarget.JVM_17

tasks.withType<Test>().configureEach {
    javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
    maxHeapSize = "384m"
    maxParallelForks = 1
    jvmArgs("-XX:ActiveProcessorCount=1")
    systemProperty("java.io.tmpdir", temporaryDir.absolutePath)
    systemProperty("message.schema.directory", rootProject.file("shared/schemas").absolutePath)
    systemProperty("cellular.source.directory", project.file("src/main/java/com/vowifi/sw/network").absolutePath)
}
