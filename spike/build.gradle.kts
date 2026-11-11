plugins {
    alias(libs.plugins.android.application)
}

// Renderer spike (spec §12.3, M2): the same test app twice, once on Mapsforge and once on VTM, to
// measure them on the G15. Throwaway: never distributed, removed once the decision is made.
android {
    namespace = "io.github.lrosa2.singletrail.spike"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.lrosa2.singletrail.spike"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "spike"
    }

    flavorDimensions += "renderer"
    productFlavors {
        create("mapsforge") {
            dimension = "renderer"
            applicationIdSuffix = ".mapsforge"
            manifestPlaceholders["label"] = "Spike Mapsforge"
        }
        create("vtm") {
            dimension = "renderer"
            applicationIdSuffix = ".vtm"
            manifestPlaceholders["label"] = "Spike VTM"
        }
    }

    buildTypes {
        release {
            // Measured like a real release (not debuggable). Signed with the debug key: a
            // throwaway build that never leaves the test phone.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val mapsforge = "0.30.0"

dependencies {
    implementation(project(":core:gpx"))
    implementation(project(":core:nav"))

    "mapsforgeImplementation"("com.github.mapsforge.mapsforge:mapsforge-map-android:$mapsforge")
    "mapsforgeImplementation"("com.github.mapsforge.mapsforge:mapsforge-themes:$mapsforge") // default theme, for comparison

    "vtmImplementation"("com.github.mapsforge.vtm:vtm-android:$mapsforge")
    "vtmImplementation"("com.github.mapsforge.vtm:vtm-themes:$mapsforge") // default theme, for comparison
    // The native helper library: arm64 for the G15, x86_64 for the emulator.
    "vtmRuntimeOnly"("com.github.mapsforge.vtm:vtm-android:$mapsforge:natives-arm64-v8a")
    "vtmRuntimeOnly"("com.github.mapsforge.vtm:vtm-android:$mapsforge:natives-x86_64")
}
