package io.aequicor.heartbeat.feature.appupdate.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppUpdateException
import io.aequicor.heartbeat.feature.appupdate.impl.domain.updateFailure
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.name

/**
 * Updates a macOS app bundle from a release disk image: the image is mounted read-only, its single `.app` is copied
 * next to the download and checked, and the helper swaps it in once the app has quit. A bundle the user cannot
 * replace (another owner, a read-only volume, App Translocation of a bundle never moved out of Downloads) is
 * installed by hand.
 */
@Inject
internal class MacBundleUpdate(private val commands: CommandRunner) {
    private val log = Log.tag("MacBundleUpdate")

    /** The `.app` bundle of the launcher `<name>.app/Contents/MacOS/<launcher>`, or null outside a bundle. */
    fun bundleOf(launcher: Path): Path? {
        val macOs = launcher.parent ?: return null
        val contents = macOs.parent ?: return null
        val bundle = contents.parent ?: return null
        val isBundle = macOs.name == "MacOS" && contents.name == "Contents" && bundle.name.endsWith(APP_SUFFIX)
        return bundle.takeIf { isBundle }
    }

    /** Fails with [AppUpdateFailure.ManualInstallRequired] unless this user can move [bundle] aside and replace it. */
    fun requireReplaceable(bundle: Path) {
        if (!Files.isWritable(bundle) || !Files.isWritable(bundle.parent)) {
            updateFailure(
                AppUpdateFailure.ManualInstallRequired,
                "the installed bundle is not replaceable by this user",
            )
        }
    }

    /** Copies the bundle of the disk [image] of [version] into [directory] and returns the copy. */
    suspend fun prepare(image: Path, version: String, directory: Path): Path {
        val mount = files { Files.createDirectories(directory.resolve("mount")) }
        val staged = directory.resolve("staged$APP_SUFFIX")
        val attach = listOf(
            "attach",
            "-nobrowse",
            "-readonly",
            "-noautoopen",
            "-noverify",
            "-mountpoint",
            "$mount",
            "$image",
        )
        try {
            // Cancellation may arrive after hdiutil mounted the image but before its result reaches us.
            commands.run(listOf(HDIUTIL) + attach)
            val app = files {
                Files.list(mount).use { entries ->
                    entries.filter { it.name.endsWith(APP_SUFFIX) && Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                        .toList()
                }
            }.singleOrNull() ?: updateFailure(
                AppUpdateFailure.InvalidRelease,
                "the release image holds no single app bundle",
            )
            commands.run(listOf(DITTO, "$app", "$staged"))
        } finally {
            // Also after cancellation: an image left attached keeps a mount until logout.
            withContext(NonCancellable) { detach(mount) }
        }
        val bundled = files { bundleVersion(staged) }
        if (bundled != version) {
            updateFailure(
                AppUpdateFailure.InvalidRelease,
                "the release image holds another version expected=$version actual=${bundled.orEmpty()}",
            )
        }
        log.i { "update bundle prepared version=$version" }
        return staged
    }

    /** The helper command that replaces [bundle] with [staged] after process [pid] quits and opens it. */
    fun helperCommand(script: Path, pid: Long, bundle: Path, staged: Path): List<String> =
        listOf(SHELL, "$script", "$pid", "$bundle", "$staged")

    /** A disk image left attached only costs a mount until logout: the prepared copy is still good. */
    private suspend fun detach(mount: Path) {
        try {
            commands.run(listOf(HDIUTIL, "detach", "$mount", "-force"))
        } catch (e: AppUpdateException) {
            log.w(e) { "the release image could not be detached" }
        }
    }

    // The file system message names the user's paths: only the kind of failure travels on, so it is not the cause.
    @Suppress("SwallowedException")
    private fun <T> files(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw AppUpdateException(
            AppUpdateFailure.InstallFailed,
            "the update bundle could not be prepared",
            e.withoutDetails(),
        )
    }

    private fun bundleVersion(bundle: Path): String? {
        val plist = Files.readString(bundle.resolve("Contents/Info.plist"))
        return ShortVersion.find(plist)?.groupValues?.get(1)?.trim()
    }

    private companion object {
        const val APP_SUFFIX = ".app"
        const val HDIUTIL = "/usr/bin/hdiutil"
        const val DITTO = "/usr/bin/ditto"
        const val SHELL = "/bin/sh"
        val ShortVersion = Regex("<key>CFBundleShortVersionString</key>\\s*<string>([^<]*)</string>")
    }
}
