package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionAuthority
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionBinding
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionControl
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionExecutionInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.seconds

/** Stable executor incarnation. Retirement forbids future work; it is not evidence that native work stopped. */
@Serializable
internal data class StudioExecutorState(val instance: String, val isRetired: Boolean = false) {
    init {
        require(instance.isNotBlank()) { "Executor incarnation is missing" }
    }
}

/** Only explicit executor producers can establish execution authority; legacy full helpers retain their contract. */
internal val StudioChatRecord.isExplicitExecutor: Boolean
    get() = identityIssue == null && identity?.version == 1 && identity.origin in setOf(
        StudioIdentityOrigin.ExplicitSubagent,
        StudioIdentityOrigin.InternalExecution,
    )

/** Native segments keep one immutable instance and exact owner across rollover and restart. */
internal fun StudioChatRecord.executorBinding(): SessionExecutionBinding? {
    val instance = executor ?: run {
        check(!isExplicitExecutor) { "Executor incarnation metadata is unavailable" }
        return null
    }
    check(isExplicitExecutor) { "Executor role is unavailable" }
    return SessionExecutionBinding(SessionExecutionInstance("studio", instance.instance), logicalSession)
}

/** Fail closed before queuing, preparing or recovering any work of a retired instance. */
internal fun StudioChatRecord.requireLiveExecutor() {
    executorBinding()
    check(executor?.isRetired != true) { "Executor instance was permanently retired" }
}

/** Canonical Studio storage is the sole lifecycle authority; the facade keeps only immutable native bindings. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class StudioExecutorAuthority(
    private val records: StudioChatRecords,
    private val permissions: Lazy<StudioPermissionRelays>? = null,
) : SessionExecutionAuthority {
    override val provider: String = "studio"

    override suspend fun permitsPermissionOwner(
        relay: io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRelay,
    ): Boolean {
        val owner = resolvedStudioIdentities(records.store.get(ChatsKey).orEmpty())
            .singleOrNull { it.id == relay.owner.value }?.logicalSession ?: return false
        return owner.isHumanInteractive && owner.root == relay.binding.logicalSession?.root &&
            owner.id == relay.binding.logicalSession?.owner
    }

    override suspend fun enterPermission(
        relay: io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRelay,
        decision: io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision,
    ): Boolean = permits(relay.binding) && permissions?.value?.enter(relay, decision) == true

    override suspend fun permits(binding: SessionExecutionBinding): Boolean {
        val known = resolvedStudioIdentities(records.store.get(ChatsKey).orEmpty())
            .singleOrNull { it.executor?.instance == binding.instance.id } ?: return false
        return known.isExplicitExecutor && known.executor?.isRetired == false && known.executorBinding() == binding
    }
}

/** Retirement is durable before ordinary Stop observes the current request or a late native opener. */
@Inject
internal class StudioExecutorLifecycle(
    private val records: StudioChatRecords,
    private val control: SessionExecutionControl,
) {
    private val log = Log.tag("StudioExecutorLifecycle")

    suspend fun cancel(id: String, pause: suspend (String) -> Unit, stopWork: suspend () -> Unit) {
        val binding = retire(id)
        pause(id)
        if (binding == null) stopWork() else stop(binding, stopWork)
    }

    suspend fun retire(id: String): SessionExecutionBinding? {
        val saved = records.record(id)
        val instance = saved.executor ?: return null
        val binding = SessionExecutionBinding(
            SessionExecutionInstance("studio", instance.instance),
            saved.identity?.context?.takeUnless { it.isHumanInteractive },
        )
        control.fence(binding) {
            records.lock.withLock {
                val current = records.record(id)
                check(
                    current.executor?.instance == instance.instance,
                ) { "Executor incarnation changed before retirement" }
                records.updateRecord(id) { copy(executor = checkNotNull(executor).copy(isRetired = true)) }
            }
        }
        log.i { "Executor instance retired before native cancellation" }
        return binding
    }

    suspend fun stop(binding: SessionExecutionBinding, cancel: suspend () -> Unit) {
        try {
            cancel()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error) { "Initial executor Stop is unconfirmed; retry after admitted handoffs" }
        }
        try {
            log.v { "Wait for admitted executor handoffs before final exact native Stop" }
            val isDrained = withTimeoutOrNull(10.seconds) {
                control.awaitAdmissions(binding)
                true
            } == true
            check(isDrained) { "Executor handoff remains unconfirmed" }
        } finally {
            withContext(NonCancellable) { withTimeout(10.seconds) { cancel() } }
        }
    }
}
