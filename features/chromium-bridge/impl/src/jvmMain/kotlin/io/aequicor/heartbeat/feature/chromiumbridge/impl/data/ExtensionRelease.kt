package io.aequicor.heartbeat.feature.chromiumbridge.impl.data

import io.aequicor.heartbeat.core.common.release.GitHubDownloadHosts
import io.aequicor.heartbeat.core.network.release.ReleaseArtifact
import io.aequicor.heartbeat.core.network.release.ReleaseDownload
import java.net.URI
import java.util.Properties

/** A lock shipped in app resources: the app chooses the publisher and bytes, never the extension or a server. */
internal data class ExtensionRelease(val version: String, val artifact: ReleaseArtifact) {
    companion object {
        fun read(text: String): ExtensionRelease {
            val properties = Properties().apply { text.reader().use(::load) }
            val version = properties.getProperty("version")
            val sha256 = properties.getProperty("sha256")
            val size = properties.getProperty("size")?.toLongOrNull()
            val url = URI(requireNotNull(properties.getProperty("url")))
            require(version != null && version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) {
                "Invalid extension component version"
            }
            require(sha256 != null && sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid extension checksum" }
            require(size != null && size in 1..MAX_ARCHIVE_BYTES) { "Invalid extension archive size" }
            require(
                url.scheme == "https" && url.host == "github.com" && url.port == -1 &&
                    url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null &&
                    url.rawPath.substringBeforeLast('/') == "/aequicor/Heartbeat/releases/download/v$version" &&
                    url.rawPath.substringAfterLast('/').matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.zip")),
            ) { "Invalid extension publisher URL" }
            return ExtensionRelease(
                version,
                ReleaseArtifact(
                    "chromium-extension-$version.zip",
                    ReleaseDownload(
                        url.toASCIIString(),
                        sha256,
                        size,
                        GitHubDownloadHosts,
                        maxBytes = MAX_ARCHIVE_BYTES,
                    ),
                ),
            )
        }
    }
}

private const val MAX_ARCHIVE_BYTES = 64L * 1024 * 1024
