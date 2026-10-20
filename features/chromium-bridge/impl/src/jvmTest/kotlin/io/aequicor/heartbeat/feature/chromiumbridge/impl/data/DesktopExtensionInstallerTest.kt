package io.aequicor.heartbeat.feature.chromiumbridge.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.network.release.ReleaseArtifact
import io.aequicor.heartbeat.core.network.release.VerifiedArtifactCache
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExtensionPreparation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopExtensionInstallerTest {
    private val root = Files.createTempDirectory("heartbeat-extension-install")
    private val sha = "a".repeat(64)
    private val archive = root.resolve("component.zip")
    private val manifest = Files.readString(
        Path.of(System.getProperty("heartbeat.chromiumPlugin.root"), "manifest.json"),
    )
    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Unconfined
        override val default = Dispatchers.Unconfined
        override val io = Dispatchers.Unconfined
    }

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `bridge startup is local and explicit preparation publishes verified content for offline reuse`() = runTest {
        archive()
        var calls = 0
        val progress = mutableListOf<ExtensionPreparation>()
        val first = installer { calls++ }
        assertNull(first.locate())
        assertEquals(0, calls)
        val folder = first.prepare { progress += it }
        assertEquals(1, calls)
        assertTrue(ExtensionPreparation.Extracting in progress)
        val offline = installer { error("Offline reuse must not reach the cache downloader") }
        assertEquals(folder, offline.locate())
        assertEquals(folder, offline.prepare {})
        assertTrue(Files.isRegularFile(folder.resolve("app/panel.wasm")))
    }

    @Test
    fun `altered payload is unavailable and an explicit retry repairs it without replacing a loaded folder`() =
        runTest {
            archive()
            val installer = installer()
            val old = installer.prepare {}
            Files.writeString(old.resolve("app/panel.js"), "corrupt")
            assertNull(installer.locate())
            val fresh = installer.prepare {}
            assertNotEquals(old, fresh)
            assertEquals("panel", Files.readString(fresh.resolve("app/panel.js")))
            assertEquals("corrupt", Files.readString(old.resolve("app/panel.js")))
        }

    @Test
    fun `offline failure and cancelled extraction publish no partial folder and allow retry`() = runTest {
        archive()
        val offline = installer { error("offline") }
        assertFailsWith<IllegalStateException> { offline.prepare {} }
        assertNull(offline.locate())
        val retry = installer()
        assertFailsWith<CancellationException> {
            retry.prepare { if (it == ExtensionPreparation.Extracting) throw CancellationException("closed") }
        }
        assertNull(retry.locate())
        assertTrue(Files.isDirectory(retry.prepare {}))
        assertTrue(Files.walk(root).use { it.noneMatch { path -> path.fileName.toString().startsWith(".extract-") } })
    }

    @Test
    fun `wrong identity or missing panel is never published`() = runTest {
        archive(manifest = manifest.replace("\"manifest_version\": 3", "\"manifest_version\": 2"))
        val installer = installer()
        assertFailsWith<IllegalArgumentException> { installer.prepare {} }
        assertNull(installer.locate())
        archive(manifest = manifest.replace("MIIBIjAN", "NIIBIjAN"))
        assertFailsWith<IllegalArgumentException> { installer.prepare {} }
        assertNull(installer.locate())
        archive(includeWasm = false)
        assertFailsWith<IllegalArgumentException> { installer.prepare {} }
        assertNull(installer.locate())
    }

    @Test
    fun `unexpected files and links invalidate the offline cache`() = runTest {
        archive()
        val installer = installer()
        val folder = installer.prepare {}
        Files.writeString(folder.resolve("injected.js"), "unexpected")
        assertNull(installer.locate())
        Files.delete(folder.resolve("injected.js"))
        Files.delete(folder.resolve("app/panel.js"))
        Files.createSymbolicLink(folder.resolve("app/panel.js"), archive)
        assertNull(installer.locate())
    }

    @Test
    fun `a linked cache root is rejected before writing outside the cache`() = runTest {
        archive()
        val outside = Files.createDirectory(root.resolve("outside"))
        Files.createSymbolicLink(root.resolve("cache"), outside)
        assertFailsWith<Exception> { installer().prepare {} }
        assertTrue(Files.list(outside).use { it.findAny().isEmpty })
    }

    @Test
    fun `installed resource paths fit the Chromium Windows path limit`() = runTest {
        archive()
        val folder = installer().prepare {}
        val relative = root.resolve("cache").relativize(folder).toString().replace('\\', '/')
        val storage = "C:/Users/extension-user/AppData/Roaming/Aequicor/Heartbeat/cache/chromium-extension"
        val resource = "app/composeResources/io.aequicor.heartbeat.chromiumplugin.panel.resources/" +
            "files/fonts/NotoSansSymbols2-Regular.ttf"
        assertTrue("$storage/$relative/$resource".length < 260)
        assertEquals(folder, installer().locate())
    }

    @Test
    fun `cache prefix collisions still verify the full artifact digest and retain loaded folders`() = runTest {
        archive()
        val first = installer()
        val original = first.prepare {}
        val second = installer(expectedSha = sha.take(16) + "b".repeat(48))
        assertNull(second.locate())
        val replacement = second.prepare {}
        assertNotEquals(original, replacement)
        assertEquals(replacement, second.locate())
        assertNull(first.locate())
        assertTrue(Files.isRegularFile(original.resolve("app/panel.js")))
    }

    private fun installer(expectedSha: String = sha, before: () -> Unit = {}): DesktopExtensionInstaller {
        val cache = object : VerifiedArtifactCache {
            override suspend fun prepare(artifact: ReleaseArtifact, progress: suspend (Long, Long?) -> Unit): Path {
                before()
                assertEquals(expectedSha, artifact.download.sha256)
                progress(Files.size(archive), Files.size(archive))
                return archive
            }
        }
        return DesktopExtensionInstaller(root.resolve("cache"), cache, dispatchers) {
            "version=1.2.3\nurl=https://github.com/aequicor/Heartbeat/releases/download/v1.2.3/extension.zip\n" +
                "sha256=$expectedSha\nsize=${Files.size(archive)}\n"
        }
    }

    private fun archive(manifest: String = this.manifest, includeWasm: Boolean = true) {
        val files = linkedMapOf(
            "manifest.json" to manifest,
            "src/sidepanel/panel.html" to "panel html",
            "src/background/service-worker.js" to "worker",
            "app/panel.js" to "panel",
        )
        if (includeWasm) files["app/panel.wasm"] = "wasm"
        ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            for ((name, content) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
    }
}
