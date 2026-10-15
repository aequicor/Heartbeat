package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.common.release.GitHubDownloadHosts
import io.aequicor.heartbeat.core.network.release.ReleaseArtifact
import io.aequicor.heartbeat.core.network.release.ReleaseDownload
import java.io.InputStream
import java.util.Properties

/** The exact standalone Pi approved by this Heartbeat build; no release metadata request chooses its identity. */
internal data class PiRuntimeRelease(val version: String, val target: String, val artifact: ReleaseArtifact) {
    val isZip: Boolean get() = target.startsWith("windows-")
    val executable: String get() = if (isZip) "pi.exe" else "pi"
}

/** Reads build-generated pins without installing anything; unsupported hosts have no pin. */
internal fun readPiRuntimeRelease(input: InputStream?, target: String? = piReleaseTarget()): PiRuntimeRelease? {
    if (input == null) return null
    val properties = input.use { Properties().apply { load(it) } }
    val version = properties.getProperty("version")
    val platform = properties.getProperty("target")
    require(version != null && version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Invalid Pi runtime version" }
    require(platform != null && platform == target) { "Pi runtime pin is for another platform" }
    val asset = "pi-$platform." + if (platform.startsWith("windows-")) "zip" else "tar.gz"
    require(properties.getProperty("asset") == asset) { "Pi runtime asset differs" }
    val url = "https://github.com/earendil-works/pi/releases/download/v$version/$asset"
    require(properties.getProperty("url") == url) { "Pi runtime source differs" }
    val sha256 = properties.getProperty("sha256")
    require(sha256 != null && sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid Pi runtime digest" }
    return PiRuntimeRelease(
        version,
        platform,
        ReleaseArtifact(asset, ReleaseDownload(url, sha256, null, GitHubDownloadHosts)),
    )
}
