plugins { id("com.android.library") }

android {
    namespace = "com.vowifi.sw.root"
    compileSdk = 37
    defaultConfig { minSdk = 33 }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("com.github.topjohnwu.libsu:service:6.0.0")
}
