package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Multibinds
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolAction
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolGroup
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.NoSessionHooks
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.SessionHooks
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.NoToolPolicyResolver
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ToolPolicyResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Empty profiles/mobile graphs still have a dispatcher, with no local tools. */
@ContributesTo(ProfileScope::class)
public interface AgentToolBindings {
    /** Contributions may be absent on mobile and in profiles without local engines. */
    @Multibinds(allowEmpty = true)
    public fun agentToolContributions(): Set<AgentToolContribution>
}

/**
 * One authorization boundary for tool calls; adapters never implement a second trust gate for these tools.
 * A session without a project sees only contributions supporting it; the turn barrier still reaches every
 * contribution. Whether such a session gets hosted tools at all is the opt-in of the request that opens it
 * (`CreateSessionRequest.areDetachedToolsEnabled` tells which one decides per adapter): adapters check it before
 * attaching tools, this dispatcher never sees it and answers any request without a workspace.
 */
@Inject
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class DefaultAgentTools(
    private val contributions: Set<AgentToolContribution>,
    private val hooks: SessionHooks = NoSessionHooks,
    private val policies: ToolPolicyResolver = NoToolPolicyResolver,
) : ProfileAgentTools {
    private val log = Log.tag("AgentTools")
    private val nativeGate = NativeToolGate(policies, hooks) {
        catalog().flatMap { it.tools }.map { it.name }.toSet()
    }
    private val callsLock = Mutex()
    private val calls = mutableMapOf<Pair<SessionRef, TurnId>, MutableSet<Job>>()
    private val finishedTurns = mutableSetOf<Pair<SessionRef, TurnId>>()

    // Entries stay after the turn: a delayed call must still resolve to its finished facade turn.
    private val boundTurns = mutableMapOf<Pair<SessionRef, RequestId>, BoundTurn>()

    override suspend fun bindTurn(session: SessionRef, request: RequestId, turn: TurnId, target: EngineTarget?) {
        log.i { "Bind hosted execution to the facade turn" }
        callsLock.withLock {
            val key = session to request
            val previous = boundTurns[key]
            check(previous == null || previous.turn == turn) { "Request already belongs to another turn" }
            boundTurns[key] = BoundTurn(turn, target ?: previous?.target)
        }
    }

    override fun catalog(): List<ToolGroup> {
        val entries = contributions.flatMap { it.catalog }
        check(entries.map { it.name }.distinct().size == entries.size) { "Duplicate hosted catalog name" }
        return contributions.groupBy { it.group }.asSequence().map { (group, owners) ->
            check(owners.map { it.title }.distinct().size == 1) { "Conflicting tool group titles" }
            ToolGroup(group, owners.first().title, owners.flatMap { it.catalog }.sortedBy { it.name })
        }.filter { it.tools.isNotEmpty() }.sortedBy { it.id }.toList()
    }

    override suspend fun nativeTools(scope: ToolPolicyScope): ResolvedToolPolicy = checkNotNull(
        policies.resolve(scope, catalog().flatMap { it.tools }.map { it.name }.toSet(), isExecuting = false),
    )

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        specifications(AgentToolScope(workspace))

    override suspend fun specifications(scope: AgentToolScope): List<AgentToolSpec> =
        declarations(scope).map { it.second }

    override suspend fun instructions(workspace: WorkspaceRef?): String = instructions(AgentToolScope(workspace))

    override suspend fun instructions(scope: AgentToolScope): String {
        val declarations = declarations(scope)
        val allowed = declarations.map { it.second.name }.toSet()
        val scoped = scope.copy(declared = allowed)
        return declarations.mapTo(linkedSetOf()) { it.first }.map { it.instructions(scoped) }.joined()
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult =
        withInvocation(context, AgentToolResult("Native turn has ended", isError = true)) {
            executeAuthorized(it, name, arguments)
        }

    override suspend fun authorizeNative(context: AgentToolContext, call: NativeToolCall): NativeVerdict =
        withInvocation(context, NativeVerdict.Deny("Native turn has ended")) { nativeGate.authorize(it, call) }

    override suspend fun authorizeHosted(
        context: AgentToolContext,
        name: String,
        arguments: JsonObject,
    ): NativeVerdict = withInvocation(context, NativeVerdict.Deny("Native turn has ended")) {
        val result = executeAuthorized(it, name, arguments, isAdapterOperated = true)
        if (result.isError) NativeVerdict.Deny(result.text) else NativeVerdict.Allow
    }

    override suspend fun afterHosted(
        context: AgentToolContext,
        name: String,
        arguments: JsonObject,
        result: AgentToolResult,
    ): String? = withInvocation(context, null) { trusted ->
        // The operation already completed: changing availability must not suppress its result hook.
        val spec = contributions.filter { it.isAdapterOperated }.flatMap { it.catalog }
            .singleOrNull { it.name == name } ?: return@withInvocation null
        val hookContext = hooks.context(trusted.session, trusted.request, trusted.turn) ?: return@withInvocation null
        try {
            hooks.afterTool(HookedToolCall(hookContext, name, spec.action, arguments), result)?.take(HOOK_NOTE_CHARS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(IllegalStateException("Adapter result hook failed (${e::class.simpleName.orEmpty()})")) {
                "Adapter result hook failed"
            }
            null
        }
    }

    private suspend fun <T> withInvocation(
        context: AgentToolContext,
        ended: T,
        block: suspend (AgentToolContext) -> T,
    ): T = coroutineScope {
        val invocation = checkNotNull(currentCoroutineContext()[Job])
        val turnEnded = context.lifetime?.invokeOnCompletion {
            invocation.cancel(CancellationException("Native turn ended"))
        }
        var trusted = context
        try {
            currentCoroutineContext().ensureActive()
            val isRegistered = callsLock.withLock {
                val bound = context.request?.let { boundTurns[context.session to it] }
                trusted = context.copy(
                    turn = bound?.turn ?: context.turn,
                    historyTurn = context.turn,
                    target = bound?.target ?: context.target,
                    authorization = null,
                )
                val key = trusted.session to trusted.turn
                if (key in finishedTurns) false else calls.getOrPut(key) { mutableSetOf() }.add(invocation)
            }
            if (!isRegistered) return@coroutineScope ended
            block(trusted)
        } finally {
            turnEnded?.dispose()
            withContext(NonCancellable) {
                callsLock.withLock {
                    val key = trusted.session to trusted.turn
                    calls[key]?.let { pending ->
                        pending.remove(invocation)
                        if (pending.isEmpty()) calls.remove(key)
                    }
                }
            }
        }
    }

    override suspend fun finishTurn(session: SessionRef, turn: TurnId) {
        log.i { "Revoke hosted tools and await outstanding calls" }
        val pending = callsLock.withLock {
            val key = session to turn
            finishedTurns.add(key)
            calls[key]?.toList().orEmpty()
        }
        pending.forEach { it.cancel() }
        pending.forEach { it.cancelAndJoin() }
        // One owner's failed cleanup must neither skip the others nor fail the turn that already ended.
        contributions.forEach { contribution ->
            try {
                contribution.finishTurn(session, turn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.w(e) { "Hosted tool cleanup failed for the finished turn" }
            }
        }
        hooks.releaseTurn(session, turn)
    }

    private suspend fun executeAuthorized(
        context: AgentToolContext,
        name: String,
        arguments: JsonObject,
        isAdapterOperated: Boolean = false,
    ): AgentToolResult {
        val declaration = declarations(context.toolScope(), isExecuting = true, isAdapterOperated = isAdapterOperated)
            .singleOrNull { it.second.name == name }
            ?: return AgentToolResult("Tool is unavailable for this session", isError = true)
        val (owner, spec) = declaration
        log.i { "Hosted tool requested name=${spec.name} action=${spec.action}" }
        return try {
            val call = hooks.context(context.session, context.request, context.turn)?.let {
                HookedToolCall(it, name, spec.action, arguments)
            }
            val verdict = call?.let { hooks.beforeTool(it) } ?: ToolHookVerdict.Continue
            if (verdict is ToolHookVerdict.Deny) {
                return AgentToolResult(
                    "Blocked by a session hook: ${verdict.reason.take(HOOK_NOTE_CHARS)}",
                    isError = true,
                )
            }
            val approval = owner.approval(context, spec, arguments)
            val refusal = authorizationRefusal(context, owner, spec, arguments, approval, verdict)
            if (refusal != null) {
                refusal
            } else if (isAdapterOperated) {
                currentCoroutineContext().ensureActive()
                // The adapter performs the operation only after this authorization returns successfully.
                AgentToolResult("")
            } else {
                currentCoroutineContext().ensureActive()
                val result = owner.execute(context.copy(authorization = approval), name, arguments)
                log.i { "Hosted tool finished name=${spec.name} failed=${result.isError}" }
                val note = call?.let { hooks.afterTool(it, result) }
                if (note.isNullOrBlank()) {
                    result
                } else {
                    result.copy(
                        text = note.take(HOOK_NOTE_CHARS) + "\n\n" + result.text,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Filesystem/native exceptions may contain paths or environment values.
            log.w(IllegalStateException("Hosted tool failed (${e::class.simpleName.orEmpty()})")) {
                "Hosted tool failed name=${spec.name}"
            }
            AgentToolResult("Tool failed (${e::class.simpleName.orEmpty()})", isError = true)
        }
    }

    private suspend fun authorizationRefusal(
        context: AgentToolContext,
        owner: AgentToolContribution,
        spec: AgentToolSpec,
        arguments: JsonObject,
        approval: AgentToolApproval,
        verdict: ToolHookVerdict,
    ): AgentToolResult? {
        val isRequiredByTrust = when (spec.action) {
            AgentToolAction.Read -> false
            AgentToolAction.Edit -> context.trust == TrustLevel.Ask
            AgentToolAction.Command -> context.trust != TrustLevel.Full
        }
        // The owner may add a decision (its own approval policy); it can never remove one the table demands.
        val isDecisionRequired = verdict is ToolHookVerdict.Ask ||
            isRequiredByTrust || owner.requiresDecision(context, spec, arguments)
        val shown = if (verdict is ToolHookVerdict.Ask) {
            approval.copy(
                description = "A session hook requires confirmation: ${verdict.reason.take(HOOK_NOTE_CHARS)}\n\n" +
                    approval.description.orEmpty(),
            )
        } else {
            approval
        }
        return if (isDecisionRequired && !context.permissions.request(shown)) {
            log.i { "Hosted tool declined name=${spec.name}" }
            AgentToolResult("The user declined this action", isError = true)
        } else if (declarations(
                context.toolScope(),
                isExecuting = true,
                isAdapterOperated = owner.isAdapterOperated,
            ).none { it.first === owner && it.second == spec }
        ) {
            AgentToolResult("Tool became unavailable", isError = true)
        } else if (isDecisionRequired && owner.approval(context, spec, arguments) != approval) {
            AgentToolResult("The action changed while awaiting approval; request it again", isError = true)
        } else {
            null
        }
    }

    private suspend fun declarations(
        scope: AgentToolScope,
        isExecuting: Boolean = false,
        isAdapterOperated: Boolean = false,
    ): List<Pair<AgentToolContribution, AgentToolSpec>> {
        val available = owners(scope.workspace).filter { it.isAdapterOperated == isAdapterOperated }
            .flatMap { owner -> owner.specifications(scope).map { owner to it } }
        check(available.map { it.second.name }.distinct().size == available.size) { "Duplicate hosted tool name" }
        val names = catalog().flatMap { it.tools }.map { it.name }.toSet() + available.map { it.second.name }
        val policy = policies.resolve(
            ToolPolicyScope(
                scope.target?.engine ?: scope.session?.engine,
                scope.workspace,
                scope.session,
                scope.target,
            ),
            names,
            isExecuting,
        ) ?: return emptyList()
        return available.filter { (_, spec) ->
            spec.name !in policy.hostedDenied && (scope.declared?.contains(spec.name) != false)
        }
    }

    private fun AgentToolContext.toolScope(): AgentToolScope =
        AgentToolScope(workspace, target, session = session, isRefreshedPerTurn = true)

    private fun owners(workspace: WorkspaceRef?): List<AgentToolContribution> =
        contributions.filter { workspace != null || it.isDetachedSupported }

    private fun List<String>.joined(): String = filter { it.isNotBlank() }.joinToString("\n\n")

    private data class BoundTurn(val turn: TurnId, val target: EngineTarget?)
}

private const val HOOK_NOTE_CHARS = 2_000
