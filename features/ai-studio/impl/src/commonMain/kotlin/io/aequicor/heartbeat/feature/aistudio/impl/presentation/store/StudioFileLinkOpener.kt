package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioFileLinks
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioScreenHost
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.PaneUi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** File links of the studio's chats: whether this window previews them, and the requests for its side preview. */
@SingleIn(AiStudioScope::class)
@Inject
class StudioFileLinkOpener(private val fileLinks: StudioFileLinks) {
    private val log = Log.tag("StudioFileLinks")
    private val requests = MutableSharedFlow<StudioFileRequest>(extraBufferCapacity = 8)

    /** File links to show, executed by the lifecycle component. */
    val navigation: SharedFlow<StudioFileRequest> = requests.asSharedFlow()

    /** Only a native window previews files; a remote view keeps them inert. */
    internal suspend fun observe(pipeline: StudioPipeline, host: StudioScreenHost) {
        fileLinks.availability.collect { isAvailable ->
            pipeline.updateState { copy(isFilePreviewEnabled = isAvailable && host == StudioScreenHost.Window) }
        }
    }

    /** Resolves a link against the pane's chat; an unresolved link still opens to say it cannot be shown. */
    internal suspend fun open(pipeline: StudioPipeline, intent: AiStudioScreenIntent.OpenFileLink) {
        var pane: PaneUi? = null
        pipeline.withState { if (isFilePreviewEnabled) pane = panes.firstOrNull { it.id == intent.paneId } }
        val target = pane ?: return
        val path = fileLinks.resolve(intent.path, target.sessionId, target.projectId)
        log.i { "Open chat file link resolved=${path != null}" }
        requests.emit(StudioFileRequest(path, intent.path, intent.line))
    }
}

/** Opens what a pane shows beside its chat: an app viewer or a file preview. */
internal suspend fun openBeside(
    pipeline: StudioPipeline,
    intent: AiStudioScreenIntent,
    surfaces: StudioSurfaceBinding,
    files: StudioFileLinkOpener,
) {
    if (intent is AiStudioScreenIntent.OpenApp) surfaces.openApp(pipeline, intent)
    if (intent is AiStudioScreenIntent.OpenFileLink) files.open(pipeline, intent)
}
