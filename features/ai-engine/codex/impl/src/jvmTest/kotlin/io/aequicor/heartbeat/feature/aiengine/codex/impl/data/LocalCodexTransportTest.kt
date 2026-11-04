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
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
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
import java.io.IOException
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
    fun `locating a user executable does not execute it`() = runTest {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val marker = File(temporaryFolder.root, "executed")
        val executable = File(temporaryFolder.root, "codex")
        executable.writeText("#!/bin/sh\ntouch '${marker.absolutePath}'\necho codex-cli 0.160.0\n")
        assertTrue(executable.setExecutable(true))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        val transport = LocalCodexTransport(
            CodexLocalConfiguration(),
            EngineLaunchConfig.Default,
            dispatchers,
            FakeScope(backgroundScope),
        )

        val installation = transport.locate(LaunchContext(LaunchSettings(executable = executable.absolutePath)))

        assertEquals(executable.absolutePath, installation.path)
        assertNull(installation.version)
        assertFalse(marker.exists())
    }

    /** A broken local pipe is the app-server crashing, not a provider server error shown to the user. */
    @Test
    fun `broken app-server pipe is reported as an engine crash`() {
        assertEquals(EngineFailure.Engine(EngineFailureReason.Crashed), IOException("Broken pipe").sanitized().failure)
        assertEquals(
            EngineFailure.Transport(TransportFailureReason.ProtocolViolation),
            IllegalStateException("malformed").sanitized().failure,
        )
    }

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
    fun `protected launch rejects script wrappers before exporting models`() = runTest {
        val marker = File(temporaryFolder.root, "started")
        val wrapper = File(temporaryFolder.root, "codex.cmd")
        wrapper.writeText("@echo started > \"${marker.absolutePath}\"\r\n")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        val transport = LocalCodexTransport(
            CodexLocalConfiguration(executable = wrapper.absolutePath),
            EngineLaunchConfig.Default,
            dispatchers,
            FakeScope(backgroundScope),
        )

        val error = assertFailsWith<EngineException> {
            transport.prepare().open(CodexNativeOff(isActionAnalysisRequired = true))
        }
        assertEquals(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet), error.failure)
        assertFalse(marker.exists())
    }

    @Test
    fun `prepared execution retains original executable home flags and environment`() = runTest {
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
        assertNull(prepared.version())
        val policies = listOf(CodexNativeOff())
        for (off in policies) {
            assertCapturedProcess(prepared, off, home, args)
        }
        assertEquals(1, lookups)
        assertFalse(prepared.toString().contains(home))
        assertNull(transport.prepare().version())
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
    fun `unsupported versions refuse legacy restricted launch before creating an app server`() = runTest {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        for ((index, version) in listOf("0.159.9", "0.160.0-alpha.1", "unknown").withIndex()) {
            val arguments = temporaryFolder.newFile("legacy-arguments-$index")
            val context = LaunchContext(
                LaunchSettings(
                    executable = executable("legacy-$index", version).absolutePath,
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
                assertEquals("legacy-$index", wire.messages.first().obj("params").text("executable"))
            } finally {
                wire.close()
            }
        }
    }

    @Test
    fun `protected launches defer compatibility to capabilities regardless of version`() = runTest {
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
            val wire = launch.open(CodexNativeOff(isActionAnalysisRequired = true))
            try {
                assertEquals("unsupported-$index", wire.messages.first().obj("params").text("executable"))
            } finally {
                wire.close()
            }
        }
    }

    @Test
    fun `unknown version never executes selected file even for restricted launch`() = runTest {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        val probes = temporaryFolder.newFile("version-probes")
        val arguments = temporaryFolder.newFile("probed-arguments")
        val context = LaunchContext(
            LaunchSettings(
                executable = executable("probed", "0.163.0-alpha.10.2").absolutePath,
                environment = listOf(
                    EnvironmentEntry("HEARTBEAT_TEST_VERSIONS", probes.absolutePath),
                    EnvironmentEntry("HEARTBEAT_TEST_ARGS", arguments.absolutePath),
                ),
            ),
        )
        val launch = LocalCodexTransport(
            CodexLocalConfiguration(),
            EngineLaunchConfig { context },
            dispatchers,
            FakeScope(backgroundScope),
        ).prepare()
        assertNull(launch.version())
        repeat(2) { assertFailsWith<EngineException> { launch.open(CodexNativeOff(isShellDisabled = true)) } }
        assertEquals("", probes.readText())
        assertEquals("", arguments.readText())
    }

    @Test
    fun `held process version restores legacy restricted launch without executing a probe`() = runTest {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        val probes = temporaryFolder.newFile("observed-probes")
        val arguments = temporaryFolder.newFile("observed-arguments")
        val context = LaunchContext(
            LaunchSettings(
                executable = executable("observed", "0.163.0-alpha.10.2").absolutePath,
                environment = listOf(
                    EnvironmentEntry("HEARTBEAT_TEST_VERSIONS", probes.absolutePath),
                    EnvironmentEntry("HEARTBEAT_TEST_ARGS", arguments.absolutePath),
                ),
            ),
        )
        val launch = LocalCodexTransport(
            CodexLocalConfiguration(),
            EngineLaunchConfig { context },
            dispatchers,
            FakeScope(backgroundScope),
        ).prepare()
        // The metadata process has not reported yet: the floor is unknown and the restricted launch refuses.
        assertNull(launch.version())
        assertFailsWith<EngineException> { launch.open(CodexNativeOff(isShellDisabled = true)) }
        assertEquals("", arguments.readText())
        launch.observeVersion("0.163.0-alpha.10.2")
        assertEquals("0.163.0-alpha.10.2", launch.version())
        val wire = launch.open(CodexNativeOff(isShellDisabled = true))
        try {
            assertEquals("observed", wire.messages.first().obj("params").text("executable"))
        } finally {
            wire.close()
        }
        assertTrue("features.shell_tool=false" in arguments.readLines())
        // No version probe of the selected file was executed to restore the launch.
        assertEquals("", probes.readText())
    }

    @Test
    fun `observed old baseline prerelease and malformed versions stay refused`() = runTest {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val dispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = object : DispatcherProvider {
            override val main = dispatcher
            override val default = dispatcher
            override val io = dispatcher
        }
        val versions = listOf("0.159.9", "0.160.0-alpha.1", "unknown", "1.0", "  ")
        for ((index, version) in versions.withIndex()) {
            val arguments = temporaryFolder.newFile("observed-legacy-arguments-$index")
            val context = LaunchContext(
                LaunchSettings(
                    executable = executable("observed-legacy-$index", version).absolutePath,
                    environment = listOf(EnvironmentEntry("HEARTBEAT_TEST_ARGS", arguments.absolutePath)),
                ),
            )
            val launch = LocalCodexTransport(
                CodexLocalConfiguration(),
                EngineLaunchConfig { context },
                dispatchers,
                FakeScope(backgroundScope),
            ).prepare()
            launch.observeVersion(version)
            val observed = launch.version()
            if (version.isBlank()) {
                assertNull(observed, "blank observation must stay unknown")
            } else {
                assertEquals(version, observed)
            }
            assertFailsWith<EngineException> { launch.open(CodexNativeOff(isShellDisabled = true)) }
            assertEquals("", arguments.readText())
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
                if [ -n "${'$'}HEARTBEAT_TEST_VERSIONS" ]; then printf 'probe\n' >> "${'$'}HEARTBEAT_TEST_VERSIONS"; fi
                printf 'codex-cli $version\n'
                exit 0
            fi
            if [ "${'$'}1" = "debug" ]; then
                printf '{"models":[{"slug":"model"}]}\n'
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
