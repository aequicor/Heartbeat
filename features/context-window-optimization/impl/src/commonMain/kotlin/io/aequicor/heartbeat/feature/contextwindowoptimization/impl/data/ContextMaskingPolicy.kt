package io.aequicor.heartbeat.feature.contextwindowoptimization.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationEnabled
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationState
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationView
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.KoogMaskingEnabled

/**
 * Masking of older tool results in rebuilt prompts, from the profile settings: the newest turns stay verbatim and the
 * rest keep a short beginning. Masking advances [MASK_STEP_TURNS] turns at a time for prompt cache stability.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class ContextMaskingPolicy(private val view: ContextOptimizationView, private val toggles: FeatureToggles) :
    ContextPolicyProvider {
    override suspend fun policy(session: SessionRef): ContextPolicy? {
        if (!toggles.get(ContextOptimizationEnabled) || !toggles.get(KoogMaskingEnabled)) return null
        val settings = (view.state.value as? ContextOptimizationState.Ready)?.settings ?: return null
        return ContextPolicy(settings.koogKeepRecentTurns, settings.koogToolStubChars, MASK_STEP_TURNS)
    }

    private companion object {
        const val MASK_STEP_TURNS = 2
    }
}
