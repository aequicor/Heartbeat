package io.aequicor.heartbeat.feature.togglespanel.impl.domain

import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelEffect
import io.aequicor.heartbeat.feature.togglespanel.api.TogglesPanelIntent

/** Executes flag-panel effects through the domain repository port. */
class TogglesPanelEffects(private val repository: TogglesRepository) :
    EffectHandler<TogglesPanelEffect, TogglesPanelIntent> {
    override suspend fun handle(effect: TogglesPanelEffect, machine: EffectScope<TogglesPanelIntent>) {
        when (effect) {
            TogglesPanelEffect.Observe -> repository.observeStates().collect {
                machine.send(TogglesPanelIntent.Internal.Snapshot(it))
            }

            is TogglesPanelEffect.Write -> {
                repository.apply(effect.operation)
                machine.send(TogglesPanelIntent.Internal.Written)
            }
        }
    }
}
