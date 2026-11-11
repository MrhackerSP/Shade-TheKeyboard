# Keep the IME service entry point and all activities referenced from the
# manifest by name (R8 would otherwise rename them and break inflation).
-keep class com.noircipher.shade.ShadeService { *; }
-keep class com.noircipher.shade.MainActivity { *; }
-keep class com.noircipher.shade.SettingsActivity { *; }
-keep class com.noircipher.shade.SettingsActivity$* { *; }
-keep class com.noircipher.shade.SettingsAdapter$* { *; }
-keep class com.noircipher.shade.SettingsTree { *; }
-keep class com.noircipher.shade.SettingsItem { *; }
-keep class com.noircipher.shade.SettingsItem$* { *; }
-keep class com.noircipher.shade.SettingsPage { *; }
-keep class com.noircipher.shade.TypingStatsActivity { *; }
-keep class com.noircipher.shade.engine.SmartComposeEngine { *; }
-keep class com.noircipher.shade.TranslatorPanel { *; }
-keep class com.noircipher.shade.ClipboardSuggestions { *; }

# Reflection-free code, so we can afford aggressive optimizations
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''

# Strip verbose logging in release. Keep Log.w / Log.e so real failures stay
# visible in bug reports (adb logcat).
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Keep preference XML inflation
-keep class * extends androidx.preference.Preference { *; }
-keep class * extends androidx.preference.PreferenceFragmentCompat { *; }

-keep class ai.onnxruntime.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keepclassmembers class ai.onnxruntime.** { *; }
-keepclassmembers class org.tensorflow.lite.** { *; }

# kotlinx.coroutines — R8 can strip the volatile fields that coroutine
# internals rely on, causing hard-to-debug crashes (CancellationException,
# "Job was cancelled", NPEs in select/Channel). Keep them.
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keep class kotlinx.coroutines.android.AndroidExceptionPreHandler { *; }
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }

# Keep enum values used by reflection (ONNX / TFLite use enums for options)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep the BuildConfig class (some libs reflect on it)
-keep class com.noircipher.shade.BuildConfig { *; }
