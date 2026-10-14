package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.app

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiAppRoute
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostId
import io.aequicor.heartbeat.feature.serviceasui.api.UiLibrary
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceId
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceName
import io.aequicor.heartbeat.feature.serviceasui.impl.di.scope.ServiceUiAppScope
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.ServiceUiMachine
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardStorage
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiMethodCalls
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecDecoding
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.decodeUiSpec
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiFacts
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.platformFact
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceHost
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceModel
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceSession
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import pro.respawn.flowmvi.dsl.collect

/**
 * One retained app of a host, independent of its compact entries and of UI subscriptions. The machine owns modal
 * visibility; this coordinator resolves the active owner's app and retains its surface and document registration.
 * Another chat, owner, spec or argument set cancels it. Only a selected start app entering its first chat rebinds
 * the existing surface, revoking old calls while preserving the document and local form state.
 */
@SingleIn(ServiceUiAppScope::class)
@Inject
internal class AppHostModel(
    route: ServiceUiAppRoute,
    private val machine: ServiceUiMachine,
    private val cards: UiCardStorage,
    private val calls: UiMethodCalls,
    private val surfacesFactory: SurfaceModel.Factory,
    platform: PlatformInfo,
    @ForScope(ServiceUiAppScope::class) handle: ScopeHandle,
) {
    private val log = Log.tag("ServiceUiApp")
    private val host = UiHostId(route.host)
    private val scope = handle.coroutineScope
    private val platform = platformFact(platform.host)
    private val screen = MutableStateFlow(AppHostState())
    private val models = MutableStateFlow<ImmutableMap<String, SurfaceModel>>(persistentMapOf())
    private var running: Running? = null
    private var selected: AppSelection? = null
    private var drawings = 0L
    private var continuation: UiHostContext? = null

    val state: StateFlow<AppHostState> = screen.asStateFlow()
    val surfaces: StateFlow<ImmutableMap<String, SurfaceModel>> = models.asStateFlow()

    init {
        scope.launch { observe() }
        scope.launch {
            machine.state.collect { current ->
                val app = (current as? ServiceUiState.Ready)?.apps?.get(host)
                showState(screen.value.copy(isExpanded = app?.isExpanded == true, sequence = app?.sequence ?: 0))
            }
        }
    }

    /** Native chrome never executes an owner method; opening and closing remain machine transitions. */
    fun intent(intent: AppHostIntent) {
        scope.launch {
            when (intent) {
                AppHostIntent.Open -> selected?.let { open(it, it.surface.content.name.value) }
                is AppHostIntent.Close -> machine.send(ServiceUiIntent.Public.CloseApp(host, intent.sequence))
            }
        }
    }

    private data class Snapshot(val context: UiHostContext, val library: UiLibrary)
    private data class Resolved(val snapshot: Snapshot?, val selection: AppSelection?)
    private data class Running(
        val selection: AppSelection,
        val model: SurfaceModel,
        val scope: CoroutineScope,
        val key: String,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun observe() {
        machine.state.map { state ->
            val ready = state as? ServiceUiState.Ready ?: return@map null
            ready.hosts[host]?.let { Snapshot(it, ready.library) }
        }.distinctUntilChanged().flatMapLatest { snapshot ->
            prepare(snapshot)
            if (snapshot == null) {
                flowOf(Resolved(null, null))
            } else {
                val receipts = snapshot.context.call.session?.let(cards::observeLive) ?: flowOf(null)
                combine(calls.activeOwners(snapshot.context.call), receipts) { owners, card ->
                    Resolved(snapshot, selectApp(snapshot.library, snapshot.context, owners, card))
                }.distinctUntilChanged()
            }
        }.collect { show(it) }
    }

    private fun prepare(snapshot: Snapshot?) {
        val previous = running ?: return
        if (snapshot == null || snapshot.library[previous.selection.surface.id] != previous.selection.surface ||
            !acceptsContinuation(snapshot.context)
        ) {
            selected = null
            clear()
        } else if (snapshot.context != previous.selection.context) {
            previous.model.pause()
            selected = null
            showState(AppHostState())
            val destination = snapshot.context
            if (mayEnterFirstChat(previous.selection, destination)) {
                continuation = destination
                log.v { "App paused awaiting its first chat owner" }
            } else {
                clear()
            }
        }
    }

    private fun acceptsContinuation(context: UiHostContext): Boolean = continuation == null || continuation == context

    private suspend fun show(resolved: Resolved) {
        val snapshot = resolved.snapshot
        if (snapshot == null) {
            clear()
            return
        }
        val next = resolved.selection
        val request = ServiceUiIntent.Internal.ResolveApp(
            host,
            next?.target,
            snapshot.context,
            snapshot.library.revision,
        )
        if (machine.send(request) != SendResult.Accepted) return
        selected = next
        if (next == null) {
            if (continuation != snapshot.context) clear()
            showState(AppHostState())
            return
        }
        keep(next)
        val app = (machine.state.value as? ServiceUiState.Ready)?.apps?.get(host)
        showState(
            AppHostState(
                next.surface.content.title,
                next.surface.content.description,
                running?.key,
                isStart = next.context.call.session == null,
                isExpanded = app?.isExpanded == true,
                sequence = app?.sequence ?: 0,
                isLoading = running == null,
            ),
        )
    }

    private fun keep(next: AppSelection) {
        val current = running
        if (current != null && current.selection.sameSession(next)) {
            running = current.copy(selection = next)
            return
        }
        if (current != null && continuation == next.context && current.selection.canContinueAs(next)) {
            current.model.rebind(next.context.call, Host(next), UiFacts(platform, hasSession = true))
            running = current.copy(selection = next)
            continuation = null
            log.v { "App entered its first chat without replacing its document" }
            return
        }
        clear()
        val spec = (decodeUiSpec(next.surface.content.spec) as? UiSpecDecoding.Decoded)?.spec
        if (spec == null) {
            log.w { "App surface ${next.surface.id.value} has an unreadable spec" }
            return
        }
        val child = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val session = SurfaceSession(
            spec,
            next.surface.content.owner,
            next.context.call,
            next.target.args,
            UiFacts(platform, hasSession = next.context.call.session != null),
            Host(next),
            title = next.surface.content.title,
        )
        try {
            val model = surfacesFactory.create(session, child)
            val entry = Running(next, model, child, "app-${++drawings}")
            running = entry
            models.value = persistentMapOf(entry.key to model)
            // The app is a live workspace even while its full view is closed or its receipt is offscreen.
            child.launch { model.store.collect { states.collect { } } }
        } catch (error: CancellationException) {
            child.cancel()
            throw error
        } catch (error: Exception) {
            log.e(error.redactedFailure()) { "App surface could not start" }
            child.cancel()
        }
    }

    private fun clear() {
        log.v { "App model released" }
        running?.scope?.cancel()
        running = null
        models.value = persistentMapOf()
        continuation = null
        showState(AppHostState())
    }

    private fun showState(next: AppHostState) {
        log.v { "App presentation: start=${next.isStart}, expanded=${next.isExpanded}, loading=${next.isLoading}" }
        screen.value = next
    }

    private fun isCurrent(selection: AppSelection): Boolean {
        val ready = machine.state.value as? ServiceUiState.Ready ?: return false
        return selected?.sameSession(selection) == true && ready.hosts[host] == selection.context &&
            ready.library[selection.surface.id] == selection.surface
    }

    private suspend fun open(selection: AppSelection, name: String): Boolean {
        if (!isCurrent(selection) || name != selection.surface.content.name.value) return false
        return machine.send(
            ServiceUiIntent.Public.OpenApp(host, selection.surface.id, selection.context),
        ) == SendResult.Accepted
    }

    private inner class Host(private val selection: AppSelection) : SurfaceHost {
        override fun isActive(): Boolean = isCurrent(selection)

        override suspend fun prompt(text: String, isSubmitted: Boolean): Boolean = isCurrent(selection) &&
            machine.send(
                ServiceUiIntent.Internal.RequestPrompt(host, text, isSubmitted, selection.context),
            ) == SendResult.Accepted

        override suspend fun focusCard(name: String): Boolean = open(selection, name)

        override suspend fun openSurface(name: String, args: JsonObject): Boolean =
            args == selection.target.args && open(selection, name)

        override suspend fun openPanel(name: String, args: JsonObject): Boolean {
            if (!isCurrent(selection) || !name.matches(SURFACE_NAME)) return false
            val target = UiSurfaceId.of(selection.surface.content.owner, UiSurfaceName(name))
            return machine.send(
                ServiceUiIntent.Public.OpenPanel(host, target, args, selection.context),
            ) == SendResult.Accepted
        }
    }

    private companion object {
        val SURFACE_NAME = Regex("[a-z][a-z0-9_]{0,31}")
    }
}

/** Retain a paused start document only during the exact first-chat transition, never on another actual chat. */
private fun mayEnterFirstChat(previous: AppSelection?, context: UiHostContext?): Boolean {
    if (previous == null || context == null) return false
    val before = previous.context.call
    val after = context.call
    return before.session == null && after.session != null && before.workspace == after.workspace
}
