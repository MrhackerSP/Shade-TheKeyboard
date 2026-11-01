import java.util.Properties

plugins {
    id("com.android.application")
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
    }

    // Release signing comes ONLY from keystore.properties. Without it the release
    // build is left unsigned (app-release-unsigned.apk) on purpose: it must never be
    // silently signed with the debug key and then shipped by mistake.
    val keystoreProps = rootProject.file("keystore.properties")
    val hasReleaseKeystore = keystoreProps.exists()

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                val props = Properties().apply {
                    keystoreProps.inputStream().use { load(it) }
                }
                storeFile = file(props["storeFile"] as String)
                storePassword = props["storePassword"] as String
                keyAlias = props["keyAlias"] as String
                keyPassword = props["keyPassword"] as String
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
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
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

    // Keep model assets uncompressed so AssetMmap can memory-map them.
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

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.asynclayoutinflater:asynclayoutinflater:1.1.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
