package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.feature.appupdate.api.AppVersion
import io.aequicor.heartbeat.feature.appupdate.impl.domain.PreviousAttempt
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class UpdateWorkspaceTest {
    private val root = createTempDirectory("app-update-workspace")
    private val workspace = UpdateWorkspace(root.resolve("updates"))

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `a release directory starts empty and replaces other releases`() {
        val older = workspace.releaseDirectory("1.3.0")
        older.resolve("Heartbeat-1.3.0-macos-arm64.dmg").writeText("old")

        val current = workspace.releaseDirectory("1.4.0")

        assertFalse(older.exists())
        assertTrue(Files.isDirectory(current))
        assertEquals(0, Files.list(current).use { it.count() })
    }

    @Test
    fun `without a started installation there is nothing to report`() {
        assertEquals(PreviousAttempt.None, workspace.takeOutcome(AppVersion(1, 3, 0)))
    }

    @Test
    fun `the next launch of the installed version succeeded and cleans up`() {
        val directory = workspace.releaseDirectory("1.4.0")
        workspace.helperLog.writeText("update installed\n")
        workspace.markStarted("1.4.0")

        assertEquals(PreviousAttempt.Succeeded, workspace.takeOutcome(AppVersion(1, 4, 0)))
        assertFalse(directory.exists())
        assertFalse(workspace.helperLog.exists())
        assertEquals(PreviousAttempt.None, workspace.takeOutcome(AppVersion(1, 4, 0)))
    }

    @Test
    fun `a launch of another version failed and keeps the helper log`() {
        workspace.releaseDirectory("1.4.0")
        workspace.helperLog.writeText("the app did not quit\n")
        workspace.markStarted("1.4.0")

        assertEquals(PreviousAttempt.Failed, workspace.takeOutcome(AppVersion(1, 3, 0)))
        assertTrue(workspace.helperLog.exists())
    }

    @Test
    fun `a helper that could not start leaves no mark`() {
        workspace.releaseDirectory("1.4.0")
        workspace.markStarted("1.4.0")
        workspace.clearStarted()

        assertEquals(PreviousAttempt.None, workspace.takeOutcome(AppVersion(1, 3, 0)))
    }
}
