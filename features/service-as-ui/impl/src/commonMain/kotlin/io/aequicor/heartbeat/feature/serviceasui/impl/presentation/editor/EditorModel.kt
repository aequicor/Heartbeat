package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.editor

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.flowmvi.sendTo
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiSurfaceRoute
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethods
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceId
import io.aequicor.heartbeat.feature.serviceasui.api.UiWrite
import io.aequicor.heartbeat.feature.serviceasui.api.UiWriteOp
import io.aequicor.heartbeat.feature.serviceasui.api.UiWriteOrigin
import io.aequicor.heartbeat.feature.serviceasui.impl.di.scope.ServiceUiEditorScope
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.ServiceUiMachine
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiMethodCalls
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.WriteOutcome
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpec
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.formatUiSpec
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.submit
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiFacts
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.WriteNoticeUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.notice
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.platformFact
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceHost
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceIntent
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceModel
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store.SurfaceSession
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

private val CHECK_DELAY = 300.milliseconds

private typealias EditorPipeline = PipelineContext<EditorState, EditorIntent, EditorAction>

/**
 * Editor of one library surface: the draft of its spec, checked against the owner's methods and surfaces as the agent's
 * writes are, saved by the user over the revision it was made from (see [synced] for how drafts follow the library).
 * The last valid draft is previewed with the owner's methods, as for a new chat with the owner selected; the preview
 * loads data only when the user asks and cannot send prompts or open other surfaces.
 */
