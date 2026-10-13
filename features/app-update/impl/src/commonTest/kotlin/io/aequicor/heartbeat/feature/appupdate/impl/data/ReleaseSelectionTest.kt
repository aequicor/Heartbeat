package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppVersion
import io.aequicor.heartbeat.feature.appupdate.impl.domain.GitHubRepository
import io.aequicor.heartbeat.feature.appupdate.impl.domain.ReleasePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleaseSelectionTest {
    private val base = "https://github.com/aequicor/Heartbeat/releases/download/v1.4.0"
    private val dmg = GitHubAsset("Heartbeat-1.4.0-macos-arm64.dmg", 10, "$base/Heartbeat-1.4.0-macos-arm64.dmg")
    private val release = GitHubRelease(
        tag = "v1.4.0",
        pageUrl = "https://github.com/aequicor/Heartbeat/releases/tag/v1.4.0",
        assets = listOf(dmg, GitHubAsset("SHA256SUMS", 1, "$base/SHA256SUMS")),
    )

    @Test
    fun `asset names follow the release workflow`() {
        val version = AppVersion(1, 4, 0)
        assertEquals("Heartbeat-1.4.0-macos-arm64.dmg", ReleasePlatform.MacOsArm64.assetName(version))
        assertEquals("Heartbeat-1.4.0-macos-x64.dmg", ReleasePlatform.MacOsX64.assetName(version))
        assertEquals("Heartbeat-1.4.0-windows-x64-setup.exe", ReleasePlatform.WindowsX64.assetName(version))
        assertEquals("Heartbeat-1.4.0-windows-arm64-setup.exe", ReleasePlatform.WindowsArm64.assetName(version))
    }

    @Test
    fun `only a newer app release tag is an update`() {
        assertEquals(AppVersion(1, 4, 0), release.versionNewerThan(AppVersion(1, 3, 9)))
        assertNull(release.versionNewerThan(AppVersion(1, 4, 0)))
        assertNull(release.versionNewerThan(AppVersion(2, 0, 0)))
        assertNull(release.copy(tag = "152.0.6-hb2").versionNewerThan(AppVersion(1, 0, 0)))
    }

    @Test
    fun `the build of the platform is found only on GitHub`() {
        val version = AppVersion(1, 4, 0)
        assertEquals(dmg, release.buildFor(version, ReleasePlatform.MacOsArm64))
        assertNull(release.buildFor(version, ReleasePlatform.WindowsX64))
        val elsewhere = release.copy(assets = listOf(dmg.copy(url = "https://example.com/a.dmg")))
        assertNull(elsewhere.buildFor(version, ReleasePlatform.MacOsArm64))
        assertNull(release.copy(assets = listOf(dmg, dmg)).buildFor(version, ReleasePlatform.MacOsArm64))
    }

    @Test
    fun `the digest of an asset is its SHA-256`() {
        val hex = "AB".repeat(32)
        assertEquals(hex.lowercase(), dmg.copy(digest = "sha256:$hex").sha256)
        assertNull(dmg.copy(digest = "sha512:$hex").sha256)
        assertNull(dmg.copy(digest = "sha256:abc").sha256)
        assertNull(dmg.sha256)
    }

    @Test
    fun `a checksum list names the asset exactly once`() {
        val hex = "cd".repeat(32)
        val sums = "$hex  Heartbeat-1.4.0-macos-arm64.dmg\n${"ef".repeat(32)} *Heartbeat-1.4.0-macos-x64.dmg\n"
        assertEquals(hex, sha256FromSums(sums, "Heartbeat-1.4.0-macos-arm64.dmg"))
        assertEquals("ef".repeat(32), sha256FromSums(sums, "Heartbeat-1.4.0-macos-x64.dmg"))
        assertNull(sha256FromSums(sums, "Heartbeat-1.4.0-windows-x64-setup.exe"))
        assertNull(sha256FromSums(sums + sums, "Heartbeat-1.4.0-macos-arm64.dmg"))
    }

    @Test
    fun `the offered release carries the verified build`() {
        val hex = "01".repeat(32)
        assertEquals(
            AppRelease("1.4.0", dmg.name, dmg.url, hex, 10, release.pageUrl),
            release.toAppRelease(AppVersion(1, 4, 0), dmg, hex),
        )
        val elsewhere = release.copy(pageUrl = "https://evil.test/x")
        assertEquals("", elsewhere.toAppRelease(AppVersion(1, 4, 0), dmg, hex).pageUrl)
    }

    @Test
    fun `repositories are owner and name`() {
        assertEquals(GitHubRepository("aequicor", "Heartbeat"), GitHubRepository.parse("aequicor/Heartbeat"))
        listOf("", "aequicor", "a/b/c", "a/..", "a b/c", "../x").forEach { assertNull(GitHubRepository.parse(it), it) }
    }
}
