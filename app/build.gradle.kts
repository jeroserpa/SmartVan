plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "van.supervisor.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "van.supervisor.app"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.${versionCode}"
    }

    // Sign with the repo's fixed key when CI provides it, so a new build
    // installs over the old one. Named explicitly: the default
    // ~/.android/debug.keystore was silently ignored on the runner.
    signingConfigs {
        getByName("debug") {
            System.getenv("VAN_KEYSTORE")?.let {
                storeFile = file(it)
                storeType = "pkcs12"
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
