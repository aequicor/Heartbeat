package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientFactory
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpClientHandler
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpCommand
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpException
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpImplementation
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpInitialization
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpSessionUpdate
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpStdioTransportFactory
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityObservation
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineCompatibilityRequirements
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineProtocolIdentity
import io.aequicor.heartbeat.feature.aiengine.facade.api.Installation
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.kimi.api.KimiEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Explicit, credential-free ACP inspection of the exact selected artifact. Only initialize is sent: no login,
 * sessions, prompts or tools. A handshake establishes wire/ABI facts, never the full behavioral baseline.
 */
internal class KimiCompatibilityProbe internal constructor(
    private val setup: KimiRuntimeSetup,
    private val transports: AcpStdioTransportFactory,
    private val clients: AcpClientFactory,
    private val dispatchers: DispatcherProvider,
    private val isTrusted: (KimiControlledRuntime) -> Boolean,
) {
    @Inject
    constructor(
        setup: KimiRuntimeSetup,
        transports: AcpStdioTransportFactory,
        clients: AcpClientFactory,
        dispatchers: DispatcherProvider,
    ) : this(setup, transports, clients, dispatchers, ::isKnownKimiRelease)

    suspend fun inspect(installation: Installation, launch: LaunchContext): EngineCompatibilityObservation =
        withTimeout(PROBE_TIMEOUT_MS) {
            val (runtime, source) = setup.locateControlled(launch)
            val isSameSource = installation.source == source && installation.path == runtime.executable &&
                installation.version == KIMI_RUNTIME_VERSION
            if (!installation.isRunnable || !isSameSource || !isTrusted(runtime)) {
                throw EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
            }
            withKimiProbeDirectory(dispatchers) { directory ->
                val command = isolatedKimiProbeCommand(runtime.executable, directory.root)
                negotiate(command, runtime, directory)
            }
        }

    private suspend fun negotiate(
        command: AcpCommand,
        runtime: KimiControlledRuntime,
        directory: KimiProbeDirectory,
    ): EngineCompatibilityObservation {
        directory.processStarted()
        val transport = try {
            transports.open(command)
        } catch (error: AcpException.LaunchFailed) {
            directory.processStopped()
            throw error
        }
        val owner = SupervisorJob(currentCoroutineContext()[Job])
        val scope = CoroutineScope(currentCoroutineContext() + owner)
        try {
            val connection = clients.connect(
                transport,
                scope,
                object : AcpClientHandler {
                    override suspend fun onSessionUpdate(update: AcpSessionUpdate) = Unit
                },
            )
            val initialized = connection.initialize(AcpImplementation("heartbeat-inspect", "1"))
            return kimiCompatibilityObservation(runtime, initialized)
        } finally {
            // Closing transport unblocks owned requests even if initialize is still running after caller cancellation.
            try {
                transport.close()
            } finally {
                withContext(NonCancellable) {
                    try {
                        transport.closeAndAwait()
                        directory.processStopped()
                    } finally {
                        owner.cancelAndJoin()
                    }
                }
            }
        }
    }
}

/** Owns an empty temporary data root for a read-only probe, including cancellation and failed process startup. */
internal suspend fun <T> withKimiProbeDirectory(
    dispatchers: DispatcherProvider,
    block: suspend (KimiProbeDirectory) -> T,
): T = withContext(dispatchers.io) {
    val root = Files.createTempDirectory("heartbeat-kimi-inspect-")
    val directory = KimiProbeDirectory(root)
    try {
        block(directory)
    } finally {
        // walkFileTree does not follow links created by the child into another directory.
        if (directory.isProcessStopped) {
            withContext(NonCancellable) { removeProbeDirectory(root) }
        } else {
            Log.tag("KimiCompatibilityProbe").w {
                "Keeping isolated probe data after unconfirmed process termination"
            }
        }
    }
}

/** Temporary data must outlive a child whose termination has not been confirmed. */
internal class KimiProbeDirectory(val root: Path) {
    var isProcessStopped: Boolean = true
        private set

    fun processStarted() {
        isProcessStopped = false
    }

    fun processStopped() {
        isProcessStopped = true
    }
}

/** Uses the pinned CLI's KIMI_CODE_HOME data root and removes inherited credential/config routes. */
internal fun isolatedKimiProbeCommand(executable: String, root: Path): AcpCommand {
    val home = Files.createDirectory(root.resolve("home")).toString()
    val temp = Files.createDirectory(root.resolve("tmp")).toString()
    val work = Files.createDirectory(root.resolve("work")).toString()
    val environment = mapOf(
        "KIMI_CODE_HOME" to home,
        "HOME" to home,
        "USERPROFILE" to home,
        "APPDATA" to home,
        "LOCALAPPDATA" to home,
        "XDG_CONFIG_HOME" to home,
        "XDG_DATA_HOME" to home,
        "XDG_CACHE_HOME" to home,
        "TMPDIR" to temp,
        "TMP" to temp,
        "TEMP" to temp,
    ) + listOf("SystemRoot", "WINDIR").mapNotNull { key -> System.getenv(key)?.let { key to it } }.toMap()
    return AcpCommand(executable, listOf("acp"), work, environment, isEnvironmentInherited = false)
}

private fun kimiCompatibilityObservation(
    runtime: KimiControlledRuntime,
    initialized: AcpInitialization,
): EngineCompatibilityObservation {
    val heartbeat = (initialized.agentCapabilities["_meta"] as? JsonObject)?.get("heartbeat") as? JsonObject
    val capabilities = buildSet {
        if (heartbeat.hasAbi("permissionGate", KIMI_PERMISSION_GATE)) add("heartbeat.permissionGate.1")
        if (heartbeat.hasAbi("hostedCallBinding", 1)) add("heartbeat.hostedCallBinding.1")
    }
    return EngineCompatibilityObservation(
        requirements = EngineCompatibilityRequirements(
            engine = KimiEngine.Id,
            heartbeatVersions = null,
            connectorVersions = null,
            minimumRuntime = null,
            protocols = setOf(EngineProtocolIdentity.Negotiated("acp", 1)),
            configurationSchemas = null,
            historySchemas = null,
            nativeTargets = setOf("win32-x64", "win32-arm64", "darwin-x64", "darwin-arm64", "linux-x64"),
            baselineCapabilities = setOf("heartbeat.permissionGate.1", "heartbeat.hostedCallBinding.1"),
        ),
        evidence = EngineCompatibilityEvidence(
            identity = EngineCompatibilityIdentity(
                engine = KimiEngine.Id,
                runtime = initialized.agentInfo?.version,
                protocol = initialized.protocolVersion.takeIf { it > 0 }?.let {
                    EngineProtocolIdentity.Negotiated("acp", it)
                },
                nativeTarget = runtime.target,
                runtimeFingerprint = runtime.fingerprint,
            ),
            // Initialize and ABI advertisements alone do not exercise permission decisions or session behavior.
            isBaselineConfirmed = null,
            // The adjacent properties file only checks internal consistency; it is not an independent trust anchor.
            // A mismatched peer version rejects this pin, but a matching version cannot prove the artifact's origin.
            isProvenanceConfirmed = initialized.agentInfo?.version
                ?.takeIf { it != KIMI_RUNTIME_VERSION }
                ?.let { false },
            capabilities = capabilities,
        ),
    )
}

private fun JsonObject?.hasAbi(name: String, version: Int): Boolean {
    val value = this?.get(name) as? JsonPrimitive
    return value != null && !value.isString && value.intOrNull == version
}

private fun removeProbeDirectory(root: Path) {
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

private const val PROBE_TIMEOUT_MS = 5_000L
