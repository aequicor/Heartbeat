package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.AccessFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.SessionSourceId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SubsessionCreation
import io.aequicor.heartbeat.feature.scheduler.api.RequestInitiator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import kotlin.time.Instant

/** Creation idempotency is scoped to the logical creator, not its current engine segment. */
internal data class StudioSubsessionCreation(
    val creator: LogicalSessionId,
    val request: RequestId,
    val fingerprint: String,
    val cause: RequestInitiator,
)

/** Invalid creation provenance cannot take down the chat list or silently allocate another idempotent child. */
internal val StudioChatRecord.parsedSubsessionCreation: StudioSubsessionCreation?
    get() {
        val raw = subsessionCreation as? JsonObject ?: return null
        if ((raw["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull != 1) return null
        val creator = raw.creationString("creator") ?: return null
        val request = raw.creationString("request") ?: return null
        val fingerprint = raw.creationString("fingerprint") ?: return null
        val engine = raw.creationString("engine") ?: return null
        val source = raw.creationString("source") ?: return null
        val native = raw.creationString("native") ?: return null
        val cause = raw.creationString("cause") ?: return null
        return StudioSubsessionCreation(
            LogicalSessionId(creator),
            RequestId(request),
            fingerprint,
            RequestInitiator(SessionRef(EngineId(engine), SessionSourceId(source), native), RequestId(cause)),
        )
    }

private fun JsonObject.creationString(key: String): String? =
    (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

private fun StudioSubsessionCreation.metadata(): JsonObject = buildJsonObject {
    put("version", 1)
    put("creator", creator.value)
    put("request", request.value)
    put("fingerprint", fingerprint)
    put("engine", cause.session.engine.value)
    put("source", cause.session.source.value)
    put("native", cause.session.nativeId)
    put("cause", cause.request.value)
}

/** Validates the host-bound caller against the unique persisted conversation on every operation. */
internal fun List<StudioChatRecord>.conversationCaller(caller: AgentToolContext): StudioChatRecord {
    val context = caller.logicalSession ?: conversationControlDenied()
    val record = singleOrNull { it.id == context.id.value && it.owns(caller.session) }
        ?: conversationControlDenied()
    if (record.logicalSession != context || !context.isHumanInteractive) conversationControlDenied()
    if (record.resolvedExecutionWorkspace() != caller.workspace || caller.request == null) {
        conversationControlDenied()
    }
    return record
}

/** Root observation is deliberately narrower than control of another chat's direct child. */
internal fun List<StudioChatRecord>.controlledSubsession(
    caller: AgentToolContext,
    child: LogicalSessionId,
    isReadOnly: Boolean = false,
): StudioChatRecord {
    val source = checkNotNull(conversationCaller(caller).logicalSession)
    val record = singleOrNull { it.id == child.value } ?: conversationControlDenied()
    val target = record.logicalSession ?: conversationControlDenied()
    val isCreator = target.isControlledBy(source.id)
    val isFamilyRoot = isReadOnly && source.kind == SessionKind.Session && source.id == target.root
    if (target.kind != SessionKind.Subsession || (!isCreator && !isFamilyRoot)) conversationControlDenied()
    return record
}

internal fun newStudioSubsession(
    id: String,
    at: Instant,
    parent: StudioChatRecord,
    creation: SubsessionCreation,
    cause: RequestInitiator,
): StudioChatRecord {
    val context = parent.logicalSession ?: conversationControlDenied()
    check(parent.worktreeTaskId == null || parent.executionWorkspace != null) { "The creator's checkout is not ready" }
    check(creation.target != null || parent.target != null) { "The creator's route is unknown" }
    return StudioChatRecord(
        id,
        creation.title,
        at,
        target = creation.target ?: parent.target,
        projectId = parent.projectId,
        executionWorkspace = parent.resolvedExecutionWorkspace(),
        humanAuthority = parent.humanAuthority ?: StudioHumanAuthority(isComplete = false),
        identity = StudioSessionIdentity(StudioIdentityOrigin.AgentCreated, context.subsession(LogicalSessionId(id))),
        subsessionCreation = StudioSubsessionCreation(
            context.id,
            creation.request,
            creation.fingerprint(),
            cause,
        ).metadata(),
    )
}

internal fun SubsessionCreation.fingerprint(): String = Json.encodeToString(this).encodeUtf8().sha256().hex()

internal fun conversationControlDenied(): Nothing =
    throw EngineException(EngineFailure.Access(AccessFailureReason.OperationNotAllowed))

/** Inherited human originals precede the child's own originals; generated creator text is never added here. */
internal fun mergedConversationAuthority(parent: StudioChatRecord, child: StudioChatRecord): StudioHumanAuthority {
    val inherited = parent.humanAuthority ?: StudioHumanAuthority(isComplete = false)
    val own = child.humanAuthority ?: StudioHumanAuthority(isComplete = false)
    val messages = (inherited.messages + own.messages).distinctBy { it.request to it.text }
    val isUnique = messages.map { it.request }.distinct().size == messages.size
    return StudioHumanAuthority(messages, inherited.isComplete && own.isComplete && isUnique)
}
