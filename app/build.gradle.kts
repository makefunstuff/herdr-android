import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
// GitHub Actions passes a secret that isn't set as an empty string, so empty counts as unset.
fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotEmpty() }

val releaseKeystore = env("HERDR_KEYSTORE_FILE")?.let { path ->
    // A half-configured key must fail the build rather than quietly ship a debug-signed APK.
    listOf("HERDR_KEYSTORE_PASSWORD", "HERDR_KEY_ALIAS").filter { env(it) == null }.let { missing ->
        if (missing.isNotEmpty()) throw GradleException("HERDR_KEYSTORE_FILE is set but ${missing.joinToString()} is not")
    }
    file(path).also { if (!it.isFile) throw GradleException("Keystore $path not found") }
}

android {
    namespace = "com.makefunstuff.herdr"
    compileSdk = 35

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        applicationId = "com.makefunstuff.herdr"
        minSdk = 28
        targetSdk = 35
        // CI's run number grows with every build, so each new APK installs as an update.
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        // The fixed key from the HERDR_KEYSTORE* secrets (see README). Every build signed with it
        // installs over the previous one and keeps the app's data, including its SSH key.
        if (releaseKeystore != null) {
            create("herdr") {
                storeFile = releaseKeystore
                storePassword = env("HERDR_KEYSTORE_PASSWORD")
                keyAlias = env("HERDR_KEY_ALIAS")
                keyPassword = env("HERDR_KEY_PASSWORD") ?: env("HERDR_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            // Without the keystore (local builds, forks) fall back to the debug key so the APK still installs.
            signingConfig = signingConfigs.findByName("herdr") ?: signingConfigs.getByName("debug")
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

    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.makefunstuff.herdr:core")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
