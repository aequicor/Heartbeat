rootProject.name = "heartbeat"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    includeBuild("build-logic")
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
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":androidApp")
include(":desktopApp")
include(":shared")

include(":core:logging")
include(":core:common")
include(":core:di:api")
include(":core:di:ext")
include(":core:di:impl")
include(":core:network:api")
include(":core:network:impl")
include(":core:navigation:api")
include(":core:navigation:impl")
include(":core:navigation:compose")
include(":core:state-machine:api")
include(":core:state-machine:impl")
include(":core:state-machine:flowmvi-ext")
include(":core:profile-facade:api")
include(":core:profile-facade:impl")
include(":core:datastore:api")
include(":core:datastore:impl")
include(":core:feature-toggles:api")
include(":core:feature-toggles:impl")
include(":platform-main:di-bundle")
include(":platform-main:root")

include(":design-system:tokens")
include(":design-system:adaptive")
include(":design-system:theme")
include(":design-system:resources")
include(":design-system:layouts")
include(":design-system:components")
include(":design-system:catalog")
include(":platform-main:uikit-sandbox:desktop")
include(":platform-main:uikit-sandbox:android")
include(":platform-main:uikit-sandbox:shared")

// Кастомный набор правил detekt (политика логирования и обработки ошибок)
include(":lint:detekt-rules")
