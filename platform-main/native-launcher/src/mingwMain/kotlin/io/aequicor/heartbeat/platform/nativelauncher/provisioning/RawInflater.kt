@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher.provisioning

import kotlinx.cinterop.Pinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.zlib.Z_NULL
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

/** Raw-deflate распаковка на zlib: сжатый поток ZIP, без zlib-заголовка (windowBits = -15). */
internal actual class RawInflater actual constructor() {
    private val stream = nativeHeap.alloc<z_stream>()
    private val inputs = mutableListOf<Pinned<ByteArray>>()
    private var isStreamEnd = false

    init {
        val state = inflateInit2_(stream.ptr, WINDOW_BITS, platform.zlib.ZLIB_VERSION, sizeOf<z_stream>().toInt())
        check(state == Z_OK) { "inflateInit2 failed: $state" }
    }

    actual fun input(data: ByteArray) {
        data.usePinned { pinned ->
            stream.next_in = pinned.addressOf(0).reinterpret()
            stream.avail_in = data.size.toUInt()
            inputs += pinned
        }
    }

    actual fun inflate(output: ByteArray): Int {
        output.usePinned { pinned ->
            stream.next_out = pinned.addressOf(0).reinterpret()
            stream.avail_out = output.size.toUInt()
            val result = inflate(stream.ptr, Z_NO_FLUSH)
            check(result == Z_OK || result == Z_STREAM_END || result == Z_BUF_ERROR) {
                "inflate failed: $result"
            }
            if (result == Z_STREAM_END) isStreamEnd = true
            if (stream.avail_in == 0u) {
                inputs.forEach { it.unpin() }
                inputs.clear()
            }
            return output.size - stream.avail_out.toInt()
        }
    }

    actual fun needsInput(): Boolean = stream.avail_in == 0u && !isStreamEnd

    actual fun finished(): Boolean = isStreamEnd

    actual fun end() {
        inputs.forEach { it.unpin() }
        inputs.clear()
        inflateEnd(stream.ptr)
        nativeHeap.free(stream.ptr.rawValue)
    }

    companion object {
        private const val WINDOW_BITS = -15
        private const val Z_NO_FLUSH = 0
        private const val Z_BUF_ERROR = -5
    }
}
