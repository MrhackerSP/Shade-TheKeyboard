import java.util.Properties

plugins {
    id("com.android.application")
    // Kotlin plugin is provided by AGP 9.x's built-in Kotlin support.
}

// KLIPY GIF API key, baked into BuildConfig. Looked up (first match wins) in:
//   1. -PKLIPY_API_KEY=... / ~/.gradle/gradle.properties
//   2. the KLIPY_API_KEY environment variable (CI)
//   3. KLIPY_API_KEY=... in local.properties (git-ignored)
// Without a key the GIF button is hidden. Note that a key inside an APK can always be extracted,
// so use a key that is restricted/rate-limited on KLIPY's side and never one with other privileges.
val klipyApiKey: String = run {
    val local = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    (providers.gradleProperty("KLIPY_API_KEY").orNull
        ?: System.getenv("KLIPY_API_KEY")
        ?: local.getProperty("KLIPY_API_KEY")
        ?: "").trim().filter { it.isLetterOrDigit() || it == '-' || it == '_' }
}

android {
    namespace = "com.noircipher.shade"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.noircipher.shade"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "KLIPY_API_KEY", "\"$klipyApiKey\"")
    }

    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                val props = Properties().apply {
                    propsFile.inputStream().use { load(it) }
                }
                storeFile = file(props["storeFile"] as String)
                storePassword = props["storePassword"] as String
                keyAlias = props["keyAlias"] as String
                keyPassword = props["keyPassword"] as String
            } else {
                storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("ort", "tflite", "onnx")
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*"
            )
        }
    }

    testOptions {
        // Android stubs return defaults instead of throwing, so pure-logic classes that
        // touch android.* types can still be unit-tested on the JVM.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

// AGP 9.x's built-in Kotlin uses the top-level `kotlin {}` block
// instead of the old `android.kotlinOptions {}` DSL. jvmTarget is
// inherited from `android.compileOptions` automatically, so we
// only need to keep the opt-in flag here.
kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // The Android SDK's org.json is a stub in local unit tests; KlipyParser needs the real one.
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    // Material Components for Android — needed for Material 3 settings UI
    // (MaterialSwitch, Slider, MaterialButtonToggleGroup, Theme.Material3.*).
    implementation("com.google.android.material:material:1.12.0")
    // Styling helpers for inline autofill chips (password / address suggestions in the strip).
    implementation("androidx.autofill:autofill:1.1.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.asynclayoutinflater:asynclayoutinflater:1.1.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    // RecyclerView for the card-based settings list.
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
