plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.zdredge.consistency"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.zdredge.consistency"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
        /*
         * The M9 verification fixture, as its own install. **Not debug**: the real app on the phone
         * is the debug build and has held real data since 2026-09-10, so a tool that clears the
         * database cannot live in it. The suffix gives this build its own app id, and with it its
         * own database, alarms and permissions. Install with `installFixture`; `installDebug` is
         * still the real app.
         */
        create("fixture") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".fixture"
            // :data and :fixture only have debug and release.
            matchingFallbacks += "debug"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    // Only the fixture build type. Never debug, never release -- see the build type above.
    "fixtureImplementation"(project(":fixture"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.work.runtime)

    // No test dependencies, deliberately: `:app` has no tests (docs/CLAUDE.md, Testing). Every
    // decision that could be wrong lives in `:domain` or `:data`; previews are this module's check.
    // The wizard's template test and its Compose/Espresso test libraries were removed after M11 --
    // ui-test-manifest had been adding an exported test activity to the debug build, the real app.
    debugImplementation(libs.androidx.compose.ui.tooling)
    "fixtureImplementation"(libs.androidx.compose.ui.tooling)
}