package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.runtime

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.acpinterface.api.AcpCommand
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.DeepSeekAcpPatch
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.dshEnvironment
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailureReason
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * Owns an empty temporary DSH data root for a restricted inspect: its own home, agents home, work and temp
 * directories. It is removed only after every process that used it was confirmed stopped, so a child that outlives
 * cancellation never loses its data under a live process.
 */
internal class DshProbeDirectory(val root: Path) {
    val home: Path = Files.createDirectory(root.resolve("home"))
    val agents: Path = Files.createDirectory(home.resolve("agents"))
    val work: Path = Files.createDirectory(root.resolve("work"))
    val temp: Path = Files.createDirectory(root.resolve("tmp"))

    var isProcessStopped: Boolean = true
        private set

    fun processStarted() {
        isProcessStopped = false
    }

    fun processStopped() {
        isProcessStopped = true
    }
}

/** Creates and reliably removes an isolated DSH probe directory around [block]. */
internal suspend fun <T> withDshProbeDirectory(
    dispatchers: DispatcherProvider,
    block: suspend (DshProbeDirectory) -> T,
): T = withContext(dispatchers.io) {
    val root = Files.createTempDirectory("heartbeat-dsh-inspect-")
    val directory = DshProbeDirectory(root)
    try {
        block(directory)
    } finally {
        if (directory.isProcessStopped) {
            withContext(NonCancellable) { removeProbeDirectory(root) }
        } else {
            Log.tag("DshProbeDirectory").w { "Keeping isolated DSH probe data after unconfirmed process termination" }
        }
    }
}

/**
 * Exact launch of the restricted ACP inspect: the ordinary `dsh` ACP profile and process-only overlay, with no
 * inherited environment. Only host lookup variables are retained; credentials, proxies, user home and user
 * configuration are replaced by the temporary probe root. No login, session or model request follows from it.
 */
internal fun isolatedDshProbeCommand(executable: String, directory: DshProbeDirectory): AcpCommand = AcpCommand(
    executable,
    probeAcpArguments(),
    directory.work.toString(),
    isolatedDshProbeEnvironment(directory),
    isEnvironmentInherited = false,
)

/** A temporary overlay failure is a conclusive preflight failure, not a reason to start an unbound process. */
private fun probeAcpArguments(): List<String> = try {
    DeepSeekAcpPatch.arguments()
} catch (error: IllegalStateException) {
    Log.tag("DshProbeDirectory").w(error) { "DSH process-only ACP overlay is unavailable" }
    throw EngineException(EngineFailure.Engine(EngineFailureReason.RequirementsNotMet))
}

/** Complete environment of the restricted inspect: inherited variables are dropped, not filtered by trust. */
internal fun isolatedDshProbeEnvironment(directory: DshProbeDirectory): Map<String, String> {
    val home = directory.home.toString()
    val temp = directory.temp.toString()
    return dshEnvironment(home) + buildMap {
        put("DSH_AGENTS_HOME", directory.agents.toString())
        put("HOME", home)
        put("USERPROFILE", home)
        put("APPDATA", home)
        put("LOCALAPPDATA", home)
        put("XDG_CONFIG_HOME", home)
        put("XDG_DATA_HOME", home)
        put("XDG_CACHE_HOME", home)
        put("TMPDIR", temp)
        put("TMP", temp)
        put("TEMP", temp)
        listOf("PATH", "PATHEXT", "SystemRoot", "WINDIR", "COMSPEC", "LANG", "LC_ALL").forEach { key ->
            System.getenv(key)?.let { put(key, it) }
        }
    }
}

private fun removeProbeDirectory(root: Path) {
    try {
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    } catch (error: IOException) {
        Log.tag("DshProbeDirectory").w(IllegalStateException(error.javaClass.simpleName)) {
            "Isolated DSH probe data cleanup failed"
        }
    } catch (error: SecurityException) {
        Log.tag("DshProbeDirectory").w(IllegalStateException(error.javaClass.simpleName)) {
            "Isolated DSH probe data cleanup was denied"
        }
    }
}
