package io.aequicor.heartbeat.feature.attachments.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.desktopdialogs.DesktopFileDialogs
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.attachments.api.AttachmentFailure
import io.aequicor.heartbeat.feature.attachments.api.AttachmentInput
import io.aequicor.heartbeat.feature.attachments.impl.domain.AttachmentNativeActions
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/**
 * Attachment actions on the host's own dialogs (Explorer on Windows, Finder panels on macOS): the chooser
 * offers exactly the formats the model accepts, and every imported file still undergoes the shared
 * MIME/signature/size checks before the machine publishes it.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class DesktopAttachmentActions(
    private val dispatchers: DispatcherProvider,
    private val dialogs: DesktopFileDialogs,
) : AttachmentNativeActions {
    private val log = Log.tag("NativeAttachments")

    override suspend fun choose(host: Any?, support: PromptInputSupport): List<AttachmentInput>? {
        log.i { "Choose attachment files" }
        val extensions = attachmentExtensions(support)
        if (extensions.isEmpty()) throw AttachmentValidationException(AttachmentFailure.Unsupported)
        val selected = dialogs.pickFiles(extensions = extensions, allowMultiple = true)
        if (selected.isEmpty()) return null
        return selected.map { path ->
            val name = File(path).name
            if (!support.accepts(attachmentMediaType(name))) {
                throw AttachmentValidationException(AttachmentFailure.Unsupported)
            }
            AttachmentInput.File(path, name)
        }
    }

    override suspend fun open(host: Any?, resource: ResolvedResource): Unit = withContext(dispatchers.io) {
        log.i { "Open saved attachment with system application" }
        Desktop.getDesktop().open(File(requireNotNull(resource.localPath)))
    }

    override suspend fun export(host: Any?, resource: ResolvedResource): Boolean {
        log.i { "Export saved attachment" }
        val target = dialogs.pickSaveLocation(
            suggestedName = resource.name,
            extensions = saveExtensions(resource.name),
        ) ?: return false
        withContext(dispatchers.io) { File(target).outputStream().use { it.write(resource.bytes) } }
        return true
    }
}
