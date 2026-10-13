package io.aequicor.heartbeat.feature.serviceasui.impl.presentation.card

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.core.mvi.HeartbeatStoreFactory
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiCardRoute
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.UiCallContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostContext
import io.aequicor.heartbeat.feature.serviceasui.api.UiHostId
import io.aequicor.heartbeat.feature.serviceasui.api.UiOwner
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceId
import io.aequicor.heartbeat.feature.serviceasui.api.UiSurfaceName
import io.aequicor.heartbeat.feature.serviceasui.impl.di.scope.ServiceUiCardScope
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.ServiceUiMachine
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCard
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardSource
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiCardStorage
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.JsonObject
import pro.respawn.flowmvi.api.PipelineContext
import pro.respawn.flowmvi.api.Store
import pro.respawn.flowmvi.plugins.whileSubscribed

private typealias CardPipeline = PipelineContext<CardState, CardIntent, CardAction>

/**
 * One card the agent showed in a response: an inline spec without an owner, or a library surface drawn with the
 * card's arguments. A card loads no data by itself and only inserts prompts into the composer of the place it is
 * drawn in, while that place shows its chat; an evicted card, a gone or disabled surface or a switched-off feature
 * shows a placeholder. The drawn surface restarts when its spec, owner, place or arguments change.
 */
@SingleIn(ServiceUiCardScope::class)
@Inject
internal class CardModel(
    route: ServiceUiCardRoute,
    private val cards: UiCardStorage,
    private val machine: ServiceUiMachine,
    private val surfaceModels: SurfaceModel.Factory,
    platformInfo: PlatformInfo,
    factory: HeartbeatStoreFactory,
    @ForScope(ServiceUiCardScope::class) handle: ScopeHandle,
) {
    private val log = Log.tag("ServiceUiCard")
    private val id = route.id
    private val place = route.host?.let(::placeOf)
    private val scope = handle.coroutineScope
    private val platform = platformFact(platformInfo.host)
    private val models = MutableStateFlow<ImmutableMap<String, SurfaceModel>>(persistentMapOf())
    private var running: Running? = null
    private var unreadable: DrawnCard? = null
    private var drawings = 0L

    /** The store of the drawn surface by [CardState.key]. */
    val surfaces: StateFlow<ImmutableMap<String, SurfaceModel>> = models.asStateFlow()

    val store: Store<CardState, CardIntent, CardAction> = factory.create<CardState, CardIntent, CardAction>(
        name = "ServiceUiCard",
        initial = CardState(),
        onError = { this },
    ) {
        whileSubscribed { Work(this).observe() }
    }

    init {
        store.start(scope)
    }

    /** What the card draws: [spec] of [owner] for the place [host] bound to its chat, with [args]. */
    private data class DrawnCard(
        val title: String,
        val spec: String,
        val owner: UiOwner?,
        val host: UiHostId?,
        val context: UiHostContext,
        val args: JsonObject,
    ) {
        override fun toString(): String = "DrawnCard(owner=${owner?.id ?: "none"}, host=${host?.value ?: "none"})"
    }

    private data class Running(
        val drawn: DrawnCard,
        val model: SurfaceModel,
        val scope: CoroutineScope,
        val key: String,
    )

    /** Work of the subscription on the store [pipeline]. */
    private inner class Work(private val pipeline: CardPipeline) {
        suspend fun observe() {
            combine(cards.observe(id), machine.state) { card, state -> card to state }
                .collect { (card, state) -> show(card, state) }
        }

        private suspend fun show(card: UiCard?, state: ServiceUiState) {
            val drawn = card?.let { drawn(it, state) }
            keep(drawn)
            val key = running?.key
            val shown = when {
                card == null -> CardState(CardPhase.Missing)
                state is ServiceUiState.Loading -> CardState(CardPhase.Loading, card.title)
                drawn == null || key == null -> CardState(CardPhase.Unavailable, card.title)
                else -> CardState(CardPhase.Drawn, card.title, key)
            }
            pipeline.updateState { shown }
        }
    }

    /** What [card] draws in [state], or null while the feature is off or its library surface is not available. */
    private fun drawn(card: UiCard, state: ServiceUiState): DrawnCard? {
        val ready = state as? ServiceUiState.Ready ?: return null
        // Only the place the card is drawn in, and only while it shows the card's chat, takes its prompts and panels.
        val bound = place?.takeIf { ready.hosts[it]?.call?.session == card.chat }
        val context = bound?.let { ready.hosts[it] } ?: UiHostContext(UiCallContext(session = card.chat))
        val (spec, owner) = when (val source = card.source) {
            is UiCardSource.Inline -> source.spec to null

            is UiCardSource.Surface -> ready.library[source.surface]?.takeIf { it.isEnabled }
                ?.let { it.content.spec to it.content.owner }
                ?: return null
        }
        return DrawnCard(card.title, spec, owner, bound, context, card.args)
    }

    private fun placeOf(host: String): UiHostId? = try {
        UiHostId(host)
    } catch (e: IllegalArgumentException) {
        log.w(e) { "Card $id has an invalid place" }
        null
    }

    /** Starts the store of [drawn] unless it runs already, closing the previous one; an unreadable spec once. */
    private fun keep(drawn: DrawnCard?) {
        if (running?.drawn == drawn || (drawn != null && drawn == unreadable)) return
        running?.scope?.cancel()
        running = drawn?.let { start(it) }
        if (drawn != null && running == null) unreadable = drawn
        log.d { "Card $id surface ${if (running == null) "closed" else "started"}" }
        models.value = running?.let { persistentMapOf(it.key to it.model) } ?: persistentMapOf()
    }

    private fun start(drawn: DrawnCard): Running? {
        val spec = (decodeUiSpec(drawn.spec) as? UiSpecDecoding.Decoded)?.spec
        if (spec == null) {
            log.w { "Card $id has an unreadable spec" }
            return null
        }
        val child = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        val session = SurfaceSession(
            spec = spec,
            owner = drawn.owner,
            context = drawn.context.call,
            args = drawn.args,
            facts = UiFacts(platform, hasSession = true),
            host = CardHost(drawn),
            isCard = true,
        )
        return try {
            Running(drawn, surfaceModels.create(session, child), child, "card-${++drawings}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.e(e.redactedFailure()) { "Card $id could not start" }
            child.cancel()
            null
        }
    }

    /** Prompts go into the composer of the place bound to the card's chat; panels open in its dock. */
    private inner class CardHost(private val drawn: DrawnCard) : SurfaceHost {
        override suspend fun prompt(text: String, isSubmitted: Boolean): Boolean {
            val host = drawn.host ?: return false
            val request = ServiceUiIntent.Internal.RequestPrompt(
                host,
                text,
                isSubmitted = false,
                expectedContext = drawn.context,
            )
            return machine.send(request) == SendResult.Accepted
        }

        override suspend fun openPanel(name: String, args: JsonObject): Boolean {
            val host = drawn.host
            val owner = drawn.owner
            if (host == null || owner == null || drawn.context.dock == null) return false
            val surface = try {
                UiSurfaceId.of(owner, UiSurfaceName(name))
            } catch (e: IllegalArgumentException) {
                log.w(e) { "Surface name of card $id is invalid" }
                return false
            }
            val request = ServiceUiIntent.Public.OpenPanel(host, surface, args, expectedContext = drawn.context)
            return machine.send(request) == SendResult.Accepted
        }

        override suspend fun openSurface(name: String, args: JsonObject): Boolean = false
    }
}
