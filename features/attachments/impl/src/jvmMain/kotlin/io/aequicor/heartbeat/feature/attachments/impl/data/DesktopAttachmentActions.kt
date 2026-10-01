package io.aequicor.heartbeat.feature.attachments.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.PromptInputSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResolvedResource
import io.aequicor.heartbeat.feature.attachments.api.AttachmentInput
import io.aequicor.heartbeat.feature.attachments.impl.domain.AttachmentNativeActions
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@ContributesBinding(ProfileScope::class)
@Inject
internal class DesktopAttachmentActions(private val dispatchers: DispatcherProvider) : AttachmentNativeActions {
    private val log = Log.tag("NativeAttachments")

    override suspend fun choose(host: Any?, support: PromptInputSupport): List<AttachmentInput>? = withContext(
        dispatchers.main,
    ) {
        log.i { "Choose attachment files" }
        if (System.getProperty("os.name").startsWith("windows", ignoreCase = true)) {
            return@withContext chooseWindowsAttachments(support)
        }
        val dialog = FileDialog(null as Frame?, "", FileDialog.LOAD)
        try {
            dialog.isMultipleMode = true
            dialog.setFilenameFilter { _, name -> support.accepts(attachmentMediaType(name)) }
            dialog.isVisible = true
            dialog.files.takeIf { it.isNotEmpty() }?.map { AttachmentInput.File(it.absolutePath, it.name) }
        } finally {
            dialog.dispose()
        }
    }

    override suspend fun open(host: Any?, resource: ResolvedResource): Unit = withContext(dispatchers.io) {
        log.i { "Open saved attachment with system application" }
        Desktop.getDesktop().open(File(requireNotNull(resource.localPath)))
    }

    override suspend fun export(host: Any?, resource: ResolvedResource): Boolean {
        log.i { "Export saved attachment" }
        val target = withContext(dispatchers.main) {
            val dialog = FileDialog(null as Frame?, "", FileDialog.SAVE)
            try {
                dialog.file = resource.name
                dialog.isVisible = true
                dialog.file?.let { File(dialog.directory, it) }
            } finally {
                dialog.dispose()
            }
        } ?: return false
        withContext(dispatchers.io) { target.outputStream().use { it.write(resource.bytes) } }
        return true
    }
}
