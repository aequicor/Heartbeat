package io.aequicor.heartbeat.buildlogic

import androidx.room.gradle.RoomExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType

/**
 * `heartbeat.room` — a module with its own Room database (a feature `impl`): [KmpLibraryConventionPlugin],
 * the Room Gradle plugin with schemas in `<module>/schemas` (committed), the Room compiler (KSP) for every target,
 * and `:core:datastore:api` — the database is opened by `DataStores.database(DatabaseSpec)`, never by `Room.*` directly.
 */
class RoomConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        pluginManager.apply("heartbeat.kmp.library")
        pluginManager.apply("com.google.devtools.ksp")
        pluginManager.apply("androidx.room")

        val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

        extensions.configure<RoomExtension> {
            schemaDirectory("$projectDir/schemas")
        }

        val roomCompiler = libs.findLibrary("androidx-room-compiler").get()
        KSP_CONFIGURATIONS.forEach { configuration -> dependencies.add(configuration, roomCompiler) }
        dependencies.add("commonMainImplementation", dependencies.project(mapOf("path" to DATASTORE_API)))
        Unit
    }

    private companion object {
        const val DATASTORE_API = ":core:datastore:api"

        /** Room generates the database implementation per target: KSP has to run for each of them. */
        val KSP_CONFIGURATIONS = listOf("kspAndroid", "kspJvm", "kspIosArm64", "kspIosSimulatorArm64")
    }
}
