package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store

import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.feature.browser.api.BrowserBridgeResult
import io.aequicor.heartbeat.feature.browser.api.BrowserDocumentBridge
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodFailure
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodResult
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiMethodCalls
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiPlatform
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiPlatformResult
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiWebDocuments
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiAction
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiLoad
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiPresentation
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiSpecLimits
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiBindings
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiEvaluator
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiFacts
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiScope
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiSourceStatus
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiValues
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.NativeWidgetUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiPress
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed

private typealias SurfacePipeline = PipelineContext<SurfaceState, SurfaceIntent, SurfaceAction>

/**
 * One drawn surface: loads automatic sources when methods become available and durable owner data changes, keeps state
 * typed by inputs and runs actions. Calls run beside intent processing, at most [UiSpecLimits.CALLS] at a time, and
 * are cancelled with the store. Every change re-evaluates the spec, so the screen only draws resolved models.
 */
@AssistedInject
internal class SurfaceModel(
    @Assisted session: SurfaceSession,
    private val calls: UiMethodCalls,
    private val platform: UiPlatform,
    factory: HeartbeatStoreFactory,
    @Assisted scope: CoroutineScope,
    web: UiWebDocuments,
) {
    /** Creates the store of one drawn surface in its own [scope]. */
    @AssistedFactory
    fun interface Factory {
        fun create(session: SurfaceSession, scope: CoroutineScope): SurfaceModel
    }

    private val executions = MutableStateFlow(SurfaceExecution(session, scope))
    private val session: SurfaceSession get() = executions.value.session
    private val parentScope = scope
    private var observedExecution: SurfaceExecution? = null
    private val log = Log.tag("ServiceUiSurface")
    private val evaluator = UiEvaluator(session.spec)
    private val calling = Semaphore(UiSpecLimits.CALLS)
    private val sourceLoads = session.spec.data.mapValues { Mutex() }
    private val callableOwner = session.owner.takeUnless { session.isPreview }

    // Pages call on their own; their own permit keeps the user's buttons and data sources free.
    private val pageCalling = Semaphore(1)

    // Only approved live library surfaces get a bridge, including cards; previews never execute owner code.
    private val documents = SurfaceWebDocuments(
        web,
        callableOwner?.let(::PageCalls),
        session.title,
    )

    /** Route ids of the browser documents drawn for `web` nodes, by node key. */
    val webDocuments: StateFlow<ImmutableMap<String, String>> = documents.shown

    private val pipelineStore: Store<SurfaceState, SurfaceIntent, SurfaceAction> =
        factory.create<SurfaceState, SurfaceIntent, SurfaceAction>(
            name = "Surface",
            initial = rendered(SurfaceState(values = initialValues(), inputBinding = executions.value.inputBinding)),
            onError = { copy(notice = NoticeUi.Broken) },
        ) {
            install {
                name = "web-documents"
                onStart {
                    withState { documents.show(this) }
                    launch { web.configuration.collect(documents::configure) }
                }
                onState { _, next -> next.also(documents::show) }
                onStop { documents.close() }
            }
            whileSubscribed { Work(this).observeMethods() }
            reduce { intent ->
                val bound = intent as? SurfaceIntent.Bound ?: return@reduce
                val execution = executions.value
                if (bound.binding === execution.inputBinding &&
                    (execution.isActive || bound.input == SurfaceIntent.BindingChanged)
                ) {
                    Work(this, execution).handle(bound.input)
                }
            }
        }

    val store: Store<SurfaceState, SurfaceIntent, SurfaceAction> =
        SurfaceInputStore(pipelineStore) { executions.value.inputBinding }

    init {
        store.start(scope)
    }

    /** Stops old context work immediately while a start app waits for its first chat's owner admission. */
    fun pause() {
        executions.value.cancel()
        store.intent(SurfaceIntent.BindingChanged)
    }

    /**
     * Rebinds only a host-validated continuation of the same app. The document/DOM and local inputs survive;
     * old page calls, actions and source results are revoked before new-context calls may start.
     */
    fun rebind(context: UiCallContext, host: SurfaceHost, facts: UiFacts) {
        val previous = executions.value
        previous.cancel()
        log.v { "Surface authority rebound; previous work cancelled" }
        executions.value = SurfaceExecution(
            previous.session.copy(context = context, host = host, facts = facts),
            parentScope,
        )
    }

    /** Calls of a page into the methods of [owner]: JSON object arguments (or none), bounded with the surface's own. */
    private inner class PageCalls(private val owner: UiOwner) : BrowserDocumentBridge {
        override fun capture(): BrowserDocumentBridge {
            val execution = executions.value
            return BrowserDocumentBridge { method, arguments -> call(execution, method, arguments) }
        }

        override suspend fun call(method: String, arguments: JsonElement): BrowserBridgeResult =
            call(executions.value, method, arguments)

        private suspend fun call(
            execution: SurfaceExecution,
            method: String,
            arguments: JsonElement,
        ): BrowserBridgeResult {
            val objectArguments = when (arguments) {
                is JsonObject -> arguments
                JsonNull -> JsonObject(emptyMap())
                is JsonPrimitive, is JsonArray -> return BrowserBridgeResult.Failure("Arguments must be a JSON object")
            }
            val result = execution.execute {
                pageCalling.withPermit {
                    if (execution.isActive) {
                        calls.callFromPage(
                            owner,
                            method,
                            objectArguments,
                            execution.session.context,
                        )
                    } else {
                        UiMethodResult.Failure(UiMethodFailure.Inactive)
                    }
                }
            } ?: return BrowserBridgeResult.Failure("This app context is no longer active")
            return when (result) {
                is UiMethodResult.Success -> BrowserBridgeResult.Value(result.value)
                is UiMethodResult.UserError -> BrowserBridgeResult.Failure(result.message)
                is UiMethodResult.Failure -> BrowserBridgeResult.Failure(result.reason.pageMessage())
            }
        }
    }

    private fun initialValues() = UiValues(
        state = session.spec.state,
        args = session.args,
        status = session.spec.data.mapValues { UiSourceStatus.Idle },
        facts = session.facts,
    )

    private fun rendered(state: SurfaceState): SurfaceState {
        val top = state.modals.lastOrNull()
        return state.copy(
            root = evaluator.body(state.values),
            modal = top?.let { evaluator.modal(it.name, state.values.copy(args = it.args)) },
            quickActions = evaluator.quickActions(state.values),
        )
    }

    /** Work of one intent or subscription on the store [pipeline]. */
    private inner class Work(
        private val pipeline: SurfacePipeline,
        private val execution: SurfaceExecution = executions.value,
    ) {
        private val session: SurfaceSession get() = execution.session
        private val isCurrent: Boolean get() = executions.value === execution && execution.isActive

        suspend fun observeMethods() {
            executions.collectLatest { current -> Work(pipeline, current).observeCurrentMethods() }
        }

        private suspend fun observeCurrentMethods() {
            resetBinding()
            val owner = callableOwner ?: return
            combine(calls.methods(owner), calls.dataRevision(owner)) { methods, revision -> methods to revision }
                .collect { (methods, revision) ->
                    var isLoadNeeded = false
                    pipeline.updateState {
                        if (!isCurrent) return@updateState this
                        isLoadNeeded = methods.isAvailable && (
                            !hasStarted || loadedRevision != revision || loadedMethodsGeneration != methods.generation
                        )
                        copy(isMethodsAvailable = methods.isAvailable)
                    }
                    if (isLoadNeeded) {
                        // Wait for this snapshot before accepting the next revision. StateFlow conflates rapid writes;
                        // each source is serialized with manual reloads so an old result cannot overtake a fresh one.
                        coroutineScope {
                            session.spec.data.filterValues { it.load == UiLoad.Auto }.keys.forEach { name ->
                                launch { load(name) }
                            }
                        }
                        pipeline.updateState {
                            if (!isCurrent) return@updateState this
                            copy(
                                hasStarted = true,
                                loadedRevision = revision,
                                loadedMethodsGeneration = methods.generation,
                            )
                        }
                    }
                }
        }

        private suspend fun resetBinding() {
            if (observedExecution !== execution) {
                observedExecution = execution
                pipeline.updateState {
                    if (!isCurrent) return@updateState this
                    rendered(
                        copy(
                            isMethodsAvailable = false,
                            hasStarted = false,
                            loadedRevision = null,
                            loadedMethodsGeneration = null,
                            inputBinding = execution.inputBinding,
                            values = values.copy(facts = session.facts, busy = emptySet()),
                        ),
                    )
                }
            }
        }

        suspend fun handle(intent: SurfaceIntent) {
            when (intent) {
                is SurfaceIntent.Bound -> error("Surface input binding must be checked before handling")

                SurfaceIntent.BindingChanged -> pipeline.updateState {
                    if (executions.value.isActive) this else copy(isMethodsAvailable = false)
                }

                is SurfaceIntent.Press -> {
                    val type = intent.press.action::class.simpleName ?: "UiAction"
                    log.i { "Surface action $type" }
                    pipeline.launch { execution.execute { press(intent.press) } }
                }

                is SurfaceIntent.RunQuickAction -> runQuickAction(intent.id)

                is SurfaceIntent.Input -> update { it.withInput(intent.key, intent.value) }

                is SurfaceIntent.OpenLink -> {
                    log.i { "Surface link clicked" }
                    openUrl(intent.url, isTrusted = false)
                }

                is SurfaceIntent.Pick -> pipeline.launch { execution.execute { pick(intent) } }

                is SurfaceIntent.Reload -> reload(intent.source)

                SurfaceIntent.DismissModal -> closeModal()

                SurfaceIntent.DismissNotice -> pipeline.updateState { copy(notice = null) }
            }
        }

        private fun reload(source: String?) {
            val sources = source?.let { listOf(it) } ?: session.spec.data.keys.toList()
            sources.forEach { pipeline.launch { execution.execute { load(it) } } }
        }

        /**
         * Runs the quick action [id] of the current state. Its busy key is taken before the work starts, in the same
         * state update that checked it, so a second press queued behind this one finds the action busy and does
         * nothing; the key is released when the work ends, even if the execution was revoked meanwhile.
         */
        private suspend fun runQuickAction(id: String) {
            var claimed: UiPress? = null
            pipeline.updateState {
                val action = quickActions.firstOrNull { it.id == id && it.isEnabled && !it.isBusy }
                if (!isCurrent || action == null) {
                    this
                } else {
                    claimed = action.press
                    rendered(copy(values = values.copy(busy = values.busy + action.press.origin)))
                }
            }
            val press = claimed ?: return log.d { "Quick action ignored: hidden, disabled or already running" }
            log.i { "Surface quick action ${press.action::class.simpleName ?: "UiAction"}" }
            pipeline.launch {
                try {
                    execution.execute { run(press.action, press.scope) }
                } finally {
                    withContext(NonCancellable) { update { it.copy(busy = it.busy - press.origin) } }
                }
            }
        }

        private suspend fun press(press: UiPress) {
            update { it.copy(busy = it.busy + press.origin) }
            try {
                run(press.action, press.scope)
            } finally {
                withContext(NonCancellable) { update { it.copy(busy = it.busy - press.origin) } }
            }
        }

        /** Runs [action] with the item it was pressed on; false stops a sequence. */
        @Suppress("CyclomaticComplexMethod") // One branch per action type keeps the catalog in one readable place.
        private suspend fun run(action: UiAction, scope: UiScope): Boolean {
            if (!isCurrent) return false
            val bindings = bindings()
            return when (action) {
                is UiAction.Call -> call(action, bindings.arguments(action.args, scope))

                is UiAction.FocusCard -> {
                    if (callableOwner == null) {
                        notice(NoticeUi.Unsupported)
                    } else {
                        session.host.focusCard(action.surface) || notice(NoticeUi.Unsupported)
                    }
                }

                is UiAction.Reload -> load(action.source)

                is UiAction.SetState -> {
                    val value = bindings.json(action.value, scope)
                    update { it.withInput(action.key, value) }
                    true
                }

                is UiAction.Open -> open(action, bindings.arguments(action.args, scope))

                UiAction.Close -> {
                    closeModal()
                    true
                }

                is UiAction.Prompt -> {
                    val isSubmitted = action.isSubmitted && callableOwner != null && !session.isCard
                    session.host.prompt(bindings.text(action.text, scope), isSubmitted) || notice(NoticeUi.Unsupported)
                }

                is UiAction.OpenUrl -> openUrl(bindings.text(action.url, scope), isTrusted = session.owner != null)

                is UiAction.Copy -> {
                    pipeline.action(SurfaceAction.Copy(bindings.text(action.text, scope)))
                    true
                }

                is UiAction.SaveFile ->
                    finished(platform.save(bindings.text(action.text, scope), action.name, action.extensions))

                is UiAction.Reveal -> if (callableOwner == null) {
                    notice(NoticeUi.Unsupported)
                } else {
                    finished(platform.reveal(bindings.text(action.path, scope)))
                }

                is UiAction.Steps -> action.actions.all { run(it, scope) }
            }
        }

        private suspend fun call(action: UiAction.Call, arguments: JsonObject): Boolean {
            val owner = callableOwner ?: return notice(NoticeUi.Unsupported)
            val result = execution.execute {
                calling.withPermit {
                    if (isCurrent) {
                        calls.call(owner, action.method, arguments, session.context)
                    } else {
                        UiMethodResult.Failure(UiMethodFailure.Inactive)
                    }
                }
            } ?: return false
            if (!isCurrent) return false
            val into = action.into
            if (result is UiMethodResult.Success && into != null) update { it.withInput(into, result.value) }
            return reported(result)
        }

        private suspend fun open(action: UiAction.Open, arguments: JsonObject): Boolean {
            val modal = action.modal
            val surface = action.surface
            return when {
                modal != null -> {
                    pipeline.updateState {
                        rendered(
                            copy(modals = (modals + OpenModal(modal, arguments)).toImmutableList()),
                        )
                    }
                    true
                }

                surface != null && action.presentation == UiPresentation.Panel ->
                    session.host.openPanel(surface, arguments) || notice(NoticeUi.Unsupported)

                surface != null -> session.host.openSurface(surface, arguments) || notice(NoticeUi.Unsupported)

                else -> false
            }
        }

        /** Opens an http(s) [url], after the user confirms it unless [isTrusted]. */
        private suspend fun openUrl(url: String, isTrusted: Boolean): Boolean {
            val isLink = url.startsWith("https://") || url.startsWith("http://")
            if (isLink) {
                pipeline.action(SurfaceAction.OpenUrl(url, isTrusted))
            } else {
                notice(NoticeUi.InvalidLink)
            }
            return isLink
        }

        /** Runs the data source [name] and stores its value and status. */
        private suspend fun load(name: String): Boolean {
            if (callableOwner == null) return false
            val lock = sourceLoads[name] ?: return false
            return lock.withLock { loadSource(name) }
        }

        private suspend fun loadSource(name: String): Boolean {
            val owner = checkNotNull(callableOwner)
            val source = session.spec.data.getValue(name)
            update { it.copy(status = it.status + (name to UiSourceStatus.Loading)) }
            val arguments = bindings().arguments(source.args, UiScope())
            val result = execution.execute {
                calling.withPermit {
                    if (isCurrent) {
                        calls.call(owner, source.method, arguments, session.context)
                    } else {
                        UiMethodResult.Failure(UiMethodFailure.Inactive)
                    }
                }
            }
            return if (result == null || !isCurrent) false else loaded(name, result)
        }

        private suspend fun loaded(name: String, result: UiMethodResult): Boolean {
            update { values ->
                val status = when (result) {
                    is UiMethodResult.Success -> UiSourceStatus.Ready
                    is UiMethodResult.UserError -> UiSourceStatus.Failed(result.message)
                    is UiMethodResult.Failure -> UiSourceStatus.Failed(result.reason.name)
                }
                val data = if (result is UiMethodResult.Success) values.data + (name to result.value) else values.data
                values.copy(data = data, status = values.status + (name to status))
            }
            return reported(result)
        }

        private suspend fun pick(intent: SurfaceIntent.Pick) {
            val result = when (intent.widget) {
                NativeWidgetUi.File -> platform.pick(isFolder = false, intent.extensions)
                NativeWidgetUi.Folder -> platform.pick(isFolder = true, emptyList())
                NativeWidgetUi.Date -> return
            }
            val path = (result as? UiPlatformResult.Done)?.path
            if (path == null) finished(result) else update { it.withInput(intent.key, JsonPrimitive(path)) }
        }

        private suspend fun closeModal() {
            pipeline.updateState { rendered(copy(modals = modals.dropLast(1).toImmutableList())) }
        }

        private suspend fun reported(result: UiMethodResult): Boolean = when (result) {
            is UiMethodResult.Success -> true
            is UiMethodResult.UserError -> notice(NoticeUi.Message(result.message))
            is UiMethodResult.Failure -> notice(NoticeUi.Failed(result.reason.ui()))
        }

        /** Whether [result] is done; a closed dialog stops quietly, other endings show why. */
        private suspend fun finished(result: UiPlatformResult): Boolean = when (result) {
            is UiPlatformResult.Done -> true
            UiPlatformResult.Cancelled -> false
            UiPlatformResult.Unsupported -> notice(NoticeUi.Unsupported)
            UiPlatformResult.Failed -> notice(NoticeUi.FileFailed)
        }

        /** Shows [notice]; false, so a sequence stops. */
        private suspend fun notice(notice: NoticeUi): Boolean {
            pipeline.updateState { if (isCurrent) copy(notice = notice) else this }
            return false
        }

        private suspend fun update(change: (UiValues) -> UiValues) {
            pipeline.updateState { if (isCurrent) rendered(copy(values = change(values))) else this }
        }

        private suspend fun bindings(): UiBindings {
            var current = UiValues()
            pipeline.withState { current = values }
            return evaluator.bindings(current)
        }
    }
}

