package io.aequicor.heartbeat.feature.aistudio.impl.presentation.store

import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioFileContent
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioFileLinks
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import pro.respawn.flowmvi.api.MVIAction
import pro.respawn.flowmvi.api.MVIIntent
import pro.respawn.flowmvi.api.MVIState
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.plugins.reduce
import pro.respawn.flowmvi.plugins.whileSubscribed
import kotlin.time.Duration.Companion.seconds

/** A file link to show: [path] resolved, or null when the link could not be resolved and only [requested] is known. */
data class StudioFileRequest(val path: String?, val requested: String, val line: Int? = null)

/** What the preview shows of a file. */
sealed interface StudioFileView {
    /** The file text; [modifiedAt] tells the next poll whether it changed. */
    data class Text(val text: String, val modifiedAt: Long) : StudioFileView {
        override fun toString(): String = "StudioFileView.Text(length=${text.length})"
    }

    /** Larger than the preview reads. */
    data object TooLarge : StudioFileView

    /** Not text. */
    data object Binary : StudioFileView

    /** Missing, a folder, unreadable or not resolvable. */
    data object Unavailable : StudioFileView
}

/**
 * The side file preview of a studio window. [opening] numbers each opening and is null while closed; [content] is
 * null while loading. [isSource] shows Markdown as text, which a link to a line does from the start.
 */
@Immutable
data class StudioFilePreviewState(
    val opening: Long? = null,
    val path: String? = null,
    val requested: String = "",
    val line: Int? = null,
    val content: StudioFileView? = null,
    val isSource: Boolean = false,
) : MVIState {
    /** The file name shown in the header. */
    val name: String get() = (path ?: requested).trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

    /** Markdown files are rendered unless [isSource]. */
    val isMarkdown: Boolean get() = MarkdownExtensions.any { name.endsWith(it, ignoreCase = true) }
}

/** Input of the file preview. */
sealed interface StudioFilePreviewIntent : MVIIntent {
    /** Shows [request], replacing the file shown now. */
    data class Open(val request: StudioFileRequest) : StudioFilePreviewIntent

    /** A link inside the shown file: relative paths resolve against that file's folder. */
    data class OpenLinked(val path: String, val line: Int?) : StudioFilePreviewIntent

    /** Reads the file again. */
    data object Reload : StudioFilePreviewIntent

    /** Switches a Markdown file between rendered and source text. */
    data object ToggleSource : StudioFilePreviewIntent

    /** Hides the preview. */
    data object Close : StudioFilePreviewIntent
}

/** The preview has no one-off actions. */
sealed interface StudioFilePreviewAction : MVIAction

private typealias FilePipeline =
    PipelineContext<StudioFilePreviewState, StudioFilePreviewIntent, StudioFilePreviewAction>

/**
 * Store of the side file preview: reads the file on opening and on demand, and while the preview is watched reads it
 * again when its modification time changes, so a plan being written shows its latest revision.
 */
@SingleIn(AiStudioScope::class)
@Inject
class StudioFilePreviewModel(
    factory: HeartbeatStoreFactory,
    private val files: StudioFileLinks,
    @ForScope(AiStudioScope::class) scope: ScopeHandle,
) {
    private val log = Log.tag("StudioFilePreview")
    private var openings = 0L
    private val shown = MutableStateFlow<Long?>(null)

    /** The opening shown now, null while closed; the side panel chooses the preview by it. */
    val opening: StateFlow<Long?> = shown.asStateFlow()

    val store = factory.create<StudioFilePreviewState, StudioFilePreviewIntent, StudioFilePreviewAction>(
        name = "StudioFilePreview",
        initial = StudioFilePreviewState(),
        onError = { this },
    ) {
        install {
            name = "opening"
            onState { _, next ->
                next.also {
                    if (shown.value != it.opening) log.v { "File preview opening changed open=${it.opening != null}" }
                    shown.value = it.opening
                }
            }
        }
        reduce { intent -> handle(this, intent) }
        whileSubscribed(name = "file-changes") { watchChanges(this) }
    }

    init {
        store.start(scope.coroutineScope)
    }

    private suspend fun handle(pipeline: FilePipeline, intent: StudioFilePreviewIntent) = with(pipeline) {
        when (intent) {
            is StudioFilePreviewIntent.Open -> open(this, intent.request)

            is StudioFilePreviewIntent.OpenLinked -> {
                var shown: String? = null
                withState { shown = path }
                val directory = parentOf(shown)
                val resolved = if (directory != null) files.resolveIn(intent.path, directory) else null
                open(this, StudioFileRequest(resolved, intent.path, intent.line))
            }

            StudioFilePreviewIntent.Reload -> {
                log.i { "Reload file preview" }
                load(this)
            }

            StudioFilePreviewIntent.ToggleSource -> updateState { copy(isSource = !isSource) }

            StudioFilePreviewIntent.Close -> {
                log.i { "Close file preview" }
                updateState { StudioFilePreviewState() }
            }
        }
    }

    private suspend fun open(pipeline: FilePipeline, request: StudioFileRequest) = with(pipeline) {
        val opening = ++openings
        log.i { "Open file preview resolved=${request.path != null} line=${request.line != null}" }
        updateState {
            StudioFilePreviewState(
                opening = opening,
                path = request.path,
                requested = request.requested,
                line = request.line,
                content = if (request.path == null) StudioFileView.Unavailable else null,
                isSource = request.line != null,
            )
        }
        load(this)
    }

    /** Reads the shown file; a result for a file replaced meanwhile is dropped. */
    private suspend fun load(pipeline: FilePipeline) = with(pipeline) {
        var path: String? = null
        withState { path = this.path }
        val target = path ?: return@with
        val content = files.read(target).toView()
        log.d { "File preview read ${content::class.simpleName.orEmpty()}" }
        updateState { if (this.path == target) copy(content = content) else this }
    }

    private suspend fun watchChanges(pipeline: FilePipeline) = with(pipeline) {
        while (currentCoroutineContext().isActive) {
            delay(POLL_INTERVAL)
            var shown: Pair<String, Long>? = null
            withState {
                val text = content as? StudioFileView.Text
                shown = if (path != null && text != null) path to text.modifiedAt else null
            }
            val (path, modifiedAt) = shown ?: continue
            val current = files.modifiedAt(path)
            log.v { "File preview poll changed=${current != modifiedAt}" }
            if (current != null && current != modifiedAt) load(this)
        }
    }

    private companion object {
        val POLL_INTERVAL = 2.seconds
    }
}

private fun StudioFileContent.toView(): StudioFileView = when (this) {
    is StudioFileContent.Text -> StudioFileView.Text(text, modifiedAt)
    is StudioFileContent.TooLarge -> StudioFileView.TooLarge
    StudioFileContent.Binary -> StudioFileView.Binary
    StudioFileContent.Unavailable -> StudioFileView.Unavailable
}

private val MarkdownExtensions = listOf(".md", ".markdown", ".mdx")

/** The folder of an absolute path, in either separator style. */
private fun parentOf(path: String?): String? = if (path == null) {
    null
} else {
    path.substring(0, maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\')).coerceAtLeast(0)).takeIf { it.isNotEmpty() }
}
