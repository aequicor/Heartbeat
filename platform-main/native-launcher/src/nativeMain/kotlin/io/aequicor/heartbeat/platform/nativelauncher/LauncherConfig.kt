@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

package io.aequicor.heartbeat.platform.nativelauncher

import io.aequicor.heartbeat.platform.nativelauncher.bootstrap.NATIVE_LAUNCHER_VERSION
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ImageStore
import io.aequicor.heartbeat.platform.nativelauncher.provisioning.ProvisionedImage
import okio.Path
import okio.Path.Companion.toPath
import kotlin.native.OsFamily
import kotlin.native.Platform

/**
 * Конфигурация канала нативного лончера: адреса bootstrap-манифеста и подписи, доверенный release-ключ,
 * каталог данных. По умолчанию — release-канал GitHub Releases; для локальных проверок значения переопределяются
 * переменными окружения (тестовый HTTP-сервер, throwaway-ключ). Релизные ключи, версия и канал встраиваются
 * при сборке; после обновления тонкого пакета старый payload не удовлетворяет требуемой версии/архитектуре.
 */
data class LauncherConfig(
    val manifestUrl: String,
    val signatureUrl: String,
    val trustedKeys: List<ByteArray>,
    val dataDir: Path,
    val version: String,
    val target: String,
    val channel: String = BOOTSTRAP_CHANNEL,
) {
    val imageRoot: Path get() = checkNotNull(dataDir.parent).resolve("${dataDir.name}-launcher").resolve("bootstrap")

    /** Old payloads remain on disk but cannot satisfy a newer thin installer's required platform version. */
    fun currentImage(store: ImageStore) = store.currentImage()?.takeIf(::isCompatible)

    private fun isCompatible(image: ProvisionedImage): Boolean {
        if (image.minNativeLauncherVersion > NATIVE_LAUNCHER_VERSION) return false
        return image.platformVersion == version && image.platformTarget == target && image.platformChannel == channel
    }

    companion object {
        private const val DEFAULT_REPO = "aequicor/Heartbeat"

        fun load(): LauncherConfig {
            val version = env("HEARTBEAT_BOOTSTRAP_VERSION") ?: BOOTSTRAP_VERSION
            val target = launcherHostTarget()
            val defaultManifest = "https://github.com/$DEFAULT_REPO/releases/download/v$version/bootstrap-$target.json"
            val manifest = env("HEARTBEAT_BOOTSTRAP_MANIFEST_URL") ?: defaultManifest
            val signature = env("HEARTBEAT_BOOTSTRAP_SIGNATURE_URL") ?: "$manifest.sig"
            val keys = env("HEARTBEAT_BOOTSTRAP_PUBLIC_KEY")?.let { listOf(decodeBase64(it)) }
                ?: BOOTSTRAP_PUBLIC_KEYS.map(::decodeBase64)
            return LauncherConfig(manifest, signature, keys, dataDirectory(), version, target)
        }

        private fun dataDirectory(): Path {
            val defaultName = if (BOOTSTRAP_CHANNEL == "dev") "HeartbeatDev" else "Heartbeat"
            val dataName = env("HEARTBEAT_DATA_NAME") ?: defaultName
            require(
                dataName.isNotBlank() && dataName.none { it == '/' || it == '\\' } && dataName !in listOf(".", ".."),
            ) {
                "Invalid data directory name"
            }
            val directory = when (Platform.osFamily) {
                OsFamily.WINDOWS -> "${env("APPDATA") ?: error("APPDATA is not set")}/Aequicor/$dataName"

                OsFamily.MACOSX -> "${env("HOME") ?: error("HOME is not set")}/Library/Application Support/$dataName"

                OsFamily.LINUX -> {
                    val home = env("HOME") ?: error("HOME is not set")
                    val base = env("XDG_DATA_HOME")?.takeIf { it.startsWith('/') } ?: "$home/.local/share"
                    "$base/${dataName.lowercase()}"
                }

                else -> error("Unsupported launcher host")
            }
            return directory.toPath()
        }

        private fun env(name: String): String? = launcherEnvironment(name)?.takeIf { it.isNotBlank() }

        private const val BITS_PER_BASE64_DIGIT = 6
        private const val BITS_PER_BYTE = 8
        private const val BYTE_MASK = 0xff

        internal fun decodeBase64(value: String): ByteArray {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val clean = value.filterNot { it.isWhitespace() || it == '=' }
            val out = ArrayList<Byte>(clean.length * 3 / 4)
            var accumulator = 0
            var bits = 0
            for (c in clean) {
                val digit = alphabet.indexOf(c)
                require(digit >= 0) { "Invalid base64 in bootstrap public key" }
                accumulator = (accumulator shl BITS_PER_BASE64_DIGIT) or digit
                bits += BITS_PER_BASE64_DIGIT
                if (bits >= BITS_PER_BYTE) {
                    bits -= BITS_PER_BYTE
                    out += ((accumulator ushr bits) and BYTE_MASK).toByte()
                }
            }
            return out.toByteArray()
        }
    }
}

/** Runtime architecture can differ from the launcher executable, notably x64 emulation on Windows ARM64. */
internal expect fun launcherHostTarget(): String

/** Environment strings use the host Unicode API, including UTF-16 user-profile paths on Windows. */
internal expect fun launcherEnvironment(name: String): String?
