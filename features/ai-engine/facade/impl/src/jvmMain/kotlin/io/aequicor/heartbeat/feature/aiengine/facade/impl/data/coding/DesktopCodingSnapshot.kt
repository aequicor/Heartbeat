package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolApproval
import io.aequicor.heartbeat.feature.aiengine.facade.api.AgentToolContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okio.ByteString.Companion.encodeUtf8
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** Complete immutable evidence; diagnostics never include arguments, paths or file contents. */
internal data class CodingOperationSnapshot(
    val approval: AgentToolApproval,
    val change: String,
    val root: ProjectRoot,
    val arguments: JsonObject,
) {
    override fun toString(): String = "CodingOperationSnapshot(***)"
}

/** Immutable operation binding; content hashes detect edits while a permission prompt is open. */
internal class DesktopCodingSnapshot(
    private val workspaces: LocalWorkspaces,
    private val dispatchers: DispatcherProvider,
) {
    private val log = Log.tag("DesktopCodingSnapshot")

    suspend fun snapshot(
        context: AgentToolContext,
        original: AgentToolApproval,
        arguments: JsonObject,
    ): CodingOperationSnapshot? = try {
        val directory = if (workspaces.isAvailable) context.workspace?.let { workspaces.resolve(it) } else null
        if (directory == null) {
            null
        } else {
            withContext(dispatchers.io) {
                val root = ProjectRoot(Path.of(directory))
                capture(context, original, arguments, root)
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        log.w(error.redactedFailure()) { "Project safety snapshot unavailable" }
        null
    }
}

private fun capture(
    context: AgentToolContext,
    original: AgentToolApproval,
    arguments: JsonObject,
    root: ProjectRoot,
): CodingOperationSnapshot {
    val encoded = arguments.toString()
    val supplied = Json.parseToJsonElement(encoded).jsonObject
    val target = if (original.name == "run_command") null else root.canonical(supplied.argText("path"))
    val destination = if (original.name == "move_file") root.canonical(supplied.argText("destination")) else null
    val execution = buildJsonObject {
        supplied.forEach { (key, value) -> put(key, value) }
        target?.let { put("path", it.toString()) }
        destination?.let { put("destination", it.toString()) }
    }
    val change = buildJsonObject {
        put("caller", Json.encodeToJsonElement(context.session))
        put("workspace", context.workspace?.value)
        put("turn", context.turn.value)
        put("request", context.request?.value)
        put("call", context.callId?.value)
        put("target", Json.encodeToJsonElement(context.target))
        put("trust", context.trust.name)
        put("decisionMode", context.decisionMode.name)
        context.reviewTarget?.let { put("reviewTarget", Json.encodeToJsonElement(it)) }
        put("operation", original.name)
        put("arguments", supplied)
        put("executionArguments", execution)
        put("root", root.path.toString())
        put("rootIdentity", fileIdentity(root.path))
        put("shell", if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "PowerShell" else "/bin/sh")
        if (context.isActionAnalysisRequired) {
            val shell = controlledCodingShell(System.getProperty("os.name").orEmpty().startsWith("Windows"))
            put("controlledExecutable", shell.executable)
            put("controlledEnvironment", shell.environmentBinding)
        }
        if (target != null) put("previousFile", fileEvidence(target))
        if (destination != null) put("destinationFile", fileEvidence(destination))
    }.toString()
    val key = change.encodeUtf8().sha256().hex()
    return CodingOperationSnapshot(original.copy(binding = "coding-operation:$key"), change, root, execution)
}

private fun fileEvidence(path: Path): JsonObject = buildJsonObject {
    val isExisting = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
    put("exists", isExisting)
    if (isExisting) {
        require(Files.isRegularFile(path)) { "The target must be a regular text file" }
        val before = fileIdentity(path)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(8192)
            var read = input.read(buffer)
            while (read >= 0) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
        check(before == fileIdentity(path)) { "File changed while capturing evidence" }
        put("identity", before)
        put("contentHash", hash)
    }
}

private fun fileIdentity(path: Path): JsonObject {
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
    return buildJsonObject {
        put("key", attributes.fileKey()?.toString())
        put("created", attributes.creationTime().toString())
        // Directory timestamps change for unrelated entries and are not part of workspace identity.
        if (attributes.isRegularFile) {
            put("modified", attributes.lastModifiedTime().toString())
            put("size", attributes.size())
        }
    }
}
