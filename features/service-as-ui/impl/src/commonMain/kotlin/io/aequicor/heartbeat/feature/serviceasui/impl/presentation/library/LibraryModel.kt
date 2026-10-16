package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.library

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurface
import io.aequicor.heartbeat.feature.serviceasui.api.UiWrite
import io.aequicor.heartbeat.feature.serviceasui.api.UiWriteOp
import io.aequicor.heartbeat.feature.serviceasui.api.UiWriteOrigin
import io.aequicor.heartbeat.feature.serviceasui.impl.di.scope.ServiceUiLibraryScope
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.ServiceUiMachine
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.submit
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.WriteNoticeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.notice
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.toUi
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.plugins.reduce
import kotlin.time.Clock
import kotlin.uuid.Uuid

private typealias LibraryPipeline = PipelineContext<LibraryState, LibraryIntent, LibraryAction>

/**
 * The surface library of the profile grouped by owner. Switching a surface is a user write at its current revision;
 * the list follows the machine, so a write that fails leaves the switch where the saved library has it and tells why.
 */
@SingleIn(ServiceUiLibraryScope::class)
@Inject
internal class LibraryModel(
    private val machine: ServiceUiMachine,
    private val clock: Clock,
    factory: HeartbeatStoreFactory,
    @ForScope(ServiceUiLibraryScope::class) handle: ScopeHandle,
) {
    private val log = Log.tag("ServiceUiLibrary")

    private val screen = factory.createScreen<LibraryState, LibraryIntent, LibraryAction>(
        name = "ServiceUiLibrary",
        initial = LibraryState().reflected(machine.state.value),
        onError = { this },
    ) {
        reflect(machine) { reflected(it) }
        reduce { intent -> Work(this).handle(intent) }
    }
    val store: Store<LibraryState, LibraryIntent, LibraryAction> = screen.store

    /** Latest screen state, read synchronously for the first frame. */
    val current = screen.current

    init {
        store.start(handle.coroutineScope)
    }

    /** Work of one intent on the store [pipeline]. */
    private inner class Work(private val pipeline: LibraryPipeline) {
        suspend fun handle(intent: LibraryIntent) {
            when (intent) {
                is LibraryIntent.SetEnabled -> setEnabled(intent)

                is LibraryIntent.Open -> {
                    log.i { "Open interface ${intent.id}" }
                    pipeline.action(LibraryAction.OpenSurface(intent.id))
                }

                LibraryIntent.Reload -> pipeline.sendTo(machine, ServiceUiIntent.Public.Reload)

                LibraryIntent.DismissNotice -> pipeline.updateState { copy(notice = null) }
            }
        }

        private suspend fun setEnabled(intent: LibraryIntent.SetEnabled) {
            val ready = machine.state.value as? ServiceUiState.Ready ?: return
            val surface = ready.library.surfaces.firstOrNull { it.id.value == intent.id } ?: return
            if (surface.isEnabled == intent.isEnabled) return
            var isStarted = false
            pipeline.updateState {
                isStarted = intent.id !in pending
                if (isStarted) copy(pending = (pending + intent.id).toPersistentSet(), notice = null) else this
            }
            if (!isStarted) return
            log.i { "User ${if (intent.isEnabled) "enables" else "disables"} interface ${intent.id}" }
            pipeline.launch {
                var notice: WriteNoticeUi? = null
                try {
                    val op = UiWriteOp.SetEnabled(surface.id, intent.isEnabled, surface.revision, clock.now())
                    val write = UiWrite("user.${Uuid.random().toHexString()}", UiWriteOrigin.User, op)
                    notice = machine.submit(write).notice()
                } finally {
                    // An unexpected failure must not leave the switch locked.
                    withContext(NonCancellable) {
                        pipeline.updateState {
                            copy(
                                pending = (pending - intent.id).toPersistentSet(),
                                notice = notice,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun LibraryState.reflected(state: ServiceUiState): LibraryState = when (state) {
    is ServiceUiState.Disabled -> copy(phase = LibraryPhase.Disabled)
    is ServiceUiState.Loading -> copy(phase = LibraryPhase.Loading)
    is ServiceUiState.Failed -> copy(phase = LibraryPhase.Failed)
    is ServiceUiState.Ready -> copy(phase = LibraryPhase.Ready, groups = groupsOf(state))
}

private fun groupsOf(state: ServiceUiState.Ready) = state.library.surfaces
    .groupBy { it.content.owner }
    .map { (owner, surfaces) -> groupOf(state, owner, surfaces) }
    .sortedWith(compareBy({ it.title.lowercase() }, { it.key }))
    .toImmutableList()

private fun groupOf(state: ServiceUiState.Ready, owner: UiOwner, surfaces: List<UiSurface>): OwnerGroupUi {
    val info = state.owners[owner.kind]?.owners?.firstOrNull { it.id == owner.id }
    return OwnerGroupUi(
        key = "${owner.kind}:${owner.id}",
        title = info?.title?.takeIf { it.isNotBlank() } ?: owner.id,
        isKnown = info != null,
        isEnabled = info?.isEnabled == true,
        surfaces = surfaces.sortedWith(compareBy({ it.content.title.lowercase() }, { it.id.value }))
            .map { surface ->
                SurfaceRowUi(
                    id = surface.id.value,
                    title = surface.content.title,
                    description = surface.content.description,
                    placement = surface.content.placement.toUi(),
                    isEnabled = surface.isEnabled,
                )
            }
            .toImmutableList(),
    )
}
