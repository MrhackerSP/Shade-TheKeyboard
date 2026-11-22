import java.util.Properties

plugins {
    id("com.android.application")
    // Kotlin plugin is provided by AGP 9.x's built-in Kotlin support.
}

// GIF provider API key, baked into BuildConfig. Looked up (first match wins) in:
//   1. -PGIF_API_KEY=... / ~/.gradle/gradle.properties
//   2. the GIF_API_KEY environment variable (CI)
//   3. GIF_API_KEY=... in local.properties (git-ignored)
// Without a key the GIF button is hidden. Note that a key inside an APK can always be extracted,
// so use a key that is restricted/rate-limited at the provider and never one with other privileges.
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
        // Release-line discipline: versionName must track the GitHub release
        // tag (Updater.kt compares `releases/latest` tag vs this versionName —
        // a local versionName GREATER than the latest tag silently suppresses
        // every update). The published prerelease was "0.5" (Beta), so the
        // next cut is 0.6; bump both numbers together with each tag.
        versionCode = 6
        versionName = "0.6"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "GIF_API_KEY", "\"$gifApiKey\"")
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
            // Signature-scheme discipline (mirrors the Expressive release line):
            // minSdk 26 installs with v2 alone, so v1 (jar signing) is dead weight
            // and a known downgrade surface; v2 is the integrity baseline and v3
            // adds the signed certificate chain so key rotation stays possible
            // later without shipping a new applicationId.
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
        // Include the merged app resources so Robolectric IME smoke tests can resolve
        // app strings/layouts exactly like a real device build would.
        unitTests.isIncludeAndroidResources = true
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

// Release gate: the published APK must verify under apksigner with the v2 AND
// v3 schemes actually enabled. Runs automatically after assembleRelease, so a
// signing regression fails the build instead of surfacing as an un-installable
// or downgrade-able artifact. Uses the build-tools apksigner directly (no AGP
// internal API), highest installed build-tools version wins.
tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Verifies the release APK with apksigner (v2 + v3 schemes required)."
    doLast {
        val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        require(apk.exists()) {
            "Release APK not found at ${apk.path} — assembleRelease must run before verifyReleaseSigning."
        }
        val sdkRoot = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        require(!sdkRoot.isNullOrBlank()) {
            "ANDROID_HOME / ANDROID_SDK_ROOT is not set — cannot locate apksigner for release verification."
        }
        val buildToolsDir = File(sdkRoot, "build-tools")
        val apksigner = buildToolsDir.listFiles()
            ?.sortedByDescending { it.name }
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
            output.contains(Regex("Verified using v$scheme scheme\\s*:\\s*true", RegexOption.IGNORE_CASE))
        require(schemeOk(2) && schemeOk(3)) {
            "Release APK must verify with v2 AND v3 signature schemes. apksigner reported:\n$output"
        }
        println("verifyReleaseSigning: ${apk.name} verified (v2 + v3 schemes, certs printed above).")
    }
}

// AGP creates variant tasks (including assembleRelease) inside afterEvaluate,
// which runs AFTER this script's top level. A direct tasks.named() lookup here
// therefore crashes configuration on Gradle 8.13+/9 ("Task with name
// 'assembleRelease' not found in project ':app'") — for every command, not
// just release builds. A matching rule is lazy: it attaches the finalize hook
// the moment AGP registers the task, and is a safe no-op if the release
// variant is ever absent.
tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy("verifyReleaseSigning")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // The Android SDK's org.json is a stub in local unit tests; GifParser needs the real one.
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
    // Robolectric drives the real IME lifecycle (create / onCreateInputView / onStartInputView /
    // layout / draw) on the JVM, so a startup crash in ShadeService shows up as a test failure.
    testImplementation("org.robolectric:robolectric:4.16.1")
}
