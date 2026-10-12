package io.aequicor.heartbeat.feature.harness.impl.data.services

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessMachineKey
import io.aequicor.heartbeat.feature.harness.api.HarnessMutation
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.activeHarnesses
import io.aequicor.heartbeat.feature.harness.impl.domain.runtime.HarnessEventGate
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessAccessSnapshot
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessDeliveryPermit
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessSchedulerAccess
import io.aequicor.heartbeat.feature.harness.impl.domain.services.HarnessTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Uses persisted scheduler routing and attachments even before any facade callback after restart. Reading the
 * registry never starts the library or an engine. A helper must be durably attached before its first prompt;
 * neither owner text nor an action-id prefix can prove helper affiliation. Until the library and enabled
 * generation are restored, a decision is [HarnessAccess.Unknown], never a refusal.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class MachineHarnessSchedulerAccess(
    private val machines: MachineRegistry,
    private val toggles: FeatureToggles,
    private val gate: HarnessEventGate,
) : HarnessSchedulerAccess {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun permits(harness: HarnessId, target: HarnessTarget?): Flow<HarnessAccess> =
        toggles.observe(HarnessEnabled).flatMapLatest { enabled ->
            if (!enabled) {
                flowOf(HarnessAccess.Denied)
            } else {
                combine(
                    machines.observe(HarnessMachineKey).flatMapLatest { it?.state ?: flowOf(null) },
                    gate.epochs,
                ) { library, epoch ->
                    val isAllowed = HarnessSchedulerSnapshot(library, epoch, gate.hasOpened)
                        .allows(harness, target)
                    // A permit is bound to its enabled generation; other decisions do not depend on it.
                    isAllowed to epoch.takeIf { isAllowed == true }
                }.distinctUntilChanged().map { (isAllowed, epoch) ->
                    when (isAllowed) {
                        true -> HarnessAccess.Permitted(HarnessDeliveryPermit { isCurrent(harness, target, epoch) })
                        false -> HarnessAccess.Denied
                        null -> HarnessAccess.Unknown
                    }
                }
            }
        }

    override fun snapshot(): HarnessAccessSnapshot = current()

    /** Only this decision and its enabled generation matter; unrelated library edits keep the permit current. */
    private suspend fun isCurrent(harness: HarnessId, target: HarnessTarget?, epoch: Long?): Boolean {
        if (!toggles.get(HarnessEnabled)) return false
        val current = current()
        return current.epoch == epoch && current.allows(harness, target) == true
    }

    private fun current(): HarnessSchedulerSnapshot = HarnessSchedulerSnapshot(
        machines.find(HarnessMachineKey)?.state?.value,
        gate.currentEpoch,
        gate.hasOpened,
    )
}

/** A missing library or unopened generation leaves admission unknown. */
internal data class HarnessSchedulerSnapshot(
    val library: HarnessState?,
    val epoch: Long?,
    val hasOpened: Boolean = true,
) : HarnessAccessSnapshot {
    override fun allows(harness: HarnessId, target: HarnessTarget?): Boolean? {
        if (library?.isSuspended == true) return false
        if (epoch == null) return if (hasOpened) false else null
        val ready = when (library) {
            null, is HarnessState.Idle, is HarnessState.Loading -> return null
            is HarnessState.Failed -> return false
            is HarnessState.Ready -> library
        }
        if (!ready.isRuntimeAvailable) return false
        val enabled = ready.harnesses.map { it.harness }.filter {
            it.isEnabled && ready.pending[it.id] !is HarnessMutation.Remove
        }
        if (enabled.none { it.id == harness }) return false
        return if (target == null) true else allowsTarget(ready, enabled, harness, target)
    }

    /** Only an explicit connection to the target chat admits its harness. */
    private fun allowsTarget(
        ready: HarnessState.Ready,
        enabled: List<Harness>,
        harness: HarnessId,
        target: HarnessTarget,
    ): Boolean = activeHarnesses(enabled, ready.attachments, target.session).any { it.id == harness }

    override fun toString(): String = "HarnessSchedulerSnapshot(***)"
}
