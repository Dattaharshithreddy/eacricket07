plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.harshith.cricket07"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.harshith.cricket07"
        // WebGPU on Android needs Android 12+ (and a recent Chrome / WebView)
        minSdk = 31
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal sideload build: signed with the debug key so `assembleRelease` gives an installable APK.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
// The game bundle is never packaged: it's built from the player's own copy and picked at run time.

// No libraries: plain Android framework only (Activity, WebView, Storage Access Framework).
