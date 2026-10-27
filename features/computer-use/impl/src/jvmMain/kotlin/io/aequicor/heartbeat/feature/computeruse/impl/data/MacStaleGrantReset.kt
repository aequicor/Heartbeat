package io.aequicor.heartbeat.feature.computeruse.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUsePermission
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Clears a privacy entry macOS keeps for an earlier build of this application.
 *
 * Ad-hoc signed builds are identified by their code hash, so after an update the old entry stays in the list with
 * its switch on while the probe reports the permission as missing. Resetting that entry before the guide opens
 * System Settings lets the user add the current build instead of toggling a row that no longer applies. Only an
 * installed bundle is reset: a development launch is attributed to the IDE or terminal, whose entry is not ours.
 */
internal object MacStaleGrantReset {
    private val log = Log.tag("MacStaleGrantReset")

    /** The `.app` directory when [command] is the launcher inside it (`X.app/Contents/MacOS/X`); otherwise `null`. */
    fun installedBundle(command: String?): File? {
        val executable = command?.let(::File) ?: return null
        val macOsDir = executable.parentFile ?: return null
        val contents = macOsDir.parentFile ?: return null
        val bundle = contents.parentFile ?: return null
        val isLauncher = macOsDir.name == MAC_OS_DIR &&
            contents.name == CONTENTS_DIR &&
            bundle.name.endsWith(APP_SUFFIX)
        return bundle.takeIf { isLauncher }
    }

    /** `CFBundleIdentifier` of an XML `Info.plist`; `null` for a binary or malformed one. */
    fun bundleId(plist: String): String? =
        BUNDLE_ID.find(plist)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** `tccutil` service name of [permission]. */
    fun service(permission: ComputerUsePermission): String = when (permission) {
        ComputerUsePermission.ScreenRecording -> "ScreenCapture"
        ComputerUsePermission.Accessibility -> "Accessibility"
    }

    /** The reset command for the installed bundle that runs this process; `null` outside such a bundle. */
    fun command(permission: ComputerUsePermission, executable: String? = currentExecutable()): List<String>? {
        val bundle = installedBundle(executable) ?: return null
        val id = try {
            bundleId(File(bundle, "Contents/Info.plist").readText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "bundle identifier could not be read" }
            null
        }
        if (id == null) {
            log.i { "stale grant reset skipped: bundle identifier is unknown" }
            return null
        }
        return listOf(TCCUTIL, "reset", service(permission), id)
    }

    private fun currentExecutable(): String? = ProcessHandle.current().info().command().orElse(null)

    private val BUNDLE_ID = Regex("<key>CFBundleIdentifier</key>\\s*<string>([^<]+)</string>")
    private const val MAC_OS_DIR = "MacOS"
    private const val CONTENTS_DIR = "Contents"
    private const val APP_SUFFIX = ".app"
    const val TCCUTIL = "tccutil"
}
