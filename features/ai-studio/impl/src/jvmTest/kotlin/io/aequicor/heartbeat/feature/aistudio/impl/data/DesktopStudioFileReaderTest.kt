package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioFileContent
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioFileReader
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DesktopStudioFileReaderTest {
    private val root: Path = Files.createTempDirectory("studio-file-reader")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `text files are read with their modification time`() = runTest {
        val file = root.resolve("plan.md").apply { writeText("# План\n") }
        val content = assertIs<StudioFileContent.Text>(reader().read(file.toString()))
        assertEquals("# План\n", content.text)
        assertEquals(content.modifiedAt, reader().modifiedAt(file.toString()))
    }

    @Test
    fun `binary large missing and directory targets are not previewed as text`() = runTest {
        val binary = root.resolve("a.bin").apply { writeBytes(byteArrayOf(1, 0, 2)) }
        val large = root.resolve("big.txt").apply {
            writeBytes(ByteArray((StudioFileReader.MAX_PREVIEW_BYTES + 1).toInt()) { 'a'.code.toByte() })
        }
        val directory = root.resolve("dir").createDirectory()
        assertEquals(StudioFileContent.Binary, reader().read(binary.toString()))
        assertIs<StudioFileContent.TooLarge>(reader().read(large.toString()))
        assertEquals(StudioFileContent.Unavailable, reader().read(root.resolve("missing").toString()))
        assertEquals(StudioFileContent.Unavailable, reader().read(directory.toString()))
        assertNull(reader().modifiedAt(directory.toString()))
    }

    @Test
    fun `paths resolve against the base the home or as absolute`() {
        val base = root.toString()
        assertEquals(root.resolve("src/a.kt").toString(), resolveLocalPath("src/../src/a.kt", base, null))
        assertEquals(root.resolve("x.md").toString(), resolveLocalPath("~/x.md", null, base))
        assertEquals(root.resolve("abs.md").toString(), resolveLocalPath(root.resolve("abs.md").toString(), null, null))
        assertNull(resolveLocalPath("relative.md", null, base))
        assertNotNull(resolveLocalPath("~", null, base))
    }

    private fun TestScope.reader() = DesktopStudioFileReader(
        StandardTestDispatcher(testScheduler).let { dispatcher ->
            object : DispatcherProvider {
                override val main = dispatcher
                override val default = dispatcher
                override val io = dispatcher
            }
        },
    )
}
