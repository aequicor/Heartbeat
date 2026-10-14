package io.aequicor.heartbeat.buildlogic

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChromiumPanelArtifactTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `complete exported panel can be verified in another directory`() {
        val source = panel()
        ChromiumPanelArtifact.writeManifest(source)
        val imported = temporaryFolder.newFolder()
        source.copyRecursively(imported, overwrite = true)

        ChromiumPanelArtifact.verify(imported)
        assertTrue(imported.resolve("SHA256SUMS").readText().contains("  composeResources/fonts/code.ttf\n"))
    }

    @Test
    fun `import requires the producer manifest`() {
        assertFailsWith<IllegalStateException> { ChromiumPanelArtifact.verify(panel()) }
    }

    @Test
    fun `lost additional resource fails verification even when entry points remain`() {
        val source = panel()
        source.resolve("optional.js").writeText("export const value = 1;")
        ChromiumPanelArtifact.writeManifest(source)
        source.resolve("optional.js").delete()

        assertFailsWith<IllegalStateException> { ChromiumPanelArtifact.verify(source) }
    }

    @Test
    fun `modified and extra files fail verification`() {
        for (name in listOf("panel.js", "unexpected.js")) {
            val source = panel()
            ChromiumPanelArtifact.writeManifest(source)
            source.resolve(name).writeText("different content")

            assertFailsWith<IllegalStateException> { ChromiumPanelArtifact.verify(source) }
        }
    }

    @Test
    fun `export rejects missing and empty required payloads`() {
        for (name in listOf("panel.js", "runtime.wasm", "composeResources/fonts/code.ttf")) {
            val source = panel()
            source.resolve(name).writeBytes(byteArrayOf())

            assertFailsWith<IllegalStateException> { ChromiumPanelArtifact.writeManifest(source) }
        }
        assertFailsWith<IllegalStateException> {
            ChromiumPanelArtifact.verify(temporaryFolder.root.resolve("absent"))
        }
    }

    @Test
    fun `manifest cannot name a file outside the imported directory`() {
        val source = panel()
        ChromiumPanelArtifact.writeManifest(source)
        val manifest = source.resolve("SHA256SUMS")
        manifest.appendText("${"0".repeat(64)}  ../outside.js\n")

        assertFailsWith<IllegalStateException> { ChromiumPanelArtifact.verify(source) }
    }

    private fun panel(): File = temporaryFolder.newFolder().apply {
        resolve("panel.js").writeText("console.log('panel');")
        resolve("runtime.wasm").writeBytes(byteArrayOf(0, 97, 115, 109, 1, 0, 0, 0))
        resolve("composeResources/fonts/code.ttf").apply {
            parentFile.mkdirs()
            writeText("font payload")
        }
    }
}
