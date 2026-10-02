package io.aequicor.heartbeat.feature.aiengine.facade.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ArchiveKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubDownloadHosts
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubRelease
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.InstallPlan
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LaunchContext
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.LoginPrompt
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.hostOf
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.isTrustedReleaseUrl
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.sha256FromSums
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ReleaseFeedsTest {
    private val digest = "4f8d288b78c9768d3a4ac6f61f06cd34394b82ac17d5b42d1e44a437add401b7"
    private val url = "https://github.com/earendil-works/pi/releases/download/v0.99.2/pi-darwin-arm64.tar.gz"

    @Test
    fun `a GitHub release reports the checksum GitHub states for each asset`() {
        val json = """
            {
              "tag_name": "v0.99.2", "draft": false, "prerelease": false, "name": "ignored",
              "assets": [
                {"name": "pi-darwin-arm64.tar.gz", "size": 61234567, "browser_download_url": "$url",
                 "digest": "sha256:${digest.uppercase()}", "content_type": "application/gzip"},
                {"name": "SHA256SUMS", "size": 512, "browser_download_url": "https://github.com/x/SHA256SUMS"},
                {"name": "odd.bin", "size": 1, "browser_download_url": "https://github.com/x/odd", "digest": "md5:abc"}
              ]
            }
        """.trimIndent()

        val release = Json { ignoreUnknownKeys = true }.decodeFromString(GitHubRelease.serializer(), json)

        assertEquals("v0.99.2", release.tag)
        assertEquals(digest, release.asset("pi-darwin-arm64.tar.gz")?.sha256)
        assertNull(release.asset("SHA256SUMS")?.sha256)
        assertNull(release.asset("odd.bin")?.sha256)
        assertNull(release.asset("missing"))
    }

    @Test
    fun `checksum lists name each asset exactly once`() {
        val sums = """
            $digest  pi-darwin-arm64.tar.gz
            ${"a".repeat(64)} *pi-windows-x64.zip
            ${"b".repeat(64)}  ./pi-linux-x64.tar.gz
            ${"c".repeat(64)}  twice.zip
            ${"d".repeat(64)}  twice.zip
            not-a-hash  broken.zip
        """.trimIndent()

        assertEquals(digest, sha256FromSums(sums, "pi-darwin-arm64.tar.gz"))
        assertEquals("a".repeat(64), sha256FromSums(sums, "pi-windows-x64.zip"))
        assertEquals("b".repeat(64), sha256FromSums(sums, "pi-linux-x64.tar.gz"))
        assertNull(sha256FromSums(sums, "twice.zip"))
        assertNull(sha256FromSums(sums, "broken.zip"))
        assertNull(sha256FromSums(sums, "pi-darwin-arm64"))
    }

    @Test
    fun `an install plan accepts only https files on its hosts with a stated checksum`() {
        val plan = InstallPlan("0.99.2", url, digest, 61_234_567, ArchiveKind.TarGz(1), "pi", GitHubDownloadHosts)
        assertEquals("pi", plan.executable)

        val invalid = listOf(
            { plan.copy(url = url.replace("https://", "http://")) },
            { plan.copy(url = "https://evil.example.com/pi.tar.gz") },
            { plan.copy(sha256 = digest.uppercase()) },
            { plan.copy(sha256 = "abc") },
            { plan.copy(executable = "../pi") },
            { plan.copy(executable = "/usr/bin/pi") },
            { plan.copy(executable = "bin\\pi.exe") },
            { plan.copy(executable = "C:pi.exe") },
            { plan.copy(version = "../1.0") },
            { plan.copy(size = 0) },
            { plan.copy(archive = ArchiveKind.Raw("bin/claude")) },
        )
        invalid.forEachIndexed { index, build -> assertFailsWith<IllegalArgumentException>("case $index") { build() } }
    }

    @Test
    fun `hosts are read without ports or paths and ambiguous authorities have none`() {
        assertEquals("github.com", hostOf(url))
        assertEquals("downloads.claude.ai", hostOf("https://Downloads.Claude.AI:443/claude-code-releases/stable"))
        assertEquals("", hostOf("https://github.com@evil.example.com/file"))
        assertEquals("", hostOf("https://evil.example\\@auth.openai.com/x"))
        assertEquals("", hostOf("https://auth.openai.com%2eevil.example/x"))
        assertEquals("", hostOf("not a url"))
        assertFalse(isTrustedReleaseUrl("http://github.com/file", setOf("github.com")))
    }

    @Test
    fun `launch contexts and sign-in prompts never print their values`() {
        val printed = listOf(
            LaunchContext(LaunchSettings(executable = "/Users/me/bin/codex")),
            LoginPrompt.OpenUrl("https://auth.openai.com/oauth?state=s3cr3t", "WXYZ-1234"),
            LoginPrompt.PasteCode("https://claude.ai/oauth?code_challenge=c4ll"),
        ).joinToString()

        listOf("/Users/me", "s3cr3t", "WXYZ", "c4ll").forEach { assertFalse(it in printed, it) }
    }
}
