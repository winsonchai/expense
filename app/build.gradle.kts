plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
val previewKeystorePath = providers.environmentVariable("EXPENSE_PREVIEW_KEYSTORE").orNull

android {
    namespace = "com.winsonchai.expense"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.winsonchai.expense"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.7.0"
        manifestPlaceholders["appLabel"] = "Expense"
    }
    signingConfigs {
        if (!previewKeystorePath.isNullOrBlank()) create("previewLocal") {
            storeFile = file(previewKeystorePath)
            // Dedicated test key only. Release signing must use separately managed secrets.
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        create("preview") {
            initWith(getByName("debug"))
            if (!previewKeystorePath.isNullOrBlank()) signingConfig = signingConfigs.getByName("previewLocal")
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            matchingFallbacks += listOf("debug")
            manifestPlaceholders["appLabel"] = "Expense Preview"
        }
        create("tapPreview") {
            initWith(getByName("preview"))
            // The distributed 0.4 APK used a different CI key. Keep it and its data installed.
            applicationIdSuffix = ".preview.tap"
            versionNameSuffix = "-tap-preview"
            manifestPlaceholders["appLabel"] = "Expense Tap Preview"
        }
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
