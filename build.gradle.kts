// Top-level build file. Plugins are declared here and applied in the modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    // M3: Room needs an annotation processor, and the Room Gradle plugin declares the schema
    // export directory in a way the configuration cache tolerates (it is on, see gradle.properties).
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
