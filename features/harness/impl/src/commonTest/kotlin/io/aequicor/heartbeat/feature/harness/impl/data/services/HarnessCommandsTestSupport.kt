package io.aequicor.heartbeat.feature.harness.impl.data.services

import io.aequicor.heartbeat.core.featuretoggles.FeatureToggle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.impl.data.services.HarnessScriptCommandsFactory
import io.aequicor.heartbeat.feature.harness.impl.domain.authoring.HarnessCreationScope
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigin
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessCallOrigins
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessOriginContext
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommandOutcome
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommandRequest
import io.aequicor.heartbeat.feature.scheduler.api.LocalCommands
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.coroutines.CoroutineContext

/** Records requests and answers them with [outcome], suspending in [before] first. */
internal class RecordingLocalCommands(
    override val isAvailable: Boolean = true,
    var outcome: LocalCommandOutcome = LocalCommandOutcome.Exited(0, "ok"),
) : LocalCommands {
    val requests = mutableListOf<LocalCommandRequest>()
    var before: suspend () -> Unit = {}

    override suspend fun run(request: LocalCommandRequest): LocalCommandOutcome {
        requests += request
        before()
        return outcome
    }
}

/** Saved projects resolve to themselves; anything else is unknown. */
internal class SavedProjectScopes(private val saved: Set<WorkspaceRef>) : HarnessCreationScope {
    override suspend fun resolve(workspace: WorkspaceRef?): HarnessScope? = when (workspace) {
        null -> HarnessScope.Attached
        in saved -> HarnessScope.Projects(setOf(workspace))
        else -> null
    }
}

/** Answers every boolean flag with [isEnabled]. */
internal class CommandToggles(var isEnabled: Boolean = true) : FeatureToggles {
    @Suppress("UNCHECKED_CAST") // The fake answers only boolean flags.
    override fun <T : Any> observe(toggle: FeatureToggle<T>): Flow<T> = flowOf(isEnabled as T)

    @Suppress("UNCHECKED_CAST") // The fake answers only boolean flags.
    override suspend fun <T : Any> get(toggle: FeatureToggle<T>): T = isEnabled as T
}

/** Origins whose current value the test sets, as the platform carrier would. */
internal class CommandOrigins(var current: HarnessCallOrigin = HarnessCallOrigin()) : HarnessCallOrigins {
    override fun current(): HarnessCallOrigin = current
    override fun context(origin: HarnessCallOrigin): CoroutineContext = HarnessOriginContext(origin)
}

/** A factory whose commands never run: fixtures of other script services do not exercise them. */
internal fun commandsTestFactory(
    commands: LocalCommands = RecordingLocalCommands(isAvailable = false),
    scopes: HarnessCreationScope = SavedProjectScopes(emptySet()),
    toggles: FeatureToggles = CommandToggles(),
    slots: HarnessCommandSlots = HarnessCommandSlots(),
    origins: HarnessCallOrigins = CommandOrigins(),
) = HarnessScriptCommandsFactory(commands, scopes, slots, origins, toggles)
