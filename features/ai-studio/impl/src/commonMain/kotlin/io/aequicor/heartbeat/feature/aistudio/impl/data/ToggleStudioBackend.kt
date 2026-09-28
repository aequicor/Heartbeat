package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioEffect
import io.aequicor.heartbeat.feature.aistudio.api.AiStudioIntent
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.di.scope.AiStudioScope
import io.aequicor.heartbeat.feature.aistudio.impl.domain.AiStudioEffects
import io.aequicor.heartbeat.feature.aistudio.impl.domain.EngineStudioEffects
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioAvailability
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioBackend
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRepository
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Reads [StudioEngineRuntime] once per feature scope and keeps that backend until the studio is left, so one
 * screen never mixes engine chats with the demo workspace. The unused backend is never created.
 */
@SingleIn(AiStudioScope::class)
@ContributesBinding(AiStudioScope::class)
@Inject
internal class ToggleStudioBackend(
    private val toggles: FeatureToggles,
    private val engineRepository: Lazy<StudioRepository>,
    private val engineRuntime: Lazy<StudioRuntime>,
    private val demoRepository: Lazy<InMemoryStudioRepository>,
    private val availability: StudioAvailability,
    private val clock: Clock,
) : StudioBackend {
    private val log = Log.tag("StudioBackend")
    private val lock = Mutex()
    private var selected: Selected? = null

    override suspend fun repository(): StudioRepository = select().repository

    override suspend fun effects(): EffectHandler<AiStudioEffect, AiStudioIntent> = select().effects

    private suspend fun select(): Selected = lock.withLock {
        selected ?: create().also { selected = it }
    }

    private suspend fun create(): Selected = if (toggles.get(StudioEngineRuntime)) {
        log.i { "Studio uses the engine runtime" }
        val repository = engineRepository.value
        Selected(repository, EngineStudioEffects(repository, engineRuntime.value, availability))
    } else {
        log.i { "Studio uses the demo workspace" }
        val repository = demoRepository.value
        Selected(repository, AiStudioEffects(repository, ScriptedStudioAgent(), availability, clock))
    }

    private class Selected(
        val repository: StudioRepository,
        val effects: EffectHandler<AiStudioEffect, AiStudioIntent>,
    )
}
