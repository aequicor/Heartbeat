package io.aequicor.heartbeat.core.desktopdialogs

/**
 * Native operating-system dialogs for choosing files and folders: the Explorer picker on Windows
 * (the COM `IFileDialog` family) and the Finder panels on macOS (`NSOpenPanel` / `NSSavePanel`).
 * Java's own windows are not system dialogs, so features ask for a selection here instead of
 * building an AWT or Swing chooser.
 *
 * Calls are safe from any thread and serialized: the implementation captures the focused application
 * window on the UI thread, then uses a dedicated STA thread on Windows or AWT's modal loop elsewhere.
 * A dialog the user cancels returns an empty result (`null` / `emptyList()`) and never throws; a failure
 * of the native backend is logged and rethrown. Coroutine cancellation waits for an already visible
 * dialog to be dismissed so native resources and modal ownership are released before returning.
 *
 * Bound on Desktop (JVM) only — Android and iOS present their own system pickers inside features.
 */
public interface DesktopFileDialogs {
    /** Folder chooser; the selected directory path, or null when the user cancels. Contents are not read. */
    public suspend fun pickDirectory(title: String? = null): String?

    /**
     * File chooser limited to [extensions] — bare names such as `png`, an empty list accepts every file;
     * returns the selected absolute paths, empty when the user cancels.
     */
    public suspend fun pickFiles(
        title: String? = null,
        extensions: List<String> = emptyList(),
        allowMultiple: Boolean = false,
    ): List<String>

    /**
     * Save chooser with [suggestedName] pre-filled and overwrite confirmation. Windows uses [extensions]
     * as its type mask and default extension; AWT panels infer the type from [suggestedName]. Returns the
     * chosen absolute path, or null when the user cancels. Nothing is written here.
     */
    public suspend fun pickSaveLocation(
        title: String? = null,
        suggestedName: String? = null,
        extensions: List<String> = emptyList(),
    ): String?
}
