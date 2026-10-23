package io.aequicor.heartbeat.bundles.studio

import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Includes
import io.aequicor.heartbeat.core.bundles.FoundationProfileBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileEngineBindings
import io.aequicor.heartbeat.core.bundles.FoundationProfileRuntimeBindings
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.navigation.RouteEntry
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextPolicyProvider
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionHook
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolOutputOffload
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.SessionTransferIntent
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.SessionTransferOutput
import io.aequicor.heartbeat.feature.aisessionenginetransfer.api.SessionTransferState
import io.aequicor.heartbeat.feature.autocomplete.api.ComposerAssists
import io.aequicor.heartbeat.feature.checklist.api.ChecklistRemoteScreens
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationView
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.planningmode.api.PlanningView
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireRemoteScreens
import io.aequicor.heartbeat.feature.scheduler.api.spi.ScheduledWakeOwner

/**
 * What the profile graph of the `studio` bundle publishes to the foundation of the same profile. Its profile
 * startups go out through `ProfileStartupAccess`, which the graph implements as a contribution of the profile scope.
 */
public interface StudioProfileExports {
    /** Effort levels the profile offers per model; the studio of the foundation reads them. */
    public val effortChoices: EffortChoicesView

    /** Plans of the profile's chats; the studio of the foundation reads them. */
    public val planningView: PlanningView

    /** Suggestions of the composer (commands, files, skills); the studio of the foundation reads them. */
    public val composerAssists: ComposerAssists

    /** Hand-over of a chat to another engine; the machine starts on first read, so the foundation reads it lazily. */
    public val sessionTransfer: Machine<SessionTransferState, SessionTransferIntent, SessionTransferOutput>

    /** Agent tools of the bundle's features; `engines` merges them into the set its facade assembles. */
    public val agentTools: Set<AgentToolContribution>

    /** Session hooks of the bundle's features; `engines` merges them into the set its facade runs. */
    public val sessionHooks: Set<SessionHook>

    /** Questionnaire screens the studio embeds next to a chat. */
    public val questionnaireScreens: QuestionnaireRemoteScreens

    /** Checklist screens the studio embeds next to a chat. */
    public val checklistScreens: ChecklistRemoteScreens

    /** Admission controllers of the bundle's features; `agent-runtime` merges them into the set its scheduler reads. */
    public val scheduledWakeOwners: Set<ScheduledWakeOwner>

    /** Offloads of long tool output; `engines` merges them into the set its facade assembles. */
    public val toolOutputOffloads: Set<ToolOutputOffload>

    /** Context policies of the bundle's features; `engines` merges them into the set its facade assembles. */
    public val contextPolicyProviders: Set<ContextPolicyProvider>

    /** Navigation entries of the bundle's screens; the foundation merges them into the profile routes. */
    @ForScope(ProfileScope::class)
    public val profileRoutes: Set<RouteEntry<*>>

    /** State of context compaction and rollover; the studio of the foundation reads it. */
    public val contextOptimization: ContextOptimizationView
}

/** Profile-level graph of the `studio` bundle: the profile contributions of its member features. */
@GraphExtension(ProfileScope::class)
internal interface StudioProfileGraph : StudioProfileExports {
    @GraphExtension.Factory
    fun interface Factory {
        fun create(
            @Includes foundation: FoundationProfileBindings,
            @Includes engines: FoundationProfileEngineBindings,
            @Includes runtime: FoundationProfileRuntimeBindings,
        ): StudioProfileGraph
    }
}
