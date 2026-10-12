package io.aequicor.heartbeat.feature.browser.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * One direct child of the Chromium root that app DataStores resolved; never an arbitrary deletion path.
 * The root itself is trusted as returned (its ancestors may be platform links such as macOS `/var`) and is
 * canonicalized; only the profile slot below it must be a plain directory. A sibling marker records an unfinished
 * deletion so that a crash or failed clear is completed before Chromium opens the slot again.
 */
internal class BrowserProfileFiles(val directory: File, chromiumRoot: File) {
    private val log = Log.tag("BrowserProfileFiles")
    private val root = chromiumRoot.toPath().toAbsolutePath().normalize()
    private val path = directory.toPath().toAbsolutePath()
    private val name: String

    init {
        require(path == path.normalize() && path.parent == root) { "Browser profile must be a Chromium root child" }
        name = path.fileName.toString()
        require(name.matches(Regex("(?:[0-9a-f]{2})+"))) { "Browser profile slot must be hex-encoded" }
    }

    /** Whether a previous deletion of this slot started and did not finish. */
    fun isDeletionPending(): Boolean =
        canonicalRoot(create = false)?.let { Files.exists(it.resolve(name + PENDING_SUFFIX), NOFOLLOW_LINKS) } ?: false

    /** Prepares the slot for Chromium, first finishing a deletion that a crash or failed clear left behind. */
    suspend fun open(dispatchers: DispatcherProvider) = withContext(dispatchers.io) {
        if (isDeletionPending()) {
            log.i { "Finishing an interrupted browser data clear before Chromium opens the profile" }
            delete(dispatchers)
        }
        create()
    }

    fun create() {
        canonicalRoot(create = true)?.let { checkedSlot(it, create = true) }
    }

    /** CEF can briefly retain Windows handles after its close callback; retries never follow links. */
    suspend fun delete(dispatchers: DispatcherProvider) = withContext(dispatchers.io) {
        val canonical = canonicalRoot(create = false) ?: return@withContext
        val marker = canonical.resolve(name + PENDING_SUFFIX)
        if (!Files.exists(marker, NOFOLLOW_LINKS)) Files.createFile(marker)
        deleteSlotWithRetries(canonical)
        Files.deleteIfExists(marker)
    }

    private suspend fun deleteSlotWithRetries(canonicalRoot: Path) {
        var failure: IOException? = null
        repeat(DELETE_ATTEMPTS) { attempt ->
            try {
                if (Files.exists(canonicalRoot.resolve(name), NOFOLLOW_LINKS)) {
                    deleteTreeWithoutLinks(checkedSlot(canonicalRoot, create = false))
                }
                return
            } catch (error: UnsafeBrowserProfilePath) {
                throw error
            } catch (error: IOException) {
                if (attempt == 0) log.w(error) { "Browser profile cleanup will retry after native file handles close" }
                failure = error
            }
            if (attempt + 1 < DELETE_ATTEMPTS) delay(DELETE_RETRY_MILLIS)
        }
        throw IOException("Browser profile files are still in use", failure).also { error ->
            log.e(error) { "Browser data cleanup failed; it resumes before the profile opens again" }
        }
    }

    private fun canonicalRoot(create: Boolean): Path? {
        if (create) Files.createDirectories(root)
        return try {
            root.toRealPath()
        } catch (error: NoSuchFileException) {
            if (create) throw error
            null
        }
    }

    private fun checkedSlot(canonicalRoot: Path, create: Boolean): Path {
        val slot = canonicalRoot.resolve(name)
        if (create && !Files.exists(slot, NOFOLLOW_LINKS)) Files.createDirectory(slot)
        val attributes = attributes(slot)
        if (!attributes.isDirectory || isRedirected(slot, attributes)) throw UnsafeBrowserProfilePath()
        return slot
    }

    private fun deleteTreeWithoutLinks(directory: Path) {
        // The default walker does not follow symbolic links. Windows junctions are checked separately.
        Files.walkFileTree(
            directory,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (isRedirected(dir, attrs)) {
                        if (dir == directory) throw UnsafeBrowserProfilePath()
                        Files.delete(dir)
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (isRedirected(file.parent, attributes(file.parent))) throw UnsafeBrowserProfilePath()
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    if (isRedirected(dir, attributes(dir))) throw UnsafeBrowserProfilePath()
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun attributes(path: Path): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)

    private fun isRedirected(path: Path, attributes: BasicFileAttributes): Boolean =
        attributes.isSymbolicLink || attributes.isOther || path.toRealPath() != path
}

private class UnsafeBrowserProfilePath : IOException("Browser profile path contains a redirected directory")

private const val PENDING_SUFFIX = ".clearing"
private const val DELETE_ATTEMPTS = 40
private const val DELETE_RETRY_MILLIS = 250L
