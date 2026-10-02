package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install

import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import java.io.InputStream
import java.io.OutputStream

/**
 * Streaming reader of POSIX (ustar/pax) and GNU tar archives, enough for release archives: regular files,
 * directories, pax `path`/`size` records and GNU long names. Every header checksum is verified; links, devices and
 * other special entries are reported as [TarEntryType.Special] so the caller can refuse them.
 */
internal class TarReader(private val input: InputStream) {
    private var remaining = 0L
    private var padding = 0L

    /** The next entry, or null at the end of the archive; skips whatever the caller left of the previous one. */
    fun next(): TarEntry? {
        skipRest()
        val extensions = Extensions()
        var entry: TarEntry? = null
        while (entry == null) {
            val header = readBlock()?.takeUnless { block -> block.all { it.toInt() == 0 } } ?: break
            verifyChecksum(header)
            entry = read(header, extensions)
        }
        return entry
    }

    /** An entry, or null for an extension header that only describes the next entry. */
    private fun read(header: ByteArray, extensions: Extensions): TarEntry? {
        val type = header[TYPE_OFFSET].toInt().toChar()
        when (type) {
            PAX_HEADER -> extensions.apply(readRecords(readData(header.size(), MAX_EXTENSION_BYTES)))
            PAX_GLOBAL, GNU_LONG_LINK -> readData(header.size(), MAX_EXTENSION_BYTES)
            GNU_LONG_NAME -> extensions.longName = readLongName(header)
            else -> return entry(header, type, extensions)
        }
        return null
    }

    private fun readLongName(header: ByteArray): String =
        readData(header.size(), MAX_EXTENSION_BYTES).decodeToString().trimEnd('\u0000')

    private fun entry(header: ByteArray, type: Char, extensions: Extensions): TarEntry {
        val size = extensions.size ?: header.size()
        if (size < 0) invalid()
        val entryType = when (type) {
            REGULAR, REGULAR_OLD, CONTIGUOUS -> TarEntryType.File
            DIRECTORY -> TarEntryType.Directory
            else -> TarEntryType.Special
        }
        val data = if (entryType == TarEntryType.Directory) 0L else size
        remaining = data
        padding = paddingOf(data)
        val name = extensions.path ?: extensions.longName ?: header.name()
        return TarEntry(name, entryType, data, header.octal(MODE_OFFSET, MODE_LENGTH).toInt())
    }

    /** pax and GNU headers that describe the entry that follows them. */
    private inner class Extensions {
        var path: String? = null
        var longName: String? = null
        var size: Long? = null

        fun apply(records: Map<String, String>) {
            records["path"]?.let { path = it }
            records["size"]?.let { size = it.toLongOrNull() ?: invalid() }
        }
    }

