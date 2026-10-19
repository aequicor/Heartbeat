package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.NativeCommandEnvironment
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ToolCallId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A tool call awaiting approval (`resources/pi/heartbeat-approval.ts`): its name, the command or path shown to the
 * user and, for `edit` / `write`, the absolute [path] the extension pinned in the call; null when it was not pinned.
 */
internal data class PiApprovalCall(
    val tool: String,
    val target: String,
    val path: String? = null,
    val arguments: JsonObject = JsonObject(emptyMap()),
    val result: AgentToolResult? = null,
    val toolCallId: ToolCallId? = null,
    val isStructuredReply: Boolean = false,
    val commandEnvironment: PiCommandEnvironment? = null,
) {
    override fun toString(): String = "PiApprovalCall"
}

/** Projection captured by the trusted extension and pinned by its private per-call execution plan. */
internal data class PiCommandEnvironment(val directory: String, val captured: NativeCommandEnvironment) {
    override fun toString(): String = "PiCommandEnvironment(***)"
}

/** Option that lets Pi run an approved tool; any other answer blocks it. */
internal val PiApprovalAllow = PermissionOptionId("allow")
private val PiApprovalDeny = PermissionOptionId("deny")

// Pi's built-in file mutation tools; commands and extension tools are never file edits.
internal val EditTools = setOf("edit", "write")

// The permission panel shows the title and the options only, so a cost the user must know is stated in them.
private const val ALLOW_TITLE = "Разрешить"
private const val DENY_TITLE = "Запретить"
private const val ALLOW_HOST_TITLE = "Завершить Heartbeat и выполнить"
private const val APPROVAL_TARGET_LIMIT = 4_000
private const val RESULT_CHARS = 32_768
private const val MAX_TOOL_CALL_ID_CHARS = 256
private const val HEX_RADIX = 16
private const val HEX_DIGITS = 4
private val log = Log.tag("PiApproval")

/** Parses the approval message; null when it is malformed, and the request stays blocked. */
internal fun approvalCall(message: String?, isResult: Boolean = false): PiApprovalCall? {
    val fields = try {
        message?.let { Json.parseToJsonElement(it) as? JsonObject }
    } catch (e: SerializationException) {
        // Parser messages quote the input, which contains the command; the request stays blocked.
        log.w(EngineException(EngineFailure.Transport(TransportFailureReason.ProtocolViolation))) {
            "Malformed Pi approval request: ${e::class.simpleName.orEmpty()}"
        }
        null
    } ?: return null
    val tool = fields.string("toolName")?.takeIf { it.isNotBlank() } ?: return null
    // Field names must match the request built in resources/pi/heartbeat-approval.ts.
    return PiApprovalCall(
        tool,
        fields.string("target").orEmpty(),
        fields.string("path"),
        fields["arguments"] as? JsonObject ?: JsonObject(emptyMap()),
        if (isResult && tool in SearchTools) {
            AgentToolResult(
                fields.string("result").orEmpty().take(RESULT_CHARS),
                isError = (fields["isError"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        } else if (isResult) {
            return null
        } else {
            null
        },
        fields.string("toolCallId")
            ?.takeIf { it.isNotBlank() && it.length <= MAX_TOOL_CALL_ID_CHARS }
            ?.let(::ToolCallId),
        commandEnvironment = if (tool == "bash" && !isResult) commandEnvironment(fields) else null,
    )
}

/** Model-facing arguments never supply this adapter-owned top-level protocol field. */
private fun commandEnvironment(fields: JsonObject): PiCommandEnvironment? {
    val files = (fields["arguments"] as? JsonObject)?.get("review_files") as? JsonArray
    val hasReviewFiles = files != null && files.size in 1..MAX_REVIEW_FILES && files.all {
        (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content?.isNotBlank() == true
    }
    if (!SimplePush.matches(fields.string("target").orEmpty().trim()) && !hasReviewFiles) return null
    val proof = fields["nativeCommandEnvironment"] as? JsonObject ?: return null
    if (proof.keys != setOf("version", "cwd", "values") ||
        (proof["version"] as? JsonPrimitive)?.intOrNull != 1
    ) {
        return null
    }
    val directory = proof.string("cwd")?.takeIf { it.isNotBlank() } ?: return null
    val values = proof["values"] as? JsonObject ?: return null
    if (values.toString().length > MAX_ENVIRONMENT_CHARS || values.keys.any { it.uppercase() !in EnvironmentKeys }) {
        return null
    }
    val environment = values.mapValues { (_, value) ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    }
    return PiCommandEnvironment(directory, NativeCommandEnvironment(environment))
}

private const val MAX_ENVIRONMENT_CHARS = 32_768
private const val MAX_REVIEW_FILES = 16
private val SimplePush = Regex("git[ \\t]+push(?:[ \\t]+(?:-u[ \\t]+)?origin[ \\t]+[A-Za-z0-9][A-Za-z0-9._/-]*)?")
private val EnvironmentKeys = setOf(
    "PATH", "HOME", "XDG_CONFIG_HOME", "USERPROFILE", "HOMEDRIVE", "HOMEPATH", "SYSTEMROOT", "WINDIR",
    "PATHEXT", "PROGRAMFILES", "PROGRAMFILES(X86)", "TMPDIR", "TMP", "TEMP",
    "GIT_HEARTBEAT_UNSAFE_OVERRIDE_PRESENT",
    "GH_HOST", "GH_REPO",
)

/** The user-facing request; null blocks a call whose target is too long to show in full. */
internal fun approvalRequest(
    id: String,
    turn: TurnId,
    call: PiApprovalCall,
    isHostTermination: Boolean,
): PermissionRequest? {
    if (call.target.length > APPROVAL_TARGET_LIMIT) {
        // Approving a partially shown command is not consent; the tool call is blocked instead.
        log.w { "Pi tool call is too long to show for approval; blocking it" }
        return null
    }
    val target = visible(call.target)
    val allow = if (isHostTermination) ALLOW_HOST_TITLE else ALLOW_TITLE
    return PermissionRequest(
        PermissionRequestId(id),
        turn,
        if (target.isBlank()) call.tool else "${call.tool}: $target",
        listOf(PermissionOption(PiApprovalAllow, allow), PermissionOption(PiApprovalDeny, DENY_TITLE)),
    )
}

/** Makes line breaks, control and bidirectional formatting characters visible in the approval text. */
private fun visible(text: String): String = buildString {
    text.forEach { char ->
        when {
            char == '\n' -> append("\\n")

            char == '\r' -> append("\\r")

            char == '\t' -> append("\\t")

            Character.isISOControl(char) || Character.getType(char) == Character.FORMAT.toInt() ->
                append("\\u").append(char.code.toString(HEX_RADIX).padStart(HEX_DIGITS, '0'))

            else -> append(char)
        }
    }
}
