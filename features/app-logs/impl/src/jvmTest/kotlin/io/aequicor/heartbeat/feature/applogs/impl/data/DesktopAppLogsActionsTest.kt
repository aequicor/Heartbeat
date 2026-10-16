package io.aequicor.heartbeat.feature.applogs.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.desktopdialogs.DesktopFileDialogs
import io.aequicor.heartbeat.core.logging.DesktopLogFiles
import io.aequicor.heartbeat.feature.applogs.api.AppLogsOperation
import io.aequicor.heartbeat.feature.applogs.api.AppLogsOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopAppLogsActionsTest {
    @Test
    fun `save fixes the time window before opening the picker`() = runTest {
        withFixture(StandardTestDispatcher(testScheduler)) { fixture ->
            val target = fixture.root.resolve("export.log")
            fixture.dialogs.choose = {
                Files.writeString(fixture.source, record("later"), StandardOpenOption.APPEND)
                target.toString()
            }
            assertEquals(AppLogsOutcome.Success, fixture.actions.execute(AppLogsOperation.SaveRecent))
            assertEquals(record("initial"), Files.readString(target))
            assertEquals(listOf("log"), fixture.dialogs.extensions)
        }
    }

    @Test
    fun `archive fixes records before the picker and replaces an existing destination`() = runTest {
        withFixture(StandardTestDispatcher(testScheduler)) { fixture ->
            val target = Files.writeString(fixture.root.resolve("export.zip"), "old destination")
            fixture.dialogs.choose = {
                Files.writeString(fixture.source, record("later"), StandardOpenOption.APPEND)
                target.toString()
            }
            assertEquals(AppLogsOutcome.Success, fixture.actions.execute(AppLogsOperation.SaveArchive))
            ZipFile(target.toFile()).use { zip ->
                assertEquals(1, zip.size())
                val text = zip.getInputStream(zip.entries().nextElement()).bufferedReader().use { it.readText() }
                assertEquals(record("initial"), text)
            }
            assertEquals(listOf("zip"), fixture.dialogs.extensions)
            assertNoStagingFiles(fixture.root)
        }
    }

    @Test
    fun `cancelled and empty saves do not write a destination`() = runTest {
        withFixture(StandardTestDispatcher(testScheduler)) { fixture ->
            assertEquals(AppLogsOutcome.Cancelled, fixture.actions.execute(AppLogsOperation.SaveRecent))
            assertEquals(1, fixture.dialogs.calls)
            Files.delete(fixture.source)
            assertEquals(AppLogsOutcome.Empty, fixture.actions.execute(AppLogsOperation.SaveRecent))
            assertEquals(AppLogsOutcome.Empty, fixture.actions.execute(AppLogsOperation.SaveArchive))
            assertEquals(AppLogsOutcome.Empty, fixture.actions.execute(AppLogsOperation.CopyRecent))
            assertEquals(1, fixture.dialogs.calls)
            assertNoStagingFiles(fixture.root)
        }
    }

    @Test
    fun `cancellation propagates and current or legacy logs cannot be overwritten`() = runTest {
        withFixture(StandardTestDispatcher(testScheduler)) { fixture ->
            fixture.dialogs.choose = { throw CancellationException("closed") }
            assertFailsWith<CancellationException> { fixture.actions.execute(AppLogsOperation.SaveArchive) }
            fixture.dialogs.choose = { fixture.source.toString() }
            assertFailsWith<IllegalArgumentException> { fixture.actions.execute(AppLogsOperation.SaveRecent) }
            assertEquals(record("initial"), Files.readString(fixture.source))
            val legacySource = Files.writeString(fixture.legacy.resolve("heartbeat-0-0.log"), record("legacy"))
            fixture.dialogs.choose = { legacySource.toString() }
            assertFailsWith<IllegalArgumentException> { fixture.actions.execute(AppLogsOperation.SaveArchive) }
            assertEquals(record("legacy"), Files.readString(legacySource))
            assertNoStagingFiles(fixture.root)
        }
    }

    @Test
    fun `destination failure leaves existing user file intact`() = runTest {
        withFixture(StandardTestDispatcher(testScheduler)) { fixture ->
            val destination = Files.createDirectory(fixture.root.resolve("existing-directory"))
            val child = Files.writeString(destination.resolve("keep.txt"), "keep")
            fixture.dialogs.choose = { destination.toString() }
            assertFailsWith<java.io.IOException> { fixture.actions.execute(AppLogsOperation.SaveRecent) }
            assertEquals("keep", Files.readString(child))
            assertNoStagingFiles(fixture.root)
        }
    }

    private fun assertNoStagingFiles(root: Path) {
        Files.list(
            root,
        ).use { files -> assertFalse(files.anyMatch { it.fileName.toString().startsWith(".heartbeat-") }) }
    }

    private suspend fun withFixture(dispatcher: CoroutineDispatcher, block: suspend (Fixture) -> Unit) {
        val root = Files.createTempDirectory("app-logs-actions-test")
        try {
            block(Fixture(root, dispatcher))
        } finally {
            Files.walk(
                root,
            ).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private class Fixture(val root: Path, dispatcher: CoroutineDispatcher) {
        val logs: Path = Files.createDirectory(root.resolve("logs"))
        val legacy: Path = Files.createDirectory(root.resolve("legacy"))
        val source: Path = Files.writeString(logs.resolve("heartbeat-0-0.log"), record("initial"))
        val dialogs = FakeDialogs()
        val actions = DesktopAppLogsActions(
            DesktopLogFiles(logs, listOf(legacy), Clock.fixed(NOW, ZoneOffset.UTC)),
            dialogs,
            object : DispatcherProvider {
                override val main = dispatcher
                override val default = dispatcher
                override val io = dispatcher
            },
        )
    }

    private class FakeDialogs : DesktopFileDialogs {
        var calls = 0
        var extensions = emptyList<String>()
        var choose: () -> String? = { null }
        override suspend fun pickDirectory(title: String?): String? = error("Unexpected folder picker")
        override suspend fun pickFiles(title: String?, extensions: List<String>, allowMultiple: Boolean): List<String> =
            error("Unexpected file picker")

        override suspend fun pickSaveLocation(
            title: String?,
            suggestedName: String?,
            extensions: List<String>,
        ): String? {
            calls += 1
            this.extensions = extensions
            assertTrue(suggestedName?.startsWith("heartbeat-") == true)
            return choose()
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-16T12:00:00Z")
        fun record(message: String): String = "$NOW [INFO] Test - $message\n"
    }
}
