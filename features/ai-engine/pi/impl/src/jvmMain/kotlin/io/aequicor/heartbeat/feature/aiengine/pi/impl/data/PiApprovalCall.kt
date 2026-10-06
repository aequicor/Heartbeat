package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A tool call awaiting approval (`resources/pi/heartbeat-approval.ts`): its name, the command or path shown to the
 * user and, for `edit` / `write`, the absolute [path] the extension pinned in the call; null when it was not pinned.
 */
internal data class PiApprovalCall(val tool: String, val target: String, val path: String? = null) {
    override fun toString(): String = "PiApprovalCall"
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
private const val HEX_RADIX = 16
private const val HEX_DIGITS = 4
private val log = Log.tag("PiApproval")

/** Parses the approval message; null when it is malformed, and the request stays blocked. */
internal fun approvalCall(message: String?): PiApprovalCall? {
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
    return PiApprovalCall(tool, fields.string("target").orEmpty(), fields.string("path"))
}

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
