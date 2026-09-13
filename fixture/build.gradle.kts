// :fixture — generated history for verification (spec O7, build-order M9). Test scaffolding only.
//
// **Never on the real app's classpath.** :app takes this as `fixtureImplementation`, so it is compiled
// into the separate `fixture` build type (app id com.zdredge.consistency.fixture) and into neither
// debug nor release. The real app on the phone is the debug build and holds real data; a tool that
// clears the database has no business inside it.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.zdredge.consistency.fixture"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":data"))

    // The loader writes straight to the tables and clears them first, which needs Room itself;
    // :data keeps Room as `implementation`, so it is not inherited.
    implementation(libs.room.runtime)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
