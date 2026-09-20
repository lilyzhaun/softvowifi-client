import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room3)
}

kotlin {
    android {
        namespace = "com.vowifi.sw.manager.shared"
        compileSdk {
            version = release(37)
        }
        minSdk = 33
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            api(libs.androidx.navigation3.runtime)
            implementation(libs.androidx.navigationevent.compose)
            implementation(libs.miuix.ui)
            implementation(libs.miuix.icons)
            implementation(libs.miuix.navigation3.ui)
            implementation(libs.miuix.preference)
            implementation(libs.miuix.blur)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        getByName("commonTest").kotlin.srcDir("src/publicTest/kotlin")
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.room3.runtime)
            implementation(libs.androidx.sqlite)
            implementation(libs.androidx.sqlite.framework)
        }
        val desktopMain by getting {
            dependencies {
            implementation(compose.desktop.currentOs)
            }
        }
    }
}

dependencies {
    add("kspAndroid", libs.androidx.room3.compiler)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

compose.desktop {
    application {
        mainClass = "com.vowifi.sw.manager.desktop.MainKt"
    }
}
