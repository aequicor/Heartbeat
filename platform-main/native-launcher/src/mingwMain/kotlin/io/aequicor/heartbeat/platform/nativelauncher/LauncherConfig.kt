@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import kotlinx.cinterop.toKString
import okio.Path
import okio.Path.Companion.toPath
import platform.posix.getenv

/**
 * Конфигурация канала нативного лончера: адреса bootstrap-манифеста и подписи, доверенный release-ключ,
 * каталог данных. По умолчанию — release-канал GitHub Releases; для локальных проверок значения переопределяются
 * переменными окружения (тестовый HTTP-сервер, throwaway-ключ). Ключ по умолчанию не задан: без него лончер
 * не запускает provisioning (fail closed), а не принимает неподписанные артефакты.
 */
data class LauncherConfig(
    val manifestUrl: String,
    val signatureUrl: String,
    val trustedKeys: List<ByteArray>,
    val dataDir: Path,
) {
    val imageRoot: Path get() = dataDir.parent!!.resolve("${dataDir.name}-launcher").resolve("bootstrap")

    companion object {
        private const val DEFAULT_REPO = "aequicor/Heartbeat"
        private const val DEFAULT_MANIFEST =
            "https://github.com/$DEFAULT_REPO/releases/latest/download/bootstrap-windows-x64.json"
        private const val DEFAULT_SIGNATURE = "$DEFAULT_MANIFEST.sig"

        fun load(): LauncherConfig {
            val manifest = env("HEARTBEAT_BOOTSTRAP_MANIFEST_URL") ?: DEFAULT_MANIFEST
            val signature = env("HEARTBEAT_BOOTSTRAP_SIGNATURE_URL") ?: "$manifest.sig"
            val keys = env("HEARTBEAT_BOOTSTRAP_PUBLIC_KEY")?.let { key ->
                listOf(decodeBase64(key))
            } ?: emptyList()
            val dataName = env("HEARTBEAT_DATA_NAME") ?: "Heartbeat"
            val appData = env("APPDATA") ?: error("APPDATA is not set")
            return LauncherConfig(manifest, signature, keys, "$appData/Aequicor/$dataName".toPath())
        }

        private fun env(name: String): String? = getenv(name)?.toKString()?.takeIf { it.isNotBlank() }

        private fun decodeBase64(value: String): ByteArray {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val clean = value.filterNot { it.isWhitespace() || it == '=' }
            val out = ArrayList<Byte>(clean.length * 3 / 4)
            var accumulator = 0
            var bits = 0
            for (c in clean) {
                val digit = alphabet.indexOf(c)
                require(digit >= 0) { "Invalid base64 in bootstrap public key" }
                accumulator = (accumulator shl 6) or digit
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out += ((accumulator ushr bits) and 0xff).toByte()
                }
            }
            return out.toByteArray()
        }
    }
}
