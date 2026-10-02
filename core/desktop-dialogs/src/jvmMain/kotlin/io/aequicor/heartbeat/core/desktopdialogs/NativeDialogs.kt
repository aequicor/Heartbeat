package io.aequicor.heartbeat.core.desktopdialogs

import io.aequicor.heartbeat.core.common.HostPlatform

/**
 * One host's dialog implementation. Every method starts on the AWT event thread and reports
 * a cancelled dialog as `null` / an empty list. Windows captures the owner there, then suspends while
 * a dedicated STA thread drives the dialog; AWT panels run their own nested event loop.
 */
internal interface NativeDialogs {
    suspend fun pickDirectory(title: String?): String?

    suspend fun pickFiles(title: String?, extensions: List<String>, allowMultiple: Boolean): List<String>

    suspend fun pickSaveLocation(title: String?, suggestedName: String?, extensions: List<String>): String?
}

/**
 * Dialogs of the host operating system. Windows shows the Explorer COM dialog, macOS shows the Finder
 * panels through AWT — whose open panel becomes a folder chooser only with an Apple system property.
 * Linux is not a shipping host: GTK has no AWT folder dialog, so Swing remains its development fallback.
 */
internal fun nativeDialogsFor(host: HostPlatform): NativeDialogs = when (host) {
    HostPlatform.Windows -> WindowsExplorerDialogs()

    HostPlatform.MacOs -> AwtNativeDialogs(directoriesThroughAwtProperty = true)

    HostPlatform.Linux, HostPlatform.Android, HostPlatform.Ios ->
        AwtNativeDialogs(directoriesThroughAwtProperty = false)
}
