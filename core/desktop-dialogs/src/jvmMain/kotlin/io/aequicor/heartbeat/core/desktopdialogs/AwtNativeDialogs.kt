package io.aequicor.heartbeat.core.desktopdialogs

import java.awt.Dialog
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.FilenameFilter
import javax.swing.JFileChooser

/**
 * AWT's [FileDialog] presents the operating system's own panel — `NSOpenPanel` / `NSSavePanel` on macOS,
 * the GTK chooser on Linux — so it is the native backend wherever the Explorer COM dialog does not apply.
 *
 * AWT has no folder dialog: on macOS Apple's `apple.awt.fileDialogForDirectories` property turns the open
 * panel into a folder chooser ([directoriesThroughAwtProperty]), on other hosts the Swing chooser remains
 * as a development fallback (Linux is not a shipping platform).
 *
 * [pickSaveLocation] ignores the requested extensions: the panel takes the file type from the suggested
 * name, and filtering a save panel by name would block names the user still has to complete.
 */
internal class AwtNativeDialogs(internal val directoriesThroughAwtProperty: Boolean) : NativeDialogs {

    override suspend fun pickDirectory(title: String?): String? =
        if (directoriesThroughAwtProperty) macDirectory(title) else swingDirectory(title)

    override suspend fun pickFiles(title: String?, extensions: List<String>, allowMultiple: Boolean): List<String> =
        show(
            title = title,
            mode = FileDialog.LOAD,
            configure = {
                isMultipleMode = allowMultiple
                filenameFilter = FilenameFilter { _, name -> matchesExtensions(name, extensions) }
            },
        ) { dialog ->
            if (allowMultiple) dialog.files.map { it.absolutePath } else listOfNotNull(dialog.selectedPath())
        }

    override suspend fun pickSaveLocation(title: String?, suggestedName: String?, extensions: List<String>): String? =
        show(title = title, mode = FileDialog.SAVE, configure = { file = suggestedName }) { dialog ->
            dialog.selectedPath()
        }

    /** The open panel chooses folders only while Apple's property is set; the peer reads it when shown. */
    private fun macDirectory(title: String?): String? {
        val property = "apple.awt.fileDialogForDirectories"
        val previous = System.getProperty(property)
        System.setProperty(property, "true")
        return try {
            show(title = title, mode = FileDialog.LOAD, configure = {}) { dialog -> dialog.selectedPath() }
        } finally {
            if (previous == null) System.clearProperty(property) else System.setProperty(property, previous)
        }
    }

    private fun swingDirectory(title: String?): String? {
        val chooser = JFileChooser().apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            if (title != null) dialogTitle = title
        }
        val approved = chooser.showOpenDialog(focusedWindow())
        return if (approved == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
    }

    private fun <T> show(
        title: String?,
        mode: Int,
        configure: FileDialog.() -> Unit,
        selection: (FileDialog) -> T,
    ): T {
        val dialog = createFileDialog(title, mode)
        try {
            dialog.configure()
            // Modal and blocking: it returns on the event thread once the user closes the panel.
            dialog.isVisible = true
            return selection(dialog)
        } finally {
            dialog.dispose()
        }
    }

    private fun createFileDialog(title: String?, mode: Int): FileDialog = when (val owner = focusedWindow()) {
        is Frame -> FileDialog(owner, title, mode)
        is Dialog -> FileDialog(owner, title, mode)
        else -> FileDialog(null as Frame?, title, mode)
    }
}

/** The single selection of an AWT dialog; null when the user cancelled. */
private fun FileDialog.selectedPath(): String? = file?.let { name -> File(directory, name).absolutePath }
