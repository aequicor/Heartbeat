package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationCancellation
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationControl
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationMessage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.SubsessionCreation
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Full subsessions share the ordinary chat registry and configuration, without a permanent helper work lease. */
@ContributesBinding(ProfileScope::class)
@Inject
internal class EngineStudioConversationControl(
    private val records: Lazy<StudioConversationRecords>,
    private val dispatch: StudioConversationDispatch,
    private val observations: StudioConversationObservations,
    private val clock: Clock,
) : HostConversationControl {
    override suspend fun createSubsession(
        caller: AgentToolContext,
        creation: SubsessionCreation,
    ): HostConversationSnapshot {
        checkInvocation(caller)
        return dispatch.owned {
            val child = records.value.editConversations { rows ->
                val source = rows.conversationCaller(caller)
                check(rows.none { it.subsessionCreation != null && it.parsedSubsessionCreation == null }) {
                    "Creation provenance is unreadable; reconcile before allocating another conversation"
                }
                val created = rows.filter {
                    it.parsedSubsessionCreation?.let { saved ->
                        saved.creator == source.logicalSession?.id && saved.request == creation.request
                    } == true
                }
                check(created.size <= 1) { "Conversation creation identity is ambiguous" }
                val previous = created.singleOrNull()
                if (previous != null) {
                    check(previous.parsedSubsessionCreation?.fingerprint == creation.fingerprint()) {
                        "Conversation creation cannot change under the same request"
                    }
                    StudioConversationUpdate(rows, previous)
                } else {
                    val parent = source.copy(target = dispatch.initialTarget(source))
                    val record = newStudioSubsession(
                        Uuid.random().toString(),
                        clock.now(),
                        parent,
                        creation,
                        caller.cause(),
                    )
                    StudioConversationUpdate(rows + record, record)
                }
            }
            val childId = LogicalSessionId(child.id)
            val initialMessage = creation.initialMessage
            if (initialMessage != null && child.logicalSession?.kind == SessionKind.Subsession) {
                enqueue(
                    caller,
                    childId,
                    HostConversationMessage(creation.request, initialMessage),
                    checkNotNull(child.parsedSubsessionCreation).cause,
                )
            }
            observations.snapshot(readCreated(childId))
        }
    }

    override suspend fun send(
        caller: AgentToolContext,
        child: LogicalSessionId,
        message: HostConversationMessage,
    ): HostConversationSnapshot {
        checkInvocation(caller)
        return dispatch.owned {
            read(caller, child, isReadOnly = false)
            enqueue(caller, child, message, caller.cause())
            observations.snapshot(read(caller, child, isReadOnly = true))
        }
    }

    override suspend fun state(caller: AgentToolContext, child: LogicalSessionId): HostConversationSnapshot {
        checkInvocation(caller)
        return observations.snapshot(read(caller, child, isReadOnly = true))
    }

    override suspend fun result(
        caller: AgentToolContext,
        child: LogicalSessionId,
        request: RequestId,
    ): HostConversationResult? {
        checkInvocation(caller)
        return observations.result(read(caller, child, isReadOnly = true), request)
    }

    override suspend fun cancel(
        caller: AgentToolContext,
        child: LogicalSessionId,
        request: RequestId,
    ): HostConversationCancellation {
        checkInvocation(caller)
        return dispatch.owned { observations.cancel(read(caller, child, isReadOnly = false), request) }
    }

    private suspend fun enqueue(
        caller: AgentToolContext,
        child: LogicalSessionId,
        message: HostConversationMessage,
        cause: RequestInitiator,
    ) {
        dispatch.enqueue(child, message, cause) { isNew ->
            records.value.editConversations { rows ->
                val source = rows.conversationCaller(caller)
                val target = rows.controlledSubsession(caller, child)
                val updated = if (isNew) {
                    target.copy(
                        humanAuthority = mergedConversationAuthority(source, target),
                    )
                } else {
                    target
                }
                StudioConversationUpdate(rows.map { if (it.id == target.id) updated else it }, updated)
            }
        }
    }

    private suspend fun read(caller: AgentToolContext, child: LogicalSessionId, isReadOnly: Boolean): StudioChatRecord =
        records.value.editConversations { rows ->
            StudioConversationUpdate(rows, rows.controlledSubsession(caller, child, isReadOnly))
        }

    private suspend fun readCreated(child: LogicalSessionId): StudioChatRecord =
        records.value.editConversations { rows ->
            StudioConversationUpdate(rows, checkNotNull(rows.singleOrNull { it.id == child.value }))
        }

    private suspend fun checkInvocation(caller: AgentToolContext) {
        currentCoroutineContext().ensureActive()
        caller.lifetime?.ensureActive()
        if (caller.logicalSession == null || caller.request == null) conversationControlDenied()
    }
}

private fun AgentToolContext.cause(): RequestInitiator = RequestInitiator(session, checkNotNull(request))
