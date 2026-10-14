rootProject.name = "build-logic"

// Included builds must use the same portable cache as the main build in release jobs.
buildCache {
    local {
        providers.environmentVariable("HEARTBEAT_BUILD_CACHE_DIR").orNull?.let {
            directory = file(it)
        }
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

include(":convention")
