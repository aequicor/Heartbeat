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
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
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

/** One authorization boundary; adapters never implement a second gate for these tools. */
@Inject
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class DefaultAgentTools(private val contributions: Set<AgentToolContribution>) : ProfileAgentTools {
    private val log = Log.tag("AgentTools")
    private val callsLock = Mutex()
    private val calls = mutableMapOf<Pair<SessionRef, TurnId>, MutableSet<Job>>()
    private val finishedTurns = mutableSetOf<Pair<SessionRef, TurnId>>()
    private val boundTurns = mutableMapOf<Pair<SessionRef, RequestId>, TurnId>()

    override suspend fun bindTurn(session: SessionRef, request: RequestId, turn: TurnId) {
        log.i { "Bind hosted execution to the facade turn" }
        callsLock.withLock {
            val key = session to request
            check(boundTurns[key] == null || boundTurns[key] == turn) { "Request already belongs to another turn" }
            boundTurns[key] = turn
        }
    }

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        declarations(workspace).map { it.second }

    override suspend fun instructions(workspace: WorkspaceRef?): String = contributions
        .map { it.instructions(workspace) }
        .filter { it.isNotBlank() }
        .joinToString("\n\n")

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult =
        coroutineScope {
            val invocation = checkNotNull(currentCoroutineContext()[Job])
            val turnEnded = context.lifetime?.invokeOnCompletion {
                invocation.cancel(CancellationException("Native turn ended"))
            }
            var trusted = context
            try {
                currentCoroutineContext().ensureActive()
                val isRegistered = callsLock.withLock {
                    val turn = context.request?.let { boundTurns[context.session to it] } ?: context.turn
                    trusted = context.copy(turn = turn, authorization = null)
                    val key = trusted.session to trusted.turn
                    if (key in finishedTurns) false else calls.getOrPut(key) { mutableSetOf() }.add(invocation)
                }
                if (!isRegistered) return@coroutineScope AgentToolResult("Native turn has ended", isError = true)
                executeAuthorized(trusted, name, arguments)
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
    }

    private suspend fun executeAuthorized(
        context: AgentToolContext,
        name: String,
        arguments: JsonObject,
    ): AgentToolResult {
        val declaration = declarations(context.workspace).singleOrNull { it.second.name == name }
            ?: return AgentToolResult("Tool is unavailable for this session", isError = true)
        val (owner, spec) = declaration
        log.i { "Hosted tool requested name=${spec.name} action=${spec.action}" }
        return try {
            val approval = owner.approval(context, spec, arguments)
            val refusal = authorizationRefusal(context, owner, spec, arguments, approval)
            if (refusal != null) {
                refusal
            } else {
                currentCoroutineContext().ensureActive()
                owner.execute(context.copy(authorization = approval), name, arguments).also {
                    log.i { "Hosted tool finished name=${spec.name} failed=${it.isError}" }
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
    ): AgentToolResult? {
        val isDecisionRequired = when (spec.action) {
            AgentToolAction.Read -> false
            AgentToolAction.Edit -> context.trust == TrustLevel.Ask
            AgentToolAction.Command -> context.trust != TrustLevel.Full
        }
        return if (isDecisionRequired && !context.permissions.request(approval)) {
            log.i { "Hosted tool declined name=${spec.name}" }
            AgentToolResult("The user declined this action", isError = true)
        } else if (declarations(context.workspace).none { it.first === owner && it.second == spec }) {
            AgentToolResult("Tool became unavailable", isError = true)
        } else if (isDecisionRequired && owner.approval(context, spec, arguments) != approval) {
            AgentToolResult("The action changed while awaiting approval; request it again", isError = true)
        } else {
            null
        }
    }

    private suspend fun declarations(workspace: WorkspaceRef?): List<Pair<AgentToolContribution, AgentToolSpec>> {
        val result = contributions.flatMap { owner -> owner.specifications(workspace).map { owner to it } }
        check(result.map { it.second.name }.distinct().size == result.size) { "Duplicate hosted tool name" }
        return result
    }
}
