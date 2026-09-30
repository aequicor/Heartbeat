package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthRevision
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSourceId
import io.aequicor.heartbeat.feature.aiengine.authenticator.api.AuthSources
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSessionState
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFacade
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineUsageEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProviderUsageSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Profile-owned telemetry; it never creates a conversation to discover an account quota. */
@SingleIn(ProfileScope::class)
@Inject
internal class EngineStudioUsage(
    private val facade: EngineFacade,
    private val toggles: FeatureToggles,
    private val sources: AuthSources,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) {
    private val log = Log.tag("StudioUsage")
    private val targets = MutableStateFlow(emptySet<String>())
    private val mutableState = MutableStateFlow(StudioUsageState())
    val state = mutableState.asStateFlow()
    private val handles = mutableMapOf<String, ActiveSession>()
    private val jobs = mutableMapOf<String, Job>()
    private val enabled = toggles.observe(EngineUsageEnabled)
        .stateIn(profile.coroutineScope, SharingStarted.Eagerly, false)

    init {
        profile.coroutineScope.launch {
            providerTargets()
                .distinctUntilChanged()
                .collectLatest { ids ->
                    log.d { "Replace observed usage routes" }
                    mutableState.update { it.copy(providers = emptyMap()) }
                    coroutineScope {
                        ids.forEach { observed ->
                            val id = observed.modelId
                            val target = studioModelTarget(id) ?: return@forEach
                            launch {
                                launch { refresh(id) }
                                facade.providerUsage.observe(target.engine, target.binding).collect { snapshot ->
                                    mutableState.update { current ->
                                        current.copy(providers = current.providers + (id to snapshot))
                                    }
                                }
                            }
                        }
                    }
                }
        }
    }

    private fun providerTargets() = combine(
        targets,
        enabled,
        facade.bindings.state,
        sources.state,
        facade.engines.state,
    ) { ids, isEnabled, bindings, auth, engines ->
        if (!isEnabled) {
            emptyList()
        } else {
            ids.mapNotNull { id ->
                val target = studioModelTarget(id) ?: return@mapNotNull null
                val binding = bindings.firstOrNull {
                    it.id == target.binding && it.engine == target.engine && it.isEnabled
                } ?: return@mapNotNull null
                if (engines.none { it.descriptor.id == target.engine }) return@mapNotNull null
                val source = auth.firstOrNull { it.info.id == binding.authSource } ?: return@mapNotNull null
                ObservedUsageTarget(id, source.info.id, source.info.revision)
            }
        }
    }

    fun observe(modelIds: Set<String>) {
        log.d { "Observe usage for ${modelIds.size} model routes" }
        targets.value = modelIds
    }

    suspend fun refresh(modelId: String) {
        if (!toggles.get(EngineUsageEnabled)) return
        val target = studioModelTarget(modelId) ?: return
        log.d { "Refresh provider usage engine=${target.engine.value}" }
        try {
            facade.providerUsage.refresh(target.engine, target.binding)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The catalog retains its last confirmed snapshot, marked stale, for this exact account only.
            log.w(e) { "Provider usage refresh failed" }
        }
    }

    /** Exactly one collector per handle, independent of the turn and screen observation lifetimes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun attach(id: String, active: ActiveSession) {
        log.d { "Attach context observation" }
        if (handles[id] === active) return
        jobs.remove(id)?.cancel()
        handles[id] = active
        mutableState.update { it.copy(contexts = it.contexts - id) }
        jobs[id] = profile.coroutineScope.launch {
            try {
                combine(active.state, allowed(active)) { session, isAllowed -> session to isAllowed }
                    .transformWhile { value ->
                        emit(value)
                        value.first != ActiveSessionState.Closed
                    }
                    .collectLatest { (session, isAllowed) ->
                        val access = contextAccess(active, session, isAllowed)
                        if (access == null) {
                            publishContext(id, active, null)
                        } else {
                            access.feature.state.collect { publishContext(id, active, it) }
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "Context usage observation failed" }
            } finally {
                if (handles[id] === active) {
                    handles.remove(id)
                    jobs.remove(id)
                    mutableState.update { it.copy(contexts = it.contexts - id) }
                }
            }
        }
    }
    private fun contextAccess(
        active: ActiveSession,
        session: ActiveSessionState,
        isAllowed: Boolean,
    ): FeatureAccess.Available<SessionContextUsage>? {
        val isOpen = session !is ActiveSessionState.Closing && session !is ActiveSessionState.Unavailable &&
            session != ActiveSessionState.Closed
        return if (isAllowed && isOpen) {
            active.features.resolve(SessionContextUsage) as? FeatureAccess.Available
        } else {
            null
        }
    }

    private fun allowed(active: ActiveSession) = combine(
        enabled,
        facade.bindings.state,
        sources.state,
        facade.engines.state,
    ) { isEnabled, bindings, auth, engines ->
        val route = active.route
        isEnabled && engines.any { it.descriptor.id == route.engine } &&
            bindings.any {
                it.id == route.binding && it.engine == route.engine && it.isEnabled &&
                    it.authSource == route.authSource
            } && auth.any { it.info.id == route.authSource && it.info.revision == route.revision }
    }

    private fun publishContext(id: String, active: ActiveSession, usage: ContextUsage?) {
        if (handles[id] !== active) return
        log.d { "Update context observation" }
        mutableState.update {
            it.copy(contexts = if (usage == null) it.contexts - id else it.contexts + (id to usage))
        }
    }
}

internal data class StudioUsageState(
    val contexts: Map<String, ContextUsage> = emptyMap(),
    val providers: Map<String, ProviderUsageSnapshot> = emptyMap(),
)

private data class ObservedUsageTarget(val modelId: String, val source: AuthSourceId, val revision: AuthRevision)
