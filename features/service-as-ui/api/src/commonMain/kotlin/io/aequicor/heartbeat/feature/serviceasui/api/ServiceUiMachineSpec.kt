package io.aequicor.heartbeat.feature.serviceasui.api

import io.aequicor.heartbeat.core.statemachine.MachineSpec
import io.aequicor.heartbeat.core.statemachine.StateBuilder
import io.aequicor.heartbeat.core.statemachine.machineSpec

/** Automatic library reads before `Reload` is needed. */
public const val SERVICE_UI_LOAD_ATTEMPTS: Int = 3

/**
 * | State | Input | Guard | Result |
 * |---|---|---|---|
 * | any | BindHost | known place, or fewer than [ServiceUiLimits.HOSTS] | remember the context |
 * | any | UnbindHost | known place | forget it; in Ready also close the panels it opened |
 * | any | OwnersChanged | — | remember the snapshot; in Ready a complete one purges owners missing from it |
 * | Loading, Failed, Ready | AvailabilityChanged(false) | — | Disabled; a saved write: EditRejected(Unavailable) |
 * | Disabled, Loading, Failed | Edit | — | EditRejected(Unavailable) |
 * | Disabled | AvailabilityChanged(true) | — | Loading, Load |
 * | Loading | Loaded | — | Ready; purge owners deleted meanwhile |
 * | Loading | LoadFailed | — | Failed, RetryLoad while [SERVICE_UI_LOAD_ATTEMPTS] remain |
 * | Failed | RetryLoad / Reload | — | Loading, Load |
 * | Ready | Edit | no write being saved | apply: Persist a change; a rejection or a no-op is reported at once |
 * | Ready | Edit | a write being saved | EditRejected(Busy) |
 * | Ready | Persisted | the write being saved | commit, EditApplied; start a waiting purge |
 * | Ready | PersistFailed | the write being saved | drop it, EditRejected(Storage); start a waiting purge |
 * | Ready | OpenPanel | matching expected context, dock, enabled surface, args and panel limits | open in the dock |
 * | Ready | ClosePanel | open dock | close it |
 * | Ready | RequestPrompt | bound place, matching expected context, bounded text | PromptRequested with context |
 * | Ready | RequestFocusCard | matching chat context, enabled surface of the requesting owner | CardFocusRequested |
 * | Ready | ResolveSlot | exact host context and library snapshot | publish or clear inline slot presence |
 * | Ready | ResolveApp | exact host context and library snapshot | select the canonical app or revoke it |
 * | Ready | OpenApp | exact selected identity and context | expand the retained app, including before a chat |
 * | Ready | CloseApp | matching opening sequence | close its modal, retaining the app |
 *
 * Writes are serialized; callers retry on Busy. An expected library revision mismatch reports Conflict before applying
 * the operation. Rebinding or removing a host clears its slot presence; a library commit clears all slot presence
 * until the feature resolves it again. Disabling drops every slot projection. A commit closes the panels of surfaces
 * it deleted or disabled. Purges of owners whose deletion a complete provider snapshot confirmed are the feature's
 * own writes: they wait for the
 * write being saved and are not retried after a storage failure until the next snapshot. Stale storage responses and
 * other unexpected inputs are ignored.
 */
public val ServiceUiMachineSpec: MachineSpec<ServiceUiState, ServiceUiIntent, ServiceUiEffect, ServiceUiOutput> =
    machineSpec(ServiceUiMachineKey, ServiceUiState.Disabled()) {
        state<ServiceUiState.Disabled> {
            on<ServiceUiIntent.Internal.AvailabilityChanged>(guard = { intent.isEnabled }) {
                goto<ServiceUiState.Loading> { ServiceUiState.Loading(state.hosts, state.owners) }
                effect { ServiceUiEffect.Load }
            }
        }
        state<ServiceUiState.Loading> {
            on<ServiceUiIntent.Internal.Loaded> {
                goto<ServiceUiState.Ready> { loaded(state, intent.library) }
                effect { persistOf(null, loaded(state, intent.library)) }
            }
            on<ServiceUiIntent.Internal.LoadFailed> {
                goto<ServiceUiState.Failed> { ServiceUiState.Failed(state.hosts, state.owners, state.attempt + 1) }
                effect {
                    (state.attempt + 1).takeIf { it < SERVICE_UI_LOAD_ATTEMPTS }?.let { ServiceUiEffect.RetryLoad(it) }
                }
            }
        }
        state<ServiceUiState.Failed> {
            on<ServiceUiIntent.Internal.RetryLoad> {
                goto<ServiceUiState.Loading> { ServiceUiState.Loading(state.hosts, state.owners, state.attempt) }
                effect { ServiceUiEffect.Load }
            }
            on<ServiceUiIntent.Public.Reload> {
                goto<ServiceUiState.Loading> { ServiceUiState.Loading(state.hosts, state.owners, state.attempt) }
                effect { ServiceUiEffect.Load }
            }
        }
        state<ServiceUiState.Ready> { ready() }
        any {
            on<ServiceUiIntent.Public.BindHost>(guard = {
                intent.host in state.hosts || state.hosts.size < ServiceUiLimits.HOSTS
            }) {
                stay { state.withHosts(state.hosts + (intent.host to intent.context)) }
            }
            on<ServiceUiIntent.Public.UnbindHost>(guard = { intent.host in state.hosts }) {
                stay { state.withHosts(state.hosts - intent.host) }
            }
            on<ServiceUiIntent.Internal.OwnersChanged> {
                stay { state.withOwners(state.owners + (intent.kind to intent.owners)) }
            }
            on<ServiceUiIntent.Internal.AvailabilityChanged>(guard = {
                !intent.isEnabled && state !is ServiceUiState.Disabled
            }) {
                goto<ServiceUiState.Disabled> { ServiceUiState.Disabled(state.hosts, state.owners) }
                output {
                    (state as? ServiceUiState.Ready)?.write?.let {
                        ServiceUiOutput.EditRejected(it.write.id, UiEditRejection.Unavailable)
                    }
                }
            }
            on<ServiceUiIntent.Internal.Edit> {
                output { ServiceUiOutput.EditRejected(intent.write.id, UiEditRejection.Unavailable) }
            }
        }
        onEffectFailure { effect, _ ->
            when (effect) {
                ServiceUiEffect.Load -> ServiceUiIntent.Internal.LoadFailed
                is ServiceUiEffect.Persist -> ServiceUiIntent.Internal.PersistFailed(effect.writeId)
                is ServiceUiEffect.RetryLoad -> null
            }
        }
    }

