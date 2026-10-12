package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.store

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodFailure
import io.aequicor.heartbeat.feature.serviceasui.api.UiMethodResult
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiMethodCalls
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiPlatform
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiAction
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiLoad
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.spec.UiPresentation
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiBindings
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiEvaluator
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiScope
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiSourceStatus
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.UiValues
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.NativeWidgetUi
import io.aequicor.heartbeat.feature.serviceasui.impl.presentation.model.UiPress
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed

private typealias SurfacePipeline = PipelineContext<SurfaceState, SurfaceIntent, SurfaceAction>

/**
 * One drawn surface: loads its automatic data sources once the owner's methods are available, keeps local state
 * typed by inputs and runs actions. Calls run beside intent processing, at most [CALLS_PER_SURFACE] at a time, and
 * are cancelled with the store. Every change re-evaluates the spec, so the screen only draws resolved models.
 */
internal class SurfaceModel(
    private val session: SurfaceSession,
    private val calls: UiMethodCalls,
    private val platform: UiPlatform,
    factory: HeartbeatStoreFactory,
    scope: CoroutineScope,
) {
    private val log = Log.tag("ServiceUiSurface")
    private val evaluator = UiEvaluator(session.spec)
    private val calling = Semaphore(CALLS_PER_SURFACE)

    val store: Store<SurfaceState, SurfaceIntent, SurfaceAction> =
        factory.create<SurfaceState, SurfaceIntent, SurfaceAction>(
            name = "Surface",
            initial = rendered(SurfaceState(values = initialValues())),
            onError = { copy(notice = NoticeUi.Broken) },
        ) {
            whileSubscribed { Work(this).observeMethods() }
            reduce { intent -> Work(this).handle(intent) }
        }

    init {
        store.start(scope)
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
        )
    }

    /** Work of one intent or subscription on the store [pipeline]. */
    private inner class Work(private val pipeline: SurfacePipeline) {
        suspend fun observeMethods() {
            val owner = session.owner ?: return
            calls.methods(owner).collect { methods ->
                var isStarting = false
                pipeline.updateState {
                    isStarting = methods.isAvailable && !hasStarted
                    copy(isMethodsAvailable = methods.isAvailable, hasStarted = hasStarted || methods.isAvailable)
                }
                if (isStarting) {
                    val sources = session.spec.data.filterValues { it.load == UiLoad.Auto }.keys
                    sources.forEach { pipeline.launch { load(it) } }
                }
            }
        }

        suspend fun handle(intent: SurfaceIntent) {
            when (intent) {
                is SurfaceIntent.Press -> {
                    val type = intent.press.action::class.simpleName ?: "UiAction"
                    log.i { "Surface action $type from ${intent.press.origin}" }
                    pipeline.launch { press(intent.press) }
                }

                is SurfaceIntent.Input -> update { it.withInput(intent.key, intent.value) }

                is SurfaceIntent.OpenLink -> {
                    log.i { "Surface link clicked" }
                    openUrl(intent.url, isTrusted = false)
                }

                is SurfaceIntent.Pick -> pipeline.launch { pick(intent) }

                is SurfaceIntent.Reload -> {
                    val sources = intent.source?.let { listOf(it) } ?: session.spec.data.keys.toList()
                    sources.forEach { pipeline.launch { load(it) } }
                }

                SurfaceIntent.DismissModal -> closeModal()

                SurfaceIntent.DismissNotice -> pipeline.updateState { copy(notice = null) }
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
            val bindings = bindings()
            return when (action) {
                is UiAction.Call -> call(action, bindings.arguments(action.args, scope))

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
                    val isSubmitted = action.isSubmitted && session.owner != null
                    session.host.prompt(bindings.text(action.text, scope), isSubmitted) || notice(NoticeUi.Unsupported)
                }

                is UiAction.OpenUrl -> openUrl(bindings.text(action.url, scope), isTrusted = session.owner != null)

                is UiAction.Copy -> {
                    pipeline.action(SurfaceAction.Copy(bindings.text(action.text, scope)))
                    true
                }

                is UiAction.SaveFile -> platformResult(
                    platform.hasFiles && platform.save(
                        bindings.text(action.text, scope),
                        action.name,
                        action.extensions,
                    ),
                )

                is UiAction.Reveal -> platformResult(
                    session.owner != null && platform.hasFiles && platform.reveal(bindings.text(action.path, scope)),
                )

                is UiAction.Steps -> action.actions.all { run(it, scope) }
            }
        }

        private suspend fun call(action: UiAction.Call, arguments: JsonObject): Boolean {
            val owner = session.owner ?: return notice(NoticeUi.Unsupported)
            val result = calling.withPermit { calls.call(owner, action.method, arguments, session.context) }
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

                surface != null -> {
                    pipeline.action(SurfaceAction.OpenSurface(surface, arguments))
                    true
                }

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
            val owner = session.owner ?: return false
            val source = session.spec.data[name] ?: return false
            update { it.copy(status = it.status + (name to UiSourceStatus.Loading)) }
            val arguments = bindings().arguments(source.args, UiScope())
            val result = calling.withPermit { calls.call(owner, source.method, arguments, session.context) }
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
            if (!platform.hasFiles) {
                notice(NoticeUi.Unsupported)
                return
            }
            val picked = when (intent.widget) {
                NativeWidgetUi.File -> platform.pickFile(intent.extensions)
                NativeWidgetUi.Folder -> platform.pickFolder()
                NativeWidgetUi.Date -> null
            } ?: return
            update { it.withInput(intent.key, JsonPrimitive(picked)) }
        }

        private suspend fun closeModal() {
            pipeline.updateState { rendered(copy(modals = modals.dropLast(1).toImmutableList())) }
        }

        private suspend fun reported(result: UiMethodResult): Boolean = when (result) {
            is UiMethodResult.Success -> true
            is UiMethodResult.UserError -> notice(NoticeUi.Message(result.message))
            is UiMethodResult.Failure -> notice(NoticeUi.Failed(result.reason.ui()))
        }

        private suspend fun platformResult(isDone: Boolean): Boolean = isDone || notice(NoticeUi.Unsupported)

        /** Shows [notice]; false, so a sequence stops. */
        private suspend fun notice(notice: NoticeUi): Boolean {
            pipeline.updateState { copy(notice = notice) }
            return false
        }

        private suspend fun update(change: (UiValues) -> UiValues) {
            pipeline.updateState { rendered(copy(values = change(values))) }
        }

        private suspend fun bindings(): UiBindings {
            var current = UiValues()
            pipeline.withState { current = values }
            return evaluator.bindings(current)
        }
    }
}

/** Method calls of one surface running at the same time; more wait for a free slot. */
internal const val CALLS_PER_SURFACE: Int = 2

private fun UiValues.withInput(key: String, value: JsonElement): UiValues = copy(state = state + (key to value))

private fun UiBindings.arguments(arguments: JsonObject, scope: UiScope): JsonObject =
    json(arguments, scope) as? JsonObject ?: JsonObject(emptyMap())

private fun UiMethodFailure.ui(): FailureUi = when (this) {
    UiMethodFailure.Unavailable -> FailureUi.Unavailable
    UiMethodFailure.NotFound -> FailureUi.NotFound
    UiMethodFailure.Inactive -> FailureUi.Inactive
    UiMethodFailure.TimedOut -> FailureUi.TimedOut
    UiMethodFailure.Failed -> FailureUi.Failed
    UiMethodFailure.TooLarge -> FailureUi.TooLarge
}