    /** Copies the data of the current entry into [output]. */
    fun copyTo(output: OutputStream) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) invalid()
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun skipRest() {
        skipExactly(remaining + padding)
        remaining = 0
        padding = 0
    }

    private fun readData(size: Long, limit: Int): ByteArray {
        if (size < 0 || size > limit) invalid()
        val data = input.readNBytes(size.toInt())
        if (data.size.toLong() != size) invalid()
        skipExactly(paddingOf(size))
        return data
    }

    private fun readBlock(): ByteArray? {
        val block = input.readNBytes(BLOCK_SIZE)
        return when (block.size) {
            0 -> null
            BLOCK_SIZE -> block
            else -> invalid()
        }
    }

    private fun skipExactly(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped <= 0) {
                if (input.read() < 0) invalid()
                left--
            } else {
                left -= skipped
            }
        }
    }

    private fun verifyChecksum(header: ByteArray) {
        val stored = header.octal(CHECKSUM_OFFSET, CHECKSUM_LENGTH)
        var sum = 0L
        header.forEachIndexed { index, byte ->
            val isChecksumField = index in CHECKSUM_OFFSET until CHECKSUM_OFFSET + CHECKSUM_LENGTH
            sum += if (isChecksumField) SPACE else (byte.toInt() and BYTE_MASK).toLong()
        }
        if (sum != stored) invalid()
    }

    private fun ByteArray.size(): Long {
        val first = this[SIZE_OFFSET].toInt() and BYTE_MASK
        if (first and BASE256_FLAG == 0) return octal(SIZE_OFFSET, SIZE_LENGTH)
        // GNU base-256: big-endian two's complement in the remaining bytes of the field.
        var value = (first and BASE256_MASK).toLong()
        for (index in SIZE_OFFSET + 1 until SIZE_OFFSET + SIZE_LENGTH) {
            if (value > Long.MAX_VALUE shr Byte.SIZE_BITS) invalid()
            value = (value shl Byte.SIZE_BITS) or (this[index].toInt() and BYTE_MASK).toLong()
        }
        return value
    }

    private fun ByteArray.name(): String {
        val name = string(NAME_OFFSET, NAME_LENGTH)
        val isUstar = string(MAGIC_OFFSET, MAGIC_LENGTH).startsWith("ustar")
        val prefix = if (isUstar) string(PREFIX_OFFSET, PREFIX_LENGTH) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun ByteArray.string(offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && this[end].toInt() != 0) end++
        return decodeToString(offset, end)
    }

    private fun ByteArray.octal(offset: Int, length: Int): Long {
        val text = string(offset, length).trim()
        if (text.isEmpty()) return 0
        return text.toLongOrNull(OCTAL_RADIX) ?: invalid()
    }

    /** pax extended header records: `<length> <key>=<value>\n`, where length counts the whole record. */
    private fun readRecords(data: ByteArray): Map<String, String> {
        val records = mutableMapOf<String, String>()
        var offset = 0
        while (offset < data.size) {
            val space = (offset until data.size).firstOrNull { data[it] == ' '.code.toByte() } ?: invalid()
            val length = data.decodeToString(offset, space).toIntOrNull() ?: invalid()
            if (length <= space - offset || offset + length > data.size) invalid()
            val record = data.decodeToString(space + 1, offset + length).removeSuffix("\n")
            val separator = record.indexOf('=')
            if (separator <= 0) invalid()
            records[record.substring(0, separator)] = record.substring(separator + 1)
            offset += length
        }
        return records
    }

    private fun paddingOf(size: Long): Long = (BLOCK_SIZE - size % BLOCK_SIZE) % BLOCK_SIZE

    private fun invalid(): Nothing = throw installFailure(InstallFailureReason.InvalidArchive)

    private companion object {
        const val BLOCK_SIZE = 512
        const val BUFFER_BYTES = 64 * 1024
        const val MAX_EXTENSION_BYTES = 64 * 1024
        const val NAME_OFFSET = 0
        const val NAME_LENGTH = 100
        const val MODE_OFFSET = 100
        const val MODE_LENGTH = 8
        const val SIZE_OFFSET = 124
        const val SIZE_LENGTH = 12
        const val CHECKSUM_OFFSET = 148
        const val CHECKSUM_LENGTH = 8
        const val TYPE_OFFSET = 156
        const val MAGIC_OFFSET = 257
        const val MAGIC_LENGTH = 6
        const val PREFIX_OFFSET = 345
        const val PREFIX_LENGTH = 155
        const val SPACE = 0x20L
        const val OCTAL_RADIX = 8
        const val BASE256_FLAG = 0x80
        const val BASE256_MASK = 0x7F
        const val BYTE_MASK = 0xFF
        const val REGULAR = '0'
        const val REGULAR_OLD = '\u0000'
        const val CONTIGUOUS = '7'
        const val DIRECTORY = '5'
        const val PAX_HEADER = 'x'
        const val PAX_GLOBAL = 'g'
        const val GNU_LONG_NAME = 'L'
        const val GNU_LONG_LINK = 'K'
    }
}

/** One archive entry; [mode] holds the POSIX permission bits. */
internal data class TarEntry(val name: String, val type: TarEntryType, val size: Long, val mode: Int)

/** What an archive entry is. */
internal enum class TarEntryType { File, Directory, Special }
