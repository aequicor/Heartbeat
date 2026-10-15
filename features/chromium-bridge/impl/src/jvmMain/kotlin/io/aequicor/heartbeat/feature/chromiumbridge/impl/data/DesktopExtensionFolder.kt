package io.aequicor.heartbeat.feature.chromiumbridge.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.chromiumbridge.api.ExtensionPreparation
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.ExtensionFolder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The extension folder: an installed build prepares a pinned component on demand; a build run from a
 * checkout uses the repository's folder, found by walking up from the working directory, so edits take effect on
 * the browser's «Reload».
 */
internal class DesktopExtensionFolder(
    private val io: CoroutineDispatcher,
    private val property: (String) -> String? = System::getProperty,
    private val installer: DesktopExtensionInstaller? = null,
) : ExtensionFolder {
    private val log = Log.tag("ExtensionFolder")

    override suspend fun locate(): String? = withContext(io) {
        val isInstalled = property(PACKAGED_LAUNCHER) != null
        val folder = (if (isInstalled) bundled() else checkout() ?: bundled()) ?: installer?.locate()
        log.i { "Extension folder located isFound=${folder != null} isInstalled=$isInstalled" }
        folder?.toString()
    }

    override suspend fun prepare(progress: suspend (ExtensionPreparation) -> Unit): String =
        locate() ?: checkNotNull(installer) { "Extension installer is unavailable" }.prepare(progress).toString()

    override suspend fun reveal(folder: String) = withContext(io) {
        try {
            Desktop.getDesktop().open(Path.of(folder).toFile())
            log.i { "Extension folder revealed" }
        } catch (e: IOException) {
            log.w(e) { "Extension folder could not be opened" }
        } catch (e: UnsupportedOperationException) {
            log.w(e) { "No file manager to open the extension folder" }
        }
    }

    private fun bundled(): Path? = property(RESOURCES)?.let { Path.of(it).resolve(FOLDER) }?.takeIf(::isExtension)

    private fun checkout(): Path? = property(WORKING_DIRECTORY)
        ?.let { generateSequence(Path.of(it).toAbsolutePath()) { path -> path.parent } }
        ?.map { it.resolve(FOLDER) }
        ?.firstOrNull(::isExtension)

    private fun isExtension(folder: Path): Boolean = Files.isRegularFile(folder.resolve("manifest.json"))

    private companion object {
        const val FOLDER = "chromium-plugin"
        const val RESOURCES = "compose.application.resources.dir"
        const val PACKAGED_LAUNCHER = "jpackage.app-path"
        const val WORKING_DIRECTORY = "user.dir"
    }
}
