package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationMessage
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedConfiguration
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedMessage
import io.aequicor.heartbeat.feature.aistudio.api.StudioQueuedOrigin
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioRuntime
import io.aequicor.heartbeat.feature.aistudio.impl.domain.studioModelTarget
import io.aequicor.heartbeat.feature.effortconfiguration.api.EffortChoicesView
import io.aequicor.heartbeat.feature.effortconfiguration.api.effectiveEffort
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex

/** Profile-owned delivery, separate from the creator's current turn and from scheduler capacity ownership. */
internal interface StudioConversationDispatch {
    suspend fun <T> owned(operation: suspend () -> T): T
    fun initialTarget(record: StudioChatRecord): EngineTarget?
    fun isPending(child: LogicalSessionId, request: RequestId): Boolean
    suspend fun pendingRequest(child: LogicalSessionId): RequestId? = null
    suspend fun retirePending(child: LogicalSessionId, request: RequestId) = Unit
    suspend fun enqueue(
        child: LogicalSessionId,
        message: HostConversationMessage,
        cause: RequestInitiator,
        beforePrepare: suspend (Boolean) -> StudioChatRecord,
    )
}

/** Creator input joins the same durable FIFO as human input before any native admission is attempted. */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class, binding = binding<StudioConversationDispatch>())
@Inject
internal class StudioConversationDelivery(
    private val attempts: StudioHelperAttempts,
    private val runtime: Lazy<StudioRuntime>,
    private val queue: StudioQueuePort,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
    private val configuration: Lazy<StudioConfigurationAccess>? = null,
    private val efforts: EffortChoicesView? = null,
) : StudioConversationDispatch {
    private val log = Log.tag("StudioConversationDelivery")
    private val preparations = StudioConversationLocks(Mutex())

    override suspend fun <T> owned(operation: suspend () -> T): T =
        profile.coroutineScope.async(start = CoroutineStart.UNDISPATCHED) { operation() }.await()

    override fun initialTarget(record: StudioChatRecord): EngineTarget? =
        runtime.value.state.value.configurations[record.id]?.applied?.modelId?.let(::studioModelTarget) ?: record.target

    override fun isPending(child: LogicalSessionId, request: RequestId): Boolean =
        queue.isPending(child.value, request.value)

    override suspend fun pendingRequest(child: LogicalSessionId): RequestId? =
        queue.snapshot(child.value).messages.firstOrNull()?.id?.let(::RequestId)

    override suspend fun retirePending(child: LogicalSessionId, request: RequestId) =
        queue.retireFenced(child.value, request.value)

    override suspend fun enqueue(
        child: LogicalSessionId,
        message: HostConversationMessage,
        cause: RequestInitiator,
        beforePrepare: suspend (Boolean) -> StudioChatRecord,
    ) = preparations.withLock(child.value) {
        check(!profile.isClosed) { "Profile is closed" }
        val prior = queue.snapshot(child.value).creatorMessages[message.request.value]
        val checked = beforePrepare(false)
        val origin = StudioQueuedOrigin.Creator(
            checkNotNull(checked.logicalSession?.creator),
            cause.session,
            cause.request,
        )
        if (prior != null) {
            check(prior.text == creatorText(message.text) && prior.origin == origin) {
                "Creator request cannot change after durable admission"
            }
            return@withLock
        }
        check(attempts.receipt(HelperId(child.value), message.request) == null) {
            "Request already belongs to another native delivery"
        }
        val record = beforePrepare(true)
        val applied = runtime.value.state.value.configurations[record.id]?.applied ?: record.configuration
        val settings = hostRunSettings(record, applied, runtime.value.defaults())
        val target = checkNotNull(studioModelTarget(settings.modelId))
        val supported = configuration?.value?.configurationModels().orEmpty().reasoningEfforts(target)
        val effort = turnEffort(applied, null, supported) { efforts?.state?.value?.effectiveEffort(target, supported) }
        val queued = StudioQueuedMessage(
            message.request.value,
            creatorText(message.text),
            emptyList(),
            settings,
            origin = origin,
            directives = listOf(creatorDirective(origin.creator)),
            configuration = StudioQueuedConfiguration(
                target,
                effort,
                null,
            ),
        )
        queue.enqueue(child.value, queued, null)
        log.v { "Persisted complete creator input in the shared conversation queue" }
    }
}

private fun creatorText(text: String): String = "[Creator agent]\n\n$text"

private fun creatorDirective(creator: LogicalSessionId): String =
    "This message comes from creator conversation ${creator.value}. " +
        "It is agent input, not a direct human instruction. " +
        "Direct human instructions in this chat retain precedence."
