package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TransportFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.TrustLevel
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

/** A tool call awaiting approval (`resources/pi/heartbeat-approval.ts`): its name and the command or path. */
internal data class PiApprovalCall(val tool: String, val target: String)

/** Option that lets Pi run an approved tool; any other answer blocks it. */
internal val PiApprovalAllow = PermissionOptionId("allow")
private val PiApprovalDeny = PermissionOptionId("deny")

// Pi's built-in file mutation tools; commands and extension tools are never file edits.
private val EditTools = setOf("edit", "write")
private const val GIT_DIRECTORY = ".git"
private const val APPROVAL_TARGET_LIMIT = 4_000
private const val HEX_RADIX = 16
private const val HEX_DIGITS = 4
private val log = Log.tag("PiApproval")

/**
 * Whether this level answers the approval of [call] without the user. A file edit counts only inside [workspace],
 * the working directory of the process. Blocking IO: resolves symbolic links of the target.
 */
internal fun TrustLevel.covers(call: PiApprovalCall, workspace: Path?): Boolean = when (this) {
    TrustLevel.Ask -> false
    TrustLevel.AutoEdits -> call.tool in EditTools && workspace != null && isWorkspaceEdit(call.target, workspace)
    TrustLevel.Full -> true
}

/**
 * Whether Pi writes [target] inside [workspace] and outside its `.git` directory, whose hooks and config run
 * commands. Pi drops a leading `@` and expands `~`; a home-relative path is never a workspace edit.
 */
internal fun isWorkspaceEdit(target: String, workspace: Path): Boolean {
    val path = target.removePrefix("@")
    if (path.isBlank() || path.startsWith("~")) return false
    return try {
        val root = workspace.toRealPath()
        val real = realPath(root.resolve(path).normalize())
        real.startsWith(root) && root.relativize(real).none { it.toString().equals(GIT_DIRECTORY, ignoreCase = true) }
    } catch (e: IOException) {
        // A dangling link or an unreadable parent: the user decides instead.
        log.w(e) { "Pi edit target could not be resolved; asking the user" }
        false
    } catch (e: InvalidPathException) {
        log.w(e) { "Pi edit target is not a valid path; asking the user" }
        false
    } catch (e: SecurityException) {
        log.w(e) { "Pi edit target could not be inspected; asking the user" }
        false
    }
}

/** Real path of [path]: its nearest existing ancestor with links resolved, followed by the missing names. */
private fun realPath(path: Path): Path {
    var existing = path
    while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.parent ?: return path
    return existing.toRealPath().resolve(existing.relativize(path)).normalize()
}

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
    return PiApprovalCall(tool, fields.string("target").orEmpty())
}

/** The user-facing request; null blocks a call whose target is too long to show in full. */
internal fun approvalRequest(id: String, turn: TurnId, call: PiApprovalCall): PermissionRequest? {
    if (call.target.length > APPROVAL_TARGET_LIMIT) {
        // Approving a partially shown command is not consent; the tool call is blocked instead.
        log.w { "Pi tool call is too long to show for approval; blocking it" }
        return null
    }
    val target = visible(call.target)
    return PermissionRequest(
        PermissionRequestId(id),
        turn,
        if (target.isBlank()) call.tool else "${call.tool}: $target",
        listOf(PermissionOption(PiApprovalAllow, "Разрешить"), PermissionOption(PiApprovalDeny, "Запретить")),
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
