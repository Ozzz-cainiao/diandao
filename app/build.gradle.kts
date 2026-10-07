plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.ozzz.personalagent"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.ozzz.personalagent"
        minSdk = 28
        targetSdk = 36
        versionCode = 13
        versionName = "0.0.13-preview"
    }
    buildFeatures {
        aidl = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Keep the first diagnostic build simple; no reflection-sensitive shrinking yet.
    buildTypes { release { isMinifyEnabled = false } }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
