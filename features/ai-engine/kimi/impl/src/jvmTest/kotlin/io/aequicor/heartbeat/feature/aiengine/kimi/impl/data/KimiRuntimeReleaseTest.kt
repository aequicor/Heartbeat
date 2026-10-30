package io.aequicor.heartbeat.feature.aiengine.kimi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubRelease
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KimiRuntimeReleaseTest {
    @Test
    fun `all five native targets are explicit and unsupported hosts never fall back`() {
        assertEquals("win32-x64", kimiRuntimeTarget("Windows 11", "amd64"))
        assertEquals("win32-arm64", kimiRuntimeTarget("Windows 11", "aarch64"))
        assertEquals("darwin-x64", kimiRuntimeTarget("Mac OS X", "x86_64"))
        assertEquals("darwin-arm64", kimiRuntimeTarget("Mac OS X", "arm64"))
        assertEquals("linux-x64", kimiRuntimeTarget("Linux", "amd64"))
        assertNull(kimiRuntimeTarget("Linux", "aarch64"))
        assertNull(kimiRuntimeTarget("Windows 11", "x86"))
        assertNull(kimiRuntimeTarget("Unknown", "x64"))
    }

    @Test
    fun `adapter resolves only its exact runtime from the Heartbeat release`() = runTest {
        val plan = kimiInstallPlan(feed(document()), "win32-x64")
        assertEquals(KIMI_RUNTIME_VERSION, plan.version)
        assertEquals("$KIMI_RELEASE_ROOT/kimi-$KIMI_RUNTIME_VERSION-win32-x64.zip", plan.url)
        assertEquals("a".repeat(64), plan.sha256)
        assertEquals("kimi.exe", plan.executable)
        assertTrue(plan.isRetainedForRollback)
    }

    @Test
    fun `wrong provenance gate version or archive cannot become an install plan`() = runTest {
        val valid = document()
        for (invalid in listOf(
            valid.replace(KIMI_SOURCE_COMMIT, "0".repeat(40)),
            valid.replace("\"permissionGate\":1", "\"permissionGate\":0"),
            valid.replace(KIMI_RUNTIME_VERSION, "9.9.9"),
            valid.replace("kimi-$KIMI_RUNTIME_VERSION-win32-x64.zip", "../runtime.zip"),
            valid.replace("a".repeat(64), "unverified"),
            valid.replace("\"size\":42", "\"size\":-1"),
            "not json",
        )) {
            assertFailsWith<ManagementException> { kimiInstallPlan(feed(invalid), "win32-x64") }
        }
    }

    private fun feed(document: String) = object : ReleaseFeeds {
        override suspend fun latestGitHubRelease(owner: String, repository: String): GitHubRelease =
            error("Pinned runtimes never resolve latest")
        override suspend fun document(url: String, allowedHosts: Set<String>): String {
            assertEquals("$KIMI_RELEASE_ROOT/runtime-manifest.json", url)
            return document
        }
    }

    private fun document(): String = """
        {"schemaVersion":1,"engine":"kimi","version":"$KIMI_RUNTIME_VERSION",
        "sourceCommit":"$KIMI_SOURCE_COMMIT","permissionGate":1,
        "platforms":{"win32-x64":{"file":"kimi-$KIMI_RUNTIME_VERSION-win32-x64.zip",
        "sha256":"${"a".repeat(64)}","size":42}}}
        """.trimIndent()
}
