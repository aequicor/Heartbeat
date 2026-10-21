package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.coding

import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionAnalysis
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionEffect
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionEvidence
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.ActionResourceKind
import kotlinx.serialization.json.JsonObject
import okio.ByteString.Companion.encodeUtf8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** These contracts belong to the concrete coding owner; other contributions cannot gain them by reusing a name. */
internal fun codingFileAnalysis(
    root: ProjectRoot,
    name: String,
    arguments: JsonObject,
    mutationBinding: String?,
): ActionAnalysis {
    val effects = when (name) {
        "read_file", "list_dir", "glob", "grep" -> setOf(ActionEffect.Read)
        "write_file" -> setOf(ActionEffect.Create, ActionEffect.Modify)
        "edit_file" -> setOf(ActionEffect.Modify)
        "delete_file" -> setOf(ActionEffect.Delete)
        "move_file" -> setOf(ActionEffect.Create, ActionEffect.Delete)
        else -> return ActionAnalysis.Unknown("Unknown coding operation")
    }
    if (effects != setOf(ActionEffect.Read) && mutationBinding == null) {
        return ActionAnalysis.Unknown("File mutation has no complete snapshot")
    }
    val paths = buildList {
        add(root.canonical(arguments.argText("path").ifBlank { "." }))
        if (name == "move_file") add(root.canonical(arguments.argText("destination")))
    }
    val binding = mutationBinding ?: buildString {
        append(name).append(arguments).append(fileIdentity(root.path))
        paths.forEach { append(fileIdentity(it)) }
    }.encodeUtf8().sha256().hex()
    return ActionAnalysis.Known(
        ActionEvidence(
            "Hosted project operation $name: $arguments",
            paths.map { ActionResource(ActionResourceKind.FileSystem, it.toString(), true) },
            effects,
            binding,
        ),
    )
}

private fun fileIdentity(path: Path): String {
    if (!Files.exists(path)) return "$path:absent"
    val stat = Files.readAttributes(path, BasicFileAttributes::class.java)
    return "${path.toRealPath()}:${stat.fileKey()}:${stat.creationTime()}" +
        if (stat.isRegularFile) ":${stat.size()}:${stat.lastModifiedTime()}" else ""
}
