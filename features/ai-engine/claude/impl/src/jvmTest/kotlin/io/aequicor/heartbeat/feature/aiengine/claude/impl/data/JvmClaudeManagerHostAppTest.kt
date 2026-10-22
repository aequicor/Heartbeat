package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.claude.impl.domain.ClaudeDesktopSwitch
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostAppState
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmClaudeManagerHostAppTest {
    private val launched = mutableListOf<List<String>>()
    private val installed = testClaudeHost(
        directories = mapOf("/Applications/Claude.app" to listOf("Contents")),
        files = mapOf("/Applications/Claude.app/Contents/Info.plist" to ""),
    )

    @Test
    fun `the installed app is inspected and opened without a pipe to Heartbeat`() = runTest {
        val manager = manager(installed)

        assertTrue(manager.inspectHostApp().isInstalled)
        manager.openHostApp()

        assertEquals(listOf(listOf("/usr/bin/open", "-a", File("/Applications/Claude.app").path)), launched)
    }

    @Test
    fun `a missing app or a failed start is a requirement or availability failure`() = runTest {
        val missing = assertFailsWith<EngineException> { manager(testClaudeHost()).openHostApp() }
        assertEquals(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet), missing.failure)

        val broken = manager(installed) { throw IOException("Cannot run program /Applications/Claude.app") }
        val failed = assertFailsWith<EngineException> { broken.openHostApp() }
        assertEquals(EngineFailure.Engine(EngineFailureReason.Unavailable), failed.failure)
    }

    @Test
    fun `switched off the app is neither shown nor opened`() = runTest {
        val manager = manager(installed, isEnabled = false)

        assertEquals(HostAppState(), manager.inspectHostApp())
        assertFailsWith<EngineException> { manager.openHostApp() }
        assertTrue(launched.isEmpty())
    }

    private fun manager(
        host: ClaudeHost,
        isEnabled: Boolean = true,
        launcher: ClaudeAppLauncher = ClaudeAppLauncher { launched += it },
    ): JvmClaudeManager {
        val dispatchers = object : DispatcherProvider {
            override val main = Dispatchers.Unconfined
            override val default = Dispatchers.Unconfined
            override val io = Dispatchers.Unconfined
        }
        return JvmClaudeManager(
            UnusedTransport,
            ClaudeSignIn(dispatchers),
            dispatchers,
            desktop = ClaudeDesktopSwitch { isEnabled },
            host = host,
            launcher = launcher,
        )
    }

    private object UnusedTransport : ClaudeTransport {
        override suspend fun run(
            arguments: List<String>,
            input: String,
            workspace: WorkspaceRef?,
            closeInput: Boolean,
            hosted: ClaudeHostedTools?,
            line: suspend (String) -> Boolean,
        ): Int = error("unused")
    }
}
