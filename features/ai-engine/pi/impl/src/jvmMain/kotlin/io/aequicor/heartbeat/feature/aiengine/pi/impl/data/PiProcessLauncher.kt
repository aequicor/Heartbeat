package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.secrets.SecretKey
import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthFailureReason
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import java.util.HexFormat

@Inject
internal class PiProcessLauncher(
    private val dispatchers: DispatcherProvider,
    private val secrets: SecretStore,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    @ForScope(ProfileScope::class) private val stores: DataStores,
) {
    private val log = Log.tag("PiProcessLauncher")

    fun executable(): Path? {
        val root = System.getProperty("compose.application.resources.dir") ?: return null
        val name = if (System.getProperty("os.name").startsWith("Windows")) "pi.exe" else "pi"
        return Path.of(root, "pi", name)
    }

    suspend fun credentialFingerprint(source: AuthSource.ManagedKey): String = withContext(dispatchers.io) {
        val secret = secrets.read(SecretKey(source.secret.value))
            ?: authenticationFailure(AuthFailureReason.NotAuthenticated, source.info.id)
        secret.use { it.reveal { chars -> fingerprint(String(chars)) } }
    }

    suspend fun start(
        source: AuthSource.ManagedKey,
        workspace: String?,
        event: suspend (JsonObject) -> Unit,
        failed: suspend (EngineFailure) -> Unit,
    ): PiConnection = withContext(NonCancellable + dispatchers.io) {
        val executable = executable()?.takeIf { Files.isRegularFile(it) }
            ?: piFailure(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        val provider = provider(source) ?: authenticationFailure(AuthFailureReason.AuthMismatch, source.info.id)
        val owner = stores.owner as? StorageOwner.Profile
            ?: piFailure(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
        val root = piProfileRoot(owner.id.value)
        val secret = secrets.read(SecretKey(source.secret.value))
            ?: authenticationFailure(AuthFailureReason.NotAuthenticated, source.info.id)
        // Per-process agent configuration; removed when the process exits.
        val agentDir = Files.createTempDirectory(Files.createDirectories(root.resolve("runtime")), "pi-")
        val sessionDir = Files.createDirectories(root.resolve("sessions"))
        val workingDir = workspace?.let(Path::of) ?: Files.createDirectories(root.resolve("workspace"))
        val tools = if (System.getProperty("os.name").startsWith("Windows")) {
            "read,powershell,edit,write"
        } else {
            "read,bash,edit,write"
        }
        val command = listOf(
            executable.toString(), "--mode", "rpc", "--provider", provider.id,
            "--session-dir", sessionDir.toString(), "--no-extensions", "--no-skills",
            // --no-approve refuses project-local trust-gated resources; it is not tool-call approval.
            "--no-prompt-templates", "--no-context-files", "--no-themes", "--no-approve", "--tools", tools,
        )
        val builder = ProcessBuilder(command).directory(workingDir.toFile())
        val environment = builder.environment()
        environment.keys.retainAll(SAFE_ENVIRONMENT)
        environment["PI_CODING_AGENT_DIR"] = agentDir.toString()
        environment["PI_SKIP_VERSION_CHECK"] = "1"
        try {
            secret.use { it.reveal { chars -> environment[provider.variable] = String(chars) } }
            log.i { "Starting bundled Pi process" }
            val process = builder.start()
            process.onExit().whenComplete { _, _ -> deleteTree(agentDir) }
            val rpc = PiRpc(process, profile.coroutineScope, dispatchers, event, failed)
            rpc.closeWith(profile.onClose(rpc::close))
            rpc
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            deleteTree(agentDir)
            log.w(EngineException(EngineFailure.Engine(EngineFailureReason.Unavailable))) {
                "Pi startup failed: ${e::class.simpleName.orEmpty()}"
            }
            piFailure(EngineFailure.Engine(EngineFailureReason.Unavailable))
        } finally {
            environment.remove(provider.variable)
        }
    }

    private fun deleteTree(directory: Path) {
        try {
            if (Files.exists(directory)) {
                Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            }
        } catch (e: IOException) {
            log.w(e) { "Pi runtime directory cleanup failed" }
        }
    }

    private companion object {
        val SAFE_ENVIRONMENT = setOf(
            "PATH", "Path", "SystemRoot", "SYSTEMROOT", "WINDIR", "COMSPEC", "ComSpec",
            "TEMP", "TMP", "TMPDIR", "LANG", "LC_ALL", "PATHEXT",
        )
    }
}

internal fun fingerprint(value: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
