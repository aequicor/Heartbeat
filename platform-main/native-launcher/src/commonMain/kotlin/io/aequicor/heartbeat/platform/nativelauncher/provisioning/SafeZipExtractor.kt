package io.aequicor.heartbeat.platform.nativelauncher.provisioning

import okio.FileSystem
import okio.Path
import okio.buffer

/**
 * Безопасная распаковка артефакта в staging: абсолютные пути, `..`, разделители Windows, symlink и специальные
 * файлы отклоняются до записи первого байта. Лимиты числа записей и распакованного объёма действуют до начала
 * распаковки, размер каждой записи проверяется при чтении. Staging не содержит исполняемых для запуска файлов:
 * приложение стартует только после атомарной активации ([ImageStore.activate]).
 * Custom file systems must supply [applyFileMode]; the default adapter only touches [FileSystem.SYSTEM].
 */
class SafeZipExtractor(
    private val fs: FileSystem = FileSystem.SYSTEM,
    private val limits: ZipLimits = ZipLimits(),
    private val applyFileMode: (Path, Int) -> Unit = { path, mode ->
        require(fs === FileSystem.SYSTEM) { "Custom FileSystem requires its own file-mode adapter" }
        ProvisioningPlatform.applyFileMode(path, mode)
    },
) {
    data class ZipLimits(
        val maxEntries: Int = 10_000,
        val maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
        val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    )

    fun extract(archiveFile: Path, stagingDir: Path) {
        val archive = ZipArchive(fs, archiveFile)
        val entries = archive.entries
        require(entries.size <= limits.maxEntries) { "Too many entries in $archiveFile: ${entries.size}" }
        var total = 0L
        entries.forEach { entry ->
            validateName(entry.name)
            require(!entry.isEncrypted) { "Encrypted entry: ${entry.name}" }
            require(!entry.isSymlink) { "Symlink in archive: ${entry.name}" }
            require(entry.method == STORED || entry.method == DEFLATED) {
                "Unsupported compression method ${entry.method}: ${entry.name}"
            }
            require(entry.uncompressedSize in 0..limits.maxEntryBytes) {
                "Entry exceeds the size limit: ${entry.name} (${entry.uncompressedSize} bytes)"
            }
            total += entry.uncompressedSize
            require(total <= limits.maxTotalBytes) { "Archive exceeds the unpacked size limit: $archiveFile" }
        }
        fs.createDirectories(stagingDir)
        entries.forEach { entry ->
            val target = stagingDir.resolve(entry.name)
            if (entry.isDirectory) {
                fs.createDirectories(target)
            } else {
                entry.name.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let {
                    fs.createDirectories(stagingDir.resolve(it))
                }
                fs.sink(target, mustCreate = true).buffer().let { sink ->
                    try {
                        archive.readEntry(entry, sink)
                    } finally {
                        sink.close()
                    }
                }
                applyFileMode(target, entry.unixMode)
            }
        }
    }

    private fun validateName(name: String) {
        require(name.isNotEmpty()) { "Empty entry name" }
        require(!name.contains('\\')) { "Backslash in entry name: $name" }
        require(name.none { it < ' ' }) { "Control character in entry name" }
        require(!name.startsWith('/')) { "Absolute path in archive: $name" }
        require(!Regex("^[A-Za-z]:").containsMatchIn(name)) { "Drive path in archive: $name" }
        val segments = name.split('/')
        require(segments.none { it == ".." || it == "." }) { "Path traversal in archive: $name" }
        segments.forEachIndexed { index, segment ->
            require(segment.isNotEmpty() || (index == segments.lastIndex && name.endsWith("/"))) {
                "Blank path segment: $name"
            }
        }
    }

    companion object {
        private const val STORED = 0
        private const val DEFLATED = 8
        private const val DEFAULT_MAX_ENTRY_BYTES = 512L * 1024 * 1024
        private const val DEFAULT_MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    }
}