private typealias ReadyBuilder =
    StateBuilder<ServiceUiState, ServiceUiState.Ready, ServiceUiIntent, ServiceUiEffect, ServiceUiOutput>

private fun ReadyBuilder.ready() {
    appTransitions()
    slotTransitions()
    on<ServiceUiIntent.Internal.RequestFocusCard>(guard = {
        intent.expectedContext.call.session != null && state.hosts[intent.host] == intent.expectedContext &&
            intent.cardId.isNotBlank() &&
            state.library[intent.surface]?.let { it.isEnabled && it.content.owner == intent.owner } == true
    }) {
        output { ServiceUiOutput.CardFocusRequested(intent.host, intent.cardId, intent.expectedContext) }
    }
    on<ServiceUiIntent.Internal.Edit> {
        stay { state.edited(intent.write) }
        effect { persistOf(state, state.edited(intent.write)) }
        output { state.editOutcome(intent.write) }
    }
    on<ServiceUiIntent.Internal.Persisted>(guard = { state.write?.write?.id == intent.writeId }) {
        stay { state.committed() }
        effect { persistOf(state, state.committed()) }
        output { state.write?.let { ServiceUiOutput.EditApplied(it.write.id, it.result.surface, it.result.removed) } }
    }
    on<ServiceUiIntent.Internal.PersistFailed>(guard = { state.write?.write?.id == intent.writeId }) {
        stay { state.copy(write = null).purged() }
        effect { persistOf(state, state.copy(write = null).purged()) }
        output { ServiceUiOutput.EditRejected(intent.writeId, UiEditRejection.Storage) }
    }
    on<ServiceUiIntent.Internal.OwnersChanged> {
        stay { state.ownersChanged(intent) }
        effect { persistOf(state, state.ownersChanged(intent)) }
    }
    on<ServiceUiIntent.Public.UnbindHost>(guard = { intent.host in state.hosts }) {
        stay {
            state.copy(
                hosts = state.hosts - intent.host,
                panels = state.panels.filterValues { it.host != intent.host },
                apps = state.apps - intent.host,
                slots = state.slots - intent.host,
            )
        }
    }
    on<ServiceUiIntent.Public.OpenPanel>(guard = { state.canOpen(intent) }) {
        stay { state.opened(intent) }
    }
    on<ServiceUiIntent.Public.ClosePanel>(guard = { intent.dock in state.panels }) {
        stay { state.copy(panels = state.panels - intent.dock) }
    }
    on<ServiceUiIntent.Internal.RequestPrompt>(guard = {
        intent.host in state.hosts && intent.text.isNotBlank() && intent.text.length <= ServiceUiLimits.PROMPT_CHARS &&
            (intent.expectedContext == null || intent.expectedContext == state.hosts[intent.host])
    }) {
        output {
            ServiceUiOutput.PromptRequested(
                intent.host,
                intent.text,
                intent.isSubmitted,
                state.hosts.getValue(intent.host),
            )
        }
    }
}

private fun ServiceUiState.withHosts(hosts: Map<UiHostId, UiHostContext>): ServiceUiState = when (this) {
    is ServiceUiState.Disabled -> copy(hosts = hosts)

    is ServiceUiState.Loading -> copy(hosts = hosts)

    is ServiceUiState.Failed -> copy(hosts = hosts)

    is ServiceUiState.Ready -> copy(
        hosts = hosts,
        apps = apps.filter { (host, app) -> hosts[host] == app.context },
        slots = slots.filterKeys { hosts[it] == this.hosts[it] },
    )
}

