pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Mapsforge and VTM stopped publishing to Maven Central after 0.25.0 (OSSRH shut down);
        // JitPack builds them from their GitHub tags. Only those groups may come from there.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroupByRegex("com\\.github\\.mapsforge.*") }
        }
    }
}

rootProject.name = "Singletrail"

include(":app")
include(":core:gpx")
include(":core:nav")

// The renderer spike (M2, spec §12.3) is a throwaway test app, built only on request:
// ./gradlew -Pspike :spike:assembleMapsforgeRelease :spike:assembleVtmRelease
if (providers.gradleProperty("spike").isPresent) include(":spike")
