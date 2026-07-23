plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes a monotonically increasing version so the in-app updater can tell
// when a newer build exists. Falls back to 1 for local builds.
val ciVersionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
val ciVersionName = System.getenv("VERSION_NAME") ?: "1.0.$ciVersionCode"

android {
    namespace = "com.plutoforce.tapsave"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.plutoforce.tapsave"
        minSdk = 29
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = ciVersionName
    }

    // Fixed, committed key so every build shares one signature and updates
    // install over the top. Self-signed — fine for sideloading.
    signingConfigs {
        create("shared") {
            storeFile = file("tapsave.keystore")
            storePassword = "tapsave"
            keyAlias = "tapsave"
            keyPassword = "tapsave"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // FileProvider for handing the downloaded update APK to the installer.
    implementation("androidx.core:core:1.13.1")
}
