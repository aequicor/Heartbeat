package io.aequicor.heartbeat.feature.aiengine.facade.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionSafetyEnabled
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSecuritySettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.IsolatedPrompts
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolOutputOffload
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.SessionHooks
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.ActionSafetyGate
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.DefaultAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.impl.data.safety.ActionSafetyPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ToolPolicyResolver

/** Profile dispatcher dependencies are explicit; test constructor defaults must not hide missing bindings. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object AgentToolsBindings {
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun actionSafety(
        analyzers: Set<NativeActionAnalysis>,
        prompts: Lazy<IsolatedPrompts>,
        toggles: FeatureToggles,
    ): ActionSafetyGate = ActionSafetyGate(
        NativeActionAnalysis { context, call ->
            val results = analyzers.map { it.analyze(context, call) }
            results.filterIsInstance<ActionAnalysis.Denied>().firstOrNull()
                ?: results.filterIsInstance<ActionAnalysis.Known>().singleOrNull()
                ?: ActionAnalysis.Unknown("Native executor or action has no complete verified contract")
        },
        ActionSafetyPolicy(prompts),
        enabled = { toggles.get(ActionSafetyEnabled) },
    )

    /** Shares the profile workspace registry with route resolution, including virtual sandbox providers. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun tools(
        contributions: Set<AgentToolContribution>,
        hooks: SessionHooks,
        policies: ToolPolicyResolver,
        offloads: Lazy<Set<ToolOutputOffload>>,
        settings: AgentSecuritySettings,
        workspaces: LocalWorkspaces,
        actionSafety: ActionSafetyGate,
    ): ProfileAgentTools = DefaultAgentTools(
        contributions,
        hooks,
        policies,
        offloads,
        workspaces = workspaces,
        settings = settings,
        actionSafety = actionSafety,
    )
}

/** Optional native analyzers supplement the shared policy; mobile platforms have no native process analyzer. */
@ContributesTo(ProfileScope::class)
public interface NativeActionAnalyzerBindings {
    /** Host-owned analyzers only; external tool declarations cannot register an analyzer. */
    @Multibinds(allowEmpty = true)
    public fun nativeActionAnalyzers(): Set<NativeActionAnalysis>
}
