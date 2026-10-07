package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path

/** Fixed inputs shared by the initial host and its independent cache-reload host. */
internal class HarnessProbeEnvironment(
    val directory: Path,
    private val appVersion: String,
    private val dispatchers: DispatcherProvider,
    private val factory: HarnessProbeHostFactory,
) {
    fun createHost(scope: CoroutineScope): HarnessScriptHost = factory(directory, appVersion, scope, dispatchers)
}
