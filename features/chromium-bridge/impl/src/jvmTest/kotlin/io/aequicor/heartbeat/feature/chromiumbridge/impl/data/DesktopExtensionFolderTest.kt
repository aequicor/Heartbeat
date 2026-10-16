package io.aequicor.heartbeat.feature.chromiumbridge.impl.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopExtensionFolderTest {
    private val root = Files.createTempDirectory("hb-extension")
    private val checkout = extension(root.resolve("checkout"))
    private val resources = extension(root.resolve("resources"))
    private val module = Files.createDirectories(root.resolve("checkout/features/browser"))

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `a run from a checkout uses the repository folder`() = runTest {
        val folder = locate("user.dir" to module.toString(), "compose.application.resources.dir" to resources.parent)

        assertEquals(checkout.toString(), folder)
    }

    @Test
    fun `an installed build uses its resources even inside a checkout`() = runTest {
        val folder = locate(
            "jpackage.app-path" to "/Applications/Heartbeat.app",
            "user.dir" to module.toString(),
            "compose.application.resources.dir" to resources.parent,
        )

        assertEquals(resources.toString(), folder)
    }

    @Test
    fun `a packaged development build prepares its bundled extension without a checkout or downloader`() = runTest {
        root.resolve("checkout").toFile().deleteRecursively()
        val properties = mapOf(
            "jpackage.app-path" to root.resolve("app/Heartbeat.exe").toString(),
            "user.dir" to root.resolve("outside-checkout").toString(),
            "compose.application.resources.dir" to resources.parent.toString(),
        )
        val folder = DesktopExtensionFolder(Dispatchers.Unconfined, properties::get)

        assertEquals(resources.toString(), folder.prepare { error("Bundled extension must not download") })
    }

    @Test
    fun `a folder without a manifest is not an extension`() = runTest {
        Files.delete(resources.resolve("manifest.json"))

        assertNull(locate("jpackage.app-path" to "/app", "compose.application.resources.dir" to resources.parent))
    }

    private suspend fun locate(vararg properties: Pair<String, Any>): String? {
        val values = properties.associate { (key, value) -> key to value.toString() }
        return DesktopExtensionFolder(Dispatchers.Unconfined, values::get).locate()
    }

    private fun extension(parent: Path): Path {
        val folder = Files.createDirectories(parent.resolve("chromium-plugin"))
        Files.writeString(folder.resolve("manifest.json"), "{}")
        return folder
    }
}
