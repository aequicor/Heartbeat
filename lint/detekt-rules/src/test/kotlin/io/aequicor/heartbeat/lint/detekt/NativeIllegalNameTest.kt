package io.aequicor.heartbeat.lint.detekt

import dev.detekt.api.Config
import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import dev.detekt.test.utils.compileForTest
import org.jetbrains.kotlin.psi.KtFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NativeIllegalNameTest {
    private val rule = NativeIllegalName(Config.empty)

    @TempDir
    lateinit var root: Path

    @Test
    fun `reports a comma in a common test name`() {
        val findings = rule.lint(source("commonTest", "fun `loads, then saves`() = Unit"))

        assertEquals(1, findings.size)
        assertTrue("','" in findings.single().message)
    }

    @Test
    fun `reports every character the native compiler rejects`() {
        ",()$&~*?#|§%@{}".forEach { char ->
            assertEquals(1, rule.lint(source("commonTest", "fun `a${char}b`() = Unit")).size, "char $char")
        }
        // Rejected by the JVM backend too; still listed so jvm-less native modules stay covered.
        listOf(".", ";", ":", "/", "\\", "<", ">", "[", "]").forEach { char ->
            assertEquals(1, rule.lint(source("iosTest", "fun `a${char}b`() = Unit")).size, "char $char")
        }
    }

    @Test
    fun `accepts characters the native compiler allows`() {
        val code = "fun `loads - then saves 'draft' to \"store\" + retries = 3!`() = Unit"

        assertEquals(0, rule.lint(source("commonTest", code)).size)
    }

    @Test
    fun `checks every declaration kind`() {
        val code = """
            class `A,B`(val `p,q`: Int) {
                constructor() : this(0)
                val `x,y` = 1
                fun f(`a,b`: Int) = Unit
            }
            enum class E { `ONE,TWO` }
            typealias `T,U` = Int
        """.trimIndent()

        assertEquals(6, rule.lint(source("commonMain", code)).size)
    }

    @Test
    fun `checks every source set compiled by kotlin native`() {
        listOf(
            "commonMain",
            "commonTest",
            "nativeTest",
            "appleTest",
            "iosMain",
            "iosTest",
            "iosSimulatorArm64Test",
            "macosArm64Main",
            "linuxX64Test",
            "androidNativeArm64Main",
            "mobileMain",
        ).forEach { sourceSet ->
            assertEquals(1, rule.lint(source(sourceSet, "fun `a,b`() = Unit")).size, sourceSet)
        }
    }

    @Test
    fun `ignores source sets without kotlin native`() {
        listOf("jvmMain", "jvmTest", "androidMain", "androidUnitTest", "wasmJsMain", "okhttpMain", "main", "test")
            .forEach { sourceSet ->
                assertEquals(0, rule.lint(source(sourceSet, "fun `a,b`() = Unit")).size, sourceSet)
            }
        assertEquals(0, rule.lint("fun `a,b`() = Unit").size)
    }

    @Test
    fun `native source sets are configurable`() {
        val custom = NativeIllegalName(TestConfig("nativeSourceSets" to listOf("ios")))

        assertEquals(0, custom.lint(source("commonTest", "fun `a,b`() = Unit")).size)
        assertEquals(1, custom.lint(source("iosTest", "fun `a,b`() = Unit")).size)
    }

    private fun source(sourceSet: String, code: String): KtFile {
        val file = root.resolve("module/src/$sourceSet/kotlin/io/aequicor/heartbeat/Example.kt")
        Files.createDirectories(file.parent)
        Files.writeString(file, code)
        return compileForTest(file)
    }
}
