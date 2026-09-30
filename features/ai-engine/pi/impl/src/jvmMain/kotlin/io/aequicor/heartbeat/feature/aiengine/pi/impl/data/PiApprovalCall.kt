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

/**
 * A tool call awaiting approval (`resources/pi/heartbeat-approval.ts`): its name, the command or path shown to the
 * user and, for `edit` / `write`, the absolute [path] the extension pinned in the call; null when it was not pinned.
 */
internal data class PiApprovalCall(val tool: String, val target: String, val path: String? = null)

/** Option that lets Pi run an approved tool; any other answer blocks it. */
internal val PiApprovalAllow = PermissionOptionId("allow")
private val PiApprovalDeny = PermissionOptionId("deny")

// Pi's built-in file mutation tools; commands and extension tools are never file edits.
internal val EditTools = setOf("edit", "write")

// The permission panel shows the title and the options only, so a cost the user must know is stated in them.
private const val ALLOW_TITLE = "Разрешить"
private const val DENY_TITLE = "Запретить"
private const val ALLOW_HOST_TITLE = "Завершить Heartbeat и выполнить"
private const val GIT_DIRECTORY = ".git"
private const val FILE_SCHEME = "file:"

// Spaces Pi replaces with a plain space before resolving a tool path; the extension never pins such a path.
private val UnicodeSpaces = Regex("[\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000]")
private const val APPROVAL_TARGET_LIMIT = 4_000
private const val HEX_RADIX = 16
private const val HEX_DIGITS = 4
private val log = Log.tag("PiApproval")

/**
 * Whether this level answers the approval of [call] without the user. A file edit counts only when its path was
 * pinned and lies inside [workspace], the working directory of the process. Blocking IO: resolves symbolic links.
 */
internal fun TrustLevel.covers(call: PiApprovalCall, workspace: Path?): Boolean = when (this) {
    TrustLevel.Ask -> false

    TrustLevel.AutoEdits -> {
        val path = call.path
        call.tool in EditTools && workspace != null && path != null && isWorkspaceEdit(path, workspace)
    }

    TrustLevel.Full -> true
}

/**
 * Whether this level answers the approval of [call] on its own. Trust never extends to a command that ends the
 * host ([terminatesHost]): the processes that forked the application would take the running turn down with them,
 * so such a call always waits for the user.
 */
internal fun TrustLevel.answers(call: PiApprovalCall, workspace: Path?): Boolean {
    if (!terminatesHost(call)) return covers(call, workspace)
    log.w { "Pi tool call can stop the host process; only the user decides: ${call.tool}" }
    return false
}

/**
 * Whether the pinned absolute [path] lies inside [workspace] and outside its `.git` directory, whose hooks and config
 * run commands. Pi writes a pinned path unchanged; anything relative or in a form Pi rewrites is never an edit here.
 */
internal fun isWorkspaceEdit(path: String, workspace: Path): Boolean {
    val isRewritten = path.startsWith("~") || path.startsWith("@") || path.startsWith(FILE_SCHEME, ignoreCase = true)
    if (path.isBlank() || isRewritten || UnicodeSpaces.containsMatchIn(path)) return false
    return try {
        val target = Path.of(path)
        if (!target.isAbsolute) return false
        val root = workspace.toRealPath()
        val real = realPath(target.normalize())
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
    // Field names must match the request built in resources/pi/heartbeat-approval.ts.
    return PiApprovalCall(tool, fields.string("target").orEmpty(), fields.string("path"))
}

/** The user-facing request; null blocks a call whose target is too long to show in full. */
internal fun approvalRequest(id: String, turn: TurnId, call: PiApprovalCall): PermissionRequest? {
    if (call.target.length > APPROVAL_TARGET_LIMIT) {
        // Approving a partially shown command is not consent; the tool call is blocked instead.
        log.w { "Pi tool call is too long to show for approval; blocking it" }
        return null
    }
    val target = visible(call.target)
    val allow = if (terminatesHost(call)) ALLOW_HOST_TITLE else ALLOW_TITLE
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
