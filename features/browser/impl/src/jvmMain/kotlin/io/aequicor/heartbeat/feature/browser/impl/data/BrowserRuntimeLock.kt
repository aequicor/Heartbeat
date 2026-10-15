package io.aequicor.heartbeat.feature.browser.impl.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Exact release identity compiled into the application, never replaced by remote latest-release metadata. */
internal data class BrowserRuntimeLock(
    val version: String,
    val platform: String,
    val releaseTag: String,
    val file: String,
    val sha256: String,
) {
    val archive: String get() = "jcef-natives-$platform-$releaseTag.tar.gz"
    val url: String get() = "https://github.com/aequicor/Heartbeat/releases/download/$version/$file"

    companion object {
        /** Rejects malformed or incompatible pins before touching the network or an existing installation. */
        fun read(source: String, releaseTag: String, platform: String): BrowserRuntimeLock {
            require(source.length <= MAX_LOCK_BYTES) { "Browser runtime lock is too large" }
            require(platform in SUPPORTED_PLATFORMS) { "Unsupported browser runtime platform" }
            val metadata = Json.parseToJsonElement(source).jsonObject
            val version = metadata.getValue("artifactVersion").jsonPrimitive.content
            val commit = metadata.getValue("jcefCommit").jsonPrimitive.content
            val cef = metadata.getValue("cefVersion").jsonPrimitive.content
            require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+-hb[0-9]+"))) { "Invalid browser runtime version" }
            require(commit.matches(Regex("[0-9a-f]{40}"))) { "Invalid browser runtime revision" }
            val expectedTag = "jcef-${commit.take(7)}+cef-$cef+heartbeat-${version.substringAfterLast('-')}"
            require(releaseTag == expectedTag) { "Browser runtime lock differs from its API" }
            val asset = metadata.getValue("nativeArtifacts").jsonObject.getValue(platform).jsonObject
            val file = asset.getValue("file").jsonPrimitive.content
            val sha256 = asset.getValue("sha256").jsonPrimitive.content
            require(file == "jcef-natives-$platform-$version.jar") { "Invalid browser runtime artifact" }
            require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid browser runtime checksum" }
            return BrowserRuntimeLock(version, platform, releaseTag, file, sha256)
        }
    }
}

private val SUPPORTED_PLATFORMS = setOf("macosx-amd64", "macosx-arm64", "windows-amd64", "windows-arm64", "linux-amd64")

private const val MAX_LOCK_BYTES = 32 * 1024
