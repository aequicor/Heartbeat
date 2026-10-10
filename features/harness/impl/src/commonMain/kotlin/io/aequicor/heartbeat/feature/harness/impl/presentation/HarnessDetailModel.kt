package io.aequicor.heartbeat.feature.harness.impl.presentation

import androidx.compose.runtime.Immutable
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.flowmvi.reflect
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.harness.api.Harness
import io.aequicor.heartbeat.feature.harness.api.HarnessAuthor
import io.aequicor.heartbeat.feature.harness.api.HarnessChange
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessIntent
import io.aequicor.heartbeat.feature.harness.api.HarnessOutput
import io.aequicor.heartbeat.feature.harness.api.HarnessScope
import io.aequicor.heartbeat.feature.harness.api.HarnessState
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsIntent
import io.aequicor.heartbeat.feature.harness.api.workflow.HarnessRunsState
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowViewer
import io.aequicor.heartbeat.feature.harness.impl.domain.HarnessMachine
import io.aequicor.heartbeat.feature.harness.impl.domain.workflow.HarnessRunsMachine
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed
import kotlin.time.Clock

/** Title and description being edited. */
@Immutable
internal data class MetaDraftUi(val title: String, val description: String) {
    val isValid: Boolean get() = title.isNotBlank() && '\n' !in title && '\n' !in description
}

/** Why the last change did not apply. */
internal enum class DetailErrorUi { SaveFailed, Conflict }

/** One harness with its items, connections, tool policy entry and runs. */
@Immutable
internal data class HarnessDetailState(
    val phase: PhaseUi = PhaseUi.Loading,
    val name: String = "",
    val title: String = "",
    val description: String = "",
    val isEnabled: Boolean = false,
    val scope: ScopeUi = ScopeUi.Attached,
    val projects: ImmutableList<ProjectChoiceUi> = persistentListOf(),
    val attachments: ImmutableList<AttachmentUi> = persistentListOf(),
    val items: ImmutableList<ItemRowUi> = persistentListOf(),
    val runs: ImmutableList<RunUi> = persistentListOf(),
    val hostedOff: Int = 0,
    val nativeSwitches: Int = 0,
    val meta: MetaDraftUi? = null,
    val isDeleting: Boolean = false,
    val error: DetailErrorUi? = null,
) : MVIState

/** Controls of the detail screen. */
internal sealed interface HarnessDetailIntent : MVIIntent {
    data class SetEnabled(val isEnabled: Boolean) : HarnessDetailIntent
    data class SetItemEnabled(val item: String, val isEnabled: Boolean) : HarnessDetailIntent
    data class OpenItem(val item: String) : HarnessDetailIntent
    data object OpenTools : HarnessDetailIntent
    data class SelectScope(val scope: ScopeUi) : HarnessDetailIntent
    data class ToggleProject(val key: String) : HarnessDetailIntent
    data class Detach(val key: String) : HarnessDetailIntent
    data class CancelRun(val run: String) : HarnessDetailIntent
    data object EditMeta : HarnessDetailIntent
    data class ChangeMeta(val title: String, val description: String) : HarnessDetailIntent
    data object SaveMeta : HarnessDetailIntent
    data object CancelMeta : HarnessDetailIntent
    data object Delete : HarnessDetailIntent
    data object ConfirmDelete : HarnessDetailIntent
    data object CancelDelete : HarnessDetailIntent
    data object DismissError : HarnessDetailIntent
}

/** One-off navigation the screen's component performs within its own lifecycle. */
internal sealed interface HarnessDetailAction : MVIAction {
    /** Opens the editor of an item. */
    data class OpenItem(val item: String) : HarnessDetailAction

    /** Opens the tool policy. */
    data object OpenTools : HarnessDetailAction

    /** The harness was deleted; the screen closes. */
    data object Close : HarnessDetailAction
}

private typealias DetailPipeline = PipelineContext<HarnessDetailState, HarnessDetailIntent, HarnessDetailAction>

private val log = Log.tag("HarnessHost")

