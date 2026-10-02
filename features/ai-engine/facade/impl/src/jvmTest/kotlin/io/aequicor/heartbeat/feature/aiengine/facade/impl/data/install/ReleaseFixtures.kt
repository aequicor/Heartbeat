package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install

import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.plugins.HttpTimeout
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A client configured like the app's: non-2xx responses throw, per-request timeouts are honoured. */
internal fun releaseClient(handler: MockRequestHandler): HttpClient = HttpClient(MockEngine(handler)) {
    expectSuccess = true
    install(HttpTimeout)
}

internal fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(
    MessageDigest.getInstance("SHA-256").digest(bytes),
)

/** Asserts that [block] fails with the installation failure [reason]. */
internal inline fun assertInstallFailure(reason: InstallFailureReason, block: () -> Unit) {
    val error = assertFailsWith<ManagementException> { block() }
    assertEquals(ManagementFailure.Install(reason), error.failure)
}

/** One tar entry written by [tarGz]; [type] is the tar type flag. */
internal class TarItem(
    val name: String,
    val data: ByteArray = ByteArray(0),
    val type: Char = '0',
    val mode: Int = 0b110_100_100,
    val link: String = "",
)

internal fun file(name: String, text: String, mode: Int = 0b110_100_100) = TarItem(
    name,
    text.encodeToByteArray(),
    mode = mode,
)

internal fun directory(name: String) = TarItem(name, type = '5', mode = 0b111_101_101)

/** A pax extended header naming the next entry [path]. */
internal fun paxPath(path: String): TarItem {
    val body = " path=$path\n"
    var length = body.length + 1
    while ((length.toString() + body).length != length) length = (length.toString() + body).length
    return TarItem("PaxHeader", (length.toString() + body).encodeToByteArray(), type = 'x')
}

/** A GNU long-name header naming the next entry [name]. */
internal fun gnuLongName(name: String) = TarItem("././@LongLink", (name + "\u0000").encodeToByteArray(), type = 'L')

internal fun tarGz(vararg items: TarItem): ByteArray {
    val tar = ByteArrayOutputStream()
    items.forEach { item ->
        tar.write(header(item))
        tar.write(item.data)
        tar.write(ByteArray((512 - item.data.size % 512) % 512))
    }
    tar.write(ByteArray(1024))
    return ByteArrayOutputStream().also { out ->
        GZIPOutputStream(
            out,
        ).use { it.write(tar.toByteArray()) }
    }.toByteArray()
}

internal fun header(item: TarItem): ByteArray {
    val header = ByteArray(512)
    fun put(offset: Int, text: String) = text.encodeToByteArray().copyInto(header, offset)
    fun octal(offset: Int, length: Int, value: Long) = put(offset, value.toString(8).padStart(length - 1, '0'))
    put(0, item.name.take(100))
    octal(100, 8, item.mode.toLong())
    octal(108, 8, 0)
    octal(116, 8, 0)
    octal(124, 12, item.data.size.toLong())
    octal(136, 12, 0)
    put(148, "        ")
    header[156] = item.type.code.toByte()
    put(157, item.link)
    put(257, "ustar")
    put(263, "00")
    val checksum = header.sumOf { it.toInt() and 0xFF }
    put(148, checksum.toString(8).padStart(6, '0'))
    header[154] = 0
    header[155] = ' '.code.toByte()
    return header
}

internal fun zip(vararg entries: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { out ->
    ZipOutputStream(out).use { zip ->
        entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name))
            if (!name.endsWith('/')) zip.write(text.encodeToByteArray())
            zip.closeEntry()
        }
    }
}.toByteArray()
