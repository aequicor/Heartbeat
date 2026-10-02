package io.aequicor.heartbeat.core.desktopdialogs

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NativeDesktopFileDialogsTest {
    @Test
    fun `the host dialog answers and a cancelled dialog stays empty`() = runTest {
        val dialogs = RecordingDialogs()
        val service = service(StandardTestDispatcher(testScheduler), dialogs)

        dialogs.directory = "/Users/dev/Project"
        assertEquals("/Users/dev/Project", service.pickDirectory(title = "Project"))

        dialogs.files = listOf("C:\\Research\\Документ 1.pdf", "C:\\Research\\photo.jpg")
        assertEquals(
            listOf("C:\\Research\\Документ 1.pdf", "C:\\Research\\photo.jpg"),
            service.pickFiles(extensions = listOf("pdf", "jpg"), allowMultiple = true),
        )

        dialogs.files = emptyList()
        assertEquals(emptyList(), service.pickFiles())
        assertEquals(
            listOf("folder Project", "files pdf,jpg multiple=true", "files  multiple=false"),
            dialogs.calls,
        )

        dialogs.saved = "C:\\Research\\export.pdf"
        assertEquals("C:\\Research\\export.pdf", service.pickSaveLocation(suggestedName = "export.pdf"))

        dialogs.saved = null
        assertNull(service.pickSaveLocation(suggestedName = "export.pdf"))
    }

    @Test
    fun `a broken native backend reaches the caller`() = runTest {
        val dialogs = RecordingDialogs().apply { failure = IllegalStateException("COM is unavailable") }
        val service = service(StandardTestDispatcher(testScheduler), dialogs)
        assertFailsWith<IllegalStateException> { service.pickDirectory() }
    }

    @Test
    fun `cancellation of the dialog call is passed through unchanged`() = runTest {
        val dialogs = RecordingDialogs().apply { failure = CancellationException("scope closed") }
        val service = service(StandardTestDispatcher(testScheduler), dialogs)
        assertFailsWith<CancellationException> { service.pickFiles() }
    }

    private fun service(dispatcher: CoroutineDispatcher, dialogs: NativeDialogs): NativeDesktopFileDialogs =
        NativeDesktopFileDialogs(
            platform = object : PlatformInfo {
                override val host = HostPlatform.Windows
            },
            dispatchers = object : DispatcherProvider {
                override val main = dispatcher
                override val default = dispatcher
                override val io = dispatcher
            },
            dialogs = dialogs,
        )
}

private class RecordingDialogs : NativeDialogs {
    var directory: String? = null
    var files: List<String> = emptyList()
    var saved: String? = null
    var failure: Throwable? = null
    val calls = mutableListOf<String>()

    override fun pickDirectory(title: String?): String? = record("folder $title") { directory }

    override fun pickFiles(title: String?, extensions: List<String>, allowMultiple: Boolean): List<String> =
        record("files ${extensions.joinToString(",")} multiple=$allowMultiple") { files }

    override fun pickSaveLocation(title: String?, suggestedName: String?, extensions: List<String>): String? =
        record("save $suggestedName ${extensions.joinToString(",")}") { saved }

    private fun <T> record(call: String, result: () -> T): T {
        calls += call
        failure?.let { throw it }
        return result()
    }
}
