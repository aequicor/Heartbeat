package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioAutoContinuePort
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueEnabled
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueIntent
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueMachineKey
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueOutput
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueState
import io.aequicor.heartbeat.feature.autocontinue.api.AutoContinueView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/** Auto-continue for the studio screen through its profile machine; reading the state starts the machine. */
@OptIn(ExperimentalCoroutinesApi::class)
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class MachineStudioAutoContinue(
    view: Lazy<AutoContinueView>,
    private val machines: MachineRegistry,
    toggles: FeatureToggles,
) : StudioAutoContinuePort {
    private val log = Log.tag("MachineStudioAutoContinue")

    override val availability: Flow<Boolean> = toggles.observe(AutoContinueEnabled)

    override val state: Flow<AutoContinueState> = flow { emitAll(view.value.state) }

    override val drafts: Flow<AutoContinueOutput.Draft> = machines.observe(AutoContinueMachineKey)
        .flatMapLatest { it?.outputs ?: emptyFlow() }
        .filterIsInstance<AutoContinueOutput.Draft>()

    override suspend fun send(intent: AutoContinueIntent.Public) {
        val result = machines.send(AutoContinueMachineKey, intent)
        log.i { "auto-continue command $intent: $result" }
    }
}
