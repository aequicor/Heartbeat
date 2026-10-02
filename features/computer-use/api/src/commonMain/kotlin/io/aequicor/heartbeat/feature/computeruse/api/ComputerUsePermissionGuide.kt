package io.aequicor.heartbeat.feature.computeruse.api

import kotlinx.coroutines.flow.StateFlow

/** Operating-system permission the user grants in the system settings, not in Heartbeat. */
public enum class ComputerUsePermission(
    /** Host capability blocker removed when the user grants this permission. */
    public val blocker: ComputerUseBlocker,
) {
    /** macOS Screen Recording: capture of the desktop and of other applications' windows. */
    ScreenRecording(ComputerUseBlocker.ScreenRecordingPermission),

    /** macOS Accessibility: mouse and keyboard input into other applications. */
    Accessibility(ComputerUseBlocker.AccessibilityPermission),
}

/**
 * App-owned guidance for granting a [ComputerUsePermission]. While [guide] names a permission, its system settings
 * page is open and the desktop host shows a small panel with the application tile the user drags into that page's
 * list. The feature shows and hides the guide; it ends once the permission is granted or after a timeout. The host
 * only renders it and reports the user closing the panel through [dismiss].
 */
public interface ComputerUsePermissionGuide {
    /** The permission being granted right now; `null` when no panel is shown. */
    public val guide: StateFlow<ComputerUsePermission?>

    /**
     * Closes the panel at the user's request; the feature keeps checking for the grant in the background.
     * Called on the main thread, where the feature also changes the shown permission.
     */
    public fun dismiss()
}
