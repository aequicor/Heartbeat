package io.aequicor.heartbeat.feature.researchchat.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.researchchat.api.ResearchResourceKind
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ImportedResearchFile
import io.aequicor.heartbeat.feature.researchchat.impl.domain.ResearchFileImporter
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.io.encoding.Base64

@ContributesBinding(ProfileScope::class)
@Inject
internal class DesktopResearchFileImporter(private val dispatchers: DispatcherProvider) : ResearchFileImporter {
    private val log = Log.tag("ResearchFileImport")
    override val isAvailable: Boolean = true

    override suspend fun pick(): ImportedResearchFile? {
        log.i { "Choose research attachment" }
        val file = withContext(dispatchers.main) {
            val dialog = FileDialog(null as Frame?, "", FileDialog.LOAD)
            try {
                dialog.setFilenameFilter { _, name -> name.substringAfterLast('.').lowercase() in mediaTypes }
                dialog.isVisible = true
                dialog.file?.let { File(dialog.directory, it) }
            } finally {
                dialog.dispose()
            }
        } ?: return null
        return withContext(dispatchers.io) {
            log.d { "Read selected research attachment" }
            val mediaType = requireNotNull(mediaTypes[file.extension.lowercase()]) { "Unsupported attachment format" }
            val bytes = file.inputStream().use { it.readNBytes(MAX_FILE_BYTES + 1) }
            encodeResearchFile(file.name, mediaType, bytes)
        }
    }
}

/** Bounded conversion is separate from the native picker so malformed/oversize files can be tested. */
internal fun encodeResearchFile(name: String, mediaType: String, bytes: ByteArray): ImportedResearchFile {
    require(bytes.isNotEmpty() && bytes.size <= MAX_FILE_BYTES) { "Attachment must be between 1 byte and 10 MiB" }
    require(mediaType in mediaTypes.values) { "Unsupported attachment format" }
    val isText = mediaType.startsWith("text/")
    val value = if (isText) {
        bytes.decodeToString(throwOnInvalidSequence = true)
    } else {
        "data:$mediaType;base64,${Base64.encode(bytes)}"
    }
    require(value.isNotBlank()) { "Empty document" }
    return ImportedResearchFile(
        title = name,
        value = value,
        kind = if (mediaType.startsWith("image/")) ResearchResourceKind.Image else ResearchResourceKind.Document,
        mediaType = mediaType,
    )
}

private const val MAX_FILE_BYTES = 10 * 1024 * 1024
private val mediaTypes = mapOf(
    "txt" to "text/plain", "md" to "text/markdown", "markdown" to "text/markdown",
    "pdf" to "application/pdf", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
    "webp" to "image/webp", "gif" to "image/gif",
)
