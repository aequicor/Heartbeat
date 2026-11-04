package io.aequicor.heartbeat.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import javax.inject.Inject

/** Identity of the embedded SDK requested by the connector, always sourced from the version catalog. */
abstract class EmbeddedEngineContractExtension @Inject constructor(objects: ObjectFactory) {
    val runtimeGroup: Property<String> = objects.property(String::class.java)
    val runtimeVersion: Property<String> = objects.property(String::class.java)
}

/** Generates JVM-only build facts; Native/Android must never substitute these facts for their actual target. */
class EmbeddedEngineContractConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        require(path.startsWith(":features:ai-engine:") && path.endsWith(":impl"))
        val contract = extensions.create<EmbeddedEngineContractExtension>("embeddedEngineContract")
        val external = configurations.getByName("jvmRuntimeClasspath").incoming.artifactView {
            componentFilter { it is ModuleComponentIdentifier }
        }.artifacts
        val resolved = external.resolvedArtifacts
        val sharedInputs = rootProject.fileTree(rootDir) {
            include("gradle/libs.versions.toml", "gradle.properties", "settings.gradle.kts", "build.gradle.kts")
            include("build-logic/settings.gradle.kts", "build-logic/convention/build.gradle.kts")
            include("build-logic/gradle.properties", "build-logic/convention/src/main/kotlin/**")
            exclude("**/build/**")
        }
        val hostInputs = rootProject.fileTree(rootDir) {
            include("features/ai-engine/facade/*/src/*Main/kotlin/**", "features/ai-engine/facade/*/build.gradle.kts")
            include("features/ai-engine/authenticator/*/src/*Main/kotlin/**")
            include("features/ai-engine/authenticator/*/build.gradle.kts")
            include("core/**/src/*Main/kotlin/**", "core/**/build.gradle.kts")
            include("features/**/api/src/*Main/kotlin/**", "features/**/api/build.gradle.kts")
            exclude("**/build/**")
        }
        val ownInputs = fileTree(projectDir) {
            include("src/*Main/kotlin/**", "build.gradle.kts")
        }
        val apiInputs = fileTree(projectDir.parentFile.resolve("api")) {
            include("src/*Main/kotlin/**", "build.gradle.kts")
        }
        val namespace = "io.aequicor.heartbeat.feature." + path.removePrefix(":features:").split(':')
            .joinToString(".") { it.replace("-", "") } + ".data.generated"
        val generate = tasks.register<WriteEmbeddedEngineContract>("generateEmbeddedEngineContract") {
            sourceRoot.set(rootProject.layout.projectDirectory)
            facadeSources.from(hostInputs, sharedInputs)
            connectorSources.from(ownInputs, apiInputs, sharedInputs)
            sdkArtifacts.from(external.artifactFiles)
            coordinates.set(resolved.map { artifacts ->
                require(artifacts.map { it.file.name }.distinct().size == artifacts.size) { "Ambiguous SDK names" }
                artifacts.associate { it.file.name to it.id.componentIdentifier.displayName }
            })
            variants.set(resolved.map { artifacts -> artifacts.associate { it.file.name to it.variant.displayName } })
            runtimeGroup.set(contract.runtimeGroup)
            runtimeVersion.set(contract.runtimeVersion)
            packageName.set(namespace)
            outputDirectory.set(layout.buildDirectory.dir("generated/embeddedEngineContract"))
        }
        extensions.getByType<KotlinMultiplatformExtension>().sourceSets.getByName("jvmMain").kotlin.srcDir(
            generate.map { it.outputDirectory },
        )
    }
}