@SingleIn(ServiceUiEditorScope::class)
@Inject
internal class EditorModel(
    route: ServiceUiSurfaceRoute,
    private val machine: ServiceUiMachine,
    private val calls: UiMethodCalls,
    private val surfaceModels: SurfaceModel.Factory,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    platformInfo: PlatformInfo,
    factory: HeartbeatStoreFactory,
    @ForScope(ServiceUiEditorScope::class) handle: ScopeHandle,
) {
    private val log = Log.tag("ServiceUiEditor")
    private val id = UiSurfaceId(route.id)
    private val scope = handle.coroutineScope
    private val platform = platformFact(platformInfo.host)

    /** The saved surface as the subscription saw it last; null while the library does not hold it. */
    private val edited = MutableStateFlow<EditedSurface?>(null)

    /** The draft text as the store holds it last; checks follow it with a pause. */
    private val drafts = MutableStateFlow("")
    private val previewed = MutableStateFlow<Previewed?>(null)
    private val models = MutableStateFlow<ImmutableMap<String, SurfaceModel>>(persistentMapOf())
    private var running: Running? = null
    private var previews = 0L

    /** The store of the preview by [EditorState.previewKey]. */
    val surfaces: StateFlow<ImmutableMap<String, SurfaceModel>> = models.asStateFlow()

    val store: Store<EditorState, EditorIntent, EditorAction> =
        factory.create<EditorState, EditorIntent, EditorAction>(
            name = "ServiceUiEditor",
            initial = EditorState(),
            onError = { this },
        ) {
            whileSubscribed { Work(this).observe() }
            whileSubscribed { Work(this).check() }
            whileSubscribed {
                previewed.collect { next ->
                    val key = keep(next)
                    updateState { copy(previewKey = key) }
                }
            }
            reduce { intent -> Work(this).handle(intent) }
        }

    init {
        store.start(scope)
    }

    /** What the preview draws. */
    private data class Previewed(val spec: UiSpec, val owner: UiOwner) {
        override fun toString(): String = "Previewed(owner=${owner.id})"
    }

    private data class Running(
        val previewed: Previewed,
        val model: SurfaceModel,
        val scope: CoroutineScope,
        val key: String,
    )

    /** Work of the subscription or of one intent on the store [pipeline]. */
    private inner class Work(private val pipeline: EditorPipeline) {
        @OptIn(ExperimentalCoroutinesApi::class)
        suspend fun observe() {
            val owners = machine.state.map { (it as? ServiceUiState.Ready)?.library?.get(id)?.content?.owner }
                .distinctUntilChanged()
            // Unknown methods only weaken the check to warnings, so the editor does not wait for a slow provider.
            val methods = owners.flatMapLatest { owner ->
                owner?.let { calls.methods(it).onStart<UiMethods?> { emit(null) } } ?: flowOf(null)
            }
            combine(machine.state, methods) { state, methods -> state to methods }.collect { (state, methods) ->
                val ready = state as? ServiceUiState.Ready
                val surface = ready?.library?.get(id)
                val known = if (ready != null && surface != null) editedSurface(ready, surface, methods) else null
                edited.value = known
                update { synced(state, known) }
            }
        }

        /** Checks the draft a moment after it stops changing and previews its last valid version. */
        @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
        suspend fun check() {
            combine(drafts, edited) { text, known -> text to known }
                .debounce(CHECK_DELAY)
                .mapLatest { (text, known) ->
                    val check = known?.let { withContext(dispatchers.default) { checkDraft(text, it) } }
                    known to check
                }
                .collect { (known, check) ->
                    if (check != null) pipeline.updateState { withCheck(check) }
                    val owner = known?.surface?.content?.owner
                    val spec = check?.spec
                    previewed.value = when {
                        owner == null && machine.state.value is ServiceUiState.Loading -> previewed.value
                        owner == null -> null
                        spec != null -> Previewed(spec, owner)
                        else -> previewed.value?.takeIf { it.owner == owner }
                    }
                }
        }

        suspend fun handle(intent: EditorIntent) {
            when (intent) {
                is EditorIntent.ChangeText -> update { typed(intent.text) }

                EditorIntent.Save -> save()

                EditorIntent.Revert -> edited.value?.let { known ->
                    log.i { "User reverts the draft of interface ${id.value}" }
                    update { adopted(known.surface) }
                }

                EditorIntent.KeepDraft -> edited.value?.let { known ->
                    log.i { "User keeps the draft of interface ${id.value} over its saved change" }
                    val surface = known.surface
                    pipeline.updateState {
                        copy(base = surface.revision, baseSpec = surface.content.spec, isChangedElsewhere = false)
                    }
                }

                is EditorIntent.SetEnabled -> setEnabled(intent.isEnabled)

                EditorIntent.Delete -> delete()

                EditorIntent.LoadPreview -> running?.model?.store?.intent(SurfaceIntent.Reload())

                EditorIntent.Reload -> pipeline.sendTo(machine, ServiceUiIntent.Public.Reload)

                EditorIntent.DismissNotice -> pipeline.updateState { copy(notice = null) }
            }
        }

        /** Applies [change] and hands its draft text to the check. */
        private suspend fun update(change: EditorState.() -> EditorState) {
            var text = ""
            pipeline.updateState { change().also { text = it.text } }
            drafts.value = text
        }

        private suspend fun save() {
            val known = edited.value ?: return
            var draft: EditorState? = null
            pipeline.updateState {
                val isSavable = phase == EditorPhase.Ready && isChecked && isValid && isDirty && !isSaving &&
                    !isChangedElsewhere
                draft = takeIf { isSavable }
                if (isSavable) copy(isSaving = true, notice = null) else this
            }
            val saving = draft ?: return
            log.i { "User saves interface ${id.value} over revision ${saving.base}" }
            written {
                val content = formatUiSpec(saving.text, isIndented = false)?.let { spec ->
                    try {
                        known.surface.content.copy(spec = spec)
                    } catch (e: IllegalArgumentException) {
                        log.w(e) { "Draft of interface ${id.value} breaks a rule of its content" }
                        null
                    }
                }
                when {
                    // The surface moved past the draft's base after it was taken: its title or placement may differ.
                    known.surface.revision != saving.base -> WriteNoticeUi.Changed

                    content == null -> WriteNoticeUi.Failed

                    else -> machine.submit(write(UiWriteOp.Put(content, saving.base, author = null, clock.now())))
                        .notice()
                }
            }
        }

        private suspend fun setEnabled(isEnabled: Boolean) {
            val known = edited.value ?: return
            if (known.surface.isEnabled == isEnabled || !startWrite()) return
            log.i { "User ${if (isEnabled) "enables" else "disables"} interface ${id.value} in its editor" }
            written {
                machine.submit(write(UiWriteOp.SetEnabled(id, isEnabled, known.surface.revision, clock.now())))
                    .notice()
            }
        }

        private suspend fun delete() {
            val known = edited.value ?: return
            if (!startWrite()) return
            log.i { "User deletes interface ${id.value}" }
            written {
                val outcome = machine.submit(write(UiWriteOp.Delete(id, known.surface.revision)))
                if (outcome is WriteOutcome.Applied) pipeline.action(EditorAction.Deleted)
                outcome.notice()
            }
        }

        /**
         * Runs the started write [block] beside intent processing, so typing goes on while it is saved; the write ends
         * with the notice it returns, or without one when it fails unexpectedly.
         */
        private fun written(block: suspend () -> WriteNoticeUi?) {
            pipeline.launch {
                var notice: WriteNoticeUi? = null
                try {
                    notice = block()
                } finally {
                    withContext(NonCancellable) { pipeline.updateState { copy(isSaving = false, notice = notice) } }
                }
            }
        }

        /** Marks a write of this screen as running; false while another one runs. */
        private suspend fun startWrite(): Boolean {
            var isStarted = false
            pipeline.updateState {
                isStarted = !isSaving
                if (isStarted) copy(isSaving = true, notice = null) else this
            }
            return isStarted
        }
    }

    private fun write(op: UiWriteOp) = UiWrite("user.${Uuid.random().toHexString()}", UiWriteOrigin.User, op)

    /** Runs the preview of [previewed] unless it runs already, closing the previous one; returns its key. */
    private fun keep(previewed: Previewed?): String? {
        if (running?.previewed != previewed) {
            running?.scope?.cancel()
            running = previewed?.let { start(it) }
            log.v { "Preview of interface ${id.value} ${if (running == null) "closed" else "started"}" }
            models.value = running?.let { persistentMapOf(it.key to it.model) } ?: persistentMapOf()
        }
        return running?.key
    }

    private fun start(previewed: Previewed): Running? {
        val child = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val owner = previewed.owner
        val session = SurfaceSession(
            spec = previewed.spec,
            owner = owner,
            context = UiCallContext(preselected = mapOf(owner.kind to setOf(owner.id))),
            args = JsonObject(emptyMap()),
            facts = UiFacts(platform, hasSession = false),
            host = PreviewHost,
            isCard = true,
        )
        return try {
            Running(previewed, surfaceModels.create(session, child), child, "preview-${++previews}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e.redactedFailure()) { "Preview of interface ${id.value} could not start" }
            child.cancel()
            null
        }
    }

    /** The preview has no chat: prompts and other surfaces are not available there. */
    private object PreviewHost : SurfaceHost {
        override suspend fun prompt(text: String, isSubmitted: Boolean): Boolean = false
        override suspend fun openPanel(name: String, args: JsonObject): Boolean = false
        override suspend fun openSurface(name: String, args: JsonObject): Boolean = false
    }
}
