package io.aequicor.heartbeat.feature.chromiumbridge.impl.data.host

import java.nio.file.Path

/**
 * Arguments of the native-messaging host: the app socket written into the launcher wrapper at registration,
 * followed by what Chrome appends — the calling extension origin and, on Windows, `--parent-window=<handle>`.
 */
internal data class HostArguments(val socket: Path, val origin: String) {
    companion object {
        private val ORIGIN = Regex("^chrome-extension://[a-p]{32}/$")
        private val PARENT_WINDOW = Regex("^--parent-window=\\d+$")

        /** @throws IllegalArgumentException when the wrapper or the browser passed unexpected arguments. */
        fun parse(arguments: List<String>): HostArguments {
            val socket = arguments.firstOrNull()?.let(Path::of)
            require(socket != null && socket.isAbsolute) { "Socket path must be absolute" }
            val callers = arguments.drop(1).filterNot(PARENT_WINDOW::matches)
            val origin = callers.singleOrNull()
            require(origin != null && ORIGIN.matches(origin)) { "Unexpected caller arguments count=${callers.size}" }
            return HostArguments(socket, origin)
        }
    }
}
