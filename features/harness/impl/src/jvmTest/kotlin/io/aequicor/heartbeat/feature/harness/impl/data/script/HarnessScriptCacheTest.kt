package io.aequicor.heartbeat.feature.harness.impl.data.script

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.ItemId
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCodeKind
import io.aequicor.heartbeat.feature.harness.impl.domain.script.HarnessCompilationRequest
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessScriptCacheTest {
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun `sweep removes only cached entries of items outside the retained set`() {
        val directory = createTempDirectory("harness-cache")
        try {
            val cache = HarnessScriptCache(directory)
            val kept = cache.itemKey(request("kept"))
            val orphan = cache.itemKey(request("removed"))
            val files = listOf("$kept.jar", "$kept.json", "$orphan.jar", "$orphan.json", "compiler-1.jar", "notes.json")
            files.forEach { directory.resolve(it).writeText("x") }
            assertEquals(2, cache.retainOnly(setOf(kept)))
            assertTrue(directory.resolve("$kept.jar").exists())
            assertTrue(directory.resolve("$kept.json").exists())
            assertFalse(directory.resolve("$orphan.jar").exists())
            assertFalse(directory.resolve("$orphan.json").exists())
            assertTrue(directory.resolve("compiler-1.jar").exists())
            assertTrue(directory.resolve("notes.json").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `sweep of a missing directory removes nothing`() {
        val directory = createTempDirectory("harness-cache")
        Files.delete(directory)
        assertEquals(0, HarnessScriptCache(directory).retainOnly(emptySet()))
    }

    private fun request(item: String) = HarnessCompilationRequest(
        HarnessId("h"),
        ItemId(item),
        HarnessCodeKind.Script,
        "",
    )
}
