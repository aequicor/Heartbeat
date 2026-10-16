package io.aequicor.heartbeat.feature.projectconfig.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigFile
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigLayout
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigLimits
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigPath
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblem
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigProblemCode
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigReadFailure
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigManifests
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigRead
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigTree
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigWriteException
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigWriteFailure
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ProjectFolders
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.outcome
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/**
 * `.heartbeat` folders on the desktop file system. Symbolic links are never followed: a linked `.heartbeat` is
 * refused, linked files and directories inside are reported and skipped, and writes refuse to pass through them.
 * Hidden files (a leading dot, including temporary files of writes) are skipped silently.
 */
@ContributesBinding(AppScope::class)
@Inject
internal class DesktopProjectFolders(private val dispatchers: DispatcherProvider) : ProjectFolders {
    override val isAvailable: Boolean = true

    override suspend fun read(root: String): ConfigRead = withContext(dispatchers.io) {
        readProjectFolder(Path.of(root)).also { log.v { "Read project configuration: ${it.outcome}" } }
    }

    override suspend fun fingerprint(root: String): String? = withContext(dispatchers.io) {
        projectFolderFingerprint(Path.of(root))
    }

    override suspend fun write(root: String, put: List<ConfigFile>, delete: List<ConfigPath>): Unit =
        withContext(dispatchers.io) {
            writeProjectFolder(Path.of(root), put, delete)
            log.v { "Wrote project configuration: ${put.size} put, ${delete.size} deleted" }
        }
}

private val log = Log.tag("ProjectFolders")

/** Reads `<root>/.heartbeat`; see [DesktopProjectFolders]. */
internal fun readProjectFolder(root: Path): ConfigRead {
    val directory = root.resolve(ConfigLayout.PROJECT_DIRECTORY)
    val attributes = attributesOrNull(directory) ?: return ConfigRead.Absent
    if (attributes.isSymbolicLink || !attributes.isDirectory) return ConfigRead.Failed(ConfigReadFailure.NotDirectory)
    val reader = FolderReader(directory)
    return try {
        Files.walkFileTree(directory, emptySet(), ConfigLimits.DEPTH, reader)
        reader.manifest?.let(ConfigManifests::check)?.let { ConfigRead.Failed(it) }
            ?: ConfigRead.Read(ConfigTree.of(reader.files, reader.ignored))
    } catch (e: TreeLimitException) {
        log.w(e.redactedFailure()) { "Project configuration exceeds its limits" }
        ConfigRead.Failed(ConfigReadFailure.Limit)
    } catch (e: IOException) {
        log.w(e.redactedFailure()) { "Project configuration is unreadable" }
        ConfigRead.Failed(ConfigReadFailure.Io)
    }
}

/** Marker of paths, sizes and modification times under `<root>/.heartbeat`, or null when it is not a folder. */
internal fun projectFolderFingerprint(root: Path): String? {
    val directory = root.resolve(ConfigLayout.PROJECT_DIRECTORY)
    val attributes = attributesOrNull(directory) ?: return null
    if (attributes.isSymbolicLink || !attributes.isDirectory) return null
    val entries = mutableListOf<String>()
    return try {
        Files.walkFileTree(
            directory,
            emptySet(),
            ConfigLimits.DEPTH,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (entries.size > ConfigLimits.FILES) return FileVisitResult.TERMINATE
                    if (!file.fileName.toString().startsWith('.')) {
                        entries += "${directory.relativize(
                            file,
                        )}|${attrs.size()}|${attrs.lastModifiedTime().toMillis()}"
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    entries += "${directory.relativize(file)}|failed"
                    return FileVisitResult.CONTINUE
                }
            },
        )
        val digest = MessageDigest.getInstance("SHA-256")
        entries.sorted().forEach { digest.update((it + "\n").encodeToByteArray()) }
        digest.digest().toHexString()
    } catch (e: IOException) {
        log.w(e.redactedFailure()) { "Project configuration cannot be listed" }
        null
    }
}

/**
 * Replaces [put] and deletes [delete] in `<root>/.heartbeat`, creating the folder with a manifest; every directory
 * on the way must be a real directory inside the folder.
 */
internal fun writeProjectFolder(root: Path, put: List<ConfigFile>, delete: List<ConfigPath>) {
    if (attributesOrNull(root)?.isDirectory != true) throw ConfigWriteException(ConfigWriteFailure.Unavailable)
    val directory = root.resolve(ConfigLayout.PROJECT_DIRECTORY)
    requireDirectory(directory)
    if (attributesOrNull(directory.resolve(ConfigLayout.MANIFEST)) == null) write(directory, ConfigManifests.current)
    put.forEach { write(directory, it) }
    delete.forEach { remove(directory, it) }
}

