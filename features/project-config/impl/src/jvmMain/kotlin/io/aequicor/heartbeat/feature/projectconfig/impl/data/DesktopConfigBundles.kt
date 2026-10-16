package io.aequicor.heartbeat.feature.projectconfig.impl.data

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigFile
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigLayout
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigLimits
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigPath
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigReadFailure
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigBundles
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigManifests
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigRead
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigTree
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigWriteException
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.ConfigWriteFailure
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.outcome
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * `.hbai` bundles on the desktop file system: a ZIP of the tree with its manifest. Reading never extracts to disk and
 * never trusts declared sizes; entries outside the tree, duplicates and a missing or newer manifest refuse the whole
 * bundle. Directory entries and hidden entries (archiver metadata such as `__MACOSX` or `.DS_Store`) are skipped.
 */
@ContributesBinding(AppScope::class)
@Inject
internal class DesktopConfigBundles(private val dispatchers: DispatcherProvider) : ConfigBundles {
    override val isAvailable: Boolean = true

    override suspend fun read(path: String): ConfigRead = withContext(dispatchers.io) {
        readConfigBundle(Path.of(path)).also { log.v { "Read configuration bundle: ${it.outcome}" } }
    }

    override suspend fun write(path: String, tree: ConfigTree): Unit = withContext(dispatchers.io) {
        writeConfigBundle(Path.of(path), tree)
        log.v { "Wrote configuration bundle: ${tree.files.size} files" }
    }
}

private val log = Log.tag("ConfigBundles")

/** Reads bundle [file]; see [DesktopConfigBundles]. */
internal fun readConfigBundle(file: Path): ConfigRead {
    val attributes = attributesOrNull(file)
    if (attributes == null || !attributes.isRegularFile) return ConfigRead.Failed(ConfigReadFailure.Unavailable)
    return try {
        ConfigRead.Read(ZipFile(file.toFile()).use(::readEntries))
    } catch (e: BundleRefusedException) {
        log.w(e) { "Configuration bundle refused: ${e.failure}" }
        ConfigRead.Failed(e.failure)
    } catch (e: ZipException) {
        log.w(e) { "Configuration bundle is not a ZIP archive" }
        ConfigRead.Failed(ConfigReadFailure.Format)
    } catch (e: IOException) {
        log.w(e) { "Configuration bundle is unreadable" }
        ConfigRead.Failed(ConfigReadFailure.Io)
    }
}

private fun readEntries(zip: ZipFile): ConfigTree {
    val entries = zip.entries().asSequence().filterNot { it.isDirectory || it.name.isHiddenEntry() }.toList()
    refuseIf(entries.size > ConfigLimits.FILES, ConfigReadFailure.Limit)
    val files = mutableListOf<ConfigFile>()
    val seen = mutableSetOf<String>()
    var manifest: String? = null
    var total = 0L
    for (entry in entries) {
        val path = ConfigPath.parse(entry.name)
        refuseIf(path == null || !seen.add(entry.name), ConfigReadFailure.Format)
        val bytes = zip.getInputStream(entry).use { it.readAtMost(ConfigLimits.FILE_BYTES) }
        refuseIf(bytes == null, ConfigReadFailure.Limit)
        total += bytes?.size ?: 0
        refuseIf(total > ConfigLimits.TOTAL_BYTES, ConfigReadFailure.Limit)
        val text = bytes?.let(::decodeConfigText) ?: throw BundleRefusedException(ConfigReadFailure.Format)
        if (path?.value == ConfigLayout.MANIFEST) manifest = text else path?.let { files += ConfigFile(it, text) }
    }
    val failure = manifest?.let(ConfigManifests::check) ?: ConfigReadFailure.Format.takeIf { manifest == null }
    failure?.let { throw BundleRefusedException(it) }
    return ConfigTree.of(files)
}

/** Writes [tree] with the manifest to [file]; entries are sorted and undated, so equal trees give equal bundles. */
internal fun writeConfigBundle(file: Path, tree: ConfigTree) {
    val files = listOf(ConfigManifests.current) + tree.files
    if (files.size > ConfigLimits.FILES || files.sumOf { it.bytes.toLong() } > ConfigLimits.TOTAL_BYTES) {
        throw ConfigWriteException(ConfigWriteFailure.Limit)
    }
    val target = file.toAbsolutePath()
    requireBundleTarget(target)
    val bytes = ByteArrayOutputStream()
    try {
        ZipOutputStream(bytes).use { zip ->
            files.forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.path.value).apply { time = FIXED_ENTRY_TIME })
                zip.write(entry.text.encodeToByteArray())
                zip.closeEntry()
            }
        }
    } catch (e: IOException) {
        throw ConfigWriteException(ConfigWriteFailure.Io, e)
    }
    replaceAtomically(target, bytes.toByteArray())
}

/** [target] is a regular file or absent, inside an existing directory. */
private fun requireBundleTarget(target: Path) {
    val parent = target.parent?.let(::attributesOrNull)
    val existing = attributesOrNull(target)
    val failure = when {
        parent == null || !parent.isDirectory -> ConfigWriteFailure.Unavailable
        existing != null && !existing.isRegularFile -> ConfigWriteFailure.NotDirectory
        else -> null
    }
    failure?.let { throw ConfigWriteException(it) }
}

private fun refuseIf(condition: Boolean, failure: ConfigReadFailure) {
    if (condition) throw BundleRefusedException(failure)
}

/** The whole bundle is refused for [failure]. */
private class BundleRefusedException(val failure: ConfigReadFailure) : IllegalStateException(failure.name)

private fun String.isHiddenEntry(): Boolean = split('/').any { it.startsWith('.') || it == "__MACOSX" }

/** 1980-01-02, the earliest date every ZIP reader accepts; entries carry no real time. */
private const val FIXED_ENTRY_TIME = 315_619_200_000L
