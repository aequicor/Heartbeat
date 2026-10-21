package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocument
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnvironmentEntry
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeShellDialect
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeShellExecution
import io.aequicor.heartbeat.feature.aiengine.facade.api.POWER_SHELL_UTF8_INITIALIZATION
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PiControlledPowerShellTest {
    @Test
    fun `the bundled executor validates the same UTF8 initialization as the shared contract`() {
        val source = assertNotNull(javaClass.getResourceAsStream("/pi/heartbeat-powershell.ts"))
            .use { it.bufferedReader().readText() }
        assertTrue("const INITIALIZATION = ${JsonPrimitive(POWER_SHELL_UTF8_INITIALIZATION)};" in source)
    }

    @Test
    fun `hermetic inspected launch binds the fixed Windows executable arguments and sanitized environment`() =
        withLayout { layout ->
            val controlled = assertNotNull(layout.manifest())
            assertEquals(NativeShellDialect.PowerShell, controlled.shell.dialect)
            assertEquals(
                layout.root.resolve("System32/WindowsPowerShell/v1.0/powershell.exe").toString(),
                controlled.shell.executable,
            )
            assertEquals(
                listOf("-NoLogo", "-NoProfile", "-NonInteractive", "-Command"),
                controlled.shell.argumentPrefix,
            )
            assertEquals(POWER_SHELL_UTF8_INITIALIZATION, controlled.shell.initialization)
            val envelope = Json.parseToJsonElement(controlled.manifest).jsonObject
            val configuration = assertNotNull(envelope.string("configuration"))
            assertEquals(fingerprint(configuration), controlled.shell.environmentBinding)
            assertEquals(controlled.shell.environmentBinding, envelope.string("binding"))
            assertEquals(
                POWER_SHELL_UTF8_INITIALIZATION,
                Json.parseToJsonElement(configuration).jsonObject.string("initialization"),
            )
            val environment = Json.parseToJsonElement(configuration).jsonObject.getValue("environment").jsonObject
            assertNull(environment["PROVIDER_KEY"])
            assertNull(environment["HEARTBEAT_AGENT_TOOLS_TOKEN"])
            assertNull(environment["NODE_OPTIONS"])
            assertEquals("host-path", environment.string("PATH"))
            assertEquals(
                layout.root.resolve("System32/WindowsPowerShell/v1.0/Modules").toString(),
                environment.string("PSModulePath"),
            )
            assertNotEquals(
                controlled.shell.environmentBinding,
                layout.manifest(environment = mapOf("PATH" to "other"))?.shell?.environmentBinding,
            )
        }

    @Test
    fun `custom discovered configured and unverified runtimes never acquire executor evidence`() =
        withLayout { layout ->
            for (source in listOf(InstallSource.Custom, InstallSource.Managed, InstallSource.Missing)) {
                assertNull(layout.manifest(startup = layout.startup.copy(source = source)))
            }
            val customEnvironment = layout.startup.copy(environment = listOf(EnvironmentEntry("PATH", "custom")))
            assertNull(layout.manifest(startup = customEnvironment))
            val document = ConfigDocument.Json(JsonObject(emptyMap()))
            val configured = EngineConfigValues(mapOf(PiDocuments.Settings to document))
            assertNull(layout.manifest(startup = layout.startup.copy(config = configured)))
            assertNull(layout.manifest(flags = PiFlags(isDiscovering = true)))
            assertNull(layout.manifest(flags = PiFlags(builtins = listOf("builtin:shell"))))
            assertNull(layout.manifest(version = "0.88.0"))
            assertNull(layout.manifest(version = null))
            assertNull(layout.manifest(bundled = layout.root.resolve("other.exe")))
            assertNull(layout.manifest(bundled = null))
            assertNull(layout.manifest(isWindows = false))
            Files.delete(layout.root.resolve("System32/WindowsPowerShell/v1.0/powershell.exe"))
            assertNull(layout.manifest())
        }

    @Test
    fun `disabled analysis disabled native tool and hosted collisions retain unknown execution`() =
        withLayout { layout ->
            assertNull(layout.manifest(plan = layout.plan.copy(isActionAnalysisRequired = false)))
            val disabled = ResolvedToolPolicy(nativeOff = setOf("powershell"))
            assertNull(layout.manifest(plan = layout.plan.copy(policy = disabled)))
            val conflicting = ResolvedToolPolicy(nativeOn = setOf("powershell"), nativeOff = setOf("powershell"))
            assertNull(layout.manifest(plan = layout.plan.copy(policy = conflicting)))
            for (name in listOf("powershell", "PowerShell")) {
                val spec = AgentToolSpec(name, "untrusted description", JsonObject(emptyMap()))
                val hosted = PiHostedTools(null, listOf(spec), "")
                assertNull(layout.manifest(plan = layout.plan.copy(hosted = hosted)))
            }
        }

    @Test
    fun `only the exact connection envelope can attach shell evidence and model arguments cannot forge it`() {
        val shell = NativeShellExecution(NativeShellDialect.PowerShell, "fixed", listOf("-Command"), "host-binding")
        val connection = FakeConnection().apply { controlledPowerShell = shell }
        val call = PiApprovalCall(
            "powershell",
            "Get-ChildItem",
            arguments = JsonObject(mapOf("command" to JsonPrimitive("Get-ChildItem"))),
            toolCallId = ToolCallId("call"),
            executorBinding = shell.environmentBinding,
        )
        assertEquals(shell, connection.controlledShell(call))
        assertNull(FakeConnection().controlledShell(call))
        assertNull(connection.controlledShell(call.copy(tool = "bash")))
        assertNull(connection.controlledShell(call.copy(target = "different")))
        assertNull(connection.controlledShell(call.copy(toolCallId = null)))
        assertNull(connection.controlledShell(call.copy(executorBinding = "forged")))
        val forged = assertNotNull(
            approvalCall(
                """{"toolName":"powershell","toolCallId":"call","target":"Get-ChildItem",
                "arguments":{"command":"Get-ChildItem","executorBinding":"host-binding",
                "shell":{"environmentBinding":"host-binding"}}}""",
            ),
        )
        assertNull(connection.controlledShell(forged))
        assertNull(forged.executorBinding)
    }

    private class Layout(val root: Path) {
        val startup = PiStartup(root.resolve("pi.exe"), InstallSource.Bundled)
        val plan = PiLaunchPlan(
            ResolvedToolPolicy(nativeOn = setOf("powershell")),
            null,
            emptySet(),
            isActionAnalysisRequired = true,
        )

        fun manifest(
            startup: PiStartup = this.startup,
            flags: PiFlags = PiFlags(),
            plan: PiLaunchPlan = this.plan,
            bundled: Path? = this.startup.executable,
            version: String? = "0.87.1",
            environment: Map<String, String> = mapOf(
                "SystemRoot" to root.toString(),
                "PATH" to "host-path",
                "PROVIDER_KEY" to "secret",
                "HEARTBEAT_AGENT_TOOLS_TOKEN" to "secret",
                "NODE_OPTIONS" to "custom",
            ),
            isWindows: Boolean = true,
        ) = piControlledPowerShell(
            startup,
            flags,
            plan,
            PiPowerShellHost(bundled, version, root, environment, root, isWindows),
        )
    }

    private fun withLayout(block: (Layout) -> Unit) {
        val root = Files.createTempDirectory("pi-controlled-powershell")
        try {
            Files.createDirectories(root.resolve("System32/WindowsPowerShell/v1.0"))
            Files.createFile(root.resolve("System32/WindowsPowerShell/v1.0/powershell.exe"))
            Files.createFile(root.resolve("System32/taskkill.exe"))
            block(Layout(root))
        } finally {
            val temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
            assertTrue(root.toAbsolutePath().normalize().startsWith(temporaryRoot))
            root.toFile().deleteRecursively()
        }
    }
}
