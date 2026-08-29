// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.paparazzi) apply false
}

// Repository configuration is handled in settings.gradle.kts

subprojects {
    // Common configuration for all modules
    ext {
        set("compileSdkVersion", 35)
        set("minSdkVersion", 21)
        set("targetSdkVersion", 34)
    }
}