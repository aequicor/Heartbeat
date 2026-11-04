package io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.runtime

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.aiengine.deepseekharness.impl.data.probeDeepSeekVersion
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeMark

/**
 * Fresh observation of the runtime line of one exact selected DSH launcher. It starts the adapter's existing
 * `--version` probe in the same isolated probe environment the admission baseline uses, so the observation never
 * reads credentials, the user home or project configuration. Null means the line could not be observed.
 */
internal fun interface DshRuntimeVersionProbe {
    suspend fun observe(executable: String, deadline: TimeMark): String?
}

/** Bounded `dsh --version` observation used by promotion to recheck the assessed runtime line. */
@Inject
internal class JvmDshRuntimeVersionProbe(private val dispatchers: DispatcherProvider) : DshRuntimeVersionProbe {
    override suspend fun observe(executable: String, deadline: TimeMark): String? {
        if (deadline.hasPassedNow()) return null
        val remaining = (-deadline.elapsedNow()).inWholeMilliseconds.coerceIn(1L, VERSION_TIMEOUT_MILLIS)
        return withTimeoutOrNull(remaining) {
            withDshProbeDirectory(dispatchers) { directory ->
                directory.processStarted()
                try {
                    probeDeepSeekVersion(
                        dispatchers = dispatchers,
                        executable = executable,
                        timeoutMillis = remaining,
                        workingDirectory = directory.work.toFile(),
                        environment = isolatedDshProbeEnvironment(directory),
                    )
                } finally {
                    directory.processStopped()
                }
            }
        }
    }
}

private const val VERSION_TIMEOUT_MILLIS = 10_000L