private fun ServiceUiState.withOwners(owners: Map<String, UiOwners>): ServiceUiState = when (this) {
    is ServiceUiState.Disabled -> copy(owners = owners)
    is ServiceUiState.Loading -> copy(owners = owners)
    is ServiceUiState.Failed -> copy(owners = owners)
    is ServiceUiState.Ready -> copy(owners = owners)
}

private fun loaded(state: ServiceUiState.Loading, library: UiLibrary): ServiceUiState.Ready =
    ServiceUiState.Ready(state.hosts, state.owners, library).checkedOwners(state.owners.keys)

/** Persist of the write [next] started; null when it still saves the write of [previous], or saves nothing. */
private fun persistOf(previous: ServiceUiState.Ready?, next: ServiceUiState.Ready): ServiceUiEffect.Persist? =
    next.write
        ?.takeIf { it.write.id != previous?.write?.write?.id }
        ?.let { ServiceUiEffect.Persist(it.write.id, it.result.library) }

private fun ServiceUiState.Ready.edited(write: UiWrite): ServiceUiState.Ready {
    val result = library.apply(write)
    return when {
        this.write != null || result !is UiWriteResult.Applied || result.library == library -> this
        else -> copy(write = UiPendingWrite(write, result))
    }
}

private fun ServiceUiState.Ready.editOutcome(write: UiWrite): ServiceUiOutput? {
    if (this.write != null) return ServiceUiOutput.EditRejected(write.id, UiEditRejection.Busy)
    return when (val result = library.apply(write)) {
        is UiWriteResult.Rejected -> ServiceUiOutput.EditRejected(write.id, result.reason.toEdit())
        is UiWriteResult.Applied -> ServiceUiOutput.EditApplied(write.id, null).takeIf { result.library == library }
    }
}

private fun UiLibrary.apply(write: UiWrite): UiWriteResult =
    if (write.expectedLibraryRevision != null && write.expectedLibraryRevision != revision) {
        UiWriteResult.Rejected(UiWriteRejection.Conflict)
    } else {
        apply(write.op)
    }

private fun ServiceUiState.Ready.committed(): ServiceUiState.Ready {
    val pending = write ?: return this
    val library = pending.result.library
    return copy(
        library = library,
        write = null,
        panels = panels.filterValues { library.canOpen(it.surface) },
        apps = apps.filterValues { library[it.surface]?.isEnabled == true },
        slots = emptyMap(),
    )
        .purged()
}

/** Starts the waiting purge when no write is being saved. */
private fun ServiceUiState.Ready.purged(): ServiceUiState.Ready {
    if (write != null || purge.isEmpty()) return this
    val write = UiWrite("purge.${writes + 1}", UiWriteOrigin.System, UiWriteOp.Purge(purge))
    val result = library.apply(write.op) as? UiWriteResult.Applied
    return if (result == null || result.library == library) {
        copy(purge = emptySet())
    } else {
        copy(write = UiPendingWrite(write, result), purge = emptySet(), writes = writes + 1)
    }
}

private fun ServiceUiState.Ready.ownersChanged(intent: ServiceUiIntent.Internal.OwnersChanged) =
    copy(owners = owners + (intent.kind to intent.owners)).checkedOwners(setOf(intent.kind))

/** Queues owners of [kinds] that a complete snapshot no longer lists, then starts the purge when idle. */
private fun ServiceUiState.Ready.checkedOwners(kinds: Set<String>): ServiceUiState.Ready {
    val current = write?.result?.library ?: library
    val deleted = current.surfaces.map { it.content.owner }.filterTo(mutableSetOf()) { owner ->
        val snapshot = owners[owner.kind]
        owner.kind in kinds && snapshot != null && snapshot.isComplete && snapshot.owners.none { it.id == owner.id }
    }
    return copy(purge = purge + deleted).purged()
}

private fun UiLibrary.canOpen(id: UiSurfaceId): Boolean = this[id]?.let {
    it.isEnabled && (it.content.placement == UiPlacement.Panel || it.content.placement == UiPlacement.Modal)
} == true

private fun ServiceUiState.Ready.canOpen(intent: ServiceUiIntent.Public.OpenPanel): Boolean {
    val context = hosts[intent.host] ?: return false
    val dock = context.dock ?: return false
    return (intent.expectedContext == null || intent.expectedContext == context) && library.canOpen(intent.surface) &&
        intent.args.toString().encodeToByteArray().size <= ServiceUiLimits.ARGS_BYTES &&
        (dock in panels || panels.size < ServiceUiLimits.PANELS)
}

private fun ServiceUiState.Ready.opened(intent: ServiceUiIntent.Public.OpenPanel): ServiceUiState.Ready {
    val dock = hosts[intent.host]?.dock ?: return this
    val sequence = panelSequence + 1
    val panel = UiPanel(intent.surface, intent.host, intent.args, sequence)
    return copy(panels = panels + (dock to panel), panelSequence = sequence)
}

private fun UiWriteRejection.toEdit(): UiEditRejection = when (this) {
    UiWriteRejection.Conflict -> UiEditRejection.Conflict
    UiWriteRejection.Missing -> UiEditRejection.Missing
    UiWriteRejection.Limit -> UiEditRejection.Limit
}
