package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioDirectoryPicker
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

@Inject
@ContributesBinding(ProfileScope::class)
internal class DesktopStudioDirectoryPicker(private val dispatchers: DispatcherProvider) : StudioDirectoryPicker {
    private val log = Log.tag("StudioDirectoryPicker")
    override val isAvailable: Boolean = true

    override suspend fun pick(): String? = withContext(dispatchers.main) {
        log.i { "Open local folder chooser" }
        if (System.getProperty("os.name").startsWith("Mac")) {
            macDirectory()
        } else {
            val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.path else null
        }
    }

    private fun macDirectory(): String? {
        val property = "apple.awt.fileDialogForDirectories"
        val previous = System.getProperty(property)
        val dialog = FileDialog(null as Frame?, "", FileDialog.LOAD)
        return try {
            System.setProperty(property, "true")
            dialog.isVisible = true
            dialog.file?.let { File(dialog.directory, it).path }
        } finally {
            dialog.dispose()
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }
}
