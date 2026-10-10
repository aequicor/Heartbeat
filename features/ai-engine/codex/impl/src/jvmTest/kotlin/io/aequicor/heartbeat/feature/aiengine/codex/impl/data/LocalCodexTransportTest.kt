package io.aequicor.heartbeat.feature.aiengine.codex.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.feature.aiengine.codex.api.CodexLocalConfiguration
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigOverride
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAvailability
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnvironmentEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineLaunchConfig
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LocalCodexTransportTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `missing local CLI reports an installation failure instead of a network failure`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        val transport = LocalCodexTransport(
            CodexLocalConfiguration(executable = File(temporaryFolder.root, "missing-codex.exe").absolutePath),
            EngineLaunchConfig.Default,
            dispatchers,
            FakeScope(backgroundScope),
        )
        val expected = EngineFailure.Engine(EngineFailureReason.RequirementsNotMet)

        assertEquals(expected, assertFailsWith<EngineException> { transport.open() }.failure)
        assertEquals(EngineAvailability.Unavailable(expected), transport.available())
    }

    @Test
    fun `prepared execution processes and version retain original executable home flags and environment`() = runTest {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val first = executable("first", "0.160.0")
        val second = executable("second", "0.161.0")
        val home = temporaryFolder.newFolder("home").absolutePath
        val args = temporaryFolder.newFile("arguments")
        val overrides = mutableListOf(
            ConfigOverride("model_reasoning_effort", "\"low\""),
            ConfigOverride("features.shell_tool", "true"),
            ConfigOverride("web_search", "\"cached\""),
        )
        var context = LaunchContext(
            LaunchSettings(
                executable = first.absolutePath,
                homeDirectory = home,
                environment = listOf(
                    EnvironmentEntry("HEARTBEAT_TEST_LABEL", "captured"),
                    EnvironmentEntry("HEARTBEAT_TEST_ARGS", args.absolutePath),
                ),
                configOverrides = overrides,
            ),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        var lookups = 0
        val transport = LocalCodexTransport(
            CodexLocalConfiguration(),
            EngineLaunchConfig {
                lookups++
                context
            },
            dispatchers,
            FakeScope(backgroundScope),
        )
        val prepared = transport.prepare()
        overrides.clear()
        context = LaunchContext(LaunchSettings(executable = second.absolutePath))
        assertEquals("0.160.0", prepared.version())
        val policies = listOf(
            CodexNativeOff(isShellDisabled = true, isSearchDisabled = true),
            CodexNativeOff(isShellDisabled = true),
            CodexNativeOff(),
        )
        for (off in policies) {
            assertCapturedProcess(prepared, off, home, args)
        }
        assertEquals(1, lookups)
        assertFalse(prepared.toString().contains(home))
        assertEquals("0.161.0", transport.prepare().version())
        assertEquals(2, lookups)
    }

    @Test
    fun `cancellation during return from IO disposes the unclaimed process registration`() = runTest {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val handoff = HandoffDispatcher(dispatcher)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = handoff
        }
        val base = FakeScope(backgroundScope)
        var registrations = 0
        val profile = object : ScopeHandle by base {
            override fun onClose(action: () -> Unit): DisposableHandle {
                registrations++
                val handle = base.onClose(action)
                return DisposableHandle {
                    handle.dispose()
                    registrations--
                }
            }
        }
        val transport = LocalCodexTransport(
            CodexLocalConfiguration(executable = executable("cancelled", "0.160.0").absolutePath),
            EngineLaunchConfig.Default,
            dispatchers,
            profile,
        )
        val prepared = transport.prepare()
        val opening = async(start = CoroutineStart.LAZY) { prepared.open() }
        handoff.afterDispatch = {
            handoff.afterDispatch = {}
            assertEquals(1, registrations)
            opening.cancel()
        }
        opening.start()
        runCurrent()
        assertFailsWith<CancellationException> { opening.await() }
        assertEquals(0, registrations)
        base.close()
    }

    private suspend fun assertCapturedProcess(
        prepared: PreparedCodexLaunch,
        off: CodexNativeOff,
        home: String,
        args: File,
    ) {
        val wire = prepared.open(off)
        try {
            val data = wire.messages.first().obj("params")
            assertEquals("captured", data.text("label"))
            assertEquals(home, data.text("home"))
            assertEquals("first", data.text("executable"))
            assertNotNull(wire.processOwner())
        } finally {
            wire.close()
        }
        assertNull(wire.processOwner())
        val arguments = args.readLines()
        assertTrue("model_reasoning_effort=\"low\"" in arguments)
        assertEquals(
            "features.shell_tool=" + !off.isShellDisabled,
            arguments.last { it.startsWith("features.shell_tool=") },
        )
        assertEquals(
            if (off.isSearchDisabled) "web_search=\"disabled\"" else "web_search=\"cached\"",
            arguments.last { it.startsWith("web_search=") },
        )
    }

    @Test
    fun `unsupported versions refuse restricted launch before creating an app server`() = runTest {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        for ((index, version) in listOf("0.159.9", "0.160.0-alpha.1", "unknown").withIndex()) {
            val arguments = temporaryFolder.newFile("unsupported-arguments-$index")
            val context = LaunchContext(
                LaunchSettings(
                    executable = executable("unsupported-$index", version).absolutePath,
                    environment = listOf(EnvironmentEntry("HEARTBEAT_TEST_ARGS", arguments.absolutePath)),
                ),
            )
            val launch = LocalCodexTransport(
                CodexLocalConfiguration(),
                EngineLaunchConfig { context },
                dispatchers,
                FakeScope(backgroundScope),
            ).prepare()
            assertFailsWith<EngineException> { launch.open(CodexNativeOff(isShellDisabled = true)) }
            assertEquals("", arguments.readText())
            val wire = launch.open()
            try {
                assertEquals("unsupported-$index", wire.messages.first().obj("params").text("executable"))
            } finally {
                wire.close()
            }
        }
    }

    /** Cancels after IO has created the process but before the awaiting coroutine resumes on main. */
    private class HandoffDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var afterDispatch: () -> Unit = {}
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            delegate.dispatch(context) {
                block.run()
                afterDispatch()
            }
        }
    }

    /** POSIX process fixture only; Windows argv behavior has separate encoder tests. */
    private fun executable(name: String, version: String): File = temporaryFolder.newFile(name).apply {
        writeText(
            """
            #!/bin/sh
            if [ "${'$'}1" = "--version" ]; then
                printf 'codex-cli $version\n'
                exit 0
            fi
            printf '%s\n' "${'$'}@" > "${'$'}HEARTBEAT_TEST_ARGS"
            printf '{"method":"probe","params":{"executable":"$name","label":"%s","home":"%s"}}\n' "${'$'}HEARTBEAT_TEST_LABEL" "${'$'}CODEX_HOME"
            while IFS= read -r line; do :; done
            """.trimIndent(),
        )
        check(setExecutable(true))
    }
}
