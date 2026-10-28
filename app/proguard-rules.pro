# Keep the IME service entry point (referenced from the manifest by name)
-keep class com.noircipher.shade.ShadeService { *; }
-keep class com.noircipher.shade.MainActivity { *; }
-keep class com.noircipher.shade.SettingsActivity { *; }
-keep class com.noircipher.shade.SettingsActivity$SettingsFragment { *; }

# Reflection-free code, so we can afford aggressive optimizations
-optimizationpasses 5
-allowaccessmodification
-repackageclasses ''

# Strip logging in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Keep preference XML inflation
-keep class * extends androidx.preference.Preference { *; }
