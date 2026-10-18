package io.aequicor.heartbeat.feature.harness.api.script

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.HarnessName
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.api.github.ScriptGitHub
import io.aequicor.heartbeat.feature.harness.api.github.UnavailableScriptGitHub
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowDefinition
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowRegistration
import kotlinx.coroutines.CoroutineScope

/** Script ABI revision included in compiled artifact cache keys; change it when this public contract changes. */
public const val HARNESS_API_VERSION: Int = 12

/**
 * Host-issued services of one approved item revision. Its scope, subscriptions and registrations belong to that
 * activation and are revoked on unload. Implementations enforce activation, quotas and callback restrictions on
 * every call; retaining this object cannot extend its authority. This interface performs no IO itself.
 */
public interface HarnessScriptScope {
    /** Owning harness; script code cannot replace its identity. */
    public val harness: HarnessId

    /** Immutable slug used by namespaced tools and custom events. */
    public val name: HarnessName

    /** Script item whose approved source created this activation. */
    public val item: ItemId

    /** Approved harness revision captured when this activation was created. */
    public val revision: Long

    /** Activation-owned coroutine scope; scripts use this instead of top-level suspend or detached jobs. */
    public val scope: CoroutineScope

    /** Bounded asynchronous event subscriptions. */
    public val events: ScriptEvents

    /** Constraints and extra context for opted-in sessions. */
    public val hooks: ScriptHooks

    /** Session access filtered by current harness activation. */
    public val sessions: ScriptSessions

    /** Owned timers, wakes and namespaced events. */
    public val scheduler: ScriptScheduler

    /** Tool and instruction contributions. */
    public val agent: ScriptAgent

    /** Rendering of this harness's enabled templates. */
    public val prompts: ScriptPrompts

    /** Starts workflows using the script's approved authority. */
    public val workflows: ScriptWorkflows

    /** Durable private JSON shared by this harness's approved scripts. */
    public val data: ScriptData

    /** Durable development processes of this harness, stored in [data]'s namespace. */
    public val processes: ScriptProcesses

    /** Recoverable GitHub reads and writes, gated by the host and a separate feature toggle. */
    public val github: ScriptGitHub get() = UnavailableScriptGitHub

    /** Methods for this harness's interfaces, called by user actions. */
    public val ui: ScriptUi

    /** Fixed local commands declared in this approved source. */
    public val commands: ScriptCommands
}

/**
 * Constructor-injected script base; evaluation registers callbacks, while suspend work uses [script]'s scope.
 * DSL members are public because K2 script templates cannot resolve protected base members.
 */
// Kotlin scripting templates must be inherited by generated script classes, never instantiated directly.
@Suppress("AbstractClassCanBeConcreteClass")
public abstract class HarnessScriptBase(public val script: HarnessScriptScope) {
    /** Activation-owned events. */
    public val events: ScriptEvents get() = script.events

    /** Activation-owned hooks. */
    public val hooks: ScriptHooks get() = script.hooks

    /** Activation-filtered sessions. */
    public val sessions: ScriptSessions get() = script.sessions

    /** Activation-owned scheduling. */
    public val scheduler: ScriptScheduler get() = script.scheduler

    /** Approved agent contributions. */
    public val agent: ScriptAgent get() = script.agent

    /** Enabled prompt templates. */
    public val prompts: ScriptPrompts get() = script.prompts

    /** Approved workflow starts. */
    public val workflows: ScriptWorkflows get() = script.workflows

    /** Durable private JSON of this harness. */
    public val data: ScriptData get() = script.data

    /** Durable development processes of this harness. */
    public val processes: ScriptProcesses get() = script.processes

    /** Recoverable GitHub operations of this activation. */
    public val github: ScriptGitHub get() = script.github

    /** Methods for this harness's interfaces. */
    public val ui: ScriptUi get() = script.ui

    /** Fixed local commands of this script. */
    public val commands: ScriptCommands get() = script.commands
}

/** Workflow evaluation only registers its definition; execution and replay belong to the workflow driver. */
// Kotlin scripting templates must be inherited by generated workflow classes, never instantiated directly.
@Suppress("AbstractClassCanBeConcreteClass")
public abstract class HarnessWorkflowBase(private val registration: WorkflowRegistration) {
    /** Registers exactly one definition without invoking its body during script evaluation. */
    public fun workflow(definition: WorkflowDefinition): Unit = registration.register(definition)
}

/** Idempotently removes an activation-owned registration; disposal never proves native work has stopped. */
public fun interface ScriptRegistration {
    /** Stops future callbacks or contributions; already owned work follows its host's cleanup barrier. */
    public fun dispose()
}
