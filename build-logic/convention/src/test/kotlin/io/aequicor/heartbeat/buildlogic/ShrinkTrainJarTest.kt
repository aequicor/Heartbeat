package io.aequicor.heartbeat.buildlogic

import org.gradle.api.GradleException
import proguard.Configuration
import proguard.ConfigurationParser
import proguard.ProGuard
import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The newest JDK whose class files ProGuard 7.8 reads. */
private const val MAX_PROGUARD_JDK = 25

class ShrinkTrainJarTest {
    private val pkg = "io/aequicor/heartbeat/buildlogic/sdkfixture"
    private val fixtureClasses = listOf("Api", "Api\$Companion", "HiddenClass", "PublishedClass", "Singleton", "Mode", "Contract", "FixtureKt")

    @Test
    fun `mode defaults to off and parses known values`() {
        assertEquals(TrainShrinkMode.Off, TrainShrinkMode.parse(null))
        assertEquals(TrainShrinkMode.Off, TrainShrinkMode.parse(" "))
        assertEquals(TrainShrinkMode.Off, TrainShrinkMode.parse("off"))
        assertEquals(TrainShrinkMode.Shrink, TrainShrinkMode.parse("Shrink"))
        assertFailsWith<GradleException> { TrainShrinkMode.parse("obfuscate") }
    }

    @Test
    fun `configuration reads inputs once and never silences warnings`() {
        val lines = trainProguardConfiguration(
            input = File("/in/foundation.jar"),
            output = File("/out/foundation.jar"),
            keptInputs = listOf(File("/in/contracts.jar")),
            scratchDirectory = File("/scratch"),
            libraryJars = listOf(File("/lib/a.jar")),
            jmods = listOf(File("/jdk/jmods/java.base.jmod")),
            rules = listOf(File("/rules/sdk.pro")),
            keptPackages = listOf("b.api", "a.api"),
            mapping = File("/out/mapping.txt"),
        )
        assertEquals("-injars \"/in/foundation.jar\"", lines[0])
        assertEquals("-outjars \"/out/foundation.jar\"", lines[1])
        assertEquals("-outjars \"/scratch/contracts.jar\"", lines[3])
        assertTrue("-libraryjars \"/jdk/jmods/java.base.jmod\"(!**.jar;!module-info.class)" in lines)
        assertTrue("-dontobfuscate" in lines && "-dontoptimize" in lines)
        assertTrue(lines.none { it.startsWith("-dontwarn") || it.startsWith("-ignorewarnings") }, lines.toString())
        assertTrue("-include \"/rules/sdk.pro\"" in lines)
        assertEquals(
            listOf("-keep,includedescriptorclasses class a.api.* { *; }", "-keep,includedescriptorclasses class b.api.* { *; }"),
            lines.takeLast(2),
        )
    }

    @Test
    fun `packages of a jar are listed sorted`() {
        val jar = fixtureJar(Files.createTempDirectory("packages").toFile(), "classes.jar")
        assertEquals(listOf("io.aequicor.heartbeat.buildlogic.sdkfixture"), jarPackages(jar))
    }

    @Test
    fun `normalized jars do not depend on entry times`() {
        val work = Files.createTempDirectory("normalize").toFile()
        val early = File(work, "early.jar")
        val late = File(work, "late.jar")
        for ((jar, time) in listOf(early to 1_000_000_000_000L, late to 1_700_000_000_000L)) {
            JarOutputStream(jar.outputStream()).use { out ->
                out.putNextEntry(ZipEntry("a.txt").apply { this.time = time })
                out.write("a".toByteArray())
                out.closeEntry()
            }
        }
        normalizeJar(early, File(work, "n1.jar"))
        normalizeJar(late, File(work, "n2.jar"))
        assertContentEquals(File(work, "n1.jar").readBytes(), File(work, "n2.jar").readBytes())
    }

    @Test
    fun `shrinking removes what is unused and is deterministic`() {
        val jmods = File(System.getProperty("java.home"), "jmods")
        // A JDK without jmods or newer than the class files ProGuard reads cannot be its library: nothing to verify here.
        if (!jmods.isDirectory || Runtime.version().feature() > MAX_PROGUARD_JDK) return
        val work = Files.createTempDirectory("shrink").toFile()
        val input = fixtureJar(work, "in.jar")
        val first = shrink(work, input, "first.jar")
        val second = shrink(work, input, "second.jar")
        assertContentEquals(first.readBytes(), second.readBytes())
        val names = ZipFile(first).use { zip -> zip.entries().asSequence().map { it.name }.toSet() }
        assertTrue("$pkg/Api.class" in names, names.toString())
        assertFalse("$pkg/HiddenClass.class" in names, names.toString())
    }

    private fun shrink(work: File, input: File, outputName: String): File {
        val output = File(work, outputName)
        val rules = File(work, "keep.pro").apply { writeText("-keep class io.aequicor.heartbeat.buildlogic.sdkfixture.Api { public *; }\n") }
        val stdlib = File(Unit::class.java.protectionDomain.codeSource.location.toURI())
        val jmods = File(System.getProperty("java.home"), "jmods").listFiles().orEmpty().filter { it.extension == "jmod" }
        val lines = trainProguardConfiguration(
            input = input,
            output = output,
            keptInputs = emptyList(),
            scratchDirectory = work,
            libraryJars = listOf(stdlib),
            jmods = jmods.sortedBy { it.name },
            rules = listOf(rules),
            keptPackages = emptyList(),
            mapping = File(work, "mapping-$outputName.txt"),
        )
        val configuration = File(work, "proguard-$outputName.pro").apply { writeText(lines.joinToString("\n")) }
        val parsed = Configuration()
        ConfigurationParser(arrayOf("@${configuration.absolutePath}"), Properties()).use { it.parse(parsed) }
        ProGuard(parsed).execute()
        return output
    }

    private fun fixtureJar(directory: File, name: String): File {
        val jar = File(directory, name)
        JarOutputStream(jar.outputStream()).use { out ->
            for (className in fixtureClasses.sorted()) {
                val entry = ZipEntry("$pkg/$className.class").apply { time = 0 }
                out.putNextEntry(entry)
                out.write(javaClass.classLoader.getResourceAsStream("$pkg/$className.class")!!.use { it.readBytes() })
                out.closeEntry()
            }
        }
        return jar
    }
}
