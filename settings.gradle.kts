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
        exclusiveContent {
            forRepository {
                ivy {
                    name = "HeartbeatJcef"
                    url = uri("https://github.com/aequicor/Heartbeat/releases/download")
                    // Keep IDE sources/javadoc requests separate from the verified binary JARs.
                    patternLayout { artifact("[revision]/[artifact]-[revision](-[classifier]).[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeGroup("io.aequicor.heartbeat.jcef") }
        }
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

// Release stages transfer only task outputs across hosts, separately from native toolchains.
buildCache {
    local {
        providers.environmentVariable("HEARTBEAT_BUILD_CACHE_DIR").orNull?.let {
            directory = file(it)
        }
    }
}

include(":core:logging")
include(":core:mvi")
include(":features:welcome:api", ":features:welcome:impl")
include(
    ":features:questionnaire:api",
    ":features:questionnaire:impl",
    ":features:questionnaire:screen-model",
    ":features:questionnaire:screen-ui",
)
include(":features:ai-studio:api", ":features:ai-studio:impl", ":features:ai-studio:screen-model", ":features:ai-studio:screen-ui")
include(":features:search-engine:api", ":features:search-engine:impl")
include(":features:toggles-panel:api", ":features:toggles-panel:impl")
include(":features:settings:api", ":features:settings:impl")
include(":features:app-logs:api", ":features:app-logs:impl")
include(":core:common")
include(":core:di:api")
include(":core:di:ext")
include(":core:di:impl")
include(":bundles:agent-runtime")
include(":bundles:engines")
include(":bundles:shell")
include(":bundles:studio")
include(":bundles:web")
include(":platform-main:launcher")
include(":core:bundles:api")
include(":core:bundles:impl")
include(":core:bundles:spike-fixture")
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
include(":chromium-plugin:panel")

include(":core:secrets:api", ":core:secrets:impl")

include(":core:desktop-dialogs")

include(":features:ai-engine:authenticator:api", ":features:ai-engine:authenticator:impl")
include(":features:ai-engine:facade:api", ":features:ai-engine:facade:impl")
include(":features:ai-engine:pi:api")
include(":features:ai-engine:pi:impl")

include(":features:ai-engine:koog:api", ":features:ai-engine:koog:impl")
include(":features:ai-engine:connections:api", ":features:ai-engine:connections:impl")
include(":features:ai-engine:control-panel:api", ":features:ai-engine:control-panel:impl")
include(":features:ai-engine:acp-interface:api", ":features:ai-engine:acp-interface:impl")
include(":features:ai-engine:claude:api")
include(":features:ai-engine:claude:impl")
include(":features:ai-session-engine-transfer:api", ":features:ai-session-engine-transfer:impl")
include(":features:effort-configuration:api", ":features:effort-configuration:impl")
include(":features:auto-continue:api")
include(":features:context-window-optimization:api", ":features:context-window-optimization:impl")
include(":features:worktree-mode:api", ":features:worktree-mode:impl")
include(":features:feedback:api", ":features:feedback:impl")

include(":features:ai-engine:codex:api")
include(":features:ai-engine:codex:impl")
include(":features:research-chat:api", ":features:research-chat:impl")
include(":features:computer-use:api", ":features:computer-use:impl")
include(":features:attachments:api", ":features:attachments:impl")
include(":features:agent-learning:api", ":features:agent-learning:impl")
include(":features:adaptive-subagents:api", ":features:adaptive-subagents:impl")
include(":features:autocomplete:api", ":features:autocomplete:impl")
include(":features:organic-ai:api", ":features:organic-ai:impl")
include(":features:plant-uml-support:api", ":features:plant-uml-support:impl")
include(":features:scheduler:api", ":features:scheduler:impl")

include(
    ":features:checklist:api",
    ":features:checklist:impl",
    ":features:checklist:screen-model",
    ":features:checklist:screen-ui",
)
include(":features:harness:api", ":features:harness:impl")
include(":features:browser:api", ":features:browser:impl")
include(":features:chromium-bridge:api", ":features:chromium-bridge:impl", ":features:chromium-bridge:protocol")
include(":features:app-update:api", ":features:app-update:impl")
include(":features:service-as-ui:api", ":features:service-as-ui:impl")
include(":features:project-config:api", ":features:project-config:impl")
include(":features:planning-mode:api", ":features:planning-mode:impl")
