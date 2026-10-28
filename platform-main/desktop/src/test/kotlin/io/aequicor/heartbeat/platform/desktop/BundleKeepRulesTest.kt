package io.aequicor.heartbeat.platform.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The bundle host calls the accessors of the `core:bundles:api` binding interfaces reflectively, so the shrunk train
 * must keep each of them: a new interface without a rule works in development and breaks the release.
 */
class BundleKeepRulesTest {
    @Test
    fun `every binding interface of core bundles api is kept in the release build`() {
        val rules = File("train-rules.pro").readText()
        val sources = File("../../core/bundles/api/src")
        val declared = sources.walk().filter { it.extension == "kt" }
            .flatMap { file -> INTERFACE.findAll(file.readText()).map { it.groupValues[1] } }
            .filter { it.startsWith("Foundation") || it.endsWith("Part") }
            .toList()
        assertTrue(declared.isNotEmpty(), "no binding interfaces found in ${sources.absolutePath}")
        val missing = declared.filter { "-keep interface io.aequicor.heartbeat.core.bundles.$it { *; }" !in rules }
        assertTrue(missing.isEmpty(), "train-rules.pro has no keep rule for $missing")
    }

    @Test
    fun `native bindings of the project are kept by the shape of their supertype`() {
        val rules = File("train-rules.pro").readText()
        // JNA finds natives by method name, fields by @FieldOrder and calls callbacks only from native code.
        listOf("Library", "Structure", "Callback").forEach { supertype ->
            val project = """io\.aequicor\.heartbeat\.\*\*"""
            val keep = Regex("-keep class $project (implements|extends) com\\.sun\\.jna\\.$supertype")
            assertTrue(
                keep.containsMatchIn(rules),
                "train-rules.pro does not keep the project's JNA $supertype subtypes",
            )
        }
    }

    @Test
    fun `serialization rules refer to the wildcard of the whole class name`() {
        val rules = File("train-rules.pro").readText()
        // <1> of the target is the part the first wildcard of the condition matched: a package prefix before it
        // would leave the target naming a class that does not exist.
        val conditions = rules.lines().filter { it.startsWith("-if @kotlinx.serialization.Serializable") }
        assertTrue(conditions.isNotEmpty(), "no serialization conditions")
        conditions.forEach { assertTrue(it.contains("class **"), "the condition must start with `class **`: $it") }
    }

    private companion object {
        val INTERFACE = Regex("""^public interface (\w+)""", RegexOption.MULTILINE)
    }
}
