plugins {
    alias(libs.plugins.heartbeat.kmp.compose)
    alias(libs.plugins.heartbeat.metro)
    alias(libs.plugins.heartbeat.room)
    alias(libs.plugins.kotlinSerialization)
}

compose.resources { packageOfResClass = "io.aequicor.heartbeat.feature.harness.impl.resources" }

// The worker loads original Maven artifacts by SHA-256; a Kotlin upgrade needs a deliberate lock refresh.
check(libs.versions.kotlin.get() == "2.4.20") {
    "Refresh HarnessCompilerArtifacts and its pinned Kotlin version before upgrading the harness compiler"
}

kotlin {
    sourceSets {
        jvmMain.dependencies {
            implementation(projects.core.network.api)
            implementation(libs.kotlin.compiler.embeddable)
            implementation(libs.kotlin.scripting.common)
            implementation(libs.kotlin.scripting.jvm)
            implementation(libs.kotlin.scripting.jvm.host)
        }
        jvmTest.dependencies {
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.androidx.room.testing)
            implementation(libs.compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
        commonTest.dependencies {
            implementation(libs.flowmvi.test)
        }
        commonMain.dependencies {
            implementation(projects.features.projectConfig.api)
            implementation(projects.features.harness.api)
            implementation(projects.features.adaptiveSubagents.api)
            implementation(projects.features.aiEngine.facade.api)
            implementation(projects.features.organicAi.api)
            implementation(projects.features.scheduler.api)
            implementation(projects.features.serviceAsUi.api)
            implementation(projects.features.worktreeMode.api)
            implementation(projects.core.common)
            implementation(projects.core.logging)
            implementation(projects.core.di.api)
            implementation(projects.core.di.ext)
            implementation(projects.core.datastore.api)
            implementation(projects.core.secrets.api)
            implementation(projects.core.featureToggles.api)
            implementation(projects.core.profileFacade.api)
            implementation(projects.core.stateMachine.api)
            implementation(projects.core.stateMachine.flowmviExt)
            implementation(projects.core.mvi)
            implementation(projects.core.navigation.compose)
            implementation(projects.designSystem.components)
            implementation(projects.designSystem.theme)
            implementation(projects.designSystem.layouts)
            implementation(libs.kotlinx.collectionsImmutable)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
        }
    }
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
}

// The repository-owned harness is compiled and executed by LifecycleBoardTest.
tasks.named<Test>("jvmTest") {
    inputs.dir(rootProject.layout.projectDirectory.dir(".heartbeat/harnesses/product_lifecycle"))
}
