package io.aequicor.heartbeat.feature.organicai.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRelay
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionAuthority
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.organicai.api.CellId
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiEnabled
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.api.cell
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicAiMachine
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicExecutorAddress
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeJournal
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicNativeStage
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicPermissionRelays
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganismJournal

/** Durable acknowledgement and live host state must agree; either side can revoke, neither alone can grant. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class OrganicExecutorAuthority(
    private val journal: OrganismJournal,
    private val machine: Lazy<OrganicAiMachine>,
    private val toggles: FeatureToggles,
    private val native: OrganicNativeJournal,
    private val permissions: OrganicPermissionRelays? = null,
) : SessionExecutionAuthority {
    override val provider: String = "organic"

    override suspend fun permits(binding: SessionExecutionBinding): Boolean {
        if (binding.instance.provider != provider || !toggles.get(OrganicAiEnabled)) return false
        val address = OrganicExecutorAddress.parse(binding.instance.id)
        val id = OrganismId(address.organism)
        val saved = journal.read(id) ?: return false
        val current = (machine.value.state.value as? OrganicAiState.Living)?.organisms?.get(id) ?: return false
        return address.permits(saved, binding) && address.permits(current, binding)
    }

    override suspend fun enterPermission(relay: PermissionRelay, decision: PermissionDecision): Boolean {
        if (!permits(relay.binding) || !relay.accepts(decision)) return false
        val address = OrganicExecutorAddress.parse(relay.binding.instance.id)
        if (address.kind != OrganicExecutorAddress.Kind.Cell) return false
        val id = OrganismId(address.organism)
        val current = (machine.value.state.value as? OrganicAiState.Living)?.organisms?.get(id) ?: return false
        val phase = current.cell(CellId(address.member))?.phase as? CellPhase.Working ?: return false
        if (relay !in phase.relays || relay.permission !in phase.awaiting) return false
        val receipt = native.attempts(id, address.incarnation).singleOrNull {
            it.claim.execution == relay.binding && it.claim.request == relay.request
        } ?: return false
        val isPending = receipt.outcome == null && receipt.stage in setOf(
            OrganicNativeStage.Sending,
            OrganicNativeStage.Accepted,
        )
        if (!isPending || receipt.session != relay.session || receipt.claim.target != relay.target) return false
        return permissions?.consume(relay) == true
    }

    override suspend fun permitsPrompt(
        binding: SessionExecutionBinding,
        session: SessionRef,
        target: EngineTarget,
        request: PromptRequest,
    ): Boolean {
        if (!permits(binding)) return false
        val address = OrganicExecutorAddress.parse(binding.instance.id)
        val current = (machine.value.state.value as? OrganicAiState.Living)
            ?.organisms?.get(OrganismId(address.organism)) ?: return false
        if (!address.permitsRequest(current, request.id)) return false
        val receipt = native.attempts(OrganismId(address.organism), address.incarnation)
            .singleOrNull { it.claim.execution == binding && it.claim.request == request.id } ?: return false
        return receipt.stage == OrganicNativeStage.Entered && receipt.session == session &&
            receipt.claim.target == target && receipt.prepared == request
    }

    override suspend fun enterPrompt(
        binding: SessionExecutionBinding,
        session: SessionRef,
        request: RequestId,
    ): Boolean {
        if (!permits(binding)) return false
        val address = OrganicExecutorAddress.parse(binding.instance.id)
        val current = (machine.value.state.value as? OrganicAiState.Living)
            ?.organisms?.get(OrganismId(address.organism)) ?: return false
        if (!address.permitsRequest(current, request)) return false
        val id = OrganismId(address.organism)
        val receipt = native.attempts(id, address.incarnation)
            .singleOrNull { it.claim.execution == binding && it.claim.request == request } ?: return false
        if (receipt.stage != OrganicNativeStage.Entered || receipt.session != session || receipt.prepared == null) {
            return false
        }
        native.advance(
            id,
            address.incarnation,
            receipt,
            receipt.copy(stage = OrganicNativeStage.Sending, revision = receipt.revision + 1),
        )
        return true
    }
}
