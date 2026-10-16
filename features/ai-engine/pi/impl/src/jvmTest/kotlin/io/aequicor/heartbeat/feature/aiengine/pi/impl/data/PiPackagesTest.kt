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
import kotlin.test.assertTrue

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

        assertEquals(listOf(listOf("remove", "npm:old"), listOf("install", "git:github.com/a/b")), commands)
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
        val written = Json.parseToJsonElement(home.resolve("settings.json").readText()).jsonObject
        assertEquals(setOf("packages", "npmCommand"), written.keys)
    }

    @Test
    fun `sync replaces an npm version and a git ref without removing the replacements`() = runTest {
        val old = listOf("npm:tools@1", "git:github.com/owner/tools@v1")
        val desired = listOf("npm:tools@2", "git:github.com/owner/tools@v2")
        val installed = old.toMutableList()
        fun saveInstalled() {
            val packages = JsonArray(installed.map(::JsonPrimitive))
            home.resolve("settings.json").writeText("{\"packages\":$packages}")
        }
        old.forEach { source ->
            val directory = checkNotNull(installedPackagePath(source, home)).createDirectories()
            directory.resolve("source").writeText(source)
        }
        saveInstalled()
        // Pi persists and removes by identity; versions and refs share the same installation directory.
        val manager = PiPackages(home) { arguments ->
            val source = arguments[1]
            val directory = checkNotNull(installedPackagePath(source, home))
            installed.removeAll { installedPackagePath(it, home) == directory }
            when (arguments[0]) {
                "remove" -> directory.toFile().deleteRecursively()

                "install" -> {
                    directory.createDirectories().resolve("source").writeText(source)
                    installed += source
                }

                else -> error("Unexpected package command")
            }
            saveInstalled()
            0
        }

        manager.apply(PackageOperation.Sync, config(JsonArray(desired.map(::JsonPrimitive)).toString())) { _, _ -> }

        assertEquals(desired, installed)
        desired.forEach { source ->
            val directory = checkNotNull(installedPackagePath(source, home))
            assertTrue(Files.isDirectory(directory))
            assertEquals(source, directory.resolve("source").readText())
        }
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