private fun write(directory: Path, file: ConfigFile) {
    val bytes = file.text.encodeToByteArray()
    if (bytes.size > ConfigLimits.FILE_BYTES) throw ConfigWriteException(ConfigWriteFailure.Limit)
    val target = directory.resolve(file.path)
    var current = directory
    directory.relativize(target.parent).forEach { segment ->
        current = current.resolve(segment)
        requireDirectory(current)
    }
    val existing = attributesOrNull(target)
    if (existing != null && !existing.isRegularFile) throw ConfigWriteException(ConfigWriteFailure.NotDirectory)
    replaceAtomically(target, bytes)
}

private fun remove(directory: Path, path: ConfigPath) {
    val target = directory.resolve(path)
    // Every ancestor must be a real directory, otherwise the deletion would happen outside the folder.
    val ancestors = generateSequence(target.parent) { it.parent }.takeWhile { it != directory }.toList()
    ancestors.forEach { requireDirectory(it, isCreated = false) }
    val attributes = attributesOrNull(target) ?: return
    if (attributes.isDirectory) throw ConfigWriteException(ConfigWriteFailure.NotDirectory)
    try {
        Files.delete(target)
        ancestors.asSequence().takeWhile(::isEmptyDirectory).forEach(Files::delete)
    } catch (e: IOException) {
        throw ConfigWriteException(ConfigWriteFailure.Io, e)
    }
}

/** [directory] is a real directory, created when absent and [isCreated]; a link or a file refuses the write. */
private fun requireDirectory(directory: Path, isCreated: Boolean = true) {
    val attributes = attributesOrNull(directory)
    when {
        attributes == null -> if (isCreated) create(directory)

        attributes.isSymbolicLink || !attributes.isDirectory ->
            throw ConfigWriteException(ConfigWriteFailure.NotDirectory)
    }
}

private fun isEmptyDirectory(directory: Path): Boolean = Files.list(directory).use { !it.findAny().isPresent }

private fun create(directory: Path) {
    try {
        Files.createDirectory(directory)
    } catch (e: IOException) {
        throw ConfigWriteException(ConfigWriteFailure.Io, e)
    }
}

private fun Path.resolve(path: ConfigPath): Path = path.segments.fold(
    this,
) { current, segment -> current.resolve(segment) }

/** A tree grew beyond [ConfigLimits] while it was read. */
internal class TreeLimitException : IllegalStateException("Configuration tree limit")

/** Collects the files of a `.heartbeat` folder within [ConfigLimits]. */
private class FolderReader(private val directory: Path) : SimpleFileVisitor<Path>() {
    val files = mutableListOf<ConfigFile>()
    val ignored = mutableListOf<ConfigProblem>()
    var manifest: String? = null
    private var total = 0L

    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult =
        if (dir != directory && dir.fileName.toString().startsWith('.')) {
            FileVisitResult.SKIP_SUBTREE
        } else {
            FileVisitResult.CONTINUE
        }

    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
        if (file.fileName.toString().startsWith('.')) return FileVisitResult.CONTINUE
        val relative = directory.relativize(file).joinToString("/")
        val path = ConfigPath.parse(relative)
        when {
            attrs.isSymbolicLink || !attrs.isRegularFile || path == null ->
                ignored += ConfigManifests.ignored(relative, ConfigProblemCode.Unsupported)

            attrs.size() > ConfigLimits.FILE_BYTES -> ignored += ConfigManifests.ignored(
                relative,
                ConfigProblemCode.Limit,
            )

            else -> read(file, path, attrs.size())
        }
        return FileVisitResult.CONTINUE
    }

    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
        log.w(exc.redactedFailure()) { "A project configuration file is unreadable" }
        ignored += ConfigManifests.ignored(directory.relativize(file).joinToString("/"), ConfigProblemCode.Unsupported)
        return FileVisitResult.CONTINUE
    }

    private fun read(file: Path, path: ConfigPath, size: Long) {
        total += size
        if (total > ConfigLimits.TOTAL_BYTES || files.size >= ConfigLimits.FILES) throw TreeLimitException()
        val bytes = Files.newInputStream(file).use { it.readAtMost(ConfigLimits.FILE_BYTES) }
        val text = bytes?.let(::decodeConfigText)
        when {
            bytes == null -> ignored += ConfigProblem(ConfigProblemCode.Limit, path)
            text == null -> ignored += ConfigProblem(ConfigProblemCode.Syntax, path)
            path.value == ConfigLayout.MANIFEST -> manifest = text
            else -> files += ConfigFile(path, text)
        }
    }
}
