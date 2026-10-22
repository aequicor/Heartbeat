package io.aequicor.heartbeat.feature.aistudio.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioFileContent
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioFileReader
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

/** Desktop file previews: bounded reads of regular files through java.nio, off the UI thread. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class DesktopStudioFileReader(private val dispatchers: DispatcherProvider) : StudioFileReader {
    private val log = Log.tag("StudioFileReader")
    private val home: String? = System.getProperty("user.home")

    override val isAvailable: Boolean = true

    override suspend fun resolve(path: String, base: String?): String? = resolveLocalPath(path, base, home)

    override suspend fun read(path: String): StudioFileContent = withContext(dispatchers.io) {
        try {
            val file = Path.of(path)
            if (!Files.isRegularFile(file)) return@withContext StudioFileContent.Unavailable
            val modifiedAt = Files.getLastModifiedTime(file).toMillis()
            // One byte over the limit tells a large file apart, even one growing or misreporting its size.
            val bytes = Files.newInputStream(file).use { it.readNBytes(LIMIT + 1) }
            log.v { "Read file preview size=${bytes.size}" }
            if (bytes.size > LIMIT) {
                StudioFileContent.TooLarge(bytes.size.toLong())
            } else if (bytes.any { it == NUL }) {
                StudioFileContent.Binary
            } else {
                StudioFileContent.Text(bytes.decodeToString(), modifiedAt)
            }
        } catch (e: IOException) {
            log.w(e) { "File preview could not be read" }
            StudioFileContent.Unavailable
        } catch (e: InvalidPathException) {
            log.w(e) { "File preview path is invalid" }
            StudioFileContent.Unavailable
        } catch (e: SecurityException) {
            log.w(e) { "File preview is not permitted" }
            StudioFileContent.Unavailable
        }
    }

    override suspend fun modifiedAt(path: String): Long? = withContext(dispatchers.io) {
        try {
            Path.of(path).takeIf(Files::isRegularFile)?.let { Files.getLastModifiedTime(it).toMillis() }
        } catch (e: IOException) {
            log.w(e) { "File preview modification time is unavailable" }
            null
        } catch (e: InvalidPathException) {
            log.w(e) { "File preview path is invalid" }
            null
        } catch (e: SecurityException) {
            log.w(e) { "File preview modification time is not permitted" }
            null
        }
    }

    private companion object {
        const val NUL: Byte = 0
        const val LIMIT = StudioFileReader.MAX_PREVIEW_BYTES.toInt()
    }
}

/** `~/` from [home], an absolute path as is, a relative one against [base]; normalized, never touching the disk. */
internal fun resolveLocalPath(path: String, base: String?, home: String?): String? = try {
    val target = when {
        path == "~" || path.startsWith("~/") || path.startsWith("~\\") -> home?.let {
            Path.of(it, path.drop(1).trimStart('/', '\\'))
        }

        Path.of(path).isAbsolute -> Path.of(path)

        else -> base?.let { Path.of(it).resolve(path) }
    }
    target?.toAbsolutePath()?.normalize()?.toString()
} catch (e: InvalidPathException) {
    Log.tag("StudioFileReader").w(e) { "File link path is invalid" }
    null
}
