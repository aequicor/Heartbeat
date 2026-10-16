package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.core.datastore.StorageOwner
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigDocumentId
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ConfigFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportPreview
import io.aequicor.heartbeat.feature.aiengine.facade.api.ImportSelection
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.NativeImport
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.PackageOperation
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ProjectFile
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ProjectFiles
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Pi's configuration files of the desktop. Project documents live where Pi reads them: `AGENTS.md` in the project
 * root, the rest in `.pi`. Writes replace a file atomically (a linked file is written through its link) after the
 * file still has the revision the caller read. The facade calls it on the IO dispatcher.
 */
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class DesktopPiConfigurator(
    private val home: () -> Path?,
    private val userAgentDirectory: () -> Path = ::piUserAgentDirectory,
    private val commands: suspend (Path, List<String>) -> Int = { _, _ -> unsupportedCommand() },
) : PiConfigurator {
    @Inject
    constructor(
        storage: PiStorage,
        @ForScope(ProfileScope::class) stores: DataStores,
        launcher: PiProcessLauncher,
    ) : this(
        home = { (stores.owner as? StorageOwner.Profile)?.let { storage.profileHome(it.id.value) } },
        commands = launcher::runPackageCommand,
    )

    override suspend fun packages(
        operation: PackageOperation,
        config: EngineConfigValues,
        progress: (done: Int, total: Int) -> Unit,
    ) {
        val profileHome = home() ?: unsupportedCommand()
        try {
            PiPackages(profileHome) { arguments -> commands(profileHome, arguments) }.apply(operation, config, progress)
        } catch (e: IOException) {
            log.w(e) { "Pi package files could not be written" }
            throw ManagementException(ManagementFailure.Install(InstallFailureReason.Storage), e)
        }
    }

    private val log = Log.tag("PiConfigurator")

    override suspend fun previewNative(): ImportPreview = files { importer().preview() }

    override suspend fun readNative(selection: ImportSelection): NativeImport = files { importer().read(selection) }

    private fun importer(): PiNativeImporter {
        val source = userAgentDirectory()
        if (!Files.isDirectory(source)) {
            log.w { "Pi user configuration not found" }
            throw ConfigException(ConfigFailure.NativeMissing)
        }
        val profileHome = home() ?: throw ConfigException(ConfigFailure.Unsupported)
        return PiNativeImporter(source, profileHome)
    }

    override suspend fun readProject(directory: String): ProjectFiles = files {
        val root = Path.of(directory)
        ProjectFiles(
            PiProjectFiles.mapNotNull { (id, relative) -> read(root.resolve(relative))?.let { id to it } }.toMap(),
        )
    }

    override suspend fun writeProject(
        directory: String,
        changes: Map<ConfigDocumentId, String?>,
        expected: Map<ConfigDocumentId, String?>,
    ): ProjectFiles = files {
        val root = Path.of(directory)
        val targets = changes.mapKeys { (id, _) -> root.resolve(PiProjectFiles[id] ?: unsupported()) }
        val isChanged = changes.keys.any { id ->
            read(root.resolve(PiProjectFiles.getValue(id)))?.revision != expected[id]
        }
        if (isChanged) {
            log.w { "Pi project files changed since they were read" }
            throw ConfigException(ConfigFailure.Conflict)
        }
        targets.forEach { (path, text) -> if (text == null) Files.deleteIfExists(path) else write(path, text) }
        log.i { "Pi project files written changed=${changes.size}" }
        readProject(directory)
    }

    override suspend fun hasGatedConfiguration(directory: String): Boolean = files {
        val pi = Path.of(directory).resolve(".pi")
        GatedEntries.any { Files.exists(pi.resolve(it)) }
    }

    private fun read(path: Path): ProjectFile? {
        if (!Files.isRegularFile(path)) return null
        if (Files.size(path) > MAX_FILE_BYTES) throw ConfigException(ConfigFailure.Storage)
        val bytes = Files.readAllBytes(path)
        return ProjectFile(String(bytes, Charsets.UTF_8), revisionOf(bytes))
    }

    private fun write(path: Path, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (Files.isSymbolicLink(path)) {
            Files.write(path, bytes)
            return
        }
        Files.createDirectories(path.parent)
        val staged = Files.createTempFile(path.parent, ".${path.fileName}", ".tmp")
        try {
            Files.write(staged, bytes)
            try {
                Files.move(staged, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                log.w(e) { "atomic replace unsupported; replacing the Pi file directly" }
                Files.move(staged, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(staged)
        }
    }

    private inline fun <T> files(block: () -> T): T = try {
        block()
    } catch (e: CharacterCodingException) {
        log.w(e) { "Pi file is not UTF-8" }
        throw ConfigException(ConfigFailure.Storage)
    } catch (e: IOException) {
        log.w(e) { "Pi files could not be read or written" }
        throw ConfigException(ConfigFailure.Storage)
    }

    private fun unsupported(): Nothing = throw ConfigException(ConfigFailure.Unsupported)

    private companion object {
        const val MAX_FILE_BYTES = 4L * 1024 * 1024
        val GatedEntries = listOf("settings.json", "mcp.json", "extensions")
    }
}

/** Where Pi reads each project document, relative to the project root. */
internal val PiProjectFiles: Map<ConfigDocumentId, String> = mapOf(
    PiDocuments.Settings to ".pi/settings.json",
    PiDocuments.Mcp to ".pi/mcp.json",
    PiDocuments.Agents to "AGENTS.md",
    PiDocuments.System to ".pi/SYSTEM.md",
    PiDocuments.AppendSystem to ".pi/APPEND_SYSTEM.md",
)

/** Content revision of a file: its SHA-256. */
internal fun revisionOf(bytes: ByteArray): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

private fun unsupportedCommand(): Nothing =
    throw ManagementException(ManagementFailure.Install(InstallFailureReason.Unsupported))
