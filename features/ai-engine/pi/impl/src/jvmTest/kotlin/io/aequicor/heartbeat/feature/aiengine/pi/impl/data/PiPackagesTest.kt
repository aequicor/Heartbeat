package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.PackageOperation
import io.aequicor.heartbeat.feature.aiengine.facade.api.write
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PiPackagesTest {
    private val home = Files.createTempDirectory("pi-home")
    private val commands = mutableListOf<List<String>>()

    @AfterTest
    fun cleanUp() {
        home.toFile().deleteRecursively()
    }

    @Test
    fun `sync installs missing sources and removes unlisted ones`() = runTest {
        home.resolve("settings.json").writeText("""{"packages": ["npm:old", "npm:kept"], "theme": "dark"}""")
        val progress = mutableListOf<Pair<Int, Int>>()

        packages().apply(
            PackageOperation.Sync,
            config("""["npm:kept", "git:github.com/a/b", "./local"]""", npm = true),
        ) { done, total ->
            progress += done to total
        }

        assertEquals(listOf(listOf("install", "git:github.com/a/b"), listOf("remove", "npm:old")), commands)
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
        val written = Json.parseToJsonElement(home.resolve("settings.json").readText()).jsonObject
        assertEquals(setOf("packages", "npmCommand"), written.keys)
    }

    @Test
    fun `update runs the package update`() = runTest {
        packages().apply(PackageOperation.Update, config("""["npm:x"]""")) { _, _ -> }

        assertEquals(listOf(listOf("update", "--extensions")), commands)
    }

    @Test
    fun `a failing command fails the job`() = runTest {
        val error = assertFailsWith<ManagementException> {
            PiPackages(home) { 1 }.apply(PackageOperation.Sync, config("""["npm:x"]""")) { _, _ -> }
        }
        assertEquals(ManagementFailure.Install(InstallFailureReason.PackageFailed), error.failure)
    }

    @Test
    fun `remote sources map to the folders Pi installs them in`() {
        assertEquals(home.resolve("npm/node_modules/@scope/name"), installedPackagePath("npm:@scope/name@1.2.0", home))
        assertEquals(home.resolve("npm/node_modules/plain"), installedPackagePath("npm:plain@2", home))
        assertEquals(
            home.resolve("git/github.com/owner/repo"),
            installedPackagePath("git:github.com/owner/repo@v1", home),
        )
        assertEquals(
            home.resolve("git/github.com/owner/repo"),
            installedPackagePath("https://github.com/owner/repo.git", home),
        )
        assertNull(installedPackagePath("./local", home))
        assertNull(installedPackagePath("git:github.com/../etc", home))
    }

    @Test
    fun `a launch loads installed packages from their folders and leaves out missing ones`() = runTest {
        home.resolve("npm/node_modules/tools").createDirectories()
        val values = config("""["npm:tools@1", {"source": "npm:absent", "skills": []}, "pkg"]""")

        val settings = piAgentConfig(values, home) { null }.files.getValue(SETTINGS_FILE)

        val list = Json.parseToJsonElement(settings).jsonObject.getValue("packages") as JsonArray
        assertEquals(
            listOf(
                JsonPrimitive(home.resolve("npm/node_modules/tools").toString()),
                JsonPrimitive(home.resolve("pkg").toString()),
            ),
            list.toList(),
        )
    }

    private fun packages() = PiPackages(home) { arguments ->
        commands += arguments
        0
    }

    private fun config(packages: String, npm: Boolean = false): EngineConfigValues {
        val values = EngineConfigValues().write(settings("packages"), Json.parseToJsonElement(packages))
        return if (npm) values.write(settings("npmCommand"), Json.parseToJsonElement("""["pnpm"]""")) else values
    }

    private fun settings(key: String) = ConfigBinding.JsonPath(PiDocuments.Settings, listOf(key))
}
