plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.aiden.screentopdf"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aiden.screentopdf"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
            "/META-INF/INDEX.LIST",
            "/META-INF/*.kotlin_module",
            "/META-INF/versions/9/previous-compilation-data.bin",
        )
    }
}

dependencies {
    // Compose UI
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // CameraX (video recording + preview)
    val cameraxVersion = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Permissions helper for Compose
    implementation("com.google.accompanist:accompanist-permissions:0.36.0")

    // OpenCV (image processing: screen detection, perspective fix, cleanup)
    implementation("org.opencv:opencv:4.10.0")

    // ML Kit on-device text recognition (OCR for the searchable text layer)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Anthropic Claude API (official Java SDK, works from Kotlin)
    implementation("com.anthropic:anthropic-java:2.34.0")

    // Encrypted storage for the API key
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Google Play services tasks -> coroutines bridge
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
