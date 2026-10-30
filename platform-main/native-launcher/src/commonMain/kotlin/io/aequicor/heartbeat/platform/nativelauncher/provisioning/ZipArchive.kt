@file:Suppress("MagicNumber") // Смещения, сигнатуры и маски — константы бинарного формата ZIP, не параметры логики.

package io.aequicor.heartbeat.platform.nativelauncher.provisioning

import okio.FileSystem
import okio.Path

/** Запись ZIP-архива из центрального каталога. */
data class ZipEntry(
    val name: String,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val method: Int,
    val localOffset: Long,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val isEncrypted: Boolean,
    val unixMode: Int = 0,
)

/**
 * Минимальный читатель ZIP (без zip64, многотомности и шифрования) поверх okio: формат артефактов дистрибутива
 * контролируется сборкой, поэтому экзотика отклоняется. Парсинг центрального каталога общий для всех платформ:
 * внешние атрибуты Unix дают тип записи (symlink и специальные файлы отклоняются до распаковки).
 */
class ZipArchive(private val fs: FileSystem, private val file: Path) {
    val entries: List<ZipEntry> = readCentralDirectory()

    /** Читает поток данных записи в [sink], распаковывая deflate; проверяет итоговый размер. */
    fun readEntry(entry: ZipEntry, sink: okio.BufferedSink) {
        val handle = fs.openReadOnly(file)
        try {
            val header = handle.readBytes(entry.localOffset, LOCAL_HEADER_SIZE)
            require(header.leInt(0) == LOCAL_SIGNATURE) { "Bad local header for ${entry.name}" }
            val nameLength = header.leShort(26)
            val extraLength = header.leShort(28)
            val position = entry.localOffset + LOCAL_HEADER_SIZE + nameLength + extraLength
            if (entry.method == DEFLATED) {
                inflateEntry(handle, entry, position, sink)
            } else {
                copyStoredEntry(handle, entry, position, sink)
            }
        } finally {
            handle.close()
        }
    }

    private fun copyStoredEntry(handle: okio.FileHandle, entry: ZipEntry, position: Long, sink: okio.BufferedSink) {
        var offset = position
        var remaining = entry.compressedSize
        var produced = 0L
        while (remaining > 0) {
            val chunk = handle.readBytes(offset, minOf(remaining, CHUNK.toLong()).toInt())
            offset += chunk.size
            remaining -= chunk.size
            sink.write(chunk, 0, chunk.size)
            produced += chunk.size
        }
        checkProduced(entry, produced)
    }

    private fun inflateEntry(handle: okio.FileHandle, entry: ZipEntry, position: Long, sink: okio.BufferedSink) {
        var offset = position
        var remaining = entry.compressedSize
        var produced = 0L
        val output = ByteArray(CHUNK)
        val inflater = RawInflater()
        try {
            while (remaining > 0 || !inflater.finished()) {
                if (remaining > 0 && inflater.needsInput()) {
                    val chunk = handle.readBytes(offset, minOf(remaining, CHUNK.toLong()).toInt())
                    offset += chunk.size
                    remaining -= chunk.size
                    inflater.input(chunk)
                }
                val count = inflater.inflate(output)
                if (count > 0) {
                    sink.write(output, 0, count)
                    produced += count
                } else if (inflater.needsInput() && remaining == 0L && !inflater.finished()) {
                    error("Truncated deflate stream for ${entry.name}")
                } else if (count == 0 && !inflater.needsInput() && !inflater.finished()) {
                    error("Stalled deflate stream for ${entry.name}")
                }
            }
            checkProduced(entry, produced)
        } finally {
            inflater.end()
        }
    }

    private fun checkProduced(entry: ZipEntry, produced: Long) {
        require(produced == entry.uncompressedSize) {
            "Entry ${entry.name} unpacked to $produced bytes, expected ${entry.uncompressedSize}"
        }
    }

