plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.noircipher.shade"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.noircipher.shade"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // core-ktx APIs merged into core as of 1.19.0
    implementation("androidx.core:core:1.19.1")

    // AppCompat kept at 1.7.0 to preserve minSdk 21 (1.8.0 forces minSdk 23)
    implementation("androidx.appcompat:appcompat:1.7.0")

    implementation("androidx.preference:preference-ktx:1.2.1")

    // Async view inflation to eliminate first-show jank
    implementation("androidx.asynclayoutinflater:asynclayoutinflater:1.1.0")
}
