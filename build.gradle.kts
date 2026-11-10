// Top-level build file. Plugins are declared here once and applied per module.
// AGP 9 has built-in Kotlin support for Android modules, so `org.jetbrains.kotlin.android`
// is intentionally NOT used. The kotlin-jvm entry pins the Kotlin Gradle plugin version for
// the whole build (Android modules included).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
