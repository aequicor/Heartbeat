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

include(":core:logging")
include(":core:mvi")
include(":features:welcome:api", ":features:welcome:impl")
include(":features:questionnaire:api", ":features:questionnaire:impl")
include(":features:ai-studio:api", ":features:ai-studio:impl")
include(":features:search-engine:api", ":features:search-engine:impl")
include(":features:toggles-panel:api", ":features:toggles-panel:impl")
include(":features:settings:api", ":features:settings:impl")
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
include(":platform-main:shared")
include(":platform-main:android")
include(":platform-main:desktop")

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

include(":core:secrets:api", ":core:secrets:impl")

include(":features:ai-engine:authenticator:api", ":features:ai-engine:authenticator:impl")
include(":features:ai-engine:facade:api", ":features:ai-engine:facade:impl")
include(":features:ai-engine:pi:api")
include(":features:ai-engine:pi:impl")

include(":features:ai-engine:koog:api", ":features:ai-engine:koog:impl")
include(":features:ai-engine:connections:api", ":features:ai-engine:connections:impl")
include(":features:ai-engine:acp-interface:api", ":features:ai-engine:acp-interface:impl")
include(":features:ai-engine:claude:api")
include(":features:ai-engine:claude:impl")
include(":features:ai-session-engine-transfer:api", ":features:ai-session-engine-transfer:impl")
include(":features:effort-configuration:api", ":features:effort-configuration:impl")
include(":features:feedback:api", ":features:feedback:impl")

include(":features:ai-engine:codex:api")
include(":features:ai-engine:codex:impl")
include(":features:research-chat:api", ":features:research-chat:impl")
