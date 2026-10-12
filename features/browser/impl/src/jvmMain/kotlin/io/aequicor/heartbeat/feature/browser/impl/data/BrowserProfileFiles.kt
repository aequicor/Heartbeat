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

/** One direct child of the app DataStores Chromium root; never an arbitrary deletion path. */
internal class BrowserProfileFiles(val directory: File, chromiumRoot: File) {
    private val log = Log.tag("BrowserProfileFiles")
    private val chromium = chromiumRoot.toPath().toAbsolutePath().normalize()
    private val root = chromium.resolve("../../../..").normalize()
    private val path = directory.toPath().toAbsolutePath()
    private val relative = root.relativize(path)

    init {
        require(path == path.normalize() && path.parent == chromium && relative.nameCount == PROFILE_PATH_PARTS)
        require(chromium.endsWith(Path.of("storage", "app", "files", "browser_profiles")))
        require(relative.fileName.toString().matches(Regex("(?:[0-9a-f]{2})+")))
    }

    fun create() {
        checkedDirectory(create = true)
    }

    /** CEF can briefly retain Windows handles after its close callback; retries never follow links. */
    suspend fun delete(dispatchers: DispatcherProvider) = withContext(dispatchers.io) {
        var failure: IOException? = null
        repeat(DELETE_ATTEMPTS) { attempt ->
            try {
                checkedDirectory(create = false)?.let(::deleteTreeWithoutLinks)
                return@withContext
            } catch (error: UnsafeBrowserProfilePath) {
                throw error
            } catch (error: IOException) {
                if (attempt == 0) log.w(error) { "Browser profile cleanup will retry after native file handles close" }
                failure = error
            }
            if (attempt + 1 < DELETE_ATTEMPTS) delay(DELETE_RETRY_MILLIS)
        }
        throw IOException("Browser profile files are still in use", failure).also { error ->
            log.e(error) { "Browser data cleanup failed; profile reuse is blocked" }
        }
    }

    private fun checkedDirectory(create: Boolean): Path? {
        if (create) Files.createDirectories(root)
        return try {
            checkSegments(root.toRealPath(), create)
        } catch (error: NoSuchFileException) {
            if (create) throw error
            null
        }
    }

    private fun checkSegments(canonicalRoot: Path, create: Boolean): Path {
        var current = canonicalRoot
        for (segment in relative) {
            current = current.resolve(segment)
            if (create && !Files.exists(current, NOFOLLOW_LINKS)) Files.createDirectory(current)
            val attributes = attributes(current)
            if (!attributes.isDirectory || isRedirected(current, attributes)) {
                throw UnsafeBrowserProfilePath()
            }
        }
        return current
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

private const val PROFILE_PATH_PARTS = 5
private const val DELETE_ATTEMPTS = 40
private const val DELETE_RETRY_MILLIS = 250L
