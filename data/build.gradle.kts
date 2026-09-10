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

// The task that copies the exported schema into the androidTest assets does not re-run when the
// schema changes on an incremental build, so the packaged copy goes stale. That matters more than it
// sounds: MigrationTestHelper builds the OLD database from that asset, so a stale copy means a
// migration test silently verifies a migration from a schema that no longer exists. Found by adding
// a column and watching the APK keep the previous schema. Forcing the copy costs a file write.
tasks.matching { it.name.startsWith("copyRoomSchemasToAndroidTestAssets") }.configureEach {
    outputs.upToDateWhen { false }
}

// SchemaVersionPinTest reads the committed schema under data/schemas, which the Room compiler
// writes during KSP. Left implicit, the test can run before that write and assert against the
// previous build's file -- observed doing exactly that when unit and instrumented tests were invoked
// together. A check that can read a stale artifact is not a check, so the ordering is declared.
tasks.withType<Test>().configureEach {
    dependsOn("kspDebugKotlin")
}

dependencies {
    api(project(":domain"))

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    // Every Health Connect call sits behind StepSource in this module, with exactly one
    // implementation -- architecture 5. The interface is for pinning against API churn, not for
    // abstracting over providers; there is no second source and the raw-sensor hatch stays unbuilt.
    implementation(libs.androidx.health.connect)

    // For StepPermissions' ActivityResultContract only. :app must not import Health Connect itself
    // -- the whole point of the interface is that an API change lands in one module (architecture 5),
    // and the permission contract is as much a Health Connect API as the reader is.
    implementation(libs.androidx.activity)

    // JUnit 4 on the JVM, for the schema-file checks that need no device (see SchemaVersionPinTest).
    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.room.testing)
}
