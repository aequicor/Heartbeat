package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class PiMcpLaunchTest {
    @Test
    fun `modern Pi disables MCP at the process boundary even during trusted discovery`() {
        for (version in listOf("1.0.4", "1.4.0")) {
            val flags = PiFlags(isDiscovering = true, mcpArguments = piMcpArguments(version, false))
            val command = piCommand(
                Path.of("pi"),
                Path.of("sessions"),
                emptyList(),
                "read",
                isProject = true,
                flags = flags,
            )
            assertTrue("--no-mcp" in command)
            assertTrue("--approve" in command)
            assertFalse("--no-extensions" in command)
            assertEquals(emptyList(), piMcpArguments(version, true))
        }
    }

    @Test
    fun `unknown and intermediate runtimes cannot start MCP enabled or disabled`() {
        val versions = listOf(
            null,
            "invalid",
            "0.99.0",
            "0.99.2",
            "1.0.0",
            "1.0.3",
            "1.0.4-beta",
            "9999999999999999.0.0",
        )
        for (version in versions) {
            assertFailsWith<EngineException> { piMcpArguments(version, false) }
            assertFailsWith<EngineException> { piMcpArguments(version, true) }
        }
    }

    @Test
    fun `legacy bundled runtimes retain their command line but cannot opt in`() {
        for (version in listOf("0.87.1", "0.88.0", "0.98.0")) {
            assertEquals(emptyList(), piMcpArguments(version, false))
            assertFailsWith<EngineException> { piMcpArguments(version, true) }
        }
    }

    @Test
    fun `MCP needs frozen opt in and an enabled policy group without protected execution`() {
        val startup = PiStartup(null, InstallSource.Bundled, isMcpEnabled = true)
        val plan = PiLaunchPlan(ResolvedToolPolicy(nativeOn = setOf("mcp")), null, emptySet())
        assertTrue(piMcpAllowed(startup, plan))
        assertFalse(piMcpAllowed(startup.copy(isMcpEnabled = false), plan))
        assertFalse(piMcpAllowed(startup, plan.copy(policy = ResolvedToolPolicy(nativeOn = setOf("read")))))
        assertFalse(piMcpAllowed(startup, plan.copy(isActionAnalysisRequired = true)))
        assertFalse(piMcpAllowed(startup, plan.copy(policy = ResolvedToolPolicy(isToolFree = true))))
    }

    @Test
    fun `captured version belongs to the selected installation only`() {
        val managed = ManagedInstall("1.0.4", "/managed/pi", Instant.fromEpochSeconds(0))
        val context = LaunchContext(managed = managed)
        val bundled = Path.of("/bundled/pi")
        assertEquals("1.0.4", resolvePiStartup(context, bundled, "0.87.1").runtimeVersion)
        assertEquals("1.4.0", resolvePiStartup(context, bundled, "1.4.0").runtimeVersion)
        assertNull(
            resolvePiStartup(
                context.copy(settings = LaunchSettings(executable = "/custom/pi")),
                bundled,
                "1.4.0",
            ).runtimeVersion,
        )
    }
}
