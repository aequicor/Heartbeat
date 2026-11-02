package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRelay
import io.aequicor.heartbeat.feature.aiengine.facade.api.RelaysPermissions
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.organicai.api.CellPhase
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiMachineKey
import io.aequicor.heartbeat.feature.organicai.api.OrganicAiState
import io.aequicor.heartbeat.feature.organicai.api.isDeveloping
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Existing organic pending appearances projected through their full owner; native commands never resume a cell. */
@Inject
internal class StudioOrganicPermissions(
    private val machines: MachineRegistry,
    private val records: StudioChatRecords,
    private val facade: EngineFacade,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val permissions: Flow<List<StudioPermission>> = machines.observe(OrganicAiMachineKey).flatMapLatest {
        it?.state ?: flowOf(OrganicAiState.Dormant)
    }.map { project(it) }

    suspend fun current(): List<StudioPermission> = project(machines.find(OrganicAiMachineKey)?.state?.value)

    suspend fun respond(relay: PermissionRelay, decision: PermissionDecision) {
        check(current().any { it.relay == relay }) { "Organic permission appearance expired" }
        facade.sessions.get(relay.session).features.requireFeature(RelaysPermissions).respond(relay, decision)
    }

    private suspend fun project(state: OrganicAiState?): List<StudioPermission> {
        val living = state as? OrganicAiState.Living ?: return emptyList()
        val owners = resolvedStudioIdentities(records.store.get(ChatsKey).orEmpty())
            .mapNotNull { it.logicalSession?.takeIf { context -> context.isHumanInteractive } }.associateBy { it.id }
        return living.organisms.values.filter { it.isDeveloping }.flatMap { organism ->
            organism.cells.flatMap { cell ->
                (cell.phase as? CellPhase.Working)?.relays.orEmpty().mapNotNull { relay ->
                    val owner = owners[relay.owner]
                    if (owner == null || owner.root != relay.binding.logicalSession?.root) {
                        null
                    } else {
                        relay.toOwnerPermission(cell.name)
                    }
                }
            }
        }
    }
}

/** The nonce is the UI request id; the original native permission remains inside the exact relay snapshot. */
internal fun PermissionRelay.toOwnerPermission(name: String): StudioPermission? {
    val offered = permission.toStudio(owner.value)
    val options = offered.options.filter { it.isSkip || it.isOneTime }
    return if (options.isEmpty()) {
        null
    } else {
        offered.copy(
            requestId = nonce,
            title = "$name: ${offered.title}",
            options = options,
            relay = this,
        )
    }
}
