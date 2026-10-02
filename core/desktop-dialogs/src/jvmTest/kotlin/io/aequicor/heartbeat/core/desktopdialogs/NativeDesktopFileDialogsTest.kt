package io.aequicor.heartbeat.core.desktopdialogs

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.LogSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
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

    @Test
    fun `queued dialogs cannot overlap or start after cancellation`() = runTest {
        val dismiss = CompletableDeferred<Unit>()
        val dialogs = RecordingDialogs().apply { beforeReturn = { dismiss.await() } }
        val service = service(StandardTestDispatcher(testScheduler), dialogs)
        val first = async { service.pickDirectory() }
        runCurrent()
        val second = async { service.pickFiles() }
        runCurrent()
        assertEquals(listOf("folder null"), dialogs.calls)
        second.cancel()
        dismiss.complete(Unit)
        first.await()
        second.join()
        assertEquals(listOf("folder null"), dialogs.calls)
        service.pickFiles()
        assertEquals(2, dialogs.calls.size)
    }

    @Test
    fun `logs contain outcomes without selected paths or filenames`() = runTest {
        val messages = mutableListOf<String>()
        val sink = LogSink { _, tag, _, message ->
            if (tag == "DesktopFileDialogs") messages += message
        }
        Log.init(isDebug = true, sinks = listOf(sink))
        try {
            val dialogs = RecordingDialogs().apply {
                directory = "/private/research"
                files = listOf("/private/research/secret.pdf")
                saved = "/private/research/export.pdf"
            }
            val service = service(StandardTestDispatcher(testScheduler), dialogs)
            service.pickDirectory()
            service.pickFiles()
            service.pickSaveLocation()
            dialogs.files = emptyList()
            service.pickFiles()
            assertEquals(3, messages.count { "selection=count=1" in it })
            assertTrue(messages.any { "selection=cancelled" in it })
            assertFalse(messages.any { "private" in it || "secret.pdf" in it || "export.pdf" in it })
        } finally {
            Log.init(isDebug = false)
        }
    }

    @Test
    fun `a cancelled caller already on main cannot consume the selection`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val dismiss = CountDownLatch(1)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dialogs = RecordingDialogs().apply {
            directory = "/private/research"
            beforeReturn = {
                onDialogThread {
                    entered.complete(Unit)
                    assertTrue(dismiss.await(10, TimeUnit.SECONDS))
                }
            }
        }
        val service = service(dispatcher, dialogs)
        var consumed = false
        val call = launch(dispatcher) {
            service.pickDirectory()
            consumed = true
        }
        runCurrent()
        entered.await()
        try {
            call.cancel()
            runCurrent()
            assertFalse(call.isCompleted)
        } finally {
            dismiss.countDown()
        }
        call.join()
        assertFalse(consumed)
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
    var beforeReturn: suspend () -> Unit = {}

    override suspend fun pickDirectory(title: String?): String? = record("folder $title") { directory }

    override suspend fun pickFiles(title: String?, extensions: List<String>, allowMultiple: Boolean): List<String> =
        record("files ${extensions.joinToString(",")} multiple=$allowMultiple") { files }

    override suspend fun pickSaveLocation(title: String?, suggestedName: String?, extensions: List<String>): String? =
        record("save $suggestedName ${extensions.joinToString(",")}") { saved }

    private suspend fun <T> record(call: String, result: () -> T): T {
        calls += call
        beforeReturn()
        failure?.let { throw it }
        return result()
    }
}