private fun UiValues.withInput(key: String, value: JsonElement): UiValues = copy(state = state + (key to value))

private fun UiBindings.arguments(arguments: JsonObject, scope: UiScope): JsonObject =
    json(arguments, scope) as? JsonObject ?: JsonObject(emptyMap())

/** Why a page's call failed, in the English of page scripts. */
private fun UiMethodFailure.pageMessage(): String = when (this) {
    UiMethodFailure.Unavailable -> "Methods are unavailable here"
    UiMethodFailure.NotFound -> "No such method"
    UiMethodFailure.Inactive -> "The owner is not active here"
    UiMethodFailure.TimedOut -> "The call timed out"
    UiMethodFailure.Failed -> "The call failed"
    UiMethodFailure.TooLarge -> "The arguments or the result are too large"
}

private fun UiMethodFailure.ui(): FailureUi = when (this) {
    UiMethodFailure.Unavailable -> FailureUi.Unavailable
    UiMethodFailure.NotFound -> FailureUi.NotFound
    UiMethodFailure.Inactive -> FailureUi.Inactive
    UiMethodFailure.TimedOut -> FailureUi.TimedOut
    UiMethodFailure.Failed -> FailureUi.Failed
    UiMethodFailure.TooLarge -> FailureUi.TooLarge
}
