plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("com.vanniktech.maven.publish") version "0.34.0"
}

group = "io.elevenlabs"
version = "0.5.1"

android {
namespace = "io.elevenlabs"
    compileSdk = 35

    defaultConfig {
        minSdk = 21

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        buildConfigField("String", "SDK_VERSION", "\"${project.version}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Lifecycle and Architecture
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)

    // Networking and WebRTC
    implementation(libs.livekit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Testing
    testImplementation(libs.junit)
    testImplementation("io.mockk:mockk:1.13.8")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

mavenPublishing {
    // Enable publishing to Maven Central
    publishToMavenCentral(automaticRelease = true)

    // Enable GPG signing (required for Maven Central)
    signAllPublications()

    // Configure coordinates (group:artifactId:version)
    coordinates("io.elevenlabs", "elevenlabs-android", project.version.toString())

    // Configure POM metadata
    pom {
        name.set("ElevenLabs Android SDK")
        description.set("Android SDK for ElevenLabs Conversational AI")
        inceptionYear.set("2025")
        url.set("https://github.com/elevenlabs/elevenlabs-android")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://github.com/elevenlabs/elevenlabs-android/blob/main/LICENSE")
                distribution.set("https://github.com/elevenlabs/elevenlabs-android/blob/main/LICENSE")
            }
        }

        developers {
            developer {
                id.set("PaulAsjes")
                name.set("Paul Asjes")
                email.set("paul.asjes@elevenlabs.io")
                url.set("https://github.com/PaulAsjes")
            }
        }

        scm {
            url.set("https://github.com/elevenlabs/elevenlabs-android")
            connection.set("scm:git:git://github.com/elevenlabs/elevenlabs-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/elevenlabs/elevenlabs-android.git")
        }
    }
}