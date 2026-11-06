plugins {
    // AGP 9.x ships Kotlin support built-in; do NOT declare
    // org.jetbrains.kotlin.android here or it collides with AGP's
    // own `kotlin` extension and the build fails with:
    //   "Cannot add extension with name 'kotlin', as there is an
    //    extension already registered with that name."
    id("com.android.application") version "9.4.0" apply false
}
