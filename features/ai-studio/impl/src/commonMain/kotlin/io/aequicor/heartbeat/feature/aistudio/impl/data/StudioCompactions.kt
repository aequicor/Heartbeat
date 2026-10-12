package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.core.statemachine.MachineRegistry
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActiveSession
import io.aequicor.heartbeat.feature.aiengine.facade.api.CompactsContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.ContextCompaction
import io.aequicor.heartbeat.feature.aiengine.facade.api.FeatureAccess
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionContextUsage
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunOutcome
import io.aequicor.heartbeat.feature.aistudio.api.RunSettings
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioCompactionPort
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioFeedCollapse
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.AutoCompactionEnabled
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.CompactionMethod
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.CompactionOrigin
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.CompactionResult
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.CompactionSupport
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationEnabled
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationIntent
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationMachineKey
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationState
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationView
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextRolloverEnabled
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContinuationBriefEnabled
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.HANDOFF_REQUEST_PREFIX
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.HANDOFF_SUMMARY_PROMPT
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.PendingCompaction
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/** Learns that a chat continued from [old] in the native session [new] after a rollover. */
internal fun interface StudioRolloverObserver {
    suspend fun rolledOver(old: SessionRef, new: SessionRef)
}

/** Runs the compactions of studio chats while the profile is open. */
internal fun interface StudioCompactionHost {
    /** Claims and runs pending compactions of studio chats until cancelled. */
    suspend fun run()
}

