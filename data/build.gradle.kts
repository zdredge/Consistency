// :data — Room entities, DAOs, repository, and (from M7) the Health Connect client.
// See docs/architecture.md section 5 for the schema this module realises.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "com.zdredge.consistency.data"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 34
        // :data's tests are instrumented and run against the real device (docs/CLAUDE.md testing).
        // Room migrations and non-trivial queries are exactly the things a stubbed SQLite would lie
        // about, so they are proven on the SQLite that ships on the Pixel.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

room {
    // The exported schema JSON is committed. It is what makes a migration test possible at all: a
    // migration test builds the OLD schema from these files, so without them there is nothing to
    // migrate FROM and schema changes become unverifiable after the fact.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":domain"))

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.room.testing)
}
