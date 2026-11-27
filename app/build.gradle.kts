import java.util.Properties

plugins {
    id("com.android.application")
    // Kotlin plugin is provided by AGP 9.x's built-in Kotlin support.
}

val gifApiKey: String = run {
    val local = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    (providers.gradleProperty("GIF_API_KEY").orNull
        ?: System.getenv("GIF_API_KEY")
        ?: local.getProperty("GIF_API_KEY")
        ?: "").trim().filter { it.isLetterOrDigit() || it == '-' || it == '_' }
}

android {
    namespace = "com.shade.keyboard"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.shade.keyboard"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "0.6"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIF_API_KEY", "\"$gifApiKey\"")
    }

    signingConfigs {
        matching { it.name == "debug" }.configureEach {
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
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
            }
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
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
            signingConfig =
                if (signingConfigs.getByName("release").storeFile != null)
                    signingConfigs.getByName("release")
                else
                    signingConfigs.getByName("debug")
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
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Verifies the release APK with apksigner (v2 + v3 schemes required)."

    val apkProvider = layout.buildDirectory.file("outputs/apk/release/app-release.apk")

    doLast {
        val apk = apkProvider.get().asFile
        require(apk.exists()) {
            "Release APK not found at ${apk.path} — assembleRelease must run before verifyReleaseSigning."
        }
        val sdkRoot = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
            ?: run {
                // Android Studio users often have only sdk.dir in local.properties.
                val lp = rootProject.file("local.properties")
                if (lp.exists()) {
                    val p = java.util.Properties().apply { lp.inputStream().use { load(it) } }
                    p.getProperty("sdk.dir")
                } else null
            }
        require(!sdkRoot.isNullOrBlank()) {
            "ANDROID_HOME / ANDROID_SDK_ROOT is not set and local.properties has no sdk.dir — cannot locate apksigner."
        }
        val buildToolsDir = File(sdkRoot, "build-tools")
        val apksigner = buildToolsDir.listFiles()
            // Numeric-component sort: lexicographic would rank "9.x" above "37.x".
            ?.sortedWith(compareByDescending { d -> d.name.split('.').map { it.toIntOrNull() ?: 0 } })
            ?.firstNotNullOfOrNull { dir ->
                listOf("apksigner", "apksigner.bat").map { File(dir, it) }.firstOrNull { it.isFile }
            }
        require(apksigner != null) {
            "apksigner not found under ${buildToolsDir.path} — install build-tools via the SDK manager."
        }
        val proc = ProcessBuilder(apksigner.absolutePath, "verify", "--verbose", apk.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        println(output.trim())
        require(exit == 0) { "apksigner verify failed (exit $exit) for ${apk.name}." }
        fun schemeOk(scheme: Int) =
            output.contains(Regex("Verified using v$scheme scheme.*: true", RegexOption.IGNORE_CASE))
        require(schemeOk(2) && schemeOk(3)) {
            "Release APK must verify with v2 AND v3 signature schemes. apksigner reported:\n$output"
        }
        // CI release builds fall back to the debug keystore; a tagged release must never ship that.
        val isTaggedRelease = System.getenv("GITHUB_REF")?.startsWith("refs/tags/") == true
        if (isTaggedRelease) {
            require(!output.contains("CN=Android Debug")) {
                "Tagged release APK is debug-signed — CI fell back to the debug keystore."
            }
        }
        println("verifyReleaseSigning: ${apk.name} verified (v2 + v3 schemes, certs printed above).")
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy("verifyReleaseSigning")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.autofill:autofill:1.1.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.asynclayoutinflater:asynclayoutinflater:1.1.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.1")
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
