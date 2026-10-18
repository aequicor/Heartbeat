package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.core.secrets.SecretStore
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.process.ProcessDefinition
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessInstanceAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptData
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessScriptProcesses
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlin.time.Clock
import kotlin.time.Instant

/** Seconds since the epoch at which every [ProcessesTestEnv] clock starts. */
internal const val PROCESS_TEST_START = 1_000L

/**
 * One activation of the process service over protected storage. Building another environment over the same
 * [HarnessDataTestSecrets] is a restart of the profile: nothing but the stored bytes carries over.
 */
internal class ProcessesTestEnv(
    scope: TestScope,
    secrets: SecretStore = HarnessDataTestSecrets(),
    owner: HarnessId = HarnessId("owner"),
    restartedAt: Long = 0,
    definition: ProcessDefinition? = null,
    isVersioningEnabled: suspend () -> Boolean = { true },
    isArchiveEnabled: Boolean = false,
) {
    val clock = ProcessesTestClock(PROCESS_TEST_START + restartedAt)
    val access = ProcessesTestAccess(scope)
    val archives = ProtectedProcessArchives(secrets, clock, CommandToggles(isArchiveEnabled))
    val storage = ProtectedHarnessData(secrets, archives)
    val data = HarnessScriptData(owner, access, storage)
    val processGate = storage.processMutex
    val processes = HarnessScriptProcesses(
        data,
        clock,
        processGate,
        definition = definition,
        isVersioningEnabled = isVersioningEnabled,
        owner = owner,
        archives = archives,
    )
}

internal class ProcessesTestClock(var seconds: Long) : Clock {
    override fun now(): Instant = Instant.fromEpochSeconds(seconds)
}

internal class ProcessesTestAccess(scope: TestScope) : HarnessInstanceAccess {
    override val scope = scope.backgroundScope
    override val dispatcher = StandardTestDispatcher(scope.testScheduler)
    override var isActive = true
    override suspend fun awaitPublication() = isActive
}
