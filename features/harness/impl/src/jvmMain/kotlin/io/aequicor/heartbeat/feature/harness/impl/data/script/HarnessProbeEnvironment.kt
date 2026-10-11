package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.feature.harness.impl.data.script.worker.HarnessCompilerLauncher
import io.aequicor.heartbeat.feature.harness.impl.data.script.worker.JavaHarnessCompilerLauncher
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessScriptHost
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fixed inputs shared by the initial host and its independent cache-reload host. [workers], when the production
 * compiler runs, records the compiler worker processes of both hosts.
 */
internal class HarnessProbeEnvironment(
    val directory: Path,
    private val appVersion: String,
    private val dispatchers: DispatcherProvider,
    private val factory: HarnessProbeHostFactory,
    val workers: HarnessProbeWorkers? = null,
) {
    fun createHost(scope: CoroutineScope): HarnessScriptHost = factory(directory, appVersion, scope, dispatchers)
}

/** The production launcher, recording each worker it starts; the probe starts only a few. */
internal class HarnessProbeWorkers(private val launcher: HarnessCompilerLauncher = JavaHarnessCompilerLauncher()) :
    HarnessCompilerLauncher {
    private val started = CopyOnWriteArrayList<ProcessHandle>()

    val processes: List<ProcessHandle> get() = started.toList()

    override fun start(): Process = launcher.start().also { started += it.toHandle() }
}
