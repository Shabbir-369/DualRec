plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.dualrec"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.dualrec"
        minSdk = 30
        // 32 on purpose: BluetoothAdapter.enable() (silent "turn Bluetooth on") is blocked for apps targeting 33+.
        // If Android still refuses, QuickStartActivity falls back to the normal system prompt.
        targetSdk = 32
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "2.0"
    }
    // Same signing key on every build, so a new APK installs over the old one without uninstalling.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
