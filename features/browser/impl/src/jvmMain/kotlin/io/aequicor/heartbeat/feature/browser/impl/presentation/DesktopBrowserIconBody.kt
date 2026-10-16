package io.aequicor.heartbeat.feature.browser.impl.presentation

/** A site icon's bytes, bounded as native callbacks arrive; only image (or untyped) responses are accepted. */
internal class DesktopBrowserIconBody(private val reject: (String) -> Unit) : DesktopBrowserResponseBody<ByteArray> {
    private var bytes = ByteArray(0)
    private var size = 0
    private var isRejected = false

    @Synchronized
    override fun progress(current: Int, total: Int) {
        if (current > MAX_FAVICON_BYTES || total > MAX_FAVICON_BYTES) fail()
    }

    @Synchronized
    override fun append(data: ByteArray, length: Int) {
        if (isRejected) return
        if (length !in 0..data.size || length > MAX_FAVICON_BYTES - size) {
            fail()
        } else {
            val required = size + length
            if (required > bytes.size) {
                bytes = bytes.copyOf(maxOf(required, minOf(bytes.size * 2, MAX_FAVICON_BYTES)))
            }
            data.copyInto(bytes, destinationOffset = size, endIndex = length)
            size = required
        }
    }

    @Synchronized
    override fun result(url: String, mime: String): ByteArray {
        check(!isRejected) { "Site icon exceeds size limit" }
        check(mime.isEmpty() || mime.startsWith("image/") || mime in UNTYPED) { "Site icon is not an image" }
        check(size > 0) { "Site icon is empty" }
        return bytes.copyOf(size)
    }

    private fun fail() {
        isRejected = true
        reject("Site icon exceeds size limit")
    }

    private companion object {
        val UNTYPED = setOf("application/octet-stream", "text/plain")
    }
}

/** Larger responses are not icons a tab can show. */
internal const val MAX_FAVICON_BYTES = 256 * 1024
