// :domain — pure Kotlin, no Android. See docs/architecture.md section 5.
//
// The Android-free rule is enforced STRUCTURALLY, not by convention: this is a plain Kotlin JVM
// module, so android.jar is simply not on its classpath and an Android import cannot compile.
// DomainHasNoAndroidTest asserts the same thing at runtime as a belt-and-braces check.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    // Matches the Android modules. Mixing a higher target into a Java 11 consumer chain is a
    // needless trap; java.time is available regardless (minSdk 34, no desugaring needed).
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
