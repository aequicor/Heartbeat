package io.aequicor.heartbeat.platform.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The bundle host calls the accessors of the `core:bundles:api` binding interfaces reflectively, so the release
 * shrinker must keep each of them: a new interface without a rule works in development and breaks the release.
 */
class BundleKeepRulesTest {
    @Test
    fun `every binding interface of core bundles api is kept in the release build`() {
        val rules = File("compose-desktop.pro").readText()
        val sources = File("../../core/bundles/api/src")
        val declared = sources.walk().filter { it.extension == "kt" }
            .flatMap { file -> INTERFACE.findAll(file.readText()).map { it.groupValues[1] } }
            .filter { it.startsWith("Foundation") || it.endsWith("Part") }
            .toList()
        assertTrue(declared.isNotEmpty(), "no binding interfaces found in ${sources.absolutePath}")
        val missing = declared.filter { "-keep interface io.aequicor.heartbeat.core.bundles.$it { *; }" !in rules }
        assertTrue(missing.isEmpty(), "compose-desktop.pro has no keep rule for $missing")
    }

    private companion object {
        val INTERFACE = Regex("""^public interface (\w+)""", RegexOption.MULTILINE)
    }
}
