plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "demo.herdr"
    compileSdk = 35
    defaultConfig {
        applicationId = "demo.herdr"
        minSdk = 26
    }
}

dependencies {
    implementation(project(":core"))
}
