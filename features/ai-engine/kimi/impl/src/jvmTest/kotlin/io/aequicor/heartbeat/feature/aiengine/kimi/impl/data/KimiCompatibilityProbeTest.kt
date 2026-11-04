package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientFactory
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientHandler
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpCommand
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpConnection
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpImplementation
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpInitialization
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionOutcome
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpPermissionRequest
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpStdioTransportFactory
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpTransport
import io.aequicor.heartbeat.feature.aiengine.facade.api.Compatibility
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompatibilityProblem
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagedInstall
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.kimi.impl.data.runtime.KimiBaselineOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class KimiCompatibilityProbeTest {
    @Test
    fun `baseline confirms only the ABI the explicit initialize answer advertises`() = runTest {
        Fixture().use { fixture ->
            val receipt = fixture.probe.run(fixture.installation, fixture.launch)
            assertEquals(KimiBaselineOutcome.Confirmed, receipt.initialize)
            assertEquals(KimiBaselineOutcome.Confirmed, receipt.permissionGate)
            assertEquals(KimiBaselineOutcome.Confirmed, receipt.hostedCallBinding)
            assertEquals(KIMI_RUNTIME_VERSION, receipt.runtimeVersion)
            assertFalse(receipt.hasRejectedCheck)
            assertNotNull(fixture.command)
        }
    }

    @Test
    fun `baseline rejects a handshake without the mandatory heartbeat ABI`() = runTest {
        Fixture().use { fixture ->
            fixture.initialize = { AcpInitialization(1, JsonObject(emptyMap()), agentInfo = peer()) }
            val receipt = fixture.probe.run(fixture.installation, fixture.launch)
            assertEquals(KimiBaselineOutcome.Confirmed, receipt.initialize)
            assertEquals(KimiBaselineOutcome.Rejected, receipt.permissionGate)
            assertEquals(KimiBaselineOutcome.Rejected, receipt.hostedCallBinding)
            assertTrue(receipt.hasRejectedCheck)
        }
    }

    @Test
    fun `baseline rejects a process that cannot start and never opens a session`() = runTest {
        Fixture().use { fixture ->
            fixture.openFailure = AcpException.LaunchFailed()
            val receipt = fixture.probe.run(fixture.installation, fixture.launch)
            assertEquals(KimiBaselineOutcome.Rejected, receipt.initialize)
            assertEquals(KimiBaselineOutcome.Unobserved, receipt.permissionGate)
            assertNull(receipt.runtimeVersion)
            assertTrue(receipt.hasRejectedCheck)
            assertEquals(null, fixture.command)
        }
    }

    @Test
    fun `a hung initialize stays unobserved instead of becoming a failed baseline`() = runTest {
        Fixture().use { fixture ->
            fixture.initialize = { awaitCancellation() }
            val receipt = fixture.probe.run(fixture.installation, fixture.launch)
            assertEquals(KimiBaselineOutcome.Unobserved, receipt.initialize)
            assertEquals(KimiBaselineOutcome.Unobserved, receipt.permissionGate)
            assertEquals(KimiBaselineOutcome.Unobserved, receipt.hostedCallBinding)
            assertNull(receipt.runtimeVersion)
            assertFalse(receipt.hasRejectedCheck)
            assertTrue(fixture.transportClosed)
            assertFalse(Files.exists(fixture.rootObserved))
        }
    }

    @Test
    fun `self consistent but unpinned artifact is rejected before a transport is opened`() = runTest {
        Fixture().use { fixture ->
            assertFailsWith<EngineException> {
                fixture.releaseProbe.inspect(fixture.installation, fixture.launch)
            }
            assertEquals(null, fixture.command)
        }
    }

    @Test
    fun `explicit handshake isolates credentials and records facts without claiming baseline`() = runTest {
        Fixture().use { fixture ->
            assertEquals(null, fixture.command, "Construction must not probe")
            val observation = fixture.probe.inspect(fixture.installation, fixture.launch)
            val command = assertNotNull(fixture.command)
            assertEquals(fixture.executable.absolutePath, command.executable)
            assertEquals(listOf("acp"), command.arguments)
            assertFalse(command.isEnvironmentInherited)
            assertEquals(fixture.rootObserved?.resolve("home").toString(), command.environment["KIMI_CODE_HOME"])
            assertEquals(command.environment["HOME"], command.environment["USERPROFILE"])
            assertEquals(fixture.rootObserved?.resolve("work").toString(), command.workingDirectory)
            assertTrue(command.environment.keys.none { it.contains("TOKEN") || it.contains("API_KEY") })
            assertEquals(Compatibility.Unknown, observation.decision.status)
            assertEquals(KIMI_RUNTIME_VERSION, observation.evidence.identity.runtime)
            assertEquals(fixture.hash, observation.evidence.identity.runtimeFingerprint)
            assertEquals(null, observation.evidence.isProvenanceConfirmed)
            assertEquals(null, observation.evidence.isBaselineConfirmed)
            assertTrue(CompatibilityProblem.MissingRequirements in observation.decision.problems)
            assertTrue(CompatibilityProblem.MissingBaseline in observation.decision.problems)
            assertTrue(CompatibilityProblem.MissingProvenance in observation.decision.problems)
            assertFalse(observation.decision.isBaselineAllowed)
            assertTrue(fixture.transportClosed)
            assertTrue(assertNotNull(fixture.ownedWork).isCancelled)
            assertFalse(Files.exists(fixture.rootObserved))
        }
    }

    @Test
    fun `temporary home remains until transport confirms termination`() = runTest {
        Fixture().use { fixture ->
            val stopping = CompletableDeferred<Unit>()
            val released = CompletableDeferred<Unit>()
            fixture.stop = {
                stopping.complete(Unit)
                released.await()
            }
            val inspection = async { fixture.probe.inspect(fixture.installation, fixture.launch) }
            stopping.await()
            assertTrue(Files.exists(fixture.rootObserved))
            assertFalse(inspection.isCompleted)
            released.complete(Unit)
            inspection.await()
            assertFalse(Files.exists(fixture.rootObserved))
        }
    }

    @Test
    fun `unconfirmed termination cannot return an observation or delete process data`() = runTest {
        Fixture().use { fixture ->
            fixture.stop = { throw UnsupportedOperationException("Fixture cannot confirm termination") }
            assertFailsWith<UnsupportedOperationException> {
                fixture.probe.inspect(fixture.installation, fixture.launch)
            }
            assertTrue(fixture.transportClosed)
            assertTrue(Files.exists(fixture.rootObserved))
            assertTrue(assertNotNull(fixture.ownedWork).isCancelled)
        }
    }

    @Test
    fun `missing or malformed mandatory ABI is incompatible despite incomplete policy`() = runTest {
        Fixture().use { fixture ->
            listOf(
                JsonObject(emptyMap()),
                buildJsonObject {
                    putJsonObject("_meta") {
                        putJsonObject("heartbeat") {
                            put("permissionGate", "1")
                            put("hostedCallBinding", 1)
                        }
                    }
                },
            ).forEach { capabilities ->
                fixture.initialize = { AcpInitialization(1, capabilities, agentInfo = peer()) }
                val result = fixture.probe.inspect(fixture.installation, fixture.launch).decision
                assertEquals(Compatibility.Incompatible, result.status)
                assertTrue(CompatibilityProblem.BaselineCapability in result.problems)
            }
        }
    }

    @Test
    fun `conflicting peer version rejects provenance and absent version stays unknown`() = runTest {
        Fixture().use { fixture ->
            fixture.initialize = { initialized().copy(agentInfo = peer("9.0.0")) }
            val mismatch = fixture.probe.inspect(fixture.installation, fixture.launch)
            assertEquals("9.0.0", mismatch.evidence.identity.runtime)
            assertEquals(Compatibility.Incompatible, mismatch.decision.status)
            assertTrue(CompatibilityProblem.ProvenanceRejected in mismatch.decision.problems)
            fixture.initialize = { initialized().copy(agentInfo = null) }
            val unknown = fixture.probe.inspect(fixture.installation, fixture.launch)
            assertEquals(Compatibility.Unknown, unknown.decision.status)
            assertEquals(null, unknown.evidence.identity.runtime)
            assertEquals(null, unknown.evidence.isProvenanceConfirmed)
        }
    }

    @Test
    fun `changed source and tampered candidate are rejected before starting any process`() = runTest {
        Fixture().use { fixture ->
            assertFailsWith<EngineException> {
                fixture.probe.inspect(fixture.installation.copy(path = "/different/kimi"), fixture.launch)
            }
            assertEquals(null, fixture.command)
            fixture.executable.appendText("tampered")
            assertFailsWith<EngineException> { fixture.probe.inspect(fixture.installation, fixture.launch) }
            assertEquals(null, fixture.command, "Invalid candidate must not fall back to another source")
        }
    }

    @Test
    fun `a matching adjacent manifest and peer version cannot prove artifact provenance`() = runTest {
        Fixture().use { fixture ->
            fixture.executable.appendText(" substituted")
            fixture.rewriteHash()
            val observation = fixture.probe.inspect(fixture.installation, fixture.launch)
            assertNotNull(fixture.command) // The fake transport proves the self-consistent file reached probe setup.
            assertEquals(null, observation.evidence.isProvenanceConfirmed)
            assertEquals(Compatibility.Unknown, observation.decision.status)
            assertTrue(CompatibilityProblem.MissingProvenance in observation.decision.problems)
        }
    }

    @Test
    fun `cancelling initialize closes transport and owned work and removes the temporary home`() = runTest {
        Fixture().use { fixture ->
            val entered = CompletableDeferred<Unit>()
            fixture.initialize = {
                entered.complete(Unit)
                awaitCancellation()
            }
            val inspection = async { fixture.probe.inspect(fixture.installation, fixture.launch) }
            entered.await()
            inspection.cancelAndJoin()
            assertTrue(fixture.transportClosed)
            assertTrue(assertNotNull(fixture.ownedWork).isCancelled)
            assertFalse(Files.exists(fixture.rootObserved))
        }
    }

    @Test
    fun `hung initialize has a bounded lifetime and cannot become a successful observation`() = runTest {
        Fixture().use { fixture ->
            fixture.initialize = { awaitCancellation() }
            assertFailsWith<TimeoutCancellationException> {
                fixture.probe.inspect(fixture.installation, fixture.launch)
            }
            assertTrue(fixture.transportClosed)
            assertTrue(assertNotNull(fixture.ownedWork).isCancelled)
            assertFalse(Files.exists(fixture.rootObserved))
        }
    }

    private class Fixture : AutoCloseable {
        private val root = Files.createTempDirectory("kimi-inspect-fixture").toFile()
        val executable: File = root.resolve(
            if (System.getProperty("os.name").startsWith("Windows")) "kimi.exe" else "kimi",
        ).apply {
            writeText("controlled protocol fixture")
            setExecutable(true)
        }
        val hash: String = MessageDigest.getInstance("SHA-256").digest(executable.readBytes())
            .joinToString("") { "%02x".format(it) }
        private val managed = ManagedInstall(KIMI_RUNTIME_VERSION, executable.absolutePath, Instant.fromEpochSeconds(0))
        val launch = LaunchContext(managed = managed, isInstallCandidate = true)
        val installation = Installation(InstallSource.Managed, KIMI_RUNTIME_VERSION, executable.absolutePath)
        var initialize: suspend () -> AcpInitialization = { initialized() }
        var openFailure: Exception? = null
        var stop: suspend () -> Unit = {}
        var command: AcpCommand? = null
        var rootObserved: Path? = null
        var transportClosed = false
        var ownedWork: Job? = null
        private val transport = object : AcpTransport {
            override suspend fun send(frame: String): Unit = error("Only the fake protocol factory sends frames")
            override suspend fun receive(): String? = error("Only the fake protocol factory reads frames")
            override fun close() {
                transportClosed = true
            }
            override suspend fun closeAndAwait() {
                close()
                stop()
            }
        }
        private val transports = object : AcpStdioTransportFactory {
            override val isSupported = true
            override suspend fun open(command: AcpCommand): AcpTransport {
                openFailure?.let { throw it }
                this@Fixture.command = command
                rootObserved = Path.of(requireNotNull(command.workingDirectory)).parent
                assertTrue(Files.list(Path.of(requireNotNull(command.workingDirectory))).use { it.count() == 0L })
                assertTrue(Files.list(Path.of(command.environment.getValue("KIMI_CODE_HOME"))).use { it.count() == 0L })
                return transport
            }
        }
        private val clients = object : AcpClientFactory {
            override fun connect(
                transport: AcpTransport,
                scope: CoroutineScope,
                handler: AcpClientHandler,
            ): AcpConnection {
                ownedWork = scope.launch { awaitCancellation() }
                val unused = Proxy.newProxyInstance(
                    AcpConnection::class.java.classLoader,
                    arrayOf(AcpConnection::class.java),
                ) { _, method, _ ->
                    error("Inspect must not call ${method.name}")
                } as AcpConnection
                return object : AcpConnection by unused {
                    override suspend fun initialize(client: AcpImplementation): AcpInitialization {
                        assertEquals(
                            AcpPermissionOutcome.Cancelled,
                            handler.requestPermission(
                                AcpPermissionRequest("unexpected", JsonObject(emptyMap()), emptyList()),
                            ),
                        )
                        return this@Fixture.initialize()
                    }
                }
            }
        }
        val probe = KimiCompatibilityProbe(
            KimiRuntimeSetup(NoManagedKimiRuntime, TestKimiDispatchers, resourcesDirectory = null),
            transports,
            clients,
            TestKimiDispatchers,
            { true },
        )
        val releaseProbe = KimiCompatibilityProbe(
            KimiRuntimeSetup(NoManagedKimiRuntime, TestKimiDispatchers, resourcesDirectory = null),
            transports,
            clients,
            TestKimiDispatchers,
        )

        init {
            root.resolve("runtime.properties").writeText(
                "version=$KIMI_RUNTIME_VERSION\nsourceCommit=$KIMI_SOURCE_COMMIT\n" +
                    "permissionGate=$KIMI_PERMISSION_GATE\nsha256=$hash\ntarget=${kimiRuntimeTarget()}\n",
            )
        }

        fun rewriteHash() {
            val changed = MessageDigest.getInstance("SHA-256").digest(executable.readBytes())
                .joinToString("") { "%02x".format(it) }
            val manifest = root.resolve("runtime.properties")
            manifest.writeText(manifest.readText().replace("sha256=$hash", "sha256=$changed"))
        }

        override fun close() {
            rootObserved?.toFile()?.deleteRecursively()
            root.deleteRecursively()
        }
    }

    companion object {
        private fun peer(version: String = KIMI_RUNTIME_VERSION) = AcpImplementation("Kimi Code CLI", version)
        private fun initialized() = AcpInitialization(
            1,
            buildJsonObject {
                putJsonObject("_meta") {
                    putJsonObject("heartbeat") {
                        put("permissionGate", 1)
                        put("hostedCallBinding", 1)
                    }
                }
            },
            agentInfo = peer(),
        )
    }
}
