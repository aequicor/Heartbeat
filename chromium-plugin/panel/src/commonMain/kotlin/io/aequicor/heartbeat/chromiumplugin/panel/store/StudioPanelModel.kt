package io.aequicor.heartbeat.chromiumplugin.panel.store

import io.aequicor.heartbeat.chromiumplugin.panel.link.PanelHost
import io.aequicor.heartbeat.chromiumplugin.panel.link.PanelLink
import io.aequicor.heartbeat.chromiumplugin.panel.link.panelJson
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.NativeAttachmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.attachmentSupport
import io.aequicor.heartbeat.feature.aistudio.screenmodel.isAttachmentPending
import io.aequicor.heartbeat.feature.chromiumbridge.protocol.WirePage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.dsl.collect
import pro.respawn.flowmvi.plugins.init
import pro.respawn.flowmvi.plugins.reduce
import kotlin.uuid.Uuid

private typealias PanelPipeline = PipelineContext<StudioPanelState, StudioPanelIntent, StudioPanelAction>
private typealias StudioStore = Store<RemoteStudioState, AiStudioScreenIntent, RemoteStudioAction>

/**
 * Owns one remote view at a time and the explicit browser capture gestures. Reset cancels the old store before
 * opening a new view id; only draft text can be recovered by an explicit user action, never old commands.
 */
