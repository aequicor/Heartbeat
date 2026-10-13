package io.aequicor.heartbeat.feature.aistudio.impl.di

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeFactory
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteOpen
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemotePage
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteRefusal
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteReply
import io.aequicor.heartbeat.feature.aistudio.api.StudioRemoteScreens
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioScreenHost
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.remote.RemoteStudioView
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.NativeAttachmentUi
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioViewJson
import io.aequicor.heartbeat.feature.aistudio.screenmodel.remote.StudioViewSchema
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Remote studio views of the profile. Each view gets its own studio graph in a child scope of the profile
 * ([StudioScreenHost.Remote]: its machine is not addressable), lives while a host collects its frames and a grace
 * period after, and closes with the profile or when remote views become unavailable. Views are created, used
 * and closed on the main thread, like scopes.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
@Inject
internal class RemoteStudioViews(
    private val toggles: FeatureToggles,
    private val scopes: ScopeFactory,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val graphs: AiStudioGraph.Factory,
    private val dispatchers: DispatcherProvider,
) : StudioRemoteScreens {
    private val log = Log.tag("StudioRemoteViews")
    private val views = mutableMapOf<String, RemoteStudioView>()
    private val graces = mutableMapOf<String, Job>()

    /** How long a detached view waits for its host. */
    internal var grace: Duration = DEFAULT_GRACE

    /** Shortest time between two frames of a view. */
    internal var frameInterval: Duration = DEFAULT_FRAME_INTERVAL

    override val availability: Flow<Boolean> = toggles.observe(StudioEngineRuntime).distinctUntilChanged()

    init {
        profile.coroutineScope.launch(dispatchers.main) {
            availability.collect { isAvailable -> if (!isAvailable) closeAll("remote views are off") }
        }
    }

    override suspend fun open(viewId: String, schema: String): StudioRemoteOpen = withContext(dispatchers.main) {
        val refusal = when {
            !toggles.get(StudioEngineRuntime) -> StudioRemoteRefusal.Unavailable
            schema != StudioViewSchema.fingerprint -> StudioRemoteRefusal.Incompatible
            viewId !in views && views.size >= MAX_VIEWS -> StudioRemoteRefusal.Busy
            else -> null
        }
        if (refusal != null) {
            log.i { "view refused: $refusal" }
            return@withContext StudioRemoteOpen.Refused(refusal)
        }
        graces.remove(viewId)?.cancel()
        val view = views.getOrPut(viewId) { create(viewId) }
        // A host that never collects the frames must not keep the view: it waits like a detached one.
        detached(view)
        StudioRemoteOpen.Opened(view.frames(onDetached = { detached(view) }))
    }

    override suspend fun send(
        viewId: String,
        generation: String,
        intents: List<StudioRemoteIntent>,
    ): StudioRemoteReply = withContext(dispatchers.main) {
        views[viewId]?.apply(generation, intents) ?: StudioRemoteReply.UnknownView
    }

    override suspend fun attachPage(
        viewId: String,
        generation: String,
        seq: Long,
        paneId: Int,
        page: StudioRemotePage,
    ): StudioRemoteReply {
        val input = NativeAttachmentUi.Page(page.url, page.title, page.text, page.selection)
        val intent = AiStudioScreenIntent.ImportAttachments(paneId, listOf(input))
        val encoded = StudioViewJson.encodeToJsonElement(AiStudioScreenIntent.serializer(), intent)
        return send(viewId, generation, listOf(StudioRemoteIntent(seq, encoded)))
    }

    private fun create(viewId: String): RemoteStudioView {
        val scope = scopes.child(profile, "aistudio-remote-${views.size + graces.size}")
        val graph = graphs.createAiStudio(scope, StudioScreenHost.Remote)
        log.i { "view opened (views: ${views.size + 1})" }
        return RemoteStudioView(viewId, scope::close, graph.model, dispatchers.default, frameInterval)
    }

    /** A host stopped collecting: the view waits the grace period for it, then closes. */
    private fun detached(view: RemoteStudioView) {
        profile.coroutineScope.launch(dispatchers.main) {
            if (views[view.id] !== view || view.isAttached) return@launch
            graces.remove(view.id)?.cancel()
            graces[view.id] = profile.coroutineScope.launch(dispatchers.main) {
                delay(grace)
                if (views[view.id] === view && !view.isAttached) {
                    views.remove(view.id)
                    graces.remove(view.id)
                    view.close()
                }
            }
        }
    }

    private fun closeAll(reason: String) {
        if (views.isEmpty()) return
        log.i { "close ${views.size} views: $reason" }
        graces.values.forEach { it.cancel() }
        graces.clear()
        views.values.forEach { it.close() }
        views.clear()
    }

    private companion object {
        const val MAX_VIEWS = 4
        val DEFAULT_GRACE: Duration = 30.seconds
        val DEFAULT_FRAME_INTERVAL: Duration = 100.milliseconds
    }
}