    private fun readCentralDirectory(): List<ZipEntry> {
        val handle = fs.openReadOnly(file)
        try {
            val size = handle.size()
            val tailLength = minOf(size, MAX_EOCD_SEARCH.toLong()).toInt()
            val tail = handle.readBytes(size - tailLength, tailLength)
            var eocd = -1
            for (i in tailLength - EOCD_SIZE downTo 0) {
                if (tail.leInt(i) == EOCD_SIGNATURE) {
                    eocd = i
                    break
                }
            }
            require(eocd >= 0) { "Not a zip archive: $file" }
            val count = tail.leShort(eocd + 10)
            val directorySize = tail.leInt(eocd + 12).toLong()
            val directoryOffset = tail.leInt(eocd + 16).toLong()
            require(count.toLong() * CENTRAL_HEADER_SIZE <= directorySize) { "Corrupt central directory" }
            val directory = handle.readBytes(directoryOffset, directorySize.toInt())
            val entries = ArrayList<ZipEntry>(count)
            var offset = 0
            repeat(count) {
                require(
                    offset + CENTRAL_HEADER_SIZE <= directory.size && directory.leInt(offset) == CENTRAL_SIGNATURE,
                ) {
                    "Corrupt central directory entry"
                }
                val nameLength = directory.leShort(offset + 28)
                val extraLength = directory.leShort(offset + 30)
                val commentLength = directory.leShort(offset + 32)
                val name = directory.decodeToString(offset + 46, offset + 46 + nameLength)
                val isMadeByUnix = directory[offset + 5].toInt() == UNIX_HOST
                val externalAttrs = directory.leInt(offset + 38)
                val unixType = if (isMadeByUnix) (externalAttrs ushr 16) and UNIX_TYPE_MASK else 0
                val isSymlink = unixType == UNIX_SYMLINK
                require(unixType == 0 || unixType == UNIX_REGULAR || unixType == UNIX_DIRECTORY || isSymlink) {
                    "Special file in archive: $name"
                }
                entries += ZipEntry(
                    name = name,
                    compressedSize = directory.leInt(offset + 20).toLong() and UINT_MASK,
                    uncompressedSize = directory.leInt(offset + 24).toLong() and UINT_MASK,
                    method = directory.leShort(offset + 10),
                    localOffset = directory.leInt(offset + 42).toLong() and UINT_MASK,
                    isDirectory = name.endsWith("/"),
                    isSymlink = isSymlink,
                    isEncrypted = directory[offset + 8].toInt() and 1 != 0,
                    unixMode = if (isMadeByUnix) externalAttrs ushr 16 else 0,
                )
                offset += 46 + nameLength + extraLength + commentLength
            }
            return entries
        } finally {
            handle.close()
        }
    }

    companion object {
        private const val EOCD_SIGNATURE = 0x06054b50
        private const val CENTRAL_SIGNATURE = 0x02014b50
        private const val LOCAL_SIGNATURE = 0x04034b50
        private const val EOCD_SIZE = 22
        private const val MAX_EOCD_SEARCH = 65557
        private const val CENTRAL_HEADER_SIZE = 46
        private const val LOCAL_HEADER_SIZE = 30
        private const val CHUNK = 16 * 1024
        private const val DEFLATED = 8
        private const val UNIX_HOST = 3
        private const val UINT_MASK = 0xffffffffL
        private const val UNIX_TYPE_MASK = 0xf000
        private const val UNIX_SYMLINK = 0xa000
        private const val UNIX_REGULAR = 0x8000
        private const val UNIX_DIRECTORY = 0x4000
    }
}

internal fun ByteArray.leShort(offset: Int): Int =
    (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

internal fun ByteArray.leInt(offset: Int): Int = leShort(offset) or (leShort(offset + 2) shl 16)

/** Читает ровно [count] байтов с позиции [offset]; укороченный файл — ошибка разбора. */
private fun okio.FileHandle.readBytes(offset: Long, count: Int): ByteArray {
    val buffer = okio.Buffer()
    val read = read(offset, buffer, count.toLong())
    require(read == count.toLong()) { "Truncated zip file: $read of $count bytes at $offset" }
    return buffer.readByteArray()
}

/** Raw-deflate распаковка: JVM — java.util.zip.Inflater, нативные — zlib. */
internal expect class RawInflater() {
    fun input(data: ByteArray)
    fun inflate(output: ByteArray): Int
    fun needsInput(): Boolean
    fun finished(): Boolean
    fun end()
}
