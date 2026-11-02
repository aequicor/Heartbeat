package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContribution
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationAttemptPhase
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationControl
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationMessage
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.HostConversationSnapshot
import io.aequicor.heartbeat.feature.aiengine.facade.api.LogicalSessionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.SubsessionCreation
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.aiengine.facade.api.toolCatalog
import io.aequicor.heartbeat.feature.aistudio.api.StudioSessionModel
import io.aequicor.heartbeat.feature.aistudio.api.SubsessionTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import okio.ByteString.Companion.encodeUtf8

/** Thin hosted adapter: durable ownership and native evidence stay in [HostConversationControl]. */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class SubsessionAgentTools(
    private val conversations: Lazy<HostConversationControl>,
    private val toggles: FeatureToggles,
) : AgentToolContribution {
    private val log = Log.tag("SubsessionAgentTools")
    override val group = "subsessions"
    override val title = "Субсессии"
    override val isDetachedSupported = true
    override val catalog get() = ALL_SPECS.toolCatalog()

    override suspend fun specifications(workspace: WorkspaceRef?): List<AgentToolSpec> =
        if (toggles.get(StudioSessionModel)) ALL_SPECS else SUBSESSION_RECOVERY_SPECS

    override suspend fun instructions(workspace: WorkspaceRef?): String {
        val recovery = "Subsession ids are logical chat ids. Only their direct creator can send or stop; " +
            "the family root may observe. A result belongs to an exact request. " +
            "A launch acknowledgement, null result, Unknown or Unconfirmed never proves completion. " +
            "subsession_wait is an explicit bounded polling wait; timeout leaves execution unchanged."
        return if (toggles.get(StudioSessionModel)) {
            "subsession_spawn creates a full user-interactive chat; subagent_spawn creates a task executor. " +
                "spawn and send return without waiting for completion. Later sends preserve the user's current " +
                "model and human instruction priority. " + recovery
        } else {
            "New subsession starts are disabled. Existing status, result, wait and cancellation remain available. " +
                recovery
        }
    }

    override suspend fun approval(context: AgentToolContext, spec: AgentToolSpec, arguments: JsonObject) =
        AgentToolApproval(
            spec.name,
            "Субсессия: ${spec.name}",
            arguments.toString(),
            approvalBinding(context, spec, arguments),
        )

    override suspend fun execute(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult {
        val spec = ALL_SPECS.singleOrNull { it.name == name } ?: return failure("Unknown subsession tool")
        if (context.logicalSession?.isHumanInteractive != true) {
            return failure("A verified full conversation is required")
        }
        if (name in START_NAMES && !toggles.get(StudioSessionModel)) {
            return failure("New subsession starts are disabled")
        }
        if (name in COMMAND_NAMES && context.authorization != approval(context, spec, arguments)) {
            return failure("The operation has no matching authorization; request it again")
        }
        return try {
            invoke(context, name, arguments)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.w(error.redactedFailure()) { "Subsession operation could not be confirmed" }
            failure(
                "Operation not confirmed. Inspect its recorded state before retrying; " +
                    "do not assume it was not submitted.",
            )
        }
    }

    private suspend fun invoke(context: AgentToolContext, name: String, arguments: JsonObject): AgentToolResult =
        when (name) {
            SubsessionTools.SPAWN -> {
                val input = Json.decodeFromJsonElement<SubsessionSpawnArguments>(arguments)
                val request = context.subsessionRequest(name)
                val snapshot = conversations.value.createSubsession(
                    context,
                    SubsessionCreation(request, input.title, input.message),
                )
                AgentToolResult(
                    Json.encodeToString(SubsessionDelivery(snapshot, request.value, snapshot.context.id.value)),
                )
            }

            SubsessionTools.SEND -> {
                val input = Json.decodeFromJsonElement<SubsessionSendArguments>(arguments)
                val request = context.subsessionRequest(name)
                val snapshot = conversations.value.send(
                    context,
                    LogicalSessionId(input.sessionId),
                    HostConversationMessage(request, input.message),
                )
                AgentToolResult(
                    Json.encodeToString(SubsessionDelivery(snapshot, request.value, snapshot.context.id.value)),
                )
            }

            SubsessionTools.STATUS -> {
                val input = Json.decodeFromJsonElement<SubsessionStatusArguments>(arguments)
                AgentToolResult(
                    Json.encodeToString(conversations.value.state(context, LogicalSessionId(input.sessionId))),
                )
            }

            else -> inspectRequest(context, name, Json.decodeFromJsonElement(arguments))
        }

    private suspend fun inspectRequest(
        context: AgentToolContext,
        name: String,
        input: SubsessionRequestArguments,
    ): AgentToolResult {
        val child = LogicalSessionId(input.sessionId)
        val request = RequestId(input.requestId)
        return when (name) {
            SubsessionTools.RESULT -> AgentToolResult(
                Json.encodeToString(conversations.value.result(context, child, request)),
            )

            SubsessionTools.CANCEL -> AgentToolResult(
                Json.encodeToString(conversations.value.cancel(context, child, request)),
            )

            SubsessionTools.WAIT -> AgentToolResult(Json.encodeToString(wait(context, child, request, input.timeoutMs)))

            else -> failure("Unknown subsession tool")
        }
    }

    private suspend fun wait(
        context: AgentToolContext,
        child: LogicalSessionId,
        request: RequestId,
        timeout: Long,
    ): SubsessionWaitResult {
        require(timeout in 1..SUBSESSION_MAX_WAIT_MS)
        val observed = withTimeoutOrNull(timeout) {
            var result: HostConversationResult?
            var isNotSubmitted: Boolean
            do {
                result = conversations.value.result(context, child, request)
                val attempt = conversations.value.state(context, child).attempt
                isNotSubmitted =
                    attempt?.request == request && attempt.phase == HostConversationAttemptPhase.NotSubmitted
                if (result == null && !isNotSubmitted) delay(POLL_MS)
            } while (result == null && !isNotSubmitted)
            SubsessionWaitResult(request.value, result, isNotSubmitted = isNotSubmitted)
        }
        return observed ?: SubsessionWaitResult(request.value, isTimedOut = true)
    }

    private fun failure(message: String) = AgentToolResult(message, isError = true)

    private companion object {
        val ALL_SPECS = SUBSESSION_START_SPECS + SUBSESSION_RECOVERY_SPECS
        val START_NAMES = SUBSESSION_START_SPECS.map { it.name }.toSet()
        val COMMAND_NAMES = START_NAMES + SubsessionTools.CANCEL
        const val POLL_MS: Long = 250
    }
}

/** Retry identity is derived from the actual call, never supplied as a fresh id by the model. */
internal fun AgentToolContext.subsessionRequest(name: String): RequestId {
    val logical = checkNotNull(logicalSession).id.value
    val source = checkNotNull(request).value
    val call = checkNotNull(callId).value
    require(call.isNotBlank())
    return RequestId("ss_" + "$logical\u0000$source\u0000$call\u0000$name".encodeUtf8().sha256().hex())
}

private fun approvalBinding(context: AgentToolContext, spec: AgentToolSpec, arguments: JsonObject): String {
    val source = "${context.logicalSession?.id?.value.orEmpty()}\u0000${context.request?.value.orEmpty()}\u0000"
    return (source + context.callId?.value.orEmpty() + "\u0000" + spec.name + "\u0000" + arguments)
        .encodeUtf8().sha256().hex()
}

@Serializable
private data class SubsessionDelivery(
    val session: HostConversationSnapshot,
    @SerialName("request_id") val requestId: String,
    @SerialName("session_id") val sessionId: String,
)

@Serializable
private data class SubsessionWaitResult(
    @SerialName("request_id") val requestId: String,
    val result: HostConversationResult? = null,
    val isNotSubmitted: Boolean = false,
    val isTimedOut: Boolean = false,
)
