package io.aequicor.heartbeat.feature.serviceasui.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.desktopdialogs.DesktopFileDialogs
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiPlatform
import io.aequicor.heartbeat.feature.serviceasui.impl.domain.UiPlatformResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/**
 * Files of surfaces on the host's own dialogs (Explorer on Windows, Finder panels on macOS). Saving writes only where
 * the user picks; revealing selects an existing file in the file manager and never opens the file itself. Where the
 * JDK cannot select, the containing folder is opened instead — never on macOS, where a folder may be an app bundle
 * that opening would launch. Paths and contents stay out of logs.
 */
@ContributesBinding(ProfileScope::class)
@Inject
internal class DesktopUiPlatform(
    private val dispatchers: DispatcherProvider,
    private val dialogs: DesktopFileDialogs,
    private val platform: PlatformInfo,
) : UiPlatform {
    private val log = Log.tag("ServiceUiPlatform")

    override suspend fun save(text: String, name: String, extensions: List<String>): UiPlatformResult {
        val bytes = text.encodeToByteArray()
        if (bytes.size > SAVE_BYTES) {
            log.w { "Surface file not saved: ${bytes.size} bytes exceed $SAVE_BYTES" }
            return UiPlatformResult.Failed
        }
        log.i { "Save a surface file" }
        return attempt("save") {
            val target = dialogs.pickSaveLocation(suggestedName = name, extensions = extensions)
            if (target == null) {
                UiPlatformResult.Cancelled
            } else {
                withContext(dispatchers.io) { File(target).writeBytes(bytes) }
                UiPlatformResult.Done()
            }
        }
    }

    override suspend fun reveal(path: String): UiPlatformResult {
        log.i { "Reveal a surface file" }
        return attempt("reveal") {
            withContext(dispatchers.io) {
                val file = File(path)
                if (file.isAbsolute && file.exists()) show(file.absoluteFile) else UiPlatformResult.Failed
            }
        }
    }

    override suspend fun pick(isFolder: Boolean, extensions: List<String>): UiPlatformResult {
        log.i { "Pick a ${if (isFolder) "folder" else "file"} for a surface" }
        return attempt("pick") {
            val picked = if (isFolder) {
                dialogs.pickDirectory()
            } else {
                dialogs.pickFiles(extensions = extensions).firstOrNull()
            }
            if (picked == null) UiPlatformResult.Cancelled else UiPlatformResult.Done(picked)
        }
    }

    private fun show(file: File): UiPlatformResult {
        val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
        val folder = file.parentFile
        return when {
            desktop == null -> UiPlatformResult.Unsupported

            desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR) -> {
                desktop.browseFileDirectory(file)
                UiPlatformResult.Done()
            }

            platform.host != HostPlatform.MacOs && folder != null && desktop.isSupported(Desktop.Action.OPEN) -> {
                desktop.open(folder)
                UiPlatformResult.Done()
            }

            else -> UiPlatformResult.Unsupported
        }
    }

    private suspend fun attempt(operation: String, block: suspend () -> UiPlatformResult): UiPlatformResult = try {
        block().also { log.i { "Surface file $operation: $it" } }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.w(e.redactedFailure()) { "Surface file $operation failed" }
        UiPlatformResult.Failed
    }

    private companion object {
        /** Largest text a surface saves at once. */
        const val SAVE_BYTES = 4 * 1024 * 1024
    }
}
