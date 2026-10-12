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
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.PendingCompaction
import io.aequicor.heartbeat.feature.worktreemode.api.WorktreeRunKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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
 * features) wait in the context optimization machine; this host claims those of its chats and runs each as a turn of
 * the chat after the chat becomes idle, then reports the result. Sessions of other hosts are left alone.
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

    override val isAvailable: Flow<Boolean> get() = toggles.observe(ContextOptimizationEnabled)

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
        val session = chats().firstOrNull { it.id == chatId }?.ref ?: return false.also {
            log.i { "compaction skipped: the chat has no native session yet" }
        }
        val intent = ContextOptimizationIntent.Public.Compact(session, CompactionOrigin.User, instructions)
        val result = machines.send(ContextOptimizationMachineKey, intent)
        log.i { "compaction requested by the user: $result" }
        return result == SendResult.Accepted
    }

    /**
     * Reports the occupancy after a prompt turn; while automatic compaction is on the machine also decides whether a
     * compaction becomes pending. Compaction turns themselves are not reported, so they never trigger another one.
     */
    override suspend fun settled(request: StudioTurnRequest, active: ActiveSession, outcome: TurnOutcome) {
        if (request.compaction != null || outcome == TurnOutcome.Cancelled) return
        if (!toggles.get(ContextOptimizationEnabled)) return
        val usage = (active.features.resolve(SessionContextUsage) as? FeatureAccess.Available)?.feature?.state?.value
        val chat = chats().firstOrNull { it.id == request.id }
        val isRollover = chat?.isRolloverAllowed == true && toggles.get(ContextRolloverEnabled)
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
            pending.forEach { (session, compaction) -> launch { execute(session, compaction) } }
        }
    }

    private suspend fun execute(session: SessionRef, compaction: PendingCompaction) {
        val chat = chats().firstOrNull { it.ref == session } ?: return
        if (machines.send(ContextOptimizationMachineKey, ContextOptimizationIntent.Public.Claim(session)) !=
            SendResult.Accepted
        ) {
            return
        }
        log.i { "run compaction method=${compaction.method} origin=${compaction.origin}" }
        val result = try {
            compact(chat, compaction)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "compaction of a studio chat failed" }
            CompactionResult.Failed
        }
        log.i { "compaction finished: $result" }
        machines.send(ContextOptimizationMachineKey, ContextOptimizationIntent.Public.Finished(session, result))
    }

    private suspend fun compact(chat: StudioChatRecord, compaction: PendingCompaction): CompactionResult {
        val isRollover = compaction.method == CompactionMethod.Rollover
        if (isRollover && (!chat.isRolloverAllowed || !toggles.get(ContextRolloverEnabled))) {
            return CompactionResult.Unsupported
        }
        val applied = runtime.value.state.value.configurations[chat.id]?.applied
        val settings = hostRunSettings(chat, applied, runtime.value.defaults())
        val request = StudioTurnRequest(
            chat.id,
            prompt = "",
            settings = settings,
            kind = WorktreeRunKind.Coding,
            request = RequestId("compact-${Uuid.random()}"),
            compaction = ContextCompaction(compaction.instructions).takeUnless { isRollover },
            isRollover = isRollover,
        )
        val outcome = runs.run(host.value, request, waitForIdle = true)
        return if (outcome == RunOutcome.Completed) CompactionResult.Compacted else CompactionResult.Failed
    }

    private suspend fun chats(): List<StudioChatRecord> = store.get(ChatsKey).orEmpty()
}

/** Starts [StudioCompactions] with the profile, so compactions run even while no studio screen is open. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class StudioCompactionStartup(
    private val compactions: Lazy<StudioCompactionHost>,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : ProfileStartup {
    override fun start() {
        profile.coroutineScope.launch {
            try {
                compactions.value.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.tag("StudioCompactions").e(e) { "Compaction host stopped" }
            }
        }
    }
}
