package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.feature.appupdate.api.BundleBuild
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopAppBuildInfoSourceTest {
    private val sha = "a".repeat(64)

    private fun source(root: Path?, train: String?, version: String? = "1.4.0") = DesktopAppBuildInfoSource(
        LaunchProperties(
            mapOf(
                "heartbeat.app.version" to version,
                "heartbeat.train" to train,
                "heartbeat.launcher.root" to root?.toString(),
            )::get,
        ),
        TestDispatchers,
    )

    private fun writeManifest(root: Path, train: String, text: String) {
        val dir = Files.createDirectories(root.resolve("trains").resolve(train))
        Files.writeString(dir.resolve("manifest.json"), text)
    }

    @Test
    fun `bundles come from the manifest of the running train`() = runTest {
        val root = Files.createTempDirectory("build-info")
        writeManifest(root, "t1", manifestJson("t1", listOf("studio.jar" to sha, "web.jar" to sha)))
        val info = source(root, "t1").read()
        assertEquals("1.4.0", info.version)
        assertEquals("t1", info.train)
        assertFalse(info.isBeta)
        assertEquals(listOf(BundleBuild("studio", "1.0"), BundleBuild("web", "1.0")), info.bundles)
    }

    @Test
    fun `a beta channel mark is reported`() = runTest {
        val root = Files.createTempDirectory("build-info")
        val beta = manifestJson("t1", listOf("a.jar" to sha)).replace("{\"train\"", "{\"channel\":\"beta\",\"train\"")
        writeManifest(root, "t1", beta)
        assertTrue(source(root, "t1").read().isBeta)
    }

    @Test
    fun `a missing or broken manifest leaves the bundles empty`() = runTest {
        val root = Files.createTempDirectory("build-info")
        assertEquals(emptyList(), source(root, "t1").read().bundles)
        writeManifest(root, "t1", "not json")
        val info = source(root, "t1").read()
        assertEquals("t1", info.train)
        assertEquals(emptyList(), info.bundles)
    }

    @Test
    fun `a launch without a launcher or a version states only what it has`() = runTest {
        val info = source(root = null, train = null, version = null).read()
        assertNull(info.version)
        assertNull(info.train)
        assertEquals(emptyList(), info.bundles)
    }

    @Test
    fun `a train id that names a path is not followed`() = runTest {
        val root = Files.createTempDirectory("build-info")
        assertEquals(emptyList(), source(root, "../escape").read().bundles)
    }
}
