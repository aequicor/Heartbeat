package io.aequicor.heartbeat.feature.organicai.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionAuthority
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionBinding
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiEnabled
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.OrganismId
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicAiMachine
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganicExecutorAddress
import io.aequicor.heartbeat.feature.organicai.impl.domain.OrganismJournal

/** Durable acknowledgement and live host state must agree; either side can revoke, neither alone can grant. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class OrganicExecutorAuthority(
    private val journal: OrganismJournal,
    private val machine: Lazy<OrganicAiMachine>,
    private val toggles: FeatureToggles,
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
}
