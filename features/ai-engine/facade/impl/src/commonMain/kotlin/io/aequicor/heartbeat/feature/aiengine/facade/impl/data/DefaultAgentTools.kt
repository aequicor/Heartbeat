package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Multibinds
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentSecuritySettings
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolDecisionMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.HookedToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeConfirmation
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativePreparation
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeToolCall
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProfileAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedToolPolicy
import io.aequicor.heartbeat.feature.aiengine.facade.api.SecurityMode
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolGroup
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolHookVerdict
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolOutputOffload
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolPolicyScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.NoSessionHooks
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.SessionHooks
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.LegacyToolNamesResolver
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.NoToolPolicyResolver
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ToolPolicyResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
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
internal class DefaultAgentTools(
    private val contributions: Set<AgentToolContribution>,
    private val hooks: SessionHooks = NoSessionHooks,
    policies: ToolPolicyResolver = NoToolPolicyResolver,
    private val offloads: Lazy<Set<ToolOutputOffload>> = lazyOf(emptySet()),
    private val workspaces: LocalWorkspaces? = null,
    settings: AgentSecuritySettings = DefaultSecuritySettings,
) : ProfileAgentTools {
    private val log = Log.tag("AgentTools")
    private val permissions = ToolPermissions(settings)
    private val legacyNames: Map<String, Set<String>> by lazy {
        contributions.flatMap { it.legacyNames.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { (_, replacements) -> replacements.flatten().toSet() }
    }
    private val policyLookup: ToolPolicyResolver = LegacyToolNamesResolver(policies) { legacyNames }
    private val nativeGate = NativeToolGate(
        policyLookup,
        hooks,
        permissions,
        hostedNames = { catalog().flatMap { it.tools }.map { it.name }.toSet() },
        isVirtual = { context ->
            context.workspace?.value?.startsWith("sandbox-") == true ||
                context.workspace?.let { workspaces?.virtual(it) } != null
        },
    )
    private val callsLock = Mutex()
    private val calls = mutableMapOf<Pair<SessionRef, TurnId>, MutableSet<Job>>()
    private val claimedCalls = mutableMapOf<Pair<SessionRef, TurnId>, MutableSet<String>>()
    private val finishedTurns = mutableSetOf<Pair<SessionRef, TurnId>>()
    private val uncatalogued = MutableStateFlow(emptySet<String>())

    // Entries stay after the turn: a delayed call must still resolve to its finished facade turn.
    private val boundTurns = mutableMapOf<Pair<SessionRef, RequestId>, BoundTurn>()

    override suspend fun bindTurn(
        session: SessionRef,
        request: RequestId,
        turn: TurnId,
        target: EngineTarget?,
        decisionMode: AgentToolDecisionMode,
        reviewTarget: EngineTarget?,
    ) {
        log.i { "Bind hosted execution to the facade turn, separateReview=${reviewTarget != null}" }
        callsLock.withLock {
            val key = session to request
            val previous = boundTurns[key]
            val next = BoundTurn(turn, target ?: previous?.target, decisionMode, reviewTarget ?: previous?.reviewTarget)
            check(previous == null || previous == next) { "Request already has a different execution identity" }
            boundTurns[key] = next
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
        policyLookup.resolve(scope, catalog().flatMap { it.tools }.map { it.name }.toSet(), isExecuting = false),
    )

    override suspend fun nativeToolsForExecution(scope: ToolPolicyScope): ResolvedToolPolicy? =
        policyLookup.resolve(scope, catalog().flatMap { it.tools }.map { it.name }.toSet(), isExecuting = true)

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        specifications(AgentToolScope(workspace))

    override suspend fun specifications(scope: AgentToolScope): List<AgentToolSpec> =
        declarations(scope).map { it.second }

    override suspend fun instructions(workspace: WorkspaceRef?): String = instructions(AgentToolScope(workspace))

    override suspend fun instructions(scope: AgentToolScope): String {
        val independent = owners(scope.workspace).filter { it.hasIndependentInstructions && !it.isAdapterOperated }
        if (scope.declared?.isEmpty() == true) return independent.map { it.instructions(scope) }.joined()
        val declared = declared(scope)
        val scoped = scope.copy(declared = declared.allowed.map { it.second.name }.toSet())
        // Owners whose tools the policy removed still contribute what does not need them (learned rules), also
        // when the adapter already passes only the allowed names as declared; they honor the declared names
        // (allowed within the session's own), so disabled tools are never mentioned.
        val owners = declared.available.mapTo(linkedSetOf()) { it.first }
        owners += independent
        return owners.map { it.instructions(scoped) }.joined()
    }

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult =
        withInvocation(context, AgentToolResult("Native turn has ended", isError = true)) {
            executeAuthorized(it, name, arguments)
        }

    override suspend fun prepareNative(context: AgentToolContext, call: NativeToolCall): NativePreparation =
        withInvocation(context, NativePreparation.Deny("Native turn has ended")) { trusted ->
            when (val checked = nativeGate.prepare(trusted, call)) {
                is NativePreflight.Ready -> when (val verdict = checked.verdict) {
                    NativeVerdict.Allow -> NativePreparation.Allow
                    is NativeVerdict.Deny -> NativePreparation.Deny(verdict.reason)
                }

                is NativePreflight.Ask -> {
                    val consumed = kotlinx.coroutines.CompletableDeferred<Unit>()
                    NativePreparation.Ask(
                        NativeConfirmation {
                            if (!consumed.complete(Unit)) {
                                NativeVerdict.Deny("Native confirmation has already been consumed")
                            } else {
                                // Use the original native context so the facade binding is reapplied exactly once.
                                withInvocation(context, NativeVerdict.Deny("Native turn has ended")) {
                                    nativeGate.confirm(it, call, checked)
                                }
                            }
                        },
                    )
                }
            }
        }

    override suspend fun authorizeNative(context: AgentToolContext, call: NativeToolCall): NativeVerdict =
        when (val prepared = prepareNative(context, call)) {
            NativePreparation.Allow -> NativeVerdict.Allow
            is NativePreparation.Deny -> NativeVerdict.Deny(prepared.reason)
            is NativePreparation.Ask -> prepared.confirmation.authorize()
        }

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
            val mode = permissions.mode()
            currentCoroutineContext().ensureActive()
            val isRegistered = callsLock.withLock {
                val bound = context.request?.let { boundTurns[context.session to it] }
                trusted = context.copy(
                    turn = bound?.turn ?: context.turn,
                    historyTurn = context.turn,
                    target = bound?.target ?: context.target,
                    authorization = null,
                    trust = when (mode) {
                        SecurityMode.Ask -> TrustLevel.Ask
                        SecurityMode.Automatic -> TrustLevel.AutoEdits
                        SecurityMode.Trust -> TrustLevel.Full
                    },
                    decisionMode = AgentToolDecisionMode.Interactive,
                    reviewTarget = bound?.reviewTarget ?: context.reviewTarget,
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

    override suspend fun finishTurn(session: SessionRef, turn: TurnId) = finishTurn(session, turn, null)

    override suspend fun finishTurn(session: SessionRef, turn: TurnId, request: RequestId?) {
        log.i { "Revoke hosted tools and await outstanding calls" }
        try {
            // A cancelled cleanup still closes the barrier: no later call may register for the finished turn.
            val (bound, pending) = withContext(NonCancellable) {
                callsLock.withLock {
                    val key = session to turn
                    finishedTurns.add(key)
                    claimedCalls.remove(key)
                    val bound = boundTurns.entries.singleOrNull {
                        it.key.first == session && it.value.turn == turn
                    }?.key?.second
                    bound to calls[key]?.toList().orEmpty()
                }
            }
            pending.forEach { it.cancel() }
            pending.forEach { it.cancelAndJoin() }
            // One owner's failed cleanup must neither skip the others nor fail the turn that already ended.
            contributions.forEach { contribution ->
                try {
                    contribution.finishTurn(session, turn, bound ?: request)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.w(e) { "Hosted tool cleanup failed for the finished turn" }
                }
            }
            // The facade binding is authoritative: cleanup used it before the mismatch is reported.
            check(request == null || bound == null || request == bound) { "Cleanup request does not match its turn" }
        } finally {
            hooks.releaseTurn(session, turn)
        }
    }

    /** Long output stays out of the context when a profile feature offloads it. */
    private suspend fun AgentToolResult.shortened(context: AgentToolContext, name: String): AgentToolResult = try {
        offloads.value.firstNotNullOfOrNull { it.offload(context.session, name, text) }?.let { copy(text = it) } ?: this
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e) { "Tool output offload failed; the output stays whole" }
        this
    }

    /** An oversized result must never look like a complete one or flood the model context. */
    private fun AgentToolResult.withinBudget(spec: AgentToolSpec): AgentToolResult {
        if (text.length <= spec.resultChars) return this
        log.w {
            "Hosted tool result exceeds its budget name=${spec.name} chars=${text.length} limit=${spec.resultChars}"
        }
        return AgentToolResult(
            "The tool ran, but its text result exceeds the limit of ${spec.resultChars} characters. Request a " +
                "smaller or paged result. Do not repeat a mutating operation solely to retrieve its output.",
            isError = true,
        )
    }

    private suspend fun executeAuthorized(
        context: AgentToolContext,
        name: String,
        arguments: JsonObject,
        isAdapterOperated: Boolean = false,
    ): AgentToolResult {
        val callId = context.callId?.value
        if (callId != null && !callsLock.withLock {
                claimedCalls.getOrPut(context.session to context.turn) { mutableSetOf() }.add(callId)
            }
        ) {
            return AgentToolResult("This tool call has already been handled", isError = true)
        }
        val declaration = declarations(context.toolScope(), isExecuting = true, isAdapterOperated = isAdapterOperated)
            .singleOrNull { it.second.name == name }
            ?: return AgentToolResult("Tool is unavailable for this session", isError = true)
        val (owner, spec) = declaration
        log.i { "Hosted tool requested name=${spec.name} action=${spec.action}" }
        return try {
            val approval = owner.approval(context, spec, arguments)
            val refusal = authorizationRefusal(context, HostedApproval(owner, spec, arguments, approval))
            if (refusal != null) {
                refusal
            } else if (isAdapterOperated) {
                currentCoroutineContext().ensureActive()
                // The adapter performs the operation only after this authorization returns successfully.
                AgentToolResult("")
            } else {
                currentCoroutineContext().ensureActive()
                val result = owner.execute(context.copy(authorization = approval), name, arguments)
                    .shortened(context, name)
                    .withinBudget(spec)
                log.i { "Hosted tool finished name=${spec.name} failed=${result.isError}" }
                val note = afterResult(context, spec, arguments, result)
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

    /** A notification failure cannot turn a completed side effect into an apparent execution failure. */
    private suspend fun afterResult(
        context: AgentToolContext,
        spec: AgentToolSpec,
        arguments: JsonObject,
        result: AgentToolResult,
    ): String? = try {
        hooks.context(context.session, context.request, context.turn)?.let {
            hooks.afterTool(HookedToolCall(it, spec.name, spec.action, arguments), result)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(IllegalStateException("Result hook failed (${error::class.simpleName.orEmpty()})")) {
            "Tool completed but its result hook failed"
        }
        null
    }

    private suspend fun authorizationRefusal(context: AgentToolContext, operation: HostedApproval): AgentToolResult? {
        while (true) {
            val accepted = permissions.authorize(
                context,
                operation.approval.copy(
                    description = listOfNotNull(
                        operation.approval.description,
                        "Arguments: ${operation.argumentsSnapshot}",
                    ).joinToString("\n\n"),
                ),
            ) { requiresConfirmation(context, operation, it) }
            val failure = if (accepted == null) {
                "The user declined this action"
            } else {
                changedOperation(
                    context,
                    operation,
                )
            }
            if (failure != null) return AgentToolResult(failure, isError = true)
            // A mode change during final revalidation requires a new decision before dispatch.
            if (permissions.mode() == accepted) return null
        }
    }

    private suspend fun requiresConfirmation(
        context: AgentToolContext,
        operation: HostedApproval,
        mode: SecurityMode,
    ): Boolean {
        if (mode == SecurityMode.Trust) return false
        val owner = operation.owner
        val spec = operation.spec
        val existing = owner.existingAuthorization(context, spec, operation.arguments)
        val isCovered = !existing?.binding.isNullOrBlank() && existing == operation.approval
        val isOrdinaryEdit = context.workspace != null && spec.name in PROJECT_EDITS
        return hookNeedsConfirmation(context, operation) || (
            !isCovered &&
                (
                    needsConfirmation(mode, spec.action, isOrdinaryEdit) ||
                        owner.requiresDecision(context, spec, operation.arguments)
                )
        )
    }

    private suspend fun hookNeedsConfirmation(context: AgentToolContext, operation: HostedApproval): Boolean = try {
        val verdict = hooks.context(context.session, context.request, context.turn)?.let {
            hooks.beforeTool(HookedToolCall(it, operation.spec.name, operation.spec.action, operation.arguments))
        } ?: ToolHookVerdict.Continue
        verdict != ToolHookVerdict.Continue
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(IllegalStateException("Permission hook failed (${error::class.simpleName.orEmpty()})")) {
            "Permission hook unavailable; requesting confirmation"
        }
        true
    }

    private suspend fun changedOperation(context: AgentToolContext, operation: HostedApproval): String? {
        val owner = operation.owner
        val spec = operation.spec
        val isAvailable = declarations(
            context.toolScope(),
            isExecuting = true,
            isAdapterOperated = owner.isAdapterOperated,
        )
            .any { it.first === owner && it.second == spec }
        return when {
            !isAvailable -> "Tool became unavailable"

            operation.arguments.toString() != operation.argumentsSnapshot ||
                owner.approval(context, spec, operation.arguments) != operation.approval ->
                "The action changed while awaiting approval; request it again"

            else -> null
        }
    }

    private suspend fun declarations(
        scope: AgentToolScope,
        isExecuting: Boolean = false,
        isAdapterOperated: Boolean = false,
    ): List<Pair<AgentToolContribution, AgentToolSpec>> = declared(scope, isExecuting, isAdapterOperated).allowed

    private suspend fun declared(
        scope: AgentToolScope,
        isExecuting: Boolean = false,
        isAdapterOperated: Boolean = false,
    ): Declared {
        val available = owners(scope.workspace).filter { it.isAdapterOperated == isAdapterOperated }
            .flatMap { owner -> owner.specifications(scope).map { owner to it } }
        check(available.map { it.second.name }.distinct().size == available.size) { "Duplicate hosted tool name" }
        val catalogued = catalog().flatMap { it.tools }.map { it.name }.toSet()
        reportUncatalogued(available.map { it.second.name }.filterNot { it in catalogued })
        val policy = policyLookup.resolve(
            ToolPolicyScope(
                scope.target?.engine ?: scope.session?.engine,
                scope.workspace,
                scope.session,
                scope.target,
                scope.preselected,
            ),
            catalogued + available.map { it.second.name },
            isExecuting,
        )
        // Names the session froze at creation and the policy both narrow the allowed list only: an owner whose
        // tools are all filtered out still answers for instructions independent of them.
        val virtual = scope.workspace?.let { workspaces?.virtual(it) }
        check(
            scope.workspace?.value?.startsWith("sandbox-") != true || virtual != null,
        ) { "VirtualWorkspaceUnavailable" }
        if (isExecuting) virtual?.revision()
        val session = available.filter { (_, spec) ->
            scope.declared?.contains(spec.name) != false &&
                (virtual == null || virtualToolAllowed(spec.name))
        }
        return Declared(available, session.filter { (_, spec) -> policy != null && spec.name !in policy.hostedDenied })
    }

    /** Settings list only catalog names, so a tool missing from its owner's catalog cannot be turned off there. */
    private fun reportUncatalogued(names: List<String>) {
        if (names.isEmpty()) return
        val known = uncatalogued.getAndUpdate { it + names }
        val fresh = names.filterNot { it in known }
        if (fresh.isNotEmpty()) {
            log.w { "Hosted tools are missing from their contribution catalog names=${fresh.joinToString(",")}" }
        }
    }

    private fun AgentToolContext.toolScope(): AgentToolScope =
        AgentToolScope(workspace, target, session = session, isRefreshedPerTurn = true)

    private fun owners(workspace: WorkspaceRef?): List<AgentToolContribution> =
        contributions.filter { workspace != null || it.isDetachedSupported }

    private fun List<String>.joined(): String = filter { it.isNotBlank() }.joinToString("\n\n")

    private data class BoundTurn(
        val turn: TurnId,
        val target: EngineTarget?,
        val decisionMode: AgentToolDecisionMode,
        val reviewTarget: EngineTarget?,
    )

    /**
     * Declarations available to the session's workspace before its declared names and the effective policy, and
     * the ones both allow.
     */
    private data class Declared(
        val available: List<Pair<AgentToolContribution, AgentToolSpec>>,
        val allowed: List<Pair<AgentToolContribution, AgentToolSpec>>,
    )
}

private const val HOOK_NOTE_CHARS = 2_000

private val PROJECT_EDITS = setOf("write_file", "edit_file", "delete_file", "move_file")
