import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.heartbeat.detekt)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// One app version for all builds (gradle.properties, or the release tag's version in the release workflow).
val heartbeatVersion = providers.gradleProperty("heartbeat.version").get()
val heartbeatVersionParts = requireNotNull(Regex("([1-9][0-9]*)\\.([0-9]+)\\.([0-9]+)").matchEntire(heartbeatVersion)) {
    "heartbeat.version must be MAJOR.MINOR.PATCH with a major of at least 1: $heartbeatVersion"
}.destructured.toList().map(String::toInt)

dependencies {
    implementation(projects.platformMain.shared)

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "io.aequicor.heartbeat.platform.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // Идентификатор приложения в сторах: остаётся "io.aequicor" независимо от пакета Kotlin.
        applicationId = "io.aequicor"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Monotonic for MAJOR.MINOR.PATCH while minor and patch stay below 1000.
        versionCode = heartbeatVersionParts.let { (major, minor, patch) -> major * 1_000_000 + minor * 1_000 + patch }
        versionName = heartbeatVersion
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    // The release workflow signs with the key of the GitHub environment android-signing; without these variables
    // (local builds, dry runs) the release APK stays unsigned.
    val releaseKeystore = providers.environmentVariable("HEARTBEAT_ANDROID_KEYSTORE").orNull
    val releaseSigning = releaseKeystore?.let {
        signingConfigs.create("release") {
            storeFile = file(it)
            storePassword = providers.environmentVariable("HEARTBEAT_ANDROID_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("HEARTBEAT_ANDROID_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("HEARTBEAT_ANDROID_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        release {
            signingConfig = releaseSigning
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}