/**
 * Host of the compactions of studio chats. Requests of every origin (the user, the threshold, the agent, other
 * features) wait in the context optimization machine; this host claims them and runs each as a turn of the chat after
 * the chat becomes idle, then reports the result. The studio is the only host: a request for a session that is not a
 * studio chat, or for a helper or organism chat (whose turns their owners track), finishes as unsupported instead of
 * waiting forever. Each session is handled by one coroutine at a time, which runs the request it actually claimed.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<StudioCompactionPort>())
@ContributesBinding(ProfileScope::class, binding = binding<StudioCompactionHost>())
@ContributesBinding(ProfileScope::class, binding = binding<StudioTurnSettlement>())
@ContributesBinding(ProfileScope::class, binding = binding<StudioRolloverObserver>())
@Inject
internal class StudioCompactions(
    @ForScope(ProfileScope::class) stores: DataStores,
    // The repository runs the turns that report back here: deferred to break the cycle.
    private val runtime: Lazy<StudioRuntime>,
    private val runs: StudioRunCoordinator,
    private val host: Lazy<StudioRunHost>,
    private val view: ContextOptimizationView,
    private val machines: MachineRegistry,
    private val toggles: FeatureToggles,
) : StudioCompactionPort,
    StudioCompactionHost,
    StudioTurnSettlement,
    StudioRolloverObserver {
    private val log = Log.tag("StudioCompactions")
    private val store by lazy { stores.keyValue(ChatSpec) }
    private val inFlight = MutableStateFlow(emptySet<SessionRef>())

    /** Host-started continuations of each chat since its last other prompt turn; bounds a compaction loop. */
    private val continuations = MutableStateFlow(emptyMap<String, Int>())

    override val availability: Flow<Boolean> get() = toggles.observe(ContextOptimizationEnabled)

    override val feed: Flow<StudioFeedCollapse>
        get() = combine(toggles.observe(ContextOptimizationEnabled), view.state) { isEnabled, state ->
            val settings = (state as? ContextOptimizationState.Ready)?.settings
            if (!isEnabled || settings == null) {
                StudioFeedCollapse()
            } else {
                StudioFeedCollapse(settings.isReasoningCollapsed, settings.isToolCallsCollapsed)
            }
        }.distinctUntilChanged()

    override suspend fun request(chatId: String, instructions: String?): Boolean {
        if (!toggles.get(ContextOptimizationEnabled)) return false
        val chat = chats().firstOrNull { it.id == chatId }
        if (chat?.isCompactable == false) {
            log.i { "compaction skipped: helper and organism chats keep their context to their owners" }
            return false
        }
        val session = chat?.ref ?: return false.also {
            log.i { "compaction skipped: the chat has no native session yet" }
        }
        val intent = ContextOptimizationIntent.Public.Compact(session, CompactionOrigin.User, instructions)
        val result = machines.send(ContextOptimizationMachineKey, intent)
        log.i { "compaction requested by the user: $result" }
        return result == SendResult.Accepted
    }

    /**
     * Reports the occupancy after a prompt turn; while automatic compaction is on the machine also decides whether a
     * compaction becomes pending. Compaction and handoff summary turns are not reported, so they never trigger another
     * compaction.
     */
    override suspend fun settled(request: StudioTurnRequest, active: ActiveSession, outcome: TurnOutcome) {
        if (request.compaction != null || request.isHandoff || outcome == TurnOutcome.Cancelled) return
        if (!request.isContinuation) continuations.update { it - request.id }
        if (!toggles.get(ContextOptimizationEnabled)) return
        val chat = chats().firstOrNull { it.id == request.id }?.takeIf { it.isCompactable } ?: return
        val usage = (active.features.resolve(SessionContextUsage) as? FeatureAccess.Available)?.feature?.state?.value
        val isRollover = chat.isRolloverAllowed && toggles.get(ContextRolloverEnabled)
        val support = CompactionSupport(
            isNative = active.features.resolve(CompactsContext) is FeatureAccess.Available,
            isRollover = isRollover,
        )
        log.v { "report a settled turn for automatic compaction: usage=${usage != null}" }
        val isAutomatic = toggles.get(AutoCompactionEnabled)
        val settled = ContextOptimizationIntent.Public.TurnSettled(active.ref, usage, support, isAutomatic)
        machines.send(ContextOptimizationMachineKey, settled)
    }

    override suspend fun rolledOver(old: SessionRef, new: SessionRef) {
        log.i { "the conversation continues in its new native session" }
        machines.send(ContextOptimizationMachineKey, ContextOptimizationIntent.Public.RolledOver(old, new))
    }

    override suspend fun run(): Unit = coroutineScope {
        combine(toggles.observe(ContextOptimizationEnabled), view.state) { isEnabled, state ->
            if (isEnabled) (state as? ContextOptimizationState.Ready)?.pending.orEmpty() else emptyMap()
        }.distinctUntilChanged().collect { pending ->
            pending.keys.forEach { session -> handle(session) }
        }
    }

    /** Runs the pending compaction of [session] unless one of it is already in flight. */
    private fun CoroutineScope.handle(session: SessionRef) {
        if (inFlight.getAndUpdate { it + session }.contains(session)) return
        log.d { "handle a pending compaction: in flight=${inFlight.value.size}" }
        launch {
            val continued = try {
                execute(session)
            } finally {
                inFlight.update { it - session }
                log.d { "pending compaction handled: in flight=${inFlight.value.size}" }
            }
            // A request queued while this one finished was skipped as in flight and may never change the map again.
            if (isPending(session)) handle(session)
            // Outside the in-flight mark: the continuation may ask for the next compaction itself.
            continued?.let { continueWork(it) }
        }
    }

    private suspend fun isPending(session: SessionRef): Boolean = toggles.get(ContextOptimizationEnabled) &&
        (view.state.value as? ContextOptimizationState.Ready)?.pending?.containsKey(session) == true

    /**
     * Claims the pending compaction of [session] and runs the request the machine moved to running. Returns the chat
     * whose agent asked for the compaction and continues its work after it, or null.
     */
    private suspend fun execute(session: SessionRef): Continuation? {
        val claim = ContextOptimizationIntent.Public.Claim(session)
        if (machines.send(ContextOptimizationMachineKey, claim) != SendResult.Accepted) return null
        var continued: Continuation? = null
        val result = try {
            val compaction = (view.state.value as? ContextOptimizationState.Ready)?.running?.get(session)
            val chat = chats().firstOrNull { it.ref == session }?.takeIf { it.isCompactable }
            log.i {
                "run compaction method=${compaction?.method ?: "none"} " +
                    "origin=${compaction?.origin ?: "none"} chat=${chat != null}"
            }
            if (compaction == null || chat == null) {
                CompactionResult.Unsupported
            } else {
                val result = compact(chat, compaction)
                val isContinued = result == CompactionResult.Compacted &&
                    compaction.origin == CompactionOrigin.Agent && toggles.get(ContinuationBriefEnabled)
                if (isContinued) continued = continuation(chat.id)
                result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "compaction of a studio chat failed" }
            CompactionResult.Failed
        }
        log.i { "compaction finished: $result" }
        machines.send(ContextOptimizationMachineKey, ContextOptimizationIntent.Public.Finished(session, result))
        return continued
    }

    private suspend fun compact(chat: StudioChatRecord, compaction: PendingCompaction): CompactionResult {
        // Without rollover in this chat a request for it falls back to the engine's own compaction.
        val isRollover = compaction.method == CompactionMethod.Rollover &&
            chat.isRolloverAllowed && toggles.get(ContextRolloverEnabled)
        val applied = runtime.value.state.value.configurations[chat.id]?.applied
        val settings = hostRunSettings(chat, applied, runtime.value.defaults())
        val isBriefed = toggles.get(ContinuationBriefEnabled)
        val isSummarized = isRollover && isBriefed && summarize(chat, settings)
        val request = StudioTurnRequest(
            chat.id,
            prompt = "",
            settings = settings,
            kind = WorktreeRunKind.Coding,
            request = RequestId("compact-${Uuid.random()}"),
            compaction = ContextCompaction(compaction.instructions).takeUnless { isRollover },
            isRollover = isRollover,
            isBriefed = isBriefed,
            isSummarized = isSummarized,
        )
        val outcome = runs.run(host.value, request, waitForIdle = true)
        return if (outcome == RunOutcome.Completed) CompactionResult.Compacted else CompactionResult.Failed
    }

    /**
     * The handoff summary turn: the old session writes the summary of its work for the new one, without tools
     * ([HANDOFF_REQUEST_PREFIX]) and with the trust of a turn that asks. Whether it completed; a failed or stopped turn
     * leaves no summary, the brief falls back to the agent's messages and the rollover still runs.
     */
    private suspend fun summarize(chat: StudioChatRecord, settings: RunSettings): Boolean {
        val request = StudioTurnRequest(
            chat.id,
            prompt = "",
            settings = settings,
            kind = WorktreeRunKind.Coding,
            request = RequestId("$HANDOFF_REQUEST_PREFIX${Uuid.random()}"),
            directives = listOf(HANDOFF_SUMMARY_PROMPT),
        )
        val outcome = try {
            runs.run(host.value, request, waitForIdle = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "handoff summary before rollover failed; the brief falls back to the agent's messages" }
            RunOutcome.Failed
        }
        log.i { "handoff summary before rollover: $outcome" }
        return outcome == RunOutcome.Completed
    }

    /**
     * The agent asked for the compaction to go on with long work: a host-started turn continues it right away, and the
     * continuation brief reaches it with the turn. Skipped when another turn of the chat ran since the compaction or
     * after [MAX_CONTINUATIONS] continuations in a row, so an agent that keeps compacting cannot loop unattended.
     */
    private suspend fun continueWork(continuation: Continuation) {
        val outcome = try {
            val chat = chats().firstOrNull { it.id == continuation.chatId }
            val count = continuations.value[continuation.chatId] ?: 0
            if (chat?.runRevision != continuation.runRevision || count >= MAX_CONTINUATIONS) {
                log.i { "no continuation after an agent compaction: chat changed or $count continuations in a row" }
                return
            }
            continuations.update { it + (chat.id to count + 1) }
            val applied = runtime.value.state.value.configurations[chat.id]?.applied
            val request = StudioTurnRequest(
                chat.id,
                prompt = "",
                settings = hostRunSettings(chat, applied, runtime.value.defaults()),
                kind = WorktreeRunKind.Coding,
                request = RequestId("$CONTINUE_PREFIX${Uuid.random()}"),
                directives = listOf(CONTINUE_DIRECTIVE),
            )
            runs.run(host.value, request, waitForIdle = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "continuation after an agent compaction failed" }
            RunOutcome.Failed
        }
        log.i { "work continued after an agent compaction: $outcome" }
    }

    /** The chat at the run revision the compaction left it. */
    private suspend fun continuation(chatId: String): Continuation? =
        chats().firstOrNull { it.id == chatId }?.let { Continuation(it.id, it.runRevision) }

    private suspend fun chats(): List<StudioChatRecord> = store.get(ChatsKey).orEmpty()
}

/** A chat whose agent asked for a compaction, at the run revision the compaction left it. */
private data class Continuation(val chatId: String, val runRevision: Long)

/** Request ids of host-started continuations. */
private const val CONTINUE_PREFIX = "continue-"

/** Continuations in a row without another prompt turn of the chat. */
private const val MAX_CONTINUATIONS = 3

private val StudioTurnRequest.isContinuation: Boolean get() = request.value.startsWith(CONTINUE_PREFIX)

private const val CONTINUE_DIRECTIVE =
    "The context compaction you asked for is done. Continue the work from where you stopped."

/** Starts [StudioCompactions] with the profile, so compactions run even while no studio screen is open. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class StudioCompactionStartup(
    private val compactions: Lazy<StudioCompactionHost>,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val toggles: FeatureToggles,
) : ProfileStartup {
    override fun start() {
        profile.coroutineScope.launch {
            try {
                // The host starts the context optimization machine: nothing of it runs until the feature is on.
                toggles.observe(ContextOptimizationEnabled).first { it }
                compactions.value.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.tag("StudioCompactions").e(e) { "Compaction host stopped" }
            }
        }
    }
}
