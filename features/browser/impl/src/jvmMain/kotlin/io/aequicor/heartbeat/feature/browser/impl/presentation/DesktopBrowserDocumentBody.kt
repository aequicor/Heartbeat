package io.aequicor.heartbeat.feature.browser.impl.presentation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64

/** Accumulates one managed response within its size limit and turns the final response into [T]. */
internal interface DesktopBrowserResponseBody<T> {
    fun progress(current: Int, total: Int)
    fun append(data: ByteArray, length: Int)
    fun result(url: String, mime: String): T
}

/** Bounds bytes as native callbacks arrive, before either JVM accumulation or attachment import. */
internal class DesktopBrowserDocumentBody(private val reject: (String) -> Unit) :
    DesktopBrowserResponseBody<JsonObject> {
    private var bytes = ByteArray(0)
    private var size = 0
    private var isRejected = false

    @Synchronized
    override fun progress(current: Int, total: Int) {
        if (current > MAX_BYTES || total > MAX_BYTES) fail()
    }

    @Synchronized
    override fun append(data: ByteArray, length: Int) {
        if (isRejected) return
        if (length !in 0..data.size || length > MAX_BYTES - size) {
            fail()
        } else {
            val required = size + length
            if (required > bytes.size) {
                bytes = bytes.copyOf(maxOf(required, (bytes.size * GROWTH_FACTOR).coerceAtMost(MAX_BYTES)))
            }
            data.copyInto(bytes, destinationOffset = size, endIndex = length)
            size = required
        }
    }

    @Synchronized
    override fun result(url: String, mime: String): JsonObject {
        check(!isRejected) { "Source document exceeds size limit" }
        if (mime in HTML_TYPES) {
            return buildJsonObject {
                put("kind", "html")
                put("url", url)
            }
        }
        val extension = checkNotNull(DOCUMENT_TYPES[mime]) { "Unsupported document type" }
        check(size > 0) { "Document is empty" }
        val content = bytes.copyOf(size)
        val hasSignature = content.take(PDF_SIGNATURE.length).toByteArray().decodeToString() == PDF_SIGNATURE
        check(mime != "application/pdf" || hasSignature) {
            "Invalid PDF content"
        }
        return buildJsonObject {
            put("kind", "document")
            put("url", url)
            put("mediaType", mime)
            put("extension", extension)
            put("contentBase64", Base64.getEncoder().encodeToString(content))
        }
    }

    private fun fail() {
        isRejected = true
        reject("Source document exceeds size limit")
    }

    private companion object {
        const val MAX_BYTES = 10_000_000
        const val GROWTH_FACTOR = 2
        const val PDF_SIGNATURE = "%PDF-"
        val HTML_TYPES = setOf("text/html", "application/xhtml+xml")
        val DOCUMENT_TYPES = mapOf("application/pdf" to "pdf", "text/plain" to "txt", "text/markdown" to "md")
    }
}