internal class StudioPanelModel(
    private val factory: HeartbeatStoreFactory,
    private val link: PanelLink,
    private val host: PanelHost,
) {
    private val log = Log.tag("Panel/StudioHost")
    private val viewIds = MutableStateFlow(Uuid.random().toString())
    private val selections = Channel<Unit>(Channel.CONFLATED)
    private var current: StudioStore? = null
    private var captureJob: Job? = null
    private var captureVersion = 0L
    private var queuedInputs = 0L

    val store = factory.create<StudioPanelState, StudioPanelIntent, StudioPanelAction>(
        "ChromiumStudioHost",
        StudioPanelState(),
        onError = { this },
    ) {
        init {
            val pipeline = this
            launch { followViews(pipeline) }
            launch {
                link.status.collect { linkState ->
                    updateState { copy(isConnected = linkState.status == LinkStatus.Ready.wire) }
                }
            }
            launch { followSelections(pipeline) }
        }
        reduce { input -> handle(this, input) }
    }

    private suspend fun followViews(pipeline: PanelPipeline) {
        viewIds.collectLatest { id ->
            coroutineScope {
                val view = remoteStudioStore(factory, link, id)
                current = view
                var previousRecovery: AiStudioScreenState? = null
                view.start(this)
                try {
                    view.collect {
                        states.collect { remote ->
                            val lost = remote.interruptedScreen
                            val draft = lost?.takeIf { it !== previousRecovery }?.focusedDraft()
                            previousRecovery = lost
                            pipeline.updateState {
                                if (viewIds.value == id) {
                                    copy(
                                        remote = remote,
                                        recoveryDraft = draft ?: recoveryDraft,
                                    )
                                } else {
                                    this
                                }
                            }
                        }
                    }
                } finally {
                    if (current === view) current = null
                }
            }
        }
    }

    private suspend fun followSelections(pipeline: PanelPipeline) {
        host.onSelection { selections.trySend(Unit) }
        selections.trySend(Unit)
        try {
            for (ignored in selections) {
                val page = readPage(isSelection = true, pipeline) ?: continue
                // The context menu may open the panel before there is a view; keep it for explicit attachment.
                captureVersion++
                captureJob?.cancel()
                pipeline.updateState { copy(capturedPage = page, captureProblem = null, isCapturing = false) }
            }
        } finally {
            host.onSelection { }
        }
    }

    private suspend fun handle(pipeline: PanelPipeline, input: StudioPanelIntent) = with(pipeline) {
        when (input) {
            is StudioPanelIntent.Screen -> withState { if (canInteract()) send(input.input) }
            is StudioPanelIntent.CapturePage -> capture(pipeline, input.paneId)
            StudioPanelIntent.AttachCaptured -> attachCaptured(pipeline)
            StudioPanelIntent.DismissCapture -> updateState { copy(capturedPage = null, captureProblem = null) }
            StudioPanelIntent.NewView -> reset(pipeline)
            StudioPanelIntent.RecoverDraft -> recover(pipeline)
            StudioPanelIntent.DismissRecovery -> updateState { copy(recoveryDraft = null) }
        }
    }

    private suspend fun capture(pipeline: PanelPipeline, paneId: Int) = with(pipeline) {
        var destination: CaptureDestination? = null
        withState {
            val screen = remote.screen ?: return@withState
            if (canInteract() && !isCapturing && remote.settledInputs == queuedInputs) {
                destination = CaptureDestination(
                    viewIds.value,
                    remote.generation,
                    paneId,
                    screen.draftKey(paneId),
                    queuedInputs,
                )
            }
        }
        val target = destination ?: return@with
        updateState { copy(isCapturing = true, captureProblem = null) }
        log.i { "Page capture requested" }
        val attempt = ++captureVersion
        captureJob = launch {
            try {
                val page = readPage(isSelection = false, pipeline) ?: return@launch
                updateState { copy(capturedPage = page) }
                finishCapture(pipeline, target)
            } finally {
                withContext(NonCancellable) {
                    updateState { if (captureVersion == attempt) copy(isCapturing = false) else this }
                }
            }
        }
    }

    private suspend fun finishCapture(pipeline: PanelPipeline, target: CaptureDestination) = with(pipeline) {
        withState {
            if (canInteract() && target.matches(viewIds.value, remote, queuedInputs)) {
                attachCaptured(pipeline, target.paneId)
            } else {
                updateState { copy(captureProblem = CaptureProblem.DestinationChanged) }
            }
        }
    }

    private fun StudioPanelState.canInteract(): Boolean =
        isInteractive && remote.connectionEpoch == link.connectionEpoch

    private fun send(input: AiStudioScreenIntent) {
        val view = current ?: return
        queuedInputs++
        view.intent(input)
    }

    private suspend fun attachCaptured(pipeline: PanelPipeline, paneId: Int? = null) = with(pipeline) {
        withState {
            val page = capturedPage ?: return@withState
            val screen = remote.screen ?: return@withState
            if (!canInteract()) return@withState
            if (remote.settledInputs != queuedInputs) {
                updateState { copy(captureProblem = CaptureProblem.DestinationChanged) }
                return@withState
            }
            val target = paneId ?: screen.focusedPaneId
            if (!screen.isAttachmentsEnabled || screen.isAttachmentPending(target) ||
                "text/markdown" !in screen.attachmentSupport(target)?.mediaTypes.orEmpty()
            ) {
                updateState { copy(captureProblem = CaptureProblem.Unsupported) }
                return@withState
            }
            val input = NativeAttachmentUi.Page(page.url, page.title, page.text, page.selection)
            send(AiStudioScreenIntent.ImportAttachments(target, listOf(input)))
            log.i { "Captured page queued for attachment" }
            updateState { copy(capturedPage = null, captureProblem = null) }
        }
    }

    private suspend fun readPage(isSelection: Boolean, pipeline: PanelPipeline): WirePage? = try {
        val json = if (isSelection) host.takeSelection() else host.capturePage()
        if (json == null) {
            if (!isSelection) pipeline.updateState { copy(captureProblem = CaptureProblem.Unreadable) }
            null
        } else {
            panelJson.decodeFromString(WirePage.serializer(), json).also { require(it.isValid()) { "Invalid page" } }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "Page capture failed" }
        pipeline.updateState { copy(captureProblem = CaptureProblem.Unreadable) }
        null
    }

    private suspend fun reset(pipeline: PanelPipeline) = with(pipeline) {
        withState {
            if (remote.status != StudioViewStatus.TooLarge) return@withState
            captureVersion++
            captureJob?.cancel()
            queuedInputs = 0
            viewIds.value = Uuid.random().toString()
            updateState {
                copy(
                    remote = RemoteStudioState(),
                    isCapturing = false,
                    recoveryDraft = remote.screen?.focusedDraft() ?: recoveryDraft,
                )
            }
            log.i { "Oversized view replaced" }
        }
    }

    private suspend fun recover(pipeline: PanelPipeline) = with(pipeline) {
        withState {
            val draft = recoveryDraft ?: return@withState
            val screen = remote.screen ?: return@withState
            if (!canInteract()) return@withState
            val target = screen.focusedPaneId
            val existing = screen.draft(target)
            val recovered = listOf(existing, draft).filter(String::isNotBlank).joinToString("\n\n")
            send(AiStudioScreenIntent.Suggestions.DraftChanged(target, recovered))
            updateState { copy(recoveryDraft = null) }
            log.i { "Previous draft restored by the user" }
        }
    }
}

private fun AiStudioScreenState.focusedDraft(): String? = draft(focusedPaneId).takeIf { it.isNotBlank() }

/** A capture may finish after navigation, reset, or replacement of the app's view. */
private data class CaptureDestination(
    val view: String,
    val generation: String?,
    val paneId: Int,
    val draftKey: String,
    val inputs: Long,
) {
    fun matches(viewId: String, remote: RemoteStudioState, queued: Long): Boolean =
        view == viewId && generation == remote.generation && remote.screen?.draftKey(paneId) == draftKey &&
            inputs == queued && remote.settledInputs == queued
}
