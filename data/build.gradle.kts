// :data — Room entities, DAOs, repository, Health Connect client.
// Empty at M1; persistence arrives in M3. See docs/architecture.md section 5.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.zdredge.consistency.data"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":domain"))
}