/** Direct user edits need no approval; every command targets the revision shown on screen. */
internal class HarnessDetailModel(
    harness: String,
    private val machine: HarnessMachine,
    private val runs: HarnessRunsMachine,
    workspaces: LocalWorkspaces,
    factory: HeartbeatStoreFactory,
    scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) {
    private val id = HarnessId(harness)
    private val saved = MutableStateFlow<List<LocalWorkspace>>(emptyList())
    private val issued = MutableStateFlow<Set<RequestId>>(emptySet())

    val store = factory.create<HarnessDetailState, HarnessDetailIntent, HarnessDetailAction>(
        "HarnessDetail",
        HarnessDetailState().reflectHarness(machine.state.value),
        onError = { this },
    ) {
        reflect(machine, onOutput = { output -> onLibraryOutput(output) }) { reflectHarness(it) }
        reflect(runs) { reflectRuns(it) }
        whileSubscribed {
            workspaces.observe().collect { list ->
                log.v { "Saved projects for the scope: ${saved.value.size} -> ${list.size}" }
                saved.value = list
                updateState { reflectHarness(machine.state.value) }
            }
        }
        reduce { intent -> handle(intent) }
    }

    init {
        store.start(scope)
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun DetailPipeline.onLibraryOutput(output: HarnessOutput) {
        when {
            output is HarnessOutput.Deleted && output.id == id -> action(HarnessDetailAction.Close)

            output is HarnessOutput.StorageFailed && output.requestId in issued.value ->
                updateState { copy(error = DetailErrorUi.SaveFailed) }

            output is HarnessOutput.Rejected && output.requestId in issued.value ->
                updateState { copy(error = DetailErrorUi.Conflict) }
        }
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun DetailPipeline.handle(intent: HarnessDetailIntent) {
        val harness = current()
        when (intent) {
            is HarnessDetailIntent.OpenItem -> action(HarnessDetailAction.OpenItem(intent.item))

            HarnessDetailIntent.OpenTools -> action(HarnessDetailAction.OpenTools)

            is HarnessDetailIntent.CancelRun -> sendTo(
                runs,
                HarnessRunsIntent.Public.Cancel(request(), RunId(intent.run), WorkflowViewer.User),
            )

            HarnessDetailIntent.DismissError -> updateState { copy(error = null) }

            is HarnessDetailIntent.ChangeMeta ->
                updateState { copy(meta = meta?.copy(title = intent.title, description = intent.description)) }

            HarnessDetailIntent.CancelMeta -> updateState { copy(meta = null) }

            HarnessDetailIntent.CancelDelete -> updateState { copy(isDeleting = false) }

            HarnessDetailIntent.Delete -> updateState { copy(isDeleting = true) }

            is HarnessDetailIntent.SetEnabled,
            is HarnessDetailIntent.SetItemEnabled,
            is HarnessDetailIntent.SelectScope,
            is HarnessDetailIntent.ToggleProject,
            is HarnessDetailIntent.Detach,
            HarnessDetailIntent.EditMeta,
            HarnessDetailIntent.SaveMeta,
            HarnessDetailIntent.ConfirmDelete,
            -> if (harness != null) edit(harness, intent)
        }
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun DetailPipeline.edit(harness: Harness, intent: HarnessDetailIntent) {
        when (intent) {
            is HarnessDetailIntent.SetEnabled -> send(
                HarnessIntent.Public.SetEnabled(request(), id, intent.isEnabled, harness.revision, clock.now()),
            )

            is HarnessDetailIntent.SetItemEnabled -> send(
                HarnessIntent.Public.SetItemEnabled(
                    request(),
                    id,
                    ItemId(intent.item),
                    intent.isEnabled,
                    harness.revision,
                    clock.now(),
                ),
            )

            is HarnessDetailIntent.SelectScope ->
                scopeOf(intent.scope, harness)?.let { change(harness, HarnessChange.Scope(it)) }

            is HarnessDetailIntent.ToggleProject ->
                toggled(harness, intent.key)?.let { change(harness, HarnessChange.Scope(it)) }

            is HarnessDetailIntent.Detach -> sessionOf(intent.key)?.let { session ->
                send(HarnessIntent.Public.Detach(request(), id, session))
            }

            HarnessDetailIntent.EditMeta -> updateState { copy(meta = MetaDraftUi(harness.title, harness.description)) }

            HarnessDetailIntent.SaveMeta -> withState {
                val draft = meta?.takeIf { it.isValid } ?: return@withState
                updateState { copy(meta = null) }
                change(harness, HarnessChange.Meta(draft.title.trim(), draft.description.trim()))
            }

            HarnessDetailIntent.ConfirmDelete -> {
                updateState { copy(isDeleting = false) }
                send(HarnessIntent.Public.Delete(request(), id, harness.revision))
            }

            is HarnessDetailIntent.OpenItem,
            HarnessDetailIntent.OpenTools,
            is HarnessDetailIntent.CancelRun,
            HarnessDetailIntent.DismissError,
            is HarnessDetailIntent.ChangeMeta,
            HarnessDetailIntent.CancelMeta,
            HarnessDetailIntent.CancelDelete,
            HarnessDetailIntent.Delete,
            -> Unit
        }
    }

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun DetailPipeline.change(harness: Harness, change: HarnessChange) = send(
        HarnessIntent.Public.Update(request(), id, change, harness.revision, HarnessAuthor.User, clock.now()),
    )

    // PipelineContext is FlowMVI's coroutine-backed receiver for store updates and sendTo.
    @Suppress("SuspendFunWithCoroutineScopeReceiver")
    private suspend fun DetailPipeline.send(intent: HarnessIntent.Public) {
        issued.update { (it + intent.requestId).toList().takeLast(ISSUED_KEPT).toSet() }
        log.v { "Harness detail command issued: ${intent::class.simpleName.orEmpty()}" }
        sendTo(machine, intent) { updateState { copy(error = DetailErrorUi.SaveFailed) } }
    }

    /** Connected sessions are listed under their JSON key; a key that no longer parses is ignored. */
    private fun sessionOf(key: String): SessionRef? = try {
        Json.decodeFromString<SessionRef>(key)
    } catch (error: IllegalArgumentException) {
        log.w(IllegalArgumentException(error::class.simpleName)) { "Connected session key is not readable" }
        null
    }

    /** A projects scope needs at least one project; without saved projects it cannot be selected. */
    private fun scopeOf(scope: ScopeUi, harness: Harness): HarnessScope? = when (scope) {
        ScopeUi.Attached -> HarnessScope.Attached

        ScopeUi.Profile -> HarnessScope.Profile

        ScopeUi.Projects -> (harness.scope as? HarnessScope.Projects)
            ?: saved.value.firstOrNull()?.let { HarnessScope.Projects(setOf(it.ref)) }
    }

    private fun toggled(harness: Harness, key: String): HarnessScope? {
        val selected = (harness.scope as? HarnessScope.Projects)?.projects.orEmpty()
        val ref = WorkspaceRef(key)
        val next = if (ref in selected) selected - ref else selected + ref
        return next.takeIf { it.isNotEmpty() }?.let(HarnessScope::Projects)
    }

    private fun current(): Harness? =
        (machine.state.value as? HarnessState.Ready)?.harnesses?.firstOrNull { it.harness.id == id }?.harness

    private fun HarnessDetailState.reflectHarness(state: HarnessState): HarnessDetailState {
        val ready = state as? HarnessState.Ready ?: return copy(phase = state.phase())
        val entry = ready.harnesses.firstOrNull { it.harness.id == id } ?: return copy(phase = PhaseUi.NotFound)
        val harness = entry.harness
        val selected = (harness.scope as? HarnessScope.Projects)?.projects.orEmpty()
        val known = saved.value.map { ProjectChoiceUi(it.ref.value, it.name, it.ref in selected) }
        val removed = selected.filter { ref -> saved.value.none { it.ref == ref } }
            .map { ProjectChoiceUi(it.value, it.value, true) }
        return copy(
            phase = PhaseUi.Ready,
            name = harness.name.value,
            title = harness.title,
            description = harness.description,
            isEnabled = harness.isEnabled,
            scope = harness.scope.toUi(),
            projects = (known + removed).toImmutableList(),
            attachments = ready.attachments.filterValues { id in it }.keys
                .map { AttachmentUi(it.key(), it.label()) }.sortedBy { it.label }.toImmutableList(),
            items = entry.itemRows(ready.isRuntimeAvailable).toImmutableList(),
            hostedOff = harness.tools.hostedOff.size,
            nativeSwitches = harness.tools.native.values.sumOf { it.size },
        )
    }

    private fun HarnessDetailState.reflectRuns(state: HarnessRunsState): HarnessDetailState {
        val names = current()?.items?.associate { it.id to it.name.value }.orEmpty()
        val owned = (state as? HarnessRunsState.Ready)?.runs.orEmpty().asSequence().filter { it.harness == id }
            .sortedByDescending { it.startedAt }.take(RUNS_SHOWN).toList()
        return copy(runs = owned.map { it.toUi(names[it.workflow] ?: it.workflow.value) }.toImmutableList())
    }
}

private const val RUNS_SHOWN = 20
internal const val ISSUED_KEPT = 32
